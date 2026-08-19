import { useState } from 'react';
import {
  API_BASE_URL,
  isProblemDetails,
  RequestError,
  type ApiRequest,
} from '../../shared/api';
import { useNavigate } from 'react-router';
import { authFetch } from '../../shared/auth';

const useDiaryDelete = ({ diaryId }: { diaryId: string | undefined }) => {
  const [diaryDeleteRequest, setDiaryDeleteRequest] = useState<
    ApiRequest<void>
  >({
    status: 'idle',
  });
  const navigate = useNavigate();

  const handleDiaryDelete = async (): Promise<void> => {
    setDiaryDeleteRequest({ status: 'loading' });

    try {
      const response = await authFetch(`${API_BASE_URL}/diaries/${diaryId}`, {
        method: 'DELETE',
      });

      if (!response.ok) {
        const errorData = await response.json();
        if (isProblemDetails(errorData)) {
          throw new RequestError(errorData);
        }

        throw new Error('알 수 없는 에러가 발생했습니다.');
      }

      setDiaryDeleteRequest({ status: 'success', data: undefined });
      navigate('/');
    } catch (error: unknown) {
      if (error instanceof Error) {
        setDiaryDeleteRequest({
          status: 'error',
          error: error,
        });
      }
    }
  };

  return { diaryDeleteRequest, handleDiaryDelete };
};

export default useDiaryDelete;
