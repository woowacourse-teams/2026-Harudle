import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { getFeedDetail } from '../../domain/feeds/feedDetail';
import type { Feed } from '../../domain/feeds/feedList';
import useFeedDetail from './useFeedDetail';

const mockGetFeedDetail = jest.fn<typeof getFeedDetail>();
const mockCaptureError = jest.fn();
const mockTrack = jest.fn();
jest.mock('../../posthog/useAnalytics', () => ({
  useAnalytics: () => ({ track: mockTrack }),
}));
jest.mock('../../domain/feeds/feedDetail', () => ({
  getFeedDetail: (
    ...args: Parameters<typeof getFeedDetail>
  ): ReturnType<typeof getFeedDetail> => mockGetFeedDetail(...args),
}));
jest.mock('../../posthog/useErrorTracking', () => ({
  useErrorTracking: () => ({ captureError: mockCaptureError }),
}));

const feed: Feed = {
  id: 'feed-1',
  author: { id: 'user-1', nickname: '네오', profileImageUrl: '/profile.svg' },
  category: { id: 1, name: '일상' },
  imageUrl: '/comic.webp',
  imageUrlExpiresAt: '2099-10-11T12:00:00+09:00',
  publishedAt: '2026-10-11T12:00:00+09:00',
  likeCount: 12,
  likedByMe: false,
  commentCount: 1,
  isMine: false,
  shareUrl: 'https://harudle.com/feeds/feed-1',
};

beforeEach((): void => {
  mockGetFeedDetail.mockReset();
});

describe('피드 상세 요청 수명', (): void => {
  it('피드 전환 시 이전 요청을 취소하고 늦은 응답을 무시한다', async (): Promise<void> => {
    let resolvePrevious: ((value: Feed) => void) | undefined;
    mockGetFeedDetail.mockImplementationOnce(
      (): Promise<Feed> =>
        new Promise((resolve): void => {
          resolvePrevious = resolve;
        }),
    );
    mockGetFeedDetail.mockResolvedValueOnce({ ...feed, id: 'feed-2' });
    const { result, rerender } = renderHook(
      ({ feedId }) => useFeedDetail({ feedId }),
      { initialProps: { feedId: 'feed-1' } },
    );
    const signal = mockGetFeedDetail.mock.calls[0][0].signal;
    rerender({ feedId: 'feed-2' });
    await waitFor((): void => {
      expect(result.current.request).toMatchObject({
        status: 'success',
        data: { id: 'feed-2' },
      });
    });
    expect(signal?.aborted).toBe(true);
    await act(async (): Promise<void> => {
      resolvePrevious?.(feed);
    });
    expect(result.current.request).toMatchObject({
      status: 'success',
      data: { id: 'feed-2' },
    });
    expect(mockTrack.mock.calls.map(([event]): unknown => event)).toEqual([
      'api_requested',
      'api_requested',
    ]);
  });
});
