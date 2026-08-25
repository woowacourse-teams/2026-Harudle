import { css } from '@emotion/react';
import { theme } from '../styles/theme';

import type { ReactNode } from 'react';

const FloatingActionButton = ({
  onClick,
  icon,
  disabled,
}: {
  onClick: () => void;
  icon: ReactNode;
  disabled: boolean;
}) => {
  return (
    <button css={buttonStyle} onClick={onClick} disabled={disabled}>
      {icon}
    </button>
  );
};

export default FloatingActionButton;

const buttonStyle = () => css`
  position: absolute;
  right: 20px;
  bottom: 20px;
  z-index: 10;

  width: 56px;
  height: 56px;
  border: none;
  border-radius: 50%;

  background-color: ${theme.colors.bg.brand};
  cursor: pointer;

  &:active {
    transform: scale(0.98);
  }

  &:disabled {
    opacity: 0.65;
    cursor: not-allowed;
  }
`;
