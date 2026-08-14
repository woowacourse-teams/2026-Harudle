import type { MonthlyDiary } from './HomePage';

const DiaryItemRow = ({ monthlyDiary }: { monthlyDiary: MonthlyDiary }) => {
  const { date, title, thumbnailUrl } = monthlyDiary;
  return (
    <div>
      <div>{date}</div>
      <div>{title}</div>
      <img src={thumbnailUrl} alt={`그림일기 ${date}`} />
    </div>
  );
};

export default DiaryItemRow;
