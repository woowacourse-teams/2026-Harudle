/** @jest-environment node */

import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import * as auth from '../../shared/auth';
import { RequestError } from '../../shared/api';
import {
  REPORT_ERRORS,
  submitReport,
  type ReportRequest,
} from './reportSubmission';

const request: ReportRequest = {
  targetType: 'FEED',
  targetId: '00000000-0000-4000-8000-000000000001',
  reason: 'SPAM',
};
const receipt = {
  id: '10000000-0000-4000-8000-000000000001',
  status: 'RECEIVED',
  duplicate: false,
} as const;

beforeEach((): void => {
  jest.restoreAllMocks();
  jest.spyOn(auth, 'requestCsrfToken').mockResolvedValue('csrf-token');
});

describe('신고 접수 API 경계', (): void => {
  it('CSRF를 발급받아 인증 요청을 보내고 최초 접수를 반환한다', async (): Promise<void> => {
    const fetch = jest
      .spyOn(auth, 'authFetch')
      .mockResolvedValue(Response.json(receipt, { status: 201 }));
    await expect(
      submitReport({ ...request, description: '  광고가 반복돼요.  ' }),
    ).resolves.toEqual(receipt);
    expect(fetch).toHaveBeenCalledWith('/api/v1/reports', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': 'csrf-token',
      },
      body: JSON.stringify({ ...request, description: '광고가 반복돼요.' }),
    });
  });

  it('선택 상세가 공백뿐이면 생략하고 중복 접수를 구분한다', async (): Promise<void> => {
    const duplicate = { ...receipt, duplicate: true };
    const fetch = jest
      .spyOn(auth, 'authFetch')
      .mockResolvedValue(Response.json(duplicate));
    await expect(
      submitReport({ ...request, description: '  ' }),
    ).resolves.toEqual(duplicate);
    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/reports',
      expect.objectContaining({ body: JSON.stringify(request) }),
    );
  });

  it('500 code point를 허용하고 초과 입력은 요청 전에 거절한다', async (): Promise<void> => {
    const fetch = jest
      .spyOn(auth, 'authFetch')
      .mockResolvedValue(Response.json(receipt, { status: 201 }));
    await submitReport({ ...request, description: '😀'.repeat(500) });
    fetch.mockClear();
    jest.mocked(auth.requestCsrfToken).mockClear();
    await expect(
      submitReport({ ...request, description: '😀'.repeat(501) }),
    ).rejects.toThrow(REPORT_ERRORS.invalid);
    expect(fetch).not.toHaveBeenCalled();
    expect(auth.requestCsrfToken).not.toHaveBeenCalled();
  });

  it('CSRF 발급이 실패하면 신고 요청을 보내지 않는다', async (): Promise<void> => {
    const failure = new Error('CSRF 발급 실패');
    jest.mocked(auth.requestCsrfToken).mockRejectedValue(failure);
    const fetch = jest.spyOn(auth, 'authFetch');
    await expect(submitReport(request)).rejects.toBe(failure);
    expect(fetch).not.toHaveBeenCalled();
  });

  it.each([
    { status: 200, body: receipt },
    { status: 201, body: { ...receipt, duplicate: true } },
    { status: 201, body: { ...receipt, id: 'invalid' } },
    { status: 201, body: { ...receipt, status: 'RESOLVED' } },
    { status: 201, body: null },
  ])(
    '접수 여부가 불명확한 응답은 성공으로 안내하지 않는다: %j',
    async ({ status, body }): Promise<void> => {
      jest
        .spyOn(auth, 'authFetch')
        .mockResolvedValue(Response.json(body, { status }));
      await expect(submitReport(request)).rejects.toThrow(
        REPORT_ERRORS.response,
      );
    },
  );

  it.each([
    { status: 400, code: 'INVALID_REPORT' },
    { status: 403, code: 'INVALID_CSRF_TOKEN' },
    { status: 404, code: 'REPORT_TARGET_NOT_FOUND' },
  ] as const)(
    'Problem Details 오류를 보존한다: $code',
    async ({ status, code }): Promise<void> => {
      const problem = {
        type: 'about:blank',
        title: '접수 실패',
        status,
        code,
        detail: '신고 대상을 확인해 주세요.',
        instance: '/api/v1/reports',
      };
      jest
        .spyOn(auth, 'authFetch')
        .mockImplementation(async (): Promise<Response> =>
          Response.json(problem, { status }),
        );
      await expect(submitReport(request)).rejects.toEqual(
        new RequestError(problem),
      );
    },
  );

  it('확인된 CSRF 거절은 새 토큰으로 같은 신고를 한 번 재시도한다', async (): Promise<void> => {
    jest
      .mocked(auth.requestCsrfToken)
      .mockResolvedValueOnce('csrf-old')
      .mockResolvedValueOnce('csrf-new');
    const problem = {
      type: 'about:blank',
      title: 'CSRF 거절',
      status: 403,
      code: 'INVALID_CSRF_TOKEN',
      detail: '다시 시도해 주세요.',
      instance: '/api/v1/reports',
    };
    const fetch = jest
      .spyOn(auth, 'authFetch')
      .mockResolvedValueOnce(Response.json(problem, { status: 403 }))
      .mockResolvedValueOnce(Response.json(receipt, { status: 201 }));
    await expect(submitReport(request)).resolves.toEqual(receipt);
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(jest.mocked(auth.requestCsrfToken)).toHaveBeenCalledTimes(2);
    expect(
      fetch.mock.calls.map(([, init]): string | null =>
        new Headers(init?.headers).get('X-XSRF-TOKEN'),
      ),
    ).toEqual(['csrf-old', 'csrf-new']);
    expect(fetch.mock.calls.map(([, init]): unknown => init?.body)).toEqual([
      JSON.stringify(request),
      JSON.stringify(request),
    ]);
  });

  it('두 번째 CSRF 거절은 추가 재시도 없이 오류로 반환한다', async (): Promise<void> => {
    const problem = {
      type: 'about:blank',
      title: 'CSRF 거절',
      status: 403,
      code: 'INVALID_CSRF_TOKEN',
      detail: '다시 시도해 주세요.',
      instance: '/api/v1/reports',
    };
    const fetch = jest
      .spyOn(auth, 'authFetch')
      .mockImplementation(async (): Promise<Response> =>
        Response.json(problem, { status: 403 }),
      );
    await expect(submitReport(request)).rejects.toThrow(problem.detail);
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(auth.requestCsrfToken).toHaveBeenCalledTimes(2);
  });

  it.each([
    { httpStatus: 403, status: 403, code: 'FORBIDDEN' },
    { httpStatus: 401, status: 401, code: 'UNAUTHORIZED' },
    { httpStatus: 503, status: 503, code: 'SERVICE_UNAVAILABLE' },
    { httpStatus: 403, status: 400, code: 'INVALID_CSRF_TOKEN' },
    { httpStatus: 400, status: 400, code: 'INVALID_CSRF_TOKEN' },
  ])(
    'CSRF 거절이 아닌 오류는 다시 제출하지 않는다: %j',
    async ({ httpStatus, status, code }): Promise<void> => {
      const problem = {
        type: 'about:blank',
        title: '접수 실패',
        status,
        code,
        detail: '접수할 수 없어요.',
        instance: '/api/v1/reports',
      };
      const fetch = jest
        .spyOn(auth, 'authFetch')
        .mockResolvedValue(Response.json(problem, { status: httpStatus }));
      await expect(submitReport(request)).rejects.toThrow(problem.detail);
      expect(fetch).toHaveBeenCalledTimes(1);
      expect(auth.requestCsrfToken).toHaveBeenCalledTimes(1);
    },
  );

  it('재시도를 위한 CSRF 발급 실패는 신고를 다시 보내지 않는다', async (): Promise<void> => {
    const failure = new Error('CSRF 재발급 실패');
    jest
      .mocked(auth.requestCsrfToken)
      .mockResolvedValueOnce('csrf-old')
      .mockRejectedValueOnce(failure);
    const fetch = jest.spyOn(auth, 'authFetch').mockResolvedValue(
      Response.json(
        {
          type: 'about:blank',
          title: 'CSRF 거절',
          status: 403,
          code: 'INVALID_CSRF_TOKEN',
          detail: '다시 시도해 주세요.',
          instance: '/api/v1/reports',
        },
        { status: 403 },
      ),
    );
    await expect(submitReport(request)).rejects.toBe(failure);
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('JSON이 아닌 실패 응답에는 고정 안내를 사용한다', async (): Promise<void> => {
    jest
      .spyOn(auth, 'authFetch')
      .mockResolvedValue(new Response('<html>오류</html>', { status: 500 }));
    await expect(submitReport(request)).rejects.toThrow(REPORT_ERRORS.failed);
  });
});
