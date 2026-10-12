import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { act, renderHook } from '@testing-library/react';
import type { deleteFeed } from '../../domain/feeds/feedDelete';
import useFeedDelete from './useFeedDelete';

const mockDeleteFeed = jest.fn<typeof deleteFeed>();
const mockCaptureError = jest.fn();

jest.mock('../../domain/feeds/feedDelete', () => ({
  deleteFeed: (
    ...args: Parameters<typeof deleteFeed>
  ): ReturnType<typeof deleteFeed> => mockDeleteFeed(...args),
}));
jest.mock('../../posthog/useErrorTracking', () => ({
  useErrorTracking: () => ({ captureError: mockCaptureError }),
}));

beforeEach((): void => {
  mockDeleteFeed.mockReset();
  mockCaptureError.mockReset();
});

describe('피드 삭제 요청과 성공 후 처리', (): void => {
  it('삭제 실패는 오류 상태로 남기고 성공 콜백은 실행하지 않는다', async (): Promise<void> => {
    const error = new Error('피드 삭제 실패');
    mockDeleteFeed.mockRejectedValue(error);
    const onSuccess = jest.fn<() => void>();
    const { result } = renderHook(() => useFeedDelete({ feedId: 'feed-1' }));

    await act(async (): Promise<void> => {
      await result.current.execute({ onSuccess });
    });

    expect(result.current.request).toEqual({ status: 'error', error });
    expect(onSuccess).not.toHaveBeenCalled();
    expect(mockCaptureError).toHaveBeenCalledWith(error, {
      feature: 'feed',
      operation: 'delete',
      feed_id: 'feed-1',
    });
  });

  it('성공 후 처리에서 발생한 오류를 서버 삭제 실패로 바꾸지 않는다', async (): Promise<void> => {
    mockDeleteFeed.mockResolvedValue(undefined);
    const callbackError = new Error('화면 후처리 실패');
    const onSuccess = (): void => {
      throw callbackError;
    };
    const { result } = renderHook(() => useFeedDelete({ feedId: 'feed-1' }));

    await act(async (): Promise<void> => {
      await expect(result.current.execute({ onSuccess })).rejects.toThrow(
        callbackError,
      );
    });

    expect(result.current.request).toEqual({
      status: 'success',
      data: undefined,
    });
    expect(mockCaptureError).not.toHaveBeenCalled();
    expect(mockDeleteFeed).toHaveBeenCalledTimes(1);
  });
});
