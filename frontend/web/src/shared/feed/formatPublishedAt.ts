export const formatPublishedAt = (publishedAt: string): string => {
  const elapsed = Date.now() - new Date(publishedAt).getTime();
  if (!Number.isFinite(elapsed)) return '';
  const minutes = Math.max(0, Math.floor(elapsed / 60_000));
  if (minutes < 1) return '방금 전';
  if (minutes < 60) return `${minutes}분 전`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}시간 전`;
  return `${Math.floor(hours / 24)}일 전`;
};
