import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { authFetch, requestCsrfToken } from '../../shared/auth';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { isNonNegativeInteger, isRecord } from '../../shared/utils';

type NotificationType = 'LIKE' | 'COMMENT';

interface NotificationActor {
  readonly id: string;
  readonly nickname: string;
  readonly profileImageUrl: string;
  readonly profileImageUrlExpiresAt: string | null;
}

export interface Notification {
  readonly id: string;
  readonly type: NotificationType;
  readonly feedId: string;
  readonly actor: NotificationActor | null;
  readonly message: string;
  readonly createdAt: string;
  readonly read: boolean;
}

export interface NotificationsResponse {
  readonly items: readonly Notification[];
  readonly nextCursor: string | null;
  readonly hasNext: boolean;
  readonly unreadCount: number;
}

interface NotificationsRequest {
  readonly size?: number;
  readonly cursor?: string;
}

const isDate = (value: unknown): value is string =>
  typeof value === 'string' && Number.isFinite(Date.parse(value));

const isActor = (value: unknown): value is NotificationActor =>
  isRecord(value) &&
  typeof value.id === 'string' &&
  typeof value.nickname === 'string' &&
  typeof value.profileImageUrl === 'string' &&
  (value.profileImageUrlExpiresAt === null ||
    isDate(value.profileImageUrlExpiresAt));

const isNotification = (value: unknown): value is Notification =>
  isRecord(value) &&
  typeof value.id === 'string' &&
  (value.type === 'LIKE' || value.type === 'COMMENT') &&
  typeof value.feedId === 'string' &&
  (value.actor === null || isActor(value.actor)) &&
  typeof value.message === 'string' &&
  isDate(value.createdAt) &&
  typeof value.read === 'boolean';

export const isNotificationsResponse = (
  value: unknown,
): value is NotificationsResponse =>
  isRecord(value) &&
  Array.isArray(value.items) &&
  value.items.every(isNotification) &&
  typeof value.hasNext === 'boolean' &&
  (value.hasNext
    ? typeof value.nextCursor === 'string' && value.nextCursor.length > 0
    : value.nextCursor === null) &&
  typeof value.unreadCount === 'number' &&
  isNonNegativeInteger(value.unreadCount);

const throwResponseError = async (
  response: Response,
  fallback: string,
): Promise<never> => {
  const data: unknown = await response.json().catch((): null => null);
  if (isProblemDetails(data)) throw new RequestError(data);
  throw new Error(fallback);
};

export const getNotifications = async ({
  size = 20,
  cursor,
}: NotificationsRequest = {}): Promise<NotificationsResponse> => {
  const params = new URLSearchParams({ size: String(size) });
  if (cursor !== undefined) params.set('cursor', cursor);
  const response = await authFetch(`${API_BASE_URL}/notifications?${params}`);
  if (!response.ok)
    return throwResponseError(
      response,
      ERROR_MESSAGES.NOTIFICATIONS_FETCH_FAILED,
    );
  const data: unknown = await response.json();
  if (!isNotificationsResponse(data))
    throw new Error(ERROR_MESSAGES.INVALID_NOTIFICATIONS_RESPONSE);
  return data;
};

export const markNotificationsRead = async (
  ids: readonly string[],
): Promise<readonly string[]> => {
  if (ids.length === 0) return [];
  const csrfToken = await requestCsrfToken();
  const results = await Promise.allSettled(
    ids.map(async (id): Promise<string> => {
      const response = await authFetch(
        `${API_BASE_URL}/notifications/${encodeURIComponent(id)}`,
        {
          method: 'PATCH',
          headers: {
            'Content-Type': 'application/json',
            'X-XSRF-TOKEN': csrfToken,
          },
          body: JSON.stringify({ read: true }),
        },
      );
      if (!response.ok)
        return throwResponseError(
          response,
          ERROR_MESSAGES.NOTIFICATION_READ_FAILED,
        );
      return id;
    }),
  );
  return results.flatMap((result): string[] =>
    result.status === 'fulfilled' ? [result.value] : [],
  );
};
