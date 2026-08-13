export const formatKoreanDate = (date: Date): string => {
  return date.toLocaleDateString('sv-SE', {
    timeZone: 'Asia/Seoul',
  });
};
