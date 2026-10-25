import { css, keyframes } from '@emotion/react';
import successIcon from '../assets/icons/toast-success.svg';
import errorIcon from '../assets/icons/toast-error.svg';
import loadingIcon from '../assets/icons/toast-loading.svg';
import { theme } from '../styles/theme';

export type ToastType = 'success' | 'error' | 'loading';

interface ToastAction {
  readonly label: string;
  readonly onClick: () => void;
}

export interface ToastProps {
  readonly type: ToastType;
  readonly message: string;
  readonly description?: string;
  readonly action?: ToastAction;
  readonly onClose?: () => void;
}

const Toast = ({ type, message, description, action, onClose }: ToastProps) => {
  return (
    <div css={toastStyle}>
      <span css={iconStyle} data-type={type}>
        <span css={iconImageStyles[type]} />
      </span>

      <div css={contentStyle}>
        <p>{message}</p>
        {description && <p css={descriptionStyle}>{description}</p>}
      </div>

      {action && (
        <button type="button" css={actionButtonStyle} onClick={action.onClick}>
          {action.label}
        </button>
      )}

      {onClose && (
        <button type="button" css={closeButtonStyle} onClick={onClose}>
          ×
        </button>
      )}
    </div>
  );
};

export default Toast;

const toastStyle = css`
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  max-width: 390px;
  min-height: 64px;
  padding: 12px 8px 12px 16px;
  border: 1px solid ${theme.colors.stroke.outline};
  border-radius: 16px;
  background-color: ${theme.colors.background.surface};
  color: ${theme.colors.foreground.neutral};
  box-shadow: 0 6px 20px rgba(26, 20, 41, 0.1);
`;

const spin = keyframes`
  to {
    transform: rotate(360deg);
  }
`;

const iconStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  border-radius: 12px;
  background-color: ${theme.colors.background.brandWeak};
  color: ${theme.colors.foreground.brand};

  & > span {
    width: 20px;
    height: 20px;
    background-color: currentColor;
  }

  &[data-type='success'] {
    background-color: ${theme.colors.background.positiveWeak};
    color: ${theme.colors.foreground.positive};
  }

  &[data-type='error'] {
    background-color: ${theme.colors.background.criticalWeak};
    color: ${theme.colors.foreground.critical};
  }

  &[data-type='loading'] span {
    animation: ${spin} 1s linear infinite;
  }
`;

const iconImageStyles = {
  success: css`
    mask: url(${successIcon}) center no-repeat;
  `,
  error: css`
    mask: url(${errorIcon}) center no-repeat;
  `,
  loading: css`
    mask: url(${loadingIcon}) center no-repeat;
  `,
};

const contentStyle = css`
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
  font-size: 15px;
  font-weight: 500;
  line-height: 24px;
`;

const descriptionStyle = css`
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 12px;
  font-weight: 400;
  line-height: 18px;
`;

const actionButtonStyle = css`
  flex-shrink: 0;
  min-height: 44px;
  padding: 8px 12px;
  border: none;
  border-radius: 12px;
  background-color: ${theme.colors.background.brandWeak};
  color: ${theme.colors.foreground.brand};
  font-size: 12px;
  font-weight: 500;
  line-height: 18px;
  cursor: pointer;
`;

const closeButtonStyle = css`
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  padding: 0;
  border: none;
  background-color: transparent;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
`;
