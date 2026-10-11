import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { authFetch } from '../../shared/auth';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { isFeedComment, type FeedComment } from './feedComments';

interface FeedCommentCreateRequest {
  readonly feedId: string;
  readonly content: string;
}

export const isValidCommentContent = (content: string): boolean => {
  const length = [...content.trim()].length;
  return length >= 1 && length <= 100;
};

export const createFeedComment = async ({
  feedId,
  content,
}: FeedCommentCreateRequest): Promise<FeedComment> => {
  const normalizedContent = content.trim();
  if (!isValidCommentContent(normalizedContent))
    throw new Error(ERROR_MESSAGES.INVALID_COMMENT_CONTENT);

  const response = await authFetch(`${API_BASE_URL}/feeds/${feedId}/comments`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content: normalizedContent }),
  });

  if (!response.ok) {
    const errorData: unknown = await response.json().catch((): null => null);
    if (isProblemDetails(errorData)) throw new RequestError(errorData);
    throw new Error(ERROR_MESSAGES.FEED_COMMENT_CREATION_FAILED);
  }

  const data: unknown = await response.json();
  if (!isFeedComment(data))
    throw new Error(ERROR_MESSAGES.INVALID_FEED_COMMENT_RESPONSE);
  return data;
};
