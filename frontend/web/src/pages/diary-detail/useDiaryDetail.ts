import { useEffect, useState } from 'react';
import {
  API_BASE_URL,
  isProblemDetails,
  RequestError,
  type ApiRequest,
} from '../../shared/api';
import { authFetch } from '../../shared/auth';

interface DiaryDetail {
  id: string;
  diaryDate: string;
  sourceText: string;
  createdAt: string;
  generation: {
    id: string;
    status: 'SUCCEEDED';
    title: string;
    imageUrl: string;
    imageUrlExpiresAt: string;
    completedAt: string;
  };
}

const isDiaryDetail = (value: unknown): value is DiaryDetail => {
  return (
    typeof value === 'object' &&
    value !== null &&
    'id' in value &&
    typeof value.id === 'string' &&
    'diaryDate' in value &&
    typeof value.diaryDate === 'string' &&
    'sourceText' in value &&
    typeof value.sourceText === 'string' &&
    'createdAt' in value &&
    typeof value.createdAt === 'string' &&
    'generation' in value &&
    typeof value.generation === 'object' &&
    value.generation !== null &&
    'id' in value.generation &&
    typeof value.generation.id === 'string' &&
    'status' in value.generation &&
    value.generation.status === 'SUCCEEDED' &&
    'title' in value.generation &&
    typeof value.generation.title === 'string' &&
    'imageUrl' in value.generation &&
    typeof value.generation.imageUrl === 'string' &&
    'imageUrlExpiresAt' in value.generation &&
    typeof value.generation.imageUrlExpiresAt === 'string' &&
    'completedAt' in value.generation &&
    typeof value.generation.completedAt === 'string'
  );
};

const useDiaryDetail = ({ diaryId }: { diaryId: string | undefined }) => {
  const [diaryDetailReqeust, setDiaryDetailRequest] = useState<
    ApiRequest<DiaryDetail>
  >({
    status: 'idle',
  });

  useEffect(() => {
    const getDiaryDetail = async (): Promise<void> => {
      setDiaryDetailRequest({
        status: 'loading',
      });
      try {
        const response = await authFetch(`${API_BASE_URL}/diaries/${diaryId}`);

        if (!response.ok) {
          const errorData = await response.json();
          if (isProblemDetails(errorData)) {
            throw new RequestError(errorData);
          }

          throw new Error('알 수 없는 에러가 발생했습니다.');
        }

        const data: unknown = await response.json();

        if (!isDiaryDetail(data)) {
          throw new Error('MonthlyDiaries 응답 형식이 일치하지 않습니다.');
        }

        setDiaryDetailRequest({
          status: 'success',
          data: data,
        });
      } catch (error: unknown) {
        if (error instanceof Error) {
          setDiaryDetailRequest({
            status: 'error',
            error: error,
          });
        }
      }
    };

    void getDiaryDetail();
  }, [diaryId]);

  return { diaryDetailReqeust };
};

export default useDiaryDetail;
