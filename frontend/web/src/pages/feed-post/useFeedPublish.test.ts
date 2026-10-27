import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { act, renderHook } from '@testing-library/react';
import type { publishFeed } from '../../domain/feeds/feedPublish';
import useFeedPublish from './useFeedPublish';

const mockRequest = jest.fn<typeof publishFeed>();
const mockCaptureError = jest.fn();
jest.mock('../../domain/feeds/feedPublish', () => ({
  publishFeed: (
    ...args: Parameters<typeof publishFeed>
  ): ReturnType<typeof publishFeed> => mockRequest(...args),
}));
jest.mock('../../posthog/useErrorTracking', () => ({
  useErrorTracking: () => ({ captureError: mockCaptureError }),
}));
jest.mock('../../posthog/useAnalytics', () => ({
  useAnalytics: () => ({ track: jest.fn() }),
}));

const response: Awaited<ReturnType<typeof publishFeed>> = {
  id: 'feed-1',
  author: { id: 'user-1', nickname: '하루들', profileImageUrl: '/profile.svg' },
  category: { id: 1, name: '일상' },
  imageUrl: '/comic.webp',
  imageUrlExpiresAt: '2099-10-12T12:00:00+09:00',
  publishedAt: '2026-10-12T12:00:00+09:00',
  likeCount: 0,
  commentCount: 0,
  likedByMe: false,
  isMine: true,
  shareUrl: 'https://harudle.com/feeds/feed-1',
};

beforeEach((): void => {
  mockRequest.mockReset();
  mockCaptureError.mockReset();
});

describe('요청과 성공 콜백의 오류 구분', (): void => {
  it('성공 콜백의 예외를 요청 실패로 처리하지 않는다', async (): Promise<void> => {
    mockRequest.mockResolvedValue(response);
    const callbackError = new Error('화면 후처리 실패');
    const onSuccess = (): void => {
      throw callbackError;
    };
    const onError = jest.fn();
    const { result } = renderHook(() => useFeedPublish({ diaryId: 'diary-1' }));

    await act(async (): Promise<void> => {
      await expect(
        result.current.execute({ categoryId: 1, onSuccess, onError }),
      ).rejects.toThrow(callbackError);
    });

    expect(result.current.request).toEqual({
      status: 'success',
      data: response,
    });
    expect(mockCaptureError).not.toHaveBeenCalled();
    expect(onError).not.toHaveBeenCalled();
    expect(mockRequest).toHaveBeenCalledTimes(1);
  });

  it('요청 실패 시 성공 콜백을 실행하지 않는다', async (): Promise<void> => {
    const error = new Error('요청 실패');
    mockRequest.mockRejectedValue(error);
    const onSuccess = jest.fn<() => void>();
    const onError = jest.fn();
    const { result } = renderHook(() => useFeedPublish({ diaryId: 'diary-1' }));

    await act(async (): Promise<void> => {
      await result.current.execute({ categoryId: 1, onSuccess, onError });
    });

    expect(result.current.request).toEqual({ status: 'error', error });
    expect(onSuccess).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledWith(error);
  });
});
