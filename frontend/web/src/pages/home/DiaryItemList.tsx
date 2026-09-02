import { useNavigate } from 'react-router';

import FloatingActionButton from '../../shared/FloatingActionButton';
import DiaryItemRow from './DiaryItemRow';
import DiaryEmptyState from './DiaryEmptyState';

import { css } from '@emotion/react';
import plusIcon from '../../assets/icons/plus.svg';
import DiaryError from './DiaryError';
import { useDiaryGenerateContext } from '../diary-generating/DiaryGenerateContext';
import DiaryItemRowSkeleton from './DiaryItemRowSkeleton';
import { useEffect } from 'react';
import { theme } from '../../styles/theme';
import useMonthlyDiaries from './HomePage/useMonthlyDiaries';
import type { MonthlyDiaryDay } from '../../domain/diary/monthlyDiaries';
import type { Month } from '../../shared/utils';
import LoadingSpinner from '../../shared/LoadingSpinner';

const DiaryItemList = ({ year, month }: { year: number; month: Month }) => {
  const navigate = useNavigate();
  const { request, refetch } = useMonthlyDiaries({ year, month });
  const { diaryGenerateRequest, resetDiaryGenerateRequest } =
    useDiaryGenerateContext();

  /**
   * 일기 생성 중 홈 화면으로 이동했을 떄
   * 홈 화면에서는 최초 1번만 일기 생성 성공 또는 실패 후처리를 하면 되므로
   * 비동기 상태를 초기화한다.
   */
  useEffect(() => {
    if (diaryGenerateRequest.status === 'error') {
      resetDiaryGenerateRequest();
      alert(diaryGenerateRequest.error.message);
    }

    if (diaryGenerateRequest.status === 'success') {
      resetDiaryGenerateRequest();
      void refetch({ showLoading: false });
    }
  }, [diaryGenerateRequest.status]);

  const isMonthlyDiaryExist = (monthlyDiaryDays: MonthlyDiaryDay[]) => {
    return monthlyDiaryDays.some((day) => day.exist);
  };

  if (request.status === 'idle' || request.status === 'loading') {
    return <LoadingSpinner />;
  }

  if (request.status === 'error') {
    return <DiaryError errorMessage={request.error.message} />;
  }

  const { days } = request.data;
  const showSkeleton = diaryGenerateRequest.status === 'loading';

  const monthlyDiaryCount =
    request.status === 'success'
      ? request.data.days.reduce((count, day) => count + day.items.length, 0)
      : 0;

  return (
    <div>
      {isMonthlyDiaryExist(days) ? (
        <>
          <header css={diaryListHeaderStyle}>
            <h2 css={diaryListTitleStyle}>기록</h2>
            <span css={monthlyDiaryCountStyle}>총 {monthlyDiaryCount}개</span>
          </header>
          <div css={diaryListStyle}>
            {showSkeleton && <DiaryItemRowSkeleton />}

            {days.map((day) => {
              return day.items.map((diary) => (
                <DiaryItemRow
                  key={diary.id}
                  monthlyDiary={diary}
                  date={day.date}
                  onClick={() => navigate(`/diary/${diary.id}`)}
                />
              ));
            })}

            <FloatingActionButton
              onClick={() => {
                navigate('/diary-write');
              }}
              icon={<img css={plusIconStyle} src={plusIcon} />}
              disabled={showSkeleton}
            />
          </div>
        </>
      ) : showSkeleton ? (
        <DiaryItemRowSkeleton />
      ) : (
        <DiaryEmptyState />
      )}
    </div>
  );
};

export default DiaryItemList;

const diaryListHeaderStyle = css`
  display: flex;
  justify-content: start;
  align-items: center;
  gap: 8px;
  min-height: 24px;
  padding-left: 10px;
`;

const diaryListTitleStyle = css`
  color: ${theme.colors.text.primary};
  font-size: 15px;
  font-weight: 600;
  line-height: 22px;
`;

const monthlyDiaryCountStyle = css`
  color: ${theme.colors.text.secondary};
  font-size: 13px;
  font-weight: 400;
  line-height: 20px;
`;

const diaryListStyle = css`
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 100%;
  padding-bottom: 76px;
`;

const plusIconStyle = css`
  width: 24px;
  height: 24px;
`;
