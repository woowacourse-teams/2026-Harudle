const ERROR_MESSAGE_BY_CODE: Record<string, string> = {
  VALIDATION_ERROR: '입력 내용을 다시 확인해주세요.',
  INVALID_IDEMPOTENCY_KEY: '요청 정보가 올바르지 않습니다. 다시 시도해주세요.',
  UNAUTHORIZED: '로그인이 만료되었습니다. 다시 로그인해주세요.',
  INVALID_REFRESH_TOKEN: '로그인이 만료되었습니다. 다시 로그인해주세요.',
  INVALID_CURRENT_USER:
    '사용자 정보를 확인할 수 없습니다. 다시 로그인해주세요.',
  FORBIDDEN: '접근할 수 없는 내용입니다.',
  API_NOT_FOUND: '요청한 기능을 찾을 수 없습니다.',
  DIARY_NOT_FOUND: '일기를 찾을 수 없습니다.',
  SHARE_NOT_FOUND: '공유된 일기를 찾을 수 없습니다.',
  METHOD_NOT_ALLOWED: '지원하지 않는 요청입니다.',
  NOT_ACCEPTABLE: '지원하지 않는 응답 형식입니다.',
  GENERATION_IN_PROGRESS: '같은 일기를 이미 만들고 있습니다.',
  GENERATION_FAILED: '완성된 그림일기가 없어 공유할 수 없습니다.',
  IDEMPOTENCY_KEY_CONFLICT: '요청이 중복되었습니다. 다시 시도해주세요.',
  PAYLOAD_TOO_LARGE: '전송할 내용의 크기가 너무 큽니다.',
  UNSUPPORTED_MEDIA_TYPE: '지원하지 않는 요청 형식입니다.',
  DAILY_GENERATION_LIMIT_EXCEEDED: '오늘 생성 가능한 횟수를 모두 사용했습니다.',
  INTERNAL_SERVER_ERROR: '잠시 문제가 발생했습니다. 다시 시도해주세요.',
  AI_PROVIDER_ERROR: '그림일기를 만드는 중 문제가 발생했습니다.',
  GENERATION_UNAVAILABLE: '현재 그림일기를 만들 수 없습니다.',
  GENERATION_INTERRUPTED: '그림일기 만들기가 중단되었습니다.',
  IMAGE_STORAGE_ERROR: '이미지를 저장하거나 불러오지 못했습니다.',
  AI_PROVIDER_TIMEOUT: '그림일기 생성 시간이 초과되었습니다.',
};

interface ProblemDetails {
  code: string;
  status: number;
  traceId?: string;
}

const isRecord = (value: unknown): value is Record<string, unknown> => {
  return typeof value === 'object' && value !== null;
};

const isProblemDetails = (value: unknown): value is ProblemDetails => {
  return (
    isRecord(value) &&
    typeof value.code === 'string' &&
    typeof value.status === 'number' &&
    (value.traceId === undefined || typeof value.traceId === 'string')
  );
};

export class ApiError extends Error {
  constructor(
    message: string,
    readonly code: string,
    readonly status: number,
    readonly traceId?: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export const createApiError = async (
  response: Response,
  fallbackMessage: string,
): Promise<ApiError> => {
  try {
    const body: unknown = await response.json();

    if (isProblemDetails(body)) {
      return new ApiError(
        ERROR_MESSAGE_BY_CODE[body.code] ?? fallbackMessage,
        body.code,
        body.status,
        body.traceId,
      );
    }
  } catch {
    // JSON이 아닌 오류 응답은 상태 코드와 화면별 기본 메시지로 처리한다.
  }

  return new ApiError(
    fallbackMessage,
    `HTTP_${response.status}`,
    response.status,
  );
};

export const throwIfResponseFailed = async (
  response: Response,
  fallbackMessage: string,
): Promise<void> => {
  if (!response.ok) {
    throw await createApiError(response, fallbackMessage);
  }
};

export const toUserError = (error: unknown, fallbackMessage: string): Error => {
  if (error instanceof ApiError) {
    return error;
  }

  if (error instanceof TypeError) {
    return new Error('네트워크 연결을 확인해주세요.');
  }

  return new Error(fallbackMessage);
};
