/** @jest-environment node */

import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { isRefreshTokenResponse } from './auth';

let auth: typeof import('./auth');
const originalWindow = Object.getOwnPropertyDescriptor(globalThis, 'window');
const originalStorage = Object.getOwnPropertyDescriptor(
  globalThis,
  'localStorage',
);
const originalAlert = Object.getOwnPropertyDescriptor(globalThis, 'alert');
const mockAlert = jest.fn();
const location = {
  pathname: '/setting',
  href: '/setting',
  replace: jest.fn<(url: string) => void>(),
};

const csrf = (): Response => Response.json({ token: 'csrf-token' });
const invalidRefresh = (): Response =>
  Response.json(
    {
      type: 'about:blank',
      title: 'Expired',
      status: 401,
      detail: '세션이 만료되었어요.',
      instance: '/api/v1/auth/refresh',
      code: 'INVALID_REFRESH_TOKEN',
    },
    { status: 401 },
  );

beforeEach(async (): Promise<void> => {
  jest.resetModules();
  auth = await import('./auth');
  const values = new Map([['harudle.has-completed-oauth', 'true']]);
  Object.defineProperty(globalThis, 'localStorage', {
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
  Object.defineProperty(globalThis, 'window', {
    configurable: true,
    value: { location },
  });
  Object.defineProperty(globalThis, 'alert', {
    configurable: true,
    value: mockAlert,
  });
  location.pathname = '/setting';
  location.href = '/setting';
  location.replace.mockReset();
  mockAlert.mockReset();
});

const restoreGlobal = (
  name: string,
  descriptor: PropertyDescriptor | undefined,
): void => {
  if (descriptor) Object.defineProperty(globalThis, name, descriptor);
  else Reflect.deleteProperty(globalThis, name);
};

afterEach((): void => {
  jest.restoreAllMocks();
  restoreGlobal('window', originalWindow);
  restoreGlobal('localStorage', originalStorage);
  restoreGlobal('alert', originalAlert);
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

describe('공개 문의 화면에서 인증 실패', (): void => {
  it.each(['/contact', '/contact/', '/CONTACT', '/CoNtAcT///'])(
    '%s에서는 로그인 이력이 없어도 오류만 전달하고 화면을 이동하지 않는다',
    async (pathname): Promise<void> => {
      location.pathname = pathname;
      location.href = pathname;
      localStorage.removeItem('harudle.has-completed-oauth');
      const fetchMock = jest.spyOn(globalThis, 'fetch');

      await expect(auth.authFetch('/api/v1/me/profile')).rejects.toMatchObject({
        name: 'AuthenticationRequiredError',
      });

      expect(fetchMock).not.toHaveBeenCalled();
      expect(location.replace).not.toHaveBeenCalled();
      expect(location.href).toBe(pathname);
      expect(mockAlert).not.toHaveBeenCalled();
    },
  );

  it('기존 화면에서 시작한 갱신이 문의로 이동한 뒤 실패하면 문의에 머문다', async (): Promise<void> => {
    let finishRefresh: ((response: Response) => void) | undefined;
    let notifyStarted: (() => void) | undefined;
    const started = new Promise<void>((resolve): void => {
      notifyStarted = resolve;
    });
    const fetchMock = jest
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(csrf())
      .mockImplementationOnce(
        (): Promise<Response> =>
          new Promise((resolve): void => {
            finishRefresh = resolve;
            notifyStarted?.();
          }),
      );
    const request = auth.authFetch('/api/v1/me/profile');
    await started;
    location.pathname = '/CONTACT/';
    location.href = '/CONTACT/';
    finishRefresh?.(invalidRefresh());

    await expect(request).rejects.toMatchObject({
      problem: { code: 'INVALID_REFRESH_TOKEN' },
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(localStorage.getItem('harudle.has-completed-oauth')).toBeNull();
    expect(location.href).toBe('/CONTACT/');
    expect(location.replace).not.toHaveBeenCalled();
    expect(mockAlert).not.toHaveBeenCalled();
  });

  it('문의에서 무시한 만료 안내가 이후 회원 화면의 만료 처리를 막지 않는다', async (): Promise<void> => {
    location.pathname = '/contact';
    location.href = '/contact';
    const fetchMock = jest
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(invalidRefresh());
    await expect(auth.authFetch('/api/v1/me/profile')).rejects.toMatchObject({
      problem: { code: 'INVALID_REFRESH_TOKEN' },
    });
    expect(mockAlert).not.toHaveBeenCalled();

    location.pathname = '/setting';
    location.href = '/setting';
    localStorage.setItem('harudle.has-completed-oauth', 'true');
    fetchMock
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(invalidRefresh());
    await expect(auth.authFetch('/api/v1/me/profile')).rejects.toMatchObject({
      problem: { code: 'INVALID_REFRESH_TOKEN' },
    });

    expect(mockAlert).toHaveBeenCalledTimes(1);
    expect(location.href).toBe('/');
  });
});

describe('기존 회원 요청 동작', (): void => {
  it.each(['/setting', '/contact-us', '/contact/help'])(
    '%s에서는 로그인 이력이 없으면 기존대로 홈으로 이동한다',
    async (pathname): Promise<void> => {
      location.pathname = pathname;
      localStorage.removeItem('harudle.has-completed-oauth');

      await expect(auth.authFetch('/api/v1/me/profile')).rejects.toMatchObject({
        name: 'AuthenticationRequiredError',
      });

      expect(location.replace).toHaveBeenCalledWith('/');
    },
  );

  it('동시 401 요청은 갱신을 한 번 공유하고 새 토큰으로 한 번씩 재시도한다', async (): Promise<void> => {
    auth.setAccessToken('expired-token');
    const fetchMock = jest
      .spyOn(globalThis, 'fetch')
      .mockImplementation(async (url, init): Promise<Response> => {
        if (url === '/api/v1/auth/csrf') return csrf();
        if (url === '/api/v1/auth/refresh') {
          return Response.json({
            accessToken: 'new-token',
            tokenType: 'Bearer',
            expiresIn: 1800,
          });
        }
        return new Response('{}', {
          status:
            new Headers(init?.headers).get('Authorization') ===
            'Bearer expired-token'
              ? 401
              : 200,
        });
      });

    const responses = await Promise.all([
      auth.authFetch('/api/v1/me/profile'),
      auth.authFetch('/api/v1/diary-generations/generation-1'),
    ]);

    expect(responses.map((response): number => response.status)).toEqual([
      200, 200,
    ]);
    expect(
      fetchMock.mock.calls.filter(
        ([url]): boolean => url === '/api/v1/auth/refresh',
      ),
    ).toHaveLength(1);
    expect(fetchMock).toHaveBeenCalledTimes(6);
    expect(mockAlert).not.toHaveBeenCalled();
  });
});
