/** @jest-environment node */

import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { feedHandlers, resetFeedMockState } from './feedHandlers';

jest.mock('msw', () => {
  class MockHttpResponse extends Response {
    static json(data: unknown, init: ResponseInit = {}): MockHttpResponse {
      const headers = new Headers(init.headers);
      if (!headers.has('Content-Type'))
        headers.set('Content-Type', 'application/json');
      return new MockHttpResponse(JSON.stringify(data), { ...init, headers });
    }
  }

  const createHandler = (method: string) => {
    return (path: string, resolver: unknown) => ({ method, path, resolver });
  };

  return {
    delay: () => Promise.resolve(),
    http: {
      get: createHandler('GET'),
      put: createHandler('PUT'),
      post: createHandler('POST'),
      delete: createHandler('DELETE'),
    },
    HttpResponse: MockHttpResponse,
  };
});

jest.mock('../assets/images/diary-four-panel.webp', () => 'diary.webp');
jest.mock('../assets/icons/article_person.svg', () => 'profile.svg');

interface MockHandler {
  method: string;
  path: string;
  resolver: (args: {
    request: Request;
    params: Record<string, string>;
  }) => Promise<Response>;
}

// jest.mock에서 만든 핸들러의 내부 형태를 테스트에서만 사용한다.
const handlers = feedHandlers as unknown as MockHandler[];
const memberHeaders = {
  Authorization: 'Bearer mock-access-token',
};
const ownFeedId = '00000000-0000-4000-8000-000000000001';
const otherFeedId = '00000000-0000-4000-8000-000000000002';

const requestApi = async (
  path: string,
  init?: RequestInit,
): Promise<Response> => {
  const request = new Request(`http://localhost${path}`, init);
  const pathname = new URL(request.url).pathname;
  for (const handler of handlers) {
    if (handler.method !== request.method) continue;
    const pattern = new RegExp(
      `^${handler.path.replace(':feedId', '([^/]+)')}$`,
    );
    const match = pathname.match(pattern);
    if (match) {
      return handler.resolver({ request, params: { feedId: match[1] ?? '' } });
    }
  }
  throw new Error(`MSW 핸들러가 없습니다: ${request.method} ${path}`);
};

const listFeeds = async (
  query = '',
  headers?: HeadersInit,
): Promise<{
  items: Record<string, unknown>[];
  nextCursor: string | null;
  hasNext: boolean;
}> => {
  const response = await requestApi(`/api/v1/feeds${query}`, { headers });
  const data: unknown = await response.json();
  if (
    response.status !== 200 ||
    typeof data !== 'object' ||
    data === null ||
    !('items' in data) ||
    !Array.isArray(data.items) ||
    !('nextCursor' in data) ||
    (data.nextCursor !== null && typeof data.nextCursor !== 'string') ||
    !('hasNext' in data) ||
    typeof data.hasNext !== 'boolean'
  )
    throw new Error('피드 목록 응답 형식이 올바르지 않습니다.');

  const items: Record<string, unknown>[] = [];
  const rawItems: readonly unknown[] = data.items;
  for (const item of rawItems) {
    if (typeof item !== 'object' || item === null)
      throw new Error('피드 형식이 올바르지 않습니다.');
    items.push(Object.fromEntries(Object.entries(item)));
  }
  return { items, nextCursor: data.nextCursor, hasNext: data.hasNext };
};

beforeEach(() => resetFeedMockState());

describe('피드 페이지 MSW', () => {
  it('비로그인 카테고리와 목록 조회, 기본 크기, 마지막 페이지를 지원한다', async () => {
    const categories = await requestApi('/api/v1/categories');
    await expect(categories.json()).resolves.toEqual([
      { id: '1', name: '일상', sortOrder: 0, active: true },
      { id: '2', name: '우테코', sortOrder: 1, active: true },
    ]);
    const first = await listFeeds();
    expect(first.items).toHaveLength(20);
    expect(first.hasNext).toBe(true);
    expect(
      first.items.every(
        (feed): boolean => feed.isMine === false && feed.likedByMe === false,
      ),
    ).toBe(true);
    if (!first.nextCursor) throw new Error('다음 커서가 없습니다.');
    const last = await listFeeds(
      `?cursor=${encodeURIComponent(first.nextCursor)}`,
    );
    expect(last.items).toHaveLength(4);
    expect(last.nextCursor).toBeNull();
    expect(last.hasNext).toBe(false);
    expect(
      new Set([...first.items, ...last.items].map((feed) => feed.id)).size,
    ).toBe(24);
  });

  it('카테고리 필터와 최신순의 동일 시각 ID 내림차순을 반영한다', async () => {
    const filtered = await listFeeds('?categoryId=2&size=50');
    expect(filtered.items).toHaveLength(12);
    expect(
      filtered.items.every(
        (feed): boolean =>
          JSON.stringify(feed.category) ===
          JSON.stringify({ id: '2', name: '우테코' }),
      ),
    ).toBe(true);
    const latest = await listFeeds('?size=2');
    expect(latest.items.map((feed) => feed.id)).toEqual([
      otherFeedId,
      ownFeedId,
    ]);
  });

  it('잘못된 조건과 다른 조건의 커서를 거부한다', async () => {
    for (const query of [
      'sort=INVALID',
      'size=0',
      'size=51',
      'size=1.5',
      'categoryId=abc',
    ]) {
      const response = await requestApi(`/api/v1/feeds?${query}`);
      expect(response.status).toBe(400);
      await expect(response.json()).resolves.toMatchObject({
        code: 'VALIDATION_ERROR',
      });
    }
    expect((await requestApi('/api/v1/feeds?categoryId=999')).status).toBe(404);
    const first = await listFeeds('?size=1');
    for (const query of ['sort=POPULAR', 'categoryId=1']) {
      const response = await requestApi(
        `/api/v1/feeds?${query}&cursor=${encodeURIComponent(first.nextCursor ?? '')}`,
      );
      expect(response.status).toBe(400);
      expect(response.headers.get('Content-Type')).toBe(
        'application/problem+json',
      );
      await expect(response.json()).resolves.toMatchObject({
        code: 'INVALID_CURSOR',
      });
    }
    expect((await requestApi('/api/v1/feeds?cursor=broken')).status).toBe(400);
  });

  it('반복 좋아요가 중복 증감하지 않고 로그인 목록에 반영된다', async () => {
    const path = `/api/v1/feeds/${ownFeedId}/like`;
    for (let i = 0; i < 2; i += 1) {
      const response = await requestApi(path, {
        method: 'PUT',
        headers: memberHeaders,
      });
      await expect(response.json()).resolves.toEqual({
        likedByMe: true,
        likeCount: 1,
      });
    }
    const member = await listFeeds('', memberHeaders);
    expect(
      member.items.find((feed): boolean => feed.id === ownFeedId),
    ).toMatchObject({ likedByMe: true, isMine: true, likeCount: 1 });
    const guest = await listFeeds();
    expect(
      guest.items.find((feed): boolean => feed.id === ownFeedId),
    ).toMatchObject({ likedByMe: false, isMine: false, likeCount: 1 });
    for (let i = 0; i < 2; i += 1) {
      const response = await requestApi(path, {
        method: 'DELETE',
        headers: memberHeaders,
      });
      await expect(response.json()).resolves.toEqual({
        likedByMe: false,
        likeCount: 0,
      });
    }
  });

  it('인기순에 좋아요 수 변경과 동점 정렬을 반영한다', async () => {
    const first = await listFeeds('?sort=POPULAR&size=2');
    const secondId = first.items[1].id;
    if (typeof secondId !== 'string') throw new Error('피드 ID가 없습니다.');
    expect(first.items.map((feed) => feed.likeCount)).toEqual([12, 12]);
    await requestApi(`/api/v1/feeds/${secondId}/like`, {
      method: 'PUT',
      headers: memberHeaders,
    });
    const updated = await listFeeds('?sort=POPULAR&size=1');
    expect(updated.items[0]).toMatchObject({ id: secondId, likeCount: 13 });
  });

  it('Bearer 인증과 소유권을 검사하고 본인 피드만 삭제한다', async () => {
    expect(
      (
        await requestApi('/api/v1/feeds', {
          headers: { Authorization: 'Bearer expired' },
        })
      ).status,
    ).toBe(401);
    expect(
      (await requestApi(`/api/v1/feeds/${ownFeedId}/like`, { method: 'PUT' }))
        .status,
    ).toBe(401);
    expect(
      (
        await requestApi(`/api/v1/feeds/${ownFeedId}`, {
          method: 'DELETE',
          headers: { Authorization: 'Bearer expired' },
        })
      ).status,
    ).toBe(401);
    expect(
      (
        await requestApi(`/api/v1/feeds/${otherFeedId}`, {
          method: 'DELETE',
          headers: memberHeaders,
        })
      ).status,
    ).toBe(403);
    const deleted = await requestApi(`/api/v1/feeds/${ownFeedId}`, {
      method: 'DELETE',
      headers: memberHeaders,
    });
    expect(deleted.status).toBe(204);
    expect(await deleted.text()).toBe('');
    expect(
      (await listFeeds('?size=50')).items.some(
        (feed): boolean => feed.id === ownFeedId,
      ),
    ).toBe(false);
    const missing = await requestApi(`/api/v1/feeds/${ownFeedId}/like`, {
      method: 'PUT',
      headers: memberHeaders,
    });
    expect(missing.status).toBe(404);
    await expect(missing.json()).resolves.toMatchObject({
      code: 'FEED_NOT_FOUND',
    });
  });
});
