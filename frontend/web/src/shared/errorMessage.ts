import type { GenerationFailureCode } from '../domain/diary/diaryGeneration';

export const DIARY_GENERATION_FAILURE_MESSAGES = {
  AI_PROVIDER_ERROR: [
    '네컷만화 생성에 실패했어요.',
    '잠시 후 다시 만들어 주세요.',
  ],
  AI_PROVIDER_RATE_LIMITED: [
    '네컷만화 생성 요청이 많아요.',
    '조금 기다린 뒤 다시 만들어 주세요.',
  ],
  AI_PROVIDER_TIMEOUT: [
    '네컷만화 생성 시간이 초과됐어요.',
    '잠시 후 다시 만들어 주세요.',
  ],
  AI_OUTPUT_TRUNCATED: [
    '네컷만화가 완성되지 않았어요.',
    '네컷만화를 다시 만들어 주세요.',
  ],
  AI_RESPONSE_PROCESSING_ERROR: [
    '네컷만화 생성에 실패했어요.',
    '네컷만화를 다시 만들어 주세요.',
  ],
  GENERATION_INTERNAL_ERROR: [
    '네컷만화 생성에 실패했어요.',
    '잠시 후 다시 만들어 주세요.',
  ],
  GENERATION_INTERRUPTED: [
    '네컷만화 생성이 중단됐어요.',
    '네컷만화를 다시 만들어 주세요.',
  ],
  IMAGE_STORAGE_ERROR: [
    '네컷만화 저장에 실패했어요.',
    '잠시 후 다시 만들어 주세요.',
  ],
} as const satisfies Record<GenerationFailureCode, readonly [string, string]>;

const DIARY_GENERATE = {
  DIARY_GENERATION_FAILED:
    '네컷만화를 만드는 중 문제가 생겼어요. 잠시 후 다시 시도해 주세요.',
  INVALID_DIARY_GENERATION_RESPONSE:
    'DiaryGenerate 응답 형식이 일치하지 않습니다.',
  DIARY_GENERATION_STATUS_FETCH_FAILED:
    '네컷만화 생성 상태를 불러오지 못했어요. 다시 시도해 주세요.',
  INVALID_DIARY_GENERATION_STATUS_RESPONSE:
    'DiaryGenerationStatus 응답 형식이 일치하지 않습니다.',
  DIARY_GENERATE_PROVIDER_REQUIRED:
    'useDiaryGenerateContext는 DiaryGenerateProvider 내부에서만 사용할 수 있습니다.',
} as const;

const DIARY_DELETE = {
  DIARY_DELETION_FAILED: '네컷만화를 삭제하지 못했어요. 다시 시도해 주세요.',
} as const;

const DIARY_DETAIL = {
  DIARY_DETAIL_FETCH_FAILED:
    '네컷만화를 불러오지 못했어요. 다시 시도해 주세요.',
  INVALID_DIARY_DETAIL_RESPONSE: 'DiaryDetail 응답 형식이 일치하지 않습니다.',
} as const;

const MONTHLY_DIARIES = {
  MONTHLY_DIARIES_FETCH_FAILED:
    '이 달의 네컷만화를 불러오지 못했어요. 다시 시도해 주세요.',
  INVALID_MONTHLY_DIARIES_RESPONSE:
    'MonthlyDiaries 응답 형식이 일치하지 않습니다.',
} as const;

const FEED_COMMENTS = {
  FEED_COMMENT_DELETION_FAILED: '댓글을 삭제하지 못했어요. 다시 시도해 주세요.',
  FEED_COMMENT_CREATION_FAILED:
    '댓글을 남기지 못했어요. 작성 여부를 확인한 뒤 다시 시도해 주세요.',
  INVALID_FEED_COMMENT_RESPONSE: 'FeedComment 응답 형식이 일치하지 않습니다.',
  INVALID_COMMENT_CONTENT: '댓글은 1자 이상 100자 이하로 입력해 주세요.',
  FEED_COMMENTS_FETCH_FAILED: '댓글을 불러오지 못했어요. 다시 시도해 주세요.',
  INVALID_FEED_COMMENTS_RESPONSE: 'FeedComments 응답 형식이 일치하지 않습니다.',
} as const;

const FEED_DETAIL = {
  FEED_DETAIL_FETCH_FAILED: '피드를 불러오지 못했어요. 다시 시도해 주세요.',
  INVALID_FEED_DETAIL_RESPONSE: 'FeedDetail 응답 형식이 일치하지 않습니다.',
} as const;

const FEED_LIST = {
  FEED_LIST_FETCH_FAILED: '피드를 불러오지 못했어요. 다시 시도해 주세요.',
  INVALID_FEED_LIST_RESPONSE: 'FeedList 응답 형식이 일치하지 않습니다.',
} as const;

const FEED_ACTIONS = {
  FEED_PUBLISH_FAILED: '피드에 올리지 못했어요. 다시 시도해 주세요.',
  INVALID_FEED_PUBLISH_RESPONSE: 'FeedPublish 응답 형식이 일치하지 않습니다.',
  FEED_LINK_COPY_FAILED: '링크를 복사하지 못했어요. 다시 시도해 주세요.',
  FEED_LIKE_FAILED: '좋아요를 반영하지 못했어요. 다시 시도해 주세요.',
  INVALID_FEED_LIKE_RESPONSE: 'FeedLike 응답 형식이 일치하지 않습니다.',
  FEED_DELETION_FAILED: '피드를 삭제하지 못했어요. 다시 시도해 주세요.',
} as const;

const CATEGORY_LIST = {
  CATEGORY_LIST_FETCH_FAILED:
    '카테고리를 불러오지 못했어요. 다시 시도해 주세요.',
  INVALID_CATEGORY_LIST_RESPONSE: 'CategoryList 응답 형식이 일치하지 않습니다.',
} as const;

const DIARY_IMAGE_DOWNLOAD = {
  DIARY_IMAGE_SAVE_FAILED: '이미지를 저장하지 못했어요. 다시 시도해 주세요.',
} as const;

const DIARY_IMAGE_COPY = {
  DIARY_IMAGE_COPY_FAILED: '이미지를 복사하지 못했어요. 다시 시도해 주세요.',
  DIARY_IMAGE_COPY_UNSUPPORTED:
    '이 브라우저에서는 이미지 복사를 지원하지 않아요. 이미지 저장을 이용해 주세요.',
} as const;

const GENERATION_USAGE = {
  GENERATION_USAGE_FETCH_FAILED:
    '남은 만들기 횟수를 불러오지 못했어요. 다시 확인해 주세요.',
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
  NICKNAME_UPDATE_FAILED: '닉네임을 수정하지 못했어요. 다시 시도해 주세요.',
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

const TOAST = {
  TOAST_PROVIDER_REQUIRED:
    'useToastContext는 ToastProvider 내부에서만 사용할 수 있습니다.',
} as const;

export const ERROR_MESSAGES = {
  ...DIARY_GENERATE,
  ...DIARY_DELETE,
  ...DIARY_DETAIL,
  ...MONTHLY_DIARIES,
  ...FEED_LIST,
  ...FEED_DETAIL,
  ...FEED_COMMENTS,
  ...FEED_ACTIONS,
  ...CATEGORY_LIST,
  ...DIARY_IMAGE_DOWNLOAD,
  ...DIARY_IMAGE_COPY,
  ...GENERATION_USAGE,
  ...AUTH,
  ...USER,
  ...GUEST,
  ...ADMIN,
  ...VALIDATION,
  ...PWA,
  ...TOAST,
} as const;
