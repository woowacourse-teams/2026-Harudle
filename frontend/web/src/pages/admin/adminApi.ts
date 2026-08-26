import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { authFetch } from '../../shared/auth';

export type UserStatus = 'ACTIVE' | 'DELETED';
export type GenerationStatus = 'PROCESSING' | 'SUCCEEDED' | 'FAILED';

export interface AdminUserSummary {
  id: string;
  name: string;
  email: string | null;
  status: UserStatus;
  createdAt: string;
  lastLoginAt: string | null;
  remainingGenerationCount: number;
}

export interface Generation {
  id: string;
  requestedAt: string;
  status: GenerationStatus;
  completedAt: string | null;
  failureCode: string | null;
}

export interface AdminUserDetail extends AdminUserSummary {
  usageDate: string;
  usedGenerationCount: number;
  dailyGenerationLimit: number;
  recentGenerations: Generation[];
}

export interface GenerationHistory extends Generation {
  user: {
    id: string;
    name: string;
    email: string | null;
  };
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null;

const isStringOrNull = (value: unknown): value is string | null =>
  value === null || typeof value === 'string';

const isUserStatus = (value: unknown): value is UserStatus =>
  value === 'ACTIVE' || value === 'DELETED';

const isGenerationStatus = (value: unknown): value is GenerationStatus =>
  value === 'PROCESSING' || value === 'SUCCEEDED' || value === 'FAILED';

const isUserSummary = (value: unknown): value is AdminUserSummary =>
  isRecord(value) &&
  typeof value.id === 'string' &&
  typeof value.name === 'string' &&
  isStringOrNull(value.email) &&
  isUserStatus(value.status) &&
  typeof value.createdAt === 'string' &&
  isStringOrNull(value.lastLoginAt) &&
  typeof value.remainingGenerationCount === 'number';

const isGeneration = (value: unknown): value is Generation =>
  isRecord(value) &&
  typeof value.id === 'string' &&
  typeof value.requestedAt === 'string' &&
  isGenerationStatus(value.status) &&
  isStringOrNull(value.completedAt) &&
  isStringOrNull(value.failureCode);

const isUserDetail = (value: unknown): value is AdminUserDetail =>
  isUserSummary(value) &&
  isRecord(value) &&
  typeof value.usageDate === 'string' &&
  typeof value.usedGenerationCount === 'number' &&
  typeof value.dailyGenerationLimit === 'number' &&
  Array.isArray(value.recentGenerations) &&
  value.recentGenerations.every(isGeneration);

const isGenerationHistory = (value: unknown): value is GenerationHistory =>
  isGeneration(value) &&
  isRecord(value) &&
  isRecord(value.user) &&
  typeof value.user.id === 'string' &&
  typeof value.user.name === 'string' &&
  isStringOrNull(value.user.email);

const readPage = <T>(
  value: unknown,
  isItem: (item: unknown) => item is T,
): Page<T> => {
  if (
    !isRecord(value) ||
    !Array.isArray(value.content) ||
    !value.content.every(isItem) ||
    typeof value.page !== 'number' ||
    typeof value.size !== 'number' ||
    typeof value.totalElements !== 'number' ||
    typeof value.totalPages !== 'number' ||
    typeof value.hasNext !== 'boolean'
  ) {
    throw new Error('관리자 API 응답 형식이 올바르지 않습니다.');
  }
  return value as unknown as Page<T>;
};

const readJson = async (response: Response): Promise<unknown> => {
  const data: unknown = await response.json();
  if (response.ok) return data;
  if (isProblemDetails(data)) throw new RequestError(data);
  throw new Error('관리자 API 요청에 실패했습니다.');
};

export const searchAdminUsers = async (
  query = '',
  page = 0,
  size = 20,
): Promise<Page<AdminUserSummary>> => {
  const params = new URLSearchParams({
    query,
    page: String(page),
    size: String(size),
  });
  const response = await authFetch(`${API_BASE_URL}/admin/users?${params}`);
  return readPage(await readJson(response), isUserSummary);
};

export const getAdminUser = async (
  userId: string,
): Promise<AdminUserDetail> => {
  const response = await authFetch(`${API_BASE_URL}/admin/users/${userId}`);
  const value = await readJson(response);
  if (!isUserDetail(value)) {
    throw new Error('관리자 사용자 상세 응답 형식이 올바르지 않습니다.');
  }
  return value;
};

export interface GenerationSearchParams {
  page?: number;
  size?: number;
  userId?: string;
  status?: GenerationStatus;
  from?: string;
  to?: string;
}

export const searchAdminGenerations = async (
  options: GenerationSearchParams = {},
): Promise<Page<GenerationHistory>> => {
  const params = new URLSearchParams({
    page: String(options.page ?? 0),
    size: String(options.size ?? 20),
  });
  if (options.userId) params.set('userId', options.userId);
  if (options.status) params.set('status', options.status);
  if (options.from) params.set('from', options.from);
  if (options.to) params.set('to', options.to);

  const response = await authFetch(
    `${API_BASE_URL}/admin/generations?${params}`,
  );
  return readPage(await readJson(response), isGenerationHistory);
};

const updateUsage = async (
  url: string,
  init: RequestInit,
): Promise<AdminUserDetail> => {
  const response = await authFetch(url, init);
  const value = await readJson(response);
  if (!isUserDetail(value)) {
    throw new Error('사용량 변경 응답 형식이 올바르지 않습니다.');
  }
  return value;
};

export const restoreAdminUsage = (userId: string, count: number) =>
  updateUsage(
    `${API_BASE_URL}/admin/users/${userId}/generation-usage/restore`,
    {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ count }),
    },
  );

export const resetAdminUsage = (userId: string) =>
  updateUsage(`${API_BASE_URL}/admin/users/${userId}/generation-usage/reset`, {
    method: 'PUT',
  });

export const setAdminUsage = (userId: string, usedCount: number) =>
  updateUsage(`${API_BASE_URL}/admin/users/${userId}/usage`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ usedCount }),
  });

export const setAdminGenerationLimit = async (
  userId: string,
  limitCount: number,
): Promise<void> => {
  const response = await authFetch(
    `${API_BASE_URL}/admin/users/${userId}/generation-limit`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ limitCount }),
    },
  );
  if (response.status === 204) {
    return;
  }
  await readJson(response);
};
