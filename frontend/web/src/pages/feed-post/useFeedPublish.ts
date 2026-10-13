import { useCallback, useState } from 'react';
import { useNavigate } from 'react-router';
import {
  publishFeed,
  type FeedPublishRequest,
} from '../../domain/feeds/feedPublish';
import type { Feed } from '../../domain/feeds/feedList';
import { useErrorTracking } from '../../posthog/useErrorTracking';
import type { ApiRequest } from '../../shared/api';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { useAnalytics } from '../../posthog/useAnalytics';

interface FeedPublishOptions {
  readonly categoryId: FeedPublishRequest['categoryId'];
}

interface FeedPublishState {
  readonly request: ApiRequest<Feed>;
  readonly execute: (options: FeedPublishOptions) => Promise<void>;
}

const useFeedPublish = ({
  diaryId,
}: Pick<FeedPublishRequest, 'diaryId'>): FeedPublishState => {
  const navigate = useNavigate();
  const [request, setRequest] = useState<ApiRequest<Feed>>({ status: 'idle' });
  const { captureError } = useErrorTracking();
  const { track } = useAnalytics();

  const execute = useCallback(
    async ({ categoryId }: FeedPublishOptions): Promise<void> => {
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
        setRequest({ status: 'success', data: feed });
        void navigate('/', { replace: true });
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
      }
    },
    [diaryId, navigate, captureError, request.status, track],
  );

  return { request, execute };
};

export default useFeedPublish;
