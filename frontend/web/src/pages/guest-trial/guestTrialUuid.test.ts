import { describe, expect, it } from '@jest/globals';
import { isCanonicalUuid } from './guestTrialUuid';

describe('게스트 체험 UUID 검증', () => {
  it.each([
    '7e5cc251-fdde-4cc0-a54e-2c8142750609',
    '00000000-0000-0000-0000-000000000001',
  ])('canonical UUID %s를 허용한다', (value) => {
    expect(isCanonicalUuid(value)).toBe(true);
  });

  it.each([null, '', '1-1-1-1-1'])('잘못된 UUID %s를 거부한다', (value) => {
    expect(isCanonicalUuid(value)).toBe(false);
  });
});
