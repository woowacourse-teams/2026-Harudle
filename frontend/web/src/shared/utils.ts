import { ERROR_MESSAGES } from './errorMessage';
const WEEKDAYS = [
  '일요일',
  '월요일',
  '화요일',
  '수요일',
  '목요일',
  '금요일',
  '토요일',
];

export type Month = 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12;

export const isMonth = (value: number): value is Month => {
  return Number.isInteger(value) && value >= 1 && value <= 12;
};

export const getToday = (): {
  year: number;
  month: Month;
  day: number;
} => {
  const [year, month, day] = new Date()
    .toLocaleDateString('sv-SE', { timeZone: 'Asia/Seoul' })
    .split('-')
    .map(Number);

  if (!isMonth(month)) {
    throw new Error(ERROR_MESSAGES.INVALID_MONTH);
  }

  return {
    year,
    month,
    day,
  };
};

export const formatDiaryDate = (date: string) => {
  const [year, month, day] = date.split('-').map(Number);
  const weekday = WEEKDAYS[new Date(year, month - 1, day).getDay()];

  return {
    date: `${String(month).padStart(2, '0')}.${String(day).padStart(2, '0')}`,
    weekday,
  };
};

export const isRecord = (value: unknown): value is Record<string, unknown> => {
  return typeof value === 'object' && value !== null;
};

export const isNonNegativeInteger = (value: number) => {
  return Number.isInteger(value) && value >= 0;
};
