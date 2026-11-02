import type {
  ReportReason,
  ReportTargetType,
} from '../../domain/reports/reportSubmission';

interface ReportReasonOption {
  readonly value: ReportReason;
  readonly label: string;
}

export const REPORT_REASONS: readonly ReportReasonOption[] = [
  { value: 'SPAM', label: '스팸·광고' },
  { value: 'HARASSMENT', label: '괴롭힘·욕설' },
  { value: 'INAPPROPRIATE_CONTENT', label: '부적절한 콘텐츠' },
  { value: 'OTHER', label: '기타' },
];

export const REPORT_TITLES: Readonly<Record<ReportTargetType, string>> = {
  USER: '작성자 신고',
  FEED: '피드 신고',
  COMMENT: '댓글 신고',
};

export const REPORT_COPY = {
  menuTitle: '신고하기',
  loginDescription: '신고를 접수하려면 로그인해 주세요.',
  reason: '신고 사유',
  description: '상세 내용 (선택)',
  descriptionHint:
    '필요한 내용만 적어 주세요. 비밀번호 등 민감한 정보는 적지 마세요.',
  submit: '신고 접수',
  submitting: '접수 중...',
  cancel: '취소',
  close: '확인',
  received: '신고가 접수되었어요. 운영자가 내용을 확인할게요.',
  duplicate: '이미 접수된 신고예요. 처음 제출한 사유와 내용은 유지돼요.',
} as const;
