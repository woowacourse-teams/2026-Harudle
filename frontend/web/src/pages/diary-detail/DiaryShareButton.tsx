import ActionButton from '../../shared/ActionButton';
import shareIcon from '../../assets/icons/share.svg';
import {
  API_BASE_URL,
  isProblemDetails,
  RequestError,
  type ApiRequest,
} from '../../shared/api';
import { useState } from 'react';

const DiaryShareButton = ({ diaryId }: { diaryId: string | undefined }) => {
  const [shareRequest, setShareRequest] = useState<ApiRequest<void>>({
    status: 'idle',
  });
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

      setShareRequest({ status: 'success', data: undefined });
    } catch (error) {
      if (error instanceof Error) {
        setShareRequest({
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
      label="공유하기"
      onClick={handleDiaryShare}
      disabled={shareRequest.status === 'loading'}
    />
  );
};

export default DiaryShareButton;
