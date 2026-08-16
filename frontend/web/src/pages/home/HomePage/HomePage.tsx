import BottomNavigation from '../../../shared/BottomNavigation';
import DiaryEmptyState from '../DiaryEmptyState';
import DiaryItemList from '../DiaryItemList';
import FloatingActionButton from '../../../shared/FloatingActionButton';
import { useNavigate } from 'react-router';
import type { MonthlyDiaryDay, YearMonth } from './model';
import harudleLogo from '../../../assets/images/harudle-logo.png';
import useSelectedYearMonth from './useSelectedYearMonth';
import useMonthlyDiaries from './useMonthlyDiaries';
import useGenrationUsage from './useGenrationUsage';
import { css } from '@emotion/react';
import { theme } from '../../../styles/theme';
import eventAvailableIcon from '../../../assets/icons/event-available.svg';

const formatYearMonthToString = ({ year, month }: YearMonth): string => {
  return `${year}-${month.toString().padStart(2, '0')}`;
};

const isMonthlyDiaryExist = (monthlyDiaryDays: MonthlyDiaryDay[]) => {
  return monthlyDiaryDays.some((day) => day.exist);
};

const HomePage = () => {
  const navigate = useNavigate();
  const { selectedYearMonth, handleYearMonthChange } = useSelectedYearMonth(
    2026,
    8,
  );
  const { monthlyDiariesRequest } = useMonthlyDiaries({ ...selectedYearMonth });

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
            value={formatYearMonthToString(selectedYearMonth)}
            onChange={handleYearMonthChange}
          />
          <span>{days.length}개의 기록</span>
        </div>

        <RemainingGenerationUsageCard />

        <section css={diaryContentStyle}>
          {isMonthlyDiaryExist(days) ? (
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
        </section>
      </main>

      <BottomNavigation />
    </div>
  );
};

export default HomePage;

const RemainingGenerationUsageCard = () => {
  const { generationUsageRequest } = useGenrationUsage();

  if (
    generationUsageRequest.status === 'idle' ||
    generationUsageRequest.status === 'loading'
  ) {
    return <div>로딩중...</div>;
  }

  if (generationUsageRequest.status === 'error') {
    return <div>{generationUsageRequest.error.message}</div>;
  }

  const generationUsage = generationUsageRequest.data;

  return (
    <div css={generationUsageCardStyle}>
      <img src={eventAvailableIcon} />
      <span>
        오늘 남은 생성{' '}
        <span css={generationUsageTextStyle(generationUsage > 0)}>
          {generationUsage}회
        </span>
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
  gap: 8px;
  min-height: 0px;
  padding: 20px 20px 0 20px;
`;

const contentHeaderStyle = css`
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;

  span {
    color: ${theme.colors.text.secondary};
    font-size: 14px;
    font-weight: 500;
  }
`;

const monthInputStyle = css`
  width: 135px;
  height: 100%;
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

const generationUsageCardStyle = css`
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 48px;
  padding: 0 16px;
  border: 1px solid ${theme.colors.border.primary};
  border-radius: 16px;
  background-color: #faf6fe;
`;

const generationUsageTextStyle = (isLeft: boolean) => css`
  color: ${isLeft ? theme.colors.text.brand : theme.colors.text.danger};
`;
