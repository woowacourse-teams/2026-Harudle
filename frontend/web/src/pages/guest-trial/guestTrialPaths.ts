export const getGuestDiaryResultPath = (diaryId: string): string => {
  return `/landing-try/result/${encodeURIComponent(diaryId)}`;
};
