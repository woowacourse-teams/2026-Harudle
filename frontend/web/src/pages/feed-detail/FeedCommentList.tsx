import FeedCommentItem from './FeedCommentItem';
import FeedCommentEmpty from './FeedCommentEmpty';
import FeedCommentError from './FeedCommentError';
import LoadingSpinner from '../../shared/LoadingSpinner';
import useFeedComments from './useFeedComments';

interface FeedCommentListProps {
  readonly feedId: string;
}

const FeedCommentList = ({ feedId }: FeedCommentListProps) => {
  const { request, execute } = useFeedComments({ feedId });

  if (request.status === 'idle' || request.status === 'loading') {
    return <LoadingSpinner />;
  }

  if (request.status === 'error') {
    return (
      <FeedCommentError
        onRetry={() => {
          void execute();
        }}
      />
    );
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
