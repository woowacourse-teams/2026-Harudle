import { css } from '@emotion/react';
import { useEffect, useRef, useState, type ReactElement } from 'react';
import {
  getBlockedUsers,
  setUserBlocked,
  type BlockedUser,
  type BlockedUsersResponse,
} from '../../domain/blocks/blocks';
import { setFeedListCache } from '../../domain/feeds/feedListCache';
import profileFallbackImage from '../../assets/images/profile-fallback.svg';
import autorenewIcon from '../../assets/icons/autorenew.svg';
import LoadingSpinner from '../../shared/LoadingSpinner';
import ActionButton from '../../shared/ActionButton';
import { useToastContext } from '../../shared/ToastContext';
import { SETTING_COPY } from './copy';
import { theme } from '../../styles/theme';
import type { ApiRequest } from '../../shared/api';

const BlockedUsers = () => {
  const [visibility, setVisibility] = useState<
    'unopened' | 'expanded' | 'collapsed'
  >('unopened');
  const expanded = visibility === 'expanded';
  return (
    <section css={sectionStyle}>
      <button
        type="button"
        css={toggleStyle}
        aria-expanded={expanded}
        onClick={(): void => setVisibility(expanded ? 'collapsed' : 'expanded')}
      >
        {SETTING_COPY.blockedUsersTitle}
      </button>
      {visibility !== 'unopened' && (
        <div hidden={!expanded}>
          <BlockedUsersList />
        </div>
      )}
    </section>
  );
};
export default BlockedUsers;

type ListRetry =
  | { readonly kind: 'refresh' }
  | { readonly kind: 'more'; readonly cursor: string };

const BlockedUsersList = () => {
  const { addToast } = useToastContext();
  const [request, setRequest] = useState<ApiRequest<BlockedUsersResponse>>({
    status: 'loading',
  });
  const [busy, setBusy] = useState<string | 'more' | null>(null);
  const busyRef = useRef(false);
  const [listRetry, setListRetry] = useState<ListRetry | null>(null);
  const mountedRef = useRef(true);
  const readControllerRef = useRef<AbortController | null>(null);

  useEffect((): (() => void) => {
    mountedRef.current = true;
    const controller = new AbortController();
    readControllerRef.current = controller;
    void getBlockedUsers({ signal: controller.signal })
      .then((data): void => {
        if (mountedRef.current && !controller.signal.aborted)
          setRequest({ status: 'success', data });
      })
      .catch((caught: unknown): void => {
        if (mountedRef.current && !controller.signal.aborted)
          setRequest({
            status: 'error',
            error:
              caught instanceof Error
                ? caught
                : new Error(SETTING_COPY.blockedUsersLoadError),
          });
      })
      .finally((): void => {
        if (readControllerRef.current === controller)
          readControllerRef.current = null;
      });
    return (): void => {
      mountedRef.current = false;
      readControllerRef.current?.abort();
      readControllerRef.current = null;
    };
  }, []);

  const load = async (cursor?: string): Promise<void> => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy('more');
    readControllerRef.current?.abort();
    const controller = new AbortController();
    readControllerRef.current = controller;
    try {
      const data = await getBlockedUsers({ cursor, signal: controller.signal });
      if (!mountedRef.current || controller.signal.aborted) return;
      setListRetry(null);
      setRequest((previous): ApiRequest<BlockedUsersResponse> => ({
        status: 'success',
        data: {
          ...data,
          items:
            cursor && previous.status === 'success'
              ? [
                  ...previous.data.items,
                  ...data.items.filter(
                    (item): boolean =>
                      !previous.data.items.some(
                        (old): boolean => old.userId === item.userId,
                      ),
                  ),
                ]
              : data.items,
        },
      }));
    } catch (caught: unknown) {
      if (!mountedRef.current || controller.signal.aborted) return;
      if (request.status !== 'success') {
        setRequest({
          status: 'error',
          error:
            caught instanceof Error
              ? caught
              : new Error(SETTING_COPY.blockedUsersLoadError),
        });
      } else {
        setListRetry(
          cursor === undefined ? { kind: 'refresh' } : { kind: 'more', cursor },
        );
      }
    } finally {
      if (readControllerRef.current === controller)
        readControllerRef.current = null;
      busyRef.current = false;
      if (mountedRef.current) setBusy(null);
    }
  };

  const unblock = async (user: BlockedUser): Promise<void> => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(user.userId);
    try {
      await setUserBlocked(user.userId, false);
      setFeedListCache(null);
      if (!mountedRef.current) return;
      addToast({ type: 'success', message: SETTING_COPY.unblockSuccess }, 2000);
      setRequest((previous): ApiRequest<BlockedUsersResponse> =>
        previous.status === 'success'
          ? {
              status: 'success',
              data: {
                ...previous.data,
                items: previous.data.items.filter(
                  (item): boolean => item.userId !== user.userId,
                ),
              },
            }
          : previous,
      );
      readControllerRef.current?.abort();
      const controller = new AbortController();
      readControllerRef.current = controller;
      try {
        const data = await getBlockedUsers({ signal: controller.signal });
        if (mountedRef.current && !controller.signal.aborted) {
          setRequest({ status: 'success', data });
          setListRetry(null);
        }
      } catch {
        if (mountedRef.current && !controller.signal.aborted)
          setListRetry({ kind: 'refresh' });
      } finally {
        if (readControllerRef.current === controller)
          readControllerRef.current = null;
      }
    } catch {
      if (mountedRef.current)
        addToast({ type: 'error', message: SETTING_COPY.unblockError }, 2000);
    } finally {
      busyRef.current = false;
      if (mountedRef.current) setBusy(null);
    }
  };

  return (
    <div css={listStyle}>
      {request.status === 'loading' && (
        <div role="status" aria-label={SETTING_COPY.blockedUsersLoading}>
          <LoadingSpinner />
        </div>
      )}
      {request.status === 'error' && (
        <BlockedUsersLoadError
          message={SETTING_COPY.blockedUsersLoadError}
          retryLabel={SETTING_COPY.blockedUsersRetry}
          disabled={busy !== null}
          onRetry={(): void => {
            void load();
          }}
        />
      )}
      {request.status === 'success' && (
        <>
          {request.data.items.length === 0 && !request.data.hasNext && (
            <p css={emptyStyle}>{SETTING_COPY.blockedUsersEmpty}</p>
          )}
          <ul>
            {request.data.items.map((user): ReactElement => (
              <li key={user.userId} css={rowStyle}>
                <img
                  src={user.profileImageUrl || profileFallbackImage}
                  alt=""
                  css={avatarStyle}
                  onError={(event): void => {
                    if (
                      event.currentTarget.getAttribute('src') !==
                      profileFallbackImage
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
                    ? SETTING_COPY.unblocking
                    : SETTING_COPY.unblock}
                </button>
              </li>
            ))}
          </ul>
          {request.data.hasNext && listRetry === null && (
            <ActionButton
              variant="secondary"
              disabled={busy !== null}
              label={
                busy === 'more'
                  ? SETTING_COPY.blockedUsersLoading
                  : SETTING_COPY.blockedUsersLoadMore
              }
              onClick={(): void => {
                if (request.data.nextCursor) void load(request.data.nextCursor);
              }}
            />
          )}
        </>
      )}
      {listRetry && (
        <BlockedUsersLoadError
          message={
            listRetry.kind === 'more'
              ? SETTING_COPY.blockedUsersLoadMoreError
              : SETTING_COPY.blockedUsersRefreshError
          }
          retryLabel={
            listRetry.kind === 'more'
              ? SETTING_COPY.blockedUsersLoadMoreRetry
              : SETTING_COPY.blockedUsersRetry
          }
          disabled={busy !== null}
          onRetry={(): void => {
            void load(listRetry.kind === 'more' ? listRetry.cursor : undefined);
          }}
        />
      )}
    </div>
  );
};

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

const sectionStyle = css`
  border: 1px solid ${theme.colors.stroke.outline};
  border-radius: 16px;
  overflow: hidden;
`;
const toggleStyle = css`
  display: block;
  width: 100%;
  min-height: 60px;
  padding: 16px;
  border: 0;
  background: none;
  text-align: left;
  color: ${theme.colors.foreground.neutral};
  font-size: 15px;
  cursor: pointer;
`;
const listStyle = css`
  padding: 0 16px 16px;
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
