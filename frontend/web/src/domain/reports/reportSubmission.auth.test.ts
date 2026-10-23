/** @jest-environment node */

import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { http, HttpResponse } from 'msw';
import { setAccessToken } from '../../shared/auth';
import { submitReport } from './reportSubmission';

jest.mock('msw', () => {
  class MockHttpResponse extends Response {
    static json(data: unknown, init: ResponseInit = {}): MockHttpResponse {
      return new MockHttpResponse(JSON.stringify(data), init);
    }
  }
  const handler =
    (method: string): ((path: string, resolver: unknown) => object) =>
    (path, resolver): object => ({ method, path, resolver });
  return {
    http: { get: handler('GET'), post: handler('POST') },
    HttpResponse: MockHttpResponse,
  };
});

interface ReportAttempt {
  readonly authorization: string | null;
  readonly csrfHeader: string | null;
  readonly csrfCookie: string;
  readonly body: string;
}
interface TestHandler {
  readonly method: string;
  readonly path: string;
  readonly resolver: (args: {
    readonly request: Request;
  }) => Response | Promise<Response>;
}

let restoreStorage: (() => void) | undefined;
beforeEach((): void => {
  const descriptor = Object.getOwnPropertyDescriptor(
    globalThis,
    'localStorage',
  );
  const storage = new Map([['harudle.has-completed-oauth', 'true']]);
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: {
      getItem: (key: string): string | null => storage.get(key) ?? null,
      setItem: (key: string, value: string): void => {
        storage.set(key, value);
      },
      removeItem: (key: string): void => {
        storage.delete(key);
      },
    },
  });
  restoreStorage = (): void => {
    if (descriptor)
      Object.defineProperty(globalThis, 'localStorage', descriptor);
    else Reflect.deleteProperty(globalThis, 'localStorage');
  };
  setAccessToken(null);
});
afterEach((): void => {
  setAccessToken(null);
  jest.restoreAllMocks();
  restoreStorage?.();
});

// authFetch/requestCsrfToken은 실제 구현을 사용하고 HTTP 경계만 MSW resolver로 대체한다.
// Node fetch에는 브라우저 쿠키 저장소가 없으므로 Set-Cookie 적용은 이 라우터가 모사한다.
describe('신고와 실제 Access Token 복원 흐름', (): void => {
  it.each([
    {
      title: '새로고침 후 메모리 토큰이 없는 경우',
      initialToken: null,
      expectedAttempts: 2,
    },
    {
      title: '기존 토큰이 401로 만료된 경우',
      initialToken: 'expired-token',
      expectedAttempts: 3,
    },
  ])(
    '$title에도 CSRF를 갱신해 한 건만 접수한다',
    async ({ initialToken, expectedAttempts }): Promise<void> => {
      setAccessToken(initialToken);
      let issuedCsrfCount = 0;
      let cookie = '';
      let savedCount = 0;
      const attempts: ReportAttempt[] = [];
      const requestPaths: string[] = [];
      const csrfProblem = (): Response =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'CSRF 거절',
            status: 403,
            code: 'INVALID_CSRF_TOKEN',
            detail: 'CSRF 토큰이 일치하지 않습니다.',
            instance: '/api/v1/reports',
          },
          { status: 403 },
        );
      const csrfMatches = (request: Request): boolean =>
        request.headers.get('Cookie') ===
        `XSRF-TOKEN=${request.headers.get('X-XSRF-TOKEN')}`;
      const handlers = [
        http.get('/api/v1/auth/csrf', (): Response => {
          issuedCsrfCount += 1;
          const token = `csrf-${issuedCsrfCount}`;
          return HttpResponse.json(
            { token },
            { headers: { 'Set-Cookie': `XSRF-TOKEN=${token}; Path=/` } },
          );
        }),
        http.post('/api/v1/auth/refresh', ({ request }): Response => {
          expect(csrfMatches(request)).toBe(true);
          return HttpResponse.json({
            accessToken: 'restored-token',
            tokenType: 'Bearer',
            expiresIn: 1800,
          });
        }),
        http.post('/api/v1/reports', async ({ request }): Promise<Response> => {
          const authorization = request.headers.get('Authorization');
          attempts.push({
            authorization,
            csrfHeader: request.headers.get('X-XSRF-TOKEN'),
            csrfCookie: request.headers.get('Cookie') ?? '',
            body: await request.text(),
          });
          if (authorization !== 'Bearer restored-token')
            return HttpResponse.json({}, { status: 401 });
          if (!csrfMatches(request)) return csrfProblem();
          savedCount += 1;
          return HttpResponse.json(
            {
              id: '10000000-0000-4000-8000-000000000001',
              status: 'RECEIVED',
              duplicate: false,
            },
            { status: 201 },
          );
        }),
      ];
      // MSW factory의 테스트 대역이 노출한 resolver를 통해 실제 인증 코드의 fetch를 전달한다.
      const routes = handlers as unknown as readonly TestHandler[];
      jest
        .spyOn(globalThis, 'fetch')
        .mockImplementation(async (info, init): Promise<Response> => {
          const url =
            typeof info === 'string' ? new URL(info, 'http://localhost') : info;
          const headers = new Headers(init?.headers);
          if (cookie) headers.set('Cookie', cookie);
          const request = new Request(url, { ...init, headers });
          const path = new URL(request.url).pathname;
          requestPaths.push(`${request.method} ${path}`);
          const route = routes.find(
            (candidate): boolean =>
              candidate.method === request.method && candidate.path === path,
          );
          if (!route) throw new Error('예상하지 못한 HTTP 요청입니다.');
          const response = await route.resolver({ request });
          const nextCookie = response.headers.get('Set-Cookie');
          if (nextCookie) cookie = nextCookie.split(';')[0];
          return response;
        });

      const submission = {
        targetType: 'FEED',
        targetId: '00000000-0000-4000-8000-000000000002',
        reason: 'SPAM',
        description: '신고 상세 내용',
      } as const;
      await expect(submitReport(submission)).resolves.toMatchObject({
        status: 'RECEIVED',
        duplicate: false,
      });
      expect(savedCount).toBe(1);
      expect(issuedCsrfCount).toBe(3);
      expect(
        requestPaths.filter(
          (path): boolean => path === 'POST /api/v1/auth/refresh',
        ),
      ).toHaveLength(1);
      expect(attempts).toHaveLength(expectedAttempts);
      const staleAttempt = attempts[expectedAttempts - 2];
      expect(staleAttempt).toMatchObject({
        authorization: 'Bearer restored-token',
        csrfHeader: 'csrf-1',
        csrfCookie: 'XSRF-TOKEN=csrf-2',
      });
      expect(attempts[expectedAttempts - 1]).toMatchObject({
        authorization: 'Bearer restored-token',
        csrfHeader: 'csrf-3',
        csrfCookie: 'XSRF-TOKEN=csrf-3',
      });
      expect(
        attempts.every(
          (attempt): boolean => attempt.body === JSON.stringify(submission),
        ),
      ).toBe(true);
    },
  );
});
