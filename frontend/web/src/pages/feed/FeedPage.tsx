import { css } from '@emotion/react';
import type { Category } from '../../domain/category/categoryList';
import { isFeedSort, type FeedSort } from '../../domain/feeds/feedList';
import BottomNavigationLayout from '../../shared/BottomNavigationLayout';
import FeedFilterBar from './FeedFilterBar';
import FeedItemList from './FeedItemList';
import { useSearchParams } from 'react-router';
import useEntryStatus from '../../useEntryStatus';
import LoadingSpinner from '../../shared/LoadingSpinner';

const FEED_QUERY_KEYS = {
  CATEGORY_ID: 'categoryId',
  SORT: 'sort',
} as const;

const FeedPage = () => {
  const { status } = useEntryStatus();
  const [searchParams, setSearchParams] = useSearchParams();

  const categoryId = searchParams.get(FEED_QUERY_KEYS.CATEGORY_ID) ?? undefined;
  const rawSort = searchParams.get(FEED_QUERY_KEYS.SORT);
  const sort = isFeedSort(rawSort) ? rawSort : 'LATEST';

  const handleCategoryChange = (
    nextCategoryId: Category['id'] | undefined,
  ): void => {
    setSearchParams((previous): URLSearchParams => {
      const next = new URLSearchParams(previous);
      if (nextCategoryId === undefined) {
        next.delete(FEED_QUERY_KEYS.CATEGORY_ID);
      } else {
        next.set(FEED_QUERY_KEYS.CATEGORY_ID, nextCategoryId);
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

  if (status === 'restoringSession') return <LoadingSpinner />;

  return (
    <BottomNavigationLayout>
      <main css={feedPageContentStyle}>
        <FeedFilterBar
          selectedCategoryId={categoryId}
          onCategoryChange={handleCategoryChange}
          sort={sort}
          onSortChange={handleSortChange}
        />
        <FeedItemList categoryId={categoryId} sort={sort} />
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
