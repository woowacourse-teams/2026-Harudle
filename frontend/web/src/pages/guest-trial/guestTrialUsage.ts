import type { GuestTrialStorage } from './pendingGuestDiary';

export const GUEST_TRIAL_USED_STORAGE_KEY = 'harudle.guest-trial.used';

const GUEST_TRIAL_USED_STORAGE_VALUE = 'true';

export const hasUsedGuestTrial = (storage: GuestTrialStorage): boolean => {
  try {
    return (
      storage.getItem(GUEST_TRIAL_USED_STORAGE_KEY) ===
      GUEST_TRIAL_USED_STORAGE_VALUE
    );
  } catch {
    return false;
  }
};

export const markGuestTrialUsed = (storage: GuestTrialStorage): void => {
  try {
    storage.setItem(
      GUEST_TRIAL_USED_STORAGE_KEY,
      GUEST_TRIAL_USED_STORAGE_VALUE,
    );
  } catch {
    // 서버 응답을 우선하며, Storage 접근 실패가 결과 화면을 막지 않게 한다.
  }
};
