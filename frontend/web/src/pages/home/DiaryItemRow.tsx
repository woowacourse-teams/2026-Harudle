import { css } from '@emotion/react';
import type { MonthlyDiaryDay, MonthlyDiaryItem } from './HomePage/model';
import { theme } from '../../styles/theme';

const WEEKDAYS = [
  '일요일',
  '월요일',
  '화요일',
  '수요일',
  '목요일',
  '금요일',
  '토요일',
];

const formatDiaryDate = (date: string) => {
  const [year, month, day] = date.split('-').map(Number);
  const weekday = WEEKDAYS[new Date(year, month - 1, day).getDay()];

  return {
    date: `${String(month).padStart(2, '0')}.${String(day).padStart(2, '0')}`,
    weekday,
  };
};

const DiaryItemRow = ({
  monthlyDiary,
  date,
}: {
  monthlyDiary: MonthlyDiaryItem;
  date: MonthlyDiaryDay['date'];
}) => {
  const { title, thumbnailUrl } = monthlyDiary;
  const { date: formattedDate, weekday } = formatDiaryDate(date);
  return (
    <button css={diaryItemRowStyle}>
      <div css={dateStyle}>
        <strong>{formattedDate}</strong>
        <span>{weekday}</span>
      </div>

      <span css={titleStyle}>{title}</span>

      <img src={thumbnailUrl} alt={`그림일기 ${date}`} css={thumbnailStyle} />
    </button>
  );
};

export default DiaryItemRow;

const diaryItemRowStyle = css`
  position: relative;
  display: flex;
  gap: 12px;
  justify-content: space-between;
  align-items: center;
  width: 100%;
  height: 82px;
  padding-left: 45px;
  border: none;
  background: none;
  cursor: pointer;

  &::before {
    content: '';
    position: absolute;
    top: 0;
    bottom: 0;
    left: 25px;
    width: 2px;
    background: #ded8ff;
  }

  &::after {
    content: '';
    position: absolute;
    top: 32px;
    left: 26px;
    width: 12px;
    height: 12px;
    border-radius: 50%;
    background: #aaa8b2;
    transform: translate(-50%, -50%);
    z-index: 1;
    pointer-events: none;
  }

  &:active {
    background-color: #fafafb;
  }
`;

const dateStyle = css`
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-self: center;
  gap: 2px;
  height: 64px;
  white-space: nowrap;

  strong {
    color: ${theme.colors.text.primary};
    font-size: 15px;
    font-weight: 700;
    line-height: 24px;
  }

  span {
    color: ${theme.colors.text.secondary};
    font-size: 12px;
    font-weight: 400;
    line-height: 18px;
  }
`;

const titleStyle = css`
  max-height: 60px;
  overflow: hidden;
  color: ${theme.colors.text.primary};
  font-size: 15px;
  font-weight: 500;
  line-height: 24px;
  word-break: keep-all;
`;

const thumbnailStyle = css`
  width: 70px;
  height: 70px;
  border-radius: 12px;
  object-fit: contain;
  box-sizing: border-box;
`;
