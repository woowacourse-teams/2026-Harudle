import { css } from '@emotion/react';
import { theme } from '../styles/theme';
import type { ReactNode } from 'react';

type ActionButtonVariant = 'primary' | 'secondary';

const ActionButton = ({
  onClick,
  icon,
  label,
  variant = 'primary',
  disabled = false,
}: {
  onClick: () => void;
  icon?: ReactNode;
  label: string;
  variant?: ActionButtonVariant;
  disabled?: boolean;
}) => {
  return (
    <button
      type="button"
      css={actionButtonStyle(variant)}
      onClick={onClick}
      disabled={disabled}
    >
      <span css={iconStyle} aria-hidden="true">
        {icon}
      </span>
      {label}
    </button>
  );
};

export default ActionButton;

const actionButtonStyle = (variant: ActionButtonVariant) => css`
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 8px;
  width: 100%;
  height: 56px;
  padding: 16px 20px;
  border: ${variant === 'primary' ? 'none' : `1px solid ${theme.colors.border}`};
  border-radius: 24px;
  background-color: ${variant === 'primary' ? theme.colors.primary : theme.colors.background};
  color: ${variant === 'primary' ? 'white' : 'black'};
  font-size: 16px;
  font-weight: 500;
  line-height: 24px;
  cursor: pointer;

  &:disabled {
    opacity: 0.5;
    cursor: default;
  }
`;

const iconStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  flex-shrink: 0;

  & > img,
  & > svg {
    display: block;
    width: 100%;
    height: 100%;
  }
`;
