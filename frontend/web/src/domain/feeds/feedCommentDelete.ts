import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { authFetch, requestCsrfToken } from '../../shared/auth';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import type { FeedComment } from './feedComments';

export const deleteFeedComment = async ({
  commentId,
}: {
  readonly commentId: FeedComment['id'];
}): Promise<void> => {
  const csrfToken = await requestCsrfToken();
  const response = await authFetch(`${API_BASE_URL}/comments/${commentId}`, {
    method: 'DELETE',
    headers: { 'X-XSRF-TOKEN': csrfToken },
  });

  if (!response.ok) {
    const errorData: unknown = await response.json().catch((): null => null);
    if (isProblemDetails(errorData)) throw new RequestError(errorData);
    throw new Error(ERROR_MESSAGES.FEED_COMMENT_DELETION_FAILED);
  }
};
