import { css } from '@emotion/react';
import { useRef } from 'react';
import type { Category } from '../../domain/category/categoryList';
import { isFeedSort, type FeedSort } from '../../domain/feeds/feedList';
import BottomNavigationLayout from '../../shared/BottomNavigationLayout';
import FeedFilterBar from './FeedFilterBar';
import FeedItemList from './FeedItemList';
import { useNavigate, useSearchParams } from 'react-router';
import { useAnalytics } from '../../posthog/useAnalytics';
import useFeedList from './useFeedList';
import plusIcon from '../../assets/icons/plus.svg';
import { theme } from '../../styles/theme';
import { FEED_COPY } from './copy';
import useDiaryGenerationId from '../../shared/useDiaryGenerationId';
import type { BottomSheetHandle } from '../../shared/BottomSheet';
import NeedLoginBottomSheet from '../../shared/NeedLoginBottomSheet';
import FeedNotifications from './FeedNotifications';

const FEED_QUERY_KEYS = {
  CATEGORY_ID: 'categoryId',
  SORT: 'sort',
} as const;

const FeedPage = () => {
  const navigate = useNavigate();
  const loginSheetRef = useRef<BottomSheetHandle>(null);
  const generationId = useDiaryGenerationId();
  const hasCompletedOAuth =
    localStorage.getItem('harudle.has-completed-oauth') !== null;
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

  const handleCreate = (): void => {
    if (!hasCompletedOAuth) {
      loginSheetRef.current?.open();
      return;
    }

    void navigate('/diary-write');
  };

  return (
    <BottomNavigationLayout
      headerLeft={
        <button
          type="button"
          aria-label={FEED_COPY.createAction}
          disabled={generationId !== null}
          css={createButtonStyle}
          onClick={handleCreate}
        >
          <span aria-hidden="true" css={createIconBackgroundStyle}>
            <span css={createIconStyle} />
          </span>
        </button>
      }
      headerRight={hasCompletedOAuth && <FeedNotifications />}
    >
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
      <NeedLoginBottomSheet
        description={FEED_COPY.createLoginDescription}
        ref={loginSheetRef}
      />
    </BottomNavigationLayout>
  );
};

export default FeedPage;

const createButtonStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: none;
  cursor: pointer;
  transform: translateY(3px);

  &:active:not(:disabled) > span {
    background-color: color-mix(
      in srgb,
      ${theme.colors.background.brandWeak} 88%,
      ${theme.colors.background.brandSolid}
    );
  }

  &:focus-visible {
    outline: 2px solid ${theme.colors.stroke.focusRing};
    outline-offset: 2px;
  }

  &:disabled {
    opacity: 0.65;
    cursor: not-allowed;
  }
`;

const createIconBackgroundStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background-color: ${theme.colors.background.brandWeak};
`;

const createIconStyle = css`
  width: 26px;
  height: 26px;
  background-color: ${theme.colors.foreground.brand};
  mask: url(${plusIcon}) center / contain no-repeat;
`;

const feedPageContentStyle = css`
  position: relative;
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0px;
  padding: 4px 20px 0 20px;
  font-size: 12px;
  line-height: 1.5;
`;
