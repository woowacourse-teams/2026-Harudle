import { useCallback, useEffect, useRef, useState } from 'react';
import {
  getFeedComments,
  type FeedCommentsResponse,
  type FeedComment,
} from '../../domain/feeds/feedComments';
import type { ApiRequest } from '../../shared/api';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { useErrorTracking } from '../../posthog/useErrorTracking';

interface FeedCommentsState {
  readonly request: ApiRequest<FeedCommentsResponse>;
  readonly loadMoreRequest: ApiRequest<void>;
  readonly loadMore: () => Promise<void>;
  readonly addComment: (comment: FeedComment) => void;
  readonly removeComment: (commentId: FeedComment['id']) => void;
  readonly execute: () => Promise<FeedCommentsResponse | null>;
}

const COMMENT_SIZE = 50;

const useFeedComments = ({
  feedId,
}: {
  readonly feedId: string;
}): FeedCommentsState => {
  const { captureError } = useErrorTracking();
  const [request, setRequest] = useState<ApiRequest<FeedCommentsResponse>>({
    status: 'idle',
  });
  const [loadMoreRequest, setLoadMoreRequest] = useState<ApiRequest<void>>({
    status: 'idle',
  });
  const abortControllerRef = useRef<AbortController | null>(null);

  const execute =
    useCallback(async (): Promise<FeedCommentsResponse | null> => {
      abortControllerRef.current?.abort();
      const controller = new AbortController();
      abortControllerRef.current = controller;
      const { signal } = controller;
      setRequest({ status: 'loading' });
      setLoadMoreRequest({ status: 'idle' });
      try {
        const data = await getFeedComments({
          feedId,
          size: COMMENT_SIZE,
          signal,
        });
        if (signal.aborted) return null;
        setRequest({ status: 'success', data });
        return data;
      } catch (error: unknown) {
        if (signal.aborted) return null;
        const requestError =
          error instanceof Error
            ? error
            : new Error(ERROR_MESSAGES.FEED_COMMENTS_FETCH_FAILED);
        captureError(requestError, {
          feature: 'feed',
          operation: 'read_comments',
          feed_id: feedId,
        });
        setRequest({ status: 'error', error: requestError });
        return null;
      } finally {
        if (abortControllerRef.current === controller) {
          abortControllerRef.current = null;
        }
      }
    }, [feedId, captureError]);

  const appendCommentPage = useCallback((page: FeedCommentsResponse): void => {
    setRequest((previous) => {
      if (previous.status !== 'success') return previous;
      return {
        ...previous,
        data: {
          items: [...previous.data.items, ...page.items],
          nextCursor: page.nextCursor,
          hasNext: page.hasNext,
        },
      };
    });
  }, []);

  const loadMore = useCallback(async (): Promise<void> => {
    if (request.status !== 'success' || !request.data.nextCursor) return;

    const controller = new AbortController();
    abortControllerRef.current = controller;
    const { signal } = controller;
    setLoadMoreRequest({ status: 'loading' });

    try {
      const data = await getFeedComments({
        feedId,
        cursor: request.data.nextCursor,
        size: COMMENT_SIZE,
        signal,
      });
      if (signal.aborted) return;
      appendCommentPage(data);
      setLoadMoreRequest({ status: 'success', data: undefined });
    } catch (error: unknown) {
      if (signal.aborted) return;
      const requestError =
        error instanceof Error
          ? error
          : new Error(ERROR_MESSAGES.FEED_COMMENTS_FETCH_FAILED);
      captureError(requestError, {
        feature: 'feed',
        operation: 'read_more_comments',
        feed_id: feedId,
      });
      setLoadMoreRequest({ status: 'error', error: requestError });
    } finally {
      if (abortControllerRef.current === controller) {
        abortControllerRef.current = null;
      }
    }
  }, [request, feedId, captureError, appendCommentPage]);

  useEffect((): (() => void) => {
    // 피드가 바뀌면 첫 번째 댓글 페이지를 조회하고 이전 요청을 취소한다.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void execute();
    return (): void => abortControllerRef.current?.abort();
  }, [execute]);

  const addComment = useCallback((comment: FeedComment): void => {
    setRequest((previous) => {
      if (previous.status !== 'success') return previous;
      return {
        ...previous,
        data: { ...previous.data, items: [...previous.data.items, comment] },
      };
    });
  }, []);
  const removeComment = useCallback((commentId: FeedComment['id']): void => {
    setRequest((previous) => {
      if (previous.status !== 'success') return previous;
      return {
        ...previous,
        data: {
          ...previous.data,
          items: previous.data.items.filter(
            ({ id }): boolean => id !== commentId,
          ),
        },
      };
    });
  }, []);

  return {
    request,
    loadMoreRequest,
    loadMore,
    execute,
    addComment,
    removeComment,
  };
};

export default useFeedComments;
