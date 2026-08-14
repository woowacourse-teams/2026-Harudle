import { useEffect, useState } from 'react';
import BottomNavigation from '../../../shared/BottomNavigation';
import DiaryEmptyState from '../DiaryEmptyState';
import { API_BASE_URL, type ApiRequest } from '../../../shared/api';
import DiaryItemList from '../DiaryItemList';
import FloatingActionButton from '../../../shared/FloatingActionButton';
import { useNavigate } from 'react-router';

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

export interface MonthlyDiaryItem {
  id: string;
  title: string;
  thumbnailUrl: string;
}

export interface MonthlyDiaryDay {
  date: string;
  exist: boolean;
  items: MonthlyDiaryItem[];
}

interface MonthlyDiariesResponse {
  year: number;
  month: Month;
  days: MonthlyDiaryDay[];
}

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

const HomePage = () => {
  const navigate = useNavigate();
  // TODO: 클라이언트 상태 훅으로 분리하기
  const [selectedYearMonth, setSelectedYearMonth] = useState<YearMonth>({
    year: 2026,
    month: 8,
  });

  // TODO: 서버 상태 훅으로 분리
  const [monthlyDiariesRequest, setMonthlyDiariesRequest] = useState<
    ApiRequest<MonthlyDiariesResponse>
  >({
    status: 'idle',
  });

  useEffect(() => {
    const getMonthlyDiaries = async ({
      year,
      month,
    }: YearMonth): Promise<void> => {
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
        if (error instanceof Error) {
          setMonthlyDiariesRequest({
            status: 'error',
            error: error,
          });
          alert(error.message);
        }
      }
    };

    void getMonthlyDiaries({ ...selectedYearMonth });
  }, [selectedYearMonth]);

  const handleYearMonthChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const stringYearMonth = e.target.value;
    setSelectedYearMonth(formatYearMonthToObject(stringYearMonth));
  };

  if (
    monthlyDiariesRequest.status === 'idle' ||
    monthlyDiariesRequest.status === 'loading'
  ) {
    return <div>로딩중...</div>;
  }

  if (monthlyDiariesRequest.status === 'error') {
    return <div>에러가 발생했습니다.</div>;
  }

  const { days } = monthlyDiariesRequest.data;

  return (
    <div>
      <div>로고</div>
      <div>
        <input
          type="month"
          value={formatYearMonthToString(selectedYearMonth)}
          onChange={handleYearMonthChange}
        />
        <div>{days.length}개의 기록</div>
      </div>
      <RemainingGenerationUsageCard />
      {days.length > 0 ? (
        <>
          <DiaryItemList monthlyDiaryDays={days} />
          <FloatingActionButton
            onClick={() => {
              navigate('/diary-write');
            }}
            disabled={false}
          />
        </>
      ) : (
        <DiaryEmptyState />
      )}
      <BottomNavigation />
    </div>
  );
};

export default HomePage;

interface GenerationUsageResponse {
  usageDate: string;
  usedCount: number;
  limitCount: number;
  remainingCount: number;
}

const isGenerationUsageResponse = (
  value: unknown,
): value is GenerationUsageResponse => {
  return (
    typeof value === 'object' &&
    value !== null &&
    'usageDate' in value &&
    typeof value.usageDate === 'string' &&
    'usedCount' in value &&
    typeof value.usedCount === 'number' &&
    'limitCount' in value &&
    typeof value.limitCount === 'number' &&
    'remainingCount' in value &&
    typeof value.remainingCount === 'number'
  );
};

const RemainingGenerationUsageCard = () => {
  // 서버 상태
  const [generationUsage, setGenerationUsage] = useState<ApiRequest<number>>({
    status: 'idle',
  });

  useEffect(() => {
    const getRemainingGenerationUsageCard = async (): Promise<void> => {
      setGenerationUsage({
        status: 'loading',
      });

      try {
        const response = await fetch(`${API_BASE_URL}/me/generation-usage`);

        if (!response.ok) {
          throw new Error('네트워크 에러');
        }
        const data: unknown = await response.json();

        if (!isGenerationUsageResponse(data)) {
          throw new Error('GenerationUsage 응답 형식이 일치하지 않습니다.');
        }

        setGenerationUsage({
          status: 'success',
          data: data.remainingCount,
        });
      } catch (error: unknown) {
        if (error instanceof Error) {
          setGenerationUsage({
            status: 'error',
            error: error,
          });
          alert(error.message);
        }
      }
    };

    void getRemainingGenerationUsageCard();
  }, []);

  if (
    generationUsage.status === 'idle' ||
    generationUsage.status === 'loading'
  ) {
    return <div>로딩중...</div>;
  }

  if (generationUsage.status === 'error') {
    return <div>에러가 발생했습니다.</div>;
  }

  return <div>오늘 남은 생성 {generationUsage.data}회</div>;
};
