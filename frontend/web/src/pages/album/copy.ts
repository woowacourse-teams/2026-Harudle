export const ALBUM_COPY = {
  listTitle: '네컷 모아보기',
  monthlyCount: { before: '총 ', after: '편' },
  createAction: '네컷만화 만들기',
  emptyCurrentMonth: '이번 달에는 아직 네컷만화가 없어요.',
  emptyOtherMonth: (year: number, month: number): string =>
    `${year}년 ${month}월에는 아직 네컷만화가 없어요.`,
  emptyDescription: '네컷으로 남기고 싶은 순간이 있나요?',
  remainingUsage: { before: '오늘 ', after: '편 더 만들 수 있어요' },
  usageLoadingCount: '-',
  usageError: '남은 횟수를 불러오지 못했어요',
  usageRetryAction: '다시 확인',
  loadErrorTitle: '네컷만화를 불러오지 못했어요',
  reloadAction: '다시 불러오기',
} as const;
