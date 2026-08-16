import { useState } from 'react';
import {
  API_BASE_URL,
  isProblemDetails,
  RequestError,
  type ApiRequest,
} from '../../shared/api';
import { useNavigate } from 'react-router';

const useLogout = () => {
  const navigate = useNavigate();
  const [logoutRequest, setLogoutRequest] = useState<ApiRequest<void>>({
    status: 'idle',
  });

  const handleLogout = async () => {
    setLogoutRequest({
      status: 'loading',
    });

    try {
      const response = await fetch(`${API_BASE_URL}/auth/logout`, {
        method: 'POST',
      });

      if (!response.ok) {
        const errorData = await response.json();
        if (isProblemDetails(errorData)) {
          throw new RequestError(errorData);
        }

        throw new Error('알 수 없는 에러가 발생했습니다.');
      }

      setLogoutRequest({
        status: 'success',
        data: undefined,
      });

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
