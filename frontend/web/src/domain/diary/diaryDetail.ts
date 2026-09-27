import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { authFetch } from '../../shared/auth';

export const getDiaryDetail = async ({
  diaryId,
}: DiaryDetailRequest): Promise<DiaryDetailResponse> => {
  const response = await authFetch(`${API_BASE_URL}/diaries/${diaryId}`);

  if (!response.ok) {
    const errorData: unknown = await response.json().catch(() => null);

    if (isProblemDetails(errorData)) {
      throw new RequestError(errorData);
    }

    throw new Error(ERROR_MESSAGES.DIARY_DETAIL_FETCH_FAILED);
  }

  const data: unknown = await response.json();

  if (!isDiaryDetailResponse(data)) {
    throw new Error(ERROR_MESSAGES.INVALID_DIARY_DETAIL_RESPONSE);
  }

  return data;
};

export interface DiaryDetailRequest {
  diaryId: string;
}

export interface DiaryDetailResponse {
  id: string;
  diaryDate: string;
  sourceText: string;
  createdAt: string;
  generation: {
    id: string;
    status: 'SUCCEEDED';
    title: string;
    imageUrl: string;
    imageUrlExpiresAt: string;
    completedAt: string;
  };
}

const isDiaryDetailResponse = (
  value: unknown,
): value is DiaryDetailResponse => {
  return (
    typeof value === 'object' &&
    value !== null &&
    'id' in value &&
    typeof value.id === 'string' &&
    'diaryDate' in value &&
    typeof value.diaryDate === 'string' &&
    'sourceText' in value &&
    typeof value.sourceText === 'string' &&
    'createdAt' in value &&
    typeof value.createdAt === 'string' &&
    'generation' in value &&
    typeof value.generation === 'object' &&
    value.generation !== null &&
    'id' in value.generation &&
    typeof value.generation.id === 'string' &&
    'status' in value.generation &&
    value.generation.status === 'SUCCEEDED' &&
    'title' in value.generation &&
    typeof value.generation.title === 'string' &&
    'imageUrl' in value.generation &&
    typeof value.generation.imageUrl === 'string' &&
    'imageUrlExpiresAt' in value.generation &&
    typeof value.generation.imageUrlExpiresAt === 'string' &&
    'completedAt' in value.generation &&
    typeof value.generation.completedAt === 'string'
  );
};
