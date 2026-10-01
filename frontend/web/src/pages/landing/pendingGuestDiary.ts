import type { GuestDiaryRequest } from './guestTrialApi';
import { validateGuestDiary } from './guestDiaryValidation';
import { isCanonicalUuid } from './guestTrialUuid';

export const GUEST_DIARY_PENDING_STORAGE_KEY =
  'harudle.guest-trial.pending-diary';

export interface PendingGuestDiary {
  idempotencyKey: string;
  request: GuestDiaryRequest;
}

export type GuestTrialStorage = Pick<
  Storage,
  'getItem' | 'setItem' | 'removeItem'
>;

const isRecord = (value: unknown): value is Record<string, unknown> => {
  return typeof value === 'object' && value !== null;
};

export const isPendingGuestDiary = (
  value: unknown,
): value is PendingGuestDiary => {
  if (
    !isRecord(value) ||
    typeof value.idempotencyKey !== 'string' ||
    !isCanonicalUuid(value.idempotencyKey) ||
    !isRecord(value.request) ||
    typeof value.request.diaryDate !== 'string' ||
    typeof value.request.sourceText !== 'string'
  ) {
    return false;
  }

  const request: GuestDiaryRequest = {
    diaryDate: value.request.diaryDate,
    sourceText: value.request.sourceText,
  };

  return Object.keys(validateGuestDiary(request)).length === 0;
};

/** 저장값이 손상되었거나 유효하지 않으면 해당 값을 제거하고 null을 반환한다. */
export const loadPendingGuestDiary = (
  storage: GuestTrialStorage,
): PendingGuestDiary | null => {
  try {
    const storedValue = storage.getItem(GUEST_DIARY_PENDING_STORAGE_KEY);

    if (!storedValue) {
      return null;
    }

    const data: unknown = JSON.parse(storedValue);

    if (isPendingGuestDiary(data)) {
      return data;
    }
  } catch {
    // 손상된 pending 요청은 아래에서 제거하고 새 작성 상태로 복구한다.
  }

  try {
    clearPendingGuestDiary(storage);
  } catch {
    // Storage 접근 자체가 막힌 환경에서는 작성 시 저장 오류로 안내한다.
  }

  return null;
};

export const savePendingGuestDiary = (
  storage: GuestTrialStorage,
  pendingDiary: PendingGuestDiary,
): void => {
  storage.setItem(
    GUEST_DIARY_PENDING_STORAGE_KEY,
    JSON.stringify(pendingDiary),
  );
};

export const clearPendingGuestDiary = (storage: GuestTrialStorage): void => {
  storage.removeItem(GUEST_DIARY_PENDING_STORAGE_KEY);
};
