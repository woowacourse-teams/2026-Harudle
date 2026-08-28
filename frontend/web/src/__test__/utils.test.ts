import { afterEach, describe, expect, it, jest } from '@jest/globals';
import { getToday } from '../shared/utils';

afterEach(() => {
  jest.useRealTimers();
});

describe('getToday', () => {
  it('한국 시간 기준 오늘의 연월일을 객체로 반환한다', () => {
    jest.useFakeTimers().setSystemTime(new Date('2026-08-20T15:30:00Z'));

    expect(getToday()).toEqual({
      year: 2026,
      month: 8,
      day: 21,
    });
  });
});
