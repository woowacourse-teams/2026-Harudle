import { css } from '@emotion/react';
import { theme } from '../styles/theme';
import plusIcon from '../assets/icons/plus.svg';

const FloatingActionButton = ({
  onClick,
  disabled,
}: {
  onClick: () => void;
  disabled: boolean;
}) => {
  return (
    <button css={buttonStyle(disabled)} onClick={onClick} disabled={disabled}>
      <img src={plusIcon} css={iconStyle} />
    </button>
  );
};

export default FloatingActionButton;

const buttonStyle = (disabled: boolean) => css`
  position: absolute;
  right: 20px;
  bottom: 20px;
  z-index: 10;

  width: 56px;
  height: 56px;
  border: none;
  border-radius: 50%;

  background-color: ${theme.colors.bg.brand};
  opacity: ${disabled ? 0.4 : 1};
  cursor: pointer;

  &:active {
    transform: scale(0.98);
  }
`;

const iconStyle = css`
  width: 24px;
  height: 24px;
`;
