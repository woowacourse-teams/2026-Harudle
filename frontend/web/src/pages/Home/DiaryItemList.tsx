import { css } from '@emotion/react';
import DiaryItemRow from './DiaryItemRow';
import type { MonthlyDiaryDay } from './HomePage';

const DiaryItemList = ({
  monthlyDiaryDays,
}: {
  monthlyDiaryDays: MonthlyDiaryDay[];
}) => {
  const diaries = monthlyDiaryDays.flatMap(({ date, items }) => {
    return items.map((item) => ({ date, item }));
  });

  return (
    <div css={diaryItemListStyle}>
      {diaries.map(({ date, item }, index) => (
        <DiaryItemRow
          key={item.id}
          date={date}
          diaryItem={item}
          isFirst={index === 0}
          isLast={index === diaries.length - 1}
        />
      ))}
    </div>
  );
};

export default DiaryItemList;

const diaryItemListStyle = css`
  position: relative;
  display: flex;
  flex-shrink: 0;
  flex-direction: column;
  gap: 20px;
  width: 100%;
  padding-left: 56px;
  box-sizing: border-box;
  overflow: hidden;

  &::before {
    position: absolute;
    top: 24px;
    bottom: 24px;
    left: 25px;
    width: 2px;
    background: linear-gradient(
      to bottom,
      #bfb0f0 0%,
      #dbd4f7 82%,
      #fac2a3 100%
    );
    content: '';
  }
`;
