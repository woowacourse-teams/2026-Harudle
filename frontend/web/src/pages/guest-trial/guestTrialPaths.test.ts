import { describe, expect, it } from '@jest/globals';
import { getGuestDiaryResultPath } from './guestTrialPaths';

describe('게스트 체험 경로', () => {
  it('게스트 결과 내부 URL을 randing-try 하위에 만든다', () => {
    expect(
      getGuestDiaryResultPath('7e5cc251-fdde-4cc0-a54e-2c8142750609'),
    ).toBe('/randing-try/result/7e5cc251-fdde-4cc0-a54e-2c8142750609');
  });
});
