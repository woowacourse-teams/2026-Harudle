import { describe, expect, it } from '@jest/globals';
import { validateGuestDiary } from './guestDiaryValidation';

describe('게스트 일기 입력 검증', () => {
  it('날짜와 내용이 유효하면 오류가 없다', () => {
    expect(
      validateGuestDiary(
        {
          diaryDate: '2026-08-20',
          sourceText: '오늘은 게스트 체험으로 그림 일기를 만들었다.',
        },
        '2026-08-20',
      ),
    ).toEqual({});
  });

  it('미래 날짜와 10자 미만의 내용을 거부한다', () => {
    expect(
      validateGuestDiary(
        { diaryDate: '2026-08-21', sourceText: '짧은 일기' },
        '2026-08-20',
      ),
    ).toEqual({
      diaryDate: '오늘 이후의 날짜는 선택할 수 없어요',
      sourceText: '오늘의 이야기를 10자 이상 적어주세요',
    });
  });
});
