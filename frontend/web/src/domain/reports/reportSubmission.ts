import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { authFetch, requestCsrfToken } from '../../shared/auth';
import { isRecord } from '../../shared/utils';

export type ReportTargetType = 'USER' | 'FEED' | 'COMMENT';
export type ReportReason =
  'SPAM' | 'HARASSMENT' | 'INAPPROPRIATE_CONTENT' | 'OTHER';

export interface ReportTarget {
  readonly targetType: ReportTargetType;
  readonly targetId: string;
}

export interface ReportSubmission extends ReportTarget {
  readonly reason: ReportReason;
  readonly description?: string;
}

export interface ReportReceipt {
  readonly id: string;
  readonly status: 'RECEIVED';
  readonly duplicate: boolean;
}

export const REPORT_ERRORS = {
  invalid: '신고 대상과 사유를 확인해 주세요.',
  description: '상세 내용은 500자 이하로 입력해 주세요.',
  failed: '신고를 접수하지 못했어요. 내용을 확인하고 다시 시도해 주세요.',
  response: '신고 접수 결과를 확인하지 못했어요. 다시 시도해 주세요.',
} as const;

const isUuid = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);

export const isReportTargetType = (value: unknown): value is ReportTargetType =>
  value === 'USER' || value === 'FEED' || value === 'COMMENT';

export const isReportReason = (value: unknown): value is ReportReason =>
  value === 'SPAM' ||
  value === 'HARASSMENT' ||
  value === 'INAPPROPRIATE_CONTENT' ||
  value === 'OTHER';

export const isValidReportDescription = (description: string): boolean =>
  Array.from(description.trim()).length <= 500;

export const isReportSubmission = (value: unknown): value is ReportSubmission =>
  isRecord(value) &&
  isReportTargetType(value.targetType) &&
  isUuid(value.targetId) &&
  isReportReason(value.reason) &&
  (value.description === undefined ||
    (typeof value.description === 'string' &&
      isValidReportDescription(value.description)));

export const isReportReceipt = (value: unknown): value is ReportReceipt =>
  isRecord(value) &&
  isUuid(value.id) &&
  value.status === 'RECEIVED' &&
  typeof value.duplicate === 'boolean';

const requestReportReceipt = async (
  body: string,
  canRetryCsrf: boolean,
): Promise<ReportReceipt> => {
  const csrfToken = await requestCsrfToken();
  const response = await authFetch(`${API_BASE_URL}/reports`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken },
    body,
  });
  if (!response.ok) {
    const data: unknown = await response.json().catch((): null => null);
    if (isProblemDetails(data)) {
      // Access Token 복원 중 CSRF 쿠키가 교체될 수 있다. 저장 전 거절이 확인된 경우만 한 번 재시도한다.
      if (
        canRetryCsrf &&
        response.status === 403 &&
        data.status === 403 &&
        data.code === 'INVALID_CSRF_TOKEN'
      ) {
        return requestReportReceipt(body, false);
      }
      throw new RequestError(data);
    }
    throw new Error(REPORT_ERRORS.failed);
  }
  const data: unknown = await response.json().catch((): null => null);
  if (
    !isReportReceipt(data) ||
    !(
      (response.status === 201 && !data.duplicate) ||
      (response.status === 200 && data.duplicate)
    )
  ) {
    throw new Error(REPORT_ERRORS.response);
  }
  return data;
};

export const submitReport = async (
  request: ReportSubmission,
): Promise<ReportReceipt> => {
  if (!isReportSubmission(request)) throw new Error(REPORT_ERRORS.invalid);
  const description = request.description?.trim();
  const body = JSON.stringify({
    targetType: request.targetType,
    targetId: request.targetId,
    reason: request.reason,
    ...(description ? { description } : {}),
  });
  return requestReportReceipt(body, true);
};
