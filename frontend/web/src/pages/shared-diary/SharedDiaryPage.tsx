import { useParams } from 'react-router';

import SharedDiaryError from './SharedDiaryError';
import SharedDiaryContent from './SharedDiaryContent';

const SharedDiaryPage = () => {
  const { shareId } = useParams();

  if (!shareId) {
    return <SharedDiaryError errorMessage="공유 링크를 확인해 주세요." />;
  }

  return <SharedDiaryContent shareId={shareId} />;
};

export default SharedDiaryPage;
