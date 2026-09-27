import { ERROR_MESSAGES } from '../../shared/errorMessage';
import {
  createContext,
  useCallback,
  useContext,
  useState,
  type ReactNode,
} from 'react';
import { type ApiRequest } from '../../shared/api';
import { DIARY_CONTENT_SESSION_KEY } from '../../shared/constants';
import {
  generateDiary,
  type DiaryGenerateRequest,
  type DiaryGenerateResponse,
} from '../../domain/diary/diaryGenerate';
import { useAnalytics } from '../../posthog/useAnalytics';

interface DiaryGenerateContextValue {
  request: ApiRequest<DiaryGenerateResponse>;
  execute: ({
    diaryDate,
    sourceText,
    idempotencyKey,
  }: DiaryGenerateRequest) => Promise<void>;
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
    }: DiaryGenerateRequest): Promise<void> => {
      setRequest({
        status: 'loading',
      });
      try {
        const diaryGenerateResponse = await generateDiary({
          diaryDate,
          sourceText,
          idempotencyKey,
        });

        setRequest({
          status: 'success',
          data: diaryGenerateResponse,
        });

        sessionStorage.removeItem(DIARY_CONTENT_SESSION_KEY); // TOOD: 별도 로직으로 분리 (책임분리)

        track('diary_created', {
          diary_id: diaryGenerateResponse.id,
          diary_date: diaryGenerateResponse.diaryDate,
          remaining_generation_count:
            diaryGenerateResponse.usage.remainingCount,
        });
      } catch (error: unknown) {
        if (error instanceof Error) {
          setRequest({
            status: 'error',
            error: error,
          });
        }
      }
    },
    [track],
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
