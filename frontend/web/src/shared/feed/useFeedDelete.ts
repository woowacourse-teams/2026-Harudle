import { useCallback, useState } from 'react';
import { deleteFeed } from '../../domain/feeds/feedDelete';
import type { Feed } from '../../domain/feeds/feedList';
import {
  getFeedListCache,
  setFeedListCache,
} from '../../domain/feeds/feedListCache';
import { removeFeed } from '../../domain/feeds/feedListUpdates';
import { useErrorTracking } from '../../posthog/useErrorTracking';
import type { ApiRequest } from '../../shared/api';
import { ERROR_MESSAGES } from '../../shared/errorMessage';

interface UseFeedDeleteProps {
  readonly feedId: Feed['id'];
}

interface FeedDeleteOptions {
  readonly onSuccess: () => void;
}

interface FeedDeleteState {
  readonly request: ApiRequest<void>;
  readonly execute: (options: FeedDeleteOptions) => Promise<void>;
}

const useFeedDelete = ({ feedId }: UseFeedDeleteProps): FeedDeleteState => {
  const { captureError } = useErrorTracking();
  const [request, setRequest] = useState<ApiRequest<void>>({ status: 'idle' });

  const execute = useCallback(
    async ({ onSuccess }: FeedDeleteOptions): Promise<void> => {
      if (request.status === 'loading') return;
      setRequest({ status: 'loading' });

      try {
        await deleteFeed({ feedId });
      } catch (error: unknown) {
        const requestError =
          error instanceof Error
            ? error
            : new Error(ERROR_MESSAGES.FEED_DELETION_FAILED);
        captureError(requestError, {
          feature: 'feed',
          operation: 'delete',
          feed_id: feedId,
        });
        setRequest({ status: 'error', error: requestError });
        return;
      }

      const cached = getFeedListCache();
      if (cached !== null) {
        setFeedListCache({
          ...cached,
          data: removeFeed(cached.data, feedId),
        });
      }

      setRequest({ status: 'success', data: undefined });
      onSuccess();
    },
    [feedId, request.status, captureError],
  );

  return { request, execute };
};

export default useFeedDelete;
