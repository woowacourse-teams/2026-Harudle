import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import type {
  AdminStats,
  AdminUserDetail,
  AdminUserSearchParams,
  AdminUserSummary,
  GenerationHistory,
  GenerationSearchParams,
  Page,
} from './adminApi';
import AdminPage from './AdminPage';
import { beforeEach, describe, expect, it, jest } from '@jest/globals';

// 주소창 역할. 화면이 쓴 주소를 다시 읽어 새로고침을 흉내 낸다.
let mockLocationSearch = '';

jest.mock('./AdminReportsPanel', () => ({
  __esModule: true,
  default: () => null,
}));
jest.mock('./AdminModerationPanel', () => ({
  __esModule: true,
  default: () => null,
}));

// 주소를 바꾸면 다시 그리도록 실제 라우터처럼 상태로 보관한다.
const mockRouter = {
  useSearchParams: (): [URLSearchParams, (next: URLSearchParams) => void] => {
    const [params, setParams] = useState(
      () => new URLSearchParams(mockLocationSearch),
    );
    return [
      params,
      (next): void => {
        mockLocationSearch = next.toString();
        setParams(next);
      },
    ];
  },
};

jest.mock('react-router', () => ({
  useSearchParams: () => mockRouter.useSearchParams(),
}));

const mockGetAdminUser =
  jest.fn<(userId: string) => Promise<AdminUserDetail>>();
const mockSearchAdminGenerations =
  jest.fn<
    (options?: GenerationSearchParams) => Promise<Page<GenerationHistory>>
  >();
const mockSearchAdminUsers =
  jest.fn<(params: AdminUserSearchParams) => Promise<Page<AdminUserSummary>>>();
const mockGetAdminStats = jest.fn<() => Promise<AdminStats>>();
const mockResetAdminUsage = jest.fn();
const mockRestoreAdminUsage = jest.fn();
const mockSetAdminGenerationLimit = jest.fn();

jest.mock('./adminApi', () => ({
  ADMIN_USER_SORTS: ['NAME', 'CREATED_AT', 'REMAINING_COUNT'],
  SORT_DIRECTIONS: ['ASC', 'DESC'],
  getAdminStats: () => mockGetAdminStats(),
  getAdminUser: (userId: string) => mockGetAdminUser(userId),
  resetAdminUsage: (userId: string) => mockResetAdminUsage(userId),
  restoreAdminUsage: (userId: string, count: number) =>
    mockRestoreAdminUsage(userId, count),
  searchAdminGenerations: (options?: GenerationSearchParams) =>
    mockSearchAdminGenerations(options),
  searchAdminUsers: (params: AdminUserSearchParams) =>
    mockSearchAdminUsers(params),
  setAdminGenerationLimit: (userId: string, limitCount: number) =>
    mockSetAdminGenerationLimit(userId, limitCount),
}));

jest.mock('../../assets/images/harudle-logo.webp', () => 'harudle-logo.webp');
jest.mock('./assets/admin-character-default-admin-dog.png', () => 'dog.png');
jest.mock(
  './assets/admin-character-generation-history.png',
  () => 'history.png',
);
jest.mock('./assets/admin-character-user-detail.png', () => 'detail.png');
jest.mock('./assets/admin-character-user-search.png', () => 'search.png');
jest.mock('./assets/admin-hero-person-and-dog.png', () => 'hero.png');
jest.mock('./assets/admin-operation-alert.png', () => 'alert.png');
jest.mock('./assets/admin-nav-dashboard.png', () => 'dashboard.png');
jest.mock('./assets/admin-nav-failures.png', () => 'failures.png');
jest.mock('./assets/admin-nav-generations.png', () => 'generations.png');
jest.mock('./assets/admin-nav-users.png', () => 'users.png');
jest.mock('./assets/admin-search-icon.png', () => 'search-icon.png');
jest.mock('./postcard/postcard-frame.webp', () => 'postcard-frame.webp');

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
}

const createDeferred = <T,>(): Deferred<T> => {
  let resolver: (value: T | PromiseLike<T>) => void = () => {};
  const promise = new Promise<T>((resolve) => {
    resolver = resolve;
  });
  return {
    promise,
    resolve: (value) => resolver(value),
  };
};

const usage = {
  usageDate: '2026-08-28',
  usedCount: 2,
  limitCount: 3,
  remainingCount: 1,
};

const createUser = (
  id: string,
  name: string,
  nickname: string | null = null,
  generatedDiaryCount = 0,
): AdminUserSummary => ({
  id,
  name,
  nickname,
  status: 'ACTIVE',
  createdAt: '2026-08-01T09:00:00Z',
  lastLoginAt: null,
  generationUsage: usage,
  generatedDiaryCount,
});

const createDetail = ({
  id,
  name,
  nickname,
  status,
  createdAt,
  lastLoginAt,
  generationUsage,
}: AdminUserSummary): AdminUserDetail => ({
  id,
  name,
  nickname,
  status,
  createdAt,
  lastLoginAt,
  generationUsage,
  recentGenerations: [],
});

const createPage = <T,>(
  content: T[],
  totalElements = content.length,
): Page<T> => ({
  content,
  page: 0,
  size: 20,
  totalElements,
  totalPages: totalElements === 0 ? 0 : 1,
  hasNext: false,
});

const renderAdmin = (initialEntry: string) => {
  mockLocationSearch = initialEntry.split('?')[1] ?? '';
  return render(<AdminPage />);
};

const currentUrl = (): URLSearchParams =>
  new URLSearchParams(mockLocationSearch);

const lastUserSearch = (): AdminUserSearchParams | undefined =>
  mockSearchAdminUsers.mock.calls.at(-1)?.[0];

const userRowNames = (): string[] =>
  screen
    .getAllByRole('button', { name: / 상세 보기$/ })
    .map(
      (button) =>
        button.getAttribute('aria-label')?.replace(' 상세 보기', '') ?? '',
    );

beforeEach(() => {
  mockGetAdminStats.mockReset();
  mockGetAdminStats.mockResolvedValue({
    memberCount: 0,
    todaySignupCount: 0,
  });
  mockGetAdminUser.mockReset();
  mockSearchAdminGenerations.mockReset();
  mockSearchAdminUsers.mockReset();
  mockResetAdminUsage.mockReset();
  mockRestoreAdminUsage.mockReset();
  mockSetAdminGenerationLimit.mockReset();
});

describe('관리자 대시보드 회원 수', () => {
  beforeEach(() => {
    mockSearchAdminUsers.mockResolvedValue(
      createPage([createUser('user-a', '사용자 A')]),
    );
    mockSearchAdminGenerations.mockResolvedValue(
      createPage<GenerationHistory>([]),
    );
  });

  it('대시보드에 API가 알려 준 전체 회원 수를 보여 준다', async () => {
    mockGetAdminStats.mockResolvedValue({
      memberCount: 1234,
      todaySignupCount: 5,
    });

    renderAdmin('/admin');

    const card = screen.getByRole('region', { name: '전체 회원 수' });
    expect(await within(card).findByText('1,234명')).toBeVisible();
  });

  it('같은 통계 응답으로 오늘 가입자 수를 함께 보여 준다', async () => {
    mockGetAdminStats.mockResolvedValue({
      memberCount: 1234,
      todaySignupCount: 5,
    });

    renderAdmin('/admin');

    const card = screen.getByRole('region', { name: '오늘 가입자 수' });
    expect(await within(card).findByText('5명')).toBeVisible();
    expect(within(card).getByText('한국 시간 0시 이후')).toBeVisible();
    expect(mockGetAdminStats).toHaveBeenCalledTimes(1);
  });

  it('회원 수 조회에 실패해도 나머지 대시보드는 그대로 보여 준다', async () => {
    mockGetAdminStats.mockRejectedValue(new Error('stats down'));

    renderAdmin('/admin');

    const card = screen.getByRole('region', { name: '전체 회원 수' });
    expect(
      await within(card).findByText('회원 수를 불러오지 못했어요.'),
    ).toBeVisible();
    expect(within(card).getByText('-')).toBeVisible();
    const signupCard = screen.getByRole('region', { name: '오늘 가입자 수' });
    expect(
      within(signupCard).getByText('가입자 수를 불러오지 못했어요.'),
    ).toBeVisible();
    expect(within(signupCard).getByText('-')).toBeVisible();
    expect(
      await screen.findByRole('button', { name: '사용자 A 상세 보기' }),
    ).toBeVisible();
  });

  it('대시보드가 아닌 화면에서는 회원 수를 조회하지 않는다', async () => {
    renderAdmin('/admin?view=users');
    await screen.findByRole('button', { name: '사용자 A 상세 보기' });
    expect(mockGetAdminStats).not.toHaveBeenCalled();
  });
});

describe('관리자 엽서 인쇄', () => {
  it('생성 이력에서 성공한 생성에만 엽서 인쇄 버튼을 보여 준다', async () => {
    const user = { id: 'user-a', name: '사용자 A' };
    mockSearchAdminGenerations.mockResolvedValue(
      createPage<GenerationHistory>([
        {
          id: 'succeeded-generation',
          user,
          requestedAt: '2026-11-17T11:52:10Z',
          status: 'SUCCEEDED',
          completedAt: '2026-11-17T11:52:41Z',
          errorCode: null,
        },
        {
          id: 'failed-generation',
          user,
          requestedAt: '2026-11-17T11:49:33Z',
          status: 'FAILED',
          completedAt: '2026-11-17T11:50:20Z',
          errorCode: 'IMAGE_GENERATION_FAILED',
        },
      ]),
    );

    renderAdmin('/admin?view=generations');

    expect(
      await screen.findAllByRole('button', { name: '엽서 인쇄' }),
    ).toHaveLength(1);
    expect(
      screen.getByText('succeeded-generation').closest('tr'),
    ).toContainElement(screen.getByRole('button', { name: '엽서 인쇄' }));
  });
});

describe('관리자 페이지 비동기 요청 순서', () => {
  it('신고 관리 화면은 기존 사용자·생성 목록을 조회하지 않는다', async () => {
    renderAdmin('/admin?view=reports');
    expect(screen.getByRole('heading', { name: '신고 관리' })).toBeVisible();
    await act(async (): Promise<void> => undefined);
    expect(mockSearchAdminUsers).not.toHaveBeenCalled();
    expect(mockSearchAdminGenerations).not.toHaveBeenCalled();
  });

  it('사용자 상세 조회는 마지막으로 선택한 사용자의 응답만 반영한다', async () => {
    const userA = createUser('user-a', '사용자 A');
    const userB = createUser('user-b', '사용자 B');
    const detailA = createDeferred<AdminUserDetail>();
    const detailB = createDeferred<AdminUserDetail>();
    const detailRequests = new Map([
      [userA.id, detailA],
      [userB.id, detailB],
    ]);

    mockSearchAdminUsers.mockResolvedValue(createPage([userA, userB]));
    mockGetAdminUser.mockImplementation((userId) => {
      const request = detailRequests.get(userId);
      if (!request) throw new Error(`예상하지 못한 사용자: ${userId}`);
      return request.promise;
    });

    const user = userEvent.setup();
    renderAdmin('/admin?view=users');

    await screen.findByRole('button', { name: '사용자 A 상세 보기' });
    await user.click(
      screen.getByRole('button', { name: '사용자 A 상세 보기' }),
    );
    await user.click(
      screen.getByRole('button', { name: '사용자 B 상세 보기' }),
    );

    await act(async () => {
      detailB.resolve(createDetail(userB));
    });
    await waitFor(() => {
      expect(
        screen.getByText('사용자 B', { selector: 'dd' }),
      ).toBeInTheDocument();
    });

    await act(async () => {
      detailA.resolve(createDetail(userA));
    });
    await waitFor(() => {
      expect(
        screen.getByText('사용자 B', { selector: 'dd' }),
      ).toBeInTheDocument();
      expect(
        screen.queryByText('사용자 A', { selector: 'dd' }),
      ).not.toBeInTheDocument();
    });
  });

  it('대시보드 조회와 사용자 검색이 겹치면 검색 결과를 유지한다', async () => {
    const dashboardUser = createUser('dashboard-user', '대시보드 사용자');
    const searchedUser = createUser('searched-user', '검색 사용자');
    const dashboardUsers = createDeferred<Page<AdminUserSummary>>();
    const searchedUsers = createDeferred<Page<AdminUserSummary>>();

    mockSearchAdminUsers.mockImplementation(({ query }) =>
      query === '' ? dashboardUsers.promise : searchedUsers.promise,
    );
    mockSearchAdminGenerations.mockResolvedValue(
      createPage<GenerationHistory>([]),
    );

    const user = userEvent.setup();
    renderAdmin('/admin');

    const searchInput = screen.getByPlaceholderText(
      '이름, 닉네임 또는 사용자 ID 검색',
    );
    await user.type(searchInput, '검색어');
    await user.click(screen.getByRole('button', { name: '검색' }));

    await act(async () => {
      searchedUsers.resolve(createPage([searchedUser]));
    });
    await waitFor(() => {
      expect(screen.getByText('검색 사용자')).toBeInTheDocument();
    });

    await act(async () => {
      dashboardUsers.resolve(createPage([dashboardUser]));
    });
    await waitFor(() => {
      expect(screen.getByText('검색 사용자')).toBeInTheDocument();
      expect(screen.queryByText('대시보드 사용자')).not.toBeInTheDocument();
    });
  });

  it('조회 날짜를 바꾸면 생성 이력만 그 날짜로 다시 조회하고 사용자 목록은 다시 조회하지 않는다', async () => {
    mockSearchAdminUsers.mockResolvedValue(
      createPage([createUser('user-a', '사용자 A')]),
    );
    mockSearchAdminGenerations.mockResolvedValue(
      createPage<GenerationHistory>([]),
    );

    renderAdmin('/admin');
    await screen.findByRole('button', { name: '사용자 A 상세 보기' });
    const userRequestsBefore = mockSearchAdminUsers.mock.calls.length;

    fireEvent.change(screen.getByLabelText('조회 날짜'), {
      target: { value: '2026-08-01' },
    });

    await waitFor(() => {
      expect(mockSearchAdminGenerations).toHaveBeenCalledWith(
        expect.objectContaining({ from: '2026-08-01', to: '2026-08-01' }),
      );
    });
    expect(mockSearchAdminUsers).toHaveBeenCalledTimes(userRequestsBefore);
  });
});

describe('관리자 사용자 목록', () => {
  beforeEach(() => {
    mockSearchAdminGenerations.mockResolvedValue(
      createPage<GenerationHistory>([]),
    );
  });

  it('닉네임과 생성한 일기 수를 보여 주고 닉네임이 없으면 -로 표시한다', async () => {
    mockSearchAdminUsers.mockResolvedValue(
      createPage([
        createUser('user-a', '사용자 A', 'SunnyHaru', 1234),
        createUser('user-b', '사용자 B', null, 0),
      ]),
    );

    renderAdmin('/admin?view=users');

    const rowA = (
      await screen.findByRole('button', { name: '사용자 A 상세 보기' })
    ).closest('tr');
    const rowB = screen
      .getByRole('button', { name: '사용자 B 상세 보기' })
      .closest('tr');
    if (!rowA || !rowB) throw new Error('사용자 행이 없습니다.');
    expect(screen.getByRole('columnheader', { name: '닉네임' })).toBeVisible();
    expect(
      screen.getByRole('columnheader', { name: '생성한 일기 수' }),
    ).toBeVisible();
    expect(within(rowA).getByText('SunnyHaru')).toBeVisible();
    expect(within(rowA).getByText('1,234')).toBeVisible();
    expect(within(rowB).getByText('-')).toBeVisible();
    expect(within(rowB).getByText('0')).toBeVisible();
  });

  it('사용자 상세에 이름 아래 닉네임을 보여 주고 닉네임이 없으면 -로 표시한다', async () => {
    const userA = createUser('user-a', '사용자 A', 'SunnyHaru');
    const userB = createUser('user-b', '사용자 B', null);
    mockSearchAdminUsers.mockResolvedValue(createPage([userA, userB]));
    mockGetAdminUser.mockImplementation(async (userId) =>
      createDetail(userId === userA.id ? userA : userB),
    );
    const detailRow = (label: string): string[] => {
      const term = screen.queryByText(label, { selector: 'dt' });
      return [
        term?.previousElementSibling?.previousElementSibling?.textContent ?? '',
        term?.nextElementSibling?.textContent ?? '',
      ];
    };

    const user = userEvent.setup();
    renderAdmin('/admin?view=users');

    await user.click(
      await screen.findByRole('button', { name: '사용자 A 상세 보기' }),
    );
    await screen.findByText('사용자 A', { selector: 'dd' });
    expect(detailRow('닉네임')).toEqual(['이름', 'SunnyHaru']);

    await user.click(
      screen.getByRole('button', { name: '사용자 B 상세 보기' }),
    );
    await screen.findByText('사용자 B', { selector: 'dd' });
    expect(detailRow('닉네임')).toEqual(['이름', '-']);
  });

  it('정렬을 바꾸면 그 기준으로 첫 쪽부터 다시 조회하고 주소에 남긴다', async () => {
    const neo = createUser('user-neo', '네오');
    const zeta = createUser('user-zeta', '하늘');
    mockSearchAdminUsers.mockImplementation(({ sort, direction }) =>
      Promise.resolve(
        createPage(
          sort === 'NAME' && direction === 'DESC' ? [zeta, neo] : [neo, zeta],
        ),
      ),
    );
    const user = userEvent.setup();
    renderAdmin('/admin?view=users&usersPage=2');

    await screen.findByRole('button', { name: '네오 상세 보기' });
    expect(lastUserSearch()).toMatchObject({
      sort: 'CREATED_AT',
      direction: 'DESC',
      page: 2,
    });

    await user.selectOptions(
      screen.getByRole('combobox', { name: '정렬 기준' }),
      '사전순',
    );
    await waitFor(() => expect(userRowNames()).toEqual(['네오', '하늘']));
    expect(lastUserSearch()).toMatchObject({
      sort: 'NAME',
      direction: 'ASC',
      page: 0,
    });
    expect(currentUrl().get('sort')).toBe('NAME');
    expect(currentUrl().has('usersPage')).toBe(false);

    await user.selectOptions(
      screen.getByRole('combobox', { name: '정렬 방향' }),
      '내림차순',
    );
    await waitFor(() => expect(userRowNames()).toEqual(['하늘', '네오']));
    expect(lastUserSearch()).toMatchObject({ sort: 'NAME', direction: 'DESC' });

    await user.selectOptions(
      screen.getByRole('combobox', { name: '정렬 방향' }),
      '오름차순',
    );
    await user.selectOptions(
      screen.getByRole('combobox', { name: '정렬 기준' }),
      '오늘 남은 횟수',
    );
    await waitFor(() =>
      expect(lastUserSearch()).toMatchObject({
        sort: 'REMAINING_COUNT',
        direction: 'DESC',
        page: 0,
      }),
    );

    await user.selectOptions(
      screen.getByRole('combobox', { name: '정렬 기준' }),
      '가입일',
    );
    await waitFor(() =>
      expect(lastUserSearch()).toMatchObject({
        sort: 'CREATED_AT',
        direction: 'DESC',
      }),
    );
    expect(currentUrl().has('sort')).toBe(false);
    expect(currentUrl().has('direction')).toBe(false);
  });

  it('검색어·정렬·쪽·조회 날짜는 새로고침해도 주소에서 그대로 복원한다', async () => {
    mockSearchAdminUsers.mockResolvedValue(
      createPage([createUser('user-a', '사용자 A', 'SunnyHaru')]),
    );
    const user = userEvent.setup();
    const { unmount } = renderAdmin('/admin');
    await screen.findByRole('button', { name: '사용자 A 상세 보기' });

    await user.type(
      screen.getByPlaceholderText('이름, 닉네임 또는 사용자 ID 검색'),
      '  sunny  ',
    );
    await user.click(screen.getByRole('button', { name: '검색' }));
    await user.selectOptions(
      screen.getByRole('combobox', { name: '정렬 기준' }),
      '오늘 남은 횟수',
    );
    await user.selectOptions(
      screen.getByRole('combobox', { name: '정렬 방향' }),
      '오름차순',
    );
    fireEvent.change(screen.getByLabelText('조회 날짜'), {
      target: { value: '2026-08-01' },
    });
    await waitFor(() =>
      expect(currentUrl().get('historyDate')).toBe('2026-08-01'),
    );
    const savedUrl = mockLocationSearch;
    unmount();

    mockSearchAdminUsers.mockClear();
    mockSearchAdminGenerations.mockClear();
    renderAdmin(`/admin?${savedUrl}&usersPage=1`);

    await screen.findByRole('button', { name: '사용자 A 상세 보기' });
    expect(mockSearchAdminUsers).toHaveBeenCalledTimes(1);
    expect(lastUserSearch()).toEqual({
      query: 'sunny',
      sort: 'REMAINING_COUNT',
      direction: 'ASC',
      page: 1,
      size: 20,
    });
    expect(
      screen.getByPlaceholderText('이름, 닉네임 또는 사용자 ID 검색'),
    ).toHaveValue('sunny');
    expect(screen.getByRole('combobox', { name: '정렬 기준' })).toHaveValue(
      'REMAINING_COUNT',
    );
    expect(screen.getByRole('combobox', { name: '정렬 방향' })).toHaveValue(
      'ASC',
    );
    expect(screen.getByLabelText('조회 날짜')).toHaveValue('2026-08-01');
    expect(mockSearchAdminGenerations).toHaveBeenCalledWith(
      expect.objectContaining({ from: '2026-08-01', to: '2026-08-01' }),
    );
  });

  it.each([
    ['소문자 정렬 값', 'sort=name&direction=asc'],
    ['알 수 없는 정렬 값', 'sort=NICKNAME&direction=UP'],
    ['음수 쪽 번호와 미래 날짜', 'usersPage=-1&historyDate=2999-01-01'],
    ['형식이 틀린 날짜', 'usersPage=abc&historyDate=2026-02-30'],
  ])('주소에 %s이 있으면 기본값으로 조회한다', async (_, query) => {
    mockSearchAdminUsers.mockResolvedValue(
      createPage([createUser('user-a', '사용자 A')]),
    );
    mockSearchAdminGenerations.mockResolvedValue(
      createPage<GenerationHistory>([]),
    );

    renderAdmin(`/admin?${query}`);

    await screen.findByRole('button', { name: '사용자 A 상세 보기' });
    expect(lastUserSearch()).toMatchObject({
      sort: 'CREATED_AT',
      direction: 'DESC',
      page: 0,
    });
    expect(screen.getByRole('combobox', { name: '정렬 기준' })).toHaveValue(
      'CREATED_AT',
    );
    expect(screen.getByRole('combobox', { name: '정렬 방향' })).toHaveValue(
      'DESC',
    );
    const todayDate = screen.getByLabelText('조회 날짜').getAttribute('max');
    expect(mockSearchAdminGenerations).toHaveBeenCalledWith(
      expect.objectContaining({ from: todayDate, to: todayDate }),
    );
  });
});
