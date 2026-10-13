import { useParams } from 'react-router';
import { FEED_DETAIL_COPY } from './copy';
import FeedDetailContent from './FeedDetailContent';
import FeedDetailError from './FeedDetailError';

const FeedDetailPage = () => {
  const { feedId } = useParams();

  if (!feedId) {
    return <FeedDetailError errorMessage={FEED_DETAIL_COPY.notFound} />;
  }

  return <FeedDetailContent feedId={feedId} />;
};

export default FeedDetailPage;
