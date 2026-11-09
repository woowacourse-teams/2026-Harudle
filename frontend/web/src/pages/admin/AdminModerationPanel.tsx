import { css } from '@emotion/react';
import { useEffect, useRef, useState } from 'react';
import { RequestError, type ApiRequest } from '../../shared/api';
import ActionButton from '../../shared/ActionButton';
import LoadingSpinner from '../../shared/LoadingSpinner';
import { useToastContext } from '../../shared/ToastContext';
import { setFeedListCache } from '../../domain/feeds/feedListCache';
import { theme } from '../../styles/theme';
import refreshIcon from '../../assets/icons/feed-refresh.svg';
import AdminReasonModal, {
  type AdminReasonModalHandle,
} from './AdminReasonModal';
import {
  getAdminModeration,
  getAdminModerationHistory,
  setAdminModeration,
  type AdminPageResponse,
  type ModerationHistory,
  type ModerationState,
  type ModerationTargetType,
} from './adminModerationApi';
import { ADMIN_MODERATION_COPY as COPY } from './copy';
interface Props {
  readonly targetType: ModerationTargetType;
  readonly targetId: string;
  readonly available?: boolean;
  readonly onChanged?: () => void;
}
interface Data {
  readonly state: ModerationState;
  readonly history: AdminPageResponse<ModerationHistory>;
}
const AdminModerationPanel = ({
  targetType,
  targetId,
  available = true,
  onChanged,
}: Props) => {
  const { addToast } = useToastContext();
  const [request, setRequest] = useState<ApiRequest<Data>>({
    status: 'loading',
  });
  const [revision, setRevision] = useState(0);
  const [historyPage, setHistoryPage] = useState(0);
  const [refreshError, setRefreshError] = useState(false);
  const [stale, setStale] = useState(false);
  const [reading, setReading] = useState(true);
  const controllerRef = useRef<AbortController | null>(null);
  const mountedRef = useRef(true);
  const dialogRef = useRef<AdminReasonModalHandle>(null);
  const account = targetType === 'USER';
  const reload = (): void => {
    setReading(true);
    setRevision((current): number => current + 1);
  };
  useEffect((): (() => void) => {
    mountedRef.current = true;
    const controller = new AbortController();
    controllerRef.current = controller;
    void Promise.all([
      getAdminModeration(targetType, targetId, controller.signal),
      getAdminModerationHistory(
        targetType,
        targetId,
        historyPage,
        controller.signal,
      ),
    ])
      .then(([state, history]): void => {
        if (controller.signal.aborted) return;
        setRequest({ status: 'success', data: { state, history } });
        setRefreshError(false);
        setStale(false);
      })
      .catch((error: unknown): void => {
        if (controller.signal.aborted) return;
        setRefreshError(true);
        setRequest((previous): ApiRequest<Data> =>
          previous.status === 'success'
            ? previous
            : {
                status: 'error',
                error: new Error(
                  error instanceof RequestError && error.problem.status === 404
                    ? '조치 이력이 없어요.'
                    : COPY.moderationError,
                ),
              },
        );
      })
      .finally((): void => {
        if (!controller.signal.aborted) setReading(false);
      });
    return (): void => {
      mountedRef.current = false;
      controller.abort();
    };
  }, [targetType, targetId, available, revision, historyPage]);
  const act = (): void => {
    if (!available || stale || reading || request.status !== 'success') return;
    const current = request.data.state;
    const restricted = !current.restricted;
    const title = account
      ? restricted
        ? '사용자 정지'
        : '정지 해제'
      : restricted
        ? '콘텐츠 숨김'
        : '숨김 해제';
    dialogRef.current?.open({
      title,
      description: restricted
        ? account
          ? COPY.suspendHint
          : COPY.hideHint
        : COPY.restoreHint,
      execute: async (reason): Promise<void> => {
        const state = await setAdminModeration(
          targetType,
          targetId,
          restricted,
          current.version,
          reason,
        );
        setFeedListCache(null);
        if (!mountedRef.current) return;
        controllerRef.current?.abort();
        setRequest((previous): ApiRequest<Data> =>
          previous.status === 'success'
            ? { status: 'success', data: { ...previous.data, state } }
            : previous,
        );
        setStale(true);
      },
      onSuccess: (): void => {
        addToast({ type: 'success', message: COPY.success }, 2000);
        reload();
        onChanged?.();
      },
      onConflict: (): void => {
        setStale(true);
      },
    });
  };
  return (
    <section
      css={panelStyle}
      aria-label={account ? '계정 조치' : '콘텐츠 조치'}
    >
      <div css={headingStyle}>
        <h3>{account ? '계정 정지' : '콘텐츠 숨김'}</h3>
        <div css={compactButtonStyle}>
          <ActionButton
            label={COPY.retry}
            icon={<img src={refreshIcon} alt="" />}
            variant="secondary"
            disabled={reading}
            onClick={(): void => {
              setStale(true);
              reload();
            }}
          />
        </div>
      </div>
      {!available && <p css={mutedStyle}>{COPY.deleted}</p>}
      {request.status === 'loading' ? (
        <LoadingSpinner />
      ) : request.status === 'error' ? (
        <p css={noticeStyle} role="alert">
          {request.error.message}
        </p>
      ) : request.status === 'success' ? (
        <>
          <div css={actionRowStyle}>
            <span css={stateStyle(request.data.state.restricted)}>
              {request.data.state.restricted
                ? account
                  ? '정지 중'
                  : '숨김'
                : account
                  ? '정지 없음'
                  : '관리자 숨김 없음'}
            </span>
            <div css={compactButtonStyle}>
              <ActionButton
                label={
                  request.data.state.restricted
                    ? account
                      ? '정지 해제'
                      : '숨김 해제'
                    : account
                      ? '사용자 정지'
                      : '콘텐츠 숨김'
                }
                variant="secondary"
                disabled={!available || stale || reading}
                onClick={act}
              />
            </div>
          </div>
          <div css={historySectionStyle}>
            <h4>조치 이력</h4>
            {request.data.history.content.length === 0 ? (
              <p css={mutedStyle}>조치 이력이 없어요.</p>
            ) : (
              <ul css={historyStyle}>
                {request.data.history.content.map((entry) => (
                  <li key={entry.id}>
                    <div css={historyHeadingStyle}>
                      <strong css={stateStyle(entry.restricted)}>
                        {entry.restricted
                          ? account
                            ? '정지'
                            : '숨김'
                          : '해제'}
                      </strong>
                      <time dateTime={entry.changedAt}>
                        {new Date(entry.changedAt).toLocaleString('ko-KR', {
                          year: 'numeric',
                          month: '2-digit',
                          day: '2-digit',
                          hour: '2-digit',
                          minute: '2-digit',
                        })}
                      </time>
                      <small>버전 {entry.version}</small>
                    </div>
                    <dl css={historyInfoStyle}>
                      <dt>사유</dt>
                      <dd css={reasonStyle}>{entry.reason}</dd>
                      <dt>처리자</dt>
                      <dd css={actorStyle}>
                        {entry.actorId ?? '삭제된 관리자'}
                      </dd>
                    </dl>
                  </li>
                ))}
              </ul>
            )}
            {(request.data.history.totalPages > 1 || historyPage > 0) && (
              <div css={pageStyle}>
                <ActionButton
                  label="이전 이력"
                  variant="secondary"
                  disabled={reading || historyPage === 0}
                  onClick={(): void => {
                    setStale(true);
                    setReading(true);
                    setHistoryPage((page): number => page - 1);
                  }}
                />
                <span>{historyPage + 1}쪽</span>
                <ActionButton
                  label="다음 이력"
                  variant="secondary"
                  disabled={reading || !request.data.history.hasNext}
                  onClick={(): void => {
                    setStale(true);
                    setReading(true);
                    setHistoryPage((page): number => page + 1);
                  }}
                />
              </div>
            )}
          </div>
        </>
      ) : null}
      {request.status === 'success' && (refreshError || stale) && (
        <p css={noticeStyle} role="alert">
          {refreshError ? COPY.refreshError : '최신 상태를 다시 불러와 주세요.'}
        </p>
      )}
      <AdminReasonModal ref={dialogRef} />
    </section>
  );
};
export default AdminModerationPanel;
const panelStyle = css`
  display: grid;
  gap: 16px;
  min-width: 0;
  padding: 20px;
  border: 1px solid ${theme.colors.stroke.outline};
  border-radius: 16px;
  background: ${theme.colors.background.surface};
  h3 {
    font-size: 18px;
    font-weight: 700;
  }
  h4 {
    font-size: 14px;
    font-weight: 700;
  }
`;
const headingStyle = css`
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
`;
const compactButtonStyle = css`
  flex-shrink: 0;
  > button {
    width: auto;
    height: 40px;
    padding: 8px 12px;
    border-radius: 10px;
    font-size: 13px;
    line-height: 20px;
    > span {
      width: 16px;
      height: 16px;
    }
  }
`;
const actionRowStyle = css`
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
`;
const stateStyle = (restricted: boolean) => css`
  display: inline-block;
  padding: 5px 10px;
  border-radius: 7px;
  color: ${restricted ? theme.colors.foreground.neutral : theme.colors.foreground.neutralMuted};
  background: ${restricted ? theme.colors.background.criticalWeak : theme.colors.background.neutralSubtle};
  font-size: 13px;
  font-weight: 700;
  line-height: 20px;
`;
const historySectionStyle = css`
  display: grid;
  gap: 12px;
  padding-top: 16px;
  border-top: 1px solid ${theme.colors.stroke.divider};
`;
const historyStyle = css`
  display: grid;
  gap: 12px;
  li {
    min-width: 0;
    padding: 12px;
    border-radius: 10px;
    background: ${theme.colors.background.neutralSubtle};
  }
`;
const historyHeadingStyle = css`
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
  time,
  small {
    font-size: 12px;
    color: ${theme.colors.foreground.neutralMuted};
  }
`;
const historyInfoStyle = css`
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr);
  gap: 8px;
  font-size: 13px;
  line-height: 1.5;
  dt {
    color: ${theme.colors.foreground.neutralMuted};
  }
  dd {
    min-width: 0;
    overflow-wrap: anywhere;
  }
`;
const reasonStyle = css`
  white-space: pre-wrap;
`;
const actorStyle = css`
  font-size: 12px;
  color: ${theme.colors.foreground.neutralMuted};
`;
const mutedStyle = css`
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 13px;
  line-height: 1.5;
`;
const noticeStyle = css`
  padding: 12px;
  border-radius: 10px;
  color: ${theme.colors.foreground.neutral};
  border-left: 3px solid ${theme.colors.stroke.critical};
  background: ${theme.colors.background.criticalWeak};
  font-size: 13px;
  line-height: 1.5;
  overflow-wrap: anywhere;
`;
const pageStyle = css`
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  button {
    width: auto;
    height: 36px;
    padding: 6px 10px;
    border-radius: 10px;
    font-size: 13px;
  }
  span {
    flex-shrink: 0;
    white-space: nowrap;
    font-size: 13px;
  }
`;
