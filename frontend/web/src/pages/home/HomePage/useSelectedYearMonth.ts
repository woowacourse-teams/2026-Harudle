import { useState } from 'react';
import type { YearMonth } from './model';
import { isMonth, type Month } from '../../../shared/utils';

const formatYearMonthToObject = (yearMonth: string): YearMonth => {
  const [year, month] = yearMonth.split('-').map((string) => Number(string));
  if (!isMonth(month)) {
    throw new Error('month 변환에 실패했습니다. month 범위를 확인하세요');
  }

  return { year, month };
};

const useSelectedYearMonth = (year: number, month: Month) => {
  const [selectedYearMonth, setSelectedYearMonth] = useState<YearMonth>({
    year,
    month,
  });

  const handleYearMonthChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const stringYearMonth = e.target.value;
    setSelectedYearMonth(formatYearMonthToObject(stringYearMonth));
  };

  return { selectedYearMonth, handleYearMonthChange };
};

export default useSelectedYearMonth;
