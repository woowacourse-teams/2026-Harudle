import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { authFetch } from '../../shared/auth';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import type { Feed } from './feedList';

interface FeedDeleteRequest {
  readonly feedId: Feed['id'];
}

export const deleteFeed = async ({
  feedId,
}: FeedDeleteRequest): Promise<void> => {
  const response = await authFetch(`${API_BASE_URL}/feeds/${feedId}`, {
    method: 'DELETE',
  });

  if (!response.ok) {
    const errorData: unknown = await response.json().catch((): null => null);
    if (isProblemDetails(errorData)) throw new RequestError(errorData);
    throw new Error(ERROR_MESSAGES.FEED_DELETION_FAILED);
  }
};
