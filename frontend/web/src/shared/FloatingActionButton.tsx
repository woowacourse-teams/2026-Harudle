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
      <img src={plusIcon} alt="" css={plusIconStyle} />
    </button>
  );
};

export default FloatingActionButton;

const buttonStyle = (disabled: boolean) => css`
  position: absolute;
  right: 20px;
  bottom: 96px;
  z-index: 10;

  display: flex;
  align-items: center;
  justify-content: center;

  width: 52px;
  height: 52px;
  padding: 0;
  border: none;
  border-radius: 50%;

  background-color: ${theme.colors.primary};
  box-shadow: 0 8px 24px rgba(51, 36, 89, 0.16);
  opacity: ${disabled ? 0.4 : 1};
  cursor: ${disabled ? 'default' : 'pointer'};

  &:active {
    transform: ${disabled ? 'none' : 'scale(0.96)'};
  }
`;

const plusIconStyle = css`
  display: block;
  width: 24px;
  height: 24px;
`;
