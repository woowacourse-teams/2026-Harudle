import { useSearchParams } from 'react-router';

import { isMonth, type Month } from '../../../shared/utils';

const parseYearMonth = (
  yearMonth: string,
): {
  year: number;
  month: Month;
} => {
  const [year, month] = yearMonth.split('-').map(Number);
  if (!Number.isInteger(year) || !isMonth(month)) {
    throw new Error('month 변환에 실패했습니다. month 범위를 확인하세요');
  }

  return { year, month };
};

const useSelectedYearMonth = (year: number, month: Month) => {
  const [searchParams, setSearchParams] = useSearchParams();
  const yearMonthParam = searchParams.get('yearMonth');
  const selectedYearMonth = yearMonthParam
    ? parseYearMonth(yearMonthParam)
    : { year, month };

  const handleYearMonthChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setSearchParams({ yearMonth: e.target.value }, { replace: true });
  };

  return { selectedYearMonth, handleYearMonthChange };
};

export default useSelectedYearMonth;
