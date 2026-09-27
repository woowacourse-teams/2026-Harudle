import { useParams } from 'react-router';
import DiaryDetailError from './DiaryDetailError';
import DiaryDetailContent from './DiaryDetailContent';

const DiaryDetailPage = () => {
  const { diaryId } = useParams();
  if (!diaryId) {
    return <DiaryDetailError errorMessage="일기가 존재하지 않습니다." />;
  }

  return <DiaryDetailContent diaryId={diaryId} />;
};

export default DiaryDetailPage;
