export const MOCK_SCENARIO_HEADER = 'x-msw-scenario';

export const MOCK_SCENARIOS = {
  oauthAuthorization: 'oauth-authorization',
  authRefreshFailure: 'auth-refresh-failure',
  authRefreshSuccess: 'auth-refresh-success',
  monthlyDiariesNonJsonError: 'monthly-diaries-non-json-error',
  diaryGenerationFailure: 'diary-generation-failure',
  diaryGenerationRequestFailure: 'diary-generation-request-failure',
  diaryGenerationNotFound: 'diary-generation-not-found',
  diaryGenerationStatusFailureOnce: 'diary-generation-status-failure-once',
  diaryGenerationOutOfOrder: 'diary-generation-out-of-order',
  diaryGenerationResume: 'diary-generation-resume',
  diaryDetailFailure: 'diary-detail-failure',
  diaryLongKoreanTitle: 'diary-long-korean-title',
  diaryLongEmojiTitle: 'diary-long-emoji-title',
  diaryDeleteFailure: 'diary-delete-failure',
  feedListFailure: 'feed-list-failure',
  feedListEmpty: 'feed-list-empty',
  feedPublishFailure: 'feed-publish-failure',
  profileFailure: 'profile-failure',
  logoutFailure: 'logout-failure',
} as const;
