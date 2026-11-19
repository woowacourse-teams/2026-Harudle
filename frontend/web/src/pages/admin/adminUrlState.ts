import {
  ADMIN_USER_SORTS,
  SORT_DIRECTIONS,
  type AdminUserSort,
  type SortDirection,
} from './adminApi';

const ADMIN_VIEWS = [
  'dashboard',
  'users',
  'generations',
  'failed',
  'reports',
] as const;
export type AdminView = (typeof ADMIN_VIEWS)[number];

/** 새로고침과 뒤로 가기에도 유지하는 관리자 화면 조건. 기본값인 항목은 주소에서 뺀다. */
export interface AdminUrlState {
  readonly view: AdminView;
  /** 검색 버튼으로 제출한 검색어. 입력 중인 값은 담지 않는다. */
  readonly userQuery: string;
  readonly userSort: AdminUserSort;
  readonly userDirection: SortDirection;
  readonly usersPage: number;
  /** 생성 이력 조회 날짜(YYYY-MM-DD). 빈 문자열이면 날짜 제한 없이 조회한다. */
  readonly historyDate: string;
  readonly historyPage: number;
}

export const today = (): string => {
  const date = new Date();
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 10);
};

interface UrlField<K extends keyof AdminUrlState> {
  readonly read: (params: URLSearchParams) => AdminUrlState[K];
  readonly write: (params: URLSearchParams, state: AdminUrlState) => void;
}

/** 주소의 값이 없거나 읽을 수 없으면 기본값을 쓰고, 기본값과 같은 값은 주소에 쓰지 않는다. */
const field = <K extends keyof AdminUrlState>(
  name: K,
  key: string,
  fallback: () => AdminUrlState[K],
  parse: (raw: string) => AdminUrlState[K] | null,
): UrlField<K> => ({
  read: (params): AdminUrlState[K] => {
    const raw = params.get(key);
    return (raw === null ? null : parse(raw)) ?? fallback();
  },
  write: (params, state): void => {
    const value = state[name];
    if (value === fallback()) params.delete(key);
    else params.set(key, String(value));
  },
});

const oneOf =
  <T extends string>(values: readonly T[]) =>
  (raw: string): T | null =>
    values.find((value) => value === raw) ?? null;

const parsePage = (raw: string): number | null => {
  const page = Number(raw);
  return raw !== '' && Number.isSafeInteger(page) && page >= 0 ? page : null;
};

const parseHistoryDate = (raw: string): string | null => {
  if (raw === '') return raw;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(raw)) return null;
  const date = new Date(`${raw}T00:00:00Z`);
  const isRealDate =
    !Number.isNaN(date.getTime()) && date.toISOString().startsWith(raw);
  return isRealDate && raw <= today() ? raw : null;
};

const FIELDS = {
  view: field('view', 'view', () => 'dashboard', oneOf(ADMIN_VIEWS)),
  userQuery: field(
    'userQuery',
    'query',
    () => '',
    (raw) => raw,
  ),
  userSort: field(
    'userSort',
    'sort',
    () => 'CREATED_AT',
    oneOf(ADMIN_USER_SORTS),
  ),
  userDirection: field(
    'userDirection',
    'direction',
    () => 'DESC',
    oneOf(SORT_DIRECTIONS),
  ),
  usersPage: field('usersPage', 'usersPage', () => 0, parsePage),
  historyDate: field('historyDate', 'historyDate', today, parseHistoryDate),
  historyPage: field('historyPage', 'historyPage', () => 0, parsePage),
} as const satisfies { readonly [K in keyof AdminUrlState]: UrlField<K> };

export const readAdminUrlState = (params: URLSearchParams): AdminUrlState => ({
  view: FIELDS.view.read(params),
  userQuery: FIELDS.userQuery.read(params),
  userSort: FIELDS.userSort.read(params),
  userDirection: FIELDS.userDirection.read(params),
  usersPage: FIELDS.usersPage.read(params),
  historyDate: FIELDS.historyDate.read(params),
  historyPage: FIELDS.historyPage.read(params),
});

export const writeAdminUrlState = (state: AdminUrlState): URLSearchParams => {
  const params = new URLSearchParams();
  for (const urlField of Object.values(FIELDS)) urlField.write(params, state);
  return params;
};
