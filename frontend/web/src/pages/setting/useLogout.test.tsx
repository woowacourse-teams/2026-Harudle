import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { act, renderHook } from '@testing-library/react';
import useLogout from './useLogout';

const mockNavigate = jest.fn<(...args: unknown[]) => void>();
const mockResetUser = jest.fn<() => void>();
const mockRequestCsrfToken = jest.fn<() => Promise<string>>();
const mockSetAccessToken = jest.fn<(token: string | null) => void>();
const mockLogout = jest.fn<(...args: unknown[]) => Promise<void>>();

jest.mock('react-router', () => ({
  useNavigate: () => mockNavigate,
}));

jest.mock('../../shared/useAnalytics', () => ({
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

  it('로그아웃에 성공하면 클라이언트 인증 상태를 정리하고 로그인 화면으로 이동한다', async () => {
    mockRequestCsrfToken.mockResolvedValueOnce('csrf-token');
    mockLogout.mockResolvedValueOnce();
    localStorage.setItem('harudle.has-completed-oauth', 'true');
    const { result } = renderHook(() => useLogout());

    await act(async () => {
      await result.current.handleLogout();
    });

    expect(mockResetUser).toHaveBeenCalledTimes(1);
    expect(mockSetAccessToken).toHaveBeenCalledWith(null);
    expect(localStorage.getItem('harudle.has-completed-oauth')).toBeNull();
    expect(mockNavigate).toHaveBeenCalledWith('/login');
  });
});
