import { useEffect, useState } from 'react';
import { useParams } from 'react-router';
import { API_BASE_URL, type ApiRequest } from '../../shared/api';

interface SharedDiary {
  title: string;
  diaryDate: string;
  imageUrl: string;
  imageUrlExpiresAt: string;
  createdAt: string;
}

const isRecord = (value: unknown): value is Record<string, unknown> => {
  return typeof value === 'object' && value !== null;
};

const isSharedDiary = (value: unknown): value is SharedDiary => {
  return (
    isRecord(value) &&
    typeof value.title === 'string' &&
    typeof value.diaryDate === 'string' &&
    typeof value.imageUrl === 'string' &&
    typeof value.imageUrlExpiresAt === 'string' &&
    typeof value.createdAt === 'string'
  );
};

const DiarySharePage = () => {
  const { shareId } = useParams();
  const [sharedDiary, setSharedDiary] = useState<ApiRequest<SharedDiary>>({
    status: 'idle',
  });

  useEffect(() => {
    const getSharedDiary = async (): Promise<void> => {
      setSharedDiary({ status: 'loading' });

      try {
        const response = await fetch(
          `${API_BASE_URL}/public/shares/${shareId}`,
        );

        if (!response.ok) {
          throw new Error('공유된 일기를 불러오지 못했습니다.');
        }

        const data: unknown = await response.json();

        if (!isSharedDiary(data)) {
          throw new Error('공유 일기 응답 형식이 일치하지 않습니다.');
        }

        setSharedDiary({ status: 'success', data });
      } catch (error: unknown) {
        setSharedDiary({
          status: 'error',
          error:
            error instanceof Error
              ? error
              : new Error('알 수 없는 에러가 발생했습니다.'),
        });
      }
    };

    void getSharedDiary();
  }, [shareId]);

  if (sharedDiary.status === 'idle' || sharedDiary.status === 'loading') {
    return <div>로딩중...</div>;
  }

  if (sharedDiary.status === 'error') {
    return <div>{sharedDiary.error.message}</div>;
  }

  return (
    <div>
      <div>{sharedDiary.data.diaryDate}</div>
      <div>{sharedDiary.data.title}</div>
      <img src={sharedDiary.data.imageUrl} alt={sharedDiary.data.title} />
    </div>
  );
};

export default DiarySharePage;
