import { delay, http, HttpResponse } from 'msw';
import diaryImage from '../assets/images/diary-four-panel.webp';
import { isRecord } from '../shared/utils';
import {
  isModerationTargetType,
  type AdminReportDetail,
  type AdminReport,
  type AdminPageResponse,
  type ModerationHistory,
  type ModerationState,
  type ModerationTargetType,
} from '../pages/admin/adminModerationApi';
import { MOCK_SCENARIO_HEADER } from './mockScenarios';
const actor = '08d69a34-6d70-4d42-a158-671bc67733c9';
const targetUser = '00000000-0000-4000-8000-000000000100';
const now = '2026-10-23T03:00:00Z';
const reports: AdminReport[] = [
  {
    id: '40000000-0000-4000-8000-000000000001',
    reporterId: actor,
    targetType: 'FEED',
    targetId: '00000000-0000-4000-8000-000000000002',
    targetUserId: targetUser,
    reason: 'SPAM',
    description: '반복되는 광고 내용을 확인해 주세요.',
    status: 'RECEIVED',
    createdAt: now,
    reviewedAt: null,
    reviewedBy: null,
    reviewReason: null,
  },
  {
    id: '40000000-0000-4000-8000-000000000002',
    reporterId: actor,
    targetType: 'COMMENT',
    targetId: '10000000-0000-4000-8000-000000000002',
    targetUserId: targetUser,
    reason: 'HARASSMENT',
    description: '욕설이 포함되어 있어요.',
    status: 'RECEIVED',
    createdAt: now,
    reviewedAt: null,
    reviewedBy: null,
    reviewReason: null,
  },
  {
    id: '40000000-0000-4000-8000-000000000003',
    reporterId: actor,
    targetType: 'USER',
    targetId: targetUser,
    targetUserId: targetUser,
    reason: 'OTHER',
    description: '',
    status: 'DISMISSED',
    createdAt: now,
    reviewedAt: now,
    reviewedBy: actor,
    reviewReason: '정책 위반에 해당하지 않음',
  },
];
const states = new Map<string, ModerationState>();
const histories = new Map<string, ModerationHistory[]>();
const keyOf = (type: string, id: string): string => `${type}:${id}`;
const stateOf = (
  targetType: ModerationTargetType,
  targetId: string,
): ModerationState =>
  states.get(keyOf(targetType, targetId)) ?? {
    targetType,
    targetId,
    restricted: false,
    version: 0,
    reason: null,
    actorId: null,
    changedAt: null,
  };
const problem = (request: Request, status: number, code: string): Response =>
  HttpResponse.json(
    {
      type: `urn:harudle:problem:${code}`,
      title: code,
      status,
      detail:
        status === 409
          ? '다른 관리자가 변경했어요.'
          : '관리자 요청을 처리하지 못했어요.',
      code,
      instance: new URL(request.url).pathname,
    },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
const validate = (request: Request, mutation = false): Response | null => {
  if (request.headers.get('Authorization') !== 'Bearer mock-access-token')
    return problem(request, 401, 'UNAUTHORIZED');
  const scenario = request.headers.get(MOCK_SCENARIO_HEADER);
  if (scenario === 'admin-forbidden') return problem(request, 403, 'FORBIDDEN');
  if (mutation && request.headers.get('X-XSRF-TOKEN') !== 'mock-csrf-token')
    return problem(request, 403, 'INVALID_CSRF_TOKEN');
  if (mutation && scenario === 'admin-mutation-failure')
    return problem(request, 503, 'ADMIN_UNAVAILABLE');
  if (mutation && scenario === 'admin-conflict')
    return problem(request, 409, 'MODERATION_CONFLICT');
  if (!mutation && scenario === 'admin-read-failure')
    return problem(request, 503, 'ADMIN_UNAVAILABLE');
  return null;
};
const paged = <T>(
  items: readonly T[],
  request: Request,
): AdminPageResponse<T> => {
  const params = new URL(request.url).searchParams;
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0);
  const size = Math.min(
    50,
    Math.max(1, Number(params.get('size') ?? 20) || 20),
  );
  return {
    content: items.slice(page * size, (page + 1) * size),
    page,
    size,
    totalElements: items.length,
    totalPages: Math.ceil(items.length / size),
    hasNext: (page + 1) * size < items.length,
  };
};
const usage = {
  usageDate: '2026-10-23',
  usedCount: 1,
  limitCount: 3,
  remainingCount: 2,
};
const userSummary = {
  id: targetUser,
  name: '네오',
  status: 'ACTIVE',
  createdAt: now,
  lastLoginAt: now,
  generationUsage: usage,
};
export const adminModerationHandlers = [
  http.get('/api/v1/admin/reports', async ({ request }): Promise<Response> => {
    await delay(150);
    const error = validate(request);
    if (error) return error;
    const status = new URL(request.url).searchParams.get('status');
    const content = reports
      .filter((report): boolean => status === null || report.status === status)
      .map((report) => ({
        id: report.id,
        reporterId: report.reporterId,
        targetType: report.targetType,
        targetId: report.targetId,
        targetUserId: report.targetUserId,
        reason: report.reason,
        status: report.status,
        createdAt: report.createdAt,
        reviewedAt: report.reviewedAt,
      }));
    return HttpResponse.json(paged(content, request));
  }),
  http.get(
    '/api/v1/admin/reports/:reportId',
    async ({ request, params }): Promise<Response> => {
      await delay(150);
      const error = validate(request);
      if (error) return error;
      const report = reports.find(
        (value): boolean => value.id === params.reportId,
      );
      if (!report) return problem(request, 404, 'REPORT_NOT_FOUND');
      const hidden =
        stateOf(report.targetType, report.targetId).restricted &&
        report.targetType !== 'USER';
      const target: AdminReportDetail['target'] = {
        exists: true,
        authorId: targetUser,
        authorNickname: '네오',
        authorDeleted: false,
        deleted: false,
        parentDeleted: false,
        hidden,
        parentHidden: false,
        publiclyVisible: !hidden,
        feedId:
          report.targetType === 'USER'
            ? null
            : '00000000-0000-4000-8000-000000000002',
        title: report.targetType === 'FEED' ? '오늘의 기록' : null,
        imageUrl: report.targetType === 'FEED' ? diaryImage : null,
        imageUrlExpiresAt:
          report.targetType === 'FEED'
            ? new Date(Date.now() + 900_000).toISOString()
            : null,
        commentContent:
          report.targetType === 'COMMENT' ? '신고된 댓글 내용입니다.' : null,
      };
      return HttpResponse.json({ report, target });
    },
  ),
  http.put(
    '/api/v1/admin/reports/:reportId/review',
    async ({ request, params }): Promise<Response> => {
      await delay(250);
      const error = validate(request, true);
      if (error) return error;
      const index = reports.findIndex(
        (report): boolean => report.id === params.reportId,
      );
      if (index < 0) return problem(request, 404, 'REPORT_NOT_FOUND');
      const body: unknown = await request.json();
      if (
        !isRecord(body) ||
        (body.status !== 'DISMISSED' && body.status !== 'RESOLVED') ||
        typeof body.reason !== 'string' ||
        !body.reason.trim() ||
        Array.from(body.reason.trim()).length > 500
      )
        return problem(request, 400, 'VALIDATION_ERROR');
      const report = reports[index];
      if (
        report.status !== 'RECEIVED' &&
        (report.status !== body.status ||
          report.reviewReason !== body.reason.trim())
      )
        return problem(request, 409, 'REPORT_ALREADY_REVIEWED');
      if (report.status === 'RECEIVED')
        reports[index] = {
          ...report,
          status: body.status,
          reviewedAt: new Date().toISOString(),
          reviewedBy: actor,
          reviewReason: body.reason.trim(),
        };
      const reviewed = reports[index];
      return HttpResponse.json({
        id: reviewed.id,
        status: reviewed.status,
        reviewedAt: reviewed.reviewedAt,
        reviewedBy: reviewed.reviewedBy,
        reviewReason: reviewed.reviewReason,
      });
    },
  ),
  http.get(
    '/api/v1/admin/moderation/:type/:id/history',
    async ({ request, params }): Promise<Response> => {
      await delay(150);
      const error = validate(request);
      if (error) return error;
      return HttpResponse.json(
        paged(
          histories.get(keyOf(String(params.type), String(params.id))) ?? [],
          request,
        ),
      );
    },
  ),
  http.get(
    '/api/v1/admin/moderation/:type/:id',
    async ({ request, params }): Promise<Response> => {
      await delay(150);
      const error = validate(request);
      if (error) return error;
      if (!isModerationTargetType(params.type))
        return problem(request, 400, 'VALIDATION_ERROR');
      return HttpResponse.json(stateOf(params.type, String(params.id)));
    },
  ),
  http.put(
    '/api/v1/admin/moderation/:type/:id',
    async ({ request, params }): Promise<Response> => {
      await delay(250);
      const error = validate(request, true);
      if (error) return error;
      if (!isModerationTargetType(params.type))
        return problem(request, 400, 'VALIDATION_ERROR');
      if (params.type === 'USER' && params.id === actor)
        return problem(request, 400, 'INVALID_MODERATION_TARGET');
      const body: unknown = await request.json();
      if (
        !isRecord(body) ||
        typeof body.restricted !== 'boolean' ||
        typeof body.expectedVersion !== 'number' ||
        typeof body.reason !== 'string' ||
        !body.reason.trim() ||
        Array.from(body.reason.trim()).length > 500
      )
        return problem(request, 400, 'VALIDATION_ERROR');
      const current = stateOf(params.type, String(params.id));
      if (
        current.version === body.expectedVersion + 1 &&
        current.restricted === body.restricted &&
        current.actorId === actor &&
        current.reason === body.reason.trim()
      )
        return HttpResponse.json(current);
      if (current.version !== body.expectedVersion)
        return problem(request, 409, 'MODERATION_CONFLICT');
      if (current.restricted === body.restricted)
        return HttpResponse.json(current);
      const state: ModerationState = {
        ...current,
        restricted: body.restricted,
        version: current.version + 1,
        reason: body.reason.trim(),
        actorId: actor,
        changedAt: new Date().toISOString(),
      };
      const key = keyOf(params.type, String(params.id));
      states.set(key, state);
      const history: ModerationHistory = {
        ...state,
        id: crypto.randomUUID(),
        reason: body.reason.trim(),
        actorId: actor,
        changedAt: new Date().toISOString(),
      };
      histories.set(key, [history, ...(histories.get(key) ?? [])]);
      return HttpResponse.json(state);
    },
  ),
  http.get('/api/v1/admin/users', async ({ request }): Promise<Response> => {
    const error = validate(request);
    if (error) return error;
    const query = new URL(request.url).searchParams.get('query') ?? '';
    return HttpResponse.json(
      paged(
        !query || '네오'.includes(query) || targetUser.includes(query)
          ? [userSummary]
          : [],
        request,
      ),
    );
  }),
  http.get(
    '/api/v1/admin/users/:userId',
    async ({ request, params }): Promise<Response> => {
      const error = validate(request);
      if (error) return error;
      return params.userId === targetUser
        ? HttpResponse.json({ ...userSummary, recentGenerations: [] })
        : problem(request, 404, 'USER_NOT_FOUND');
    },
  ),
  http.get(
    '/api/v1/admin/generations',
    async ({ request }): Promise<Response> => {
      const error = validate(request);
      if (error) return error;
      return HttpResponse.json(paged([], request));
    },
  ),
  http.put(
    '/api/v1/admin/users/:userId/generation-usage/reset',
    async ({ request }): Promise<Response> => {
      const error = validate(request, true);
      if (error) return error;
      usage.usedCount = 0;
      usage.remainingCount = usage.limitCount;
      return HttpResponse.json(usage);
    },
  ),
  http.patch(
    '/api/v1/admin/users/:userId/generation-usage/restore',
    async ({ request }): Promise<Response> => {
      const error = validate(request, true);
      if (error) return error;
      const body: unknown = await request.json();
      if (
        !isRecord(body) ||
        typeof body.count !== 'number' ||
        body.count < 1 ||
        body.count > usage.usedCount
      )
        return problem(request, 400, 'VALIDATION_ERROR');
      usage.usedCount -= body.count;
      usage.remainingCount = usage.limitCount - usage.usedCount;
      return HttpResponse.json(usage);
    },
  ),
  http.put(
    '/api/v1/admin/users/:userId/generation-limit',
    async ({ request }): Promise<Response> => {
      const error = validate(request, true);
      if (error) return error;
      const body: unknown = await request.json();
      if (
        !isRecord(body) ||
        typeof body.limitCount !== 'number' ||
        !Number.isInteger(body.limitCount) ||
        body.limitCount < 1
      )
        return problem(request, 400, 'VALIDATION_ERROR');
      usage.limitCount = body.limitCount;
      usage.remainingCount = Math.max(0, usage.limitCount - usage.usedCount);
      return new HttpResponse(null, { status: 204 });
    },
  ),
];
