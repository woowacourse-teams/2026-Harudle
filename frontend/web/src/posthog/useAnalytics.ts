import { useCallback } from 'react';
import { usePostHog } from '@posthog/react';

import { identifyCurrentUserForPostHog, isPostHogEnabled } from './posthog';
import type { FeedSort } from '../domain/feeds/feedList';

let hasConfirmedSession = false;

const getAuthenticationStatus = (): 'logged_in' | 'guest' | 'unknown' => {
  try {
    if (localStorage.getItem('harudle.has-completed-oauth') === null) {
      hasConfirmedSession = false;
      return 'guest';
    }

    // 저장된 인증 표시는 세션 복구 성공을 확인하기 전까지 로그인 증거로 쓰지 않는다.
    return hasConfirmedSession ? 'logged_in' : 'unknown';
  } catch {
    return 'unknown';
  }
};

interface AnalyticsEventMap {
  api_requested:
    | {
        feature: 'feed';
        operation: 'feed_list';
        endpoint: '/feeds';
        method: 'GET';
        category_id: number | null;
        sort: FeedSort;
        request_reason: 'first_page' | 'load_more';
      }
    | {
        feature: 'feed';
        operation: 'feed_detail';
        endpoint: '/feeds/:feedId';
        method: 'GET';
        feed_id: string;
      }
    | {
        feature: 'feed';
        operation: 'feed_comments';
        endpoint: '/feeds/:feedId/comments';
        method: 'GET';
        feed_id: string;
        request_reason: 'first_page' | 'load_more';
      }
    | {
        feature: 'feed';
        operation: 'feed_publish';
        endpoint: '/feeds';
        method: 'POST';
        diary_id: string;
        category_id: number;
      }
    | {
        feature: 'feed';
        operation: 'feed_like';
        endpoint: '/feeds/:feedId/like';
        method: 'PUT' | 'DELETE';
        feed_id: string;
        action: 'like' | 'unlike';
      };
  feed_category_changed: {
    feature: 'feed';
    operation: 'feed_category_change';
    previous_category_id: number | null;
    category_id: number | null;
    category_name: string;
  };
  feed_link_copy_clicked: {
    feature: 'feed';
    operation: 'copy_link';
    feed_id: string;
  };
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
  diary_image_downloaded: undefined;
  diary_image_copied: {
    diary_id: string;
  };
  landing_direct_login_clicked: {
    location: 'hero' | 'final';
  };
  landing_trial_diary_create_clicked: undefined;
  landing_trial_login_clicked: {
    location: 'result' | 'already_used';
  };
  pwa_install_clicked: {
    method: 'native_prompt' | 'ios_guide';
  };
  pwa_first_launched: undefined;
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

  const confirmAuthenticatedSession = useCallback((): void => {
    hasConfirmedSession = true;
  }, []);

  const track = useCallback<TrackAnalyticsEvent>(
    (event, ...args) => {
      if (!isPostHogEnabled) {
        return;
      }

      posthog.capture(event, {
        ...args[0],
        auth_status: getAuthenticationStatus(),
      });
    },
    [posthog],
  );

  const identifyCurrentUser = useCallback(
    (accessToken: string): Promise<void> => {
      confirmAuthenticatedSession();
      return identifyCurrentUserForPostHog(posthog, accessToken);
    },
    [posthog, confirmAuthenticatedSession],
  );

  const resetUser = useCallback((): void => {
    hasConfirmedSession = false;
    if (!isPostHogEnabled) {
      return;
    }

    posthog.reset();
  }, [posthog]);

  const updateNickname = useCallback(
    (nickname: string): void => {
      if (!isPostHogEnabled || nickname.trim().length === 0) return;

      try {
        posthog.setPersonProperties({ name: nickname });
      } catch {
        // 분석 프로필 갱신 실패가 이미 성공한 닉네임 저장 결과를 바꾸지 않게 한다.
      }
    },
    [posthog],
  );

  return {
    track,
    identifyCurrentUser,
    resetUser,
    confirmAuthenticatedSession,
    updateNickname,
  };
};
