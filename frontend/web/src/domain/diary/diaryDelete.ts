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

    throw new Error('알 수 없는 에러가 발생했습니다.');
  }
};

export interface DiaryDeleteRequest {
  diaryId: string | undefined;
}
