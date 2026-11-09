import { css } from '@emotion/react';
import type { NativePushControls } from '../../domain/push/NativePushRegistration';
import { isNativeApp } from '../../shared/bridge';
import { theme } from '../../styles/theme';

const NotificationToggle = ({
  push,
}: {
  readonly push?: NativePushControls | null;
}) => {
  if (!isNativeApp() || !push) return null;

  return (
    <div css={rowStyle}>
      <div>
        <span id="push-notification-label">푸시 알림</span>
        <p css={descriptionStyle}>이 기기에서 새 네컷만화 알림을 받아요.</p>
      </div>
      <button
        type="button"
        role="switch"
        aria-labelledby="push-notification-label"
        aria-checked={push.enabled}
        aria-busy={push.pending}
        disabled={push.pending}
        css={switchStyle}
        onClick={() => push.changeEnabled(!push.enabled)}
      />
    </div>
  );
};

export default NotificationToggle;

const rowStyle = css`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px;
  border: 1px solid ${theme.colors.stroke.outline};
  border-radius: 16px;
  color: ${theme.colors.foreground.neutral};
  font-size: 15px;
  line-height: 24px;
`;

const descriptionStyle = css`
  margin-top: 4px;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 12px;
  line-height: 18px;
`;

const switchStyle = css`
  position: relative;
  flex-shrink: 0;
  width: 44px;
  height: 28px;
  padding: 0;
  border: 0;
  border-radius: 14px;
  background: ${theme.colors.background.neutralWeak};
  cursor: pointer;

  &::after {
    content: '';
    position: absolute;
    top: 4px;
    left: 4px;
    width: 20px;
    height: 20px;
    border-radius: 50%;
    background: ${theme.colors.background.surface};
  }

  &[aria-checked='true'] {
    background: ${theme.colors.background.brandSolid};
  }

  &[aria-checked='true']::after {
    left: 20px;
  }

  &:disabled {
    opacity: 0.5;
    cursor: default;
  }

  &:focus-visible {
    outline: 3px solid ${theme.colors.stroke.focusRing};
    outline-offset: 2px;
  }
`;
