const DIARY_GENERATE = {
  DIARY_GENERATION_FAILED:
    '일기 생성 도중 문제가 발생했습니다. 다시 시도해주세요.',
  INVALID_DIARY_GENERATION_RESPONSE:
    'DiaryGenerate 응답 형식이 일치하지 않습니다.',
  DIARY_GENERATE_PROVIDER_REQUIRED:
    'useDiaryGenerateContext는 DiaryGenerateProvider 내부에서만 사용할 수 있습니다.',
} as const;

const DIARY_DELETE = {
  DIARY_DELETION_FAILED:
    '일기 삭제 도중 문제가 발생했습니다. 다시 시도해주세요.',
} as const;

const DIARY_SHARE_LINK = {
  DIARY_SHARE_LINK_CREATION_FAILED:
    '일기 공유 링크 생성 도중 문제가 발생했습니다. 다시 시도해주세요.',
  INVALID_DIARY_SHARE_RESPONSE: 'DiaryShare 응답 형식이 일치하지 않습니다.',
} as const;

const DIARY_DETAIL = {
  DIARY_DETAIL_FETCH_FAILED:
    '일기 상세 정보를 불러오는 중 문제가 발생했습니다. 다시 시도해주세요.',
  INVALID_DIARY_DETAIL_RESPONSE: 'DiaryDetail 응답 형식이 일치하지 않습니다.',
} as const;

const SHARED_DIARY = {
  SHARED_DIARY_FETCH_FAILED:
    '공유된 일기를 불러오는 중 문제가 발생했습니다. 다시 시도해주세요.',
  INVALID_SHARED_DIARY_RESPONSE: 'SharedDiary 응답 형식이 일치하지 않습니다.',
} as const;

const MONTHLY_DIARIES = {
  MONTHLY_DIARIES_FETCH_FAILED:
    '월별 일기를 불러오는 중 문제가 발생했습니다. 다시 시도해주세요.',
  INVALID_MONTHLY_DIARIES_RESPONSE:
    'MonthlyDiaries 응답 형식이 일치하지 않습니다.',
} as const;

const CURRENT_STREAK = {
  CURRENT_STREAK_FETCH_FAILED:
    '연속 일기 기록을 조회하는 중 문제가 발생했습니다. 다시 시도해주세요.',
  INVALID_CURRENT_STREAK_RESPONSE:
    'CurrentStreak 응답 형식이 일치하지 않습니다.',
} as const;

const DIARY_IMAGE_DOWNLOAD = {
  DIARY_IMAGE_SAVE_FAILED: '이미지 저장에 실패했습니다.',
} as const;

const GENERATION_USAGE = {
  GENERATION_USAGE_FETCH_FAILED:
    '남은 생성 횟수를 조회하는 중 에러가 발생했습니다. 다시 시도해주세요.',
  INVALID_GENERATION_USAGE_RESPONSE:
    'GenerationUsage 응답 형식이 일치하지 않습니다.',
} as const;

const AUTH = {
  ACCESS_TOKEN_REFRESH_FAILED:
    '로그인 정보를 갱신하는 중 문제가 발생했습니다. 다시 시도해주세요.',
  OAUTH_LOGIN_HISTORY_REQUIRED: 'OAuth 로그인 이력이 없습니다.',
  ACCESS_TOKEN_RECOVERY_FAILED: 'Access Token 복구 실패',
  INVALID_REFRESH_TOKEN_RESPONSE: 'RefreshToken 응답 형식이 일치하지 않습니다.',
  CSRF_TOKEN_ISSUANCE_FAILED: 'CSRF Token 발급에 실패했습니다.',
  INVALID_CSRF_TOKEN_RESPONSE: 'CSRF Token 응답 형식이 일치하지 않습니다.',
  LOGIN_FAILED: '로그인에 실패했습니다. 다시 로그인해주세요.',
} as const;

const USER = {
  LOGOUT_FAILED: '로그아웃하는 중 문제가 발생했습니다. 다시 시도해주세요.',
  PROFILE_FETCH_FAILED:
    '프로필을 조회하는 중 문제가 발생했습니다. 다시 시도해주세요.',
  INVALID_PROFILE_RESPONSE: 'Profile 응답 형식이 일치하지 않습니다.',
} as const;

const GUEST = {
  GUEST_ENTRY_PREPARATION_FAILED: '게스트 체험을 준비하지 못했습니다',
  GUEST_DIARY_REQUEST_SAVE_FAILED: '생성 요청을 안전하게 저장하지 못했습니다',
  GUEST_DIARY_ID_REQUIRED: '조회할 게스트 일기 ID가 없습니다',
  GUEST_DIARY_RESULT_FETCH_FAILED: '게스트 일기 결과를 불러오지 못했습니다',
  GUEST_CSRF_TOKEN_ISSUANCE_FAILED: 'CSRF Token 발급에 실패했습니다',
  INVALID_GUEST_CSRF_TOKEN_RESPONSE: 'CSRF Token 응답 형식이 일치하지 않습니다',
  GUEST_SESSION_ISSUANCE_FAILED: '게스트 세션 발급에 실패했습니다',
  GUEST_DIARY_GENERATION_FAILED: '게스트 일기 생성에 실패했습니다',
  GUEST_DIARY_FETCH_FAILED: '게스트 일기 조회에 실패했습니다',
  INVALID_GUEST_DIARY_RESPONSE: '게스트 일기 응답 형식이 일치하지 않습니다',
  GUEST_TRIAL_ALREADY_USED: '게스트 체험을 이미 사용했습니다',
  GUEST_AUTHENTICATION_CHECK_FAILED: '로그인 상태 확인에 실패했습니다',
  INVALID_GUEST_REFRESH_TOKEN_RESPONSE:
    'RefreshToken 응답 형식이 일치하지 않습니다',
} as const;

const ADMIN = {
  INVALID_ADMIN_API_RESPONSE: '관리자 API 응답 형식이 올바르지 않습니다.',
  ADMIN_API_REQUEST_FAILED: '관리자 API 요청에 실패했습니다.',
  INVALID_ADMIN_USER_DETAIL_RESPONSE:
    '관리자 사용자 상세 응답 형식이 올바르지 않습니다.',
  INVALID_ADMIN_USAGE_UPDATE_RESPONSE:
    '사용량 변경 응답 형식이 올바르지 않습니다.',
} as const;

const VALIDATION = {
  INVALID_MONTH: '올바른 month가 아닙니다!',
  MONTH_CONVERSION_FAILED: 'month 변환에 실패했습니다. month 범위를 확인하세요',
} as const;

const PWA = {
  PWA_INSTALL_PROVIDER_REQUIRED:
    'usePwaInstall은 PwaInstallProvider 내부에서만 사용할 수 있습니다.',
} as const;

export const ERROR_MESSAGES = {
  ...DIARY_GENERATE,
  ...DIARY_DELETE,
  ...DIARY_SHARE_LINK,
  ...DIARY_DETAIL,
  ...SHARED_DIARY,
  ...MONTHLY_DIARIES,
  ...CURRENT_STREAK,
  ...DIARY_IMAGE_DOWNLOAD,
  ...GENERATION_USAGE,
  ...AUTH,
  ...USER,
  ...GUEST,
  ...ADMIN,
  ...VALIDATION,
  ...PWA,
} as const;
