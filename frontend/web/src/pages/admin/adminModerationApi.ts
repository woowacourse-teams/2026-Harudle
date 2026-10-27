import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { authFetch, requestCsrfToken } from '../../shared/auth';
import { isRecord } from '../../shared/utils';

export type ReportStatus = 'RECEIVED' | 'DISMISSED' | 'RESOLVED';
export type ModerationTargetType = 'USER' | 'FEED' | 'COMMENT';
export type ReportReason =
  'SPAM' | 'HARASSMENT' | 'INAPPROPRIATE_CONTENT' | 'OTHER';
export interface AdminPageResponse<T> {
  readonly content: readonly T[];
  readonly page: number;
  readonly size: number;
  readonly totalElements: number;
  readonly totalPages: number;
  readonly hasNext: boolean;
}
export interface AdminReportSummary {
  readonly id: string;
  readonly reporterId: string | null;
  readonly targetType: ModerationTargetType;
  readonly targetId: string;
  readonly targetUserId: string;
  readonly reason: ReportReason;
  readonly status: ReportStatus;
  readonly createdAt: string;
  readonly reviewedAt: string | null;
}
export interface AdminReport extends AdminReportSummary {
  readonly description: string;
  readonly reviewedBy: string | null;
  readonly reviewReason: string | null;
}
export interface AdminReportTarget {
  readonly exists: boolean;
  readonly authorId: string | null;
  readonly authorNickname: string | null;
  readonly authorDeleted: boolean;
  readonly deleted: boolean;
  readonly parentDeleted: boolean;
  readonly hidden: boolean;
  readonly parentHidden: boolean;
  readonly publiclyVisible: boolean;
  readonly feedId: string | null;
  readonly title: string | null;
  readonly imageUrl: string | null;
  readonly imageUrlExpiresAt: string | null;
  readonly commentContent: string | null;
}
export interface AdminReportDetail {
  readonly report: AdminReport;
  readonly target: AdminReportTarget;
}
export interface AdminReviewResult {
  readonly id: string;
  readonly status: Exclude<ReportStatus, 'RECEIVED'>;
  readonly reviewedBy: string | null;
  readonly reviewedAt: string | null;
  readonly reviewReason: string | null;
}
export interface ModerationState {
  readonly targetType: ModerationTargetType;
  readonly targetId: string;
  readonly restricted: boolean;
  readonly version: number;
  readonly reason: string | null;
  readonly actorId: string | null;
  readonly changedAt: string | null;
}
export interface ModerationHistory extends ModerationState {
  readonly id: string;
  readonly reason: string;
  readonly actorId: string | null;
  readonly changedAt: string;
}
const isId = (value: unknown): value is string =>
  typeof value === 'string' && value.length > 0;
const isDate = (value: unknown): value is string =>
  typeof value === 'string' && Number.isFinite(Date.parse(value));
const isNullableString = (value: unknown): value is string | null =>
  value === null || typeof value === 'string';
const isNullableDate = (value: unknown): value is string | null =>
  value === null || isDate(value);
const isCount = (value: unknown): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
export const isReportStatus = (value: unknown): value is ReportStatus =>
  value === 'RECEIVED' || value === 'DISMISSED' || value === 'RESOLVED';
export const isModerationTargetType = (
  value: unknown,
): value is ModerationTargetType =>
  value === 'USER' || value === 'FEED' || value === 'COMMENT';
const isReportReason = (value: unknown): value is ReportReason =>
  value === 'SPAM' ||
  value === 'HARASSMENT' ||
  value === 'INAPPROPRIATE_CONTENT' ||
  value === 'OTHER';
export const isAdminReportSummary = (
  value: unknown,
): value is AdminReportSummary =>
  isRecord(value) &&
  isId(value.id) &&
  isNullableString(value.reporterId) &&
  isModerationTargetType(value.targetType) &&
  isId(value.targetId) &&
  isId(value.targetUserId) &&
  isReportReason(value.reason) &&
  isReportStatus(value.status) &&
  isDate(value.createdAt) &&
  isNullableDate(value.reviewedAt);
export const isAdminReportDetail = (
  value: unknown,
): value is AdminReportDetail => {
  if (
    !isRecord(value) ||
    !isAdminReportSummary(value.report) ||
    !isRecord(value.report) ||
    !isRecord(value.target)
  )
    return false;
  const report = value.report;
  const target = value.target;
  return (
    typeof report.description === 'string' &&
    isNullableString(report.reviewedBy) &&
    isNullableString(report.reviewReason) &&
    typeof target.exists === 'boolean' &&
    isNullableString(target.authorId) &&
    isNullableString(target.authorNickname) &&
    typeof target.authorDeleted === 'boolean' &&
    typeof target.deleted === 'boolean' &&
    typeof target.parentDeleted === 'boolean' &&
    typeof target.hidden === 'boolean' &&
    typeof target.parentHidden === 'boolean' &&
    typeof target.publiclyVisible === 'boolean' &&
    isNullableString(target.feedId) &&
    isNullableString(target.title) &&
    isNullableString(target.imageUrl) &&
    isNullableDate(target.imageUrlExpiresAt) &&
    isNullableString(target.commentContent)
  );
};
export const isModerationState = (value: unknown): value is ModerationState =>
  isRecord(value) &&
  isModerationTargetType(value.targetType) &&
  isId(value.targetId) &&
  typeof value.restricted === 'boolean' &&
  isCount(value.version) &&
  isNullableString(value.reason) &&
  isNullableString(value.actorId) &&
  isNullableDate(value.changedAt);
const isModerationHistory = (value: unknown): value is ModerationHistory =>
  isModerationState(value) &&
  isRecord(value) &&
  isId(value.id) &&
  typeof value.reason === 'string' &&
  (value.actorId === null || isId(value.actorId)) &&
  isDate(value.changedAt);
const isReviewResult = (value: unknown): value is AdminReviewResult =>
  isRecord(value) &&
  isId(value.id) &&
  (value.status === 'DISMISSED' || value.status === 'RESOLVED') &&
  (value.reviewedBy === null || isId(value.reviewedBy)) &&
  isNullableDate(value.reviewedAt) &&
  isNullableString(value.reviewReason);
export const isAdminPageResponse = <T>(
  value: unknown,
  isItem: (item: unknown) => item is T,
): value is AdminPageResponse<T> =>
  isRecord(value) &&
  Array.isArray(value.content) &&
  value.content.every(isItem) &&
  isCount(value.page) &&
  isCount(value.size) &&
  value.size > 0 &&
  isCount(value.totalElements) &&
  isCount(value.totalPages) &&
  typeof value.hasNext === 'boolean';
const readJson = async (response: Response): Promise<unknown> => {
  const data: unknown = await response.json().catch((): null => null);
  if (!response.ok) {
    if (isProblemDetails(data)) throw new RequestError(data);
    throw new Error('관리자 요청을 처리하지 못했어요. 다시 시도해 주세요.');
  }
  return data;
};
const checked = <T>(
  value: unknown,
  guard: (value: unknown) => value is T,
): T => {
  if (!guard(value))
    throw new Error('관리자 응답을 확인하지 못했어요. 다시 불러와 주세요.');
  return value;
};
const get = async (path: string, signal?: AbortSignal): Promise<unknown> =>
  readJson(await authFetch(`${API_BASE_URL}/admin/${path}`, { signal }));
const put = async (path: string, body: unknown): Promise<unknown> => {
  const csrf = await requestCsrfToken();
  return readJson(
    await authFetch(`${API_BASE_URL}/admin/${path}`, {
      method: 'PUT',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf },
      body: JSON.stringify(body),
    }),
  );
};
export const isValidAdminReason = (reason: string): boolean => {
  const length = Array.from(reason.trim()).length;
  return length >= 1 && length <= 500;
};
const normalizedReason = (reason: string): string => {
  if (!isValidAdminReason(reason))
    throw new Error('처리 사유를 1~500자로 입력해 주세요.');
  return reason.trim();
};
export const getAdminReports = async (
  status: ReportStatus,
  page = 0,
  signal?: AbortSignal,
): Promise<AdminPageResponse<AdminReportSummary>> => {
  const params = new URLSearchParams({
    status,
    page: String(page),
    size: '20',
  });
  const data = await get(`reports?${params}`, signal);
  return checked(
    data,
    (value): value is AdminPageResponse<AdminReportSummary> =>
      isAdminPageResponse(value, isAdminReportSummary),
  );
};
export const getAdminReport = async (
  id: string,
  signal?: AbortSignal,
): Promise<AdminReportDetail> => {
  const result = checked(
    await get(`reports/${encodeURIComponent(id)}`, signal),
    isAdminReportDetail,
  );
  if (result.report.id !== id)
    throw new Error('다른 신고의 정보예요. 다시 불러와 주세요.');
  return result;
};
export const reviewAdminReport = async (
  id: string,
  status: Exclude<ReportStatus, 'RECEIVED'>,
  reason: string,
): Promise<AdminReviewResult> => {
  const result = checked(
    await put(`reports/${encodeURIComponent(id)}/review`, {
      status,
      reason: normalizedReason(reason),
    }),
    isReviewResult,
  );
  if (result.id !== id || result.status !== status)
    throw new Error(
      '신고 처리 결과가 요청과 일치하지 않아요. 다시 불러와 주세요.',
    );
  return result;
};
const moderationPath = (type: ModerationTargetType, id: string): string =>
  `moderation/${type}/${encodeURIComponent(id)}`;
export const getAdminModeration = async (
  type: ModerationTargetType,
  id: string,
  signal?: AbortSignal,
): Promise<ModerationState> => {
  const result = checked(
    await get(moderationPath(type, id), signal),
    isModerationState,
  );
  if (result.targetType !== type || result.targetId !== id)
    throw new Error('다른 대상의 조치 정보예요. 다시 불러와 주세요.');
  return result;
};
export const setAdminModeration = async (
  type: ModerationTargetType,
  id: string,
  restricted: boolean,
  expectedVersion: number,
  reason: string,
): Promise<ModerationState> => {
  const result = checked(
    await put(moderationPath(type, id), {
      restricted,
      expectedVersion,
      reason: normalizedReason(reason),
    }),
    isModerationState,
  );
  if (
    result.targetType !== type ||
    result.targetId !== id ||
    result.restricted !== restricted
  )
    throw new Error('조치 결과를 확인하지 못했어요. 다시 불러와 주세요.');
  return result;
};
export const getAdminModerationHistory = async (
  type: ModerationTargetType,
  id: string,
  page = 0,
  signal?: AbortSignal,
): Promise<AdminPageResponse<ModerationHistory>> => {
  const result = checked(
    await get(
      `${moderationPath(type, id)}/history?${new URLSearchParams({ page: String(page), size: '20' })}`,
      signal,
    ),
    (value): value is AdminPageResponse<ModerationHistory> =>
      isAdminPageResponse(value, isModerationHistory),
  );
  if (
    result.content.some(
      (entry): boolean => entry.targetType !== type || entry.targetId !== id,
    )
  )
    throw new Error('다른 대상의 조치 이력이에요. 다시 불러와 주세요.');
  return result;
};
