export const API_BASE_URL = '/api/v1';

interface ApiRequestIdle {
  status: 'idle';
}

interface ApiRequestLoading {
  status: 'loading';
}

interface ApiRequestSuccess<T> {
  status: 'success';
  data: T;
}

interface ApiRequestError {
  status: 'error';
  error: Error;
}

export type ApiRequest<T> =
  ApiRequestIdle | ApiRequestLoading | ApiRequestSuccess<T> | ApiRequestError;

export type ApiRequestStatus = ApiRequest<void>['status'];

export type ErrorStatus =
  400 | 401 | 403 | 404 | 409 | 413 | 415 | 429 | 500 | 502 | 503 | 504;

export interface ErrorDetail {
  field: string;
  reason: string;
}

export interface ProblemDetails {
  readonly type: string;
  readonly title: string;
  readonly status: ErrorStatus;
  readonly detail: string;
  readonly action?: unknown;
  readonly instance: string;
  readonly code: string; // TODO: 정확하게
  readonly traceId?: string;
  readonly errors?: ErrorDetail[];
}

const isRecord = (value: unknown): value is Record<string, unknown> => {
  return typeof value === 'object' && value !== null;
};

const isErrorStatus = (value: unknown): value is ErrorStatus => {
  return (
    typeof value === 'number' &&
    [400, 401, 403, 404, 409, 413, 415, 429, 500, 502, 503, 504].includes(value)
  );
};

const isErrorDetail = (value: unknown): value is ErrorDetail => {
  return (
    isRecord(value) &&
    typeof value.field === 'string' &&
    typeof value.reason === 'string'
  );
};

export const isProblemDetails = (value: unknown): value is ProblemDetails => {
  return (
    isRecord(value) &&
    typeof value.type === 'string' &&
    typeof value.title === 'string' &&
    isErrorStatus(value.status) &&
    typeof value.detail === 'string' &&
    typeof value.instance === 'string' &&
    typeof value.code === 'string' &&
    (value.traceId === undefined || typeof value.traceId === 'string') &&
    (value.errors === undefined ||
      (Array.isArray(value.errors) && value.errors.every(isErrorDetail)))
  );
};

/** 응답의 action이 화면에 보여줄 수 있는 안내 문구이면 반환한다. */
export const getProblemAction = (
  problem: ProblemDetails | undefined,
): string | undefined => {
  return typeof problem?.action === 'string' && problem.action.trim()
    ? problem.action
    : undefined;
};

/**
 * RequestError 메시지와 다를 때만 별도로 보여줄 행동 안내를 반환한다.
 * RequestError는 action이 있으면 그 문구를 메시지로 쓰므로 같은 안내를 두 번 보여주지 않는다.
 */
export const getAdditionalProblemAction = (
  problem: ProblemDetails | undefined,
  displayedMessage: string,
): string | undefined => {
  const action = getProblemAction(problem);
  return action !== displayedMessage ? action : undefined;
};

export class RequestError extends Error {
  constructor(public readonly problem: ProblemDetails) {
    const displayErrorMessage =
      getProblemAction(problem) ??
      problem.errors?.[0]?.reason ??
      problem.detail;
    super(displayErrorMessage);
  }
}
