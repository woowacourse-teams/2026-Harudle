import { useNavigate } from 'react-router';
import type { ApiRequest } from '../../shared/api';
import FloatingActionButton from '../../shared/FloatingActionButton';
import DiaryItemRow from './DiaryItemRow';
import type { MonthlyDiariesResponse, MonthlyDiaryDay } from './HomePage/model';
import DiaryEmptyState from './DiaryEmptyState';
import loadingAnimation from '../../assets/images/loading-animation.webp';
import { css } from '@emotion/react';
import plusIcon from '../../assets/icons/plus.svg';
import DiaryError from './DiaryError';
import { useDiaryGenerateContext } from '../diary-generating/DiaryGenerateContext';
import DiaryItemRowSkeleton from './DiaryItemRowSkeleton';
import { useEffect } from 'react';

const DiaryItemList = ({
  monthlyDiariesRequest,
  getMonthlyDiaries,
}: {
  monthlyDiariesRequest: ApiRequest<MonthlyDiariesResponse>;
  getMonthlyDiaries: () => Promise<void>;
}) => {
  const navigate = useNavigate();
  const { diaryGenerateRequest } = useDiaryGenerateContext();

  useEffect(() => {
    if (diaryGenerateRequest.status === 'error') {
      alert(diaryGenerateRequest.error.message);
    }

    if (diaryGenerateRequest.status === 'success') {
      void getMonthlyDiaries();
    }
  }, [diaryGenerateRequest.status]);

  const isMonthlyDiaryExist = (monthlyDiaryDays: MonthlyDiaryDay[]) => {
    return monthlyDiaryDays.some((day) => day.exist);
  };

  if (
    monthlyDiariesRequest.status === 'idle' ||
    monthlyDiariesRequest.status === 'loading'
  ) {
    return (
      <div css={loadingAnimationBoxStyle}>
        <img src={loadingAnimation} alt="로딩 중" css={loadingImageStyle} />
      </div>
    );
  }

  if (monthlyDiariesRequest.status === 'error') {
    return <DiaryError errorMessage={monthlyDiariesRequest.error.message} />;
  }

  const { days } = monthlyDiariesRequest.data;
  const showSkeleton = diaryGenerateRequest.status === 'loading';

  return (
    <div>
      {isMonthlyDiaryExist(days) ? (
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
      ) : showSkeleton ? (
        <DiaryItemRowSkeleton />
      ) : (
        <DiaryEmptyState />
      )}
    </div>
  );
};

export default DiaryItemList;

const diaryListStyle = css`
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 100%;
  padding-bottom: 76px;
`;

const loadingAnimationBoxStyle = css`
  display: flex;
  justify-content: center;
  align-items: center;
  width: 100%;
  height: 100%;
`;

const loadingImageStyle = css`
  width: 140px;
  height: 140px;
`;

const plusIconStyle = css`
  width: 24px;
  height: 24px;
`;
