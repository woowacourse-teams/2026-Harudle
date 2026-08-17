import { useNavigate } from 'react-router';
import type { ApiRequest } from '../../shared/api';
import FloatingActionButton from '../../shared/FloatingActionButton';
import DiaryItemRow from './DiaryItemRow';
import type { MonthlyDiariesResponse, MonthlyDiaryDay } from './HomePage/model';
import DiaryEmptyState from './DiaryEmptyState';
import loadingAnimation from '../../assets/images/loading-animation.webp';
import { css } from '@emotion/react';
import plusIcon from '../../assets/icons/plus.svg';

const DiaryItemList = ({
  monthlyDiariesRequest,
}: {
  monthlyDiariesRequest: ApiRequest<MonthlyDiariesResponse>;
}) => {
  const navigate = useNavigate();

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
    return <div>{monthlyDiariesRequest.error.message}</div>;
  }

  const { days } = monthlyDiariesRequest.data;

  return (
    <div>
      {isMonthlyDiaryExist(days) ? (
        <div>
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
            disabled={false}
          />
        </div>
      ) : (
        <DiaryEmptyState />
      )}
    </div>
  );
};

export default DiaryItemList;

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
