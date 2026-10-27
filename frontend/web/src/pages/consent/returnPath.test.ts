import { describe, expect, it } from '@jest/globals';
import { getReturnPath, sanitizeReturnPath } from './returnPath';

describe('동의 후 돌아갈 내부 경로', (): void => {
  it.each([
    '/setting',
    '/setting/blocked-users',
    '/album?year=2026#month',
    '/feeds/00000000-0000-4000-8000-000000000001#comments',
    '/diary/00000000-0000-4000-8000-000000000001/feed-post',
    '/admin/reports',
  ])('허용된 서비스 경로를 유지한다: %s', (path): void => {
    expect(sanitizeReturnPath(path)).toBe(path);
  });
  it.each([
    null,
    {},
    '//example.com',
    'https://example.com',
    '/\\example.com',
    '/login',
    '/auth/callback',
    '/consent',
    '/legal/terms',
    '/admin/../../consent',
    '/%2fauth/callback',
    '/unknown',
  ])(
    '외부 주소·반복 이동·알 수 없는 경로는 홈으로 제한한다: %j',
    (path): void => {
      expect(sanitizeReturnPath(path)).toBe('/');
    },
  );
  it('라우터 state의 복귀 경로도 검증한다', (): void => {
    expect(getReturnPath({ returnTo: '//example.com' })).toBe('/');
    expect(getReturnPath({ returnTo: '/setting' })).toBe('/setting');
  });
});
