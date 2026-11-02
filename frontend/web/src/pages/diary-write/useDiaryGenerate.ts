import { useCallback, useState } from 'react';
import { RequestError, type ApiRequest } from '../../shared/api';
import {
  generateDiary,
  DIARY_GENERATION_ERROR_CODE,
  type DiaryGenerateRequest,
  type DiaryGenerateResponse,
} from '../../domain/diary/diaryGenerate';
import { useAnalytics } from '../../posthog/useAnalytics';
import { useErrorTracking } from '../../posthog/useErrorTracking';

interface DiaryGenerateExecuteOptions extends DiaryGenerateRequest {
  readonly onSuccess?: (response: DiaryGenerateResponse) => void;
  readonly onError?: (error: Error) => void;
}

interface DiaryGenerateState {
  readonly request: ApiRequest<DiaryGenerateResponse>;
  readonly execute: (options: DiaryGenerateExecuteOptions) => Promise<void>;
}

const useDiaryGenerate = (): DiaryGenerateState => {
  const { track } = useAnalytics();
  const { captureError } = useErrorTracking();
  const [request, setRequest] = useState<ApiRequest<DiaryGenerateResponse>>({
    status: 'idle',
  });

  const execute = useCallback(
    async ({
      diaryDate,
      sourceText,
      idempotencyKey,
      onSuccess,
      onError,
    }: DiaryGenerateExecuteOptions): Promise<void> => {
      setRequest({ status: 'loading' });
      let diaryGenerateResponse: DiaryGenerateResponse;

      try {
        diaryGenerateResponse = await generateDiary({
          diaryDate,
          sourceText,
          idempotencyKey,
        });
      } catch (error: unknown) {
        if (error instanceof Error) {
          const isExpectedGenerationError =
            error instanceof RequestError &&
            (error.problem.code ===
              DIARY_GENERATION_ERROR_CODE.DAILY_LIMIT_EXCEEDED ||
              error.problem.code === DIARY_GENERATION_ERROR_CODE.IN_PROGRESS);

          if (!isExpectedGenerationError) {
            captureError(error, { feature: 'diary', operation: 'create' });
          }
          setRequest({ status: 'error', error });
          onError?.(error);
        }
        return;
      }

      setRequest({ status: 'success', data: diaryGenerateResponse });
      track('diary_written', {
        diary_id: diaryGenerateResponse.id,
        diary_date: diaryGenerateResponse.diaryDate,
        remaining_generation_count: diaryGenerateResponse.usage.remainingCount,
      });
      onSuccess?.(diaryGenerateResponse);
    },
    [track, captureError],
  );

  return { request, execute };
};

export default useDiaryGenerate;
