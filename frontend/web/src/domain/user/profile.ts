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
  nickname: string;
  profileImageUrl: string;
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
    'nickname' in value &&
    typeof value.nickname === 'string' &&
    'profileImageUrl' in value &&
    typeof value.profileImageUrl === 'string' &&
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

export const updateUserNickname = async (nickname: string): Promise<void> => {
  const response = await authFetch(`${API_BASE_URL}/me/nickname`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ nickname }),
  });

  if (!response.ok) {
    const errorData: unknown = await response.json().catch(() => null);
    if (isProblemDetails(errorData)) {
      throw new RequestError(errorData);
    }

    throw new Error(ERROR_MESSAGES.NICKNAME_UPDATE_FAILED);
  }
};

export interface ProfileChanges {
  readonly nickname?: string;
  readonly image?:
    | { readonly type: 'default'; readonly code: number }
    | { readonly type: 'upload'; readonly file: File };
}

export class ProfileSaveResultUnknownError extends Error {}

export const updateUserProfile = async (
  changes: ProfileChanges,
): Promise<void> => {
  const body = new FormData();
  if (changes.nickname !== undefined) body.set('nickname', changes.nickname);
  if (changes.image?.type === 'default') {
    body.set('imageCode', String(changes.image.code));
  } else if (changes.image?.type === 'upload') {
    body.set('image', changes.image.file);
  }

  let response: Response;
  try {
    response = await authFetch(`${API_BASE_URL}/me/profile`, {
      method: 'PATCH',
      body,
    });
  } catch (error: unknown) {
    if (error instanceof TypeError) throw new ProfileSaveResultUnknownError();
    throw error;
  }

  if (response.status === 204) return;

  const errorData: unknown = await response.json().catch(() => null);
  const problem = isProblemDetails(errorData) ? errorData : null;
  if (
    response.ok ||
    (response.status >= 500 &&
      !(
        response.status === 503 &&
        problem?.code === 'PROFILE_IMAGE_STORAGE_ERROR'
      ))
  ) {
    throw new ProfileSaveResultUnknownError();
  }
  if (problem) throw new RequestError(problem);
  throw new Error(ERROR_MESSAGES.PROFILE_UPDATE_FAILED);
};
