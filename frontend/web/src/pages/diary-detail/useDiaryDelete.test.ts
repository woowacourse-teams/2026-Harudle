import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { act, renderHook } from '@testing-library/react';
import type { deleteDiary } from '../../domain/diary/diaryDelete';
import useDiaryDelete from './useDiaryDelete';

const mockRequest = jest.fn<typeof deleteDiary>();
const mockCaptureError = jest.fn();
jest.mock('../../domain/diary/diaryDelete', () => ({
  deleteDiary: (
    ...args: Parameters<typeof deleteDiary>
  ): ReturnType<typeof deleteDiary> => mockRequest(...args),
}));
jest.mock('../../posthog/useErrorTracking', () => ({
  useErrorTracking: () => ({ captureError: mockCaptureError }),
}));

beforeEach((): void => {
  mockRequest.mockReset();
  mockCaptureError.mockReset();
});

describe('요청과 성공 콜백의 오류 구분', (): void => {
  it('성공 콜백의 예외를 요청 실패로 처리하지 않는다', async (): Promise<void> => {
    mockRequest.mockResolvedValue(undefined);
    const callbackError = new Error('화면 후처리 실패');
    const onSuccess = (): void => {
      throw callbackError;
    };
    const { result } = renderHook(() => useDiaryDelete({ diaryId: 'diary-1' }));

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
    expect(mockRequest).toHaveBeenCalledTimes(1);
  });

  it('요청 실패 시 성공 콜백을 실행하지 않는다', async (): Promise<void> => {
    const error = new Error('요청 실패');
    mockRequest.mockRejectedValue(error);
    const onSuccess = jest.fn<() => void>();
    const { result } = renderHook(() => useDiaryDelete({ diaryId: 'diary-1' }));

    await act(async (): Promise<void> => {
      await result.current.execute({ onSuccess });
    });

    expect(result.current.request).toEqual({ status: 'error', error });
    expect(onSuccess).not.toHaveBeenCalled();
  });
});
