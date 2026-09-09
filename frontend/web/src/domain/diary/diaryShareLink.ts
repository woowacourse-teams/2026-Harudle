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

    throw new Error('알 수 없는 에러가 발생했습니다.');
  }

  const data: unknown = await response.json();

  if (!isDiaryShareLinkResponse(data)) {
    throw new Error('DiaryShare 응답 형식이 일치하지 않습니다.');
  }

  return data;
};

export interface DiaryShareLinkRequest {
  diaryId: string | undefined;
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
