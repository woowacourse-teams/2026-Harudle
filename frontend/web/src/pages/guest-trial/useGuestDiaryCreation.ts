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
import {
  createGuestTrialAlreadyUsedError,
  isGuestTrialAlreadyUsedError,
} from './guestTrialErrors';
import { hasUsedGuestTrial, markGuestTrialUsed } from './guestTrialUsage';

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

interface InitialGuestDiaryCreation {
  pendingDiary: PendingGuestDiary | null;
  creationState: GuestDiaryCreationState;
}

const getInitialGuestDiaryCreation = (
  storage: GuestTrialStorage,
): InitialGuestDiaryCreation => {
  if (hasUsedGuestTrial(storage)) {
    try {
      clearPendingGuestDiary(storage);
    } catch {
      // 사용 완료 상태를 우선해 작성창이 다시 노출되지 않게 한다.
    }

    return {
      pendingDiary: null,
      creationState: {
        status: 'error',
        error: createGuestTrialAlreadyUsedError(),
      },
    };
  }

  const pendingDiary = loadPendingGuestDiary(storage);

  return {
    pendingDiary,
    creationState: {
      status: pendingDiary ? 'generating' : 'writing',
    },
  };
};

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
  const [initialCreation] = useState(() =>
    getInitialGuestDiaryCreation(storage),
  );
  const pendingDiaryRef = useRef<PendingGuestDiary | null>(
    initialCreation.pendingDiary,
  );
  const restoredRequestStartedRef = useRef(false);
  const isMountedRef = useRef(true);
  const [creationState, setCreationState] = useState<GuestDiaryCreationState>(
    initialCreation.creationState,
  );

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

        markGuestTrialUsed(storage);

        try {
          clearPendingGuestDiary(storage);
        } catch {
          // Storage 정리 실패가 이미 완료된 생성 결과를 가리지 않게 한다.
        }

        pendingDiaryRef.current = null;

        if (isMountedRef.current) {
          setCreationState({ status: 'success', data: diary });
        }
      } catch (error: unknown) {
        if (isGuestTrialAlreadyUsedError(error)) {
          markGuestTrialUsed(storage);

          try {
            clearPendingGuestDiary(storage);
          } catch {
            // 이미 소진된 요청은 재시도 대상으로 남기지 않는다.
          }

          pendingDiaryRef.current = null;
        }

        if (isMountedRef.current) {
          setCreationState({
            status: 'error',
            error:
              error instanceof Error
                ? error
                : new Error('게스트 일기 생성에 실패했습니다'),
          });
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
            error: new Error('생성 요청을 안전하게 저장하지 못했습니다'),
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
