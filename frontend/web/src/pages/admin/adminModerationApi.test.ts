/** @jest-environment node */
import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { adminModerationHandlers } from '../../mocks/adminModerationHandlers';
import { authFetch, requestCsrfToken } from '../../shared/auth';
import {
  getAdminReport,
  getAdminReports,
  getAdminModeration,
  getAdminModerationHistory,
  setAdminModeration,
  reviewAdminReport,
  isAdminReportDetail,
  isAdminPageResponse,
  isAdminReportSummary,
  isModerationState,
} from './adminModerationApi';
jest.mock('../../shared/auth', () => ({
  authFetch: jest.fn(),
  requestCsrfToken: jest.fn(),
}));
jest.mock('../../assets/images/diary-four-panel.webp', () => 'diary.webp');
jest.mock('msw', () => {
  class MockResponse extends Response {
    static json(data: unknown, init: ResponseInit = {}): Response {
      return new MockResponse(JSON.stringify(data), init);
    }
  }
  const handler = (method: string) => (path: string, resolver: unknown) => ({
    method,
    path,
    resolver,
  });
  return {
    delay: (): Promise<void> => Promise.resolve(),
    http: { get: handler('GET'), put: handler('PUT'), patch: handler('PATCH') },
    HttpResponse: MockResponse,
  };
});
interface TestHandler {
  readonly method: string;
  readonly path: string;
  readonly resolver: (args: {
    request: Request;
    params: Record<string, string>;
  }) => Promise<Response>;
}
// MSW 모킹이 위에서 생성한 등록 구조를 HTTP 경계와 함께 실행한다.
const handlers = adminModerationHandlers as unknown as TestHandler[];
const reportId = '40000000-0000-4000-8000-000000000001';
const target = '00000000-0000-4000-8000-000000000002';
beforeEach((): void => {
  jest.mocked(authFetch).mockReset();
  jest
    .mocked(requestCsrfToken)
    .mockReset()
    .mockResolvedValue('mock-csrf-token');
  jest
    .mocked(authFetch)
    .mockImplementation(async (input, init): Promise<Response> => {
      const request = new Request(new URL(String(input), 'http://localhost'), {
        ...init,
        headers: {
          ...Object.fromEntries(new Headers(init?.headers)),
          Authorization: 'Bearer mock-access-token',
        },
      });
      for (const handler of handlers) {
        if (handler.method !== request.method) continue;
        const keys = Array.from(
          handler.path.matchAll(/:([A-Za-z]+)/g),
          (match): string => match[1],
        );
        const match = new URL(request.url).pathname.match(
          new RegExp(`^${handler.path.replace(/:[A-Za-z]+/g, '([^/]+)')}$`),
        );
        if (match)
          return handler.resolver({
            request,
            params: Object.fromEntries(
              keys.map((key, index): [string, string] => [
                key,
                match[index + 1],
              ]),
            ),
          });
      }
      throw new Error('관리자 MSW 경로가 없습니다.');
    });
});
describe('관리자 신고·조치 API 계약', (): void => {
  it('MSW 목록·상세를 읽고 신고를 종결하며 같은 종결은 최초 사유를 유지한다', async (): Promise<void> => {
    const list = await getAdminReports('RECEIVED');
    expect(list.content.map((item): string => item.id)).toContain(reportId);
    const detail = await getAdminReport(reportId);
    expect(detail.report.status).toBe('RECEIVED');
    expect(detail.target.authorNickname).toBe('네오');
    const receipt = await reviewAdminReport(
      reportId,
      'RESOLVED',
      '  정책 확인 완료  ',
    );
    expect(receipt.reviewReason).toBe('정책 확인 완료');
    expect(
      await reviewAdminReport(reportId, 'RESOLVED', '정책 확인 완료'),
    ).toEqual(receipt);
    await expect(
      reviewAdminReport(reportId, 'RESOLVED', '새 사유'),
    ).rejects.toThrow();
    expect(
      (await getAdminReports('RESOLVED')).content.map(
        (item): string => item.id,
      ),
    ).toContain(reportId);
    await expect(
      reviewAdminReport(reportId, 'DISMISSED', '다른 처리'),
    ).rejects.toThrow();
  });
  it('버전 기반 숨김과 해제를 수행하고 이력을 남기며 오래된 버전은 자동 재전송하지 않는다', async (): Promise<void> => {
    const initial = await getAdminModeration('FEED', target);
    const hidden = await setAdminModeration(
      'FEED',
      target,
      true,
      initial.version,
      '광고 콘텐츠',
    );
    expect(hidden.restricted).toBe(true);
    // 응답 유실 후 동일 요청을 다시 보내도 버전과 감사 이력을 늘리지 않는다.
    const replayed = await setAdminModeration(
      'FEED',
      target,
      true,
      initial.version,
      '광고 콘텐츠',
    );
    expect(replayed).toEqual(hidden);
    const before = jest.mocked(authFetch).mock.calls.length;
    await expect(
      setAdminModeration('FEED', target, false, initial.version, '오래된 화면'),
    ).rejects.toThrow();
    expect(jest.mocked(authFetch).mock.calls.length).toBe(before + 1);
    const restored = await setAdminModeration(
      'FEED',
      target,
      false,
      hidden.version,
      '조치 해제',
    );
    expect(restored.version).toBe(hidden.version + 1);
    await expect(
      setAdminModeration('FEED', target, true, initial.version, '광고 콘텐츠'),
    ).rejects.toThrow();
    const history = await getAdminModerationHistory('FEED', target);
    expect(history.content.map((item): boolean => item.restricted)).toEqual([
      false,
      true,
    ]);
  });
  it('사유 검증은 요청 전에 수행하고 API가 거절한 권한 오류를 전달한다', async (): Promise<void> => {
    await expect(
      reviewAdminReport(reportId, 'RESOLVED', '   '),
    ).rejects.toThrow('1~500');
    await expect(
      setAdminModeration('USER', target, true, 0, '가'.repeat(501)),
    ).rejects.toThrow('1~500');
    expect(authFetch).not.toHaveBeenCalled();
    jest.mocked(authFetch).mockResolvedValueOnce(
      Response.json(
        {
          type: 'urn:forbidden',
          title: 'Forbidden',
          status: 403,
          code: 'FORBIDDEN',
          detail: '관리자만 사용할 수 있어요.',
          instance: '/api/v1/admin/reports',
        },
        { status: 403 },
      ),
    );
    await expect(getAdminReports('RECEIVED')).rejects.toThrow('관리자만');
  });
  it('정상 응답의 필수 중첩 필드·상태·페이지·날짜를 검증한다', async (): Promise<void> => {
    expect(isAdminReportDetail({ report: {}, target: {} })).toBe(false);
    expect(
      isAdminPageResponse(
        {
          content: [],
          page: -1,
          size: 20,
          totalElements: 0,
          totalPages: 0,
          hasNext: false,
        },
        isAdminReportSummary,
      ),
    ).toBe(false);
    expect(
      isModerationState({
        targetType: 'USER',
        targetId: target,
        restricted: 'true',
        version: 0,
        reason: null,
        actorId: null,
        changedAt: null,
      }),
    ).toBe(false);
    jest.mocked(authFetch).mockResolvedValueOnce(
      Response.json({
        content: [{ status: 'UNKNOWN' }],
        page: 0,
        size: 20,
        totalElements: 1,
        totalPages: 1,
        hasNext: false,
      }),
    );
    await expect(getAdminReports('RECEIVED')).rejects.toThrow('응답');
  });
  it('조치 요청의 CSRF·버전·사유와 조회 취소 신호를 보존한다', async (): Promise<void> => {
    const controller = new AbortController();
    await getAdminModeration('USER', target, controller.signal);
    expect(authFetch).toHaveBeenCalledWith(
      `/api/v1/admin/moderation/USER/${target}`,
      { signal: controller.signal },
    );
    await setAdminModeration('USER', target, true, 0, '  반복 위반  ');
    const init = jest.mocked(authFetch).mock.calls.at(-1)?.[1];
    expect(init).toMatchObject({
      method: 'PUT',
      credentials: 'include',
      body: JSON.stringify({
        restricted: true,
        expectedVersion: 0,
        reason: '반복 위반',
      }),
    });
    expect(new Headers(init?.headers).get('X-XSRF-TOKEN')).toBe(
      'mock-csrf-token',
    );
  });
  it('처리자 연결이 삭제된 정상 이력과 종결 재응답을 보존하고 잘못된 타입은 거절한다', async (): Promise<void> => {
    const event = {
      id: 'removed-admin-event',
      targetType: 'FEED',
      targetId: target,
      restricted: true,
      version: 1,
      reason: '콘텐츠 위반',
      actorId: null,
      changedAt: '2026-10-23T03:00:00Z',
    };
    const historyPage = {
      content: [event],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      hasNext: false,
    };
    jest.mocked(authFetch).mockResolvedValueOnce(Response.json(historyPage));
    const history = await getAdminModerationHistory('FEED', target);
    expect(history.content[0]).toMatchObject({
      actorId: null,
      reason: '콘텐츠 위반',
      version: 1,
    });
    jest.mocked(authFetch).mockResolvedValueOnce(
      Response.json({
        id: reportId,
        status: 'RESOLVED',
        reviewedBy: null,
        reviewedAt: '2026-10-23T03:00:00Z',
        reviewReason: '최초 처리 사유',
      }),
    );
    await expect(
      reviewAdminReport(reportId, 'RESOLVED', '최초 처리 사유'),
    ).resolves.toMatchObject({
      reviewedBy: null,
      reviewReason: '최초 처리 사유',
    });
    jest
      .mocked(authFetch)
      .mockResolvedValueOnce(
        Response.json({ ...historyPage, content: [{ ...event, actorId: 42 }] }),
      );
    await expect(getAdminModerationHistory('FEED', target)).rejects.toThrow(
      '응답',
    );
  });
});
