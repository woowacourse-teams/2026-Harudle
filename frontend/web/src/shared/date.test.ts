import { describe, expect, it } from '@jest/globals';
import { formatKoreanDate } from './date';

describe('formatKoreanDate', () => {
  it('한국 시간 기준 YYYY-MM-DD 형식으로 변환한다', () => {
    const date = new Date('2026-08-05T15:30:00.000Z');

    expect(formatKoreanDate(date)).toBe('2026-08-06');
  });
});
