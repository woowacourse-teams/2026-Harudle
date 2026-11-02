/** @jest-environment node */
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { handlers } from './handlers';
import { resetAppleNativeAuthMockState } from './appleNativeAuthHandlers';
import { MOCK_SCENARIO_HEADER, MOCK_SCENARIOS } from './mockScenarios';
import { isRecord } from '../shared/utils';

jest.mock('msw', () => {
  class MockHttpResponse extends Response {
    static json(data: unknown, init: ResponseInit = {}): Response {
      return new MockHttpResponse(JSON.stringify(data), init);
    }
  }
  const handler = (method: string) => (path: string, resolver: unknown) => ({
    method,
    path,
    resolver,
  });
  return {
    delay: (): Promise<void> => Promise.resolve(),
    http: {
      get: handler('GET'),
      post: handler('POST'),
      patch: handler('PATCH'),
      put: handler('PUT'),
      delete: handler('DELETE'),
    },
    HttpResponse: MockHttpResponse,
  };
});
jest.mock('../assets/images/diary-four-panel.webp', () => 'diary.webp');
jest.mock('../assets/images/profile-fallback.svg', () => 'profile.svg');
jest.mock('../assets/icons/article_person.svg', () => 'profile.svg');

interface MockHandler {
  readonly method: string;
  readonly path: string;
  readonly resolver: (args: { request: Request }) => Promise<Response>;
}

const nativePath = '/api/v1/auth/apple/native';
const credentials = {
  authorizationCode: 'mock-apple-code',
  identityToken: 'mock-apple-token',
};
const csrfHeaders = { 'X-XSRF-TOKEN': 'mock-csrf-token' };
const originalStorage = Object.getOwnPropertyDescriptor(
  globalThis,
  'sessionStorage',
);
const originalLocation = Object.getOwnPropertyDescriptor(
  globalThis,
  'location',
);

const requestApi = (
  path: string,
  init: RequestInit = {},
): Promise<Response> => {
  const request = new Request(`http://localhost${path}`, init);
  // jest.mock에서 만든 resolver에 실제 Request를 전달해 API 계약을 확인한다.
  const handler = (handlers as unknown as MockHandler[]).find(
    (item): boolean => item.path === path && item.method === request.method,
  );
  if (!handler) throw new Error(`MSW 핸들러가 없습니다: ${path}`);
  return handler.resolver({ request });
};

const issueNonce = (): Promise<Response> =>
  requestApi(`${nativePath}/nonce`, { method: 'POST', headers: csrfHeaders });

const login = (
  scenario?: string,
  body: unknown = credentials,
): Promise<Response> =>
  requestApi(`${nativePath}/login`, {
    method: 'POST',
    headers: {
      ...csrfHeaders,
      'Content-Type': 'application/json',
      ...(scenario ? { [MOCK_SCENARIO_HEADER]: scenario } : {}),
    },
    body: JSON.stringify(body),
  });

beforeEach((): void => {
  const values = new Map<string, string>();
  Object.defineProperty(globalThis, 'sessionStorage', {
    configurable: true,
    value: {
      getItem: (key: string): string | null => values.get(key) ?? null,
      setItem: (key: string, value: string): void => {
        values.set(key, value);
      },
      removeItem: (key: string): void => {
        values.delete(key);
      },
    },
  });
  Object.defineProperty(globalThis, 'location', {
    configurable: true,
    value: { pathname: '/landing' },
  });
  jest.spyOn(Date, 'now').mockReturnValue(1_790_000_000_000);
  resetAppleNativeAuthMockState();
});

afterEach((): void => {
  jest.restoreAllMocks();
  if (originalStorage)
    Object.defineProperty(globalThis, 'sessionStorage', originalStorage);
  else Reflect.deleteProperty(globalThis, 'sessionStorage');
  if (originalLocation)
    Object.defineProperty(globalThis, 'location', originalLocation);
  else Reflect.deleteProperty(globalThis, 'location');
});

describe('Apple 네이티브 로그인 MSW 계약', (): void => {
  it('CSRF 발급 → nonce → 로그인 → refresh → 로그아웃 흐름을 재현한다', async (): Promise<void> => {
    const csrf = await requestApi('/api/v1/auth/csrf');
    await expect(csrf.json()).resolves.toEqual({ token: 'mock-csrf-token' });
    const refresh = (): Promise<Response> =>
      requestApi('/api/v1/auth/refresh', {
        method: 'POST',
        headers: csrfHeaders,
      });
    expect((await refresh()).status).toBe(401);
    await issueNonce();
    const response = await login();
    expect(response.status).toBe(204);
    expect(response.headers.get('Cache-Control')).toBe('no-store');
    expect(await response.text()).toBe('');
    const refreshed = await refresh();
    expect(refreshed.status).toBe(200);
    await expect(refreshed.json()).resolves.toMatchObject({
      accessToken: 'mock-access-token',
    });
    const logout = await requestApi('/api/v1/auth/logout', {
      method: 'POST',
      headers: { ...csrfHeaders, Authorization: 'Bearer mock-access-token' },
    });
    expect(logout.status).toBe(204);
    expect((await refresh()).status).toBe(401);
    await issueNonce();
    expect((await login()).status).toBe(204);
    expect((await refresh()).status).toBe(200);
  });

  it('nonce를 매번 새 32바이트 base64url 값으로 발급하고 캐시를 막는다', async (): Promise<void> => {
    const first = await issueNonce();
    const firstBody: unknown = await first.json();
    const secondBody: unknown = await (await issueNonce()).json();
    expect(first.status).toBe(200);
    expect(first.headers.get('Cache-Control')).toBe('no-store');
    if (!isRecord(firstBody) || !isRecord(secondBody))
      throw new Error('nonce 응답이 필요합니다.');
    expect(firstBody.nonce).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(secondBody.nonce).not.toBe(firstBody.nonce);
  });

  it.each(['/nonce', '/login'])(
    '%s는 CSRF 토큰 누락과 불일치를 거절한다',
    async (path): Promise<void> => {
      for (const token of ['', 'wrong-token']) {
        const response = await requestApi(`${nativePath}${path}`, {
          method: 'POST',
          headers: { 'X-XSRF-TOKEN': token },
          body: JSON.stringify(credentials),
        });
        expect(response.status).toBe(403);
      }
    },
  );

  it('nonce 없는 로그인과 중복 요청을 거절한다', async (): Promise<void> => {
    expect((await login()).status).toBe(400);
    await issueNonce();
    const responses = await Promise.all([login(), login()]);
    expect(responses.map((response): number => response.status)).toEqual([
      204, 400,
    ]);
  });

  it('nonce는 정확히 5분이 되면 만료되며 새 발급 후 재시도할 수 있다', async (): Promise<void> => {
    await issueNonce();
    jest.spyOn(Date, 'now').mockReturnValue(1_790_000_000_000 + 5 * 60 * 1_000);
    expect((await login()).status).toBe(400);
    await issueNonce();
    expect((await login()).status).toBe(204);
  });

  it.each([
    [MOCK_SCENARIOS.appleNativeInvalidCredentials, 400],
    [MOCK_SCENARIOS.appleNativeInactiveUser, 401],
    [MOCK_SCENARIOS.appleNativeProviderFailure, 502],
  ])(
    '%s 실패에도 nonce를 소모한다',
    async (scenario, status): Promise<void> => {
      await issueNonce();
      const response = await login(scenario);
      expect(response.status).toBe(status);
      expect(response.headers.get('Cache-Control')).toBe('no-store');
      expect(await response.text()).toBe('');
      expect((await login()).status).toBe(400);
      expect(
        (
          await requestApi('/api/v1/auth/refresh', {
            method: 'POST',
            headers: csrfHeaders,
          })
        ).status,
      ).toBe(401);
      await issueNonce();
      expect((await login()).status).toBe(204);
    },
  );

  it.each([
    null,
    {},
    { ...credentials, authorizationCode: ' ' },
    { ...credentials, identityToken: 123 },
    { ...credentials, authorizationCode: 'a'.repeat(4097) },
    { ...credentials, identityToken: 'a'.repeat(16385) },
  ])(
    '잘못된 본문 %j는 nonce 소모 전에 거절한다',
    async (body): Promise<void> => {
      await issueNonce();
      expect((await login(undefined, body)).status).toBe(400);
      expect((await login()).status).toBe(204);
    },
  );

  it('깨진 JSON과 CSRF 실패는 발급된 nonce를 소모하지 않는다', async (): Promise<void> => {
    await issueNonce();
    const malformed = await requestApi(`${nativePath}/login`, {
      method: 'POST',
      headers: csrfHeaders,
      body: '{',
    });
    expect(malformed.status).toBe(400);
    const forbidden = await requestApi(`${nativePath}/login`, {
      method: 'POST',
      body: JSON.stringify(credentials),
    });
    expect(forbidden.status).toBe(403);
    expect((await login()).status).toBe(204);
  });
});
