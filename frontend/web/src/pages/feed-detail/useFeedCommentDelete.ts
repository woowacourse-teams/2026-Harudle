import { useCallback, useState } from 'react';
import { deleteFeedComment } from '../../domain/feeds/feedCommentDelete';
import type { FeedComment } from '../../domain/feeds/feedComments';
import type { ApiRequest } from '../../shared/api';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { useErrorTracking } from '../../posthog/useErrorTracking';

interface UseFeedCommentDeleteProps {
  readonly commentId: FeedComment['id'];
}

interface FeedCommentDeleteOptions {
  readonly onSuccess: () => void;
}

interface FeedCommentDeleteState {
  readonly request: ApiRequest<void>;
  readonly execute: (options: FeedCommentDeleteOptions) => Promise<void>;
}

const useFeedCommentDelete = ({
  commentId,
}: UseFeedCommentDeleteProps): FeedCommentDeleteState => {
  const { captureError } = useErrorTracking();
  const [request, setRequest] = useState<ApiRequest<void>>({ status: 'idle' });

  const execute = useCallback(
    async ({ onSuccess }: FeedCommentDeleteOptions): Promise<void> => {
      if (request.status === 'loading') return;
      setRequest({ status: 'loading' });

      try {
        await deleteFeedComment({ commentId });
      } catch (error: unknown) {
        const requestError =
          error instanceof Error
            ? error
            : new Error(ERROR_MESSAGES.FEED_COMMENT_DELETION_FAILED);
        captureError(requestError, {
          feature: 'feed',
          operation: 'delete_comment',
          comment_id: commentId,
        });
        setRequest({ status: 'error', error: requestError });
        return;
      }

      setRequest({ status: 'success', data: undefined });
      onSuccess();
    },
    [commentId, request.status, captureError],
  );

  return { request, execute };
};

export default useFeedCommentDelete;
