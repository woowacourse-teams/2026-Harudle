import type { GuestDiaryRequest } from './guestTrialApi';

export interface GuestDiaryValidationErrors {
  diaryDate?: string;
  sourceText?: string;
}

export const getKoreanToday = (): string => {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date());
  const findPart = (type: 'year' | 'month' | 'day') => {
    return parts.find((part) => part.type === type)?.value ?? '';
  };

  return `${findPart('year')}-${findPart('month')}-${findPart('day')}`;
};

const isValidIsoDate = (value: string): boolean => {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return false;
  }

  const date = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(date.getTime()) && date.toISOString().startsWith(value);
};

export const validateGuestDiary = (
  request: GuestDiaryRequest,
  today = getKoreanToday(),
): GuestDiaryValidationErrors => {
  const errors: GuestDiaryValidationErrors = {};
  const sourceTextLength = Array.from(request.sourceText.trim()).length;

  if (!isValidIsoDate(request.diaryDate)) {
    errors.diaryDate = '일기 날짜를 선택해주세요';
  } else if (request.diaryDate > today) {
    errors.diaryDate = '오늘 이후의 날짜는 선택할 수 없어요';
  }

  if (sourceTextLength < 10) {
    errors.sourceText = '오늘의 이야기를 10자 이상 적어주세요';
  } else if (sourceTextLength > 300) {
    errors.sourceText = '오늘의 이야기는 300자까지 적을 수 있어요';
  }

  return errors;
};
