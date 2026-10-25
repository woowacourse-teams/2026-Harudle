import { css } from '@emotion/react';
import type { ReactElement } from 'react';
import { Link } from 'react-router';
import type { BlockedUsersResponse } from '../../domain/blocks/blocks';
import profileFallbackImage from '../../assets/images/profile-fallback.svg';
import autorenewIcon from '../../assets/icons/autorenew.svg';
import backIcon from '../../assets/icons/back.svg';
import LoadingSpinner from '../../shared/LoadingSpinner';
import ActionButton from '../../shared/ActionButton';
import PageHeader from '../../shared/PageHeader';
import { theme } from '../../styles/theme';
import useBlockedUsers from './useBlockedUsers';
import { BLOCKED_USERS_COPY } from './copy';

const BlockedUsersPage = () => (
  <main css={pageStyle}>
    <PageHeader
      left={
        <Link
          to="/setting"
          aria-label="설정으로 돌아가기"
          css={backButtonStyle}
        >
          <img src={backIcon} alt="" />
        </Link>
      }
      title={BLOCKED_USERS_COPY.blockedUsersTitle}
      right={null}
    />
    <section
      css={sectionStyle}
      aria-label={BLOCKED_USERS_COPY.blockedUsersTitle}
    >
      <BlockedUsers />
    </section>
  </main>
);

export default BlockedUsersPage;

const BlockedUsers = () => {
  const { request, ...actions } = useBlockedUsers();

  if (request.status === 'idle' || request.status === 'loading') {
    return (
      <div
        css={listStyle}
        role="status"
        aria-label={BLOCKED_USERS_COPY.blockedUsersLoading}
      >
        <LoadingSpinner />
      </div>
    );
  }

  if (request.status === 'error') {
    return (
      <div css={listStyle}>
        <BlockedUsersLoadError
          message={BLOCKED_USERS_COPY.blockedUsersLoadError}
          retryLabel={BLOCKED_USERS_COPY.blockedUsersRetry}
          disabled={actions.busy !== null}
          onRetry={(): void => {
            void actions.retry();
          }}
        />
      </div>
    );
  }

  return <BlockedUsersContent data={request.data} {...actions} />;
};

interface BlockedUsersContentProps extends Omit<
  ReturnType<typeof useBlockedUsers>,
  'request'
> {
  readonly data: BlockedUsersResponse;
}

const BlockedUsersContent = ({
  data,
  busy,
  listRetry,
  retry,
  loadMore,
  unblock,
}: BlockedUsersContentProps) => (
  <div css={listStyle}>
    {data.items.length === 0 && !data.hasNext && (
      <p css={emptyStyle}>{BLOCKED_USERS_COPY.blockedUsersEmpty}</p>
    )}
    <ul>
      {data.items.map((user): ReactElement => (
        <li key={user.userId} css={rowStyle}>
          <img
            src={user.profileImageUrl || profileFallbackImage}
            alt=""
            css={avatarStyle}
            onError={(event): void => {
              if (
                event.currentTarget.getAttribute('src') !== profileFallbackImage
              )
                event.currentTarget.src = profileFallbackImage;
            }}
          />
          <span css={nameStyle}>{user.nickname}</span>
          <button
            type="button"
            css={unblockStyle}
            disabled={busy !== null}
            aria-label={`${user.nickname} 차단 해제`}
            onClick={(): void => {
              void unblock(user);
            }}
          >
            {busy === user.userId
              ? BLOCKED_USERS_COPY.unblocking
              : BLOCKED_USERS_COPY.unblock}
          </button>
        </li>
      ))}
    </ul>
    {data.hasNext && listRetry === null && (
      <ActionButton
        variant="secondary"
        disabled={busy !== null}
        label={
          busy === 'more'
            ? BLOCKED_USERS_COPY.blockedUsersLoading
            : BLOCKED_USERS_COPY.blockedUsersLoadMore
        }
        onClick={(): void => {
          void loadMore();
        }}
      />
    )}
    {listRetry && (
      <BlockedUsersLoadError
        message={
          listRetry.kind === 'more'
            ? BLOCKED_USERS_COPY.blockedUsersLoadMoreError
            : BLOCKED_USERS_COPY.blockedUsersRefreshError
        }
        retryLabel={
          listRetry.kind === 'more'
            ? BLOCKED_USERS_COPY.blockedUsersLoadMoreRetry
            : BLOCKED_USERS_COPY.blockedUsersRetry
        }
        disabled={busy !== null}
        onRetry={(): void => {
          void retry();
        }}
      />
    )}
  </div>
);

interface BlockedUsersLoadErrorProps {
  readonly message: string;
  readonly retryLabel: string;
  readonly disabled: boolean;
  readonly onRetry: () => void;
}

const BlockedUsersLoadError = ({
  message,
  retryLabel,
  disabled,
  onRetry,
}: BlockedUsersLoadErrorProps) => (
  <div css={loadErrorStyle}>
    <p role="alert">{message}</p>
    <button
      type="button"
      css={retryButtonStyle}
      aria-label={retryLabel}
      disabled={disabled}
      onClick={onRetry}
    >
      <img src={autorenewIcon} alt="" />
    </button>
  </div>
);

const pageStyle = css`
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 16px;
  height: 100%;
  padding: 4px 20px 20px;
  overflow-y: auto;

  & > header {
    flex-shrink: 0;
  }
`;

const backButtonStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;

  img {
    width: 24px;
    height: 24px;
  }
`;

const sectionStyle = css`
  border: 1px solid ${theme.colors.stroke.outline};
  border-radius: 16px;
`;

const listStyle = css`
  padding: 16px;
  font-size: 14px;
  line-height: 22px;
`;
const emptyStyle = css`
  color: ${theme.colors.foreground.neutralMuted};
`;
const rowStyle = css`
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 0;
`;
const avatarStyle = css`
  width: 32px;
  height: 32px;
  border-radius: 50%;
  object-fit: cover;
`;
const nameStyle = css`
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
`;
const unblockStyle = css`
  padding: 8px 12px;
  border: 1px solid ${theme.colors.stroke.outline};
  border-radius: 8px;
  background: none;
  color: ${theme.colors.foreground.neutral};
  cursor: pointer;
  &:disabled {
    opacity: 0.5;
  }
`;

const loadErrorStyle = css`
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 48px;
  color: ${theme.colors.foreground.neutralMuted};
`;
const retryButtonStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: none;
  cursor: pointer;

  img {
    display: block;
  }

  &:hover,
  &:active {
    background-color: ${theme.colors.background.brandWeak};
  }

  &:disabled {
    opacity: 0.5;
    cursor: default;
  }
`;
