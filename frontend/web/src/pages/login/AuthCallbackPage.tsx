import { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import {
  isRefreshTokenResponse,
  requestCsrfToken,
  setAccessToken,
} from '../../shared/auth';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { LOGIN_COPY } from './copy';
import { useAnalytics } from '../../posthog/useAnalytics';
import {
  clearLoginAttemptProvider,
  getLoginAttemptProvider,
  rememberSuccessfulLogin,
} from './loginHistory';

// 서버 인증 후 쿠키를 받은 브라우저가 기존 CSRF/refresh 절차를 마무리한다.
const AuthCallbackPage = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { identifyCurrentUser } = useAnalytics();
  const [provider] = useState(getLoginAttemptProvider);
  const hasHandledOAuthError = useRef(false);
  const hasOAuthError = searchParams.has('error');
  const oauthError = searchParams.get('error');

  useEffect(() => {
    if (hasOAuthError) {
      if (hasHandledOAuthError.current) return;
      hasHandledOAuthError.current = true;
      clearLoginAttemptProvider();
      alert(
        oauthError === 'account_suspended'
          ? LOGIN_COPY.suspendedAccount
          : ERROR_MESSAGES.LOGIN_FAILED,
      );
      navigate('/login', { replace: true });
      return;
    }

    hasHandledOAuthError.current = false;
    let isActive = true;
    const getAccessTokenAtLogin = async (): Promise<void> => {
      try {
        const csrfToken = await requestCsrfToken();
        const response = await fetch(`${API_BASE_URL}/auth/refresh`, {
          method: 'POST',
          credentials: 'include',
          headers: { 'X-XSRF-TOKEN': csrfToken },
        });

        if (!response.ok) {
          const errorData: unknown = await response.json().catch(() => null);
          if (isProblemDetails(errorData)) throw new RequestError(errorData);
          throw new Error(ERROR_MESSAGES.LOGIN_FAILED);
        }

        const data: unknown = await response.json();
        if (!isActive) return;
        if (!isRefreshTokenResponse(data)) {
          throw new Error(ERROR_MESSAGES.INVALID_REFRESH_TOKEN_RESPONSE);
        }

        setAccessToken(data.accessToken);
        rememberSuccessfulLogin(provider);
        void identifyCurrentUser(data.accessToken);
        navigate('/', { replace: true });
      } catch (error: unknown) {
        if (!isActive) return;
        clearLoginAttemptProvider();
        alert(
          error instanceof Error ? error.message : ERROR_MESSAGES.LOGIN_FAILED,
        );
        navigate('/login', { replace: true });
      }
    };
    void getAccessTokenAtLogin();

    return (): void => {
      isActive = false;
    };
  }, [hasOAuthError, oauthError, identifyCurrentUser, navigate, provider]);

  return null;
};

export default AuthCallbackPage;
