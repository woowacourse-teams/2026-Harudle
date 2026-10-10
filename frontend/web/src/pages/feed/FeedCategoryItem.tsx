import { css, type SerializedStyles } from '@emotion/react';
import { theme } from '../../styles/theme';

interface FeedCategoryItemProps {
  readonly label: string;
  readonly isSelected: boolean;
  readonly onSelect: () => void;
}

const FeedCategoryItem = ({
  label,
  isSelected,
  onSelect,
}: FeedCategoryItemProps) => {
  return (
    <button
      type="button"
      css={feedCategoryItemStyle(isSelected, label.length)}
      onClick={onSelect}
    >
      {label}
    </button>
  );
};

export default FeedCategoryItem;

const feedCategoryItemStyle = (
  isSelected: boolean,
  labelLength: number,
): SerializedStyles => css`
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  min-width: ${labelLength > 2 ? 49 : 34}px;
  height: 48px;
  padding: 0;
  border: none;
  background: none;
  color: ${
    isSelected
      ? theme.colors.foreground.brand
      : theme.colors.foreground.neutralMuted
  };
  font-family: inherit;
  font-size: 16px;
  font-weight: ${isSelected ? 500 : 400};
  white-space: nowrap;
  cursor: pointer;

  &::after {
    content: '';
    position: absolute;
    right: 0;
    bottom: 0;
    left: 0;
    height: 2px;
    background-color: ${
      isSelected ? theme.colors.background.brandSolid : 'transparent'
    };
  }
`;
