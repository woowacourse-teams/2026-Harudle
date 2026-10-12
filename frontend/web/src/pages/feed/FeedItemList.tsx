import { css } from '@emotion/react';
import { useEffect } from 'react';
import type { FeedListRequest } from '../../domain/feeds/feedList';
import LoadingSpinner from '../../shared/LoadingSpinner';
import FeedEmpty from './FeedEmpty';
import FeedError from './FeedError';
import useFeedList from './useFeedList';
import FeedItem from './FeedItem';
import ActionButton from '../../shared/ActionButton';
import { FEED_COPY } from './copy';

const FeedItemList = ({
  categoryId,
  sort,
}: Pick<FeedListRequest, 'categoryId' | 'sort'>) => {
  const { request, loadMoreRequest, loadMore, updateLike, removeFeedFromList } =
    useFeedList({ categoryId, sort });
  const isLoadingMore = loadMoreRequest.status === 'loading';

  useEffect((): void => {
    if (loadMoreRequest.status === 'error') {
      window.alert(FEED_COPY.loadMoreError);
    }
  }, [loadMoreRequest]);

  if (request.status === 'idle' || request.status === 'loading') {
    return <LoadingSpinner />;
  }

  if (request.status === 'error') {
    return <FeedError errorMessage={request.error.message} />;
  }

  const { items, hasNext } = request.data;
  if (items.length === 0 && !hasNext) return <FeedEmpty />;

  return (
    <div css={feedItemListStyle}>
      {items.map((item) => (
        <FeedItem
          key={item.id}
          feed={item}
          onLikeChange={updateLike}
          onDelete={removeFeedFromList}
        />
      ))}
      {hasNext && (
        <div css={loadMoreStyle}>
          <ActionButton
            variant="secondary"
            label={
              isLoadingMore
                ? FEED_COPY.loadingMoreAction
                : FEED_COPY.loadMoreAction
            }
            disabled={isLoadingMore}
            onClick={() => {
              void loadMore();
            }}
          />
        </div>
      )}
    </div>
  );
};

export default FeedItemList;

const feedItemListStyle = css`
  display: flex;
  flex-direction: column;
  overflow-y: auto;
`;

const loadMoreStyle = css`
  flex-shrink: 0;
  padding: 16px 0 24px;
`;
