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
import { ERROR_MESSAGES } from '../../shared/errorMessage';

const mockCsrf = jest.fn<() => Promise<string>>();
const mockSetToken = jest.fn();
const mockIdentify = jest.fn<() => Promise<void>>();
const mockNavigate = jest.fn();
const mockConsumeReturnPath = jest.fn<() => string>();
const mockFetch = jest.fn<typeof fetch>();
let mockSearch = '';
const originalFetch = globalThis.fetch;
const token = {
  accessToken: 'access-token',
  tokenType: 'Bearer',
  expiresIn: 1800,
};
const jsonResponse = (): Response =>
  ({ ok: true, json: async (): Promise<unknown> => token }) as Response;

jest.mock('react-router', () => ({
  useNavigate: () => mockNavigate,
  useLocation: () => ({ search: mockSearch }),
}));
jest.mock('../../shared/auth', () => ({
  ...jest.requireActual<typeof import('../../shared/auth')>(
    '../../shared/auth',
  ),
  requestCsrfToken: (): Promise<string> => mockCsrf(),
  setAccessToken: (value: string | null): void => {
    mockSetToken(value);
  },
}));
jest.mock('../../posthog/useAnalytics', () => ({
  useAnalytics: () => ({ identifyCurrentUser: mockIdentify }),
}));
jest.mock('../consent/returnPath', () => ({
  consumeLoginReturnPath: (): string => mockConsumeReturnPath(),
}));

beforeEach((): void => {
  localStorage.clear();
  mockSearch = '';
  mockCsrf.mockReset().mockResolvedValue('csrf');
  mockSetToken.mockReset();
  mockIdentify.mockReset().mockResolvedValue();
  mockNavigate.mockReset();
  mockConsumeReturnPath
    .mockReset()
    .mockReturnValueOnce('/setting')
    .mockReturnValue('/');
  mockFetch.mockReset().mockResolvedValue(jsonResponse());
  globalThis.fetch = mockFetch;
  jest.spyOn(window, 'alert').mockImplementation((): void => undefined);
});
afterEach((): void => {
  globalThis.fetch = originalFetch;
  jest.restoreAllMocks();
});

describe('OAuth 콜백의 단일 교환과 복귀', (): void => {
  it('StrictMode에서도 쿠키 교환과 목적지 소비를 한 번만 수행한다', async (): Promise<void> => {
    render(
      <StrictMode>
        <AuthCallbackPage />
      </StrictMode>,
    );
    await waitFor(() =>
      expect(mockNavigate).toHaveBeenCalledWith('/setting', { replace: true }),
    );
    expect(mockCsrf).toHaveBeenCalledTimes(1);
    expect(mockFetch).toHaveBeenCalledTimes(1);
    expect(mockFetch).toHaveBeenCalledWith('/api/v1/auth/refresh', {
      method: 'POST',
      credentials: 'include',
      headers: { 'X-XSRF-TOKEN': 'csrf' },
    });
    expect(mockConsumeReturnPath).toHaveBeenCalledTimes(1);
    expect(mockNavigate).toHaveBeenCalledTimes(1);
    expect(mockSetToken).toHaveBeenCalledTimes(1);
    expect(mockIdentify).toHaveBeenCalledTimes(1);
  });
  it('교환이 끝나기 전에 화면을 벗어나면 늦은 응답을 반영하지 않는다', async (): Promise<void> => {
    let resolve: ((response: Response) => void) | undefined;
    mockFetch.mockImplementationOnce(
      (): Promise<Response> =>
        new Promise((complete): void => {
          resolve = complete;
        }),
    );
    const { unmount } = render(
      <StrictMode>
        <AuthCallbackPage />
      </StrictMode>,
    );
    await waitFor(() => expect(mockFetch).toHaveBeenCalledTimes(1));
    unmount();
    await act(async (): Promise<void> => {
      resolve?.(jsonResponse());
    });
    expect(mockSetToken).not.toHaveBeenCalled();
    expect(mockConsumeReturnPath).not.toHaveBeenCalled();
    expect(mockNavigate).not.toHaveBeenCalled();
  });
  it.each([
    ['?error=account_suspended', '관리자에 의해 이용이 제한된 계정이에요.'],
    ['?error=%3Cscript%3Eunknown%3C%2Fscript%3E', ERROR_MESSAGES.LOGIN_FAILED],
    ['?error=', ERROR_MESSAGES.LOGIN_FAILED],
  ] as const)(
    'OAuth 실패 %s는 다른 계정 쿠키를 교환하지 않고 알려진 문구만 한 번 안내한다',
    async (search, message): Promise<void> => {
      mockSearch = search;
      localStorage.setItem('harudle.has-completed-oauth', 'true');
      render(
        <StrictMode>
          <AuthCallbackPage />
        </StrictMode>,
      );
      await waitFor(() =>
        expect(mockNavigate).toHaveBeenCalledWith('/login', { replace: true }),
      );
      expect(window.alert).toHaveBeenCalledWith(message);
      expect(window.alert).toHaveBeenCalledTimes(1);
      expect(mockNavigate).toHaveBeenCalledTimes(1);
      expect(mockCsrf).not.toHaveBeenCalled();
      expect(mockFetch).not.toHaveBeenCalled();
      expect(mockSetToken).not.toHaveBeenCalled();
      expect(mockIdentify).not.toHaveBeenCalled();
      expect(mockConsumeReturnPath).not.toHaveBeenCalled();
    },
  );
  it('성공 교환 도중 실패 콜백으로 바뀌면 늦은 성공과 복귀 경로 소비를 무시한다', async (): Promise<void> => {
    let resolve: ((response: Response) => void) | undefined;
    mockFetch.mockImplementationOnce(
      (): Promise<Response> =>
        new Promise((complete): void => {
          resolve = complete;
        }),
    );
    const { rerender } = render(<AuthCallbackPage />);
    await waitFor(() => expect(mockFetch).toHaveBeenCalledTimes(1));
    mockSearch = '?error=account_suspended';
    rerender(<AuthCallbackPage />);
    expect(mockNavigate).toHaveBeenCalledWith('/login', { replace: true });
    await act(async (): Promise<void> => {
      resolve?.(jsonResponse());
    });
    expect(mockSetToken).not.toHaveBeenCalled();
    expect(mockIdentify).not.toHaveBeenCalled();
    expect(mockConsumeReturnPath).not.toHaveBeenCalled();
    expect(mockNavigate).toHaveBeenCalledTimes(1);
    expect(window.alert).toHaveBeenCalledTimes(1);
  });
  it('StrictMode 교환 실패도 알림과 로그인 이동을 한 번만 처리한다', async (): Promise<void> => {
    mockFetch.mockRejectedValueOnce(new Error('로그인 실패'));
    render(
      <StrictMode>
        <AuthCallbackPage />
      </StrictMode>,
    );
    await waitFor(() =>
      expect(mockNavigate).toHaveBeenCalledWith('/login', { replace: true }),
    );
    expect(mockFetch).toHaveBeenCalledTimes(1);
    expect(window.alert).toHaveBeenCalledTimes(1);
    expect(mockConsumeReturnPath).not.toHaveBeenCalled();
  });
});
