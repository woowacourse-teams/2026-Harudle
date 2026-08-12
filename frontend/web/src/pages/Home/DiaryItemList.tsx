import DiaryItemRow from './DiaryItemRow';
import type { MonthlyDiary } from './HomePage';

// TODO: 백엔드에서 uuid보내주면 그때 key값 uuid로 변경하기!

const DiaryItemList = ({
  monthlyDiaries,
}: {
  monthlyDiaries: MonthlyDiary[];
}) => {
  return (
    <div>
      {monthlyDiaries.map((diary) => (
        <DiaryItemRow
          key={`${diary.thumbnailUrl}-${diary.title}`}
          monthlyDiary={diary}
        />
      ))}
    </div>
  );
};

export default DiaryItemList;
