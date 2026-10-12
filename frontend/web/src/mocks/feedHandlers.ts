import { delay, http, HttpResponse } from 'msw';
import diaryImage from '../assets/images/diary-four-panel.webp';
import profileImage from '../assets/icons/article_person.svg';
import { isRecord } from '../shared/utils';
import { isCanonicalUuid } from '../pages/landing/guestTrialUuid';
import { MOCK_SCENARIO_HEADER, MOCK_SCENARIOS } from './mockScenarios';

interface MockCategory {
  readonly id: string;
  readonly name: string;
  readonly sortOrder: number;
  readonly active: boolean;
}

interface MockFeed {
  readonly diaryId?: string;
  readonly id: string;
  readonly author: {
    readonly id: string;
    readonly nickname: string;
    readonly profileImageUrl: string;
  };
  readonly category: Pick<MockCategory, 'id' | 'name'>;
  readonly imageUrl: string;
  readonly publishedAt: string;
  likeCount: number;
  commentCount: number;
}

interface MockFeedResponse extends MockFeed {
  readonly imageUrlExpiresAt: string;
  readonly likedByMe: boolean;
  readonly isMine: boolean;
  readonly shareUrl: string;
}

type FeedSort = 'LATEST' | 'POPULAR';
type FeedProblemCode =
  | 'INTERNAL_SERVER_ERROR'
  | 'DIARY_ALREADY_PUBLISHED'
  | 'VALIDATION_ERROR'
  | 'INVALID_CURSOR'
  | 'UNAUTHORIZED'
  | 'FORBIDDEN'
  | 'CATEGORY_NOT_FOUND'
  | 'FEED_NOT_FOUND'
  | 'COMMENT_NOT_FOUND';

const MOCK_USER_ID = '08d69a34-6d70-4d42-a158-671bc67733c9';
const MOCK_ACCESS_TOKEN = 'mock-access-token';

// 카테고리 개별 명세의 auto increment ID를 문자열로 반환한다.
const categories: readonly MockCategory[] = [
  { id: '1', name: '일상', sortOrder: 0, active: true },
  { id: '2', name: '우테코', sortOrder: 1, active: true },
];

const createInitialFeeds = (): MockFeed[] => {
  const now = Date.now();

  return Array.from({ length: 24 }, (_, index): MockFeed => {
    const category = categories[index % categories.length];

    return {
      id: `00000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`,
      author: {
        id:
          index % 3 === 0
            ? MOCK_USER_ID
            : '00000000-0000-4000-8000-000000000100',
        nickname: index % 3 === 0 ? '하루들' : '네오',
        profileImageUrl: profileImage,
      },
      category: { id: category.id, name: category.name },
      imageUrl: diaryImage,
      publishedAt: new Date(
        now - Math.floor(index / 2) * 3_600_000,
      ).toISOString(),
      likeCount: (index % 5) * 3,
      commentCount: index === 0 ? 24 : index % 4,
    };
  });
};

interface MockComment {
  readonly id: string;
  readonly feedId: string;
  readonly author: MockFeed['author'];
  readonly content: string;
  readonly createdAt: string;
}

const createInitialComments = (
  initialFeeds: readonly MockFeed[],
): MockComment[] => {
  return initialFeeds.flatMap((feed, feedIndex) =>
    Array.from({ length: feed.commentCount }, (_, index): MockComment => ({
      id: `10000000-0000-4000-8000-${String(feedIndex * 100 + index + 1).padStart(12, '0')}`,
      feedId: feed.id,
      author: {
        id:
          index % 2 === 0
            ? MOCK_USER_ID
            : '00000000-0000-4000-8000-000000000100',
        nickname: index % 2 === 0 ? '하루들' : '네오',
        profileImageUrl: profileImage,
      },
      content:
        index % 2 === 0 ? '오늘도 수고했어요!' : '마지막 장면 너무 공감돼요!',
      createdAt: new Date(
        Date.now() - (feed.commentCount - index) * 60_000,
      ).toISOString(),
    })),
  );
};

let feeds = createInitialFeeds();
let comments = createInitialComments(feeds);
const likedFeedIds = new Set<string>();

export const getMockPublishedFeedId = (diaryId: string): string | null =>
  feeds.find((feed): boolean => feed.diaryId === diaryId)?.id ?? null;

export const hasMockFeed = (feedId: string): boolean =>
  feeds.some((feed): boolean => feed.id === feedId);

export const resetFeedMockState = (): void => {
  feeds = createInitialFeeds();
  comments = createInitialComments(feeds);
  likedFeedIds.clear();
};

const problem = (
  request: Request,
  status: number,
  code: FeedProblemCode,
): Response => {
  return HttpResponse.json(
    {
      type: `urn:harudle:problem:${code.toLowerCase().replaceAll('_', '-')}`,
      title: code,
      status,
      detail: code,
      instance: new URL(request.url).pathname,
      code,
      traceId: 'mock-feed-trace-id',
    },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
};

const isAuthenticated = (request: Request): boolean =>
  request.headers.get('Authorization') === `Bearer ${MOCK_ACCESS_TOKEN}`;

const validateAuthentication = (request: Request): Response | null => {
  if (request.headers.has('Authorization') && !isAuthenticated(request)) {
    const response = problem(request, 401, 'UNAUTHORIZED');
    response.headers.set('WWW-Authenticate', 'Bearer');
    return response;
  }

  return null;
};

const validateMutation = (request: Request): Response | null => {
  if (!isAuthenticated(request)) {
    return problem(request, 401, 'UNAUTHORIZED');
  }

  return null;
};

const compareFeeds = (
  a: Pick<MockFeed, 'id' | 'publishedAt' | 'likeCount'>,
  b: Pick<MockFeed, 'id' | 'publishedAt' | 'likeCount'>,
  sort: FeedSort,
): number => {
  if (sort === 'POPULAR' && a.likeCount !== b.likeCount) {
    return b.likeCount - a.likeCount;
  }

  return b.publishedAt.localeCompare(a.publishedAt) || b.id.localeCompare(a.id);
};

const createCursor = (
  feed: MockFeed,
  sort: FeedSort,
  categoryId: string | null,
): string => {
  // 마지막 항목의 정렬 키를 보관해 삭제돼도 다음 페이지를 조회할 수 있다.
  return JSON.stringify({
    id: feed.id,
    publishedAt: feed.publishedAt,
    likeCount: feed.likeCount,
    sort,
    categoryId,
  });
};

const readCursor = (
  cursor: string,
  sort: FeedSort,
  categoryId: string | null,
): Pick<MockFeed, 'id' | 'publishedAt' | 'likeCount'> | null => {
  try {
    const value: unknown = JSON.parse(cursor);

    if (
      typeof value !== 'object' ||
      value === null ||
      !('id' in value) ||
      typeof value.id !== 'string' ||
      !isCanonicalUuid(value.id) ||
      !('publishedAt' in value) ||
      typeof value.publishedAt !== 'string' ||
      !Number.isFinite(Date.parse(value.publishedAt)) ||
      !('likeCount' in value) ||
      typeof value.likeCount !== 'number' ||
      !Number.isInteger(value.likeCount) ||
      value.likeCount < 0 ||
      !('sort' in value) ||
      value.sort !== sort ||
      !('categoryId' in value) ||
      value.categoryId !== categoryId
    ) {
      return null;
    }

    return {
      id: value.id,
      publishedAt: value.publishedAt,
      likeCount: value.likeCount,
    };
  } catch {
    return null;
  }
};

const changeLike = (
  request: Request,
  feedId: string,
  likedByMe: boolean,
): Response => {
  const error = validateMutation(request);
  if (error) return error;
  if (!isCanonicalUuid(feedId))
    return problem(request, 400, 'VALIDATION_ERROR');

  const feed = feeds.find(({ id }): boolean => id === feedId);
  if (!feed) return problem(request, 404, 'FEED_NOT_FOUND');

  if (likedByMe && !likedFeedIds.has(feedId)) {
    likedFeedIds.add(feedId);
    feed.likeCount += 1;
  } else if (!likedByMe && likedFeedIds.has(feedId)) {
    likedFeedIds.delete(feedId);
    feed.likeCount -= 1;
  }

  return HttpResponse.json({ likedByMe, likeCount: feed.likeCount });
};

export const feedHandlers = [
  http.post('/api/v1/feeds', async ({ request }): Promise<Response> => {
    const error = validateMutation(request);
    if (error) return error;
    const body: unknown = await request.json().catch((): null => null);
    if (
      !isRecord(body) ||
      typeof body.diaryId !== 'string' ||
      !isCanonicalUuid(body.diaryId) ||
      typeof body.categoryId !== 'string'
    )
      return problem(request, 400, 'VALIDATION_ERROR');

    await delay(800);
    if (
      request.headers.get(MOCK_SCENARIO_HEADER) ===
      MOCK_SCENARIOS.feedPublishFailure
    )
      return problem(request, 503, 'INTERNAL_SERVER_ERROR');
    if (getMockPublishedFeedId(body.diaryId))
      return problem(request, 409, 'DIARY_ALREADY_PUBLISHED');
    const category = categories.find(
      ({ id }): boolean => id === body.categoryId,
    );
    if (!category) return problem(request, 404, 'CATEGORY_NOT_FOUND');

    const feed: MockFeed = {
      id: crypto.randomUUID(),
      diaryId: body.diaryId,
      author: {
        id: MOCK_USER_ID,
        nickname: '하루들',
        profileImageUrl: profileImage,
      },
      category: { id: category.id, name: category.name },
      imageUrl: diaryImage,
      publishedAt: new Date().toISOString(),
      likeCount: 0,
      commentCount: 0,
    };
    feeds.unshift(feed);
    return HttpResponse.json(
      {
        id: feed.id,
        author: feed.author,
        category: feed.category,
        imageUrl: feed.imageUrl,
        imageUrlExpiresAt: new Date(Date.now() + 600_000).toISOString(),
        publishedAt: feed.publishedAt,
        likeCount: 0,
        commentCount: 0,
        likedByMe: false,
        isMine: true,
        shareUrl: `${new URL(request.url).origin}/feeds/${feed.id}`,
      },
      { status: 201 },
    );
  }),

  http.get('/api/v1/categories', async ({ request }): Promise<Response> => {
    await delay(300);
    const error = validateAuthentication(request);
    return error ?? HttpResponse.json(categories);
  }),

  http.get('/api/v1/feeds', async ({ request }): Promise<Response> => {
    await delay(300);
    const error = validateAuthentication(request);
    if (error) return error;

    const url = new URL(request.url);
    const categoryId = url.searchParams.get('categoryId');
    const sort = url.searchParams.get('sort') ?? 'LATEST';
    const sizeValue = url.searchParams.get('size');
    const size = sizeValue === null ? 20 : Number(sizeValue);

    if (
      (sort !== 'LATEST' && sort !== 'POPULAR') ||
      (sizeValue !== null && !/^\d+$/.test(sizeValue)) ||
      !Number.isInteger(size) ||
      size < 1 ||
      size > 50 ||
      (categoryId !== null && !/^[1-9]\d*$/.test(categoryId))
    ) {
      return problem(request, 400, 'VALIDATION_ERROR');
    }
    if (
      categoryId !== null &&
      !categories.some(({ id }): boolean => id === categoryId)
    ) {
      return problem(request, 404, 'CATEGORY_NOT_FOUND');
    }

    const cursorValue = url.searchParams.get('cursor');
    const cursor =
      cursorValue === null ? null : readCursor(cursorValue, sort, categoryId);
    if (cursorValue !== null && cursor === null)
      return problem(request, 400, 'INVALID_CURSOR');

    const sortedFeeds = feeds
      .filter(
        (feed): boolean =>
          categoryId === null || feed.category.id === categoryId,
      )
      .sort((a, b): number => compareFeeds(a, b, sort))
      .filter(
        (feed): boolean =>
          cursor === null || compareFeeds(feed, cursor, sort) > 0,
      );
    const page = sortedFeeds.slice(0, size);
    const hasNext = sortedFeeds.length > size;
    const lastFeed = page.at(-1);
    const authenticated = isAuthenticated(request);

    return HttpResponse.json({
      items: page.map((feed): MockFeedResponse => ({
        ...feed,
        imageUrlExpiresAt: new Date(Date.now() + 3_600_000).toISOString(),
        likedByMe: authenticated && likedFeedIds.has(feed.id),
        isMine: authenticated && feed.author.id === MOCK_USER_ID,
        shareUrl: `${url.origin}/feeds/${feed.id}`,
      })),
      nextCursor:
        hasNext && lastFeed ? createCursor(lastFeed, sort, categoryId) : null,
      hasNext,
    });
  }),

  http.get(
    '/api/v1/feeds/:feedId/comments',
    async ({ request, params }): Promise<Response> => {
      await delay(300);
      const error = validateAuthentication(request);
      if (error) return error;
      const feedId = String(params.feedId);
      if (!isCanonicalUuid(feedId))
        return problem(request, 400, 'VALIDATION_ERROR');
      if (!feeds.some(({ id }): boolean => id === feedId))
        return problem(request, 404, 'FEED_NOT_FOUND');
      const url = new URL(request.url);
      const sizeValue = url.searchParams.get('size');
      const size = sizeValue === null ? 20 : Number(sizeValue);
      if (
        (sizeValue !== null && !/^\d+$/.test(sizeValue)) ||
        !Number.isInteger(size) ||
        size < 1 ||
        size > 50
      )
        return problem(request, 400, 'VALIDATION_ERROR');
      let cursor: { readonly id: string; readonly createdAt: string } | null =
        null;
      const cursorValue = url.searchParams.get('cursor');
      if (cursorValue !== null) {
        try {
          const value: unknown = JSON.parse(cursorValue);
          if (
            typeof value !== 'object' ||
            value === null ||
            !('feedId' in value) ||
            value.feedId !== feedId ||
            !('id' in value) ||
            typeof value.id !== 'string' ||
            !isCanonicalUuid(value.id) ||
            !('createdAt' in value) ||
            typeof value.createdAt !== 'string' ||
            !Number.isFinite(Date.parse(value.createdAt))
          )
            return problem(request, 400, 'INVALID_CURSOR');
          cursor = { id: value.id, createdAt: value.createdAt };
        } catch {
          return problem(request, 400, 'INVALID_CURSOR');
        }
      }
      const sorted = comments
        .filter((comment): boolean => comment.feedId === feedId)
        .sort(
          (a, b): number =>
            a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id),
        )
        .filter(
          (comment): boolean =>
            cursor === null ||
            comment.createdAt > cursor.createdAt ||
            (comment.createdAt === cursor.createdAt && comment.id > cursor.id),
        );
      const page = sorted.slice(0, size);
      const last = page.at(-1);
      const hasNext = sorted.length > size;
      return HttpResponse.json({
        items: page.map((comment) => ({
          id: comment.id,
          author: comment.author,
          content: comment.content,
          createdAt: comment.createdAt,
          isMine:
            isAuthenticated(request) && comment.author.id === MOCK_USER_ID,
        })),
        nextCursor:
          hasNext && last
            ? JSON.stringify({ feedId, id: last.id, createdAt: last.createdAt })
            : null,
        hasNext,
      });
    },
  ),

  http.post(
    '/api/v1/feeds/:feedId/comments',
    async ({ request, params }): Promise<Response> => {
      await delay(300);
      const error = validateMutation(request);
      if (error) return error;
      const feedId = String(params.feedId);
      if (!isCanonicalUuid(feedId))
        return problem(request, 400, 'VALIDATION_ERROR');
      const feed = feeds.find(({ id }): boolean => id === feedId);
      if (!feed) return problem(request, 404, 'FEED_NOT_FOUND');
      const data: unknown = await request.json().catch((): null => null);
      if (!isRecord(data) || typeof data.content !== 'string')
        return problem(request, 400, 'VALIDATION_ERROR');
      const content = data.content.trim();
      if ([...content].length < 1 || [...content].length > 100)
        return problem(request, 400, 'VALIDATION_ERROR');
      const comment: MockComment = {
        id: crypto.randomUUID(),
        feedId,
        author: {
          id: MOCK_USER_ID,
          nickname: '하루들',
          profileImageUrl: profileImage,
        },
        content,
        createdAt: new Date().toISOString(),
      };
      comments.push(comment);
      feed.commentCount += 1;
      return HttpResponse.json(
        {
          id: comment.id,
          author: comment.author,
          content,
          createdAt: comment.createdAt,
          isMine: true,
        },
        { status: 201 },
      );
    },
  ),

  http.get(
    '/api/v1/feeds/:feedId',
    async ({ request, params }): Promise<Response> => {
      await delay(300);
      const error = validateAuthentication(request);
      if (error) return error;
      const feedId = String(params.feedId);
      if (!isCanonicalUuid(feedId))
        return problem(request, 400, 'VALIDATION_ERROR');
      const feed = feeds.find(({ id }): boolean => id === feedId);
      if (!feed) return problem(request, 404, 'FEED_NOT_FOUND');
      const authenticated = isAuthenticated(request);
      return HttpResponse.json({
        ...feed,
        imageUrlExpiresAt: new Date(Date.now() + 3_600_000).toISOString(),
        likedByMe: authenticated && likedFeedIds.has(feed.id),
        isMine: authenticated && feed.author.id === MOCK_USER_ID,
        shareUrl: `${new URL(request.url).origin}/feeds/${feed.id}`,
      });
    },
  ),

  http.delete(
    '/api/v1/comments/:commentId',
    async ({ request, params }): Promise<Response> => {
      await delay(300);
      const error = validateMutation(request);
      if (error) return error;
      const commentId = String(params.commentId);
      if (!isCanonicalUuid(commentId))
        return problem(request, 400, 'VALIDATION_ERROR');
      const comment = comments.find(({ id }): boolean => id === commentId);
      if (!comment) return problem(request, 404, 'COMMENT_NOT_FOUND');
      if (comment.author.id !== MOCK_USER_ID)
        return problem(request, 403, 'FORBIDDEN');
      comments = comments.filter(({ id }): boolean => id !== commentId);
      const feed = feeds.find(({ id }): boolean => id === comment.feedId);
      if (feed) feed.commentCount -= 1;
      return new HttpResponse(null, { status: 204 });
    },
  ),

  http.put(
    '/api/v1/feeds/:feedId/like',
    async ({ request, params }): Promise<Response> => {
      await delay(300);
      return changeLike(request, String(params.feedId), true);
    },
  ),

  http.delete(
    '/api/v1/feeds/:feedId/like',
    async ({ request, params }): Promise<Response> => {
      await delay(300);
      return changeLike(request, String(params.feedId), false);
    },
  ),

  http.delete(
    '/api/v1/feeds/:feedId',
    async ({ request, params }): Promise<Response> => {
      await delay(300);
      const error = validateMutation(request);
      if (error) return error;
      const feedId = String(params.feedId);
      if (!isCanonicalUuid(feedId))
        return problem(request, 400, 'VALIDATION_ERROR');
      const feed = feeds.find(({ id }): boolean => id === feedId);
      if (!feed) return problem(request, 404, 'FEED_NOT_FOUND');
      if (feed.author.id !== MOCK_USER_ID)
        return problem(request, 403, 'FORBIDDEN');

      feeds = feeds.filter(({ id }): boolean => id !== feedId);
      likedFeedIds.delete(feedId);
      comments = comments.filter(
        (comment): boolean => comment.feedId !== feedId,
      );
      return new HttpResponse(null, { status: 204 });
    },
  ),
];
