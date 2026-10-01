import { DIARY_DETAIL_COPY } from './copy';
import { useErrorTracking } from '../../posthog/useErrorTracking';
import ActionButton from '../../shared/ActionButton';
import shareIcon from '../../assets/icons/share.svg';
import { useState } from 'react';
import {
  createDiaryShareLink,
  type DiaryShareLinkResponse,
} from '../../domain/diary/diaryShareLink';
import type { ApiRequest } from '../../shared/api';
import { useAnalytics } from '../../posthog/useAnalytics';

const DiaryShareButton = ({
  diaryId,
  diaryTitle,
}: {
  diaryId: string;
  diaryTitle: string;
}) => {
  const { captureError } = useErrorTracking();
  const [request, setRequest] = useState<ApiRequest<DiaryShareLinkResponse>>({
    status: 'idle',
  });
  const { track } = useAnalytics();
  const execute = async () => {
    track('diary_share_clicked', { diary_id: diaryId });

    setRequest({
      status: 'loading',
    });

    try {
      const diaryShareLinkResponse = await createDiaryShareLink({ diaryId });

      setRequest({ status: 'success', data: diaryShareLinkResponse });

      if (navigator.share) {
        await navigator.share({
          title: diaryTitle,
          url: diaryShareLinkResponse.shareUrl,
        });
      }
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        // 공유를 취소한 경우는 에러처리 범주가 아니므로 return
        return;
      }

      if (error instanceof Error) {
        captureError(error, {
          feature: 'diary',
          operation: 'share',
          diary_id: diaryId,
        });
        setRequest({
          status: 'error',
          error: error,
        });
        alert(error.message);
      }
    }
  };

  return (
    <ActionButton
      icon={<img src={shareIcon} alt="공유하기 아이콘" />}
      label={DIARY_DETAIL_COPY.shareAction}
      onClick={execute}
      disabled={request.status === 'loading'}
    />
  );
};

export default DiaryShareButton;
