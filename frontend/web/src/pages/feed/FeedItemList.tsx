import { css } from '@emotion/react';
import type { FeedListRequest } from '../../domain/feeds/feedList';
import LoadingSpinner from '../../shared/LoadingSpinner';
import FeedEmpty from './FeedEmpty';
import FeedError from './FeedError';
import useFeedList from './useFeedList';
import FeedItem from './FeedItem';

const FeedItemList = ({
  categoryId,
  sort,
}: Pick<FeedListRequest, 'categoryId' | 'sort'>) => {
  const request = useFeedList({ categoryId, sort });

  if (request.status === 'idle' || request.status === 'loading') {
    return <LoadingSpinner />;
  }

  if (request.status === 'error') {
    return <FeedError errorMessage={request.error.message} />;
  }

  const { items } = request.data;
  if (items.length === 0) return <FeedEmpty />;

  return (
    <div css={feedItemListStyle}>
      {items.map((item) => (
        <FeedItem key={item.id} feed={item} />
      ))}
    </div>
  );
};

export default FeedItemList;

const feedItemListStyle = css`
  display: flex;
  flex-direction: column;
  overflow-y: auto;
`;
