/** @jest-environment node */

import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { publicFetch, setAccessToken } from './auth';

const success = (): Response => new Response('{}', { status: 200 });
const unauthorized = (): Response => new Response('{}', { status: 401 });
const csrf = (): Response => Response.json({ token: 'csrf-token' });
const refreshed = (): Response =>
  Response.json({
    accessToken: 'new-token',
    tokenType: 'Bearer',
    expiresIn: 1800,
  });

beforeEach(() => {
  setAccessToken(null);
});

afterEach(() => {
  setAccessToken(null);
  jest.restoreAllMocks();
});

const mockStorage = () => {
  const descriptor = Object.getOwnPropertyDescriptor(
    globalThis,
    'localStorage',
  );
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: { getItem: () => 'true', setItem: jest.fn() },
  });
  return () => {
    if (descriptor)
      Object.defineProperty(globalThis, 'localStorage', descriptor);
    else Reflect.deleteProperty(globalThis, 'localStorage');
  };
};

const authorization = (init?: RequestInit): string | null =>
  new Headers(init?.headers).get('Authorization');

describe('publicFetch', () => {
  it('토큰이 없으면 갱신 없이 공개 조회하며 요청 옵션을 유지한다', async () => {
    const response = success();
    const fetchMock = jest
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(response);
    const signal = new AbortController().signal;
    const init = { signal };

    expect(await publicFetch('/api/v1/feeds', init)).toBe(response);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/feeds', init);
  });

  it('메모리 토큰을 붙이고 기존 헤더와 signal을 유지한다', async () => {
    setAccessToken('valid-token');
    const fetchMock = jest
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(success());
    const signal = new AbortController().signal;
    await publicFetch('/api/v1/feeds', {
      signal,
      headers: { Accept: 'application/json' },
    });

    const init = fetchMock.mock.calls[0][1];
    expect(authorization(init)).toBe('Bearer valid-token');
    expect(new Headers(init?.headers).get('Accept')).toBe('application/json');
    expect(init?.signal).toBe(signal);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('401이면 CSRF를 발급받아 갱신하고 한 번 재시도한다', async () => {
    const restoreStorage = mockStorage();
    try {
      setAccessToken('expired-token');
      const response = success();
      const fetchMock = jest
        .spyOn(globalThis, 'fetch')
        .mockResolvedValueOnce(unauthorized())
        .mockResolvedValueOnce(csrf())
        .mockResolvedValueOnce(refreshed())
        .mockResolvedValueOnce(response);

      expect(await publicFetch('/api/v1/feeds')).toBe(response);
      expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
        '/api/v1/feeds',
        '/api/v1/auth/csrf',
        '/api/v1/auth/refresh',
        '/api/v1/feeds',
      ]);
      expect(fetchMock.mock.calls[2][1]).toMatchObject({
        method: 'POST',
        credentials: 'include',
        headers: { 'X-XSRF-TOKEN': 'csrf-token' },
      });
      expect(authorization(fetchMock.mock.calls[3][1])).toBe(
        'Bearer new-token',
      );
    } finally {
      restoreStorage();
    }
  });

  it('갱신이 실패하면 화면 이동 없이 토큰을 빼고 공개 조회한다', async () => {
    setAccessToken('expired-token');
    const response = success();
    const fetchMock = jest
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(unauthorized())
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(
        Response.json(
          {
            type: 'about:blank',
            title: 'Expired',
            status: 401,
            detail: 'Expired',
            instance: '/api/v1/auth/refresh',
            code: 'INVALID_REFRESH_TOKEN',
          },
          { status: 401 },
        ),
      )
      .mockResolvedValue(response);

    expect(await publicFetch('/api/v1/feeds')).toBe(response);
    expect(authorization(fetchMock.mock.calls[3][1])).toBeNull();
    await publicFetch('/api/v1/feeds');
    expect(fetchMock.mock.calls[4][1]).toBeUndefined();
  });

  it('동시 401은 갱신 한 번을 공유한다', async () => {
    const restoreStorage = mockStorage();
    try {
      setAccessToken('expired-token');
      const fetchMock = jest
        .spyOn(globalThis, 'fetch')
        .mockImplementation(async (url, init) => {
          if (url === '/api/v1/auth/csrf') return csrf();
          if (url === '/api/v1/auth/refresh') return refreshed();
          return authorization(init) === 'Bearer expired-token'
            ? unauthorized()
            : success();
        });

      await Promise.all([
        publicFetch('/api/v1/feeds'),
        publicFetch('/api/v1/feeds'),
      ]);
      expect(
        fetchMock.mock.calls.filter(([url]) => url === '/api/v1/auth/refresh'),
      ).toHaveLength(1);
      expect(
        fetchMock.mock.calls.filter(
          ([url, init]) =>
            url === '/api/v1/feeds' &&
            authorization(init) === 'Bearer new-token',
        ),
      ).toHaveLength(2);
    } finally {
      restoreStorage();
    }
  });

  it('갱신한 토큰도 거부되면 추가 갱신 없이 공개 조회한다', async () => {
    const restoreStorage = mockStorage();
    try {
      setAccessToken('expired-token');
      const response = success();
      const fetchMock = jest
        .spyOn(globalThis, 'fetch')
        .mockResolvedValueOnce(unauthorized())
        .mockResolvedValueOnce(csrf())
        .mockResolvedValueOnce(refreshed())
        .mockResolvedValueOnce(unauthorized())
        .mockResolvedValueOnce(response);
      expect(await publicFetch('/api/v1/feeds')).toBe(response);
      expect(fetchMock).toHaveBeenCalledTimes(5);
      expect(authorization(fetchMock.mock.calls[4][1])).toBeNull();
    } finally {
      restoreStorage();
    }
  });

  it('조회 자체의 네트워크 오류는 갱신하지 않고 호출자에게 전달한다', async () => {
    setAccessToken('valid-token');
    const error = new Error('network error');
    const fetchMock = jest.spyOn(globalThis, 'fetch').mockRejectedValue(error);
    await expect(publicFetch('/api/v1/feeds')).rejects.toBe(error);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
