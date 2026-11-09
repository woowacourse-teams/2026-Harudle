import { StrictMode } from 'react';
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { render } from '@testing-library/react';
import { requestCsrfToken, setAccessToken } from '../../shared/auth';
import WithdrawalCallbackPage from './WithdrawalCallbackPage';

const mockResetUser = jest.fn();
const mockNavigate = jest.fn();
const fetchMock = jest.fn<typeof fetch>();
const originalFetch = globalThis.fetch;
let mockSearchParams = new URLSearchParams();

jest.mock('react-router', () => ({
  useNavigate: () => mockNavigate,
  useSearchParams: () => [mockSearchParams],
}));

jest.mock('../../posthog/useAnalytics', () => ({
  useAnalytics: () => ({ resetUser: mockResetUser }),
}));
jest.mock('../../shared/auth', () => ({
  ...jest.requireActual<typeof import('../../shared/auth')>(
    '../../shared/auth',
  ),
  requestCsrfToken: jest.fn(),
  setAccessToken: jest.fn(),
}));

const renderCallback = (query: string): void => {
  mockSearchParams = new URLSearchParams(query);
  render(
    <StrictMode>
      <WithdrawalCallbackPage />
    </StrictMode>,
  );
};

beforeEach((): void => {
  jest.clearAllMocks();
  localStorage.clear();
  localStorage.setItem('harudle.has-completed-oauth', 'true');
  globalThis.fetch = fetchMock;
  jest.spyOn(window, 'alert').mockImplementation((): void => {});
});

afterEach((): void => {
  globalThis.fetch = originalFetch;
  jest.restoreAllMocks();
});

describe('구글 회원 탈퇴 콜백', (): void => {
  it('탈퇴 성공 시 한 번만 로그인 정보를 정리하고 토큰 요청 없이 홈으로 이동한다', (): void => {
    renderCallback('?withdrawal=success');

    expect(setAccessToken).toHaveBeenCalledTimes(1);
    expect(setAccessToken).toHaveBeenCalledWith(null);
    expect(localStorage.getItem('harudle.has-completed-oauth')).toBeNull();
    expect(mockResetUser).toHaveBeenCalledTimes(1);
    expect(mockNavigate).toHaveBeenCalledTimes(1);
    expect(mockNavigate).toHaveBeenCalledWith('/', { replace: true });
    expect(requestCsrfToken).not.toHaveBeenCalled();
    expect(fetchMock).not.toHaveBeenCalled();
    expect(window.alert).not.toHaveBeenCalled();
  });

  it.each(['?withdrawal=failed', '?withdrawal=unknown', '?withdrawal=', ''])(
    '성공이 아닌 결과 %s에서는 로그인 정보를 유지하고 설정으로 돌아간다',
    (query): void => {
      renderCallback(query);

      expect(localStorage.getItem('harudle.has-completed-oauth')).toBe('true');
      expect(window.alert).toHaveBeenCalledTimes(1);
      expect(window.alert).toHaveBeenCalledWith(
        '회원 탈퇴 요청을 처리하지 못했어요. 다시 시도해 주세요.',
      );
      expect(mockNavigate).toHaveBeenCalledTimes(1);
      expect(mockNavigate).toHaveBeenCalledWith('/setting', { replace: true });
      expect(requestCsrfToken).not.toHaveBeenCalled();
      expect(fetchMock).not.toHaveBeenCalled();
      expect(setAccessToken).not.toHaveBeenCalled();
      expect(mockResetUser).not.toHaveBeenCalled();
    },
  );
});
