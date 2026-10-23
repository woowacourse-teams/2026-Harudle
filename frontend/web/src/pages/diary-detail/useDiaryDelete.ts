import { useErrorTracking } from '../../posthog/useErrorTracking';
import { useCallback, useState } from 'react';
import { deleteDiary } from '../../domain/diary/diaryDelete';
import type { ApiRequest } from '../../shared/api';

const useDiaryDelete = ({ diaryId }: { diaryId: string }) => {
  const { captureError } = useErrorTracking();
  const [request, setRequest] = useState<ApiRequest<void>>({
    status: 'idle',
  });

  const execute = useCallback(
    async ({ onSuccess }: { onSuccess: () => void }): Promise<void> => {
      setRequest({ status: 'loading' });

      try {
        await deleteDiary({ diaryId });
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
        return;
      }

      setRequest({ status: 'success', data: undefined });
      onSuccess();
    },
    [diaryId, captureError],
  );

  const reset = (): void => setRequest({ status: 'idle' });

  return { request, execute, reset };
};

export default useDiaryDelete;
