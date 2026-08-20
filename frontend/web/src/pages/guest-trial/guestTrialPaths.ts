export const getGuestDiaryResultPath = (diaryId: string): string => {
  return `/randing-try/result/${encodeURIComponent(diaryId)}`;
};
