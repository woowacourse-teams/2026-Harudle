import { describe, expect, it } from '@jest/globals';
import { getGuestDiaryResultPath, isGuestTrialPath } from './guestTrialPaths';

describe('게스트 체험 경로', () => {
  it.each([
    ['/landing-try', true],
    ['/landing-try/result/7e5cc251-fdde-4cc0-a54e-2c8142750609', true],
    ['/landing', false],
    ['/landing-try-other', false],
  ])('%s의 게스트 체험 경로 여부를 판단한다', (pathname, expected) => {
    expect(isGuestTrialPath(pathname)).toBe(expected);
  });

  it('게스트 결과 내부 URL을 landing-try 하위에 만든다', () => {
    expect(
      getGuestDiaryResultPath('7e5cc251-fdde-4cc0-a54e-2c8142750609'),
    ).toBe('/landing-try/result/7e5cc251-fdde-4cc0-a54e-2c8142750609');
  });
});
