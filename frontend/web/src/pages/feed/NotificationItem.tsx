import { css } from '@emotion/react';
import type { Notification } from '../../domain/notifications/notifications';
import profileFallbackImage from '../../assets/images/profile-fallback.svg';
import { formatPublishedAt } from '../../shared/feed/formatPublishedAt';
import { theme } from '../../styles/theme';
import { FEED_COPY } from './copy';

interface NotificationItemProps {
  readonly notification: Notification;
  readonly onSelect: (notification: Notification) => void;
  readonly disabled: boolean;
}

const NotificationItem = ({
  notification,
  onSelect,
  disabled,
}: NotificationItemProps) => (
  <li css={rowStyle}>
    <button
      type="button"
      css={[buttonStyle, !notification.read && unreadStyle]}
      onClick={(): void => onSelect(notification)}
      disabled={disabled}
    >
      <img
        src={notification.actor?.profileImageUrl || profileFallbackImage}
        alt=""
        css={avatarStyle}
        onError={(event): void => {
          if (event.currentTarget.getAttribute('src') !== profileFallbackImage)
            event.currentTarget.src = profileFallbackImage;
        }}
      />
      <span css={contentStyle}>
        <span css={messageStyle}>{notification.message}</span>
        <time dateTime={notification.createdAt} css={timeStyle}>
          {formatPublishedAt(notification.createdAt)}
        </time>
      </span>
      {!notification.read && (
        <span
          css={unreadIndicatorStyle}
          role="img"
          aria-label={FEED_COPY.unreadNotification}
        />
      )}
    </button>
  </li>
);

export default NotificationItem;

const rowStyle = css`
  border-bottom: 1px solid ${theme.colors.stroke.divider};
`;

const buttonStyle = css`
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 60px;
  padding: 10px 8px;
  border: 0;
  background: transparent;
  text-align: left;
  color: inherit;
  font: inherit;
  cursor: pointer;
  &:active {
    background: ${theme.colors.background.brandWeak};
  }
  &:focus-visible {
    outline: 2px solid ${theme.colors.stroke.focusRing};
    outline-offset: -2px;
  }
  &:disabled {
    cursor: default;
  }
`;

const unreadStyle = css`
  background: ${theme.colors.background.brandWeak};
`;

const unreadIndicatorStyle = css`
  flex-shrink: 0;
  width: 6px;
  height: 6px;
  margin-left: auto;
  border-radius: 50%;
  background: ${theme.colors.background.brandSolid};
`;

const avatarStyle = css`
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  object-fit: cover;
  background: ${theme.colors.stroke.brandWeak};
`;

const contentStyle = css`
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow-wrap: anywhere;
`;

const messageStyle = css`
  font-size: 13px;
  line-height: 20px;
`;

const timeStyle = css`
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 11px;
  line-height: 16px;
`;
