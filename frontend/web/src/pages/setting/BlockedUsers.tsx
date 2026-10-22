import { css } from '@emotion/react';
import { useEffect, useRef, useState } from 'react';
import {
  getBlockedUsers,
  setUserBlocked,
  type BlockedUser,
  type BlockedUsersResponse,
} from '../../domain/blocks/blocks';
import { setFeedListCache } from '../../domain/feeds/feedListCache';
import profileFallbackImage from '../../assets/images/profile-fallback.svg';
import { theme } from '../../styles/theme';
import type { ApiRequest } from '../../shared/api';

const BlockedUsers = () => {
  const [expanded, setExpanded] = useState(false);
  return (
    <section css={sectionStyle}>
      <button
        type="button"
        css={toggleStyle}
        aria-expanded={expanded}
        onClick={(): void => setExpanded(!expanded)}
      >
        차단한 사용자
      </button>
      {expanded && <BlockedUsersList />}
    </section>
  );
};
export default BlockedUsers;

const BlockedUsersList = () => {
  const [request, setRequest] = useState<ApiRequest<BlockedUsersResponse>>({
    status: 'loading',
  });
  const [busy, setBusy] = useState<string | 'more' | null>(null);
  const busyRef = useRef(false);
  const [error, setError] = useState<string | null>(null);
  const mountedRef = useRef(true);

  useEffect((): (() => void) => {
    mountedRef.current = true;
    const controller = new AbortController();
    void getBlockedUsers({ signal: controller.signal })
      .then((data): void => {
        if (mountedRef.current) setRequest({ status: 'success', data });
      })
      .catch((caught: unknown): void => {
        if (!controller.signal.aborted)
          setRequest({
            status: 'error',
            error:
              caught instanceof Error
                ? caught
                : new Error('차단 목록을 불러오지 못했어요.'),
          });
      });
    return (): void => {
      mountedRef.current = false;
      controller.abort();
    };
  }, []);

  const load = async (cursor?: string): Promise<void> => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy('more');
    setError(null);
    try {
      const data = await getBlockedUsers({ cursor });
      if (!mountedRef.current) return;
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
      if (mountedRef.current)
        setError(
          caught instanceof Error
            ? caught.message
            : '차단 목록을 불러오지 못했어요.',
        );
    } finally {
      busyRef.current = false;
      if (mountedRef.current) setBusy(null);
    }
  };

  const unblock = async (user: BlockedUser): Promise<void> => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(user.userId);
    setError(null);
    try {
      await setUserBlocked(user.userId, false);
      setFeedListCache(null);
      const data = await getBlockedUsers();
      if (mountedRef.current) setRequest({ status: 'success', data });
    } catch (caught: unknown) {
      if (mountedRef.current)
        setError(
          caught instanceof Error
            ? caught.message
            : '차단을 해제하지 못했어요. 다시 시도해주세요.',
        );
    } finally {
      busyRef.current = false;
      if (mountedRef.current) setBusy(null);
    }
  };

  return (
    <div css={listStyle}>
      <p css={hintStyle}>
        내가 차단한 사용자만 표시해요. 해제하면 내가 한 차단만 해제돼요.
      </p>
      {request.status === 'loading' && (
        <p role="status">차단 목록을 불러오는 중…</p>
      )}
      {request.status === 'error' && (
        <>
          <p role="alert">{request.error.message}</p>
          <button
            type="button"
            disabled={busy !== null}
            onClick={(): void => {
              void load();
            }}
          >
            다시 불러오기
          </button>
        </>
      )}
      {request.status === 'success' && (
        <>
          {request.data.items.length === 0 && <p>차단한 사용자가 없어요.</p>}
          <ul>
            {request.data.items.map((user) => (
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
                  {busy === user.userId ? '해제 중…' : '해제'}
                </button>
              </li>
            ))}
          </ul>
          {request.data.hasNext && (
            <button
              type="button"
              disabled={busy !== null}
              onClick={(): void => {
                if (request.data.nextCursor) void load(request.data.nextCursor);
              }}
            >
              더 불러오기
            </button>
          )}
        </>
      )}
      {error && <p role="alert">{error}</p>}
    </div>
  );
};

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
const hintStyle = css`
  padding-bottom: 12px;
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
