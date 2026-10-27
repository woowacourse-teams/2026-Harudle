import { DIARY_GENERATION_ID_KEY } from '../../shared/constants';

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
