export const MOCK_SCENARIO_HEADER = 'x-msw-scenario';

export const MOCK_SCENARIOS = {
  oauthAuthorization: 'oauth-authorization',
  authRefreshFailure: 'auth-refresh-failure',
  authRefreshSuccess: 'auth-refresh-success',
  monthlyDiariesNonJsonError: 'monthly-diaries-non-json-error',
  diaryGenerationFailure: 'diary-generation-failure',
  diaryDetailFailure: 'diary-detail-failure',
  diaryLongKoreanTitle: 'diary-long-korean-title',
  diaryLongEmojiTitle: 'diary-long-emoji-title',
  diaryDeleteFailure: 'diary-delete-failure',
  diaryShareFailure: 'diary-share-failure',
  profileFailure: 'profile-failure',
  logoutFailure: 'logout-failure',
} as const;
