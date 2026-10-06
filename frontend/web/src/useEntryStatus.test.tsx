import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { act, renderHook, waitFor } from '@testing-library/react';
import { RequestError } from './shared/api';
import useEntryStatus from './useEntryStatus';

const mockRestoreAccessToken = jest.fn<() => Promise<void>>();
const mockCaptureError = jest.fn();

jest.mock('./shared/auth', () => ({
  restoreAccessToken: () => mockRestoreAccessToken(),
}));

jest.mock('./posthog/useErrorTracking', () => ({
  useErrorTracking: () => ({ captureError: mockCaptureError }),
}));

beforeEach(() => {
  localStorage.clear();
  mockRestoreAccessToken.mockReset();
});

describe('useEntryStatus', () => {
  it('로그인 경험이 없으면 세션을 조회하지 않고 랜딩을 선택한다', () => {
    const { result } = renderHook(() => useEntryStatus());

    expect(result.current.status).toBe('landing');
    expect(mockRestoreAccessToken).not.toHaveBeenCalled();
  });

  it('세션 복구 중 상태를 유지하고 성공하면 홈을 선택한다', async () => {
    localStorage.setItem('harudle.has-ever-logged-in', 'true');
    let finishRestore: (() => void) | undefined;
    mockRestoreAccessToken.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finishRestore = resolve;
        }),
    );
    const { result } = renderHook(() => useEntryStatus());

    expect(result.current.status).toBe('restoringSession');

    await act(async () => {
      finishRestore?.();
    });

    expect(result.current.status).toBe('home');
  });

  it('세션 만료 시 로그인 경험을 유지하고 로그인을 선택한다', async () => {
    localStorage.setItem('harudle.has-ever-logged-in', 'true');
    mockRestoreAccessToken.mockRejectedValueOnce(
      new RequestError({
        type: 'about:blank',
        title: 'Invalid refresh token',
        status: 401,
        detail: '세션 만료',
        instance: '/api/v1/auth/refresh',
        code: 'INVALID_REFRESH_TOKEN',
      }),
    );
    const { result } = renderHook(() => useEntryStatus());

    await waitFor(() => expect(result.current.status).toBe('login'));
    expect(localStorage.getItem('harudle.has-ever-logged-in')).toBe('true');
    expect(mockCaptureError).not.toHaveBeenCalled();
  });

  it.each([
    new TypeError('Failed to fetch'),
    new RequestError({
      type: 'about:blank',
      title: 'Server unavailable',
      status: 503,
      detail: '서버 오류',
      instance: '/api/v1/auth/refresh',
      code: 'SERVICE_UNAVAILABLE',
    }),
  ])(
    '네트워크·서버 실패는 로그인 이동 대신 재시도할 수 있다: %s',
    async (error) => {
      localStorage.setItem('harudle.has-ever-logged-in', 'true');
      mockRestoreAccessToken
        .mockRejectedValueOnce(error)
        .mockResolvedValueOnce();
      const { result } = renderHook(() => useEntryStatus());

      await waitFor(() => expect(result.current.status).toBe('error'));
      expect(mockCaptureError).toHaveBeenCalledWith(error, {
        feature: 'authentication',
        operation: 'restore_session',
      });

      act(() => result.current.retry());
      expect(result.current.status).toBe('restoringSession');
      await waitFor(() => expect(result.current.status).toBe('home'));
      expect(mockRestoreAccessToken).toHaveBeenCalledTimes(2);
    },
  );
});
