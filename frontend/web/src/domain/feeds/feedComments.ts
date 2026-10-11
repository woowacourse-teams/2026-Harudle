import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { publicFetch } from '../../shared/auth';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { isRecord } from '../../shared/utils';
import type { Feed } from './feedList';

export interface FeedComment {
  readonly id: string;
  readonly author: Feed['author'];
  readonly content: string;
  readonly createdAt: string;
  readonly isMine: boolean;
}

export interface FeedCommentsResponse {
  readonly items: readonly FeedComment[];
  readonly nextCursor: string | null;
  readonly hasNext: boolean;
}

interface FeedCommentsRequest {
  readonly feedId: Feed['id'];
  readonly size?: number;
  readonly cursor?: string;
  readonly signal?: AbortSignal;
}

export const isFeedComment = (value: unknown): value is FeedComment => {
  return (
    isRecord(value) &&
    typeof value.id === 'string' &&
    isRecord(value.author) &&
    typeof value.author.id === 'string' &&
    typeof value.author.nickname === 'string' &&
    typeof value.author.profileImageUrl === 'string' &&
    typeof value.content === 'string' &&
    typeof value.createdAt === 'string' &&
    Number.isFinite(Date.parse(value.createdAt)) &&
    typeof value.isMine === 'boolean'
  );
};

export const isFeedCommentsResponse = (
  value: unknown,
): value is FeedCommentsResponse => {
  return (
    isRecord(value) &&
    Array.isArray(value.items) &&
    value.items.every(isFeedComment) &&
    typeof value.hasNext === 'boolean' &&
    (value.hasNext
      ? typeof value.nextCursor === 'string' && value.nextCursor.length > 0
      : value.nextCursor === null)
  );
};

export const getFeedComments = async ({
  feedId,
  size = 20,
  signal,
}: FeedCommentsRequest): Promise<FeedCommentsResponse> => {
  const params = new URLSearchParams({ size: String(size) });
  const response = await publicFetch(
    `${API_BASE_URL}/feeds/${feedId}/comments?${params}`,
    { signal },
  );
  if (!response.ok) {
    const errorData: unknown = await response.json().catch((): null => null);
    if (isProblemDetails(errorData)) throw new RequestError(errorData);
    throw new Error(ERROR_MESSAGES.FEED_COMMENTS_FETCH_FAILED);
  }
  const data: unknown = await response.json();
  if (!isFeedCommentsResponse(data))
    throw new Error(ERROR_MESSAGES.INVALID_FEED_COMMENTS_RESPONSE);
  return data;
};
