import { delay, http, HttpResponse } from 'msw';
import type { Notification } from '../domain/notifications/notifications';
import profileImage from '../assets/images/profile-fallback.svg';
import { isRecord } from '../shared/utils';
import { MOCK_SCENARIO_HEADER, MOCK_SCENARIOS } from './mockScenarios';

const createNotifications = (): Notification[] =>
  Array.from({ length: 24 }, (_, index): Notification => ({
    id: `20000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`,
    type: index % 2 === 0 ? 'COMMENT' : 'LIKE',
    feedId: '00000000-0000-4000-8000-000000000001',
    actor: {
      id: '00000000-0000-4000-8000-000000000100',
      nickname: index % 2 === 0 ? '네오' : '모모',
      profileImageUrl: profileImage,
      profileImageUrlExpiresAt: null,
    },
    message:
      index % 2 === 0
        ? '네오님이 댓글을 남겼습니다.'
        : '모모님이 좋아요를 눌렀습니다.',
    createdAt: new Date(Date.now() - (5 + index * 7) * 60_000).toISOString(),
    read: false,
  }));

let notifications = createNotifications();

export const resetNotificationMockState = (): void => {
  notifications = createNotifications();
};

const failure = (request: Request, status: number, code: string): Response =>
  HttpResponse.json(
    {
      type: `urn:harudle:problem:${code}`,
      title: code,
      status,
      code,
      detail: '알림 요청을 처리하지 못했어요.',
      instance: new URL(request.url).pathname,
    },
    { status },
  );

const validate = (request: Request, mutation = false): Response | null => {
  if (request.headers.get('Authorization') !== 'Bearer mock-access-token')
    return failure(request, 401, 'UNAUTHORIZED');
  if (mutation && request.headers.get('X-XSRF-TOKEN') !== 'mock-csrf-token')
    return failure(request, 403, 'INVALID_CSRF_TOKEN');
  return null;
};

export const notificationHandlers = [
  http.get('/api/v1/notifications', async ({ request }): Promise<Response> => {
    await delay(150);
    const error = validate(request);
    if (error) return error;
    const scenario = request.headers.get(MOCK_SCENARIO_HEADER);
    if (scenario === MOCK_SCENARIOS.notificationsListFailure)
      return failure(request, 503, 'NOTIFICATION_UNAVAILABLE');
    const items =
      scenario === MOCK_SCENARIOS.notificationsEmpty
        ? []
        : notifications.slice(
            0,
            scenario === MOCK_SCENARIOS.notificationsMany ? 24 : 6,
          );
    const url = new URL(request.url);
    const size = Number(url.searchParams.get('size') ?? '20');
    const cursor = url.searchParams.get('cursor');
    const start =
      cursor === null
        ? 0
        : items.findIndex((item): boolean => item.id === cursor) + 1;
    if (
      !Number.isInteger(size) ||
      size < 1 ||
      size > 50 ||
      (cursor !== null && start === 0)
    )
      return failure(request, 400, 'INVALID_CURSOR');
    const page = items.slice(start, start + size);
    const hasNext = start + size < items.length;
    return HttpResponse.json({
      items: page,
      hasNext,
      nextCursor: hasNext ? page.at(-1)?.id : null,
      unreadCount: items.filter((item): boolean => !item.read).length,
    });
  }),
  http.patch(
    '/api/v1/notifications/:notificationId',
    async ({ request, params }): Promise<Response> => {
      await delay(100);
      const error = validate(request, true);
      if (error) return error;
      const item = notifications.find(
        (notification): boolean => notification.id === params.notificationId,
      );
      if (!item) return failure(request, 404, 'NOTIFICATION_NOT_FOUND');
      const body: unknown = await request.json().catch((): null => null);
      if (!isRecord(body) || body.read !== true)
        return failure(request, 400, 'VALIDATION_ERROR');
      if (
        request.headers.get(MOCK_SCENARIO_HEADER) ===
          MOCK_SCENARIOS.notificationsReadFailure &&
        item.type === 'LIKE'
      )
        return failure(request, 503, 'NOTIFICATION_UNAVAILABLE');
      notifications = notifications.map((notification): Notification =>
        notification.id === item.id
          ? { ...notification, read: true }
          : notification,
      );
      return new HttpResponse(null, { status: 204 });
    },
  ),
];
