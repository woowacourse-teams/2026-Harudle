import FeedCommentItem from './FeedCommentItem';
import FeedCommentEmpty from './FeedCommentEmpty';
import FeedCommentError from './FeedCommentError';
import LoadingSpinner from '../../shared/LoadingSpinner';
import type { ApiRequest } from '../../shared/api';
import type { FeedCommentsResponse } from '../../domain/feeds/feedComments';

interface FeedCommentListProps {
  readonly request: ApiRequest<FeedCommentsResponse>;
  readonly onRetry: () => void;
}

const FeedCommentList = ({ request, onRetry }: FeedCommentListProps) => {
  if (request.status === 'idle' || request.status === 'loading') {
    return <LoadingSpinner />;
  }

  if (request.status === 'error') {
    return <FeedCommentError onRetry={onRetry} />;
  }

  const { items } = request.data;
  if (items.length === 0) return <FeedCommentEmpty />;

  return (
    <ul>
      {items.map((comment) => (
        <FeedCommentItem key={comment.id} comment={comment} />
      ))}
    </ul>
  );
};

export default FeedCommentList;
