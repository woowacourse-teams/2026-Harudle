import { useCallback, useState } from 'react';
import { createFeedComment } from '../../domain/feeds/feedCommentCreate';
import type { FeedComment } from '../../domain/feeds/feedComments';
import type { ApiRequest } from '../../shared/api';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { useErrorTracking } from '../../posthog/useErrorTracking';

interface CommentCreateOptions {
  readonly content: string;
  readonly onSuccess: (comment: FeedComment) => void;
}

interface FeedCommentCreateState {
  readonly request: ApiRequest<FeedComment>;
  readonly execute: (options: CommentCreateOptions) => Promise<void>;
}

const useFeedCommentCreate = ({
  feedId,
}: {
  readonly feedId: string;
}): FeedCommentCreateState => {
  const { captureError } = useErrorTracking();
  const [request, setRequest] = useState<ApiRequest<FeedComment>>({
    status: 'idle',
  });

  const execute = useCallback(
    async ({ content, onSuccess }: CommentCreateOptions): Promise<void> => {
      if (request.status === 'loading') return;
      setRequest({ status: 'loading' });

      try {
        const comment = await createFeedComment({ feedId, content });
        setRequest({ status: 'success', data: comment });
        onSuccess(comment);
      } catch (error: unknown) {
        const requestError =
          error instanceof Error
            ? error
            : new Error(ERROR_MESSAGES.FEED_COMMENT_CREATION_FAILED);
        captureError(requestError, {
          feature: 'feed',
          operation: 'create_comment',
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

export default useFeedCommentCreate;
