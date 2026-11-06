import { describe, expect, it } from '@jest/globals';
import { isProblemDetails } from './api';

const problem = {
  type: 'about:blank',
  title: 'Service unavailable',
  status: 503,
  detail: '일기를 저장할 수 없어요.',
  instance: '/api/v1/diaries',
  code: 'DIARY_SOURCE_TEXT_WRITE_UNAVAILABLE',
};

describe('Problem Details 행동 안내 검증', (): void => {
  it('행동 안내가 없는 기존 응답과 문자열 안내가 있는 응답을 허용한다', (): void => {
    expect(isProblemDetails(problem)).toBe(true);
    expect(
      isProblemDetails({ ...problem, action: '나중에 다시 이용해 주세요.' }),
    ).toBe(true);
  });
  it.each([null, 1, {}, [], true])(
    '문자열이 아닌 action %p를 거절한다',
    (action): void => {
      expect(isProblemDetails({ ...problem, action })).toBe(false);
    },
  );
});
