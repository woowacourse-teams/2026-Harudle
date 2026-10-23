import { css } from '@emotion/react';
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
        <>
          <p role="alert">{COPY.listError}</p>
          <ActionButton
            label={COPY.retry}
            variant="secondary"
            onClick={(): void => {
              setRequest({ status: 'loading' });
              setRevision((value): number => value + 1);
            }}
          />
        </>
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
            <button
              type="button"
              disabled={page === 0}
              onClick={(): void => onPage(page - 1)}
            >
              이전 페이지
            </button>
            <span>{page + 1}쪽</span>
            <button
              type="button"
              disabled={!request.data.hasNext}
              onClick={(): void => onPage(page + 1)}
            >
              다음 페이지
            </button>
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
  return (
    <div css={detailStyle} aria-label="신고 상세">
      {request.status === 'error' && <p role="alert">{COPY.detailError}</p>}
      {request.status === 'success' && (
        <>
          <section css={cardStyle}>
            <h2>
              신고 상세 · {REPORT_STATUS_LABELS[request.data.report.status]}
            </h2>
            <p>
              대상 {REPORT_TARGET_LABELS[request.data.report.targetType]} ·{' '}
              {request.data.report.targetId}
            </p>
            <p>신고자 {request.data.report.reporterId ?? '탈퇴한 사용자'}</p>
            <p>사유 {REPORT_REASON_LABELS[request.data.report.reason]}</p>
            <p css={contentStyle}>
              {request.data.report.description || '상세 내용 없음'}
            </p>
            <small>
              접수{' '}
              {new Date(request.data.report.createdAt).toLocaleString('ko-KR')}
            </small>
          </section>
          <section css={cardStyle}>
            <h3>신고 대상</h3>
            <p>
              작성자 {request.data.target.authorNickname ?? '탈퇴한 사용자'}
            </p>
            <p>
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
            </p>
            {request.data.target.title && (
              <strong>{request.data.target.title}</strong>
            )}
            {request.data.target.imageUrl && (
              <img
                css={imageStyle}
                src={request.data.target.imageUrl}
                alt="신고 대상 피드 이미지"
              />
            )}
            {request.data.target.commentContent && (
              <p css={contentStyle}>{request.data.target.commentContent}</p>
            )}
          </section>
          <section css={cardStyle}>
            <h3>신고 처리</h3>
            {request.data.report.status === 'RECEIVED' ? (
              <>
                <p>{COPY.reviewHint}</p>
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
              </>
            ) : (
              <>
                <p>{request.data.report.reviewReason ?? '처리 사유 없음'}</p>
                <small>
                  처리자 {request.data.report.reviewedBy ?? '삭제된 관리자'} ·{' '}
                  {request.data.report.reviewedAt &&
                    new Date(request.data.report.reviewedAt).toLocaleString(
                      'ko-KR',
                    )}
                </small>
              </>
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
      {(refreshError || stale) && (
        <p role="alert">
          {refreshError
            ? COPY.refreshError
            : '최신 상태를 다시 불러온 뒤 처리해 주세요.'}
        </p>
      )}
      <button type="button" disabled={reading} onClick={reload}>
        {COPY.retry}
      </button>
      <AdminReasonModal ref={dialogRef} />
    </div>
  );
};
const panelStyle = css`
  display: grid;
  gap: 20px;
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
  padding: 20px;
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
  gap: 16px;
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
  justify-content: space-between;
  gap: 8px;
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
