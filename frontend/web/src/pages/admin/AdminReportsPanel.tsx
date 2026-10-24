import { css } from '@emotion/react';
import refreshIcon from '../../assets/icons/feed-refresh.svg';
import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';
import type { ApiRequest } from '../../shared/api';
import ActionButton from '../../shared/ActionButton';
import LoadingSpinner from '../../shared/LoadingSpinner';
import { useToastContext } from '../../shared/ToastContext';
import { theme } from '../../styles/theme';
import AdminModerationPanel from './AdminModerationPanel';
import AdminReasonModal, {
  type AdminReasonModalHandle,
} from './AdminReasonModal';
import {
  getAdminReports,
  getAdminReport,
  reviewAdminReport,
  isReportStatus,
  type AdminReportDetail,
  type AdminReportSummary,
  type AdminPageResponse,
  type ReportStatus,
} from './adminModerationApi';
import {
  ADMIN_MODERATION_COPY as COPY,
  REPORT_STATUS_LABELS,
  REPORT_REASON_LABELS,
  REPORT_TARGET_LABELS,
} from './copy';

const AdminReportsPanel = () => {
  const [params, setParams] = useSearchParams();
  const rawStatus = params.get('status');
  const status = isReportStatus(rawStatus) ? rawStatus : 'RECEIVED';
  const rawPage = Number(params.get('page') ?? '0');
  const page = Number.isSafeInteger(rawPage) && rawPage >= 0 ? rawPage : 0;
  const selectedId = params.get('reportId');
  const [revision, setRevision] = useState(0);
  const update = (
    nextStatus: ReportStatus,
    nextPage: number,
    reportId?: string,
  ): void => {
    const next = new URLSearchParams(params);
    next.set('view', 'reports');
    next.set('status', nextStatus);
    next.set('page', String(nextPage));
    if (reportId) next.set('reportId', reportId);
    else next.delete('reportId');
    setParams(next);
  };
  return (
    <section aria-label="신고 심사" css={panelStyle}>
      <div css={toolbarStyle} role="group" aria-label="신고 상태 필터">
        {Object.keys(REPORT_STATUS_LABELS)
          .filter(isReportStatus)
          .map((value) => (
            <button
              key={value}
              type="button"
              aria-pressed={status === value}
              css={filterStyle(status === value)}
              onClick={(): void => update(value, 0)}
            >
              {REPORT_STATUS_LABELS[value]}
            </button>
          ))}
      </div>
      <div css={columnsStyle}>
        <ReportList
          key={`${status}-${page}-${revision}`}
          status={status}
          page={page}
          selectedId={selectedId}
          onSelect={(id): void => update(status, page, id)}
          onPage={(next): void => update(status, next)}
        />
        {selectedId ? (
          <ReportDetail
            key={selectedId}
            id={selectedId}
            onReviewed={(): void => setRevision((value): number => value + 1)}
          />
        ) : (
          <p css={emptyStyle}>{COPY.select}</p>
        )}
      </div>
    </section>
  );
};
export default AdminReportsPanel;
interface ListProps {
  readonly status: ReportStatus;
  readonly page: number;
  readonly selectedId: string | null;
  readonly onSelect: (id: string) => void;
  readonly onPage: (page: number) => void;
}
const ReportList = ({
  status,
  page,
  selectedId,
  onSelect,
  onPage,
}: ListProps) => {
  const [request, setRequest] = useState<
    ApiRequest<AdminPageResponse<AdminReportSummary>>
  >({ status: 'loading' });
  const [revision, setRevision] = useState(0);
  useEffect((): (() => void) => {
    const controller = new AbortController();
    void getAdminReports(status, page, controller.signal)
      .then((data): void => {
        if (!controller.signal.aborted) setRequest({ status: 'success', data });
      })
      .catch((error: unknown): void => {
        if (!controller.signal.aborted)
          setRequest({
            status: 'error',
            error: error instanceof Error ? error : new Error(COPY.listError),
          });
      });
    return (): void => controller.abort();
  }, [status, page, revision]);
  return (
    <div css={cardStyle} aria-label="신고 목록">
      <h2>신고 목록</h2>
      {request.status === 'loading' ? (
        <LoadingSpinner />
      ) : request.status === 'error' ? (
        <div css={errorBoxStyle} role="alert">
          <p>{COPY.listError}</p>
          <div css={compactActionsStyle}>
            <ActionButton
              label={COPY.retry}
              variant="secondary"
              icon={<img src={refreshIcon} alt="" aria-hidden="true" />}
              onClick={(): void => {
                setRequest({ status: 'loading' });
                setRevision((value): number => value + 1);
              }}
            />
          </div>
        </div>
      ) : request.status === 'success' ? (
        <>
          <p>전체 {request.data.totalElements}건</p>
          <ul css={listStyle}>
            {request.data.content.map((item) => (
              <li key={item.id}>
                <button
                  css={reportButtonStyle(item.id === selectedId)}
                  type="button"
                  onClick={(): void => onSelect(item.id)}
                  aria-label={`${REPORT_TARGET_LABELS[item.targetType]} 신고 ${item.id}`}
                  aria-pressed={item.id === selectedId}
                >
                  <strong>
                    {REPORT_TARGET_LABELS[item.targetType]} ·{' '}
                    {REPORT_REASON_LABELS[item.reason]}
                  </strong>
                  <span>
                    {new Date(item.createdAt).toLocaleString('ko-KR')}
                  </span>
                  <small>{item.id}</small>
                </button>
              </li>
            ))}
          </ul>
          {request.data.content.length === 0 && <p>{COPY.empty}</p>}
          <div css={pageStyle}>
            <ActionButton
              label="이전 페이지"
              variant="secondary"
              disabled={page === 0}
              onClick={(): void => onPage(page - 1)}
            />
            <span>{page + 1}쪽</span>
            <ActionButton
              label="다음 페이지"
              variant="secondary"
              disabled={!request.data.hasNext}
              onClick={(): void => onPage(page + 1)}
            />
          </div>
        </>
      ) : null}
    </div>
  );
};
interface DetailProps {
  readonly id: string;
  readonly onReviewed: () => void;
}
const ReportDetail = ({ id, onReviewed }: DetailProps) => {
  const { addToast } = useToastContext();
  const [request, setRequest] = useState<ApiRequest<AdminReportDetail>>({
    status: 'loading',
  });
  const [revision, setRevision] = useState(0);
  const [stale, setStale] = useState(false);
  const [refreshError, setRefreshError] = useState(false);
  const [reading, setReading] = useState(true);
  const controllerRef = useRef<AbortController | null>(null);
  const mountedRef = useRef(true);
  const dialogRef = useRef<AdminReasonModalHandle>(null);
  const reload = (): void => {
    setStale(true);
    setReading(true);
    setRevision((value): number => value + 1);
  };
  useEffect((): (() => void) => {
    mountedRef.current = true;
    const controller = new AbortController();
    controllerRef.current = controller;
    void getAdminReport(id, controller.signal)
      .then((data): void => {
        if (controller.signal.aborted) return;
        setRequest({ status: 'success', data });
        setStale(false);
        setRefreshError(false);
      })
      .catch((error: unknown): void => {
        if (controller.signal.aborted) return;
        setRefreshError(true);
        setRequest((previous): ApiRequest<AdminReportDetail> =>
          previous.status === 'success'
            ? previous
            : {
                status: 'error',
                error:
                  error instanceof Error ? error : new Error(COPY.detailError),
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
  }, [id, revision]);
  const review = (status: 'DISMISSED' | 'RESOLVED'): void => {
    if (
      request.status !== 'success' ||
      request.data.report.status !== 'RECEIVED' ||
      stale ||
      reading
    )
      return;
    dialogRef.current?.open({
      title: status === 'DISMISSED' ? '신고 반려' : '신고 처리 완료',
      description: COPY.reviewHint,
      execute: async (reason): Promise<void> => {
        const result = await reviewAdminReport(id, status, reason);
        if (!mountedRef.current) return;
        controllerRef.current?.abort();
        setRequest((previous): ApiRequest<AdminReportDetail> =>
          previous.status === 'success'
            ? {
                status: 'success',
                data: {
                  ...previous.data,
                  report: { ...previous.data.report, ...result },
                },
              }
            : previous,
        );
      },
      onSuccess: (): void => {
        addToast({ type: 'success', message: COPY.success }, 2000);
        onReviewed();
        reload();
      },
      onConflict: (): void => setStale(true),
    });
  };
  if (request.status === 'loading') return <LoadingSpinner />;
  const feedback =
    request.status === 'error'
      ? COPY.detailError
      : refreshError
        ? COPY.refreshError
        : stale
          ? '최신 상태를 다시 불러온 뒤 처리해 주세요.'
          : null;
  return (
    <div css={detailStyle} aria-label="신고 상세">
      {request.status === 'success' && (
        <>
          <section css={cardStyle} aria-label="신고 정보">
            <h2>
              신고 상세 · {REPORT_STATUS_LABELS[request.data.report.status]}
            </h2>
            <dl css={metadataStyle}>
              <dt>사유</dt>
              <dd>{REPORT_REASON_LABELS[request.data.report.reason]}</dd>
              <dt>상세 내용</dt>
              <dd css={contentStyle}>
                {request.data.report.description || '상세 내용 없음'}
              </dd>
              <dt>접수 일시</dt>
              <dd>
                <time dateTime={request.data.report.createdAt}>
                  {new Date(request.data.report.createdAt).toLocaleString(
                    'ko-KR',
                  )}
                </time>
              </dd>
            </dl>
            <details css={identifiersStyle}>
              <summary>식별 정보</summary>
              <dl css={metadataStyle}>
                <dt>신고 ID</dt>
                <dd>{request.data.report.id}</dd>
                <dt>대상 ID</dt>
                <dd>{request.data.report.targetId}</dd>
                <dt>신고자 ID</dt>
                <dd>{request.data.report.reporterId ?? '탈퇴한 사용자'}</dd>
                <dt>대상 작성자 ID</dt>
                <dd>{request.data.report.targetUserId}</dd>
              </dl>
            </details>
          </section>
          <section css={cardStyle} aria-label="신고 대상">
            <h3>신고 대상</h3>
            <dl css={metadataStyle}>
              <dt>유형</dt>
              <dd>{REPORT_TARGET_LABELS[request.data.report.targetType]}</dd>
              <dt>작성자</dt>
              <dd>{request.data.target.authorNickname ?? '탈퇴한 사용자'}</dd>
              <dt>노출 상태</dt>
              <dd>
                {request.data.target.deleted ||
                request.data.target.parentDeleted ||
                !request.data.target.exists
                  ? '삭제되었거나 찾을 수 없는 콘텐츠'
                  : request.data.target.parentHidden
                    ? '다른 연결된 피드의 관리자 숨김이 유지돼요.'
                    : request.data.target.hidden
                      ? '관리자 숨김 상태'
                      : request.data.target.publiclyVisible
                        ? '공개 상태'
                        : '비공개 상태'}
              </dd>
              {request.data.target.title && (
                <>
                  <dt>제목</dt>
                  <dd>{request.data.target.title}</dd>
                </>
              )}
              {request.data.target.commentContent && (
                <>
                  <dt>댓글 내용</dt>
                  <dd css={contentStyle}>
                    {request.data.target.commentContent}
                  </dd>
                </>
              )}
            </dl>
            {request.data.target.imageUrl && (
              <img
                css={imageStyle}
                src={request.data.target.imageUrl}
                alt="신고 대상 피드 이미지"
              />
            )}
          </section>
          <section css={cardStyle}>
            <h3>신고 처리</h3>
            {request.data.report.status === 'RECEIVED' ? (
              <>
                <p>{COPY.reviewHint}</p>
                <div css={compactActionsStyle}>
                  <ActionButton
                    label="처리 완료"
                    disabled={stale || reading}
                    onClick={(): void => review('RESOLVED')}
                  />
                  <ActionButton
                    label="반려"
                    variant="secondary"
                    disabled={stale || reading}
                    onClick={(): void => review('DISMISSED')}
                  />
                </div>
              </>
            ) : (
              <dl css={metadataStyle}>
                <dt>처리 사유</dt>
                <dd css={contentStyle}>
                  {request.data.report.reviewReason ?? '처리 사유 없음'}
                </dd>
                <dt>처리자 ID</dt>
                <dd>{request.data.report.reviewedBy ?? '삭제된 관리자'}</dd>
                {request.data.report.reviewedAt && (
                  <>
                    <dt>처리 일시</dt>
                    <dd>
                      <time dateTime={request.data.report.reviewedAt}>
                        {new Date(
                          request.data.report.reviewedAt,
                        ).toLocaleString('ko-KR')}
                      </time>
                    </dd>
                  </>
                )}
              </dl>
            )}
          </section>
          {request.data.report.targetType !== 'USER' && (
            <AdminModerationPanel
              key={`content-${request.data.report.targetType}-${request.data.report.targetId}`}
              targetType={request.data.report.targetType}
              targetId={request.data.report.targetId}
              available={
                request.data.target.exists &&
                !request.data.target.deleted &&
                !request.data.target.parentDeleted
              }
              onChanged={reload}
            />
          )}
          <AdminModerationPanel
            key={`user-${request.data.report.targetUserId}`}
            targetType="USER"
            targetId={request.data.report.targetUserId}
            available={
              request.data.target.authorId !== null &&
              !request.data.target.authorDeleted
            }
          />
        </>
      )}
      <div
        css={feedback ? errorBoxStyle : undefined}
        role={feedback ? 'alert' : undefined}
      >
        {feedback && <p>{feedback}</p>}
        <div css={compactActionsStyle}>
          <ActionButton
            label={COPY.retry}
            variant="secondary"
            icon={<img src={refreshIcon} alt="" aria-hidden="true" />}
            disabled={reading}
            onClick={reload}
          />
        </div>
      </div>
      <AdminReasonModal ref={dialogRef} />
    </div>
  );
};
const panelStyle = css`
  display: grid;
  gap: 16px;
`;
const columnsStyle = css`
  display: grid;
  grid-template-columns: minmax(260px, 0.8fr) minmax(320px, 1.2fr);
  gap: 20px;
  align-items: start;
  @media (max-width: 980px) {
    grid-template-columns: minmax(0, 1fr);
  }
`;
const toolbarStyle = css`
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
`;
const filterStyle = (active: boolean) => css`
  padding: 10px 18px;
  border: 1px solid ${theme.colors.stroke.outline};
  border-radius: 20px;
  color: ${active ? theme.colors.foreground.brand : theme.colors.foreground.neutral};
  background: ${active ? theme.colors.background.brandWeak : theme.colors.background.surface};
  cursor: pointer;
`;
const cardStyle = css`
  display: grid;
  gap: 12px;
  padding: 16px;
  border: 1px solid ${theme.colors.stroke.outline};
  border-radius: 16px;
  background: ${theme.colors.background.surface};
  h2,
  h3 {
    font-size: 18px;
    font-weight: 700;
  }
  p,
  small {
    overflow-wrap: anywhere;
  }
`;
const detailStyle = css`
  display: grid;
  gap: 12px;
  min-width: 0;
`;
const listStyle = css`
  display: grid;
  gap: 8px;
`;
const reportButtonStyle = (active: boolean) => css`
  display: grid;
  gap: 8px;
  width: 100%;
  padding: 14px;
  border: 1px solid
    ${active ? theme.colors.stroke.brandSolid : theme.colors.stroke.outline};
  border-radius: 12px;
  background: ${active ? theme.colors.background.brandWeak : theme.colors.background.surface};
  color: ${theme.colors.foreground.neutral};
  text-align: left;
  cursor: pointer;
  small,
  span {
    color: ${theme.colors.foreground.neutralMuted};
    overflow-wrap: anywhere;
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
    height: 40px;
    padding: 8px 12px;
    font-size: 14px;
    line-height: 20px;
    white-space: nowrap;
  }
  span {
    flex-shrink: 0;
    white-space: nowrap;
  }
`;
const emptyStyle = css`
  padding: 24px;
  color: ${theme.colors.foreground.neutralMuted};
`;
const contentStyle = css`
  white-space: pre-wrap;
  overflow-wrap: anywhere;
`;
const imageStyle = css`
  width: 100%;
  max-height: 500px;
  object-fit: contain;
  border-radius: 12px;
`;

const metadataStyle = css`
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr);
  gap: 10px 12px;
  font-size: 14px;
  line-height: 1.5;
  dt {
    color: ${theme.colors.foreground.neutralMuted};
  }
  dd {
    min-width: 0;
    overflow-wrap: anywhere;
  }
  @media (max-width: 480px) {
    grid-template-columns: 80px minmax(0, 1fr);
    gap: 8px 10px;
  }
`;
const identifiersStyle = css`
  padding-top: 12px;
  border-top: 1px solid ${theme.colors.stroke.divider};
  summary {
    width: max-content;
    color: ${theme.colors.foreground.neutralMuted};
    font-size: 13px;
    cursor: pointer;
  }
  &[open] summary {
    margin-bottom: 12px;
  }
`;
const compactActionsStyle = css`
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  button {
    width: max-content;
    max-width: 100%;
    height: 40px;
    padding: 8px 16px;
    font-size: 14px;
    line-height: 20px;
  }
  button > span {
    width: 20px;
    height: 20px;
  }
`;
const errorBoxStyle = css`
  display: grid;
  gap: 10px;
  padding: 12px;
  border-radius: 8px;
  background: ${theme.colors.background.criticalWeak};
  color: ${theme.colors.foreground.neutral};
  border-left: 3px solid ${theme.colors.stroke.critical};
  font-size: 14px;
  line-height: 1.5;
  overflow-wrap: anywhere;
`;
