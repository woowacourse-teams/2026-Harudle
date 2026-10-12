import { describe, expect, it } from '@jest/globals';
import { isFeedListResponse, type Feed } from './feedList';

const feed: Feed = {
  id: '00000000-0000-4000-8000-000000000001',
  author: {
    id: '08d69a34-6d70-4d42-a158-671bc67733c9',
    nickname: '하루들',
    profileImageUrl: '/profile.svg',
  },
  category: { id: 1, name: '일상' },
  imageUrl: '/diary.webp',
  imageUrlExpiresAt: '2026-10-10T16:00:00+09:00',
  publishedAt: '2026-10-10T15:00:00+09:00',
  likeCount: 12,
  commentCount: 3,
  likedByMe: false,
  isMine: false,
  shareUrl: 'https://harudle.com/feeds/00000000-0000-4000-8000-000000000001',
};

describe('피드 목록 응답 검증', () => {
  it('빈 목록, 다음 페이지와 마지막 페이지를 허용한다', (): void => {
    expect(
      isFeedListResponse({ items: [], nextCursor: null, hasNext: false }),
    ).toBe(true);
    expect(
      isFeedListResponse({
        items: [feed],
        nextCursor: 'opaque-cursor',
        hasNext: true,
      }),
    ).toBe(true);
    expect(
      isFeedListResponse({ items: [feed], nextCursor: null, hasNext: false }),
    ).toBe(true);
  });

  it.each([
    null,
    [],
    { items: null, nextCursor: null, hasNext: false },
    { items: [feed], hasNext: false },
    { items: [feed], nextCursor: 1, hasNext: false },
    { items: [feed], nextCursor: null, hasNext: 'false' },
  ])('잘못된 목록 구조를 거부한다: %j', (value): void => {
    expect(isFeedListResponse(value)).toBe(false);
  });

  it.each([
    { author: null },
    { author: { ...feed.author, nickname: 1 } },
    { author: { ...feed.author, profileImageUrl: null } },
    { category: { id: '1', name: '일상' } },
    { category: { id: 0, name: '일상' } },
    { category: { id: -1, name: '일상' } },
    { category: { id: 1.5, name: '일상' } },
    { category: { id: NaN, name: '일상' } },
    { category: { id: Number.MAX_SAFE_INTEGER + 1, name: '일상' } },
    { category: { id: '1' } },
    { imageUrl: null },
    { imageUrlExpiresAt: undefined },
    { publishedAt: undefined },
    { likeCount: -1 },
    { likeCount: NaN },
    { likeCount: '12' },
    { commentCount: 1.5 },
    { commentCount: null },
    { likedByMe: 'false' },
    { isMine: undefined },
    { shareUrl: null },
  ])('잘못된 중첩 데이터와 필드를 거부한다: %j', (invalidFields): void => {
    expect(
      isFeedListResponse({
        items: [{ ...feed, ...invalidFields }],
        nextCursor: null,
        hasNext: false,
      }),
    ).toBe(false);
  });
});
