import { useCallback, useState } from 'react';
import { useNavigate } from 'react-router';
import { deleteDiary } from '../../domain/diary/diaryDelete';
import type { ApiRequest } from '../../shared/api';

const useDiaryDelete = ({ diaryId }: { diaryId: string }) => {
  const [request, setRequest] = useState<ApiRequest<void>>({
    status: 'idle',
  });
  const navigate = useNavigate();

  const execute = useCallback(async (): Promise<void> => {
    setRequest({ status: 'loading' });

    try {
      await deleteDiary({ diaryId });

      setRequest({ status: 'success', data: undefined });
      navigate('/');
    } catch (error: unknown) {
      if (error instanceof Error) {
        setRequest({
          status: 'error',
          error: error,
        });
      }
    }
  }, [diaryId, navigate]);

  return { request, execute };
};

export default useDiaryDelete;
