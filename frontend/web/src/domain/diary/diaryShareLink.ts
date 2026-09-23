import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { authFetch } from '../../shared/auth';

export const createDiaryShareLink = async ({
  diaryId,
}: DiaryShareLinkRequest): Promise<DiaryShareLinkResponse> => {
  const response = await authFetch(
    `${API_BASE_URL}/diaries/${diaryId}/share-link`,
    {
      method: 'PUT',
    },
  );

  if (!response.ok) {
    const errorData: unknown = await response.json().catch(() => null);

    if (isProblemDetails(errorData)) {
      throw new RequestError(errorData);
    }

    throw new Error(ERROR_MESSAGES.DIARY_SHARE_LINK_CREATION_FAILED);
  }

  const data: unknown = await response.json();

  if (!isDiaryShareLinkResponse(data)) {
    throw new Error(ERROR_MESSAGES.INVALID_DIARY_SHARE_RESPONSE);
  }

  return data;
};

export interface DiaryShareLinkRequest {
  diaryId: string;
}

export interface DiaryShareLinkResponse {
  shareId: string;
  shareUrl: string;
  createdAt: string;
}

const isDiaryShareLinkResponse = (
  value: unknown,
): value is DiaryShareLinkResponse => {
  return (
    typeof value === 'object' &&
    value !== null &&
    'shareId' in value &&
    typeof value.shareId === 'string' &&
    'shareUrl' in value &&
    typeof value.shareUrl === 'string' &&
    'createdAt' in value &&
    typeof value.createdAt === 'string'
  );
};
