import FeedCommentItem from './FeedCommentItem';
import FeedCommentEmpty from './FeedCommentEmpty';
import FeedCommentError from './FeedCommentError';
import LoadingSpinner from '../../shared/LoadingSpinner';
import type { ApiRequest } from '../../shared/api';
import type {
  FeedComment,
  FeedCommentsResponse,
} from '../../domain/feeds/feedComments';

interface FeedCommentListProps {
  readonly request: ApiRequest<FeedCommentsResponse>;
  readonly onRetry: () => void;
  readonly onDelete: (commentId: FeedComment['id']) => void;
}

const FeedCommentList = ({
  request,
  onRetry,
  onDelete,
}: FeedCommentListProps) => {
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
        <FeedCommentItem
          key={comment.id}
          comment={comment}
          onDelete={onDelete}
        />
      ))}
    </ul>
  );
};

export default FeedCommentList;
