import { css } from '@emotion/react';
import { useRef } from 'react';
import { useNavigate } from 'react-router';
import type { Notification } from '../../domain/notifications/notifications';
import bellIcon from '../../assets/icons/notification-bell.svg';
import BottomSheet, { type BottomSheetHandle } from '../../shared/BottomSheet';
import { useToastContext } from '../../shared/ToastContext';
import { theme } from '../../styles/theme';
import NotificationList from './NotificationList';
import useNotifications from './useNotifications';
import { FEED_COPY } from './copy';

const FeedNotifications = () => {
  const sheetRef = useRef<BottomSheetHandle>(null);
  const navigate = useNavigate();
  const { addToast } = useToastContext();
  const {
    request,
    loadMoreRequest,
    execute,
    loadMore,
    markRead,
    markAllRead,
    isBusy,
    isReadingAll,
  } = useNotifications();
  const hasUnread =
    request.status === 'success' && request.data.unreadCount > 0;

  const refresh = async (): Promise<void> => {
    const data = await execute();
    if (data === null && request.status === 'success') {
      addToast(
        { type: 'error', message: FEED_COPY.notificationsLoadError },
        3000,
      );
      return;
    }
  };

  const handleOpen = (): void => {
    sheetRef.current?.open();
    void refresh();
  };

  const handleSelect = async (notification: Notification): Promise<void> => {
    try {
      await markRead([notification]);
    } catch {
      addToast(
        { type: 'error', message: FEED_COPY.notificationsReadError },
        3000,
      );
      return;
    }
    sheetRef.current?.close();
    void navigate(`/feeds/${encodeURIComponent(notification.feedId)}`);
  };

  const handleReadAll = async (): Promise<void> => {
    try {
      await markAllRead();
    } catch (error: unknown) {
      addToast(
        {
          type: 'error',
          message:
            error instanceof Error &&
            error.message === FEED_COPY.notificationsRefreshAfterReadError
              ? FEED_COPY.notificationsRefreshAfterReadError
              : FEED_COPY.notificationsReadError,
        },
        3000,
      );
    }
  };

  return (
    <>
      <button
        type="button"
        css={bellButtonStyle}
        onClick={handleOpen}
        aria-haspopup="dialog"
        disabled={isBusy}
        aria-label={
          hasUnread
            ? FEED_COPY.unreadNotificationsAction
            : FEED_COPY.notificationsAction
        }
      >
        <img src={bellIcon} alt="" />
        {hasUnread && <span css={badgeStyle} aria-hidden="true" />}
      </button>
      <BottomSheet ref={sheetRef} title={FEED_COPY.notificationsTitle}>
        {request.status === 'success' && (
          <div css={toolbarStyle}>
            <button
              type="button"
              css={readAllButtonStyle}
              disabled={isBusy || !hasUnread}
              onClick={(): void => {
                void handleReadAll();
              }}
            >
              {isReadingAll
                ? FEED_COPY.readingAllNotificationsAction
                : FEED_COPY.readAllNotificationsAction}
            </button>
          </div>
        )}
        <NotificationList
          request={request}
          loadMoreRequest={loadMoreRequest}
          isBusy={isBusy}
          onRetry={(): void => {
            void refresh();
          }}
          onLoadMore={(): void => {
            void loadMore();
          }}
          onSelect={(notification): void => {
            void handleSelect(notification);
          }}
        />
      </BottomSheet>
    </>
  );
};

export default FeedNotifications;

const toolbarStyle = css`
  display: flex;
  justify-content: flex-end;
`;

const readAllButtonStyle = css`
  min-height: 44px;
  padding: 8px;
  border: 0;
  background: transparent;
  color: ${theme.colors.foreground.brand};
  font-size: 13px;
  cursor: pointer;
  &:disabled {
    color: ${theme.colors.foreground.neutralMuted};
    cursor: default;
  }
  &:focus-visible {
    outline: 2px solid ${theme.colors.stroke.focusRing};
  }
`;

const bellButtonStyle = css`
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
  &:focus-visible {
    outline: 2px solid ${theme.colors.stroke.focusRing};
    border-radius: 50%;
  }
`;

const badgeStyle = css`
  position: absolute;
  top: 8px;
  right: 7px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: ${theme.colors.background.brandSolid};
`;
