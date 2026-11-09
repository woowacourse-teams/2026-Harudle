import { useCallback, useEffect, useState } from 'react';
import {
  getNotifications,
  markNotificationsRead,
  type Notification,
  type NotificationsResponse,
} from '../../domain/notifications/notifications';
import type { ApiRequest } from '../../shared/api';
import { FEED_COPY } from './copy';

export interface NotificationsState {
  readonly request: ApiRequest<NotificationsResponse>;
  readonly loadMoreRequest: ApiRequest<void>;
  readonly execute: () => Promise<NotificationsResponse | null>;
  readonly loadMore: () => Promise<NotificationsResponse | null>;
  readonly markRead: (items: readonly Notification[]) => Promise<void>;
}

const NOTIFICATION_SIZE = 20;

const useNotifications = (): NotificationsState => {
  const [request, setRequest] = useState<ApiRequest<NotificationsResponse>>({
    status: 'idle',
  });
  const [loadMoreRequest, setLoadMoreRequest] = useState<ApiRequest<void>>({
    status: 'idle',
  });

  const execute =
    useCallback(async (): Promise<NotificationsResponse | null> => {
      setRequest((previous): ApiRequest<NotificationsResponse> =>
        previous.status === 'success' ? previous : { status: 'loading' },
      );
      setLoadMoreRequest({ status: 'idle' });
      try {
        const data = await getNotifications({ size: NOTIFICATION_SIZE });
        setRequest({ status: 'success', data });
        return data;
      } catch (error: unknown) {
        setRequest((previous): ApiRequest<NotificationsResponse> =>
          previous.status === 'success'
            ? previous
            : {
                status: 'error',
                error:
                  error instanceof Error
                    ? error
                    : new Error(FEED_COPY.notificationsLoadError),
              },
        );
        return null;
      }
    }, []);

  useEffect((): void => {
    // 피드 진입 시 조회한 미읽음 수로 배지를 표시한다. 읽음 처리는 시트를 열 때 실행한다.
    void execute();
  }, [execute]);

  const loadMore = async (): Promise<NotificationsResponse | null> => {
    if (
      request.status !== 'success' ||
      !request.data.nextCursor ||
      loadMoreRequest.status === 'loading'
    )
      return null;
    setLoadMoreRequest({ status: 'loading' });
    try {
      const data = await getNotifications({
        size: NOTIFICATION_SIZE,
        cursor: request.data.nextCursor,
      });
      setRequest((previous): ApiRequest<NotificationsResponse> =>
        previous.status !== 'success'
          ? previous
          : {
              status: 'success',
              data: {
                ...previous.data,
                items: [...previous.data.items, ...data.items],
                nextCursor: data.nextCursor,
                hasNext: data.hasNext,
              },
            },
      );
      setLoadMoreRequest({ status: 'success', data: undefined });
      return data;
    } catch (error: unknown) {
      setLoadMoreRequest({
        status: 'error',
        error:
          error instanceof Error
            ? error
            : new Error(FEED_COPY.notificationsLoadError),
      });
      return null;
    }
  };

  const markRead = async (items: readonly Notification[]): Promise<void> => {
    const ids = items
      .filter((item): boolean => !item.read)
      .map((item): string => item.id);
    const readIds = await markNotificationsRead(ids);
    setRequest((previous): ApiRequest<NotificationsResponse> => {
      if (previous.status !== 'success') return previous;
      const readCount = previous.data.items.filter(
        (item): boolean => !item.read && readIds.includes(item.id),
      ).length;
      return {
        status: 'success',
        data: {
          ...previous.data,
          unreadCount: Math.max(0, previous.data.unreadCount - readCount),
          items: previous.data.items.map((item): Notification =>
            readIds.includes(item.id) ? { ...item, read: true } : item,
          ),
        },
      };
    });
    if (readIds.length < ids.length)
      throw new Error(FEED_COPY.notificationsReadError);
  };

  return { request, loadMoreRequest, execute, loadMore, markRead };
};

export default useNotifications;
