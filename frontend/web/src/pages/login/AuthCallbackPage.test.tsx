import { StrictMode } from 'react';
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { act, render, waitFor } from '@testing-library/react';
import AuthCallbackPage from './AuthCallbackPage';
import { requestCsrfToken, setAccessToken } from '../../shared/auth';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { LOGIN_COPY } from './copy';

const mockNavigate = jest.fn();
const mockIdentifyCurrentUser = jest.fn();
const mockResetUser = jest.fn();
const mockSearchParams = new URLSearchParams();

jest.mock('react-router', () => ({
  useNavigate: () => mockNavigate,
  useSearchParams: () => [mockSearchParams],
}));
jest.mock('../../posthog/useAnalytics', () => ({
  useAnalytics: () => ({
    identifyCurrentUser: mockIdentifyCurrentUser,
    resetUser: mockResetUser,
  }),
}));
jest.mock('../../shared/auth', () => ({
  ...jest.requireActual<typeof import('../../shared/auth')>(
    '../../shared/auth',
  ),
  requestCsrfToken: jest.fn<() => Promise<string>>(),
  setAccessToken: jest.fn(),
}));

const fetchMock = jest.fn<typeof fetch>();
const originalFetch = globalThis.fetch;
const tokenResponse = {
  accessToken: 'access-token',
  tokenType: 'Bearer',
  expiresIn: 3600,
};

beforeEach((): void => {
  jest.clearAllMocks();
  localStorage.clear();
  sessionStorage.clear();
  for (const key of [...mockSearchParams.keys()]) mockSearchParams.delete(key);
  globalThis.fetch = fetchMock;
  fetchMock.mockReset();
  jest.mocked(requestCsrfToken).mockReset().mockResolvedValue('csrf-token');
  jest.spyOn(window, 'alert').mockImplementation((): void => {});
});

describe('구글 회원 탈퇴 콜백', (): void => {
  it('탈퇴 성공 시 로그인 정보를 정리하고 로그인 토큰을 다시 요청하지 않는다', (): void => {
    mockSearchParams.set('withdrawal', 'success');
    localStorage.setItem('harudle.has-completed-oauth', 'true');
    render(
      <StrictMode>
        <AuthCallbackPage />
      </StrictMode>,
    );
    expect(setAccessToken).toHaveBeenCalledWith(null);
    expect(localStorage.getItem('harudle.has-completed-oauth')).toBeNull();
    expect(mockResetUser).toHaveBeenCalledTimes(1);
    expect(mockNavigate).toHaveBeenCalledWith('/', { replace: true });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('탈퇴 실패 시 로그인 정보는 유지하고 설정 화면으로 돌아간다', (): void => {
    mockSearchParams.set('withdrawal', 'failed');
    localStorage.setItem('harudle.has-completed-oauth', 'true');
    render(<AuthCallbackPage />);
    expect(localStorage.getItem('harudle.has-completed-oauth')).toBe('true');
    expect(window.alert).toHaveBeenCalledWith(
      '회원 탈퇴 요청을 처리하지 못했어요. 다시 시도해 주세요.',
    );
    expect(mockNavigate).toHaveBeenCalledWith('/setting', { replace: true });
    expect(fetchMock).not.toHaveBeenCalled();
    expect(setAccessToken).not.toHaveBeenCalled();
  });
});

afterEach((): void => {
  globalThis.fetch = originalFetch;
  jest.restoreAllMocks();
});

describe('로그인 콜백', () => {
  it('StrictMode에서도 로그인 성공 처리는 한 번만 반영한다', async (): Promise<void> => {
    sessionStorage.setItem('harudle.login-attempt-provider', 'google');
    fetchMock.mockResolvedValue({
      ok: true,
      json: async (): Promise<unknown> => tokenResponse,
    } as Response);
    render(
      <StrictMode>
        <AuthCallbackPage />
      </StrictMode>,
    );
    await waitFor((): void => {
      expect(mockNavigate).toHaveBeenCalledWith('/', { replace: true });
    });
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/auth/refresh', {
      method: 'POST',
      credentials: 'include',
      headers: { 'X-XSRF-TOKEN': 'csrf-token' },
    });
    expect(setAccessToken).toHaveBeenCalledTimes(1);
    expect(mockIdentifyCurrentUser).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem('harudle.last-login-provider')).toBe('google');
    expect(sessionStorage.getItem('harudle.login-attempt-provider')).toBeNull();
  });

  it('refresh 응답 전에 페이지를 떠나면 로그인 성공을 반영하지 않는다', async (): Promise<void> => {
    let resolveRefresh!: (value: Response) => void;
    sessionStorage.setItem('harudle.login-attempt-provider', 'apple');
    fetchMock.mockReturnValue(
      new Promise<Response>((resolve): void => {
        resolveRefresh = resolve;
      }),
    );
    const { unmount } = render(<AuthCallbackPage />);
    await waitFor((): void => {
      expect(fetchMock).toHaveBeenCalledTimes(1);
    });
    unmount();
    await act(async (): Promise<void> => {
      resolveRefresh({
        ok: true,
        json: async (): Promise<unknown> => tokenResponse,
      } as Response);
    });
    expect(setAccessToken).not.toHaveBeenCalled();
    expect(mockNavigate).not.toHaveBeenCalled();
    expect(localStorage.getItem('harudle.last-login-provider')).toBeNull();
  });

  it('토큰 발급 실패 시 이번 시도만 지우고 이전 성공 기록은 유지한다', async (): Promise<void> => {
    localStorage.setItem('harudle.last-login-provider', 'kakao');
    sessionStorage.setItem('harudle.login-attempt-provider', 'apple');
    fetchMock.mockRejectedValue(new Error(ERROR_MESSAGES.LOGIN_FAILED));
    render(<AuthCallbackPage />);
    await waitFor((): void => {
      expect(mockNavigate).toHaveBeenCalledWith('/login', { replace: true });
    });
    expect(localStorage.getItem('harudle.last-login-provider')).toBe('kakao');
    expect(sessionStorage.getItem('harudle.login-attempt-provider')).toBeNull();
    expect(setAccessToken).not.toHaveBeenCalled();
  });
});

describe('OAuth 실패 콜백', () => {
  it.each([
    ['account_suspended', LOGIN_COPY.suspendedAccount],
    ['<script>raw-error</script>', ERROR_MESSAGES.LOGIN_FAILED],
    ['', ERROR_MESSAGES.LOGIN_FAILED],
  ])(
    '기존 세션이 있어도 오류 %s에서는 토큰을 복원하지 않는다',
    (error, message): void => {
      mockSearchParams.set('error', error);
      localStorage.setItem('harudle.has-completed-oauth', 'true');
      localStorage.setItem('harudle.last-login-provider', 'kakao');
      sessionStorage.setItem('harudle.login-attempt-provider', 'apple');
      render(
        <StrictMode>
          <AuthCallbackPage />
        </StrictMode>,
      );
      expect(window.alert).toHaveBeenCalledTimes(1);
      expect(window.alert).toHaveBeenCalledWith(message);
      expect(mockNavigate).toHaveBeenCalledTimes(1);
      expect(mockNavigate).toHaveBeenCalledWith('/login', { replace: true });
      expect(requestCsrfToken).not.toHaveBeenCalled();
      expect(fetchMock).not.toHaveBeenCalled();
      expect(setAccessToken).not.toHaveBeenCalled();
      expect(mockIdentifyCurrentUser).not.toHaveBeenCalled();
      expect(localStorage.getItem('harudle.last-login-provider')).toBe('kakao');
      expect(
        sessionStorage.getItem('harudle.login-attempt-provider'),
      ).toBeNull();
    },
  );

  it('요청 도중 실패 콜백으로 바뀌면 늦은 성공 응답을 반영하지 않는다', async (): Promise<void> => {
    let resolveRefresh!: (value: Response) => void;
    fetchMock.mockReturnValue(
      new Promise<Response>((resolve): void => {
        resolveRefresh = resolve;
      }),
    );
    const { rerender } = render(<AuthCallbackPage />);
    await waitFor((): void => {
      expect(fetchMock).toHaveBeenCalledTimes(1);
    });
    mockSearchParams.set('error', 'account_suspended');
    rerender(<AuthCallbackPage />);
    await act(async (): Promise<void> => {
      resolveRefresh({
        ok: true,
        json: async (): Promise<unknown> => tokenResponse,
      } as Response);
    });
    expect(setAccessToken).not.toHaveBeenCalled();
    expect(mockIdentifyCurrentUser).not.toHaveBeenCalled();
    expect(mockNavigate).not.toHaveBeenCalledWith('/', { replace: true });
    expect(window.alert).toHaveBeenCalledTimes(1);
    expect(window.alert).toHaveBeenCalledWith(LOGIN_COPY.suspendedAccount);
  });
});
