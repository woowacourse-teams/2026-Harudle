import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { authFetch } from '../../shared/auth';
import { ERROR_MESSAGES } from '../../shared/errorMessage';

export const generateDiary = async ({
  diaryDate,
  sourceText,
  idempotencyKey,
}: DiaryGenerateRequest): Promise<DiaryGenerateResponse> => {
  const response = await authFetch(`${API_BASE_URL}/diaries`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
    },
    body: JSON.stringify({
      diaryDate,
      sourceText,
    }),
  });

  if (!response.ok) {
    const errorData: unknown = await response.json().catch(() => null);

    if (isProblemDetails(errorData)) {
      throw new RequestError(errorData);
    }

    throw new Error(ERROR_MESSAGES.DIARY_GENERATION_FAILED);
  }

  const data: unknown = await response.json();

  if (!isDiaryGenerateResponse(data)) {
    throw new Error(ERROR_MESSAGES.INVALID_DIARY_GENERATION_RESPONSE);
  }

  return data;
};

export interface DiaryGenerateRequest {
  diaryDate: string;
  sourceText: string;
  idempotencyKey: string;
}

export const isDiaryGenerateRequest = (
  value: unknown,
): value is DiaryGenerateRequest => {
  return (
    typeof value === 'object' &&
    value !== null &&
    'diaryDate' in value &&
    typeof value.diaryDate === 'string' &&
    'sourceText' in value &&
    typeof value.sourceText === 'string' &&
    'idempotencyKey' in value &&
    typeof value.idempotencyKey === 'string'
  );
};

export interface DiaryGenerateResponse {
  id: string;
  diaryDate: string;
  sourceText: string;
  createdAt: string;
  generation: {
    id: string;
    status: GenerationStatus;
    title: string | null;
    imageUrl: string | null;
    imageUrlExpiresAt: string | null;
    completedAt: string | null;
  };
  usage: {
    usageDate: string;
    usedCount: number;
    limitCount: number;
    remainingCount: number;
  };
}

export const isDiaryGenerateResponse = (
  value: unknown,
): value is DiaryGenerateResponse => {
  return (
    typeof value === 'object' &&
    value !== null &&
    'id' in value &&
    typeof value.id === 'string' &&
    'diaryDate' in value &&
    typeof value.diaryDate === 'string' &&
    'sourceText' in value &&
    typeof value.sourceText === 'string' &&
    'createdAt' in value &&
    typeof value.createdAt === 'string' &&
    'generation' in value &&
    typeof value.generation === 'object' &&
    value.generation !== null &&
    'id' in value.generation &&
    typeof value.generation.id === 'string' &&
    'status' in value.generation &&
    isGenerationStatus(value.generation.status) &&
    'title' in value.generation &&
    (typeof value.generation.title === 'string' ||
      value.generation.title === null) &&
    'imageUrl' in value.generation &&
    (typeof value.generation.imageUrl === 'string' ||
      value.generation.imageUrl === null) &&
    'imageUrlExpiresAt' in value.generation &&
    (typeof value.generation.imageUrlExpiresAt === 'string' ||
      value.generation.imageUrlExpiresAt === null) &&
    'completedAt' in value.generation &&
    (typeof value.generation.completedAt === 'string' ||
      value.generation.completedAt === null) &&
    'usage' in value &&
    typeof value.usage === 'object' &&
    value.usage !== null &&
    'usageDate' in value.usage &&
    typeof value.usage.usageDate === 'string' &&
    'usedCount' in value.usage &&
    typeof value.usage.usedCount === 'number' &&
    'limitCount' in value.usage &&
    typeof value.usage.limitCount === 'number' &&
    'remainingCount' in value.usage &&
    typeof value.usage.remainingCount === 'number'
  );
};

export type GenerationStatus = 'PROCESSING' | 'SUCCEEDED' | 'FAILED';

export const isGenerationStatus = (value: unknown): value is GenerationStatus =>
  value === 'PROCESSING' || value === 'SUCCEEDED' || value === 'FAILED';
