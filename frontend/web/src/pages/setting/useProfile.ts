import { useEffect, useState } from 'react';
import { type ApiRequest } from '../../shared/api';
import {
  getUserProfile,
  type ProfileResponse,
  type UpdateProfileResponse,
} from '../../domain/user/profile';

const useProfile = () => {
  const [request, setRequest] = useState<ApiRequest<ProfileResponse>>({
    status: 'idle',
  });

  useEffect(() => {
    const execute = async () => {
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

  const updateProfile = (profile: UpdateProfileResponse) => {
    setRequest((previous) => {
      if (previous.status !== 'success') return previous;
      return { status: 'success', data: { ...previous.data, ...profile } };
    });
  };

  return { profileRequest: request, updateProfile };
};

export default useProfile;
