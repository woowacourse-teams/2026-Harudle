import { beforeEach, describe, expect, it } from '@jest/globals';
import {
  clearPostLoginPath,
  peekPostLoginPath,
  savePostLoginPath,
} from './postLoginPath';

const STATE = 's'.repeat(43);

describe('로그인 뒤 돌아갈 경로', () => {
  beforeEach((): void => {
    sessionStorage.clear();
  });

  it('우테코 캠퍼스 연결 경로만 저장하고 꺼낸다', (): void => {
    savePostLoginPath(`/campus/connect?state=${STATE}`);

    expect(peekPostLoginPath()).toBe(`/campus/connect?state=${STATE}`);

    clearPostLoginPath();
    expect(peekPostLoginPath()).toBeNull();
  });

  it.each([
    'https://evil.example/campus/connect?state=' + STATE,
    '//evil.example',
    '/setting',
    '/campus/connect?state=short',
  ])('허용하지 않은 경로 %s는 저장하지 않는다', (path): void => {
    savePostLoginPath(path);

    expect(peekPostLoginPath()).toBeNull();
  });

  it('저장소에 다른 값이 들어 있어도 꺼내지 않는다', (): void => {
    sessionStorage.setItem('harudle.post-login-path', '//evil.example');

    expect(peekPostLoginPath()).toBeNull();
  });
});
