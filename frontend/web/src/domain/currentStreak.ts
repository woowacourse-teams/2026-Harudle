import { ERROR_MESSAGES } from '../shared/errorMessage';
import { API_BASE_URL, isProblemDetails, RequestError } from '../shared/api';
import { authFetch } from '../shared/auth';
import { isNonNegativeInteger, isRecord } from '../shared/utils';

export const getCurrentStreak = async (): Promise<CurrentStreakResponse> => {
  const response = await authFetch(`${API_BASE_URL}/diaries/current-streak`);

  if (!response.ok) {
    const errorData: unknown = await response.json().catch(() => null);
    if (isProblemDetails(errorData)) {
      throw new RequestError(errorData);
    }

    throw new Error(ERROR_MESSAGES.CURRENT_STREAK_FETCH_FAILED);
  }

  const data: unknown = await response.json();

  if (!isCurrentStreakResponse(data)) {
    throw new Error(ERROR_MESSAGES.INVALID_CURRENT_STREAK_RESPONSE);
  }

  return data;
};

interface DiaryStreakItem {
  id: string;
  title: string;
  thumbnailUrl: string;
}

interface DiaryStreakDay {
  date: string;
  items: DiaryStreakItem[];
}

export interface CurrentStreak {
  streakCount: number;
  recordedToday: boolean;
}

export interface CurrentStreakResponse extends CurrentStreak {
  days: DiaryStreakDay[];
}

const isDiaryStreakItem = (value: unknown): value is DiaryStreakItem => {
  return (
    isRecord(value) &&
    typeof value.id === 'string' &&
    typeof value.title === 'string' &&
    typeof value.thumbnailUrl === 'string'
  );
};

const isDiaryStreakDay = (value: unknown): value is DiaryStreakDay => {
  return (
    isRecord(value) &&
    typeof value.date === 'string' &&
    Array.isArray(value.items) &&
    value.items.every(isDiaryStreakItem)
  );
};

const isCurrentStreakResponse = (
  value: unknown,
): value is CurrentStreakResponse => {
  return (
    isRecord(value) &&
    'streakCount' in value &&
    typeof value.streakCount === 'number' &&
    isNonNegativeInteger(value.streakCount) &&
    typeof value.recordedToday === 'boolean' &&
    Array.isArray(value.days) &&
    value.days.every(isDiaryStreakDay)
  );
};
