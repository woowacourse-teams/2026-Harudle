import { useState } from 'react';
import { type ApiRequest } from '../../shared/api';
import { useNavigate } from 'react-router';
import { requestCsrfToken, setAccessToken } from '../../shared/auth';
import { logout } from '../../domain/user/logout';
import { useAnalytics } from '../../posthog/useAnalytics';

const useLogout = () => {
  const navigate = useNavigate();
  const { resetUser } = useAnalytics();
  const [request, setRequest] = useState<ApiRequest<void>>({
    status: 'idle',
  });

  const handleLogout = async () => {
    setRequest({
      status: 'loading',
    });

    try {
      const csrfToken = await requestCsrfToken();
      await logout({ csrfToken });

      resetUser();

      setRequest({
        status: 'success',
        data: undefined,
      });

      setAccessToken(null);
      localStorage.removeItem('harudle.has-completed-oauth');
      navigate('/login');
    } catch (error: unknown) {
      if (error instanceof Error) {
        setRequest({
          status: 'error',
          error: error,
        });
      }
    }
  };

  return { request, handleLogout };
};

export default useLogout;
