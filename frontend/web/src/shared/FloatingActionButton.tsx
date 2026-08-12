import { css } from '@emotion/react';
import { theme } from '../styles/theme';

const FloatingActionButton = ({ onClick }: { onClick: () => void }) => {
  return (
    <button css={buttonStyle} onClick={onClick}>
      +
    </button>
  );
};

export default FloatingActionButton;

const buttonStyle = css`
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 50%;
  background-color: ${theme.colors.primary};
`;
