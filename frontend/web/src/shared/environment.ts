export const isMswEnabled =
  process.env.NODE_ENV === 'development' && process.env.USE_MSW === 'true';
