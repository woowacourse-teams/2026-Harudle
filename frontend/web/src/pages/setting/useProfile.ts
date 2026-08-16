import { useEffect, useState } from 'react';
import {
  API_BASE_URL,
  isProblemDetails,
  RequestError,
  type ApiRequest,
} from '../../shared/api';

interface ProfileResponse {
  id: string;
  name: string;
  email?: string;
  oauthProviders: string[];
  createdAt: string;
}

const isProfileResponse = (value: unknown): value is ProfileResponse => {
  return (
    typeof value === 'object' &&
    value !== null &&
    'id' in value &&
    typeof value.id === 'string' &&
    'name' in value &&
    typeof value.name === 'string' &&
    'email' in value &&
    'oauthProviders' in value &&
    Array.isArray(value.oauthProviders) &&
    value.oauthProviders.every((provider) => typeof provider === 'string') &&
    'createdAt' in value &&
    typeof value.createdAt === 'string'
  );
};

const useProfile = () => {
  const [profileRequest, setProfileRequest] = useState<
    ApiRequest<ProfileResponse>
  >({
    status: 'idle',
  });

  useEffect(() => {
    const getProfile = async (): Promise<void> => {
      setProfileRequest({
        status: 'loading',
      });

      try {
        const response = await fetch(`${API_BASE_URL}/me`);

        if (!response.ok) {
          const errorData = await response.json();
          if (isProblemDetails(errorData)) {
            throw new RequestError(errorData);
          }

          throw new Error('알 수 없는 에러가 발생했습니다.');
        }

        const data: unknown = await response.json();

        if (!isProfileResponse(data)) {
          throw new Error('Profile 응답 형식이 일치하지 않습니다.');
        }

        setProfileRequest({
          status: 'success',
          data: data,
        });
      } catch (error: unknown) {
        if (error instanceof Error) {
          setProfileRequest({
            status: 'error',
            error: error,
          });
        }
      }
    };

    void getProfile();
  }, []);

  return { profileRequest };
};

export default useProfile;
