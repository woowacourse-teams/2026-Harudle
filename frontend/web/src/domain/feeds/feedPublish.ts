import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { authFetch } from '../../shared/auth';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import type { Category } from '../category/categoryList';
import type { DiaryDetailResponse } from '../diary/diaryDetail';
import { isFeed, type Feed } from './feedList';

export interface FeedPublishRequest {
  readonly diaryId: DiaryDetailResponse['id'];
  readonly categoryId: Category['id'];
}

export const publishFeed = async (
  request: FeedPublishRequest,
): Promise<Feed> => {
  const response = await authFetch(`${API_BASE_URL}/feeds`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    const errorData: unknown = await response.json().catch(() => null);
    if (isProblemDetails(errorData)) throw new RequestError(errorData);
    throw new Error(ERROR_MESSAGES.FEED_PUBLISH_FAILED);
  }

  const data: unknown = await response.json();
  if (!isFeed(data))
    throw new Error(ERROR_MESSAGES.INVALID_FEED_PUBLISH_RESPONSE);
  return data;
};
