import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { authFetch } from '../../shared/auth';

export const deleteDiary = async ({
  diaryId,
}: DiaryDeleteRequest): Promise<void> => {
  const response = await authFetch(`${API_BASE_URL}/diaries/${diaryId}`, {
    method: 'DELETE',
  });

  if (!response.ok) {
    const errorData: unknown = await response.json().catch(() => null);

    if (isProblemDetails(errorData)) {
      throw new RequestError(errorData);
    }

    throw new Error(ERROR_MESSAGES.DIARY_DELETION_FAILED);
  }
};

export interface DiaryDeleteRequest {
  diaryId: string;
}
