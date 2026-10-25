import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { useEffect, useRef } from 'react';
import { API_BASE_URL } from '../../shared/api';
import {
  isRefreshTokenResponse,
  requestCsrfToken,
  setAccessToken,
  type RefreshTokenResponse,
} from '../../shared/auth';
import { useLocation, useNavigate } from 'react-router';
import { useAnalytics } from '../../posthog/useAnalytics';
import { consumeLoginReturnPath } from '../consent/returnPath';
import { LOGIN_COPY } from './copy';

const requestLoginAccessToken = async (): Promise<RefreshTokenResponse> => {
  const csrfToken = await requestCsrfToken();
  const response = await fetch(`${API_BASE_URL}/auth/refresh`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'X-XSRF-TOKEN': csrfToken },
  });
  if (!response.ok) throw new Error(ERROR_MESSAGES.LOGIN_FAILED);
  const data: unknown = await response.json();
  if (!isRefreshTokenResponse(data))
    throw new Error(ERROR_MESSAGES.INVALID_REFRESH_TOKEN_RESPONSE);
  return data;
};

// OAuth 성공 콜백에서는 브라우저의 Refresh Token 쿠키로 세션을 복원한다.
const AuthCallbackPage = () => {
  const navigate = useNavigate();
  const { search } = useLocation();
  const failureHandled = useRef(false);
  const loginRequest = useRef<Promise<RefreshTokenResponse> | null>(null);
  const { identifyCurrentUser } = useAnalytics();

  useEffect((): (() => void) | undefined => {
    const params = new URLSearchParams(search);
    // OAuth 실패를 기존의 다른 계정 쿠키로 성공 처리하지 않는다.
    if (params.has('error')) {
      if (!failureHandled.current) {
        failureHandled.current = true;
        alert(
          params.get('error') === 'account_suspended'
            ? LOGIN_COPY.suspendedAccount
            : ERROR_MESSAGES.LOGIN_FAILED,
        );
        void navigate('/login', { replace: true });
      }
      return;
    }
    failureHandled.current = false;
    let active = true;
    // StrictMode의 효과 재실행도 같은 교환을 기다린다. 폐기된 효과는 결과를 반영하지 않는다.
    loginRequest.current ??= requestLoginAccessToken();
    void loginRequest.current
      .then((data): void => {
        if (!active) return;
        setAccessToken(data.accessToken);
        localStorage.setItem('harudle.has-ever-logged-in', 'true');
        localStorage.setItem('harudle.has-completed-oauth', 'true');
        void identifyCurrentUser(data.accessToken);
        void navigate(consumeLoginReturnPath(), { replace: true });
      })
      .catch((error: unknown): void => {
        if (!active) return;
        alert(
          error instanceof Error ? error.message : ERROR_MESSAGES.LOGIN_FAILED,
        );
        void navigate('/login', { replace: true });
      });
    return (): void => {
      active = false;
    };
  }, [identifyCurrentUser, navigate, search]);

  return <div>로그인 리다이렉트 페이지</div>;
};

export default AuthCallbackPage;
