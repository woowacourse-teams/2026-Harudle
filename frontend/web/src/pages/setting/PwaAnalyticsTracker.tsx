import { useEffect } from 'react';
import { useAnalytics } from '../../shared/useAnalytics';
import { isPwaInstalled } from './PwaInstallContext';

const PWA_FIRST_LAUNCH_TRACKED_KEY = 'pwa_first_launch_tracked';

const PwaAnalyticsTracker = () => {
  const { track } = useAnalytics();

  useEffect(() => {
    if (
      !isPwaInstalled() ||
      localStorage.getItem(PWA_FIRST_LAUNCH_TRACKED_KEY)
    ) {
      return;
    }

    track('pwa_first_launched');
    localStorage.setItem(PWA_FIRST_LAUNCH_TRACKED_KEY, 'true');
  }, [track]);

  return null;
};

export default PwaAnalyticsTracker;
