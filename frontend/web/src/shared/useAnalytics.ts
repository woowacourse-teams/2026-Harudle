import { useCallback } from 'react';
import { usePostHog } from '@posthog/react';

import { identifyCurrentUserForPostHog, isPostHogEnabled } from './posthog';

interface AnalyticsEventMap {
  diary_share_clicked: {
    diary_id: string;
  };
  diary_share_viewed: {
    share_id: string;
  };
  diary_share_landing_clicked: {
    share_id: string;
  };
  diary_image_downloaded: undefined;
}

export type AnalyticsEventName = keyof AnalyticsEventMap;

type TrackAnalyticsEvent = <Event extends AnalyticsEventName>(
  event: Event,
  ...args: AnalyticsEventMap[Event] extends undefined
    ? []
    : [properties: AnalyticsEventMap[Event]]
) => void;

export const useAnalytics = () => {
  const posthog = usePostHog();

  const track = useCallback<TrackAnalyticsEvent>(
    (event, ...args) => {
      if (!isPostHogEnabled) {
        return;
      }

      posthog.capture(event, args[0]);
    },
    [posthog],
  );

  const identifyCurrentUser = useCallback(
    (accessToken: string): Promise<void> => {
      return identifyCurrentUserForPostHog(posthog, accessToken);
    },
    [posthog],
  );

  const resetUser = useCallback((): void => {
    if (!isPostHogEnabled) {
      return;
    }

    posthog.reset();
  }, [posthog]);

  return { track, identifyCurrentUser, resetUser };
};
