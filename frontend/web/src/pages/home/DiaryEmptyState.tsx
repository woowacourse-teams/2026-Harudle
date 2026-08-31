import { useNavigate, useSearchParams } from 'react-router';
import ActionButton from '../../shared/ActionButton';
import emptyPersonAndDog from '../../assets/images/empty-person-and-dog.png';
import { css } from '@emotion/react';
import { theme } from '../../styles/theme';
import { getToday } from '../../shared/utils';

const DiaryEmptyState = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const yearMonthParam = searchParams.get('yearMonth');
  const today = getToday();
  const [year, month] = yearMonthParam
    ? yearMonthParam.split('-').map(Number)
    : [today.year, today.month];

  return (
    <div css={emptyStateStyle}>
      <div css={emptyStateContentStyle}>
        <img
          src={emptyPersonAndDog}
          alt="사람과 강아지 일러스트"
          css={illustrationStyle}
        />

        <div css={emptyStateDescriptionContentStyle}>
          <div css={emptyStateTitleStyle}>
            {year}년 {month}월에는 기록이 없어요
          </div>
          <div css={emptyStateDescriptionStyle}>
            다른 달을 살펴보거나 오늘의 이야기를 남겨보세요!
          </div>
        </div>
      </div>

      <ActionButton
        label="새 일기 쓰기"
        onClick={() => {
          navigate('/diary-write');
        }}
      />
    </div>
  );
};

export default DiaryEmptyState;

const emptyStateStyle = css`
  display: flex;
  flex: 1;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  width: 100%;
  height: 100%;
`;

const emptyStateContentStyle = css`
  display: flex;
  flex-shrink: 0;
  flex-direction: column;
  align-items: center;
  width: 100%;
`;

const illustrationStyle = css`
  width: 300px;
  height: 230px;
  object-fit: cover;
`;

const emptyStateDescriptionContentStyle = css`
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  width: 100%;
  margin-bottom: 30px;
  text-align: center;
`;

const emptyStateTitleStyle = css`
  width: 100%;
  color: ${theme.colors.text.primary};
  font-size: 22px;
  font-weight: 700;
  line-height: 32px;
`;

const emptyStateDescriptionStyle = css`
  width: 100%;
  color: ${theme.colors.text.secondary};
  font-size: 15px;
  font-weight: 400;
  word-break: keep-all;
`;
