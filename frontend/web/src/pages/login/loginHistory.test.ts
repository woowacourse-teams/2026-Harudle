import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import {
  clearLoginAttemptProvider,
  getLastSuccessfulLoginProvider,
  getLoginAttemptProvider,
  rememberSuccessfulLogin,
  setLoginAttemptProvider,
} from './loginHistory';

beforeEach((): void => {
  localStorage.clear();
  sessionStorage.clear();
  jest.restoreAllMocks();
});

describe('로그인 방법 기록', () => {
  it('버튼 선택과 실패 처리만으로는 최근 로그인 방법을 바꾸지 않는다', (): void => {
    rememberSuccessfulLogin('kakao');
    setLoginAttemptProvider('apple');
    expect(getLoginAttemptProvider()).toBe('apple');
    expect(getLastSuccessfulLoginProvider()).toBe('kakao');

    clearLoginAttemptProvider();
    expect(getLastSuccessfulLoginProvider()).toBe('kakao');
    expect(getLoginAttemptProvider()).toBeNull();
  });

  it('성공한 방법만 저장하고 진행 중인 기록은 지운다', (): void => {
    setLoginAttemptProvider('google');
    rememberSuccessfulLogin(getLoginAttemptProvider());

    expect(getLastSuccessfulLoginProvider()).toBe('google');
    expect(getLoginAttemptProvider()).toBeNull();
    expect(localStorage.getItem('harudle.has-completed-oauth')).toBe('true');
  });

  it('알 수 없는 저장값을 로그인 방법으로 사용하지 않는다', (): void => {
    localStorage.setItem('harudle.last-login-provider', 'unknown');
    sessionStorage.setItem('harudle.login-attempt-provider', '{}');
    expect(getLastSuccessfulLoginProvider()).toBeNull();
    expect(getLoginAttemptProvider()).toBeNull();
  });

  it('저장소가 막혀 있어도 로그인 성공 처리가 실패하지 않는다', (): void => {
    jest.spyOn(Storage.prototype, 'setItem').mockImplementation((): never => {
      throw new Error('Storage blocked');
    });
    expect((): void => rememberSuccessfulLogin('apple')).not.toThrow();
  });
});
