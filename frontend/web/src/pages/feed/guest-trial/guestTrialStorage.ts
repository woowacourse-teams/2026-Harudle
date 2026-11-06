import { useSyncExternalStore } from 'react';
import { isCanonicalUuid } from '../../landing/guestTrialUuid';

// 서버 SUCCEEDED를 확인한 diaryId. 로그인 후 claim 대상 힌트로 쓴다.
const DIARY_ID_KEY = 'harudle.guest-trial.diary-id';
// 완료 전 작업 ID. 피드를 벗어나거나 새로고침해도 완료 조회를 이어가는 데만 쓴다.
const GENERATION_ID_KEY = 'harudle.guest-trial.generation-id';
const GUIDE_SEEN_KEY = 'harudle.guest-trial.guide-seen';

// 피드의 체험 화면과 모든 화면의 체험 알림이 같은 값을 보도록 변경을 알린다.
const listeners = new Set<() => void>();

const notifyListeners = (): void => {
  listeners.forEach((listener): void => listener());
};

const subscribe = (listener: () => void): (() => void) => {
  listeners.add(listener);
  return (): void => {
    listeners.delete(listener);
  };
};

export const readGuestTrialDiaryId = (): string | null => {
  try {
    const value: unknown = sessionStorage.getItem(DIARY_ID_KEY);
    return isCanonicalUuid(value) ? value : null;
  } catch {
    return null;
  }
};

export const saveGuestTrialDiaryId = (diaryId: string): void => {
  try {
    sessionStorage.setItem(DIARY_ID_KEY, diaryId);
  } catch {
    // 저장하지 못해도 현재 화면의 결과 확인은 계속한다.
  }
  notifyListeners();
};

export const removeGuestTrialDiaryId = (): void => {
  try {
    sessionStorage.removeItem(DIARY_ID_KEY);
  } catch {
    // 저장소를 쓸 수 없으면 다음 진입에서 서버 응답으로 다시 판단한다.
  }
  notifyListeners();
};

export const readGuestTrialGenerationId = (): string | null => {
  try {
    const value: unknown = sessionStorage.getItem(GENERATION_ID_KEY);
    return isCanonicalUuid(value) ? value : null;
  } catch {
    return null;
  }
};

export const saveGuestTrialGenerationId = (generationId: string): void => {
  try {
    sessionStorage.setItem(GENERATION_ID_KEY, generationId);
  } catch {
    // 저장하지 못하면 현재 화면에서만 완료를 확인한다.
  }
  notifyListeners();
};

export const removeGuestTrialGenerationId = (): void => {
  try {
    sessionStorage.removeItem(GENERATION_ID_KEY);
  } catch {
    // 남은 ID는 다음 조회에서 서버 응답으로 정리된다.
  }
  notifyListeners();
};

export const hasCompletedOAuth = (): boolean => {
  try {
    return localStorage.getItem('harudle.has-completed-oauth') !== null;
  } catch {
    return false;
  }
};

/** 신규 노출 대상이면 true, 아니거나 저장소를 확인할 수 없으면 false다. */
export const isNewGuestTrialTarget = (): boolean => {
  try {
    return (
      localStorage.getItem('harudle.has-ever-logged-in') === null &&
      localStorage.getItem('harudle.has-completed-oauth') === null &&
      localStorage.getItem(GUIDE_SEEN_KEY) === null
    );
  } catch {
    return false;
  }
};

/** 가이드를 실제로 보여줬을 때만 기록한다. 기록하지 못하면 가이드를 보류한다. */
export const markGuestTrialGuideSeen = (): boolean => {
  try {
    localStorage.setItem(GUIDE_SEEN_KEY, new Date().toISOString());
    return true;
  } catch {
    return false;
  }
};

// 진행 중인 작업을 지금 다시 확인해 달라는 요청. 값이 바뀌면 체험 알림이 조회를 새로 시작한다.
let statusCheckRequest = 0;

export const requestGuestTrialStatusCheck = (): void => {
  statusCheckRequest += 1;
  notifyListeners();
};

// 최종 실패·작업 없음으로 결과 없이 끝난 방문. 환급 계약이 없어 +를 기존 동작으로 돌린다.
let hasEndedWithoutResult = false;

export const markGuestTrialEndedWithoutResult = (): void => {
  hasEndedWithoutResult = true;
  notifyListeners();
};

export const useGuestTrialEndedWithoutResult = (): boolean => {
  return useSyncExternalStore(subscribe, (): boolean => hasEndedWithoutResult);
};

// 결과 시트 열기 요청. 다른 화면의 완료 토스트에서 요청하고 피드가 열리면 처리한다.
let resultOpenRequested = 0;
let resultOpenHandled = 0;

export const requestGuestTrialResultOpen = (): void => {
  resultOpenRequested += 1;
  notifyListeners();
};

export const markGuestTrialResultOpenHandled = (): void => {
  resultOpenHandled = resultOpenRequested;
  notifyListeners();
};

export const readGuestTrialResultOpenHandled = (): number => resultOpenHandled;

export const useGuestTrialDiaryId = (): string | null => {
  return useSyncExternalStore(subscribe, readGuestTrialDiaryId);
};

export const useGuestTrialGenerationId = (): string | null => {
  return useSyncExternalStore(subscribe, readGuestTrialGenerationId);
};

export const useGuestTrialStatusCheckRequest = (): number => {
  return useSyncExternalStore(subscribe, (): number => statusCheckRequest);
};

export const useGuestTrialResultOpenRequest = (): {
  readonly requested: number;
  readonly handled: number;
} => {
  const requested = useSyncExternalStore(
    subscribe,
    (): number => resultOpenRequested,
  );
  const handled = useSyncExternalStore(
    subscribe,
    (): number => resultOpenHandled,
  );
  return { requested, handled };
};
