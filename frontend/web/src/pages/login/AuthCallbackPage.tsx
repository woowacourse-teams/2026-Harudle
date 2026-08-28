import { useEffect } from 'react';

import { API_BASE_URL } from '../../shared/api';
import {
  isRefreshTokenResponse,
  requestCsrfToken,
  setAccessToken,
} from '../../shared/auth';
import { useNavigate } from 'react-router';
import { useAnalytics } from '../../shared/useAnalytics';

// 여기 왔을 땐 이미 브라우저가 쿠키에 리프레시 토큰을 저장하고 있는 상태임
const AuthCallbackPage = () => {
  const navigate = useNavigate();
  const { identifyCurrentUser } = useAnalytics();
  useEffect(() => {
    const getAccessTokenAtLogin = async () => {
      try {
        const csrfToken = await requestCsrfToken();
        const response = await fetch(`${API_BASE_URL}/auth/refresh`, {
          method: 'POST',
          credentials: 'include',
          headers: {
            'X-XSRF-TOKEN': csrfToken,
          },
        });

        if (!response.ok) {
          throw new Error('로그인에 실패했습니다. 다시 로그인해주세요.');
        }

        const data: unknown = await response.json();
        if (!isRefreshTokenResponse(data)) {
          throw new Error('RefreshToken 응답 형식이 일치하지 않습니다.');
        }

        setAccessToken(data.accessToken);
        localStorage.setItem('harudle.has-completed-oauth', 'true');

        void identifyCurrentUser(data.accessToken);
        navigate('/');
      } catch (error: unknown) {
        if (error instanceof Error) {
          alert(error.message);
          navigate('/login');
        }
      }
    };
    void getAccessTokenAtLogin();
  }, [identifyCurrentUser, navigate]);

  return <div>로그인 리다이렉트 페이지</div>;
};

export default AuthCallbackPage;
