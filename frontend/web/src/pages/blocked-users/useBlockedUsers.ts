import { useEffect, useRef, useState } from 'react';
import {
  getBlockedUsers,
  setUserBlocked,
  type BlockedUser,
  type BlockedUsersResponse,
} from '../../domain/blocks/blocks';
import { setFeedListCache } from '../../domain/feeds/feedListCache';
import type { ApiRequest } from '../../shared/api';
import { useToastContext } from '../../shared/ToastContext';
import { BLOCKED_USERS_COPY } from './copy';

type ListRetry =
  | { readonly kind: 'refresh' }
  | { readonly kind: 'more'; readonly cursor: string };

interface BlockedUsersState {
  readonly request: ApiRequest<BlockedUsersResponse>;
  readonly busy: string | null;
  readonly listRetry: ListRetry | null;
  readonly retry: () => Promise<void>;
  readonly loadMore: () => Promise<void>;
  readonly unblock: (user: BlockedUser) => Promise<void>;
}

const useBlockedUsers = (): BlockedUsersState => {
  const { addToast } = useToastContext();
  const [request, setRequest] = useState<ApiRequest<BlockedUsersResponse>>({
    status: 'loading',
  });
  const [busy, setBusy] = useState<string | null>(null);
  const busyRef = useRef(false);
  const [listRetry, setListRetry] = useState<ListRetry | null>(null);
  const mountedRef = useRef(true);
  const readControllerRef = useRef<AbortController | null>(null);

  useEffect((): (() => void) => {
    mountedRef.current = true;
    const controller = new AbortController();
    readControllerRef.current = controller;
    void getBlockedUsers({ signal: controller.signal })
      .then((data): void => {
        if (mountedRef.current && !controller.signal.aborted)
          setRequest({ status: 'success', data });
      })
      .catch((caught: unknown): void => {
        if (mountedRef.current && !controller.signal.aborted)
          setRequest({
            status: 'error',
            error:
              caught instanceof Error
                ? caught
                : new Error(BLOCKED_USERS_COPY.blockedUsersLoadError),
          });
      })
      .finally((): void => {
        if (readControllerRef.current === controller)
          readControllerRef.current = null;
      });
    return (): void => {
      mountedRef.current = false;
      readControllerRef.current?.abort();
      readControllerRef.current = null;
    };
  }, []);

  const load = async (cursor?: string): Promise<void> => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy('more');
    readControllerRef.current?.abort();
    const controller = new AbortController();
    readControllerRef.current = controller;
    try {
      const data = await getBlockedUsers({ cursor, signal: controller.signal });
      if (!mountedRef.current || controller.signal.aborted) return;
      setListRetry(null);
      setRequest((previous): ApiRequest<BlockedUsersResponse> => ({
        status: 'success',
        data: {
          ...data,
          items:
            cursor && previous.status === 'success'
              ? [
                  ...previous.data.items,
                  ...data.items.filter(
                    (item): boolean =>
                      !previous.data.items.some(
                        (old): boolean => old.userId === item.userId,
                      ),
                  ),
                ]
              : data.items,
        },
      }));
    } catch (caught: unknown) {
      if (!mountedRef.current || controller.signal.aborted) return;
      if (request.status !== 'success') {
        setRequest({
          status: 'error',
          error:
            caught instanceof Error
              ? caught
              : new Error(BLOCKED_USERS_COPY.blockedUsersLoadError),
        });
      } else {
        setListRetry(
          cursor === undefined ? { kind: 'refresh' } : { kind: 'more', cursor },
        );
      }
    } finally {
      if (readControllerRef.current === controller)
        readControllerRef.current = null;
      busyRef.current = false;
      if (mountedRef.current) setBusy(null);
    }
  };

  const unblock = async (user: BlockedUser): Promise<void> => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(user.userId);
    try {
      await setUserBlocked(user.userId, false);
      setFeedListCache(null);
      if (!mountedRef.current) return;
      addToast(
        { type: 'success', message: BLOCKED_USERS_COPY.unblockSuccess },
        2000,
      );
      setRequest((previous): ApiRequest<BlockedUsersResponse> =>
        previous.status === 'success'
          ? {
              status: 'success',
              data: {
                ...previous.data,
                items: previous.data.items.filter(
                  (item): boolean => item.userId !== user.userId,
                ),
              },
            }
          : previous,
      );
      readControllerRef.current?.abort();
      const controller = new AbortController();
      readControllerRef.current = controller;
      try {
        const data = await getBlockedUsers({ signal: controller.signal });
        if (mountedRef.current && !controller.signal.aborted) {
          setRequest({ status: 'success', data });
          setListRetry(null);
        }
      } catch {
        if (mountedRef.current && !controller.signal.aborted)
          setListRetry({ kind: 'refresh' });
      } finally {
        if (readControllerRef.current === controller)
          readControllerRef.current = null;
      }
    } catch {
      if (mountedRef.current)
        addToast(
          { type: 'error', message: BLOCKED_USERS_COPY.unblockError },
          2000,
        );
    } finally {
      busyRef.current = false;
      if (mountedRef.current) setBusy(null);
    }
  };

  const retry = async (): Promise<void> => {
    await load(listRetry?.kind === 'more' ? listRetry.cursor : undefined);
  };

  const loadMore = async (): Promise<void> => {
    if (request.status === 'success' && request.data.nextCursor) {
      await load(request.data.nextCursor);
    }
  };

  return { request, busy, listRetry, retry, loadMore, unblock };
};

export default useBlockedUsers;
