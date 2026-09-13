import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { authFetch } from '../../shared/auth';

export const getUserProfile = async (): Promise<ProfileResponse> => {
  const response = await authFetch(`${API_BASE_URL}/me`);

  if (!response.ok) {
    const errorData: unknown = await response.json().catch(() => null);
    if (isProblemDetails(errorData)) {
      throw new RequestError(errorData);
    }

    throw new Error(ERROR_MESSAGES.PROFILE_FETCH_FAILED);
  }

  const data: unknown = await response.json();

  if (!isProfileResponse(data)) {
    throw new Error(ERROR_MESSAGES.INVALID_PROFILE_RESPONSE);
  }

  return data;
};

type OAuthProvider = 'kakao';
type UserRole = 'USER' | 'ADMIN';

export interface ProfileResponse {
  id: string;
  name: string;
  email: string | null;
  role: UserRole;
  oauthProviders: OAuthProvider[];
  createdAt: string;
}

const isOAuthProvider = (value: unknown): value is OAuthProvider => {
  return value === 'kakao';
};

const isUserRole = (value: unknown): value is UserRole => {
  return value === 'USER' || value === 'ADMIN';
};

const isProfileResponse = (value: unknown): value is ProfileResponse => {
  return (
    typeof value === 'object' &&
    value !== null &&
    'id' in value &&
    typeof value.id === 'string' &&
    'name' in value &&
    typeof value.name === 'string' &&
    'email' in value &&
    (typeof value.email === 'string' || value.email === null) &&
    'role' in value &&
    isUserRole(value.role) &&
    'oauthProviders' in value &&
    Array.isArray(value.oauthProviders) &&
    value.oauthProviders.length > 0 &&
    value.oauthProviders.every(isOAuthProvider) &&
    'createdAt' in value &&
    typeof value.createdAt === 'string'
  );
};
