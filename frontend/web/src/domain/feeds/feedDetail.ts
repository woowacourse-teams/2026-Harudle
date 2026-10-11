import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { publicFetch } from '../../shared/auth';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { isFeed, type Feed } from './feedList';

interface FeedDetailRequest {
  readonly feedId: Feed['id'];
  readonly signal?: AbortSignal;
}

export const getFeedDetail = async ({
  feedId,
  signal,
}: FeedDetailRequest): Promise<Feed> => {
  const response = await publicFetch(`${API_BASE_URL}/feeds/${feedId}`, {
    signal,
  });
  if (!response.ok) {
    const errorData: unknown = await response.json().catch((): null => null);
    if (isProblemDetails(errorData)) throw new RequestError(errorData);
    throw new Error(ERROR_MESSAGES.FEED_DETAIL_FETCH_FAILED);
  }
  const data: unknown = await response.json();
  if (!isFeed(data))
    throw new Error(ERROR_MESSAGES.INVALID_FEED_DETAIL_RESPONSE);
  return data;
};
