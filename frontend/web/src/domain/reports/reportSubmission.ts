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

export interface ReportRequest extends ReportTarget {
  readonly reason: ReportReason;
  readonly description?: string;
}

export interface ReportResponse {
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

export const isReportRequest = (value: unknown): value is ReportRequest =>
  isRecord(value) &&
  isReportTargetType(value.targetType) &&
  isUuid(value.targetId) &&
  isReportReason(value.reason) &&
  (value.description === undefined ||
    (typeof value.description === 'string' &&
      isValidReportDescription(value.description)));

export const isReportResponse = (value: unknown): value is ReportResponse =>
  isRecord(value) &&
  isUuid(value.id) &&
  value.status === 'RECEIVED' &&
  typeof value.duplicate === 'boolean';

export const submitReport = async (
  request: ReportRequest,
): Promise<ReportResponse> => {
  if (!isReportRequest(request)) throw new Error(REPORT_ERRORS.invalid);
  const description = request.description?.trim();
  const body = JSON.stringify({
    targetType: request.targetType,
    targetId: request.targetId,
    reason: request.reason,
    ...(description ? { description } : {}),
  });
  const send = async (): Promise<Response> => {
    const csrfToken = await requestCsrfToken();
    return authFetch(`${API_BASE_URL}/reports`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': csrfToken,
      },
      body,
    });
  };
  let response = await send();
  let data: unknown = await response.json().catch((): null => null);
  // 토큰 복원 중 CSRF 쿠키가 교체된 경우에만 저장 전 거절된 요청을 한 번 재시도한다.
  if (
    response.status === 403 &&
    isProblemDetails(data) &&
    data.status === 403 &&
    data.code === 'INVALID_CSRF_TOKEN'
  ) {
    response = await send();
    data = await response.json().catch((): null => null);
  }
  if (!response.ok) {
    if (isProblemDetails(data)) throw new RequestError(data);
    throw new Error(REPORT_ERRORS.failed);
  }
  if (
    !isReportResponse(data) ||
    !(
      (response.status === 201 && !data.duplicate) ||
      (response.status === 200 && data.duplicate)
    )
  ) {
    throw new Error(REPORT_ERRORS.response);
  }
  return data;
};
