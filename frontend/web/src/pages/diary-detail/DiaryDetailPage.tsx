import { DIARY_DETAIL_COPY } from './copy';
import { useParams } from 'react-router';
import DiaryDetailError from './DiaryDetailError';
import DiaryDetailContent from './DiaryDetailContent';

const DiaryDetailPage = () => {
  const { diaryId } = useParams();
  if (!diaryId) {
    return <DiaryDetailError errorMessage={DIARY_DETAIL_COPY.notFound} />;
  }

  return <DiaryDetailContent diaryId={diaryId} />;
};

export default DiaryDetailPage;
