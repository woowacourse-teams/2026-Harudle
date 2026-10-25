import { MOCK_SCENARIO_HEADER, MOCK_SCENARIOS } from './mockScenarios';

export interface MockGenerationJob {
  readonly acceptedAt: number;
  readonly shouldFail: boolean;
}

export interface MockSucceededGeneration {
  id: string;
  status: 'SUCCEEDED';
  title: string;
  imageUrl: string;
  imageUrlExpiresAt: string;
  completedAt: string;
}

interface MockIncompleteGeneration {
  id: string;
  status: 'PENDING' | 'PROCESSING' | 'FAILED';
  title: null;
  imageUrl: null;
  imageUrlExpiresAt: null;
  completedAt: string | null;
}

export type MockDiaryGeneration =
  MockSucceededGeneration | MockIncompleteGeneration;

// 조회 횟수와 관계없이 접수 후 경과 시간으로 작업 진행을 재현한다.
export const MOCK_GENERATION_PENDING_MS = 1_000;
export const MOCK_GENERATION_COMPLETION_MS = 9_000;

export const createMockGenerationJob = (
  request: Request,
): MockGenerationJob => ({
  acceptedAt: Date.now(),
  shouldFail:
    request.headers.get(MOCK_SCENARIO_HEADER) ===
    MOCK_SCENARIOS.diaryGenerationFailure,
});

export const getMockGeneration = (
  job: MockGenerationJob,
  result: MockSucceededGeneration,
): MockDiaryGeneration => {
  const elapsed = Date.now() - job.acceptedAt;
  if (elapsed >= MOCK_GENERATION_COMPLETION_MS && !job.shouldFail) {
    return result;
  }

  const status =
    elapsed < MOCK_GENERATION_PENDING_MS
      ? 'PENDING'
      : elapsed < MOCK_GENERATION_COMPLETION_MS
        ? 'PROCESSING'
        : 'FAILED';

  return {
    id: result.id,
    status,
    title: null,
    imageUrl: null,
    imageUrlExpiresAt: null,
    completedAt:
      status === 'FAILED'
        ? new Date(job.acceptedAt + MOCK_GENERATION_COMPLETION_MS).toISOString()
        : null,
  };
};

export const getMockGenerationStatus = (
  generation: MockDiaryGeneration,
  diaryId: string,
): {
  id: string;
  status: MockDiaryGeneration['status'];
  diaryId: string | null;
  errorCode: 'AI_PROVIDER_ERROR' | null;
} => ({
  id: generation.id,
  status: generation.status,
  diaryId: generation.status === 'SUCCEEDED' ? diaryId : null,
  errorCode: generation.status === 'FAILED' ? 'AI_PROVIDER_ERROR' : null,
});
