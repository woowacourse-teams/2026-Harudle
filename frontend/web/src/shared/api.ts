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
  400 | 401 | 403 | 404 | 404 | 409 | 409 | 429 | 502 | 503 | 504;

export interface ErrorDetail {
  field: string;
  reason: string;
}

export interface ProblemDetails {
  readonly type: string;
  readonly title: string;
  readonly status: ErrorStatus;
  readonly detail: string;
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
    [400, 401, 403, 404, 409, 429, 502, 503, 504].includes(value)
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

export class RequestError extends Error {
  constructor(public readonly problem: ProblemDetails) {
    const displayErrorMessage = problem.errors
      ? problem.errors[0].reason
      : problem.detail;
    super(displayErrorMessage);
  }
}
