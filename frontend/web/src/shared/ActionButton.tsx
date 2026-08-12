import { css } from '@emotion/react';
import { theme } from '../styles/theme';

const ActionButton = ({
  onClick,
  label,
}: {
  onClick: () => void;
  label: string;
}) => {
  return (
    <button css={actionButtonStyle} onClick={onClick}>
      {label}
    </button>
  );
};

export default ActionButton;

const actionButtonStyle = css`
  display: flex;
  justify-content: center;
  align-items: center;
  width: 100%;
  height: 56px;
  padding: 16px 20px;
  border: none;
  border-radius: 24px;
  background-color: ${theme.colors.primary};
  color: white;
  font-size: 16px;
  font-weight: 500;
  line-height: 24px;
  cursor: pointer;
`;
