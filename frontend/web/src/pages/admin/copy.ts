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
  reasonHint: '처리 사유를 1~500자로 입력해 주세요.',
  pending: '처리 중…',
  confirm: '확인하기',
  cancel: '취소',
  close: '닫기',
  failed: '처리하지 못했어요. 사유를 확인하고 다시 시도해 주세요.',
  conflict:
    '다른 관리자가 변경했어요. 닫은 뒤 새로고침하고 다시 확인해 주세요.',
  refreshError: '최신 정보를 불러오지 못했어요. 다시 불러와 주세요.',
  success: '처리가 완료되었어요.',
  reviewHint:
    '신고 상태만 변경해요. 계정 정지와 콘텐츠 숨김은 별도로 처리해요.',
  suspendHint: '해제할 때까지 로그인과 회원 기능 이용을 제한해요.',
  hideHint:
    '관리자가 숨긴 콘텐츠는 공개 화면에서 보이지 않아요. 원본은 삭제하지 않아요.',
  restoreHint:
    '관리자 제한을 해제해요. 삭제·개인 차단 등 다른 제한은 유지돼요.',
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
} as const;
export const REPORT_REASON_LABELS = {
  SPAM: '스팸·광고',
  HARASSMENT: '괴롭힘·욕설',
  INAPPROPRIATE_CONTENT: '부적절한 콘텐츠',
  OTHER: '기타',
} as const;
