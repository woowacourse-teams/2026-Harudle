import { useToastContext } from '../../shared/ToastContext';
import { css } from '@emotion/react';
import { useEffect } from 'react';
import FeedCommentItem from './FeedCommentItem';
import FeedCommentEmpty from './FeedCommentEmpty';
import FeedCommentError from './FeedCommentError';
import LoadingSpinner from '../../shared/LoadingSpinner';
import ActionButton from '../../shared/ActionButton';
import { FEED_DETAIL_COPY } from './copy';
import type { ApiRequest } from '../../shared/api';
import type {
  FeedComment,
  FeedCommentsResponse,
} from '../../domain/feeds/feedComments';

interface FeedCommentListProps {
  readonly request: ApiRequest<FeedCommentsResponse>;
  readonly loadMoreRequest: ApiRequest<void>;
  readonly onLoadMore: () => void;
  readonly onRetry: () => void;
  readonly onDelete: (commentId: FeedComment['id']) => void;
}

const FeedCommentList = ({
  request,
  loadMoreRequest,
  onLoadMore,
  onRetry,
  onDelete,
}: FeedCommentListProps) => {
  const { addToast } = useToastContext();
  const isLoadingMore = loadMoreRequest.status === 'loading';

  useEffect((): void => {
    if (loadMoreRequest.status === 'error') {
      addToast(
        { type: 'error', message: FEED_DETAIL_COPY.commentsLoadMoreError },
        2000,
      );
    }
  }, [loadMoreRequest]);

  if (request.status === 'idle' || request.status === 'loading') {
    return <LoadingSpinner />;
  }

  if (request.status === 'error') {
    return <FeedCommentError onRetry={onRetry} />;
  }

  const { items, hasNext } = request.data;
  if (items.length === 0 && !hasNext) return <FeedCommentEmpty />;

  return (
    <>
      <ul>
        {items.map((comment) => (
          <FeedCommentItem
            key={comment.id}
            comment={comment}
            onDelete={onDelete}
          />
        ))}
      </ul>
      {hasNext && (
        <div css={loadMoreStyle}>
          <ActionButton
            variant="secondary"
            label={
              isLoadingMore
                ? FEED_DETAIL_COPY.commentsLoadingMoreAction
                : FEED_DETAIL_COPY.commentsLoadMoreAction
            }
            disabled={isLoadingMore}
            onClick={onLoadMore}
          />
        </div>
      )}
    </>
  );
};

export default FeedCommentList;

const loadMoreStyle = css`
  flex-shrink: 0;
  padding: 16px 0 24px;
`;
