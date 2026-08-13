import { useEffect, useRef, useState } from 'react';
import BottomNavigation from '../../shared/BottomNavigation';
import DiaryEmptyState from './DiaryEmptyState';
import { API_BASE_URL, type ApiRequest } from '../../shared/api';
import DiaryItemList from './DiaryItemList';
import FloatingActionButton from '../../shared/FloatingActionButton';
import { useNavigate } from 'react-router';
import harudleLogo from '../../assets/images/harudle-logo.png';
import eventAvailableIcon from '../../assets/icons/event-available.svg';
import { css } from '@emotion/react';
import { theme } from '../../styles/theme';
import loadingAnimation from '../../assets/images/loading-animation.webp';
import keyboardArrowDownIcon from '../../assets/icons/keyboard-arrow-down.svg';
import { authFetch } from '../../shared/auth';

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
  const monthInputRef = useRef<HTMLInputElement>(null);
  // TODO: 클라이언트 상태 훅으로 분리하기
  const [selectedYearMonth, setSelectedYearMonth] = useState<YearMonth>({
    year: 2026,
    month: 8,
  });

  // TODO: 서버 상태 훅으로 분리
  const [monthlyDiaries, setMonthlyDiaries] = useState<
    ApiRequest<MonthlyDiaryDay[]>
  >({
    status: 'idle',
  });

  useEffect(() => {
    const getMonthlyDiaries = async ({
      year,
      month,
    }: YearMonth): Promise<void> => {
      setMonthlyDiaries({
        status: 'loading',
      });
      try {
        const response = await authFetch(
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
      } catch (error: unknown) {
        if (error instanceof Error) {
          setMonthlyDiaries({
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

  const handleYearMonthPickerOpen = () => {
    const monthInput = monthInputRef.current;

    if (typeof monthInput?.showPicker === 'function') {
      monthInput.showPicker();
    }
  };

  const monthlyDiaryCount =
    monthlyDiaries.status === 'success'
      ? monthlyDiaries.data.reduce((count, day) => count + day.items.length, 0)
      : 0;

  return (
    <div css={homePageStyle}>
      <header css={pageHeaderStyle}>
        <button css={logoButtonStyle} onClick={() => navigate('/')}>
          <img src={harudleLogo} alt="하루들" css={logoStyle} />
        </button>
      </header>

      <main css={homeContentStyle}>
        {monthlyDiaries.status === 'idle' ||
        monthlyDiaries.status === 'loading' ? (
          <div css={pageFeedbackStyle}>
            <img src={loadingAnimation} alt="로딩 중" css={loadingImageStyle} />
          </div>
        ) : monthlyDiaries.status === 'error' ? (
          <div css={pageFeedbackStyle}>{monthlyDiaries.error.message}</div>
        ) : (
          <>
            <div css={monthHeaderStyle}>
              <div css={monthPickerStyle}>
                <input
                  ref={monthInputRef}
                  type="month"
                  value={formatYearMonthToString(selectedYearMonth)}
                  onChange={handleYearMonthChange}
                  onClick={handleYearMonthPickerOpen}
                  css={monthInputStyle}
                />
                <img src={keyboardArrowDownIcon} alt="" css={monthArrowStyle} />
              </div>

              {monthlyDiaryCount > 0 && (
                <div css={recordCountStyle}>{monthlyDiaryCount}개의 기록</div>
              )}
            </div>

            <RemainingGenerationUsageCard />

            <div css={diaryContentScrollStyle}>
              {monthlyDiaryCount > 0 ? (
                <DiaryItemList monthlyDiaryDays={monthlyDiaries.data} />
              ) : (
                <DiaryEmptyState />
              )}
            </div>
          </>
        )}
      </main>

      {monthlyDiaries.status === 'success' && monthlyDiaryCount > 0 && (
        <FloatingActionButton
          onClick={() => {
            navigate('/diary-write');
          }}
          disabled={false}
        />
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
        const response = await authFetch(`${API_BASE_URL}/me/generation-usage`);

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
    return (
      <div css={generationUsageLoadingStyle}>
        <img
          src={loadingAnimation}
          alt="로딩 중"
          css={generationUsageLoadingImageStyle}
        />
      </div>
    );
  }

  if (generationUsage.status === 'error') {
    return <div>에러가 발생했습니다.</div>;
  }

  return (
    <div css={generationUsageCardStyle}>
      <img src={eventAvailableIcon} alt="" css={generationUsageIconStyle} />

      <div css={generationUsageTextStyle(generationUsage.data)}>
        <span>오늘 남은 생성 </span>
        <strong>{generationUsage.data}회</strong>
      </div>
    </div>
  );
};

const homePageStyle = css`
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
  height: 100%;
  overflow: hidden;
  background-color: ${theme.colors.background};
`;

const pageHeaderStyle = css`
  width: 100%;
  height: 71px;
  padding: 0 20px;
  box-sizing: border-box;
  overflow: hidden;
`;

const logoButtonStyle = css`
  display: flex;
  align-items: center;
  width: 100%;
  height: 71px;
  border: none;
  background-color: transparent;
  cursor: pointer;
`;

const logoStyle = css`
  width: 106px;
  height: 71px;
`;

const homeContentStyle = css`
  position: relative;
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  gap: 5px;
  width: 100%;
  padding: 0 20px;
  min-height: 0;
  overflow: hidden;
  box-sizing: border-box;
`;

const monthHeaderStyle = css`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  height: 46px;
  padding: 10px 24px;
  border-bottom: 1px solid ${theme.colors.border};
  background-color: ${theme.colors.background};
`;

const diaryContentScrollStyle = css`
  flex: 1;
  width: 100%;
  min-height: 0;
  overflow-y: auto;
  box-sizing: border-box;
  scrollbar-width: none;
  -webkit-overflow-scrolling: touch;

  &::-webkit-scrollbar {
    display: none;
  }
`;

const monthInputStyle = css`
  position: relative;
  width: 100%;
  height: 100%;
  padding: 0 28px 0 0;
  border: none;
  outline: none;
  background-color: transparent;
  color: ${theme.colors.textPrimary};
  font-size: 18px;
  font-weight: 700;
  line-height: 26px;
  cursor: pointer;
  appearance: none;
  -webkit-appearance: none;

  &::-webkit-calendar-picker-indicator {
    display: none;
    -webkit-appearance: none;
  }
`;

const monthPickerStyle = css`
  position: relative;
  width: 130px;
  height: 26px;
`;

const monthArrowStyle = css`
  position: absolute;
  top: 1px;
  right: 0;
  width: 24px;
  height: 24px;
  pointer-events: none;
`;

const recordCountStyle = css`
  color: ${theme.colors.textSecondary};
  font-size: 14px;
  font-weight: 500;
`;

const generationUsageCardStyle = css`
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 48px;
  padding: 0 16px;
  border: 1px solid ${theme.colors.border};
  border-radius: 16px;
  background-color: ${theme.colors.generationCard};
`;

const generationUsageIconStyle = css`
  width: 24px;
  height: 24px;
`;

const generationUsageTextStyle = (remainingCount: number) => css`
  color: ${theme.colors.textPrimary};
  font-size: 14px;
  font-weight: 500;

  strong {
    color: ${remainingCount > 0 ? theme.colors.accent : theme.colors.danger};
    font-weight: 500;
  }
`;

const pageFeedbackStyle = css`
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  color: ${theme.colors.textPrimary};
  font-size: 16px;
  line-height: 26px;
  background-color: ${theme.colors.background};
`;

const loadingImageStyle = css`
  width: 160px;
  height: 160px;
`;

const generationUsageLoadingStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 48px;
`;

const generationUsageLoadingImageStyle = css`
  width: 40px;
  height: 40px;
`;
