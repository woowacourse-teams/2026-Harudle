import { css } from '@emotion/react';
import type { MonthlyDiaryItem } from './HomePage';
import { theme } from '../../styles/theme';
import { useNavigate } from 'react-router';

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
  date,
  diaryItem,
  isFirst,
  isLast,
}: {
  date: string;
  diaryItem: MonthlyDiaryItem;
  isFirst: boolean;
  isLast: boolean;
}) => {
  const navigate = useNavigate();
  const { id, title, thumbnailUrl } = diaryItem;
  const formattedDate = formatDiaryDate(date);

  return (
    <button
      type="button"
      css={diaryItemRowStyle}
      onClick={() => navigate(`/diaries/${id}`)}
    >
      <div css={timelineNodeStyle(isFirst, isLast)} />

      <div css={dateStyle}>
        <strong>{formattedDate.date}</strong>
        <span>{formattedDate.weekday}</span>
      </div>

      <div css={titleStyle}>{title}</div>

      <img src={thumbnailUrl} alt={`그림일기 ${date}`} css={thumbnailStyle} />
    </button>
  );
};

export default DiaryItemRow;

const diaryItemRowStyle = css`
  position: relative;
  display: grid;
  grid-template-columns: 58px minmax(0, 1fr) 130px;
  align-items: center;
  column-gap: 12px;
  width: 100%;
  height: 82px;
  border: none;
  background: none;
`;

const timelineNodeStyle = (isFirst: boolean, isLast: boolean) => css`
  position: absolute;
  top: 18px;
  left: -36px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background-color: ${
    isFirst ? theme.colors.accent : isLast ? '#FA8B55' : '#AAA6B2'
  };
  box-shadow: ${isFirst ? '0 0 0 8px #F0EBFF' : 'none'};
`;

const dateStyle = css`
  display: flex;
  flex-direction: column;
  align-self: center;
  gap: 2px;
  height: 64px;
  white-space: nowrap;

  strong {
    color: ${theme.colors.textPrimary};
    font-size: 15px;
    font-weight: 700;
    line-height: 24px;
  }

  span {
    color: ${theme.colors.textSecondary};
    font-size: 12px;
    font-weight: 400;
    line-height: 18px;
  }
`;

const titleStyle = css`
  display: -webkit-box;
  max-height: 60px;
  overflow: hidden;
  color: ${theme.colors.textPrimary};
  font-size: 15px;
  font-weight: 500;
  line-height: 24px;
  word-break: keep-all;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
`;

const thumbnailStyle = css`
  width: 130px;
  height: 80px;
  border: 1px solid ${theme.colors.border};
  border-radius: 12px;
  object-fit: cover;
  box-sizing: border-box;
`;
