import BottomNavigation from '../../../shared/BottomNavigation';
import DiaryEmptyState from '../DiaryEmptyState';
import DiaryItemList from '../DiaryItemList';
import FloatingActionButton from '../../../shared/FloatingActionButton';
import { useNavigate } from 'react-router';
import type { YearMonth } from './model';
import harudleLogo from '../../../assets/images/harudle-logo.png';
import useSelectedYearMonth from './useSelectedYearMonth';
import useMonthlyDiaries from './useMonthlyDiaries';
import useGenrationUsage from './useGenrationUsage';

const formatYearMonthToString = ({ year, month }: YearMonth): string => {
  return `${year}-${month.toString().padStart(2, '0')}`;
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
    <div>
      <header>
        <button onClick={() => navigate('/')}>
          <img src={harudleLogo} alt="하루들" />
        </button>
      </header>

      <main>
        <input
          type="month"
          value={formatYearMonthToString(selectedYearMonth)}
          onChange={handleYearMonthChange}
        />
        <div>{days.length}개의 기록</div>
      </main>

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

  return <div>오늘 남은 생성 {generationUsage}회</div>;
};
