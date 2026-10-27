import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { useEffect, useRef } from 'react';

import { API_BASE_URL } from '../../shared/api';
import {
  isRefreshTokenResponse,
  requestCsrfToken,
  setAccessToken,
} from '../../shared/auth';
import { useLocation, useNavigate } from 'react-router';
import { useAnalytics } from '../../posthog/useAnalytics';
import { LOGIN_COPY } from './copy';

// OAuth 성공 콜백에서는 브라우저의 Refresh Token 쿠키로 세션을 복원한다.
const AuthCallbackPage = () => {
  const navigate = useNavigate();
  const { search } = useLocation();
  const failureHandledRef = useRef(false);
  const { identifyCurrentUser } = useAnalytics();
  useEffect(() => {
    const params = new URLSearchParams(search);
    // OAuth 실패를 기존의 다른 계정 쿠키로 성공 처리하지 않는다.
    if (params.has('error')) {
      if (!failureHandledRef.current) {
        failureHandledRef.current = true;
        alert(
          params.get('error') === 'account_suspended'
            ? LOGIN_COPY.suspendedAccount
            : ERROR_MESSAGES.LOGIN_FAILED,
        );
        navigate('/login', { replace: true });
      }
      return;
    }
    failureHandledRef.current = false;
    let active = true;
    const getAccessTokenAtLogin = async (): Promise<void> => {
      try {
        const csrfToken = await requestCsrfToken();
        if (!active) return;
        const response = await fetch(`${API_BASE_URL}/auth/refresh`, {
          method: 'POST',
          credentials: 'include',
          headers: {
            'X-XSRF-TOKEN': csrfToken,
          },
        });

        if (!response.ok) {
          throw new Error(ERROR_MESSAGES.LOGIN_FAILED);
        }

        const data: unknown = await response.json();
        if (!active) return;
        if (!isRefreshTokenResponse(data)) {
          throw new Error(ERROR_MESSAGES.INVALID_REFRESH_TOKEN_RESPONSE);
        }

        setAccessToken(data.accessToken);
        localStorage.setItem('harudle.has-ever-logged-in', 'true');
        localStorage.setItem('harudle.has-completed-oauth', 'true');

        void identifyCurrentUser(data.accessToken);
        navigate('/');
      } catch (error: unknown) {
        if (!active) return;
        if (error instanceof Error) {
          alert(error.message);
          navigate('/login');
        }
      }
    };
    void getAccessTokenAtLogin();
    return (): void => {
      active = false;
    };
  }, [identifyCurrentUser, navigate, search]);

  return <div>로그인 리다이렉트 페이지</div>;
};

export default AuthCallbackPage;
