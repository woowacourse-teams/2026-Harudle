import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { act, render, waitFor } from '@testing-library/react';
import { StrictMode } from 'react';
import AuthCallbackPage from './AuthCallbackPage';
import { requestCsrfToken, setAccessToken } from '../../shared/auth';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { LOGIN_COPY } from './copy';
const mockNavigate = jest.fn();
const mockIdentifyCurrentUser = jest.fn();
let mockSearch = '';
jest.mock('react-router', () => ({
  useNavigate: () => mockNavigate,
  useLocation: () => ({ search: mockSearch }),
}));
jest.mock('../../posthog/useAnalytics', () => ({
  useAnalytics: () => ({ identifyCurrentUser: mockIdentifyCurrentUser }),
}));
jest.mock('../../shared/auth', () => ({
  ...jest.requireActual<typeof import('../../shared/auth')>(
    '../../shared/auth',
  ),
  requestCsrfToken: jest.fn(),
  setAccessToken: jest.fn(),
}));
const mockFetch = jest.fn<
  (
    input: RequestInfo | URL,
    init?: RequestInit,
  ) => Promise<{
    readonly ok: boolean;
    readonly json: () => Promise<unknown>;
  }>
>();
const originalFetch = globalThis.fetch;
const originalAlert = window.alert;
const mockAlert = jest.fn();
beforeEach((): void => {
  mockSearch = '';
  mockNavigate.mockReset();
  mockIdentifyCurrentUser.mockReset();
  mockAlert.mockReset();
  mockFetch.mockReset().mockResolvedValue({
    ok: true,
    json: async (): Promise<unknown> => ({
      accessToken: 'old-cookie-account-token',
      tokenType: 'Bearer',
      expiresIn: 1800,
    }),
  });
  jest.mocked(requestCsrfToken).mockReset().mockResolvedValue('valid-csrf');
  jest.mocked(setAccessToken).mockReset();
  localStorage.clear();
  localStorage.setItem('harudle.has-completed-oauth', 'true');
  document.cookie = 'refreshToken=existing-valid-session; path=/';
  Object.defineProperty(globalThis, 'fetch', {
    configurable: true,
    writable: true,
    value: mockFetch,
  });
  window.alert = mockAlert;
});
afterEach((): void => {
  Object.defineProperty(globalThis, 'fetch', {
    configurable: true,
    writable: true,
    value: originalFetch,
  });
  window.alert = originalAlert;
  document.cookie = 'refreshToken=; Max-Age=0; path=/';
});
describe('OAuth 콜백 결과', (): void => {
  it.each([
    ['?error=account_suspended', LOGIN_COPY.suspendedAccount],
    [
      '?error=%3Cscript%3Eraw-error%3C%2Fscript%3E',
      ERROR_MESSAGES.LOGIN_FAILED,
    ],
    ['?error=', ERROR_MESSAGES.LOGIN_FAILED],
  ])(
    '명시된 실패 %s는 기존 정상 세션이 있어도 복원하지 않는다',
    (query, message): void => {
      mockSearch = query;
      render(
        <StrictMode>
          <AuthCallbackPage />
        </StrictMode>,
      );
      expect(mockAlert).toHaveBeenCalledTimes(1);
      expect(mockAlert).toHaveBeenCalledWith(message);
      expect(mockNavigate).toHaveBeenCalledTimes(1);
      expect(mockNavigate).toHaveBeenCalledWith('/login', { replace: true });
      expect(requestCsrfToken).not.toHaveBeenCalled();
      expect(mockFetch).not.toHaveBeenCalled();
      expect(setAccessToken).not.toHaveBeenCalled();
      expect(mockIdentifyCurrentUser).not.toHaveBeenCalled();
    },
  );
  it('실패 쿼리가 없는 성공 콜백은 기존 CSRF·토큰 복원과 홈 이동을 유지한다', async (): Promise<void> => {
    render(<AuthCallbackPage />);
    await waitFor((): void => expect(mockNavigate).toHaveBeenCalledWith('/'));
    expect(requestCsrfToken).toHaveBeenCalledTimes(1);
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/v1/auth/refresh',
      expect.objectContaining({
        method: 'POST',
        credentials: 'include',
        headers: { 'X-XSRF-TOKEN': 'valid-csrf' },
      }),
    );
    expect(setAccessToken).toHaveBeenCalledWith('old-cookie-account-token');
    expect(mockIdentifyCurrentUser).toHaveBeenCalledWith(
      'old-cookie-account-token',
    );
    expect(mockAlert).not.toHaveBeenCalled();
    expect(localStorage.getItem('harudle.has-ever-logged-in')).toBe('true');
  });
  it('복원 도중 실패 콜백으로 바뀌면 늦은 정상 응답도 로그인 성공으로 처리하지 않는다', async (): Promise<void> => {
    const lateReply = {
      ok: true,
      json: async (): Promise<unknown> => ({
        accessToken: 'old-cookie-account-token',
        tokenType: 'Bearer',
        expiresIn: 1800,
      }),
    };
    let finish: (value: typeof lateReply) => void = (): void => undefined;
    mockFetch.mockImplementationOnce(
      (): Promise<typeof lateReply> =>
        new Promise((resolve): void => {
          finish = resolve;
        }),
    );
    const { rerender } = render(<AuthCallbackPage />);
    await waitFor((): void => expect(mockFetch).toHaveBeenCalledTimes(1));
    mockSearch = '?error=account_suspended';
    rerender(<AuthCallbackPage />);
    expect(mockNavigate).toHaveBeenCalledWith('/login', { replace: true });
    await act(async (): Promise<void> => finish(lateReply));
    expect(mockNavigate).not.toHaveBeenCalledWith('/');
    expect(setAccessToken).not.toHaveBeenCalled();
    expect(mockIdentifyCurrentUser).not.toHaveBeenCalled();
    expect(mockAlert).toHaveBeenCalledTimes(1);
  });
});
