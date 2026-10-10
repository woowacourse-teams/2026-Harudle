export type FeedSort = 'LATEST' | 'POPULAR';

export const isFeedSort = (value: unknown): value is FeedSort => {
  return value === 'LATEST' || value === 'POPULAR';
};
