import { css } from '@emotion/react';
import { useRef } from 'react';
import { useNavigate } from 'react-router';
import type { NotificationsResponse } from '../../domain/notifications/notifications';
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
  const { request, loadMoreRequest, execute, loadMore, markRead } =
    useNotifications();
  const hasUnread =
    request.status === 'success' && request.data.unreadCount > 0;

  const readPage = async (
    data: NotificationsResponse | null,
  ): Promise<void> => {
    if (data === null) return;
    try {
      await markRead(data.items);
    } catch {
      addToast(
        { type: 'error', message: FEED_COPY.notificationsReadError },
        3000,
      );
    }
  };

  const refresh = async (): Promise<void> => {
    const data = await execute();
    if (data === null && request.status === 'success') {
      addToast(
        { type: 'error', message: FEED_COPY.notificationsLoadError },
        3000,
      );
      return;
    }
    await readPage(data);
  };

  const handleOpen = (): void => {
    sheetRef.current?.open();
    void refresh();
  };

  const handleSelect = (feedId: string): void => {
    sheetRef.current?.close();
    void navigate(`/feeds/${encodeURIComponent(feedId)}`);
  };

  return (
    <>
      <button
        type="button"
        css={bellButtonStyle}
        onClick={handleOpen}
        aria-haspopup="dialog"
        disabled={
          request.status === 'loading' || loadMoreRequest.status === 'loading'
        }
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
        <NotificationList
          request={request}
          loadMoreRequest={loadMoreRequest}
          onRetry={(): void => {
            void refresh();
          }}
          onLoadMore={(): void => {
            void loadMore().then(readPage);
          }}
          onSelect={handleSelect}
        />
      </BottomSheet>
    </>
  );
};

export default FeedNotifications;

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
