import DiaryItemRow from './DiaryItemRow';
import type { MonthlyDiaryDay } from './HomePage/model';

const DiaryItemList = ({
  monthlyDiaryDays,
}: {
  monthlyDiaryDays: MonthlyDiaryDay[];
}) => {
  return (
    <div>
      {monthlyDiaryDays.map((day) => {
        return day.items.map((diary) => (
          <DiaryItemRow key={diary.id} monthlyDiary={diary} date={day.date} />
        ));
      })}
    </div>
  );
};

export default DiaryItemList;
