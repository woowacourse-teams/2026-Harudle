import { useCallback, useEffect, useRef, useState } from 'react';
import {
  getNotifications,
  markNotificationsRead,
  markAllNotificationsRead,
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
  readonly markAllRead: () => Promise<void>;
  readonly isBusy: boolean;
  readonly isReadingAll: boolean;
}

const NOTIFICATION_SIZE = 20;

const useNotifications = (): NotificationsState => {
  // 조회 응답이 뒤늦게 도착해 읽음 상태를 덮어쓰지 않도록 조회와 변경을 겹치지 않는다.
  const operationInFlight = useRef(false);
  const [isBusy, setIsBusy] = useState(false);
  const [isReadingAll, setIsReadingAll] = useState(false);
  const [request, setRequest] = useState<ApiRequest<NotificationsResponse>>({
    status: 'idle',
  });
  const [loadMoreRequest, setLoadMoreRequest] = useState<ApiRequest<void>>({
    status: 'idle',
  });

  const execute =
    useCallback(async (): Promise<NotificationsResponse | null> => {
      if (operationInFlight.current) return null;
      operationInFlight.current = true;
      setIsBusy(true);
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
      } finally {
        operationInFlight.current = false;
        setIsBusy(false);
      }
    }, []);

  useEffect((): void => {
    // 조회만으로는 읽음 상태를 바꾸지 않는다.
    void execute();
  }, [execute]);

  const loadMore = async (): Promise<NotificationsResponse | null> => {
    if (
      request.status !== 'success' ||
      !request.data.nextCursor ||
      operationInFlight.current
    )
      return null;
    operationInFlight.current = true;
    setIsBusy(true);
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
    } finally {
      operationInFlight.current = false;
      setIsBusy(false);
    }
  };

  const markRead = async (items: readonly Notification[]): Promise<void> => {
    const ids = items
      .filter((item): boolean => !item.read)
      .map((item): string => item.id);
    if (ids.length === 0) return;
    if (operationInFlight.current)
      throw new Error(FEED_COPY.notificationsReadError);
    operationInFlight.current = true;
    setIsBusy(true);
    try {
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
    } finally {
      operationInFlight.current = false;
      setIsBusy(false);
    }
  };

  const markAllRead = async (): Promise<void> => {
    if (operationInFlight.current) return;
    operationInFlight.current = true;
    setIsBusy(true);
    setIsReadingAll(true);
    try {
      await markAllNotificationsRead();
      setRequest((previous): ApiRequest<NotificationsResponse> =>
        previous.status !== 'success'
          ? previous
          : {
              status: 'success',
              data: {
                ...previous.data,
                unreadCount: 0,
                items: previous.data.items.map((item): Notification => ({
                  ...item,
                  read: true,
                })),
              },
            },
      );
      // 전체 읽기 도중 도착한 새 알림의 읽음 상태와 배지는 서버에서 다시 확인한다.
      try {
        const data = await getNotifications({ size: NOTIFICATION_SIZE });
        setRequest({ status: 'success', data });
        setLoadMoreRequest({ status: 'idle' });
      } catch {
        throw new Error(FEED_COPY.notificationsRefreshAfterReadError);
      }
    } finally {
      operationInFlight.current = false;
      setIsBusy(false);
      setIsReadingAll(false);
    }
  };

  return {
    request,
    loadMoreRequest,
    execute,
    loadMore,
    markRead,
    markAllRead,
    isBusy,
    isReadingAll,
  };
};

export default useNotifications;
