import { css } from '@emotion/react';
import autorenewIcon from '../../assets/icons/autorenew.svg';
import type { Category } from '../../domain/category/categoryList';
import { theme } from '../../styles/theme';
import { FEED_COPY } from './copy';
import FeedCategoryItem from './FeedCategoryItem';
import FeedCategoryListSkeleton from './FeedCategoryListSkeleton';
import useCategoryList from './useCategoryList';

export interface FeedCategoryListProps {
  readonly selectedCategoryId: Category['id'] | undefined;
  readonly onCategoryChange: (categoryId: Category['id'] | undefined) => void;
}

const FeedCategoryList = ({
  selectedCategoryId,
  onCategoryChange,
}: FeedCategoryListProps) => {
  const { request, refetch } = useCategoryList();

  if (request.status === 'idle' || request.status === 'loading') {
    return (
      <div css={feedCategoryListStyle}>
        <FeedCategoryListSkeleton />
      </div>
    );
  }

  if (request.status === 'error') {
    return (
      <div css={feedCategoryListStyle}>
        <p>{FEED_COPY.categoryLoadError}</p>
        <button
          type="button"
          css={categoryRetryButtonStyle}
          onClick={refetch}
          aria-label={FEED_COPY.retry}
        >
          <img src={autorenewIcon} alt="" />
        </button>
      </div>
    );
  }

  const categories = request.data;

  return (
    <div css={feedCategoryListStyle}>
      <div css={categoryOptionsStyle}>
        <FeedCategoryItem
          label={FEED_COPY.allCategories}
          isSelected={selectedCategoryId === undefined}
          onSelect={() => onCategoryChange(undefined)}
        />
        {categories.map((category) => (
          <FeedCategoryItem
            key={category.id}
            label={category.name}
            isSelected={category.id === selectedCategoryId}
            onSelect={() => onCategoryChange(category.id)}
          />
        ))}
      </div>
    </div>
  );
};

export default FeedCategoryList;

const feedCategoryListStyle = css`
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  height: 48px;
  color: ${theme.colors.foreground.neutralMuted};
`;

const categoryOptionsStyle = css`
  display: flex;
  align-items: center;
  gap: 24px;
  height: 48px;
  overflow-x: auto;
  scrollbar-width: none;

  &::-webkit-scrollbar {
    display: none;
  }
`;

const categoryRetryButtonStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: none;
  cursor: pointer;

  img {
    display: block;
  }

  &:hover,
  &:active {
    background-color: ${theme.colors.background.brandWeak};
  }
`;
