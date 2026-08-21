import { useCallback, useEffect, useState } from 'react';
import type { ApiRequest } from '../../shared/api';
import { getGuestDiary, type GuestDiaryResponse } from './guestTrialApi';

interface GuestDiaryResultOptions {
  diaryId: string | undefined;
  getDiary?: typeof getGuestDiary;
}

const resultRequests = new Map<string, Promise<GuestDiaryResponse>>();

const requestGuestDiaryResult = (
  diaryId: string,
  getDiary: typeof getGuestDiary,
): Promise<GuestDiaryResponse> => {
  const existingRequest = resultRequests.get(diaryId);

  if (existingRequest) {
    return existingRequest;
  }

  const request = Promise.resolve()
    .then(() => getDiary(diaryId))
    .finally(() => {
      resultRequests.delete(diaryId);
    });

  resultRequests.set(diaryId, request);
  return request;
};

const useGuestDiaryResult = ({
  diaryId,
  getDiary = getGuestDiary,
}: GuestDiaryResultOptions) => {
  const [resultRequest, setResultRequest] = useState<
    ApiRequest<GuestDiaryResponse>
  >({ status: 'idle' });
  const [requestAttempt, setRequestAttempt] = useState(0);

  useEffect(() => {
    let isActive = true;

    const loadGuestDiaryResult = async (): Promise<void> => {
      if (!diaryId) {
        setResultRequest({
          status: 'error',
          error: new Error('조회할 게스트 일기 ID가 없습니다'),
        });
        return;
      }

      setResultRequest({ status: 'loading' });

      try {
        const diary = await requestGuestDiaryResult(diaryId, getDiary);

        if (isActive) {
          setResultRequest({ status: 'success', data: diary });
        }
      } catch (error: unknown) {
        if (isActive && error instanceof Error) {
          setResultRequest({ status: 'error', error });
        }
      }
    };

    void loadGuestDiaryResult();

    return () => {
      isActive = false;
    };
  }, [diaryId, getDiary, requestAttempt]);

  const retryResult = useCallback(() => {
    setRequestAttempt((attempt) => attempt + 1);
  }, []);

  return { resultRequest, retryResult };
};

export default useGuestDiaryResult;
