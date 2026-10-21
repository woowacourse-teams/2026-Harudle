import { useToastContext } from '../../shared/ToastContext';
import { DIARY_DETAIL_COPY } from './copy';
import { useErrorTracking } from '../../posthog/useErrorTracking';
import { useCallback, useState } from 'react';
import { useNavigate } from 'react-router';
import { deleteDiary } from '../../domain/diary/diaryDelete';
import type { ApiRequest } from '../../shared/api';

const useDiaryDelete = ({ diaryId }: { diaryId: string }) => {
  const { addToast } = useToastContext();
  const { captureError } = useErrorTracking();
  const [request, setRequest] = useState<ApiRequest<void>>({
    status: 'idle',
  });
  const navigate = useNavigate();

  const execute = useCallback(async (): Promise<void> => {
    setRequest({ status: 'loading' });

    try {
      await deleteDiary({ diaryId });

      setRequest({ status: 'success', data: undefined });
      addToast(
        { type: 'success', message: DIARY_DETAIL_COPY.deleteSuccess },
        2000,
      );
      navigate('/album');
    } catch (error: unknown) {
      if (error instanceof Error) {
        captureError(error, {
          feature: 'diary',
          operation: 'delete',
          diary_id: diaryId,
        });
        setRequest({
          status: 'error',
          error: error,
        });
      }
    }
  }, [diaryId, navigate, captureError, addToast]);

  return { request, execute };
};

export default useDiaryDelete;
