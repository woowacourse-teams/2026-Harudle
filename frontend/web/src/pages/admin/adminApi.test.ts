import { afterEach, describe, expect, it, jest } from '@jest/globals';
import {
  getAdminUser,
  resetAdminUsage,
  restoreAdminUsage,
  searchAdminGenerations,
  searchAdminUsers,
  setAdminGenerationLimit,
} from './adminApi';

const mockAuthFetch = jest.fn<(...args: unknown[]) => Promise<Response>>();

jest.mock('../../shared/auth', () => ({
  authFetch: (...args: unknown[]) => mockAuthFetch(...args),
}));

const createJsonResponse = (data: unknown, status = 200): Response =>
  ({
    ok: status >= 200 && status < 300,
    status,
    json: async () => data,
  }) as Response;

const usage = {
  usageDate: '2026-08-27',
  usedCount: 2,
  limitCount: 3,
  remainingCount: 1,
};

const generation = {
  id: '7e5cc251-fdde-4cc0-a54e-2c8142750609',
  requestedAt: '2026-08-27T09:00:00Z',
  status: 'FAILED',
  completedAt: '2026-08-27T09:00:10Z',
  errorCode: 'AI_PROVIDER_TIMEOUT',
} as const;

const userSummary = {
  id: '3b85d827-72da-4e23-98ef-a97119e441b8',
  name: '하루들',
  status: 'ACTIVE',
  createdAt: '2026-08-01T09:00:00Z',
  lastLoginAt: null,
  generationUsage: usage,
} as const;

afterEach(() => {
  mockAuthFetch.mockReset();
});

describe('관리자 API', () => {
  it('백엔드 사용자 목록 응답의 중첩 사용량을 읽는다', async () => {
    const page = {
      content: [userSummary],
      page: 1,
      size: 10,
      totalElements: 11,
      totalPages: 2,
      hasNext: false,
    };
    mockAuthFetch.mockResolvedValueOnce(createJsonResponse(page));

    await expect(searchAdminUsers('하루', 1, 10)).resolves.toEqual(page);
    expect(mockAuthFetch).toHaveBeenCalledWith(
      '/api/v1/admin/users?query=%ED%95%98%EB%A3%A8&page=1&size=10',
    );
  });

  it('사용자 상세 조회에서 최근 생성 이력의 errorCode를 읽는다', async () => {
    const detail = {
      ...userSummary,
      recentGenerations: [
        {
          id: 'd9ad3f24-a4b4-4b55-86f3-cfcb9d788c4f',
          requestedAt: '2026-08-27T08:00:00Z',
          status: 'PROCESSING',
        },
        generation,
      ],
    };
    mockAuthFetch.mockResolvedValueOnce(createJsonResponse(detail));

    await expect(getAdminUser(userSummary.id)).resolves.toEqual(detail);
    expect(mockAuthFetch).toHaveBeenCalledWith(
      `/api/v1/admin/users/${userSummary.id}`,
    );
  });

  it('생성 이력 검색에 백엔드가 지원하는 필터를 전달한다', async () => {
    const history = {
      ...generation,
      user: { id: userSummary.id, name: userSummary.name },
    };
    const page = {
      content: [history],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      hasNext: false,
    };
    mockAuthFetch.mockResolvedValueOnce(createJsonResponse(page));

    await expect(
      searchAdminGenerations({
        page: 0,
        size: 20,
        userId: userSummary.id,
        status: 'FAILED',
        from: '2026-08-27',
        to: '2026-08-27',
      }),
    ).resolves.toEqual(page);
    expect(mockAuthFetch).toHaveBeenCalledWith(
      `/api/v1/admin/generations?page=0&size=20&userId=${userSummary.id}&status=FAILED&from=2026-08-27&to=2026-08-27`,
    );
  });

  it('사용량 복구는 PATCH와 count 본문을 사용한다', async () => {
    mockAuthFetch.mockResolvedValueOnce(createJsonResponse(usage));

    await expect(restoreAdminUsage(userSummary.id, 1)).resolves.toEqual(usage);
    expect(mockAuthFetch).toHaveBeenCalledWith(
      `/api/v1/admin/users/${userSummary.id}/generation-usage/restore`,
      {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ count: 1 }),
      },
    );
  });

  it('사용량 초기화는 PUT을 사용하고 사용량 응답을 읽는다', async () => {
    mockAuthFetch.mockResolvedValueOnce(createJsonResponse(usage));

    await expect(resetAdminUsage(userSummary.id)).resolves.toEqual(usage);
    expect(mockAuthFetch).toHaveBeenCalledWith(
      `/api/v1/admin/users/${userSummary.id}/generation-usage/reset`,
      { method: 'PUT' },
    );
  });

  it('생성 한도 변경은 PUT과 limitCount 본문을 사용하고 204를 허용한다', async () => {
    mockAuthFetch.mockResolvedValueOnce(createJsonResponse(null, 204));

    await expect(
      setAdminGenerationLimit(userSummary.id, 5),
    ).resolves.toBeUndefined();
    expect(mockAuthFetch).toHaveBeenCalledWith(
      `/api/v1/admin/users/${userSummary.id}/generation-limit`,
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ limitCount: 5 }),
      },
    );
  });
});
