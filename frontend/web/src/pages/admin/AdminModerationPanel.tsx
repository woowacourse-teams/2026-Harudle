import { css } from '@emotion/react';
import { useEffect, useRef, useState } from 'react';
import { RequestError, type ApiRequest } from '../../shared/api';
import ActionButton from '../../shared/ActionButton';
import LoadingSpinner from '../../shared/LoadingSpinner';
import { useToastContext } from '../../shared/ToastContext';
import { setFeedListCache } from '../../domain/feeds/feedListCache';
import { theme } from '../../styles/theme';
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
      <h3>{account ? '계정 정지' : '콘텐츠 숨김'}</h3>
      {!available && <p>{COPY.deleted}</p>}
      {request.status === 'loading' ? (
        <LoadingSpinner />
      ) : request.status === 'error' ? (
        <p role="alert">{request.error.message}</p>
      ) : request.status === 'success' ? (
        <>
          <p>
            현재 상태:{' '}
            <strong>
              {request.data.state.restricted
                ? account
                  ? '정지 중'
                  : '숨김'
                : account
                  ? '정지 없음'
                  : '관리자 숨김 없음'}
            </strong>
          </p>
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
          <h4>조치 이력</h4>
          <ul css={historyStyle}>
            {request.data.history.content.map((entry) => (
              <li key={entry.id}>
                <strong>
                  {entry.restricted ? (account ? '정지' : '숨김') : '해제'}
                </strong>{' '}
                · {new Date(entry.changedAt).toLocaleString('ko-KR')}
                <p>{entry.reason}</p>
                <small>
                  처리자 {entry.actorId ?? '삭제된 관리자'} · 버전{' '}
                  {entry.version}
                </small>
              </li>
            ))}
          </ul>
          {request.data.history.content.length === 0 && (
            <p>조치 이력이 없어요.</p>
          )}
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
        </>
      ) : null}
      <>
        {request.status === 'success' && (refreshError || stale) && (
          <p role="alert">
            {refreshError
              ? COPY.refreshError
              : '최신 상태를 다시 불러온 뒤 조치해 주세요.'}
          </p>
        )}
        <button
          type="button"
          disabled={reading}
          onClick={(): void => {
            setStale(true);
            reload();
          }}
        >
          {COPY.retry}
        </button>
      </>
      <AdminReasonModal ref={dialogRef} />
    </section>
  );
};
export default AdminModerationPanel;
const panelStyle = css`
  display: grid;
  gap: 12px;
  padding: 20px;
  border: 1px solid ${theme.colors.stroke.outline};
  border-radius: 16px;
  background: ${theme.colors.background.surface};
  h3 {
    font-size: 18px;
    font-weight: 700;
  }
  h4 {
    font-weight: 700;
    margin-top: 8px;
  }
  p {
    overflow-wrap: anywhere;
  }
`;
const historyStyle = css`
  display: grid;
  gap: 12px;
  li {
    padding-bottom: 12px;
    border-bottom: 1px solid ${theme.colors.stroke.divider};
  }
  small {
    color: ${theme.colors.foreground.neutralMuted};
  }
`;
const pageStyle = css`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  button {
    flex: 1;
    min-width: 0;
    padding-inline: 12px;
    white-space: nowrap;
  }
  span {
    flex-shrink: 0;
    white-space: nowrap;
  }
`;
