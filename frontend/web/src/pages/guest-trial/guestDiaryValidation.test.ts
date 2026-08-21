import { afterEach, describe, expect, it, jest } from '@jest/globals';
import { getKoreanToday, validateGuestDiary } from './guestDiaryValidation';

afterEach(() => {
  jest.useRealTimers();
});

describe('게스트 일기 입력 검증', () => {
  it('한국 시간의 연월일을 고정된 ISO 형식으로 반환한다', () => {
    jest.useFakeTimers().setSystemTime(new Date('2026-08-20T15:30:00Z'));

    expect(getKoreanToday()).toBe('2026-08-21');
  });

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

  it.each([10, 300])('%i자인 내용과 오늘 날짜를 허용한다', (length) => {
    expect(
      validateGuestDiary(
        {
          diaryDate: '2026-08-20',
          sourceText: '가'.repeat(length),
        },
        '2026-08-20',
      ),
    ).toEqual({});
  });

  it('301자인 내용을 거부한다', () => {
    expect(
      validateGuestDiary(
        {
          diaryDate: '2026-08-20',
          sourceText: '가'.repeat(301),
        },
        '2026-08-20',
      ),
    ).toMatchObject({
      sourceText: '오늘의 이야기는 300자까지 적을 수 있어요',
    });
  });

  it('존재하지 않는 날짜를 거부한다', () => {
    expect(
      validateGuestDiary(
        {
          diaryDate: '2026-02-30',
          sourceText: '오늘의 이야기를 충분히 길게 적었습니다',
        },
        '2026-08-20',
      ),
    ).toMatchObject({
      diaryDate: '일기 날짜를 선택해주세요',
    });
  });
});
