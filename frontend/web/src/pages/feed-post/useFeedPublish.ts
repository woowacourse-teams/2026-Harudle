import { useCallback, useState } from 'react';
import {
  publishFeed,
  type FeedPublishRequest,
} from '../../domain/feeds/feedPublish';
import type { Feed } from '../../domain/feeds/feedList';
import { setFeedListCache } from '../../domain/feeds/feedListCache';
import { useErrorTracking } from '../../posthog/useErrorTracking';
import type { ApiRequest } from '../../shared/api';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { useAnalytics } from '../../posthog/useAnalytics';

interface FeedPublishOptions {
  readonly categoryId: FeedPublishRequest['categoryId'];
  readonly onSuccess?: (response: Feed) => void;
  readonly onError?: (error: Error) => void;
}

interface FeedPublishState {
  readonly request: ApiRequest<Feed>;
  readonly execute: (options: FeedPublishOptions) => Promise<void>;
}

const useFeedPublish = ({
  diaryId,
}: Pick<FeedPublishRequest, 'diaryId'>): FeedPublishState => {
  const [request, setRequest] = useState<ApiRequest<Feed>>({ status: 'idle' });
  const { captureError } = useErrorTracking();
  const { track } = useAnalytics();

  const execute = useCallback(
    async ({
      categoryId,
      onSuccess,
      onError,
    }: FeedPublishOptions): Promise<void> => {
      if (request.status === 'loading') return;
      setRequest({ status: 'loading' });

      try {
        track('api_requested', {
          feature: 'feed',
          operation: 'feed_publish',
          endpoint: '/feeds',
          method: 'POST',
          diary_id: diaryId,
          category_id: categoryId,
        });
        const feed = await publishFeed({ diaryId, categoryId });
        setFeedListCache(null);
        setRequest({ status: 'success', data: feed });
        onSuccess?.(feed);
      } catch (error: unknown) {
        const requestError =
          error instanceof Error
            ? error
            : new Error(ERROR_MESSAGES.FEED_PUBLISH_FAILED);
        captureError(requestError, {
          feature: 'feed',
          operation: 'publish',
          diary_id: diaryId,
        });
        setRequest({ status: 'error', error: requestError });
        onError?.(requestError);
      }
    },
    [diaryId, captureError, request.status, track],
  );

  return { request, execute };
};

export default useFeedPublish;
