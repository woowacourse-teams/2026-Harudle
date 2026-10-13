import { useCallback, useEffect, useRef, useState } from 'react';
import { getFeedDetail } from '../../domain/feeds/feedDetail';
import type { Feed } from '../../domain/feeds/feedList';
import type { FeedLikeResponse } from '../../domain/feeds/feedLike';
import type { ApiRequest } from '../../shared/api';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { useErrorTracking } from '../../posthog/useErrorTracking';
import { useAnalytics } from '../../posthog/useAnalytics';

interface FeedDetailState {
  readonly request: ApiRequest<Feed>;
  readonly execute: () => Promise<void>;
  readonly increaseCommentCount: () => void;
  readonly decreaseCommentCount: () => void;
  readonly updateLike: (feedId: Feed['id'], like: FeedLikeResponse) => void;
}

const useFeedDetail = ({
  feedId,
}: {
  readonly feedId: Feed['id'];
}): FeedDetailState => {
  const { captureError } = useErrorTracking();
  const { track } = useAnalytics();
  const [request, setRequest] = useState<ApiRequest<Feed>>({ status: 'idle' });
  const abortControllerRef = useRef<AbortController | null>(null);

  const execute = useCallback(async (): Promise<void> => {
    abortControllerRef.current?.abort();
    abortControllerRef.current = new AbortController();
    const { signal } = abortControllerRef.current;

    setRequest((previous) =>
      previous.status === 'success' ? previous : { status: 'loading' },
    );
    try {
      track('api_requested', {
        feature: 'feed',
        operation: 'feed_detail',
        endpoint: '/feeds/:feedId',
        method: 'GET',
        feed_id: feedId,
      });
      const data = await getFeedDetail({ feedId, signal });
      if (signal.aborted) return;
      setRequest({ status: 'success', data });
    } catch (error: unknown) {
      if (signal.aborted) return;
      const requestError =
        error instanceof Error
          ? error
          : new Error(ERROR_MESSAGES.FEED_DETAIL_FETCH_FAILED);
      captureError(requestError, {
        feature: 'feed',
        operation: 'read',
        feed_id: feedId,
      });
      setRequest({ status: 'error', error: requestError });
    }
  }, [feedId, captureError, track]);

  useEffect((): (() => void) => {
    // 상세 ID가 바뀌면 이전 조회를 취소하고 새 피드를 조회한다.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void execute();
    return (): void => abortControllerRef.current?.abort();
  }, [execute]);

  const updateLike = useCallback(
    (feedId: Feed['id'], like: FeedLikeResponse): void => {
      setRequest((previous) => {
        if (previous.status !== 'success') return previous;
        return {
          ...previous,
          data:
            previous.data.id === feedId
              ? { ...previous.data, ...like }
              : previous.data,
        };
      });
    },
    [],
  );

  const increaseCommentCount = useCallback((): void => {
    setRequest((previous) =>
      previous.status === 'success'
        ? {
            ...previous,
            data: {
              ...previous.data,
              commentCount: previous.data.commentCount + 1,
            },
          }
        : previous,
    );
  }, []);

  const decreaseCommentCount = useCallback((): void => {
    setRequest((previous) =>
      previous.status === 'success'
        ? {
            ...previous,
            data: {
              ...previous.data,
              commentCount: Math.max(0, previous.data.commentCount - 1),
            },
          }
        : previous,
    );
  }, []);
  return {
    request,
    execute,
    updateLike,
    increaseCommentCount,
    decreaseCommentCount,
  };
};

export default useFeedDetail;
