import { useErrorTracking } from '../../posthog/useErrorTracking';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import {
  createContext,
  useCallback,
  useContext,
  useState,
  type ReactNode,
} from 'react';
import { RequestError, type ApiRequest } from '../../shared/api';
import {
  generateDiary,
  DIARY_GENERATION_ERROR_CODE,
  type DiaryGenerateRequest,
  type DiaryGenerateResponse,
} from '../../domain/diary/diaryGenerate';
import { useAnalytics } from '../../posthog/useAnalytics';

interface DiaryGenerateExecuteOptions extends DiaryGenerateRequest {
  readonly onSuccess?: (response: DiaryGenerateResponse) => void;
  readonly onError?: (error: Error) => void;
}

interface DiaryGenerateContextValue {
  request: ApiRequest<DiaryGenerateResponse>;
  execute: (options: DiaryGenerateExecuteOptions) => Promise<void>;
  resetRequest: () => void;
}

export const DiaryGenerateContext =
  createContext<DiaryGenerateContextValue | null>(null);

export const DiaryGenerateProvider = ({
  children,
}: {
  children: ReactNode;
}) => {
  const { track } = useAnalytics();
  const { captureError } = useErrorTracking();
  const [request, setRequest] = useState<ApiRequest<DiaryGenerateResponse>>({
    status: 'idle',
  });

  const resetRequest = useCallback(
    () =>
      setRequest({
        status: 'idle',
      }),
    [],
  );

  const execute = useCallback(
    async ({
      diaryDate,
      sourceText,
      idempotencyKey,
      onSuccess,
      onError,
    }: DiaryGenerateExecuteOptions): Promise<void> => {
      setRequest({
        status: 'loading',
      });
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
          setRequest({
            status: 'error',
            error: error,
          });
          onError?.(error);
        }
        return;
      }

      setRequest({
        status: 'success',
        data: diaryGenerateResponse,
      });

      track('diary_created', {
        diary_id: diaryGenerateResponse.id,
        diary_date: diaryGenerateResponse.diaryDate,
        remaining_generation_count: diaryGenerateResponse.usage.remainingCount,
      });

      onSuccess?.(diaryGenerateResponse);
    },
    [track, captureError],
  );

  return (
    <DiaryGenerateContext.Provider
      value={{
        request,
        execute,
        resetRequest,
      }}
    >
      {children}
    </DiaryGenerateContext.Provider>
  );
};

export const useDiaryGenerateContext = () => {
  const diaryGenerateContext = useContext(DiaryGenerateContext);

  if (!diaryGenerateContext) {
    throw new Error(ERROR_MESSAGES.DIARY_GENERATE_PROVIDER_REQUIRED);
  }

  return diaryGenerateContext;
};
