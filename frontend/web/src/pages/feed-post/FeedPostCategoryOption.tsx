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
  <label css={categoryChipStyle}>
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

const categoryChipStyle = css`
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 48px;
  padding: 0 8px;
  border: 1px solid ${theme.colors.stroke.outline};
  border-radius: 12px;
  background: ${theme.colors.background.surface};
  font-size: 15px;
  line-height: 22px;
  letter-spacing: -0.35px;
  text-align: center;
  word-break: keep-all;
  cursor: pointer;
  transition:
    background-color 150ms ease-out,
    border-color 150ms ease-out,
    color 150ms ease-out;

  @media (prefers-reduced-motion: reduce) {
    transition: none;
  }

  &:has(input:checked) {
    border-color: ${theme.colors.stroke.brandSolid};
    background: ${theme.colors.background.brandWeak};
    color: ${theme.colors.foreground.brand};
    font-weight: 600;
  }

  &:has(input:focus-visible) {
    outline: 3px solid ${theme.colors.stroke.focusRing};
    outline-offset: 2px;
  }
`;

// display: none이면 키보드 포커스와 radio 역할이 사라지므로, 투명한 네이티브 radio가 칩 전체를 덮어 탭과 키보드 입력을 받는다.
const radioStyle = css`
  position: absolute;
  inset: 0;
  margin: 0;
  opacity: 0;
  cursor: inherit;
`;
