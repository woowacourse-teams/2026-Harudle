export const MOCK_SCENARIO_HEADER = 'x-msw-scenario';

export const MOCK_SCENARIOS = {
  oauthAuthorization: 'oauth-authorization',
  authRefreshFailure: 'auth-refresh-failure',
  monthlyDiariesNonJsonError: 'monthly-diaries-non-json-error',
  diaryGenerationFailure: 'diary-generation-failure',
  diaryDetailFailure: 'diary-detail-failure',
  diaryDeleteFailure: 'diary-delete-failure',
  diaryShareFailure: 'diary-share-failure',
  profileFailure: 'profile-failure',
  logoutFailure: 'logout-failure',
} as const;
