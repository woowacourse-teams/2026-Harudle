import { createContext, useContext, useState, type ReactNode } from 'react';
import {
  API_BASE_URL,
  isProblemDetails,
  RequestError,
  type ApiRequest,
} from '../../shared/api';
import { authFetch } from '../../shared/auth';
import { useAnalytics } from '../../shared/useAnalytics';
import type { DiaryGeneratingState } from './DiaryGeneratingPage';

interface DiaryGenerateContextValue {
  diaryGenerateRequest: ApiRequest<DiaryGenerateResponse>;
  generateDiary: ({
    diaryDate,
    sourceText,
    idempotencyKey,
  }: DiaryGeneratingState) => Promise<void>;
}

export const DiaryGenerateContext =
  createContext<DiaryGenerateContextValue | null>(null);

export const DiaryGenerateProvider = ({
  children,
}: {
  children: ReactNode;
}) => {
  const { track } = useAnalytics();
  const [diaryGenerateRequest, setDiaryGenerateRequest] = useState<
    ApiRequest<DiaryGenerateResponse>
  >({
    status: 'idle',
  });

  const generateDiary = async ({
    diaryDate,
    sourceText,
    idempotencyKey,
  }: DiaryGeneratingState): Promise<void> => {
    setDiaryGenerateRequest({
      status: 'loading',
    });
    try {
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
        const errorData = await response.json();
        if (isProblemDetails(errorData)) {
          throw new RequestError(errorData);
        }

        throw new Error('알 수 없는 에러가 발생했습니다.');
      }

      const data: unknown = await response.json();

      if (!isDiaryGenerateResponse(data)) {
        throw new Error('일기 응답 형식이 일치하지 않습니다.');
      }

      setDiaryGenerateRequest({
        status: 'success',
        data: data,
      });
      sessionStorage.removeItem('diaryContent'); // TOOD: 별도 로직으로 분리 (주입받는 식) + session Item key 상수화

      track('diary_created', {
        diary_id: data.id,
        diary_date: data.diaryDate,
        remaining_generation_count: data.usage.remainingCount,
      });
    } catch (error: unknown) {
      if (error instanceof Error) {
        setDiaryGenerateRequest({
          status: 'error',
          error: error,
        });
      }
    }
  };

  return (
    <DiaryGenerateContext.Provider
      value={{
        diaryGenerateRequest,
        generateDiary,
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

export interface DiaryGenerateResponse {
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
    value.generation.status === 'SUCCEEDED' &&
    'title' in value.generation &&
    typeof value.generation.title === 'string' &&
    'imageUrl' in value.generation &&
    typeof value.generation.imageUrl === 'string' &&
    'imageUrlExpiresAt' in value.generation &&
    typeof value.generation.imageUrlExpiresAt === 'string' &&
    'completedAt' in value.generation &&
    typeof value.generation.completedAt === 'string' &&
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
