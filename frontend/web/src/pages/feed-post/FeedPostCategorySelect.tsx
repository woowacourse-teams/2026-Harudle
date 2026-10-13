import { css } from '@emotion/react';
import type { Category } from '../../domain/category/categoryList';
import LoadingSpinner from '../../shared/LoadingSpinner';
import CategoryError from './CategoryError';
import CategoryEmpty from './CategoryEmpty';
import useCategoryList from '../feed/useCategoryList';
import { FEED_POST_COPY } from './copy';
import FeedPostCategoryOption from './FeedPostCategoryOption';

interface FeedPostCategorySelectProps {
  readonly selectedCategoryId: Category['id'] | null;
  readonly onSelect: (categoryId: Category['id']) => void;
  readonly disabled: boolean;
}

const FeedPostCategorySelect = ({
  selectedCategoryId,
  onSelect,
  disabled,
}: FeedPostCategorySelectProps) => {
  const { request, refetch } = useCategoryList();
  const categories =
    request.status === 'success'
      ? request.data.filter((category): boolean => category.active)
      : [];

  if (request.status === 'idle' || request.status === 'loading') {
    return <LoadingSpinner />;
  }
  if (request.status === 'error') {
    return <CategoryError onRetry={refetch} />;
  }
  if (categories.length === 0) {
    return <CategoryEmpty />;
  }

  return (
    <fieldset css={categoriesStyle} disabled={disabled}>
      <legend css={categoryQuestionStyle}>
        {FEED_POST_COPY.categoryQuestion}
      </legend>
      {categories.map((category) => (
        <FeedPostCategoryOption
          key={category.id}
          category={category}
          checked={category.id === selectedCategoryId}
          onSelect={onSelect}
        />
      ))}
    </fieldset>
  );
};

export default FeedPostCategorySelect;

const categoriesStyle = css`
  display: grid;
  gap: 12px;
  min-width: 0;
`;

const categoryQuestionStyle = css`
  padding-bottom: 20px;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
`;
