import { useToastContext } from '../../shared/ToastContext';
import { css } from '@emotion/react';
import { useEffect } from 'react';
import type { Feed, FeedListRequest } from '../../domain/feeds/feedList';
import LoadingSpinner from '../../shared/LoadingSpinner';
import FeedEmpty from './FeedEmpty';
import FeedError from './FeedError';
import type { FeedListState } from './useFeedList';
import FeedItem from './FeedItem';
import ActionButton from '../../shared/ActionButton';
import { FEED_COPY } from './copy';
import useFeedScrollRestore from './useFeedScrollRestore';

interface FeedItemListProps
  extends
    Pick<FeedListRequest, 'categoryId' | 'sort'>,
    Pick<
      FeedListState,
      | 'request'
      | 'loadMoreRequest'
      | 'loadMore'
      | 'updateLike'
      | 'removeFeedFromList'
    > {
  readonly onCategoryClick: (
    categoryId: Feed['category']['id'],
    categoryName: string,
  ) => void;
}

const FeedItemList = ({
  categoryId,
  sort,
  onCategoryClick,
  request,
  loadMoreRequest,
  loadMore,
  updateLike,
  removeFeedFromList,
}: FeedItemListProps) => {
  const { addToast } = useToastContext();
  const { targetScrollRef, setFeedScroll } = useFeedScrollRestore({
    categoryId,
    sort,
    isListReady: request.status === 'success',
  });
  const isLoadingMore = loadMoreRequest.status === 'loading';

  useEffect(() => {
    if (loadMoreRequest.status === 'error') {
      addToast({ type: 'error', message: FEED_COPY.loadMoreError }, 2000);
    }
  }, [loadMoreRequest, addToast]);

  if (request.status === 'idle' || request.status === 'loading') {
    return <LoadingSpinner />;
  }

  if (request.status === 'error') {
    return <FeedError errorMessage={request.error.message} />;
  }

  const { items, hasNext } = request.data;
  if (items.length === 0 && !hasNext) return <FeedEmpty />;

  return (
    <div
      css={feedItemListStyle}
      ref={targetScrollRef}
      onScroll={(event) => {
        setFeedScroll(event.currentTarget.scrollTop);
      }}
    >
      <ul aria-label="피드">
        {items.map((item) => (
          <FeedItem
            key={item.id}
            feed={item}
            onCategoryClick={onCategoryClick}
            onLikeChange={updateLike}
            onDelete={removeFeedFromList}
          />
        ))}
      </ul>

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
