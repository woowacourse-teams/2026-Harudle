import ActionButton from '../../shared/ActionButton';
import shareIcon from '../../assets/icons/share.svg';
import {
  API_BASE_URL,
  isProblemDetails,
  RequestError,
  type ApiRequest,
} from '../../shared/api';
import { useState } from 'react';

interface DiaryShareResponse {
  shareId: string;
  shareUrl: string;
  createdAt: string;
}

const isDiaryShareResponse = (value: unknown): value is DiaryShareResponse => {
  return (
    typeof value === 'object' &&
    value !== null &&
    'shareId' in value &&
    typeof value.shareId === 'string' &&
    'shareUrl' in value &&
    typeof value.shareUrl === 'string' &&
    'createdAt' in value &&
    typeof value.createdAt === 'string'
  );
};

const DiaryShareButton = ({
  diaryId,
  diaryTitle,
}: {
  diaryId: string | undefined;
  diaryTitle: string;
}) => {
  const [shareRequest, setShareRequest] = useState<
    ApiRequest<DiaryShareResponse>
  >({ status: 'idle' });
  const handleDiaryShare = async () => {
    setShareRequest({
      status: 'loading',
    });

    try {
      const response = await fetch(
        `${API_BASE_URL}/diaries/${diaryId}/share-link`,
        {
          method: 'PUT',
        },
      );

      if (!response.ok) {
        const errorData = await response.json();
        if (isProblemDetails(errorData)) {
          throw new RequestError(errorData);
        }

        throw new Error('알 수 없는 에러가 발생했습니다.');
      }

      const data: unknown = await response.json();

      if (!isDiaryShareResponse(data)) {
        throw new Error('DiaryShare 응답 형식이 일치하지 않습니다.');
      }
      setShareRequest({ status: 'success', data });

      if (navigator.share) {
        await navigator.share({
          title: diaryTitle,
          url: data.shareUrl,
        });
      }
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        // 공유를 취소한 경우는 에러처리 범주가 아니므로 return
        return;
      }

      if (error instanceof Error) {
        setShareRequest({
          status: 'error',
          error: error,
        });
        alert(error.message);
      }
    } finally {
      setShareRequest({
        status: 'idle',
      });
    }
  };

  return (
    <ActionButton
      icon={<img src={shareIcon} alt="공유하기 아이콘" />}
      label="공유하기"
      onClick={handleDiaryShare}
      disabled={shareRequest.status === 'loading'}
    />
  );
};

export default DiaryShareButton;
