import { useCallback, useEffect, useState } from 'react';
import {
  API_BASE_URL,
  isProblemDetails,
  RequestError,
  type ApiRequest,
} from '../../../shared/api';
import type {
  MonthlyDiariesResponse,
  MonthlyDiaryDay,
  MonthlyDiaryItem,
  YearMonth,
} from './model';
import { authFetch } from '../../../shared/auth';
import { useAnalytics } from '../../../shared/useAnalytics';
import { isMonth } from '../../../shared/utils';

const useMonthlyDiaries = ({ year, month }: YearMonth) => {
  const { track } = useAnalytics();
  const [monthlyDiariesRequest, setMonthlyDiariesRequest] = useState<
    ApiRequest<MonthlyDiariesResponse>
  >({
    status: 'idle',
  });

  const getMonthlyDiaries = useCallback(async (): Promise<void> => {
    setMonthlyDiariesRequest({
      status: 'loading',
    });

    try {
      const response = await authFetch(
        `${API_BASE_URL}/diaries?year=${year}&month=${month}`,
      );

      if (!response.ok) {
        const errorData = await response.json();
        if (isProblemDetails(errorData)) {
          throw new RequestError(errorData);
        }

        throw new Error('알 수 없는 에러가 발생했습니다.');
      }

      const data: unknown = await response.json();

      if (!isMonthlyDiariesResponse(data)) {
        throw new Error('MonthlyDiaries 응답 형식이 일치하지 않습니다.');
      }

      setMonthlyDiariesRequest({
        status: 'success',
        data: data,
      });

      const diaryCount = data.days.reduce(
        (count, day) => count + day.items.length,
        0,
      );

      track('diary_timeline_viewed', {
        year: data.year,
        month: data.month,
        diary_count: diaryCount,
        has_diaries: diaryCount > 0,
      });
    } catch (error: unknown) {
      if (error instanceof Error) {
        setMonthlyDiariesRequest({
          status: 'error',
          error: error,
        });
      }
    }
  }, [year, month, track]);

  useEffect(() => {
    // TODO: API 요청과 상태 갱신 책임을 분리해 lint 예외를 제거한다.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void getMonthlyDiaries();
  }, [getMonthlyDiaries]);

  return { monthlyDiariesRequest, getMonthlyDiaries };
};

export default useMonthlyDiaries;

const isMonthlyDiaryItem = (value: unknown): value is MonthlyDiaryItem => {
  return (
    typeof value === 'object' &&
    value !== null &&
    'id' in value &&
    typeof value.id === 'string' &&
    'title' in value &&
    typeof value.title === 'string' &&
    'thumbnailUrl' in value &&
    typeof value.thumbnailUrl === 'string'
  );
};

const isMonthlyDiaryItems = (value: unknown): value is MonthlyDiaryItem[] => {
  return Array.isArray(value) && value.every(isMonthlyDiaryItem);
};

const isMonthlyDiaryDay = (value: unknown): value is MonthlyDiaryDay => {
  return (
    typeof value === 'object' &&
    value !== null &&
    'date' in value &&
    typeof value.date === 'string' &&
    'exist' in value &&
    typeof value.exist === 'boolean' &&
    'items' in value &&
    isMonthlyDiaryItems(value.items)
  );
};

const isMonthlyDiaryDays = (value: unknown): value is MonthlyDiaryDay[] => {
  return Array.isArray(value) && value.every(isMonthlyDiaryDay);
};

const isMonthlyDiariesResponse = (
  value: unknown,
): value is MonthlyDiariesResponse => {
  return (
    typeof value === 'object' &&
    value !== null &&
    'year' in value &&
    typeof value.year === 'number' &&
    'month' in value &&
    typeof value.month === 'number' &&
    isMonth(value.month) &&
    'days' in value &&
    isMonthlyDiaryDays(value.days)
  );
};
