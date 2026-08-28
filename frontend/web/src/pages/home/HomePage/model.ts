import type { Month } from '../../../shared/utils';

export interface YearMonth {
  year: number;
  month: Month;
}

export interface MonthlyDiaryItem {
  id: string;
  title: string;
  thumbnailUrl: string;
}

export interface MonthlyDiaryDay {
  date: string;
  exist: boolean;
  items: MonthlyDiaryItem[];
}

export interface MonthlyDiariesResponse {
  year: number;
  month: Month;
  days: MonthlyDiaryDay[];
}
