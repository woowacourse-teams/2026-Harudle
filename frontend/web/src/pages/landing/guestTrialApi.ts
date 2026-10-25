import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import {
  isDiaryGeneration,
  type DiaryGeneration,
  type DiaryGenerationSuccess,
} from '../../domain/diary/diaryGeneration';

export interface GuestDiaryRequest {
  diaryDate: string;
  sourceText: string;
}

interface GuestDiaryApiResponse {
  id: string;
  diaryDate: string;
  sourceText: string;
  createdAt: string;
  generation: DiaryGeneration;
}

export interface GuestDiaryResponse extends GuestDiaryApiResponse {
  generation: DiaryGenerationSuccess;
}

interface GuestCsrfTokenResponse {
  token: string;
}

const GUEST_DIARY_POLL_INTERVAL_MS = 2_000;
const GUEST_DIARY_MAX_POLL_ATTEMPTS = 60;

const isRecord = (value: unknown): value is Record<string, unknown> => {
  return typeof value === 'object' && value !== null;
};

export const isGuestCsrfTokenResponse = (
  value: unknown,
): value is GuestCsrfTokenResponse => {
  return isRecord(value) && typeof value.token === 'string';
};

const isGuestDiaryApiResponse = (
  value: unknown,
): value is GuestDiaryApiResponse => {
  return (
    isRecord(value) &&
    typeof value.id === 'string' &&
    typeof value.diaryDate === 'string' &&
    typeof value.sourceText === 'string' &&
    typeof value.createdAt === 'string' &&
    isDiaryGeneration(value.generation)
  );
};

export const isGuestDiaryResponse = (
  value: unknown,
): value is GuestDiaryResponse => {
  return (
    isGuestDiaryApiResponse(value) && value.generation.status === 'SUCCEEDED'
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
    return throwRequestError(
      response,
      ERROR_MESSAGES.GUEST_CSRF_TOKEN_ISSUANCE_FAILED,
    );
  }

  const data: unknown = await response.json();

  if (!isGuestCsrfTokenResponse(data)) {
    throw new Error(ERROR_MESSAGES.INVALID_GUEST_CSRF_TOKEN_RESPONSE);
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
    return throwRequestError(
      response,
      ERROR_MESSAGES.GUEST_SESSION_ISSUANCE_FAILED,
    );
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
    return throwRequestError(
      response,
      ERROR_MESSAGES.GUEST_DIARY_GENERATION_FAILED,
    );
  }

  const data: unknown = await response.json();

  if (!isGuestDiaryApiResponse(data)) {
    throw new Error(ERROR_MESSAGES.INVALID_GUEST_DIARY_RESPONSE);
  }

  return waitForGuestDiary(data);
};

export const getGuestDiary = async (
  diaryId: string,
): Promise<GuestDiaryResponse> => {
  return waitForGuestDiary(await fetchGuestDiary(diaryId));
};

const fetchGuestDiary = async (
  diaryId: string,
): Promise<GuestDiaryApiResponse> => {
  const response = await fetch(`${API_BASE_URL}/guest/diaries/${diaryId}`, {
    credentials: 'include',
  });

  if (!response.ok) {
    return throwRequestError(response, ERROR_MESSAGES.GUEST_DIARY_FETCH_FAILED);
  }

  const data: unknown = await response.json();

  if (!isGuestDiaryApiResponse(data)) {
    throw new Error(ERROR_MESSAGES.INVALID_GUEST_DIARY_RESPONSE);
  }

  return data;
};

const waitForGuestDiary = async (
  initialDiary: GuestDiaryApiResponse,
): Promise<GuestDiaryResponse> => {
  let diary = initialDiary;

  for (
    let attempt = 0;
    attempt <= GUEST_DIARY_MAX_POLL_ATTEMPTS;
    attempt += 1
  ) {
    if (diary.generation.status === 'SUCCEEDED') {
      return { ...diary, generation: diary.generation };
    }

    if (attempt === GUEST_DIARY_MAX_POLL_ATTEMPTS) {
      break;
    }

    await new Promise<void>((resolve): void => {
      setTimeout(resolve, GUEST_DIARY_POLL_INTERVAL_MS);
    });
    diary = await fetchGuestDiary(diary.id);
  }

  throw new Error(ERROR_MESSAGES.GUEST_DIARY_RESULT_FETCH_FAILED);
};
