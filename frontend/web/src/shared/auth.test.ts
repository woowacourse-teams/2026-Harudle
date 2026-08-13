import { afterEach, describe, expect, it, jest } from '@jest/globals';

const createResponse = (status: number, body?: unknown): Response => {
  return {
    status,
    ok: status >= 200 && status < 300,
    json: jest.fn<() => Promise<unknown>>().mockResolvedValue(body),
  } as unknown as Response;
};

afterEach(() => {
  delete (globalThis as { fetch?: typeof fetch }).fetch;
  jest.resetModules();
});

describe('authFetch', () => {
  it('동시에 로그인 복원을 요청해도 Refresh 요청은 한 번만 보낸다', async () => {
    const fetchMock = jest
      .fn<typeof fetch>()
      .mockResolvedValueOnce(createResponse(200, { token: 'csrf-token' }))
      .mockResolvedValueOnce(
        createResponse(200, { accessToken: 'new-access-token' }),
      );
    globalThis.fetch = fetchMock;
    const { restoreLogin } = await import('./auth');

    const results = await Promise.all([restoreLogin(), restoreLogin()]);

    expect(results).toEqual([true, true]);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('401 응답을 받으면 Access Token을 갱신하고 요청을 한 번 재시도한다', async () => {
    const fetchMock = jest
      .fn<typeof fetch>()
      .mockResolvedValueOnce(createResponse(200, { token: 'csrf-token' }))
      .mockResolvedValueOnce(
        createResponse(200, { accessToken: 'expired-access-token' }),
      )
      .mockResolvedValueOnce(createResponse(401))
      .mockResolvedValueOnce(createResponse(200, { token: 'next-csrf-token' }))
      .mockResolvedValueOnce(
        createResponse(200, { accessToken: 'new-access-token' }),
      )
      .mockResolvedValueOnce(createResponse(200, { id: 'diary-id' }));
    globalThis.fetch = fetchMock;
    const { authFetch, restoreLogin } = await import('./auth');

    await restoreLogin();
    const response = await authFetch('/api/v1/diaries');

    expect(response.status).toBe(200);
    expect(
      new Headers(fetchMock.mock.calls[2]?.[1]?.headers).get('Authorization'),
    ).toBe('Bearer expired-access-token');
    expect(
      new Headers(fetchMock.mock.calls[5]?.[1]?.headers).get('Authorization'),
    ).toBe('Bearer new-access-token');
    expect(fetchMock).toHaveBeenCalledTimes(6);
  });
});

describe('logout', () => {
  it('CSRF Token을 Header에 담아 로그아웃을 요청한다', async () => {
    const fetchMock = jest
      .fn<typeof fetch>()
      .mockResolvedValueOnce(createResponse(200, { token: 'csrf-token' }))
      .mockResolvedValueOnce(createResponse(204));
    globalThis.fetch = fetchMock;
    const { logout } = await import('./auth');

    await logout();

    expect(
      new Headers(fetchMock.mock.calls[1]?.[1]?.headers).get('X-XSRF-TOKEN'),
    ).toBe('csrf-token');
  });
});
