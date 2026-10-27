import { useCallback, useEffect, useRef, useState } from 'react';
import {
  getBlockedUsers,
  type BlockedUser,
  type BlockedUsersResponse,
} from '../../domain/blocks/blocks';
import type { ApiRequest } from '../../shared/api';
import { BLOCKED_USERS_COPY } from './copy';

type ListRetry =
  | { readonly kind: 'refresh' }
  | { readonly kind: 'more'; readonly cursor: string };

interface BlockedUsersState {
  readonly request: ApiRequest<BlockedUsersResponse>;
  readonly isLoading: boolean;
  readonly listRetry: ListRetry | null;
  readonly refresh: () => Promise<void>;
  readonly retry: () => Promise<void>;
  readonly loadMore: () => Promise<void>;
  readonly removeUser: (userId: BlockedUser['userId']) => void;
}

const useBlockedUsers = (): BlockedUsersState => {
  const [request, setRequest] = useState<ApiRequest<BlockedUsersResponse>>({
    status: 'loading',
  });
  const [isLoading, setIsLoading] = useState(true);
  const [listRetry, setListRetry] = useState<ListRetry | null>(null);
  const readControllerRef = useRef<AbortController | null>(null);

  const load = useCallback(async (cursor?: string): Promise<void> => {
    if (readControllerRef.current !== null) return;
    const controller = new AbortController();
    readControllerRef.current = controller;
    setIsLoading(true);
    try {
      const data = await getBlockedUsers({ cursor, signal: controller.signal });
      if (controller.signal.aborted) return;
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
      if (controller.signal.aborted) return;
      const error =
        caught instanceof Error
          ? caught
          : new Error(BLOCKED_USERS_COPY.blockedUsersLoadError);
      setRequest((previous): ApiRequest<BlockedUsersResponse> =>
        previous.status === 'success' ? previous : { status: 'error', error },
      );
      setListRetry(
        cursor === undefined ? { kind: 'refresh' } : { kind: 'more', cursor },
      );
    } finally {
      if (readControllerRef.current === controller) {
        readControllerRef.current = null;
        setIsLoading(false);
      }
    }
  }, []);

  useEffect((): (() => void) => {
    // 초기 로딩 상태에서 최초 조회도 재조회와 같은 요청 경로로 시작한다.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load();
    return (): void => {
      readControllerRef.current?.abort();
      readControllerRef.current = null;
    };
  }, [load]);

  const refresh = async (): Promise<void> => {
    await load();
  };

  const retry = async (): Promise<void> => {
    await load(listRetry?.kind === 'more' ? listRetry.cursor : undefined);
  };

  const loadMore = async (): Promise<void> => {
    if (request.status === 'success' && request.data.nextCursor) {
      await load(request.data.nextCursor);
    }
  };

  const removeUser = (userId: BlockedUser['userId']): void => {
    setRequest((previous): ApiRequest<BlockedUsersResponse> =>
      previous.status === 'success'
        ? {
            status: 'success',
            data: {
              ...previous.data,
              items: previous.data.items.filter(
                (item): boolean => item.userId !== userId,
              ),
            },
          }
        : previous,
    );
  };

  return {
    request,
    isLoading,
    listRetry,
    refresh,
    retry,
    loadMore,
    removeUser,
  };
};

export default useBlockedUsers;
