export const isGuestTrialPath = (pathname: string): boolean => {
  return pathname === '/landing-try' || pathname.startsWith('/landing-try/');
};

export const getGuestDiaryResultPath = (diaryId: string): string => {
  return `/landing-try/result/${encodeURIComponent(diaryId)}`;
};
