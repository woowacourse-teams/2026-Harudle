import type { AdminStats, AdminUserSort, SortDirection } from './adminApi';

export const ADMIN_MODERATION_COPY = {
  reports: '신고 관리',
  listError: '신고 목록을 불러오지 못했어요.',
  detailError: '신고 상세를 불러오지 못했어요.',
  moderationError: '조치 상태와 이력을 불러오지 못했어요.',
  retry: '다시 불러오기',
  empty: '이 상태의 신고가 없어요.',
  select: '신고를 선택하면 상세 내용을 확인할 수 있어요.',
  deleted: '대상이 삭제되었거나 조치할 수 없는 상태예요.',
  reason: '처리 사유',
  reasonHint: '1~500자',
  pending: '처리 중…',
  confirm: '확인하기',
  cancel: '취소',
  close: '닫기',
  failed: '처리하지 못했어요. 사유를 확인하고 다시 시도해 주세요.',
  conflict:
    '다른 관리자가 변경했어요. 닫은 뒤 새로고침하고 다시 확인해 주세요.',
  refreshError: '최신 정보를 불러오지 못했어요.',
  success: '처리가 완료되었어요.',
  reviewHint: '계정 정지·콘텐츠 숨김은 별도로 처리해요.',
  suspendHint: '로그인과 회원 기능 이용을 제한해요.',
  hideHint: '공개 화면에서 숨기고 원본은 유지해요.',
  restoreHint: '선택한 관리자 제한만 해제해요.',
  diaryReportHint:
    '작성자 본인이 AI 생성 결과를 신고한 개인 네컷이에요. 숨김·계정 조치 없이 신고 처리만 해요.',
} as const;

export const REPORT_STATUS_LABELS = {
  RECEIVED: '접수',
  DISMISSED: '반려',
  RESOLVED: '처리 완료',
} as const;
export const REPORT_TARGET_LABELS = {
  USER: '사용자',
  FEED: '피드',
  COMMENT: '댓글',
  DIARY: '개인 네컷',
} as const;
export const REPORT_REASON_LABELS = {
  SPAM: '스팸·광고',
  HARASSMENT: '괴롭힘·욕설',
  INAPPROPRIATE_CONTENT: '부적절한 콘텐츠',
  OTHER: '기타',
} as const;

export const ADMIN_LIST_COPY = {
  previousPage: '이전 페이지',
  nextPage: '다음 페이지',
  pageNumber: (page: number): string => `${page + 1}쪽`,
  userTotal: (count: number): string => `총 ${count.toLocaleString('ko-KR')}명`,
  historyTotal: (count: number): string =>
    `총 ${count.toLocaleString('ko-KR')}건`,
  joinedAt: '가입일',
  nickname: '닉네임',
  noNickname: '-',
  generatedDiaryCount: '생성한 일기 수',
  searchPlaceholder: '이름, 닉네임 또는 사용자 ID 검색',
  sortKey: '정렬 기준',
  sortDirection: '정렬 방향',
  historyDate: '조회 날짜',
  previousDate: '이전 날짜',
  nextDate: '다음 날짜',
  today: '오늘',
  userDetail: '사용자 상세',
} as const;

export const ADMIN_USER_SORT_LABELS = {
  NAME: '사전순',
  CREATED_AT: '가입일',
  REMAINING_COUNT: '오늘 남은 횟수',
} as const satisfies Record<AdminUserSort, string>;

export const SORT_DIRECTION_LABELS = {
  ASC: '오름차순',
  DESC: '내림차순',
} as const satisfies Record<SortDirection, string>;

export const ADMIN_STAT_COPY = {
  memberCount: {
    title: '전체 회원 수',
    scope: '탈퇴 회원 제외',
    error: '회원 수를 불러오지 못했어요.',
  },
  todaySignupCount: {
    title: '오늘 가입자 수',
    scope: '한국 시간 0시 이후',
    error: '가입자 수를 불러오지 못했어요.',
  },
} as const satisfies Record<
  keyof AdminStats,
  { title: string; scope: string; error: string }
>;

export const memberCountValue = (count: number): string =>
  `${count.toLocaleString('ko-KR')}명`;

export const ADMIN_POSTCARD_COPY = {
  open: '엽서 인쇄',
  title: '엽서 인쇄',
  loading: '엽서를 만드는 중이에요…',
  imageError: '만화 이미지를 불러오지 못했어요. 생성이 끝났는지 확인해 주세요.',
  composeError:
    '이 만화는 네 칸을 나누지 못해 엽서로 만들 수 없어요. 다른 생성 결과를 골라 주세요.',
  print: '인쇄하기',
  download: 'PNG 저장',
  close: '닫기',
  previewAlt: '엽서 프레임에 넣은 네컷만화 미리보기',
  printHint: '4x6 엽서 용지, 테두리 없음, 배율 100%로 인쇄해 주세요.',
} as const;
