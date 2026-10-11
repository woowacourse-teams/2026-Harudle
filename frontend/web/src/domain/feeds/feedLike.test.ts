import { describe, expect, it } from '@jest/globals';
import { isFeedLikeResponse } from './feedLike';

describe('피드 좋아요 응답 검증', (): void => {
  it('좋아요와 취소 응답을 허용한다', (): void => {
    expect(isFeedLikeResponse({ likedByMe: true, likeCount: 13 })).toBe(true);
    expect(isFeedLikeResponse({ likedByMe: false, likeCount: 0 })).toBe(true);
  });

  it.each([
    null,
    {},
    { likedByMe: 'false', likeCount: 0 },
    { likedByMe: true, likeCount: -1 },
    { likedByMe: true, likeCount: 1.5 },
    { likedByMe: true, likeCount: NaN },
    { likedByMe: true, likeCount: Infinity },
    { likedByMe: true, likeCount: '1' },
  ])('잘못된 반응 상태와 개수를 거부한다: %j', (value): void => {
    expect(isFeedLikeResponse(value)).toBe(false);
  });
});
