import { useEffect, useState } from 'react';
import { type ApiRequest } from '../../shared/api';
import {
  getUserProfile,
  type ProfileResponse,
} from '../../domain/user/profile';

const useProfile = () => {
  const [request, setRequest] = useState<ApiRequest<ProfileResponse>>({
    status: 'idle',
  });

  useEffect(() => {
    const execute = async (): Promise<void> => {
      setRequest({
        status: 'loading',
      });

      try {
        const userProfile = await getUserProfile();

        setRequest({
          status: 'success',
          data: userProfile,
        });
      } catch (error: unknown) {
        if (error instanceof Error) {
          setRequest({
            status: 'error',
            error: error,
          });
        }
      }
    };

    void execute();
  }, []);

  return { profileRequest: request };
};

export default useProfile;
