export const isLandingPath = (pathname: string): boolean => {
  return pathname === '/landing' || pathname.startsWith('/landing/');
};

export const getLandingResultPath = (diaryId: string): string => {
  return `/landing/result/${encodeURIComponent(diaryId)}`;
};
