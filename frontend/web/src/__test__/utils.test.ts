import { afterEach, describe, expect, it, jest } from '@jest/globals';
import { getToday, isMonth, isNonNegativeInteger } from '../shared/utils';

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

describe('isNonNegativeInteger 테스트', () => {
  it('값이 0인 경우 true를 반환한다.', () => {
    // given
    const validNumber = 0;

    // when & then
    expect(isNonNegativeInteger(validNumber)).toEqual(true);
  });
  it('값이 양의 정수인 경우 true를 반환한다.', () => {
    // given
    const validNumber = 3;

    // when & then
    expect(isNonNegativeInteger(validNumber)).toEqual(true);
  });
  it('값이 0보다 큰 소수인 경우 false를 반환한다', () => {
    // given
    const invalidNumber = 3.3;

    // when & then
    expect(isNonNegativeInteger(invalidNumber)).toEqual(false);
  });
  it('값이 음수인 경우 false를 반환한다.', () => {
    // given
    const invalidNumber = -3;

    // when & then
    expect(isNonNegativeInteger(invalidNumber)).toEqual(false);
  });
});
