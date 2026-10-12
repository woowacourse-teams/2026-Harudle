import { useCallback, useState } from 'react';
import {
  setFeedLike,
  type FeedLikeResponse,
} from '../../domain/feeds/feedLike';
import type { Feed } from '../../domain/feeds/feedList';
import { useErrorTracking } from '../../posthog/useErrorTracking';
import type { ApiRequest } from '../../shared/api';
import { ERROR_MESSAGES } from '../../shared/errorMessage';

interface UseFeedLikeProps {
  readonly feedId: Feed['id'];
}

interface FeedLikeOptions {
  readonly likedByMe: Feed['likedByMe'];
  readonly onSuccess: (like: FeedLikeResponse) => void;
}

interface FeedLikeState {
  readonly request: ApiRequest<FeedLikeResponse>;
  readonly execute: (options: FeedLikeOptions) => Promise<void>;
}

const useFeedLike = ({ feedId }: UseFeedLikeProps): FeedLikeState => {
  const { captureError } = useErrorTracking();
  const [request, setRequest] = useState<ApiRequest<FeedLikeResponse>>({
    status: 'idle',
  });

  const execute = useCallback(
    async ({ likedByMe, onSuccess }: FeedLikeOptions): Promise<void> => {
      if (request.status === 'loading') return;
      setRequest({ status: 'loading' });

      try {
        const like = await setFeedLike({ feedId, likedByMe });
        setRequest({ status: 'success', data: like });
        onSuccess(like);
      } catch (error: unknown) {
        const requestError =
          error instanceof Error
            ? error
            : new Error(ERROR_MESSAGES.FEED_LIKE_FAILED);
        captureError(requestError, {
          feature: 'feed',
          operation: 'like',
          feed_id: feedId,
        });
        setRequest({ status: 'error', error: requestError });
        return;
      }
    },
    [feedId, request.status, captureError],
  );

  return { request, execute };
};

export default useFeedLike;
