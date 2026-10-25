import {
  DIARY_GENERATION_ID_KEY,
  DIARY_GENERATION_TOAST_ID_KEY,
} from '../../shared/constants';

const listeners = new Set<() => void>();

const notifyListeners = (): void => {
  listeners.forEach((listener): void => {
    listener();
  });
};

export const getGenerationId = (): string | null => {
  return localStorage.getItem(DIARY_GENERATION_ID_KEY);
};

export const setGenerationId = (generationId: string): void => {
  localStorage.setItem(DIARY_GENERATION_ID_KEY, generationId);
  notifyListeners();
};

export const removeGenerationId = (): void => {
  localStorage.removeItem(DIARY_GENERATION_ID_KEY);
  notifyListeners();
};

export const subscribeGenerationId = (listener: () => void): (() => void) => {
  listeners.add(listener);

  return (): void => {
    listeners.delete(listener);
  };
};

export const clearGenerationForEndedSession = (): void => {
  try {
    localStorage.removeItem(DIARY_GENERATION_ID_KEY);
    localStorage.removeItem(DIARY_GENERATION_TOAST_ID_KEY);
    notifyListeners();
  } catch {
    // 저장소 또는 구독자의 오류가 확인된 세션 종료를 실패로 바꾸지 않게 한다.
  }
};
