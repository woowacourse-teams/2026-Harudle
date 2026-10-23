import { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import {
  isRefreshTokenResponse,
  requestCsrfToken,
  setAccessToken,
  type RefreshTokenResponse,
} from '../../shared/auth';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { useAnalytics } from '../../posthog/useAnalytics';
import {
  clearPendingLoginProvider,
  getPendingLoginProvider,
  rememberSuccessfulLogin,
} from './loginHistory';

// 서버 인증 후 쿠키를 받은 브라우저가 기존 CSRF/refresh 절차를 마무리한다.
const AuthCallbackPage = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { identifyCurrentUser } = useAnalytics();
  const [provider] = useState(getPendingLoginProvider);
  const requestRef = useRef<Promise<RefreshTokenResponse> | null>(null);
  const hasHandledOAuthError = useRef(false);
  const hasOAuthError = searchParams.has('error');

  useEffect(() => {
    if (hasOAuthError) {
      if (hasHandledOAuthError.current) return;
      hasHandledOAuthError.current = true;
      clearPendingLoginProvider();
      alert(ERROR_MESSAGES.LOGIN_FAILED);
      navigate('/login', { replace: true });
      return;
    }

    let isActive = true;
    const getAccessTokenAtLogin = async (): Promise<RefreshTokenResponse> => {
      const csrfToken = await requestCsrfToken();
      const response = await fetch(`${API_BASE_URL}/auth/refresh`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'X-XSRF-TOKEN': csrfToken },
      });

      if (!response.ok) {
        const errorData: unknown = await response.json();
        if (isProblemDetails(errorData)) throw new RequestError(errorData);
        throw new Error(ERROR_MESSAGES.LOGIN_FAILED);
      }

      const data: unknown = await response.json();
      if (!isRefreshTokenResponse(data)) {
        throw new Error(ERROR_MESSAGES.INVALID_REFRESH_TOKEN_RESPONSE);
      }
      return data;
    };

    // StrictMode의 Effect 재실행도 같은 요청을 사용해 refresh 중복을 막는다.
    requestRef.current ??= getAccessTokenAtLogin();
    void requestRef.current
      .then((data): void => {
        if (!isActive) return;
        setAccessToken(data.accessToken);
        rememberSuccessfulLogin(provider);
        void identifyCurrentUser(data.accessToken);
        navigate('/', { replace: true });
      })
      .catch((error: unknown): void => {
        if (!isActive) return;
        clearPendingLoginProvider();
        alert(
          error instanceof Error ? error.message : ERROR_MESSAGES.LOGIN_FAILED,
        );
        navigate('/login', { replace: true });
      });

    return (): void => {
      isActive = false;
    };
  }, [hasOAuthError, identifyCurrentUser, navigate, provider]);

  return null;
};

export default AuthCallbackPage;
