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
  return value >= 1 && value <= 12;
};

export const getToday = (): {
  year: number;
  month: Month;
  day: number;
} => {
  const today = new Date();

  const month = today.getMonth() + 1;
  if (!isMonth(month)) {
    throw new Error('올바른 month가 아닙니다!');
  }

  return {
    year: today.getFullYear(),
    month: month,
    day: today.getDate(),
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
