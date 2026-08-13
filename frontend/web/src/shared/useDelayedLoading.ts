import { useEffect, useState } from 'react';

const DEFAULT_DELAY_MS = 500;

export const useDelayedLoading = (
  isLoading: boolean,
  delayMs = DEFAULT_DELAY_MS,
): boolean => {
  const [loadingState, setLoadingState] = useState({
    isLoading,
    isDelayElapsed: false,
  });

  if (loadingState.isLoading !== isLoading) {
    setLoadingState({ isLoading, isDelayElapsed: false });
  }

  useEffect(() => {
    if (!isLoading) {
      return;
    }

    const timeoutId = window.setTimeout(() => {
      setLoadingState({ isLoading: true, isDelayElapsed: true });
    }, delayMs);

    return () => window.clearTimeout(timeoutId);
  }, [delayMs, isLoading]);

  return isLoading && loadingState.isDelayElapsed;
};
