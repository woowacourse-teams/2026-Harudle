import { useEffect, useState } from 'react';
import BottomNavigation from '../../shared/BottomNavigation';
import DiaryEmptyState from './DiaryEmptyState';
import { API_BASE_URL, type ApiRequest } from '../../shared/api';
import DiaryItemList from './DiaryItemList';
import FloatingActionButton from '../../shared/FloatingActionButton';

type Month = 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12;

interface YearMonth {
  year: number;
  month: Month;
}

const isMonth = (value: number): value is Month => {
  return value >= 1 && value <= 12;
};

const formatYearMonthToString = ({ year, month }: YearMonth): string => {
  return `${year}-${month.toString().padStart(2, '0')}`;
};

const formatYearMonthToObject = (yearMonth: string): YearMonth => {
  const [year, month] = yearMonth.split('-').map((string) => Number(string));
  if (!isMonth(month)) {
    throw new Error('month 변환에 실패했습니다. month 범위를 확인하세요');
  }

  return { year, month };
};

// TODO: 이거 도메인 타입 만들고 나중에 MonthlyDiaries extends Monthly로 가자
export interface MonthlyDiary {
  date: string;
  exist: boolean;
  title: string;
  thumbnailUrl: string;
}

interface MonthlyDiariesResponse {
  year: number;
  month: Month;
  days: MonthlyDiary[];
}

// 타입 가드 코드
const isMonthlyDiary = (value: unknown): value is MonthlyDiary => {
  return (
    typeof value === 'object' &&
    value !== null &&
    'date' in value &&
    typeof value.date === 'string' &&
    'exist' in value &&
    typeof value.exist === 'boolean' &&
    'title' in value &&
    typeof value.title === 'string' &&
    'thumbnailUrl' in value &&
    typeof value.thumbnailUrl === 'string'
  );
};

const isMonthlyDiaries = (value: unknown): value is MonthlyDiary[] => {
  return Array.isArray(value) && value.every(isMonthlyDiary);
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
    isMonthlyDiaries(value.days)
  );
};

const HomePage = () => {
  // TODO: 클라이언트 상태 훅으로 분리하기
  const [selectedYearMonth, setSelectedYearMonth] = useState<YearMonth>({
    year: 2026,
    month: 8,
  });

  // TODO: 서버 상태 훅으로 분리
  const [monthlyDiaries, setMonthlyDiaries] = useState<
    ApiRequest<MonthlyDiary[]>
  >({
    status: 'idle',
  });

  useEffect(() => {
    const getMonthlyDiaries = async ({
      year,
      month,
    }: YearMonth): Promise<MonthlyDiary[]> => {
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

      setMonthlyDiaries({
        status: 'success',
        data: data.days,
      });

      return data.days;
    };

    void getMonthlyDiaries({ ...selectedYearMonth });
  }, [selectedYearMonth.year, selectedYearMonth.month]);

  const handleYearMonthChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const stringYearMonth = e.target.value;
    setSelectedYearMonth(formatYearMonthToObject(stringYearMonth));
  };

  if (monthlyDiaries.status === 'idle' || monthlyDiaries.status === 'loading') {
    return <div>로딩중...</div>;
  }

  if (monthlyDiaries.status === 'error') {
    return <div>에러가 발생했습니다.</div>;
  }

  return (
    <div>
      <div>로고</div>
      <div>
        <input
          type="month"
          value={formatYearMonthToString(selectedYearMonth)}
          onChange={handleYearMonthChange}
        />
        <div>{monthlyDiaries.data.length}개의 기록</div>
      </div>
      <div>오늘 남은 생성 횟수 3회</div>
      {monthlyDiaries.data.length > 0 ? (
        <>
          <DiaryItemList monthlyDiaries={monthlyDiaries.data} />
          <FloatingActionButton />
        </>
      ) : (
        <DiaryEmptyState />
      )}
      <BottomNavigation />
    </div>
  );
};

export default HomePage;
