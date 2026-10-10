import { css } from '@emotion/react';
import type { Category } from '../../domain/category/categoryList';
import BottomNavigationLayout from '../../shared/BottomNavigationLayout';
import FeedFilterBar from './FeedFilterBar';
import { useSearchParams } from 'react-router';

const FEED_QUERY_KEYS = {
  CATEGORY_ID: 'categoryId',
} as const;

const FeedPage = () => {
  const [searchParams, setSearchParams] = useSearchParams();

  const categoryId = searchParams.get(FEED_QUERY_KEYS.CATEGORY_ID) ?? undefined;

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

  return (
    <BottomNavigationLayout>
      <main css={feedPageContentStyle}>
        <FeedFilterBar
          selectedCategoryId={categoryId}
          onCategoryChange={handleCategoryChange}
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
  overflow-y: auto;
  font-size: 12px;
  line-height: 1.5;
`;
