import type { MonthlyDiaryDay, MonthlyDiaryItem } from './HomePage/HomePage';

const DiaryItemRow = ({
  monthlyDiary,
  date,
}: {
  monthlyDiary: MonthlyDiaryItem;
  date: MonthlyDiaryDay['date'];
}) => {
  const { title, thumbnailUrl } = monthlyDiary;
  return (
    <div>
      <div>{date}</div>
      <div>{title}</div>
      <img src={thumbnailUrl} alt={`그림일기 ${date}`} />
    </div>
  );
};

export default DiaryItemRow;
