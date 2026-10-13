import { css } from '@emotion/react';
import type { Category } from '../../domain/category/categoryList';
import { theme } from '../../styles/theme';

interface FeedPostCategoryOptionProps {
  readonly category: Category;
  readonly checked: boolean;
  readonly onSelect: (categoryId: Category['id']) => void;
}

const FeedPostCategoryOption = ({
  category,
  checked,
  onSelect,
}: FeedPostCategoryOptionProps) => (
  <label css={categoryCardStyle}>
    <input
      type="radio"
      name="category"
      value={category.id}
      css={radioStyle}
      checked={checked}
      onChange={() => {
        onSelect(category.id);
      }}
    />
    <span>{category.name}</span>
  </label>
);

export default FeedPostCategoryOption;

const categoryCardStyle = css`
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 56px;
  padding: 0 16px;
  border: 1px solid ${theme.colors.stroke.outline};
  border-radius: 12px;
  background: ${theme.colors.background.surface};
  font-size: 15px;
  line-height: 22px;
  letter-spacing: -0.35px;
  cursor: pointer;
  transition:
    background-color 150ms ease-out,
    border-color 150ms ease-out;

  @media (prefers-reduced-motion: reduce) {
    transition: none;
  }

  &:has(input:checked) {
    border-color: ${theme.colors.stroke.brandSolid};
    background: ${theme.colors.background.brandWeak};
  }
`;

const radioStyle = css`
  appearance: none;
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  border: 2px solid ${theme.colors.foreground.placeholder};
  border-radius: 50%;
  background: transparent;
  cursor: inherit;
  transition: border-color 150ms ease-out;

  @media (prefers-reduced-motion: reduce) {
    transition: none;
  }

  &:checked {
    border-color: ${theme.colors.stroke.brandSolid};
    background: radial-gradient(
      circle,
      ${theme.colors.background.brandSolid} 4px,
      transparent 4px
    );
  }
`;
