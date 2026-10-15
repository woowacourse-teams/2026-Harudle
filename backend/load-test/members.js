import http from 'k6/http';
import execution from 'k6/execution';
import { check, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const fixture = JSON.parse(open(__ENV.MEMBERS_FILE || './runs/peak/members.json'));
const baseUrl = fixture.baseUrl;
const smoke = __ENV.MODE === 'smoke';
const smokeMembers = [0, 40, 160, 190];
const expectedRequests = smoke ? 28 : 1420;
const expectedVisits = smoke ? 4 : 200;
const businessRequests = new Counter('business_requests');
const businessFailures = new Rate('business_failures');
const journeySuccess = new Rate('journey_success');
const completedJourneys = new Counter('completed_journeys');
const journeyDuration = new Trend('journey_duration', true);
const visits = new Counter('member_visits');
const generationDuration = new Trend('generation_duration', true);
const apiDuration = new Trend('ordinary_api_duration', true);
const expectedJourneys = smoke
  ? { create_publish: 1, browse: 2, react: 2, records: 2 }
  : { create_publish: 20, browse: 160, react: 60, records: 100 };
const apiNames = [
  'GET generation-usage', 'POST load-test diaries', 'POST feeds', 'GET feeds/:id',
  'GET categories', 'GET feeds latest', 'GET feeds next', 'GET feeds popular',
  'GET comments', 'POST comments', 'PUT like', 'GET diaries month', 'GET diaries/:id',
];

if (!/^https:\/\/dev\.harudle\.com$|^http:\/\/(localhost|127\.0\.0\.1|host\.docker\.internal)(:\d+)?$/.test(baseUrl)) {
  throw new Error('Only the dev or local origin is allowed');
}
if (![2, 3].includes(fixture.version) || (fixture.version === 3 && !fixture.runName)
  || fixture.members.length !== 200 || new Set(fixture.members.map((member) => member.id)).size !== 200) {
  throw new Error('A prepared test run with 200 distinct members is required');
}
if (__ENV.MODE && !['smoke', 'peak'].includes(__ENV.MODE)) {
  throw new Error('MODE must be smoke or peak');
}

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'count'],
  scenarios: smoke ? {
    members: { executor: 'shared-iterations', vus: 4, iterations: 4, maxDuration: '2m', exec: 'memberVisit' },
  } : {
    members: {
      executor: 'constant-arrival-rate', rate: 40, timeUnit: '1m', duration: '5m',
      preAllocatedVUs: 20, maxVUs: 60, gracefulStop: '90s', exec: 'memberVisit',
    },
  },
  setupTimeout: '10m',
  thresholds: {
    business_requests: [`count==${expectedRequests}`],
    member_visits: [`count==${expectedVisits}`],
    business_failures: [{ threshold: 'rate<0.01', abortOnFail: true, delayAbortEval: '30s' }],
    journey_success: ['rate==1'],
    completed_journeys: [`count==${smoke ? 7 : 340}`],
    ordinary_api_duration: ['p(95)<1000'],
    generation_duration: ['min>=30000', 'p(95)<45000'],
    ...(smoke ? {} : { dropped_iterations: ['count==0'] }),
    ...Object.fromEntries(Object.entries(expectedJourneys).flatMap(([name, count]) => [
      [`completed_journeys{journey:${name}}`, [`count==${count}`]],
      [`journey_duration{journey:${name}}`, []],
    ])),
    ...Object.fromEntries(apiNames.flatMap((name) => [
      [`http_req_duration{kind:business,name:${name}}`, []],
      [`business_requests{api:${name}}`, []],
      [`business_failures{api:${name}}`, []],
    ])),
  },
};

function requireValue(condition, label) {
  if (!check(condition, { [label]: (value) => value })) fail(label);
}

function json(response, label) {
  try { return response.json(); } catch (_) { fail(`${label}: invalid JSON (HTTP ${response.status})`); }
}

function setupRequest(method, path, payload, options = {}) {
  const response = http.request(method, `${baseUrl}${path}`, payload, {
    ...options, redirects: 0, timeout: '15s', tags: { kind: 'setup', name: `${method} ${path}` },
  });
  requireValue(response.status === 200, `${method} ${path}: HTTP 200`);
  return json(response, path);
}

export function setup() {
  const koreaToday = new Date(Date.now() + 9 * 3600000).toISOString().slice(0, 10);
  requireValue(fixture.date === koreaToday, 'fixture uses today in Asia/Seoul; prepare a fresh run');
  const indexes = smoke ? smokeMembers : fixture.members.map((_, index) => index);
  const sessions = {};
  const jar = http.cookieJar();
  let categoryId;
  for (const index of indexes) {
    const member = fixture.members[index];
    jar.clear(baseUrl);
    jar.set(baseUrl, 'refresh_token', member.refreshToken, { path: '/api/v1/auth', secure: baseUrl.startsWith('https:') });
    const csrf = setupRequest('GET', '/api/v1/auth/csrf');
    requireValue(typeof csrf.token === 'string' && csrf.token.length > 0, 'CSRF token issued');
    const token = setupRequest('POST', '/api/v1/auth/refresh', null, { headers: { 'X-XSRF-TOKEN': csrf.token } });
    requireValue(token.tokenType === 'Bearer' && !!token.accessToken, 'real Bearer token issued');
    const headers = { Authorization: `Bearer ${token.accessToken}`, 'X-XSRF-TOKEN': csrf.token };
    const me = setupRequest('GET', '/api/v1/me', null, { headers });
    requireValue(me.id === member.id && me.role === 'USER', 'authenticated as the expected test member');
    if (categoryId === undefined) {
      const stub = setupRequest('GET', '/api/v1/load-test/ai-stub', null, { headers });
      requireValue(stub.environment === 'dev' && stub.delayMs === 30000 && stub.storage === 'real'
        && stub.output === 'test-image', 'dev AI Stub uses the fixed test image at 30 seconds');
      const categories = setupRequest('GET', '/api/v1/categories', null, { headers });
      const category = categories.find((item) => item.name === fixture.categoryName);
      requireValue(!!category, 'run-specific active category exists');
      categoryId = category.id;
      const page = setupRequest('GET', `/api/v1/feeds?sort=LATEST&size=20&categoryId=${categoryId}`, null, { headers });
      requireValue(page.items.length === 20 && page.hasNext && !!page.nextCursor, 'seeded feeds support pagination');
    }
    sessions[index] = { accessToken: token.accessToken, csrfToken: csrf.token, expiresAt: Date.now() + token.expiresIn * 1000 };
  }
  requireValue(Object.values(sessions).every((session) => session.expiresAt > Date.now() + 450000), 'tokens remain valid through the full run');
  jar.clear(baseUrl);
  return { sessions, categoryId };
}

function request(session, method, path, payload, name, generation = false, expectedStatus = 200, extraHeaders = {}) {
  const response = http.request(method, `${baseUrl}${path}`, payload === null ? null : JSON.stringify(payload), {
    headers: {
      Authorization: `Bearer ${session.accessToken}`, 'X-XSRF-TOKEN': session.csrfToken,
      'Content-Type': 'application/json', ...extraHeaders,
    },
    cookies: { 'XSRF-TOKEN': { value: session.csrfToken, replace: true } },
    timeout: generation ? '75s' : '15s', redirects: 0,
    tags: { kind: 'business', name },
  });
  businessRequests.add(1, { api: name });
  const success = response.status === expectedStatus;
  businessFailures.add(!success, { api: name });
  if (generation) generationDuration.add(response.timings.duration);
  else apiDuration.add(response.timings.duration);
  if (!success) {
    let errorCode = 'unknown';
    try { errorCode = response.json().code || 'unknown'; } catch (_) { /* No response body is logged. */ }
    console.error(`${name}: received HTTP ${response.status}, code=${errorCode}`);
  }
  requireValue(success, `${name}: HTTP ${expectedStatus}`);
  return json(response, name);
}

function journey(name, action) {
  const startedAt = Date.now();
  try {
    action();
    completedJourneys.add(1, { journey: name });
    journeyDuration.add(Date.now() - startedAt, { journey: name });
    journeySuccess.add(true, { journey: name });
  } catch (error) {
    journeySuccess.add(false, { journey: name });
    // Log only check names, never responses, cookies or tokens.
    console.error(`${name}: ${error.message}`);
  }
}

export function memberVisit(data) {
  const iteration = execution.scenario.iterationInTest;
  // 73 and 200 are coprime: every member visits once, with roles spread over time.
  const index = smoke ? smokeMembers[iteration] : (iteration * 73) % 200;
  if (iteration >= expectedVisits) fail('Unexpected extra visit');
  const member = fixture.members[index];
  const session = data.sessions[index];
  const jar = http.cookieJar();
  jar.clear(baseUrl);
  jar.set(baseUrl, 'XSRF-TOKEN', session.csrfToken, { path: '/api/v1', secure: baseUrl.startsWith('https:') });
  requireValue(jar.cookiesForURL(`${baseUrl}/api/v1/feeds`)['XSRF-TOKEN']?.[0] === session.csrfToken, 'member CSRF cookie is installed');
  visits.add(1);

  if (index < 20) journey('create_publish', () => {
    const usage = request(session, 'GET', '/api/v1/me/generation-usage', null, 'GET generation-usage');
    requireValue(usage.remainingCount > 0, 'generation quota remains');
    const diary = request(session, 'POST', '/api/v1/load-test/diaries', {
      diaryDate: fixture.date, sourceText: `부하 테스트 ${fixture.runId}. 오늘 팀원과 개발하고 산책했다.`,
    }, 'POST load-test diaries', true, 201, { 'Idempotency-Key': member.idempotencyKey });
    requireValue(!!diary.id && diary.generation.status === 'SUCCEEDED', 'new diary generation succeeded');
    const feed = request(session, 'POST', '/api/v1/feeds', { diaryId: diary.id, categoryId: data.categoryId }, 'POST feeds', false, 201);
    const detail = request(session, 'GET', `/api/v1/feeds/${feed.id}`, null, 'GET feeds/:id');
    requireValue(detail.id === feed.id && detail.isMine === true, 'created feed is visible to its author');
  });

  if (index >= 20 && index < 180) journey('browse', () => {
    const categories = request(session, 'GET', '/api/v1/categories', null, 'GET categories');
    requireValue(categories.some((category) => category.id === data.categoryId), 'test category is active');
    const query = `size=20&categoryId=${data.categoryId}`;
    const first = request(session, 'GET', `/api/v1/feeds?sort=LATEST&${query}`, null, 'GET feeds latest');
    requireValue(first.items.length === 20 && first.hasNext && !!first.nextCursor, 'latest page has a next cursor');
    const next = request(session, 'GET', `/api/v1/feeds?sort=LATEST&${query}&cursor=${encodeURIComponent(first.nextCursor)}`, null, 'GET feeds next');
    requireValue(next.items.length > 0 && !next.items.some((item) => first.items.some((prior) => prior.id === item.id)), 'cursor moves to another page');
    const popular = request(session, 'GET', `/api/v1/feeds?sort=POPULAR&${query}`, null, 'GET feeds popular');
    requireValue(popular.items.length > 0, 'popular page is populated');
    const chosen = popular.items[index % popular.items.length];
    const detail = request(session, 'GET', `/api/v1/feeds/${chosen.id}`, null, 'GET feeds/:id');
    requireValue(detail.id === chosen.id, 'selected feed detail matches');
  });

  if (index >= 140) journey('react', () => {
    const feedId = fixture.members[(index + 50) % 200].feedId;
    request(session, 'GET', `/api/v1/feeds/${feedId}`, null, 'GET feeds/:id');
    const comments = request(session, 'GET', `/api/v1/feeds/${feedId}/comments`, null, 'GET comments');
    requireValue(Array.isArray(comments.items), 'comments list is readable');
    const comment = request(session, 'POST', `/api/v1/feeds/${feedId}/comments`, { content: `부하 테스트 ${fixture.runId}` }, 'POST comments', false, 201);
    requireValue(!!comment.id && comment.isMine === true, 'comment is created by this member');
    const like = request(session, 'PUT', `/api/v1/feeds/${feedId}/like`, null, 'PUT like');
    requireValue(like.likedByMe === true, 'like was applied');
  });

  if (index < 100) journey('records', () => {
    for (const month of [fixture.currentMonth, fixture.previousMonth]) {
      const [year, monthNumber] = month.split('-').map(Number);
      const timeline = request(session, 'GET', `/api/v1/diaries?year=${year}&month=${monthNumber}`, null, 'GET diaries month');
      requireValue(timeline.days.some((day) => day.items.length > 0), 'monthly diary data exists');
    }
    const diary = request(session, 'GET', `/api/v1/diaries/${member.diaryId}`, null, 'GET diaries/:id');
    requireValue(diary.id === member.diaryId && diary.generation.status === 'SUCCEEDED', 'own previous diary is readable');
  });
}
