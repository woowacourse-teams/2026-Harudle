import { useEffect, useState } from 'react';
import {
  API_BASE_URL,
  isProblemDetails,
  RequestError,
  type ApiRequest,
} from '../../shared/api';

interface SharedDiary {
  title: string;
  diaryDate: string;
  imageUrl: string;
  imageUrlExpiresAt: string;
  createdAt: string;
}

const isSharedDiary = (value: unknown): value is SharedDiary => {
  return (
    typeof value === 'object' &&
    value !== null &&
    'title' in value &&
    typeof value.title === 'string' &&
    'diaryDate' in value &&
    typeof value.diaryDate === 'string' &&
    'imageUrl' in value &&
    typeof value.imageUrl === 'string' &&
    'imageUrlExpiresAt' in value &&
    typeof value.imageUrlExpiresAt === 'string' &&
    'createdAt' in value &&
    typeof value.createdAt === 'string'
  );
};

const useDiaryShare = ({ shareId }: { shareId: string | undefined }) => {
  const [sharedDiaryRequest, setSharedDiaryRequest] = useState<
    ApiRequest<SharedDiary>
  >({
    status: 'idle',
  });

  useEffect(() => {
    const getMonthlyDiaries = async (): Promise<void> => {
      setSharedDiaryRequest({
        status: 'loading',
      });
      try {
        const response = await fetch(
          `${API_BASE_URL}/public/shares/${shareId}`,
        );

        if (!response.ok) {
          const errorData = await response.json();
          if (isProblemDetails(errorData)) {
            throw new RequestError(errorData);
          }

          throw new Error('알 수 없는 에러가 발생했습니다.');
        }

        const data: unknown = await response.json();

        if (!isSharedDiary(data)) {
          throw new Error('SharedDiary 응답 형식이 일치하지 않습니다.');
        }

        setSharedDiaryRequest({
          status: 'success',
          data: data,
        });
      } catch (error: unknown) {
        if (error instanceof Error) {
          setSharedDiaryRequest({
            status: 'error',
            error: error,
          });
        }
      }
    };

    void getMonthlyDiaries();
  }, [shareId]);

  return { sharedDiaryRequest };
};

export default useDiaryShare;
