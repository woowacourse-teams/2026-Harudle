export const CONTACT_COPY = {
  pageTitle: '문의하기',
  backLabel: '하루들로 돌아가기',
  title: '무엇을 도와드릴까요?',
  description: '궁금한 점을 메일로 보내 주세요.',
  emailLabel: '운영팀 이메일',
  emailAddress: 'harudle.official@gmail.com',
  replyNotice: '문의 종류와 내용을 적어 주세요. 보내주신 메일로 답변드려요.',
  mailAction: '메일로 문의하기',
  mailNotice: '메일 앱에서 작성 후 직접 보내 주세요.',
  copyAction: '이메일 주소 복사',
  copyPending: '복사 중...',
  copySuccess: '이메일 주소를 복사했어요.',
  copyFailed: '복사하지 못했어요. 주소를 선택해 직접 복사해 주세요.',
  copyUnsupported: '주소를 선택해 직접 복사해 주세요.',
  mailSubject: '[하루들 문의]',
  mailBody:
    '비밀번호나 일기 원문은 적지 말아 주세요.\n\n문의 종류: 이용 오류 / 계정·로그인 / 기타\n\n문의 내용:\n\n문제 발생 시점 (선택):',
} as const;

export const CONTACT_MAILTO_URL =
  `mailto:${CONTACT_COPY.emailAddress}` +
  `?subject=${encodeURIComponent(CONTACT_COPY.mailSubject)}` +
  `&body=${encodeURIComponent(CONTACT_COPY.mailBody)}`;
