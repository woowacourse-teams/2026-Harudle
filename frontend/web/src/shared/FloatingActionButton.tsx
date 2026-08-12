import { css } from '@emotion/react';
import { theme } from '../styles/theme';

const FloatingActionButton = ({
  onClick,
  disabled,
}: {
  onClick: () => void;
  disabled: boolean;
}) => {
  return (
    <button css={buttonStyle(disabled)} onClick={onClick} disabled={disabled}>
      +
    </button>
  );
};

export default FloatingActionButton;

const buttonStyle = (disabled: boolean) => css`
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 50%;
  background-color: ${theme.colors.primary};
  opacity: ${disabled ? 0.4 : 1};
`;
