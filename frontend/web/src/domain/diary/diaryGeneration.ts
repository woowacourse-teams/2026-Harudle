export type GenerationStatus =
  'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED';

export const GENERATION_FAILURE_CODES = [
  'AI_PROVIDER_ERROR',
  'AI_PROVIDER_RATE_LIMITED',
  'AI_PROVIDER_TIMEOUT',
  'AI_OUTPUT_TRUNCATED',
  'AI_RESPONSE_PROCESSING_ERROR',
  'GENERATION_INTERNAL_ERROR',
  'GENERATION_INTERRUPTED',
  'IMAGE_STORAGE_ERROR',
] as const;

export type GenerationFailureCode = (typeof GENERATION_FAILURE_CODES)[number];

export const isGenerationFailureCode = (
  value: unknown,
): value is GenerationFailureCode =>
  GENERATION_FAILURE_CODES.some((code): boolean => code === value);

export interface DiaryGenerationInProgress {
  id: string;
  status: Extract<GenerationStatus, 'PENDING' | 'PROCESSING'>;
  title: null;
  imageUrl: null;
  imageUrlExpiresAt: null;
  completedAt: null;
}

export interface DiaryGenerationSuccess {
  id: string;
  status: Extract<GenerationStatus, 'SUCCEEDED'>;
  title: string;
  imageUrl: string;
  imageUrlExpiresAt: string;
  completedAt: string;
}

export type DiaryGeneration =
  DiaryGenerationInProgress | DiaryGenerationSuccess;

export const isGenerationStatus = (value: unknown): value is GenerationStatus =>
  value === 'PENDING' ||
  value === 'PROCESSING' ||
  value === 'SUCCEEDED' ||
  value === 'FAILED';

export const isDiaryGeneration = (value: unknown): value is DiaryGeneration => {
  return (
    typeof value === 'object' &&
    value !== null &&
    'id' in value &&
    typeof value.id === 'string' &&
    'status' in value &&
    'title' in value &&
    'imageUrl' in value &&
    'imageUrlExpiresAt' in value &&
    'completedAt' in value &&
    (((value.status === 'PENDING' || value.status === 'PROCESSING') &&
      value.title === null &&
      value.imageUrl === null &&
      value.imageUrlExpiresAt === null &&
      value.completedAt === null) ||
      (value.status === 'SUCCEEDED' &&
        typeof value.title === 'string' &&
        typeof value.imageUrl === 'string' &&
        typeof value.imageUrlExpiresAt === 'string' &&
        typeof value.completedAt === 'string'))
  );
};
