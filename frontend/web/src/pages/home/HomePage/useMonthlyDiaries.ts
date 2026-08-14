import { useEffect, useState } from 'react';
import { API_BASE_URL, type ApiRequest } from '../../../shared/api';
import type {
  MonthlyDiariesResponse,
  MonthlyDiaryDay,
  MonthlyDiaryItem,
  YearMonth,
} from './model';
import { isMonth } from './useSelectedYearMonth';

const useMonthlyDiaries = ({ year, month }: YearMonth) => {
  const [monthlyDiariesRequest, setMonthlyDiariesRequest] = useState<
    ApiRequest<MonthlyDiariesResponse>
  >({
    status: 'idle',
  });

  useEffect(() => {
    const getMonthlyDiaries = async (): Promise<void> => {
      setMonthlyDiariesRequest({
        status: 'loading',
      });
      try {
        const response = await fetch(
          `${API_BASE_URL}/diaries?year=${year}&month=${month}`,
        );

        if (!response.ok) {
          throw new Error('네트워크 에러');
        }
        const data: unknown = await response.json();

        if (!isMonthlyDiariesResponse(data)) {
          throw new Error('MonthlyDiaries 응답 형식이 일치하지 않습니다.');
        }

        setMonthlyDiariesRequest({
          status: 'success',
          data: data,
        });
      } catch (error: unknown) {
        // 에러 처리 정규화
        if (error instanceof Error) {
          setMonthlyDiariesRequest({
            status: 'error',
            error: error,
          });
          alert(error.message);
        }
      }
    };

    void getMonthlyDiaries();
  }, [year, month]);

  return { monthlyDiariesRequest };
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
