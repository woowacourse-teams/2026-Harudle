import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { act, renderHook } from '@testing-library/react';
import useLogout from './useLogout';

const mockResetUser = jest.fn<() => void>();
const mockRequestCsrfToken = jest.fn<() => Promise<string>>();
const mockSetAccessToken = jest.fn<(token: string | null) => void>();
const mockLogout = jest.fn<(...args: unknown[]) => Promise<void>>();

jest.mock('../../posthog/useAnalytics', () => ({
  useAnalytics: () => ({ resetUser: mockResetUser }),
}));

jest.mock('../../shared/auth', () => ({
  requestCsrfToken: () => mockRequestCsrfToken(),
  setAccessToken: (token: string | null) => mockSetAccessToken(token),
}));

jest.mock('../../domain/user/logout', () => ({
  logout: (...args: unknown[]) => mockLogout(...args),
}));

describe('useLogout', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('로그아웃에 실패하면 인증 정보를 유지하고 오류를 표시한다', async () => {
    const error = new Error('로그아웃에 실패했습니다.');
    mockRequestCsrfToken.mockResolvedValueOnce('csrf-token');
    mockLogout.mockRejectedValueOnce(error);
    localStorage.setItem('harudle.has-ever-logged-in', 'true');
    localStorage.setItem('harudle.has-completed-oauth', 'true');
    const { result } = renderHook(() => useLogout());

    await act(async () => {
      await result.current.handleLogout();
    });

    expect(result.current.request).toEqual({ status: 'error', error });
    expect(mockResetUser).not.toHaveBeenCalled();
    expect(mockSetAccessToken).not.toHaveBeenCalled();
    expect(localStorage.getItem('harudle.has-ever-logged-in')).toBe('true');
    expect(localStorage.getItem('harudle.has-completed-oauth')).toBe('true');
  });
});
