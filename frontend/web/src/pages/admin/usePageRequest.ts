import { useEffect, useState, type Dispatch, type SetStateAction } from 'react';
import type { Page } from './adminApi';

export type PageRequest<T> =
  | { readonly status: 'loading'; readonly previous: Page<T> | null }
  | { readonly status: 'success'; readonly data: Page<T> }
  | { readonly status: 'error'; readonly message: string };

/** 다음 쪽을 불러오는 동안에도 마지막으로 받은 목록을 보여 준다. */
export const shownPage = <T>(request: PageRequest<T>): Page<T> | null => {
  if (request.status === 'success') return request.data;
  if (request.status === 'loading') return request.previous;
  return null;
};

/**
 * 조회 함수가 바뀔 때마다 한 쪽을 다시 불러온다. `fetchPage`가 `null`이면 현재 화면에서
 * 쓰지 않는 목록이므로 조회하지 않는다. 늦게 도착한 이전 조회의 응답은 버린다.
 */
export const usePageRequest = <T>(
  fetchPage: (() => Promise<Page<T>>) | null,
  fallbackMessage: string,
): [PageRequest<T>, Dispatch<SetStateAction<PageRequest<T>>>] => {
  const [request, setRequest] = useState<PageRequest<T>>({
    status: 'loading',
    previous: null,
  });

  useEffect((): (() => void) | void => {
    if (!fetchPage) return;
    let ignore = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- 조회 조건이 바뀐 즉시 이전 목록을 유지한 채 로딩으로 표시한다.
    setRequest((current): PageRequest<T> => ({
      status: 'loading',
      previous: shownPage(current),
    }));
    fetchPage().then(
      (data): void => {
        if (!ignore) setRequest({ status: 'success', data });
      },
      (cause: unknown): void => {
        if (ignore) return;
        setRequest({
          status: 'error',
          message: cause instanceof Error ? cause.message : fallbackMessage,
        });
      },
    );
    return (): void => {
      ignore = true;
    };
  }, [fetchPage, fallbackMessage]);

  return [request, setRequest];
};
