import { css } from '@emotion/react';
import type { Notification } from '../../domain/notifications/notifications';
import profileFallbackImage from '../../assets/images/profile-fallback.svg';
import { formatPublishedAt } from '../../shared/feed/formatPublishedAt';
import { theme } from '../../styles/theme';

interface NotificationItemProps {
  readonly notification: Notification;
  readonly onSelect: (feedId: string) => void;
}

const NotificationItem = ({
  notification,
  onSelect,
}: NotificationItemProps) => (
  <li css={rowStyle}>
    <button
      type="button"
      css={buttonStyle}
      onClick={(): void => onSelect(notification.feedId)}
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
  padding: 10px 0;
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
