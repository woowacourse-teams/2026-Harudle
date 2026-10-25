import { useEffect, useRef, useState } from 'react';
import { type ApiRequest } from '../../shared/api';
import {
  getUserProfile,
  type ProfileResponse,
} from '../../domain/user/profile';

const useProfile = () => {
  const profileVersion = useRef(0);
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
    // 이미지 오류로 시작한 이전 조회가 늦게 도착해도, 저장 후 반영한 최신 프로필을 덮어쓰지 않는다.
    profileVersion.current += 1;
    setRequest({ status: 'success', data: profile });
  };

  const refreshProfile = async (): Promise<void> => {
    const version = ++profileVersion.current;
    try {
      const profile = await getUserProfile();
      if (version === profileVersion.current) updateProfile(profile);
    } catch {
      // 사진 주소 재조회에 실패해도 현재 프로필과 대체 이미지를 유지한다.
    }
  };

  return { profileRequest: request, updateProfile, refreshProfile };
};

export default useProfile;
