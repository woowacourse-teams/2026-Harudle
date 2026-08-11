import { css } from '@emotion/react';
import { theme } from '../styles/theme';

const ActionButton = ({ onClick }: { onClick: () => void }) => {
  return (
    <button css={actionButtonStyle} onClick={onClick}>
      새 일기 쓰기
    </button>
  );
};

export default ActionButton;

const actionButtonStyle = css`
  display: flex;
  justify-content: center;
  align-items: center;
  width: 100%;
  height: 20px;
  padding: 20px 16px;
  border: none;
  border-radius: 24px;
  background-color: ${theme.colors.primary};
  color: white;
`;
