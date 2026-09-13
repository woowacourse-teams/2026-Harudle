import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';

export const getSharedDiary = async ({
  shareId,
}: SharedDiaryRequest): Promise<SharedDiaryResponse> => {
  const response = await fetch(`${API_BASE_URL}/public/shares/${shareId}`);

  if (!response.ok) {
    const errorData: unknown = await response.json().catch(() => null);

    if (isProblemDetails(errorData)) {
      throw new RequestError(errorData);
    }

    throw new Error(ERROR_MESSAGES.SHARED_DIARY_FETCH_FAILED);
  }

  const data: unknown = await response.json();

  if (!isSharedDiaryResponse(data)) {
    throw new Error(ERROR_MESSAGES.INVALID_SHARED_DIARY_RESPONSE);
  }

  return data;
};

export interface SharedDiaryRequest {
  shareId: string;
}

export interface SharedDiaryResponse {
  title: string;
  diaryDate: string;
  imageUrl: string;
  imageUrlExpiresAt: string;
  createdAt: string;
}

const isSharedDiaryResponse = (
  value: unknown,
): value is SharedDiaryResponse => {
  return (
    typeof value === 'object' &&
    value !== null &&
    'title' in value &&
    typeof value.title === 'string' &&
    'diaryDate' in value &&
    typeof value.diaryDate === 'string' &&
    'imageUrl' in value &&
    typeof value.imageUrl === 'string' &&
    'imageUrlExpiresAt' in value &&
    typeof value.imageUrlExpiresAt === 'string' &&
    'createdAt' in value &&
    typeof value.createdAt === 'string'
  );
};
