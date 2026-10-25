import { ALBUM_COPY } from './copy';
import { useNavigate } from 'react-router';

import FloatingActionButton from '../../shared/FloatingActionButton';
import DiaryItemRow from './DiaryItemRow';
import DiaryEmptyState from './DiaryEmptyState';

import { css } from '@emotion/react';
import plusIcon from '../../assets/icons/plus.svg';
import DiaryError from './DiaryError';
import DiaryItemRowSkeleton from './DiaryItemRowSkeleton';
import { theme } from '../../styles/theme';
import useMonthlyDiaries from './useMonthlyDiaries';
import type { MonthlyDiaryDay } from '../../domain/diary/monthlyDiaries';
import type { Month } from '../../shared/utils';
import LoadingSpinner from '../../shared/LoadingSpinner';
import useDiaryGenerationId from '../../shared/useDiaryGenerationId';
import { useEffect } from 'react';

const DiaryItemList = ({ year, month }: { year: number; month: Month }) => {
  const navigate = useNavigate();
  const { request, refetch } = useMonthlyDiaries({ year, month });
  const generationId = useDiaryGenerationId();
  const showSkeleton = generationId !== null;

  useEffect(() => {
    refetch();
  }, [generationId]);

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

  const monthlyDiaryCount =
    request.status === 'success'
      ? request.data.days.reduce((count, day) => count + day.items.length, 0)
      : 0;

  return (
    <div>
      {isMonthlyDiaryExist(days) ? (
        <>
          <header css={diaryListHeaderStyle}>
            <h2 css={diaryListTitleStyle}>{ALBUM_COPY.listTitle}</h2>
            <span css={monthlyDiaryCountStyle}>
              {ALBUM_COPY.monthlyCount.before}
              {monthlyDiaryCount}
              {ALBUM_COPY.monthlyCount.after}
            </span>
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
  word-break: keep-all;
  color: ${theme.colors.foreground.neutral};
  font-size: 15px;
  font-weight: 600;
  line-height: 22px;
`;

const monthlyDiaryCountStyle = css`
  word-break: keep-all;
  color: ${theme.colors.foreground.neutralMuted};
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
