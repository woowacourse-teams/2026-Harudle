import { css } from '@emotion/react';
import ActionButton from '../../shared/ActionButton';
import { theme } from '../../styles/theme';
import type { NotificationsState } from './useNotifications';
import NotificationItem from './NotificationItem';
import { FEED_COPY } from './copy';

interface NotificationListProps extends Pick<
  NotificationsState,
  'request' | 'loadMoreRequest'
> {
  readonly onRetry: () => void;
  readonly onLoadMore: () => void;
  readonly onSelect: (feedId: string) => void;
}

const NotificationList = ({
  request,
  loadMoreRequest,
  onRetry,
  onLoadMore,
  onSelect,
}: NotificationListProps) => {
  if (request.status === 'idle' || request.status === 'loading')
    return <div css={feedbackStyle} aria-busy="true" />;

  if (request.status === 'error')
    return (
      <div css={feedbackStyle} role="alert">
        <p>{FEED_COPY.notificationsLoadError}</p>
        <ActionButton
          label={FEED_COPY.retry}
          variant="secondary"
          onClick={onRetry}
        />
      </div>
    );

  const { items, hasNext } = request.data;
  if (items.length === 0 && !hasNext)
    return <p css={feedbackStyle}>{FEED_COPY.notificationsEmpty}</p>;
  const isLoadingMore = loadMoreRequest.status === 'loading';

  return (
    <>
      <ul>
        {items.map((notification) => (
          <NotificationItem
            key={notification.id}
            notification={notification}
            onSelect={onSelect}
          />
        ))}
      </ul>
      {loadMoreRequest.status === 'error' && (
        <p css={errorStyle} role="alert">
          {FEED_COPY.notificationsLoadError}
        </p>
      )}
      {hasNext && (
        <div css={moreStyle}>
          <ActionButton
            label={
              isLoadingMore
                ? FEED_COPY.loadingMoreAction
                : loadMoreRequest.status === 'error'
                  ? FEED_COPY.retry
                  : FEED_COPY.loadMoreAction
            }
            variant="secondary"
            disabled={isLoadingMore}
            onClick={onLoadMore}
          />
        </div>
      )}
    </>
  );
};

export default NotificationList;

const feedbackStyle = css`
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  min-height: 162px;
  padding: 24px 0;
  font-size: 14px;
  line-height: 21px;
  text-align: center;
  color: ${theme.colors.foreground.neutralMuted};
`;

const moreStyle = css`
  padding: 16px 0 24px;
`;

const errorStyle = css`
  padding: 12px 0;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 12px;
  line-height: 18px;
`;
