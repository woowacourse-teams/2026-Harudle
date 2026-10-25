/** @jest-environment node */
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import {
  authFetch,
  isRefreshTokenResponse,
  publicFetch,
  setAccessToken,
  TERMS_ACCEPTANCE_REQUIRED_EVENT,
} from './auth';

const originalFetch = globalThis.fetch;
const originalWindow = Object.getOwnPropertyDescriptor(globalThis, 'window');
const originalStorage = Object.getOwnPropertyDescriptor(
  globalThis,
  'localStorage',
);
const mockFetch = jest.fn<typeof fetch>();
const problem = {
  type: 'about:blank',
  title: 'Terms acceptance required',
  status: 403,
  detail: '최신 이용약관 동의가 필요해요.',
  instance: '/api/v1/diaries',
  code: 'TERMS_ACCEPTANCE_REQUIRED',
};
const requesters = [
  ['회원 요청', authFetch],
  ['인증된 공개 요청', publicFetch],
] as const;
const mockTermsRequired = jest.fn();

beforeEach((): void => {
  const values = new Map<string, string>();
  const storage: Storage = {
    get length(): number {
      return values.size;
    },
    clear: (): void => values.clear(),
    getItem: (key): string | null => values.get(key) ?? null,
    key: (index): string | null => [...values.keys()][index] ?? null,
    removeItem: (key): void => {
      values.delete(key);
    },
    setItem: (key, value): void => {
      values.set(key, value);
    },
  };
  Object.defineProperty(globalThis, 'window', {
    configurable: true,
    value: new EventTarget(),
  });
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: storage,
  });
  localStorage.setItem('harudle.has-completed-oauth', 'true');
  setAccessToken('current-token');
  mockFetch.mockReset();
  mockTermsRequired.mockReset();
  globalThis.fetch = mockFetch;
  window.addEventListener(TERMS_ACCEPTANCE_REQUIRED_EVENT, mockTermsRequired);
});

afterEach((): void => {
  globalThis.fetch = originalFetch;
  setAccessToken(null);
  window.removeEventListener(
    TERMS_ACCEPTANCE_REQUIRED_EVENT,
    mockTermsRequired,
  );
  if (originalWindow)
    Object.defineProperty(globalThis, 'window', originalWindow);
  else Reflect.deleteProperty(globalThis, 'window');
  if (originalStorage)
    Object.defineProperty(globalThis, 'localStorage', originalStorage);
  else Reflect.deleteProperty(globalThis, 'localStorage');
  jest.restoreAllMocks();
});

describe('isRefreshTokenResponse', () => {
  it('Access Token 재발급 응답 형식을 확인한다', () => {
    const response = {
      accessToken: 'access-token',
      tokenType: 'Bearer',
      expiresIn: 1800,
    };

    expect(isRefreshTokenResponse(response)).toBe(true);
  });
});

describe('진행 중인 세션의 약관 동의 요구', (): void => {
  it.each(requesters)(
    '%s은 정확한 약관 거절을 알리고 원래 응답과 본문을 보존한다',
    async (_name, request): Promise<void> => {
      const response = Response.json(problem, { status: 403 });
      mockFetch.mockResolvedValueOnce(response);
      const result = await request('/api/v1/diaries', {
        method: 'PUT',
        body: '{"title":"일기"}',
      });
      expect(result).toBe(response);
      expect(result.bodyUsed).toBe(false);
      expect(await result.json()).toEqual(problem);
      expect(mockTermsRequired).toHaveBeenCalledTimes(1);
      expect(mockFetch).toHaveBeenCalledTimes(1);
    },
  );

  it.each(requesters)(
    '%s은 401 갱신 후의 약관 거절도 알리며 원래 요청은 한 번만 재시도한다',
    async (_name, request): Promise<void> => {
      const response = Response.json(problem, { status: 403 });
      mockFetch
        .mockResolvedValueOnce(new Response(null, { status: 401 }))
        .mockResolvedValueOnce(Response.json({ token: 'csrf' }))
        .mockResolvedValueOnce(
          Response.json({
            accessToken: 'renewed-token',
            tokenType: 'Bearer',
            expiresIn: 1800,
          }),
        )
        .mockResolvedValueOnce(response);
      const init = { method: 'PUT', body: '{"title":"일기"}' };
      const result = await request('/api/v1/diaries', init);
      expect(result).toBe(response);
      expect(result.bodyUsed).toBe(false);
      expect(await result.json()).toEqual(problem);
      expect(mockTermsRequired).toHaveBeenCalledTimes(1);
      expect(mockFetch).toHaveBeenCalledTimes(4);
      const writes = mockFetch.mock.calls.filter(
        ([url]): boolean => url === '/api/v1/diaries',
      );
      expect(writes).toHaveLength(2);
      expect(writes[0]?.[1]).toEqual({
        ...init,
        headers: new Headers({ Authorization: 'Bearer current-token' }),
      });
      expect(writes[1]?.[1]).toEqual({
        ...init,
        headers: new Headers({ Authorization: 'Bearer renewed-token' }),
      });
    },
  );

  it.each([
    ['다른 403', 403, { ...problem, code: 'ACCOUNT_SUSPENDED' }],
    ['다른 HTTP 상태', 409, problem],
    ['다른 본문 상태', 403, { ...problem, status: 409 }],
    ['불완전한 오류', 403, { code: 'TERMS_ACCEPTANCE_REQUIRED' }],
  ] as const)(
    '%s은 약관 이동을 요구하지 않는다',
    async (_name, status, body): Promise<void> => {
      const response = Response.json(body, { status });
      mockFetch.mockResolvedValueOnce(response);
      const result = await authFetch('/api/v1/diaries');
      expect(result).toBe(response);
      expect(await result.json()).toEqual(body);
      expect(mockTermsRequired).not.toHaveBeenCalled();
      expect(mockFetch).toHaveBeenCalledTimes(1);
    },
  );

  it('JSON이 아닌 403도 원래 본문을 읽을 수 있고 안내 이벤트를 내지 않는다', async (): Promise<void> => {
    const response = new Response('upstream unavailable', { status: 403 });
    mockFetch.mockResolvedValueOnce(response);
    const result = await authFetch('/api/v1/diaries');
    expect(await result.text()).toBe('upstream unavailable');
    expect(mockTermsRequired).not.toHaveBeenCalled();
    expect(mockFetch).toHaveBeenCalledTimes(1);
  });

  it('익명 공개 응답은 같은 오류여도 회원 동의 검사에 개입하지 않는다', async (): Promise<void> => {
    setAccessToken(null);
    const response = Response.json(problem, { status: 403 });
    mockFetch.mockResolvedValueOnce(response);
    expect(await publicFetch('/api/v1/feeds')).toBe(response);
    expect(response.bodyUsed).toBe(false);
    expect(mockTermsRequired).not.toHaveBeenCalled();
    expect(mockFetch).toHaveBeenCalledTimes(1);
    expect(mockFetch).toHaveBeenCalledWith('/api/v1/feeds', undefined);
  });

  it.each(['로그아웃', '토큰 교체', '요청 취소'] as const)(
    '%s 후 늦게 읽은 403은 현재 화면에 동의를 요구하지 않는다',
    async (change): Promise<void> => {
      let finish: ((value: unknown) => void) | undefined;
      const json = jest.fn(
        (): Promise<unknown> =>
          new Promise((resolve): void => {
            finish = resolve;
          }),
      );
      const response = Response.json(problem, { status: 403 });
      const clonedResponse = response.clone();
      jest.spyOn(clonedResponse, 'json').mockImplementation(json);
      jest.spyOn(response, 'clone').mockReturnValue(clonedResponse);
      mockFetch.mockResolvedValueOnce(response);
      const controller = new AbortController();
      const pending = authFetch('/api/v1/diaries', {
        signal: controller.signal,
      });
      await Promise.resolve();
      expect(json).toHaveBeenCalledTimes(1);
      if (change === '요청 취소') controller.abort();
      else setAccessToken(change === '로그아웃' ? null : 'other-token');
      finish?.(problem);
      expect(await pending).toBe(response);
      expect(response.bodyUsed).toBe(false);
      expect(mockTermsRequired).not.toHaveBeenCalled();
      expect(mockFetch).toHaveBeenCalledTimes(1);
    },
  );
});
