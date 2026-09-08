import {
  createContext,
  useCallback,
  useContext,
  useState,
  type ReactNode,
} from 'react';
import { type ApiRequest } from '../../shared/api';

import { useAnalytics } from '../../shared/useAnalytics';
import {
  generateDiary,
  type DiaryGenerateRequest,
  type DiaryGenerateResponse,
} from '../../domain/diary/diaryGenerate';

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

        sessionStorage.removeItem('diaryContent'); // TOOD: 별도 로직으로 분리 (주입받는 식) + session Item key 상수화

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
    throw new Error(
      'useDiaryGenerateContext는 DiaryGenerateProvider 내부에서만 사용할 수 있습니다.',
    );
  }

  return diaryGenerateContext;
};
