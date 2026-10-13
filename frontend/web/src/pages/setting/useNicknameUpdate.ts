import { useState } from 'react';
import { updateUserNickname } from '../../domain/user/profile';
import type { ApiRequest } from '../../shared/api';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { useAnalytics } from '../../posthog/useAnalytics';

const useNicknameUpdate = () => {
  const { updateNickname: updateAnalyticsNickname } = useAnalytics();
  const [request, setRequest] = useState<ApiRequest<void>>({
    status: 'idle',
  });

  const execute = async (nickname: string): Promise<boolean> => {
    if (request.status === 'loading') return false;
    setRequest({ status: 'loading' });

    try {
      await updateUserNickname(nickname);
      updateAnalyticsNickname(nickname);
      setRequest({ status: 'success', data: undefined });
      return true;
    } catch (error: unknown) {
      setRequest({
        status: 'error',
        error:
          error instanceof Error
            ? error
            : new Error(ERROR_MESSAGES.NICKNAME_UPDATE_FAILED),
      });
      return false;
    }
  };

  const reset = (): void => setRequest({ status: 'idle' });

  return { request, execute, reset };
};

export default useNicknameUpdate;
