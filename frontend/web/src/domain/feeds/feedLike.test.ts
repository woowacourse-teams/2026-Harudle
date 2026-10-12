/** @jest-environment node */

import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { isFeedLikeResponse, setFeedLike } from './feedLike';
import { authFetch, requestCsrfToken } from '../../shared/auth';

jest.mock('../../shared/auth', () => ({
  authFetch: jest.fn(),
  requestCsrfToken: jest.fn(),
}));

beforeEach((): void => {
  jest.mocked(authFetch).mockReset();
  jest.mocked(requestCsrfToken).mockReset();
});

describe('피드 좋아요 요청', (): void => {
  it.each([true, false])(
    '발급받은 CSRF 토큰으로 좋아요를 변경한다 (좋아요: %s)',
    async (likedByMe): Promise<void> => {
      const data = { likedByMe, likeCount: likedByMe ? 1 : 0 };
      jest.mocked(requestCsrfToken).mockResolvedValue('csrf-token');
      jest.mocked(authFetch).mockResolvedValue(Response.json(data));

      await expect(
        setFeedLike({ feedId: 'feed-id', likedByMe }),
      ).resolves.toEqual(data);
      expect(requestCsrfToken).toHaveBeenCalledTimes(1);
      expect(authFetch).toHaveBeenCalledTimes(1);
      expect(authFetch).toHaveBeenCalledWith('/api/v1/feeds/feed-id/like', {
        method: likedByMe ? 'PUT' : 'DELETE',
        headers: { 'X-XSRF-TOKEN': 'csrf-token' },
      });
    },
  );

  it('CSRF 발급 실패 시 좋아요 요청 없이 오류를 전달한다', async (): Promise<void> => {
    const error = new Error('CSRF 발급 실패');
    jest.mocked(requestCsrfToken).mockRejectedValue(error);

    await expect(
      setFeedLike({ feedId: 'feed-id', likedByMe: true }),
    ).rejects.toBe(error);
    expect(authFetch).not.toHaveBeenCalled();
  });
});

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
