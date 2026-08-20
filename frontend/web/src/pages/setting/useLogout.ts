import { useState } from 'react';
import {
  API_BASE_URL,
  isProblemDetails,
  RequestError,
  type ApiRequest,
} from '../../shared/api';
import { useNavigate } from 'react-router';
import { authFetch, requestCsrfToken, setAccessToken } from '../../shared/auth';
import { useAnalytics } from '../../shared/useAnalytics';

const useLogout = () => {
  const navigate = useNavigate();
  const { resetUser } = useAnalytics();
  const [logoutRequest, setLogoutRequest] = useState<ApiRequest<void>>({
    status: 'idle',
  });

  const handleLogout = async () => {
    setLogoutRequest({
      status: 'loading',
    });

    try {
      const csrfToken = await requestCsrfToken();
      const response = await authFetch(`${API_BASE_URL}/auth/logout`, {
        method: 'POST',
        headers: {
          'X-XSRF-TOKEN': csrfToken,
        },
      });

      if (!response.ok) {
        const errorData = await response.json();
        if (isProblemDetails(errorData)) {
          throw new RequestError(errorData);
        }

        throw new Error('알 수 없는 에러가 발생했습니다.');
      }

      resetUser();

      setLogoutRequest({
        status: 'success',
        data: undefined,
      });

      setAccessToken(null);
      navigate('/login');
    } catch (error: unknown) {
      if (error instanceof Error) {
        setLogoutRequest({
          status: 'error',
          error: error,
        });
      }
    }
  };

  return { logoutRequest, handleLogout };
};

export default useLogout;
