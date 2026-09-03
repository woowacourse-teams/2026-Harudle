import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { authFetch } from '../../shared/auth';
import { isMonth, type Month } from '../../shared/utils';

export const getMonthlyDiaries = async ({
  year,
  month,
}: {
  year: number;
  month: Month;
}): Promise<MonthlyDiariesResponse> => {
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

  return data;
};

export interface MonthlyDiariesResponse {
  year: number;
  month: Month;
  days: MonthlyDiaryDay[];
}

export interface MonthlyDiaryDay {
  date: string;
  exist: boolean;
  items: MonthlyDiaryItem[];
}

export interface MonthlyDiaryItem {
  id: string;
  title: string;
  thumbnailUrl: string;
}

export const isMonthlyDiariesResponse = (
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

const isMonthlyDiaryDays = (value: unknown): value is MonthlyDiaryDay[] => {
  return Array.isArray(value) && value.every(isMonthlyDiaryDay);
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

const isMonthlyDiaryItems = (value: unknown): value is MonthlyDiaryItem[] => {
  return Array.isArray(value) && value.every(isMonthlyDiaryItem);
};

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
