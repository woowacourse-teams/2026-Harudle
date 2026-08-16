export type Month = 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12;

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
