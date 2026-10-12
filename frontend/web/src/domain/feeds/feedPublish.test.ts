/** @jest-environment node */

import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { publishFeed } from './feedPublish';
import { authFetch } from '../../shared/auth';
import type { Feed } from './feedList';
import { ERROR_MESSAGES } from '../../shared/errorMessage';

jest.mock('../../shared/auth', () => ({ authFetch: jest.fn() }));

const feed: Feed = {
  id: 'feed-id',
  author: { id: 'user-id', nickname: '하루들', profileImageUrl: '' },
  category: { id: 1, name: '일상' },
  imageUrl: '/comic.webp',
  imageUrlExpiresAt: '2026-10-13T12:00:00+09:00',
  publishedAt: '2026-10-13T11:00:00+09:00',
  likeCount: 0,
  commentCount: 0,
  likedByMe: false,
  isMine: true,
  shareUrl: '/feeds/feed-id',
};

beforeEach((): void => {
  jest.mocked(authFetch).mockReset();
});

describe('피드 게시 API 계약', () => {
  it('카테고리 ID를 숫자로 보내고 숫자형 ID의 201 응답을 수용한다', async (): Promise<void> => {
    jest
      .mocked(authFetch)
      .mockResolvedValue(Response.json(feed, { status: 201 }));
    const request = { diaryId: 'diary-id', categoryId: 1 };

    await expect(publishFeed(request)).resolves.toEqual(feed);
    expect(authFetch).toHaveBeenCalledTimes(1);
    expect(authFetch).toHaveBeenCalledWith('/api/v1/feeds', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
  });

  it('서버 계약과 다른 문자열 카테고리 ID 응답은 거부한다', async (): Promise<void> => {
    jest.mocked(authFetch).mockResolvedValue(
      Response.json(
        {
          ...feed,
          category: { ...feed.category, id: '1' },
        },
        { status: 201 },
      ),
    );

    await expect(
      publishFeed({ diaryId: 'diary-id', categoryId: 1 }),
    ).rejects.toThrow(ERROR_MESSAGES.INVALID_FEED_PUBLISH_RESPONSE);
  });
});
