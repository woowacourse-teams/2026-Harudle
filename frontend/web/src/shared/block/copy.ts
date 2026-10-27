export const BLOCK_COPY = {
  title: '작성자 차단 확인',
  confirm: '차단하기',
  submitting: '차단 중…',
  failed: '차단하지 못했어요. 다시 시도해 주세요.',
} as const;

export const getBlockDescription = (nickname: string): string =>
  `${nickname}님이 작성한 피드와 댓글을 더 이상 볼 수 없어요.`;
