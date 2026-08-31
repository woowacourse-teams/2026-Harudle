import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type {
  AdminUserDetail,
  AdminUserSummary,
  GenerationHistory,
  GenerationSearchParams,
  Page,
} from './adminApi';
import AdminPage from './AdminPage';
import { beforeEach, describe, expect, it, jest } from '@jest/globals';

let mockSearchParams = new URLSearchParams();
const mockSetSearchParams = jest.fn();

jest.mock('react-router', () => ({
  useSearchParams: () => [mockSearchParams, mockSetSearchParams],
}));

const mockGetAdminUser =
  jest.fn<(userId: string) => Promise<AdminUserDetail>>();
const mockSearchAdminGenerations =
  jest.fn<
    (options?: GenerationSearchParams) => Promise<Page<GenerationHistory>>
  >();
const mockSearchAdminUsers =
  jest.fn<
    (
      query?: string,
      page?: number,
      size?: number,
    ) => Promise<Page<AdminUserSummary>>
  >();
const mockResetAdminUsage = jest.fn();
const mockRestoreAdminUsage = jest.fn();
const mockSetAdminGenerationLimit = jest.fn();

jest.mock('./adminApi', () => ({
  getAdminUser: (userId: string) => mockGetAdminUser(userId),
  resetAdminUsage: (userId: string) => mockResetAdminUsage(userId),
  restoreAdminUsage: (userId: string, count: number) =>
    mockRestoreAdminUsage(userId, count),
  searchAdminGenerations: (options?: GenerationSearchParams) =>
    mockSearchAdminGenerations(options),
  searchAdminUsers: (query?: string, page?: number, size?: number) =>
    mockSearchAdminUsers(query, page, size),
  setAdminGenerationLimit: (userId: string, limitCount: number) =>
    mockSetAdminGenerationLimit(userId, limitCount),
}));

jest.mock('../../assets/images/harudle-logo.png', () => 'harudle-logo.png');
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

type Deferred<T> = {
  promise: Promise<T>;
  resolve: (value: T) => void;
};

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

const createUser = (id: string, name: string): AdminUserSummary => ({
  id,
  name,
  status: 'ACTIVE',
  createdAt: '2026-08-01T09:00:00Z',
  lastLoginAt: null,
  generationUsage: usage,
});

const createDetail = (user: AdminUserSummary): AdminUserDetail => ({
  ...user,
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
  mockSearchParams = new URLSearchParams(initialEntry.split('?')[1] ?? '');
  return render(<AdminPage />);
};

beforeEach(() => {
  mockGetAdminUser.mockReset();
  mockSearchAdminGenerations.mockReset();
  mockSearchAdminUsers.mockReset();
  mockResetAdminUsage.mockReset();
  mockRestoreAdminUsage.mockReset();
  mockSetAdminGenerationLimit.mockReset();
  mockSetSearchParams.mockReset();
});

describe('관리자 페이지 비동기 요청 순서', () => {
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

    mockSearchAdminUsers.mockImplementation((query = '') =>
      query === '' ? dashboardUsers.promise : searchedUsers.promise,
    );
    mockSearchAdminGenerations.mockResolvedValue(
      createPage<GenerationHistory>([]),
    );

    const user = userEvent.setup();
    renderAdmin('/admin');

    const searchInput = screen.getByPlaceholderText('이름 또는 사용자 ID 검색');
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
});
