/** @jest-environment node */
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { blockHandlers } from './blockHandlers';
import { feedHandlers, resetFeedMockState } from './feedHandlers';
import { isBlockedUsersResponse } from '../domain/blocks/blocks';
import { isFeedListResponse } from '../domain/feeds/feedList';
jest.mock('msw', () => {
  class MockResponse extends Response {
    static json(data: unknown, init: ResponseInit = {}): Response {
      return new MockResponse(JSON.stringify(data), init);
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
      put: handler('PUT'),
      post: handler('POST'),
      delete: handler('DELETE'),
    },
    HttpResponse: MockResponse,
  };
});
jest.mock('../assets/images/diary-four-panel.webp', () => 'diary.webp');
jest.mock('../assets/icons/article_person.svg', () => 'profile.svg');
interface Handler {
  readonly method: string;
  readonly path: string;
  readonly resolver: (args: {
    request: Request;
    params: Record<string, string>;
  }) => Promise<Response>;
}
// 위 MSW mock의 resolver를 HTTP 경계와 함께 실행한다.
const handlers = [...blockHandlers, ...feedHandlers] as unknown as Handler[];
const headers = {
  Authorization: 'Bearer mock-access-token',
  'X-XSRF-TOKEN': 'mock-csrf-token',
};
const target = '00000000-0000-4000-8000-000000000100';
const originalStorage = Object.getOwnPropertyDescriptor(
  globalThis,
  'sessionStorage',
);
beforeEach((): void => {
  const values = new Map<string, string>();
  Object.defineProperty(globalThis, 'sessionStorage', {
    configurable: true,
    value: {
      getItem: (key: string): string | null => values.get(key) ?? null,
      setItem: (key: string, value: string): void => {
        values.set(key, value);
      },
    },
  });
  resetFeedMockState();
});
afterEach((): void => {
  if (originalStorage)
    Object.defineProperty(globalThis, 'sessionStorage', originalStorage);
  else Reflect.deleteProperty(globalThis, 'sessionStorage');
});
const requestApi = async (
  url: string,
  method = 'GET',
  authenticated = true,
): Promise<Response> => {
  const request = new Request(`http://localhost${url}`, {
    method,
    headers: authenticated ? headers : {},
  });
  for (const handler of handlers) {
    if (handler.method !== method) continue;
    const match = new URL(request.url).pathname.match(
      new RegExp(`^${handler.path.replace(/:(userId|feedId)/g, '([^/]+)')}$`),
    );
    if (match)
      return handler.resolver({
        request,
        params: { userId: match[1] ?? '', feedId: match[1] ?? '' },
      });
  }
  throw new Error('핸들러 없음');
};
describe('차단 MSW 계약', (): void => {
  it('멱등 차단·본인목록·회원 숨김·익명공개·해제를 재현한다', async (): Promise<void> => {
    expect((await requestApi(`/api/v1/blocks/${target}`, 'PUT')).status).toBe(
      204,
    );
    expect((await requestApi(`/api/v1/blocks/${target}`, 'PUT')).status).toBe(
      204,
    );
    const list: unknown = await (
      await requestApi('/api/v1/blocks?size=20')
    ).json();
    expect(isBlockedUsersResponse(list)).toBe(true);
    if (!isBlockedUsersResponse(list)) throw new Error('잘못된 응답');
    expect(list.items).toHaveLength(1);
    expect(list.items[0].userId).toBe(target);
    const member: unknown = await (
      await requestApi('/api/v1/feeds?size=50')
    ).json();
    const anonymous: unknown = await (
      await requestApi('/api/v1/feeds?size=50', 'GET', false)
    ).json();
    if (!isFeedListResponse(member) || !isFeedListResponse(anonymous))
      throw new Error('잘못된 피드 응답');
    expect(
      member.items.some((feed): boolean => feed.author.id === target),
    ).toBe(false);
    expect(
      anonymous.items.some((feed): boolean => feed.author.id === target),
    ).toBe(true);
    expect(
      (await requestApi(`/api/v1/blocks/${target}`, 'DELETE')).status,
    ).toBe(204);
    const restored: unknown = await (
      await requestApi('/api/v1/feeds?size=50')
    ).json();
    if (!isFeedListResponse(restored)) throw new Error('잘못된 응답');
    expect(
      restored.items.some((feed): boolean => feed.author.id === target),
    ).toBe(true);
  });
  it('인증과 자기차단 정책을 거절한다', async (): Promise<void> => {
    expect((await requestApi('/api/v1/blocks', 'GET', false)).status).toBe(401);
    expect(
      (
        await requestApi(
          '/api/v1/blocks/08d69a34-6d70-4d42-a158-671bc67733c9',
          'PUT',
        )
      ).status,
    ).toBe(400);
  });
});
