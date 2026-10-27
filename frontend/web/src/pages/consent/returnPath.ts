import { isRecord } from '../../shared/utils';

const RETURN_PATH_BASE = 'https://harudle.invalid';
const SIMPLE_PATHS = new Set([
  '/',
  '/album',
  '/setting',
  '/setting/blocked-users',
  '/diary-write',
]);

export const sanitizeReturnPath = (value: unknown): string => {
  if (
    typeof value !== 'string' ||
    !value.startsWith('/') ||
    value.startsWith('//') ||
    value.includes('\\')
  )
    return '/';
  try {
    const url = new URL(value, RETURN_PATH_BASE);
    if (url.origin !== RETURN_PATH_BASE) return '/';
    const allowed =
      SIMPLE_PATHS.has(url.pathname) ||
      /^\/feeds\/[0-9a-f-]+$/i.test(url.pathname) ||
      /^\/diary\/[0-9a-f-]+(?:\/feed-post)?$/i.test(url.pathname) ||
      /^\/admin(?:\/[a-zA-Z0-9/-]*)?$/.test(url.pathname);
    return allowed ? `${url.pathname}${url.search}${url.hash}` : '/';
  } catch {
    return '/';
  }
};

export const getReturnPath = (state: unknown): string =>
  sanitizeReturnPath(isRecord(state) ? state.returnTo : undefined);
