import { useState } from 'react';
import { type ApiRequest } from '../../shared/api';
import { requestCsrfToken, setAccessToken } from '../../shared/auth';
import { logout } from '../../domain/user/logout';
import { useAnalytics } from '../../posthog/useAnalytics';
import { clearGenerationForEndedSession } from '../../domain/diary/diaryGenerationStore';

const useLogout = () => {
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

      clearGenerationForEndedSession();
      try {
        resetUser();
      } catch {
        // 선택 분석 SDK의 오류가 확인된 로그아웃을 실패로 바꾸지 않게 한다.
      }

      setRequest({
        status: 'success',
        data: undefined,
      });

      setAccessToken(null);
      localStorage.removeItem('harudle.has-completed-oauth');
      window.location.replace('/');
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
