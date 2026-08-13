import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, jest } from '@jest/globals';
import { useDelayedLoading } from './useDelayedLoading';

afterEach(() => {
  jest.useRealTimers();
});

describe('useDelayedLoading', () => {
  it('500ms 안에 로딩이 끝나면 로딩 UI를 표시하지 않는다', () => {
    jest.useFakeTimers();
    const { result, rerender } = renderHook(
      ({ isLoading }) => useDelayedLoading(isLoading),
      { initialProps: { isLoading: true } },
    );

    act(() => jest.advanceTimersByTime(499));
    rerender({ isLoading: false });
    act(() => jest.advanceTimersByTime(1));

    expect(result.current).toBe(false);
  });

  it('500ms가 지나도 요청 중이면 로딩 UI를 표시한다', () => {
    jest.useFakeTimers();
    const { result } = renderHook(() => useDelayedLoading(true));

    act(() => jest.advanceTimersByTime(500));

    expect(result.current).toBe(true);
  });

  it('응답이 오면 표시 중인 로딩 UI를 즉시 닫는다', () => {
    jest.useFakeTimers();
    const { result, rerender } = renderHook(
      ({ isLoading }) => useDelayedLoading(isLoading),
      { initialProps: { isLoading: true } },
    );
    act(() => jest.advanceTimersByTime(500));

    rerender({ isLoading: false });

    expect(result.current).toBe(false);
  });
});
