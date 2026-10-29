import { css, type SerializedStyles } from '@emotion/react';
import { useLayoutEffect, useRef } from 'react';
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
  const buttonRef = useRef<HTMLButtonElement>(null);

  useLayoutEffect((): void => {
    if (isSelected) {
      buttonRef.current?.scrollIntoView({
        block: 'nearest',
        inline: 'nearest',
      });
    }
  }, [isSelected]);

  return (
    <li aria-live="polite">
      <button
        ref={buttonRef}
        type="button"
        css={feedCategoryItemStyle(isSelected, label.length)}
        onClick={onSelect}
      >
        {label}
      </button>
    </li>
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
  min-width: ${labelLength > 2 ? 42 : 28}px;
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
  font-size: 14px;
  font-weight: ${isSelected ? 500 : 400};
  white-space: nowrap;
  cursor: pointer;
  transition: color 180ms ease;

  &::after {
    content: '';
    position: absolute;
    right: 0;
    bottom: 0;
    left: 0;
    height: 2px;
    background-color: ${theme.colors.background.brandSolid};
    transform: scaleX(${isSelected ? 1 : 0});
    transition: transform 180ms ease;
  }

  @media (prefers-reduced-motion: reduce) {
    transition: none;

    &::after {
      transition: none;
    }
  }
`;
