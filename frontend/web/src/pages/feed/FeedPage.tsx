import { css } from '@emotion/react';
import type { Category } from '../../domain/category/categoryList';
import { isFeedSort, type FeedSort } from '../../domain/feeds/feedList';
import BottomNavigationLayout from '../../shared/BottomNavigationLayout';
import FeedFilterBar from './FeedFilterBar';
import FeedItemList from './FeedItemList';
import { useSearchParams } from 'react-router';
import { useAnalytics } from '../../posthog/useAnalytics';
import useFeedList from './useFeedList';

const FEED_QUERY_KEYS = {
  CATEGORY_ID: 'categoryId',
  SORT: 'sort',
} as const;

const FeedPage = () => {
  const { track } = useAnalytics();
  const [searchParams, setSearchParams] = useSearchParams();

  const rawCategoryId = searchParams.get(FEED_QUERY_KEYS.CATEGORY_ID);
  const categoryId = rawCategoryId === null ? undefined : Number(rawCategoryId);
  const rawSort = searchParams.get(FEED_QUERY_KEYS.SORT);
  const sort = isFeedSort(rawSort) ? rawSort : 'LATEST';
  const {
    request,
    loadMoreRequest,
    execute,
    loadMore,
    updateLike,
    removeFeedFromList,
  } = useFeedList({ categoryId, sort });

  const handleCategoryChange = (
    nextCategoryId: Category['id'] | undefined,
    categoryName: string,
  ): void => {
    if (nextCategoryId === categoryId) return;

    track('feed_category_changed', {
      feature: 'feed',
      operation: 'feed_category_change',
      previous_category_id: categoryId ?? null,
      category_id: nextCategoryId ?? null,
      category_name: categoryName,
    });
    setSearchParams((previous): URLSearchParams => {
      const next = new URLSearchParams(previous);
      if (nextCategoryId === undefined) {
        next.delete(FEED_QUERY_KEYS.CATEGORY_ID);
      } else {
        next.set(FEED_QUERY_KEYS.CATEGORY_ID, String(nextCategoryId));
      }
      return next;
    });
  };

  const handleSortChange = (nextSort: FeedSort): void => {
    setSearchParams((previous): URLSearchParams => {
      const next = new URLSearchParams(previous);
      if (nextSort === 'LATEST') {
        next.delete(FEED_QUERY_KEYS.SORT);
      } else {
        next.set(FEED_QUERY_KEYS.SORT, nextSort);
      }
      return next;
    });
  };

  return (
    <BottomNavigationLayout>
      <main css={feedPageContentStyle}>
        <FeedFilterBar
          selectedCategoryId={categoryId}
          onCategoryChange={handleCategoryChange}
          sort={sort}
          onSortChange={handleSortChange}
          onRefresh={execute}
          isRefreshing={request.status === 'loading'}
        />
        <FeedItemList
          categoryId={categoryId}
          sort={sort}
          onCategoryClick={handleCategoryChange}
          request={request}
          loadMoreRequest={loadMoreRequest}
          loadMore={loadMore}
          updateLike={updateLike}
          removeFeedFromList={removeFeedFromList}
        />
      </main>
    </BottomNavigationLayout>
  );
};

export default FeedPage;

const feedPageContentStyle = css`
  position: relative;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0px;
  padding: 4px 20px 0 20px;
  font-size: 12px;
  line-height: 1.5;
`;
