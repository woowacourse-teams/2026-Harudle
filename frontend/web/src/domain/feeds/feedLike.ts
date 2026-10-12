import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { authFetch, requestCsrfToken } from '../../shared/auth';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { isNonNegativeInteger, isRecord } from '../../shared/utils';
import type { Feed } from './feedList';

interface FeedLikeRequest {
  readonly feedId: Feed['id'];
  readonly likedByMe: boolean;
}

export interface FeedLikeResponse {
  readonly likedByMe: Feed['likedByMe'];
  readonly likeCount: Feed['likeCount'];
}

export const setFeedLike = async ({
  feedId,
  likedByMe,
}: FeedLikeRequest): Promise<FeedLikeResponse> => {
  const csrfToken = await requestCsrfToken();
  const response = await authFetch(`${API_BASE_URL}/feeds/${feedId}/like`, {
    method: likedByMe ? 'PUT' : 'DELETE',
    headers: {
      'X-XSRF-TOKEN': csrfToken,
    },
  });

  if (!response.ok) {
    const errorData: unknown = await response.json().catch((): null => null);
    if (isProblemDetails(errorData)) throw new RequestError(errorData);
    throw new Error(ERROR_MESSAGES.FEED_LIKE_FAILED);
  }

  const data: unknown = await response.json();
  if (!isFeedLikeResponse(data)) {
    throw new Error(ERROR_MESSAGES.INVALID_FEED_LIKE_RESPONSE);
  }
  return data;
};

export const isFeedLikeResponse = (
  value: unknown,
): value is FeedLikeResponse => {
  return (
    isRecord(value) &&
    typeof value.likedByMe === 'boolean' &&
    typeof value.likeCount === 'number' &&
    isNonNegativeInteger(value.likeCount)
  );
};
