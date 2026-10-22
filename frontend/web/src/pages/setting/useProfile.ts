import { useEffect, useRef, useState } from 'react';
import { type ApiRequest } from '../../shared/api';
import {
  getUserProfile,
  type ProfileResponse,
} from '../../domain/user/profile';

const useProfile = () => {
  const refreshVersion = useRef(0);
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

  const updateProfile = (profile: ProfileResponse): void => {
    refreshVersion.current += 1;
    setRequest({ status: 'success', data: profile });
  };

  const refreshProfile = async (): Promise<void> => {
    const version = ++refreshVersion.current;
    try {
      const profile = await getUserProfile();
      if (version === refreshVersion.current) updateProfile(profile);
    } catch {
      // 사진 주소 재조회에 실패해도 현재 프로필과 대체 이미지를 유지한다.
    }
  };

  return { profileRequest: request, updateProfile, refreshProfile };
};

export default useProfile;
