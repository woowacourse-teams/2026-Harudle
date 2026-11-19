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
  {
    id: '40000000-0000-4000-8000-000000000004',
    reporterId: targetUser,
    targetType: 'DIARY',
    targetId: '30000000-0000-4000-8000-000000000004',
    targetUserId: targetUser,
    reason: 'INAPPROPRIATE_CONTENT',
    description: '그림에 이상한 글자가 들어갔어요.',
    status: 'RECEIVED',
    createdAt: now,
    reviewedAt: null,
    reviewedBy: null,
    reviewReason: null,
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
const HOUR = 3_600_000;
const DAY = 24 * HOUR;
// 백엔드는 오늘 가입 수와 오늘 사용량을 한국 시간 날짜로 센다.
const kstTodayStart = (): number =>
  Math.floor((Date.now() + 9 * HOUR) / DAY) * DAY - 9 * HOUR;
const NICKNAME_WORDS = [
  'SunnyHaru',
  'BlueWhale',
  'MintCloud',
  'NightOwl',
  'PeachTea',
  'RainyDay',
  'StarFish',
  'MoonWalk',
];
// 여러 쪽으로 나뉘는 사용자 목록. 첫 사용자는 신고 대상과 같아 신고 화면 흐름에서도 쓴다.
// 앞의 세 명은 오늘 가입했고, 배열 순서가 가입일 내림차순과 같다.
const users = Array.from({ length: 45 }, (_, index) => {
  const todayStart = kstTodayStart();
  const limitCount = index % 6 === 5 ? 5 : 3;
  const usedCount = Math.min((index + 1) % 4, limitCount);
  return {
    id:
      index === 0
        ? targetUser
        : `00000000-0000-4000-8000-${String(1000 + index).padStart(12, '0')}`,
    name: index === 0 ? '네오' : `사용자 ${String(index + 1).padStart(2, '0')}`,
    nickname:
      index % 5 === 3
        ? null
        : `${NICKNAME_WORDS[index % NICKNAME_WORDS.length]}${String(index + 1).padStart(2, '0')}`,
    status: index % 7 === 6 ? 'DELETED' : 'ACTIVE',
    createdAt: new Date(
      index < 3
        ? todayStart + (3 - index) * 60_000
        : todayStart - (index - 2) * DAY + 10 * HOUR,
    ).toISOString(),
    lastLoginAt: now,
    generationUsage: {
      usageDate: new Date(todayStart + 9 * HOUR).toISOString().slice(0, 10),
      usedCount,
      limitCount,
      remainingCount: limitCount - usedCount,
    },
    generatedDiaryCount: (index * 7) % 23,
  };
});
type MockUser = (typeof users)[number];
const USER_SORT_KEYS = {
  NAME: (user: MockUser): string => user.name,
  CREATED_AT: (user: MockUser): string => user.createdAt,
  REMAINING_COUNT: (user: MockUser): number =>
    user.generationUsage.remainingCount,
} as const;
const isUserSortKey = (value: string): value is keyof typeof USER_SORT_KEYS =>
  Object.hasOwn(USER_SORT_KEYS, value);
// 백엔드처럼 정렬 값이 같으면 사용자 ID를 같은 방향으로 정렬한다.
const sortUsers = (
  list: readonly MockUser[],
  sortKey: keyof typeof USER_SORT_KEYS,
  direction: 'ASC' | 'DESC',
): MockUser[] => {
  const keyOfUser = USER_SORT_KEYS[sortKey];
  const sign = direction === 'ASC' ? 1 : -1;
  const compare = (a: string | number, b: string | number): number =>
    a < b ? -1 : a > b ? 1 : 0;
  return [...list].sort(
    (a, b) =>
      sign * (compare(keyOfUser(a), keyOfUser(b)) || compare(a.id, b.id)),
  );
};
// 관리자 전체 생성 목록의 앞쪽 예시. 실제 서버 없이 엽서 미리보기 흐름을 확인한다.
const ADMIN_GENERATION_SAMPLES = [
  {
    id: '6a2fbcda-943d-4014-a8e3-246377482e00',
    user: { id: '00000000-0000-4000-8000-000000000201', name: '레오' },
    requestedAt: '2026-11-17T11:52:10Z',
    status: 'SUCCEEDED',
    completedAt: '2026-11-17T11:52:41Z',
    errorCode: null,
  },
  {
    id: '71dc2282-72c5-4c45-8f81-018afbc67ad8',
    user: { id: '00000000-0000-4000-8000-000000000202', name: '쿠다' },
    requestedAt: '2026-11-17T11:49:33Z',
    status: 'FAILED',
    completedAt: '2026-11-17T11:50:20Z',
    errorCode: 'IMAGE_GENERATION_FAILED',
  },
  {
    id: '339fa1fd-8ea7-4830-93b7-e16672e35086',
    user: { id: '00000000-0000-4000-8000-000000000203', name: '이산' },
    requestedAt: '2026-11-17T11:45:02Z',
    status: 'SUCCEEDED',
    completedAt: '2026-11-17T11:45:35Z',
    errorCode: null,
  },
] as const;
// 실패 이력도 여러 쪽이 되도록 엽서 예시 뒤에 생성 결과를 덧붙인다.
// 엽서 이미지 조회도 이 목록에서 성공한 생성을 찾는다.
export const adminGenerations = [
  ...ADMIN_GENERATION_SAMPLES,
  ...Array.from({ length: 30 }, (_, index) => ({
    id: `50000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`,
    user: { id: users[index + 1].id, name: users[index + 1].name },
    requestedAt: new Date(
      Date.parse('2026-11-17T11:40:00Z') - index * 600_000,
    ).toISOString(),
    status: index % 3 === 0 ? 'FAILED' : 'SUCCEEDED',
    completedAt: null,
    errorCode: index % 3 === 0 ? 'IMAGE_GENERATION_FAILED' : null,
  })),
];
const usageOf = (userId: unknown) =>
  users.find((user) => user.id === userId)?.generationUsage;
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
        (report.targetType === 'FEED' || report.targetType === 'COMMENT') &&
        stateOf(report.targetType, report.targetId).restricted;
      const hasImage =
        report.targetType === 'FEED' || report.targetType === 'DIARY';
      const target: AdminReportDetail['target'] = {
        exists: true,
        authorId: targetUser,
        authorNickname: '네오',
        authorDeleted: false,
        deleted: false,
        parentDeleted: false,
        hidden,
        parentHidden: false,
        publiclyVisible: report.targetType !== 'DIARY' && !hidden,
        feedId:
          report.targetType === 'FEED' || report.targetType === 'COMMENT'
            ? '00000000-0000-4000-8000-000000000002'
            : null,
        title: hasImage ? '오늘의 기록' : null,
        imageUrl: hasImage ? diaryImage : null,
        imageUrlExpiresAt: hasImage
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
  http.get('/api/v1/admin/stats', async ({ request }): Promise<Response> => {
    const error = validate(request);
    if (error) return error;
    const members = users.filter((user) => user.status !== 'DELETED');
    const todayStart = kstTodayStart();
    return HttpResponse.json({
      memberCount: members.length,
      todaySignupCount: members.filter(
        (user) => Date.parse(user.createdAt) >= todayStart,
      ).length,
    });
  }),
  http.get('/api/v1/admin/users', async ({ request }): Promise<Response> => {
    const error = validate(request);
    if (error) return error;
    const params = new URL(request.url).searchParams;
    const query = (params.get('query') ?? '').trim().toLowerCase();
    const sort = params.get('sort') ?? 'CREATED_AT';
    const direction = params.get('direction') ?? 'DESC';
    if (!isUserSortKey(sort) || (direction !== 'ASC' && direction !== 'DESC'))
      return problem(request, 400, 'VALIDATION_ERROR');
    const matched = users.filter(
      (user) =>
        query === '' ||
        user.name.toLowerCase().includes(query) ||
        user.nickname?.toLowerCase().includes(query) ||
        user.id === query,
    );
    return HttpResponse.json(
      paged(sortUsers(matched, sort, direction), request),
    );
  }),
  http.get(
    '/api/v1/admin/users/:userId',
    async ({ request, params }): Promise<Response> => {
      const error = validate(request);
      if (error) return error;
      const user = users.find(({ id }) => id === params.userId);
      if (!user) return problem(request, 404, 'USER_NOT_FOUND');
      // 상세 응답에는 생성한 일기 수가 없다.
      const {
        id,
        name,
        nickname,
        status,
        createdAt,
        lastLoginAt,
        generationUsage,
      } = user;
      return HttpResponse.json({
        id,
        name,
        nickname,
        status,
        createdAt,
        lastLoginAt,
        generationUsage,
        recentGenerations: [],
      });
    },
  ),
  http.get(
    '/api/v1/admin/generations',
    async ({ request }): Promise<Response> => {
      const error = validate(request);
      if (error) return error;
      const status = new URL(request.url).searchParams.get('status');
      return HttpResponse.json(
        paged(
          adminGenerations.filter(
            (generation) => !status || generation.status === status,
          ),
          request,
        ),
      );
    },
  ),
  http.put(
    '/api/v1/admin/users/:userId/generation-usage/reset',
    async ({ request, params }): Promise<Response> => {
      const error = validate(request, true);
      if (error) return error;
      const usage = usageOf(params.userId);
      if (!usage) return problem(request, 404, 'USER_NOT_FOUND');
      usage.usedCount = 0;
      usage.remainingCount = usage.limitCount;
      return HttpResponse.json(usage);
    },
  ),
  http.patch(
    '/api/v1/admin/users/:userId/generation-usage/restore',
    async ({ request, params }): Promise<Response> => {
      const error = validate(request, true);
      if (error) return error;
      const usage = usageOf(params.userId);
      if (!usage) return problem(request, 404, 'USER_NOT_FOUND');
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
    async ({ request, params }): Promise<Response> => {
      const error = validate(request, true);
      if (error) return error;
      const usage = usageOf(params.userId);
      if (!usage) return problem(request, 404, 'USER_NOT_FOUND');
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
