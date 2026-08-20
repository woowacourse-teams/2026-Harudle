import { useCallback, useEffect, useRef, useState } from 'react';
import {
  createGuestDiary,
  createGuestDiaryIdempotencyKey,
  type GuestDiaryRequest,
  type GuestDiaryResponse,
} from './guestTrialApi';
import {
  clearPendingGuestDiary,
  loadPendingGuestDiary,
  savePendingGuestDiary,
  type GuestTrialStorage,
  type PendingGuestDiary,
} from './pendingGuestDiary';

export type GuestDiaryCreationState =
  | { status: 'writing' }
  | { status: 'generating' }
  | { status: 'success'; data: GuestDiaryResponse }
  | { status: 'error'; error: Error };

interface GuestDiaryCreationOptions {
  enabled: boolean;
  createDiary?: typeof createGuestDiary;
  createIdempotencyKey?: typeof createGuestDiaryIdempotencyKey;
  storage?: GuestTrialStorage;
}

const creationRequests = new Map<string, Promise<GuestDiaryResponse>>();

const requestGuestDiaryCreation = (
  pendingDiary: PendingGuestDiary,
  createDiary: typeof createGuestDiary,
): Promise<GuestDiaryResponse> => {
  const existingRequest = creationRequests.get(pendingDiary.idempotencyKey);

  if (existingRequest) {
    return existingRequest;
  }

  const request = Promise.resolve()
    .then(() =>
      createDiary({
        request: pendingDiary.request,
        idempotencyKey: pendingDiary.idempotencyKey,
      }),
    )
    .finally(() => {
      creationRequests.delete(pendingDiary.idempotencyKey);
    });

  creationRequests.set(pendingDiary.idempotencyKey, request);
  return request;
};

const useGuestDiaryCreation = ({
  enabled,
  createDiary = createGuestDiary,
  createIdempotencyKey = createGuestDiaryIdempotencyKey,
  storage = sessionStorage,
}: GuestDiaryCreationOptions) => {
  const pendingDiaryRef = useRef<PendingGuestDiary | null>(
    loadPendingGuestDiary(storage),
  );
  const restoredRequestStartedRef = useRef(false);
  const isMountedRef = useRef(true);
  const [creationState, setCreationState] = useState<GuestDiaryCreationState>({
    status: 'writing',
  });

  useEffect(() => {
    isMountedRef.current = true;

    return () => {
      isMountedRef.current = false;
    };
  }, []);

  const executePendingDiary = useCallback(
    async (pendingDiary: PendingGuestDiary): Promise<void> => {
      if (isMountedRef.current) {
        setCreationState({ status: 'generating' });
      }

      try {
        const diary = await requestGuestDiaryCreation(
          pendingDiary,
          createDiary,
        );

        clearPendingGuestDiary(storage);
        pendingDiaryRef.current = null;

        if (isMountedRef.current) {
          setCreationState({ status: 'success', data: diary });
        }
      } catch (error: unknown) {
        if (isMountedRef.current && error instanceof Error) {
          setCreationState({ status: 'error', error });
        }
      }
    },
    [createDiary, storage],
  );

  useEffect(() => {
    const pendingDiary = pendingDiaryRef.current;

    if (!enabled || !pendingDiary || restoredRequestStartedRef.current) {
      return;
    }

    restoredRequestStartedRef.current = true;
    void executePendingDiary(pendingDiary);
  }, [enabled, executePendingDiary]);

  const submitDiary = useCallback(
    async (request: GuestDiaryRequest): Promise<void> => {
      let pendingDiary = pendingDiaryRef.current;

      if (!pendingDiary) {
        pendingDiary = {
          idempotencyKey: createIdempotencyKey(),
          request,
        };

        try {
          savePendingGuestDiary(storage, pendingDiary);
          pendingDiaryRef.current = pendingDiary;
        } catch {
          setCreationState({
            status: 'error',
            error: new Error('생성 요청을 안전하게 저장하지 못했습니다.'),
          });
          return;
        }
      }

      await executePendingDiary(pendingDiary);
    },
    [createIdempotencyKey, executePendingDiary, storage],
  );

  const retryDiary = useCallback(async (): Promise<void> => {
    const pendingDiary = pendingDiaryRef.current;

    if (pendingDiary) {
      await executePendingDiary(pendingDiary);
    }
  }, [executePendingDiary]);

  return { creationState, submitDiary, retryDiary };
};

export default useGuestDiaryCreation;
