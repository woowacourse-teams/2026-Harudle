import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';

export interface GuestDiaryRequest {
  diaryDate: string;
  sourceText: string;
}

export interface GuestDiaryResponse {
  id: string;
  diaryDate: string;
  sourceText: string;
  createdAt: string;
  generation: {
    id: string;
    status: 'SUCCEEDED';
    title: string;
    imageUrl: string;
    imageUrlExpiresAt: string;
    completedAt: string;
  };
}

interface GuestCsrfTokenResponse {
  token: string;
}

const isRecord = (value: unknown): value is Record<string, unknown> => {
  return typeof value === 'object' && value !== null;
};

export const isGuestCsrfTokenResponse = (
  value: unknown,
): value is GuestCsrfTokenResponse => {
  return isRecord(value) && typeof value.token === 'string';
};

export const isGuestDiaryResponse = (
  value: unknown,
): value is GuestDiaryResponse => {
  if (!isRecord(value) || !isRecord(value.generation)) {
    return false;
  }

  const { generation } = value;

  return (
    typeof value.id === 'string' &&
    typeof value.diaryDate === 'string' &&
    typeof value.sourceText === 'string' &&
    typeof value.createdAt === 'string' &&
    typeof generation.id === 'string' &&
    generation.status === 'SUCCEEDED' &&
    typeof generation.title === 'string' &&
    typeof generation.imageUrl === 'string' &&
    typeof generation.imageUrlExpiresAt === 'string' &&
    typeof generation.completedAt === 'string'
  );
};

const throwRequestError = async (
  response: Response,
  fallbackMessage: string,
): Promise<never> => {
  const data: unknown = await response.json().catch(() => null);

  if (isProblemDetails(data)) {
    throw new RequestError(data);
  }

  throw new Error(fallbackMessage);
};

export const requestGuestCsrfToken = async (): Promise<string> => {
  const response = await fetch(`${API_BASE_URL}/auth/csrf`, {
    credentials: 'include',
  });

  if (!response.ok) {
    return throwRequestError(response, 'CSRF Token 발급에 실패했습니다');
  }

  const data: unknown = await response.json();

  if (!isGuestCsrfTokenResponse(data)) {
    throw new Error('CSRF Token 응답 형식이 일치하지 않습니다');
  }

  return data.token;
};

export const issueGuestSession = async (): Promise<void> => {
  const csrfToken = await requestGuestCsrfToken();
  const response = await fetch(`${API_BASE_URL}/guest/session`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'X-XSRF-TOKEN': csrfToken,
    },
  });

  if (!response.ok) {
    return throwRequestError(response, '게스트 세션 발급에 실패했습니다');
  }
};

export const createGuestDiaryIdempotencyKey = (): string => {
  return crypto.randomUUID();
};

export const createGuestDiary = async ({
  request,
  idempotencyKey,
}: {
  request: GuestDiaryRequest;
  idempotencyKey: string;
}): Promise<GuestDiaryResponse> => {
  const csrfToken = await requestGuestCsrfToken();
  const response = await fetch(`${API_BASE_URL}/guest/diaries`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
      'X-XSRF-TOKEN': csrfToken,
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    return throwRequestError(response, '게스트 일기 생성에 실패했습니다');
  }

  const data: unknown = await response.json();

  if (!isGuestDiaryResponse(data)) {
    throw new Error('게스트 일기 응답 형식이 일치하지 않습니다');
  }

  return data;
};

export const getGuestDiary = async (
  diaryId: string,
): Promise<GuestDiaryResponse> => {
  const response = await fetch(`${API_BASE_URL}/guest/diaries/${diaryId}`, {
    credentials: 'include',
  });

  if (!response.ok) {
    return throwRequestError(response, '게스트 일기 조회에 실패했습니다');
  }

  const data: unknown = await response.json();

  if (!isGuestDiaryResponse(data)) {
    throw new Error('게스트 일기 응답 형식이 일치하지 않습니다');
  }

  return data;
};
