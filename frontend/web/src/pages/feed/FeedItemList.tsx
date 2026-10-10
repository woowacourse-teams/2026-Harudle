import { css } from '@emotion/react';
import type { FeedListRequest } from '../../domain/feeds/feedList';
import LoadingSpinner from '../../shared/LoadingSpinner';
import FeedEmpty from './FeedEmpty';
import FeedError from './FeedError';
import useFeedList from './useFeedList';
import { theme } from '../../styles/theme';
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
      <span>FeedItemList</span>
      {items.map((item) => (
        <FeedItem key={item.id} />
      ))}
    </div>
  );
};

export default FeedItemList;

const feedItemListStyle = css`
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px;
  border: 2px solid ${theme.colors.stroke.brandSolid};
`;
