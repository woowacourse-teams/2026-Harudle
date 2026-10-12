import { useState } from 'react';
import {
  updateUserNickname,
  type UpdateProfileResponse,
} from '../../domain/user/profile';
import type { ApiRequest } from '../../shared/api';
import { ERROR_MESSAGES } from '../../shared/errorMessage';

const useNicknameUpdate = () => {
  const [request, setRequest] = useState<ApiRequest<UpdateProfileResponse>>({
    status: 'idle',
  });

  const execute = async (nickname: string) => {
    if (request.status === 'loading') return;
    setRequest({ status: 'loading' });

    try {
      const profile = await updateUserNickname(nickname);
      setRequest({ status: 'success', data: profile });
      return profile;
    } catch (error: unknown) {
      setRequest({
        status: 'error',
        error:
          error instanceof Error
            ? error
            : new Error(ERROR_MESSAGES.NICKNAME_UPDATE_FAILED),
      });
    }
  };

  const reset = () => setRequest({ status: 'idle' });

  return { request, execute, reset };
};

export default useNicknameUpdate;
