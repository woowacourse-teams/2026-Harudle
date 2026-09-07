import { afterEach, describe, expect, it, jest } from '@jest/globals';
import { getToday, isMonth } from '../shared/utils';

afterEach(() => {
  jest.useRealTimers();
});

describe('getToday 테스트', () => {
  it('한국 시간 기준 오늘의 연월일을 객체로 반환한다', () => {
    jest.useFakeTimers().setSystemTime(new Date('2026-08-20T15:30:00Z'));

    expect(getToday()).toEqual({
      year: 2026,
      month: 8,
      day: 21,
    });
  });
});

describe('isMonth 테스트', () => {
  it('값이 1~12 사이의 정수인 경우 true를 반환한다.', () => {
    // given
    const validMonth = 12;

    // when & then
    expect(isMonth(validMonth)).toEqual(true);
  });
  it('값이 1~12 사이의 정수가 아닌 경우 false를 반환한다.', () => {
    // given
    const invalidMonth = 0;

    // when & then
    expect(isMonth(invalidMonth)).toEqual(false);
  });
  it('값이 1~12 사이의 소수인 경우 false를 반환한다.', () => {
    // given
    const invalidMonth = 1.5;

    // when & then
    expect(isMonth(invalidMonth)).toEqual(false);
  });
});
