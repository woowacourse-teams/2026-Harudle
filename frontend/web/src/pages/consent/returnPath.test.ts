import { afterEach, describe, expect, it, jest } from '@jest/globals';
import {
  consumeLoginReturnPath,
  getReturnPath,
  rememberLoginReturnPath,
  sanitizeReturnPath,
} from './returnPath';

afterEach((): void => {
  sessionStorage.clear();
  jest.restoreAllMocks();
});

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
  it('라우터 state도 검증하고 OAuth 전후에 내부 목적지를 한 번만 전달한다', (): void => {
    expect(getReturnPath({ returnTo: '//example.com' })).toBe('/');
    expect(getReturnPath({ returnTo: '/setting' })).toBe('/setting');
    rememberLoginReturnPath({ returnTo: '/setting' });
    expect(consumeLoginReturnPath()).toBe('/setting');
    expect(consumeLoginReturnPath()).toBe('/');
  });
  it('저장소에서 조작한 경로도 다시 검증한다', (): void => {
    sessionStorage.setItem('harudle:login-return-path:v1', '//example.com');
    expect(consumeLoginReturnPath()).toBe('/');
    expect(sessionStorage.getItem('harudle:login-return-path:v1')).toBeNull();
  });
});
