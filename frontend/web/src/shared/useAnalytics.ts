import { useCallback } from 'react';
import { usePostHog } from '@posthog/react';

import { identifyCurrentUserForPostHog, isPostHogEnabled } from './posthog';

interface AnalyticsEventMap {
  diary_timeline_viewed: {
    year: number;
    month: number;
    diary_count: number;
    has_diaries: boolean;
  };
  diary_detail_viewed: {
    diary_id: string;
    diary_date: string;
  };
  diary_created: {
    diary_id: string;
    diary_date: string;
    remaining_generation_count: number;
  };
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
  landing_direct_login_clicked: {
    location: 'hero' | 'final';
  };
  landing_trial_diary_create_clicked: undefined;
  landing_trial_login_clicked: {
    location: 'result' | 'already_used';
  };
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
