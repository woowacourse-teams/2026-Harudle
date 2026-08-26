import BottomNavigation from '../../../shared/BottomNavigation';
import DiaryItemList from '../DiaryItemList';
import { useNavigate } from 'react-router';
import type { YearMonth } from './model';
import harudleLogo from '../../../assets/images/harudle-logo.png';
import useSelectedYearMonth from './useSelectedYearMonth';
import useMonthlyDiaries from './useMonthlyDiaries';
import useGenrationUsage from './useGenrationUsage';
import { css } from '@emotion/react';
import { theme } from '../../../styles/theme';
import { getToday } from '../../../shared/utils';
import { useDiaryGenerateContext } from '../../diary-generating/DiaryGenerateContext';
import { useEffect } from 'react';
import StreakSummaryCard from './StreakSummaryCard';

const formatYearMonthToString = ({ year, month }: YearMonth): string => {
  return `${year}-${month.toString().padStart(2, '0')}`;
};

const HomePage = () => {
  const navigate = useNavigate();
  const { selectedYearMonth, handleYearMonthChange } = useSelectedYearMonth(
    getToday().year,
    getToday().month,
  );
  const { monthlyDiariesRequest, getMonthlyDiaries } = useMonthlyDiaries({
    ...selectedYearMonth,
  });

  const monthlyDiaryCount =
    monthlyDiariesRequest.status === 'success'
      ? monthlyDiariesRequest.data.days.reduce(
          (count, day) => count + day.items.length,
          0,
        )
      : 0;

  return (
    <div css={homePageStyle}>
      <header css={pageHeaderStyle}>
        <button css={logoButtonStyle} onClick={() => navigate('/')}>
          <img css={logoStyle} src={harudleLogo} alt="하루들" />
        </button>
      </header>

      <main css={homePageContentStyle}>
        <div css={contentHeaderStyle}>
          <input
            css={monthInputStyle}
            type="month"
            aria-label="조회할 월"
            value={formatYearMonthToString(selectedYearMonth)}
            onChange={handleYearMonthChange}
          />
          <div css={contentSummaryStyle}>
            <RemainingGenerationUsage />
            <span css={monthlyDiaryCountStyle}>
              {monthlyDiaryCount}개의 기록
            </span>
          </div>
        </div>

        <StreakSummaryCard />

        <section css={diaryContentStyle}>
          <DiaryItemList
            monthlyDiariesRequest={monthlyDiariesRequest}
            getMonthlyDiaries={getMonthlyDiaries}
          />
        </section>
      </main>

      <BottomNavigation />
    </div>
  );
};

export default HomePage;

const RemainingGenerationUsage = () => {
  const { generationUsageRequest, getRemainingGenerationUsageCard } =
    useGenrationUsage();

  const { diaryGenerateRequest } = useDiaryGenerateContext();

  useEffect(() => {
    if (diaryGenerateRequest.status === 'success') {
      void getRemainingGenerationUsageCard();
    }
  }, [diaryGenerateRequest.status, getRemainingGenerationUsageCard]);

  const remainingCount =
    generationUsageRequest.status === 'success'
      ? generationUsageRequest.data
      : null;

  return (
    <div css={remainingGenerationUsageStyle} aria-live="polite">
      <span>
        오늘 남은 생성{' '}
        <strong css={generationUsageTextStyle(remainingCount)}>
          {remainingCount ?? '-'}
        </strong>
        회
      </span>
    </div>
  );
};

const homePageStyle = css`
  display: flex;
  flex-direction: column;
  height: 100%;
`;

const pageHeaderStyle = css`
  width: 100%;
  height: 71px;
  box-sizing: border-box;
`;

const logoButtonStyle = css`
  display: flex;
  justify-content: center;
  align-items: center;
  width: 100%;
  height: 100%;
  border: none;
  background: none;
  cursor: pointer;
`;

const logoStyle = css`
  width: 106px;
  height: 71px;
`;

const homePageContentStyle = css`
  position: relative;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0px;
  padding: 20px 20px 0 20px;
`;

const contentHeaderStyle = css`
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  width: 100%;
`;

const contentSummaryStyle = css`
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
  min-width: 0;
`;

const monthlyDiaryCountStyle = css`
  color: ${theme.colors.text.secondary};
  font-size: 14px;
  font-weight: 500;
`;

const monthInputStyle = css`
  width: 135px;
  height: 28px;
  border: none;
  outline: none;
  background-color: transparent;
  color: ${theme.colors.text.primary};
  font-size: 18px;
  font-weight: 700;
  line-height: 26px;
  cursor: pointer;
`;

const diaryContentStyle = css`
  flex: 1;
  overflow-y: auto;
`;

const remainingGenerationUsageStyle = css`
  display: flex;
  align-items: center;
  color: ${theme.colors.text.primary};
  font-size: 15px;
  font-weight: 500;
  line-height: 22px;
  white-space: nowrap;
`;

const generationUsageTextStyle = (remainingCount: number | null) => css`
  color: ${
    remainingCount === null
      ? theme.colors.text.secondary
      : remainingCount > 0
        ? theme.colors.text.brand
        : theme.colors.text.danger
  };
  font-weight: 800;
`;
