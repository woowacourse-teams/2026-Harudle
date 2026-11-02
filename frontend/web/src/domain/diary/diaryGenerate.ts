import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { authFetch } from '../../shared/auth';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import {
  isDiaryGeneration,
  isGenerationFailureCode,
  isGenerationStatus,
  type DiaryGeneration,
  type GenerationFailureCode,
  type GenerationStatus,
} from './diaryGeneration';

export const DIARY_GENERATION_ERROR_CODE = {
  DAILY_LIMIT_EXCEEDED: 'DAILY_GENERATION_LIMIT_EXCEEDED',
  IN_PROGRESS: 'GENERATION_IN_PROGRESS',
} as const;

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

export interface DiaryGenerateResponse {
  id: string;
  diaryDate: string;
  sourceText: string;
  createdAt: string;
  generation: DiaryGeneration;
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
    isDiaryGeneration(value.generation) &&
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

export const getDiaryGenerationStatus = async ({
  generationId,
}: DiaryGenerationStatusRequest): Promise<DiaryGenerationStatusResponse> => {
  const response = await authFetch(
    `${API_BASE_URL}/diary-generations/${generationId}`,
  );

  if (!response.ok) {
    const errorData: unknown = await response.json().catch(() => null);

    if (isProblemDetails(errorData)) {
      throw new RequestError(errorData);
    }

    throw new Error(ERROR_MESSAGES.DIARY_GENERATION_STATUS_FETCH_FAILED);
  }

  const data: unknown = await response.json();

  if (!isDiaryGenerationStatusResponse(data)) {
    throw new Error(ERROR_MESSAGES.INVALID_DIARY_GENERATION_STATUS_RESPONSE);
  }

  return data;
};

export interface DiaryGenerationStatusRequest {
  generationId: string;
}

interface DiaryGenerationStatusInProgressResponse {
  id: string;
  status: Extract<GenerationStatus, 'PENDING' | 'PROCESSING'>;
  diaryId: null;
  errorCode: null;
}

interface DiaryGenerationStatusSuccessResponse {
  id: string;
  status: Extract<GenerationStatus, 'SUCCEEDED'>;
  diaryId: string;
  errorCode: null;
}

interface DiaryGenerationStatusFailureResponse {
  id: string;
  status: Extract<GenerationStatus, 'FAILED'>;
  diaryId: null;
  errorCode: GenerationFailureCode;
}

export type DiaryGenerationStatusResponse =
  | DiaryGenerationStatusInProgressResponse
  | DiaryGenerationStatusSuccessResponse
  | DiaryGenerationStatusFailureResponse;

export const isDiaryGenerationStatusResponse = (
  value: unknown,
): value is DiaryGenerationStatusResponse => {
  if (
    typeof value !== 'object' ||
    value === null ||
    !('id' in value) ||
    typeof value.id !== 'string' ||
    !('status' in value) ||
    !isGenerationStatus(value.status) ||
    !('diaryId' in value) ||
    !('errorCode' in value)
  ) {
    return false;
  }

  switch (value.status) {
    case 'PENDING':
    case 'PROCESSING':
      return value.diaryId === null && value.errorCode === null;
    case 'SUCCEEDED':
      return typeof value.diaryId === 'string' && value.errorCode === null;
    case 'FAILED':
      return value.diaryId === null && isGenerationFailureCode(value.errorCode);
  }
};
