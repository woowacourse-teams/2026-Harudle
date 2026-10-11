import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { publicFetch } from '../../shared/auth';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import type { DeepReadonly } from '../../shared/typeUtils';
import { isNonNegativeInteger, isRecord } from '../../shared/utils';

export type FeedSort = 'LATEST' | 'POPULAR';

export interface FeedListRequest {
  readonly categoryId?: string;
  readonly sort?: FeedSort;
  readonly cursor?: string | null;
  readonly size?: number;
  readonly signal?: AbortSignal;
}

interface FeedData {
  id: string;
  author: {
    id: string;
    nickname: string;
    profileImageUrl: string;
  };
  category: {
    id: string;
    name: string;
  };
  imageUrl: string;
  imageUrlExpiresAt: string;
  publishedAt: string;
  likeCount: number;
  commentCount: number;
  likedByMe: boolean;
  isMine: boolean;
  shareUrl: string;
}

export type Feed = DeepReadonly<FeedData>;

interface FeedListData {
  items: Feed[];
  nextCursor: string | null;
  hasNext: boolean;
}

export type FeedListResponse = DeepReadonly<FeedListData>;

export const getFeedList = async ({
  categoryId,
  sort,
  cursor,
  size,
  signal,
}: FeedListRequest = {}): Promise<FeedListResponse> => {
  const params = new URLSearchParams();
  if (categoryId !== undefined) params.set('categoryId', categoryId);
  if (sort !== undefined) params.set('sort', sort);
  if (cursor !== undefined && cursor !== null) params.set('cursor', cursor);
  if (size !== undefined) params.set('size', String(size));
  const query = params.toString();

  const response = await publicFetch(
    `${API_BASE_URL}/feeds${query ? `?${query}` : ''}`,
    { signal },
  );

  if (!response.ok) {
    const errorData: unknown = await response.json().catch((): null => null);
    if (isProblemDetails(errorData)) throw new RequestError(errorData);
    throw new Error(ERROR_MESSAGES.FEED_LIST_FETCH_FAILED);
  }

  const data: unknown = await response.json();
  if (!isFeedListResponse(data)) {
    throw new Error(ERROR_MESSAGES.INVALID_FEED_LIST_RESPONSE);
  }
  return data;
};

export const isFeed = (value: unknown): value is Feed => {
  return (
    isRecord(value) &&
    typeof value.id === 'string' &&
    isRecord(value.author) &&
    typeof value.author.id === 'string' &&
    typeof value.author.nickname === 'string' &&
    typeof value.author.profileImageUrl === 'string' &&
    isRecord(value.category) &&
    typeof value.category.id === 'string' &&
    typeof value.category.name === 'string' &&
    typeof value.imageUrl === 'string' &&
    typeof value.imageUrlExpiresAt === 'string' &&
    typeof value.publishedAt === 'string' &&
    typeof value.likeCount === 'number' &&
    isNonNegativeInteger(value.likeCount) &&
    typeof value.commentCount === 'number' &&
    isNonNegativeInteger(value.commentCount) &&
    typeof value.likedByMe === 'boolean' &&
    typeof value.isMine === 'boolean' &&
    typeof value.shareUrl === 'string'
  );
};

export const isFeedListResponse = (
  value: unknown,
): value is FeedListResponse => {
  return (
    isRecord(value) &&
    Array.isArray(value.items) &&
    value.items.every(isFeed) &&
    (value.nextCursor === null || typeof value.nextCursor === 'string') &&
    typeof value.hasNext === 'boolean'
  );
};

export const isFeedSort = (value: unknown): value is FeedSort => {
  return value === 'LATEST' || value === 'POPULAR';
};
