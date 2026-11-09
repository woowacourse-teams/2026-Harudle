/** @jest-environment node */

import {
  beforeEach,
  afterEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import * as auth from '../shared/auth';
import {
  submitReport,
  type ReportRequest,
} from '../domain/reports/reportSubmission';
import { reportHandlers, resetReportMockState } from './reportHandlers';

jest.mock('msw', () => {
  class MockHttpResponse extends Response {
    static json(data: unknown, init: ResponseInit = {}): MockHttpResponse {
      return new MockHttpResponse(JSON.stringify(data), init);
    }
  }
  return {
    delay: (): Promise<void> => Promise.resolve(),
    http: {
      post: (path: string, resolver: unknown): object => ({ path, resolver }),
    },
    HttpResponse: MockHttpResponse,
  };
});

interface AuthorizationCase {
  readonly headers: HeadersInit;
  readonly status: number;
  readonly code: string;
}

interface MockHandler {
  readonly resolver: (args: { readonly request: Request }) => Promise<Response>;
}
// MSW factory의 테스트 대역에만 존재하는 resolver를 호출한다.
const handlers = reportHandlers as unknown as readonly MockHandler[];
const mutationHeaders = {
  Authorization: 'Bearer mock-access-token',
  'X-XSRF-TOKEN': 'mock-csrf-token',
};
const submission: ReportRequest = {
  targetType: 'FEED',
  targetId: '00000000-0000-4000-8000-000000000002',
  reason: 'SPAM',
};
const requestApi = (init: RequestInit): Promise<Response> =>
  handlers[0].resolver({
    request: new Request('http://localhost/api/v1/reports', init),
  });

beforeEach((): void => resetReportMockState());
afterEach((): void => {
  jest.restoreAllMocks();
});

describe('신고 MSW 계약', (): void => {
  it('클라이언트로 접수하고 재요청하면 같은 접수 ID와 중복 안내를 받는다', async (): Promise<void> => {
    jest.spyOn(auth, 'requestCsrfToken').mockResolvedValue('mock-csrf-token');
    jest
      .spyOn(auth, 'authFetch')
      .mockImplementation((_info, init): Promise<Response> => {
        const headers = new Headers(init?.headers);
        headers.set('Authorization', mutationHeaders.Authorization);
        return requestApi({ ...init, headers });
      });
    const first = await submitReport({
      ...submission,
      description: '처음 접수 내용',
    });
    const duplicate = await submitReport({
      ...submission,
      reason: 'OTHER',
      description: '다시 입력한 내용',
    });
    expect(first).toMatchObject({ status: 'RECEIVED', duplicate: false });
    expect(duplicate).toEqual({ ...first, duplicate: true });
    expect(Object.keys(duplicate).sort()).toEqual([
      'duplicate',
      'id',
      'status',
    ]);
  });

  it.each<AuthorizationCase>([
    { headers: {}, status: 401, code: 'UNAUTHORIZED' },
    {
      headers: { Authorization: mutationHeaders.Authorization },
      status: 403,
      code: 'INVALID_CSRF_TOKEN',
    },
    {
      headers: { ...mutationHeaders, 'X-XSRF-TOKEN': 'wrong' },
      status: 403,
      code: 'INVALID_CSRF_TOKEN',
    },
  ])(
    '인증과 CSRF를 확인한다: $code',
    async ({ headers, status, code }): Promise<void> => {
      const response = await requestApi({
        method: 'POST',
        headers,
        body: JSON.stringify(submission),
      });
      expect(response.status).toBe(status);
      await expect(response.json()).resolves.toMatchObject({ code });
    },
  );

  it.each([
    { ...submission, targetId: 'invalid' },
    { ...submission, targetType: 'DIARY' },
    { ...submission, reason: 'INVALID' },
    { ...submission, description: '😀'.repeat(501) },
  ])('잘못된 본문을 접수하지 않는다', async (body): Promise<void> => {
    const response = await requestApi({
      method: 'POST',
      headers: mutationHeaders,
      body: JSON.stringify(body),
    });
    expect(response.status).toBe(400);
    await expect(response.json()).resolves.toMatchObject({
      code: 'INVALID_REPORT',
    });
  });
});
