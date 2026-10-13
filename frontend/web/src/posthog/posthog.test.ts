/** @jest-environment node */

import {
  afterAll,
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import posthog from 'posthog-js';

const mockIdentify = jest.fn();
jest.mock('posthog-js', () => ({
  __esModule: true,
  default: {
    identify: (...args: unknown[]): void => {
      mockIdentify(...args);
    },
  },
}));

const originalEnvironment = process.env.NODE_ENV;
const originalWindow = Object.getOwnPropertyDescriptor(globalThis, 'window');
let identifyCurrentUser: (typeof import('./posthog'))['identifyCurrentUserForPostHog'];

beforeAll(async (): Promise<void> => {
  Reflect.set(process.env, 'NODE_ENV', 'production');
  Object.defineProperty(globalThis, 'window', {
    configurable: true,
    value: { location: { hostname: 'harudle.com' } },
  });
  const module = await import('./posthog');
  identifyCurrentUser = module.identifyCurrentUserForPostHog;
});

afterAll((): void => {
  if (originalEnvironment === undefined)
    Reflect.deleteProperty(process.env, 'NODE_ENV');
  else Reflect.set(process.env, 'NODE_ENV', originalEnvironment);
  if (originalWindow)
    Object.defineProperty(globalThis, 'window', originalWindow);
  else Reflect.deleteProperty(globalThis, 'window');
});

const mockFetch = jest.fn<typeof fetch>();
beforeEach((): void => {
  mockFetch.mockReset();
  jest.spyOn(globalThis, 'fetch').mockImplementation(mockFetch);
});
afterEach((): void => {
  jest.restoreAllMocks();
});

describe('PostHog 사용자 표시 이름', (): void => {
  it('기존 me 요청의 UUID로 식별하면서 닉네임만 표시 이름으로 보낸다', async (): Promise<void> => {
    mockFetch.mockResolvedValueOnce(
      Response.json({
        id: 'member-uuid',
        nickname: '하루',
        name: '실명',
        email: 'private@example.com',
      }),
    );
    await identifyCurrentUser(posthog, 'access-token');

    expect(mockFetch).toHaveBeenCalledTimes(1);
    expect(mockFetch).toHaveBeenCalledWith('/api/v1/me', {
      headers: { Authorization: 'Bearer access-token' },
    });
    expect(mockIdentify).toHaveBeenCalledTimes(1);
    expect(mockIdentify).toHaveBeenCalledWith('member-uuid', { name: '하루' });
  });

  it.each([undefined, null, 123, '', '   '])(
    '닉네임이 없거나 잘못된 값이어도 UUID 식별은 유지한다: %j',
    async (nickname): Promise<void> => {
      mockFetch.mockResolvedValueOnce(
        Response.json({
          id: 'member-uuid',
          nickname,
          name: '실명',
        }),
      );
      await identifyCurrentUser(posthog, 'access-token');
      expect(mockIdentify).toHaveBeenCalledTimes(1);
      expect(mockIdentify).toHaveBeenCalledWith('member-uuid');
    },
  );

  it('닉네임만 있고 유효한 회원 ID가 없으면 식별하지 않는다', async (): Promise<void> => {
    mockFetch.mockResolvedValueOnce(Response.json({ nickname: '하루' }));
    await identifyCurrentUser(posthog, 'access-token');
    expect(mockIdentify).not.toHaveBeenCalled();
  });
});
