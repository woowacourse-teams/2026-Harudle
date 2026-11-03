/** @jest-environment node */
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { pushHandlers, resetPushMockState } from '../../mocks/pushHandlers';
import { setAccessToken } from '../../shared/auth';
import { updatePushRegistration, type PushState } from './pushRegistration';

jest.mock('msw', () => {
  class MockHttpResponse extends Response {
    static json(data: unknown, init: ResponseInit = {}): MockHttpResponse {
      return new MockHttpResponse(JSON.stringify(data), {
        ...init,
        headers: { 'Content-Type': 'application/json', ...init.headers },
      });
    }
  }
  const handler =
    (method: string): ((path: string, resolver: unknown) => object) =>
    (path, resolver): object => ({ method, path, resolver });
  return {
    http: { put: handler('PUT'), delete: handler('DELETE') },
    HttpResponse: MockHttpResponse,
  };
});

interface TestHandler {
  readonly method: string;
  readonly resolver: (args: {
    request: Request;
    params: { deviceId: string };
  }) => Response | Promise<Response>;
}
const isTestHandler = (value: unknown): value is TestHandler => {
  return (
    typeof value === 'object' &&
    value !== null &&
    'method' in value &&
    typeof value.method === 'string' &&
    'resolver' in value &&
    typeof value.resolver === 'function'
  );
};
const deviceId = 'd28cad31-005f-42d0-9d78-ef1eb13822a1';
const initial: PushState = { registration: null, stateVersion: 0 };
const fetchMock = jest.fn<typeof fetch>();
const originalFetch = globalThis.fetch;
let restoreGlobals = (): void => undefined;
const requests: Request[] = [];
const route = async (
  info: Parameters<typeof fetch>[0],
  init?: Parameters<typeof fetch>[1],
): Promise<Response> => {
  const request = new Request(
    new URL(String(info), 'https://harudle.com'),
    init,
  );
  requests.push(request.clone());
  const handler: unknown = pushHandlers.find(
    (item): boolean => isTestHandler(item) && item.method === request.method,
  );
  if (!isTestHandler(handler)) throw new Error('Missing push handler');
  return handler.resolver({ request, params: { deviceId } });
};

beforeEach((): void => {
  const storage = new Map([['harudle.has-completed-oauth', 'true']]);
  const storageDescriptor = Object.getOwnPropertyDescriptor(
    globalThis,
    'localStorage',
  );
  const windowDescriptor = Object.getOwnPropertyDescriptor(
    globalThis,
    'window',
  );
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
  Object.defineProperty(globalThis, 'window', {
    configurable: true,
    value: globalThis,
  });
  restoreGlobals = (): void => {
    if (storageDescriptor)
      Object.defineProperty(globalThis, 'localStorage', storageDescriptor);
    else Reflect.deleteProperty(globalThis, 'localStorage');
    if (windowDescriptor)
      Object.defineProperty(globalThis, 'window', windowDescriptor);
    else Reflect.deleteProperty(globalThis, 'window');
  };
  globalThis.fetch = fetchMock;
  fetchMock.mockReset().mockImplementation(route);
  setAccessToken('access-token');
  resetPushMockState();
  requests.length = 0;
});
afterEach((): void => {
  globalThis.fetch = originalFetch;
  setAccessToken(null);
  restoreGlobals();
  jest.useRealTimers();
});

describe('네이티브 푸시 서버 등록', () => {
  it('인증된 첫 등록 후 FID 변경과 등록 해제에 최신 번호를 사용한다', async (): Promise<void> => {
    const signal = new AbortController().signal;
    const first = await updatePushRegistration(
      deviceId,
      'fid-1',
      initial,
      signal,
    );
    const updated = await updatePushRegistration(
      deviceId,
      'fid-2',
      first,
      signal,
    );
    const revoked = await updatePushRegistration(
      deviceId,
      null,
      updated,
      signal,
    );
    expect(first.stateVersion).toBe(1);
    expect(updated.registration?.version).toBe(1);
    expect(revoked).toEqual({ registration: null, stateVersion: 3 });
    expect(
      requests.every(
        (request): boolean =>
          request.headers.get('Authorization') === 'Bearer access-token',
      ),
    ).toBe(true);
    expect(await requests[1].json()).toEqual({
      fid: 'fid-2',
      stateVersion: 1,
      registrationId: first.registration?.id,
      version: 0,
    });
    expect(requests[2].url).toContain('stateVersion=2');
  });

  it('앱 재시작 후 상태 번호를 몰라도 현재 번호를 받아 FID를 갱신한다', async (): Promise<void> => {
    const signal = new AbortController().signal;
    await updatePushRegistration(deviceId, 'fid-1', initial, signal);
    const changed = await updatePushRegistration(
      deviceId,
      'fid-2',
      initial,
      signal,
    );
    expect(changed.stateVersion).toBe(2);
    expect(changed.registration?.version).toBe(1);
    expect(requests).toHaveLength(4);
  });

  it('이미 등록된 동일 FID를 다시 등록해도 상태가 중복 증가하지 않는다', async (): Promise<void> => {
    const signal = new AbortController().signal;
    const first = await updatePushRegistration(
      deviceId,
      'fid-1',
      initial,
      signal,
    );
    const second = await updatePushRegistration(
      deviceId,
      'fid-1',
      initial,
      signal,
    );
    expect(second).toEqual(first);
  });

  it('응답을 놓친 등록은 같은 요청으로 재시도한다', async (): Promise<void> => {
    jest.useFakeTimers();
    fetchMock.mockImplementationOnce(async (info, init): Promise<Response> => {
      await route(info, init);
      throw new TypeError('Network response lost');
    });
    const result = updatePushRegistration(
      deviceId,
      'fid-1',
      initial,
      new AbortController().signal,
    );
    await jest.runAllTimersAsync();
    await expect(result).resolves.toMatchObject({ stateVersion: 1 });
    expect(requests).toHaveLength(2);
  });

  it('재시도 대기 중 화면을 떠나면 다음 요청을 보내지 않는다', async (): Promise<void> => {
    jest.useFakeTimers();
    fetchMock.mockRejectedValue(new TypeError('offline'));
    const controller = new AbortController();
    const result = updatePushRegistration(
      deviceId,
      'fid-1',
      initial,
      controller.signal,
    );
    const rejected = expect(result).rejects.toBeDefined();
    await jest.advanceTimersByTimeAsync(1);
    controller.abort();
    await rejected;
    await jest.runAllTimersAsync();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
