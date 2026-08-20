import type { PostHog } from 'posthog-js';

import { API_BASE_URL } from './api';

const localHostnames = new Set(['localhost', '127.0.0.1', '0.0.0.0', '[::1]']);

export const isPostHogEnabled =
  process.env.NODE_ENV === 'production' &&
  !localHostnames.has(window.location.hostname);

const hasUserId = (value: unknown): value is { id: string } => {
  return (
    typeof value === 'object' &&
    value !== null &&
    'id' in value &&
    typeof value.id === 'string'
  );
};

export const identifyCurrentUserForPostHog = async (
  posthog: PostHog,
  accessToken: string,
): Promise<void> => {
  if (!isPostHogEnabled) {
    return;
  }

  try {
    const response = await fetch(`${API_BASE_URL}/me`, {
      headers: {
        Authorization: `Bearer ${accessToken}`,
      },
    });

    if (!response.ok) {
      return;
    }

    const profile: unknown = await response.json();

    if (hasUserId(profile)) {
      posthog.identify(profile.id);
    }
  } catch (error: unknown) {
    if (process.env.NODE_ENV === 'development') {
      console.warn('PostHog 사용자 식별에 실패했습니다.', error);
    }
  }
};
