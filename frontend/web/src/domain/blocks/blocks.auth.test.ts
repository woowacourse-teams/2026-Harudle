/** @jest-environment node */
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { setUserBlocked } from './blocks';
import { setAccessToken } from '../../shared/auth';

const originalFetch = globalThis.fetch;
const originalStorage = Object.getOwnPropertyDescriptor(
  globalThis,
  'localStorage',
);
const mockFetch = jest.fn<typeof fetch>();
let csrf = '';
let sequence = 0;
let rejected = 0;
let writes = 0;
let usedHeaders: string[] = [];
beforeEach((): void => {
  setAccessToken(null);
  csrf = '';
  sequence = 0;
  rejected = 0;
  writes = 0;
  usedHeaders = [];
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: { getItem: (): string => 'true' },
  });
  globalThis.fetch = mockFetch;
  mockFetch
    .mockReset()
    .mockImplementation(async (info, init): Promise<Response> => {
      const url = String(info);
      if (url.endsWith('/auth/csrf')) {
        csrf = `csrf-${++sequence}`;
        return Response.json({ token: csrf });
      }
      if (url.endsWith('/auth/refresh'))
        return Response.json({
          accessToken: 'restored',
          tokenType: 'Bearer',
          expiresIn: 1800,
        });
      const headers = new Headers(init?.headers);
      usedHeaders.push(headers.get('X-XSRF-TOKEN') ?? '');
      if (headers.get('Authorization') === 'Bearer expired')
        return new Response(null, { status: 401 });
      if (headers.get('X-XSRF-TOKEN') !== csrf) {
        rejected += 1;
        return Response.json(
          {
            type: 'urn:csrf',
            title: 'CSRF',
            status: 403,
            detail: 'invalid',
            instance: url,
            code: 'INVALID_CSRF_TOKEN',
          },
          { status: 403 },
        );
      }
      writes += 1;
      return new Response(null, { status: 204 });
    });
});
afterEach((): void => {
  globalThis.fetch = originalFetch;
  if (originalStorage)
    Object.defineProperty(globalThis, 'localStorage', originalStorage);
  else Reflect.deleteProperty(globalThis, 'localStorage');
  setAccessToken(null);
});
describe('실제 authFetch와 차단의 CSRF 복원', (): void => {
  it.each([null, 'expired'])(
    '메모리 토큰 %s에서 복원해 쿠키 변경 거절 뒤 한번만 저장한다',
    async (token): Promise<void> => {
      setAccessToken(token);
      await setUserBlocked('user-1', true);
      expect(rejected).toBe(1);
      expect(writes).toBe(1);
      expect(usedHeaders.at(-1)).toBe('csrf-3');
      expect(sequence).toBe(3);
    },
  );
});
