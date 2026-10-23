import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('./members.js', import.meta.url), 'utf8')
  .replace(/^import .*;\n/gm, '')
  .replace(/^export /gm, '');

function runner(mode = 'peak') {
  const metrics = {};
  class Metric {
    constructor(name) { this.values = metrics[name] = []; }
    add(value) { this.values.push(value); }
  }
  const fixture = {
    version: 3, runName: 'test', baseUrl: 'http://127.0.0.1',
    members: Array.from({ length: 200 }, (_, i) => ({ id: String(i) })),
  };
  const selected = [];
  const warnings = [];
  const sessions = new Proxy({}, {
    get: (_, index) => {
      selected.push(Number(index));
      return { csrfToken: 'test-csrf' };
    },
  });
  const context = vm.createContext({
    __ENV: { MODE: mode }, open: () => JSON.stringify(fixture),
    execution: { scenario: { iterationInTest: 0 } },
    Counter: Metric, Rate: Metric, Trend: Metric,
    check: (value, checks) => Object.values(checks).every((fn) => fn(value)),
    sleep() {},
    fail: (message) => { throw new Error(message); },
    http: {
      request: () => { throw new Error('HTTP must not be used by routing tests'); },
      cookieJar: () => ({
        clear() {}, set() {}, cookiesForURL: () => ({ 'XSRF-TOKEN': ['test-csrf'] }),
      }),
    },
    data: { sessions }, journeys: [],
    console: { warn: (message) => warnings.push(message), error() {} },
  });
  vm.runInContext(source, context);
  // Verify visit routing independently of the HTTP actions inside each journey.
  vm.runInContext('journey = (name) => journeys.push(name)', context);
  return {
    metrics, selected, warnings, context,
    visit(iteration) {
      context.execution.scenario.iterationInTest = iteration;
      vm.runInContext('memberVisit(data)', context);
    },
  };
}

test('200 peak visits retain all members, action counts and strict thresholds', () => {
  const r = runner();
  for (let i = 0; i < 200; i++) r.visit(i);
  assert.equal(new Set(r.selected).size, 200);
  assert.equal(r.metrics.member_visits.length, 200);
  const counts = {};
  for (const name of r.context.journeys) counts[name] = (counts[name] || 0) + 1;
  assert.deepEqual(counts, { create_publish: 20, records: 100, browse: 160, react: 60 });
  assert.equal(vm.runInContext('options.thresholds.business_requests[0]', r.context), 'count==1420');
  assert.equal(vm.runInContext('options.thresholds.member_visits[0]', r.context), 'count==200');
  assert.equal(vm.runInContext('options.thresholds.dropped_iterations[0]', r.context), 'count==0');
});

test('the final peak boundary slot does not access accounts or count a visit', () => {
  const r = runner();
  // An absent session payload must be safe because the guard runs first.
  r.context.data = undefined;
  r.visit(200);
  assert.deepEqual(r.selected, []);
  assert.equal(r.metrics.member_visits.length, 0);
  assert.equal(r.metrics.business_requests.length, 0);
  assert.equal(r.context.journeys.length, 0);
});

test('unexpected peak slots after the boundary still fail', () => {
  const r = runner();
  assert.throws(() => r.visit(201), /Unexpected extra visit/);
  assert.deepEqual(r.selected, []);
});

test('smoke keeps four selected members and rejects an extra visit', () => {
  const r = runner('smoke');
  for (let i = 0; i < 4; i++) r.visit(i);
  assert.deepEqual(r.selected, [0, 40, 160, 190]);
  assert.equal(r.metrics.member_visits.length, 4);
  assert.throws(() => r.visit(4), /Unexpected extra visit/);
});

test('slow ordinary requests log API timing without secrets or response bodies', () => {
  const r = runner();
  r.context.http.request = () => ({
    status: 200, timings: { duration: 10774, waiting: 10764 },
    headers: { 'X-Trace-Id': '217c9d38b6924cf98ebadba8f78d6438' },
    json: () => ({ privateValue: 'private-response' }),
  });
  vm.runInContext("request({ accessToken: 'private-token', csrfToken: 'private-csrf' }, 'GET', '/api/v1/feeds?cursor=private-cursor', null, 'GET feeds latest')", r.context);
  assert.deepEqual(r.warnings, ['GET feeds latest: slow response HTTP 200, durationMs=10774, blockedMs=unavailable, connectingMs=unavailable, tls_handshakingMs=unavailable, sendingMs=unavailable, waitingMs=10764, receivingMs=unavailable, traceId=217c9d38b6924cf98ebadba8f78d6438']);
  assert.ok(!r.warnings.join('').includes('private-'));
  assert.equal(r.metrics.business_requests.length, 1);
});

test('slow requests retain finite transport timings and reject invalid timings', () => {
  const r = runner();
  r.context.http.request = () => ({
    status: 200,
    timings: { duration: 2000, blocked: 2, connecting: 3, tls_handshaking: 4,
      sending: -1, waiting: NaN, receiving: Infinity },
    json: () => ({}),
  });
  vm.runInContext("request({}, 'GET', '/api/v1/feeds', null, 'GET feeds latest')", r.context);
  assert.match(r.warnings[0], /blockedMs=2, connectingMs=3, tls_handshakingMs=4/);
  assert.match(r.warnings[0], /sendingMs=unavailable, waitingMs=unavailable, receivingMs=unavailable/);
});

test('missing or malformed response trace IDs are not copied into logs', () => {
  for (const headers of [undefined, { 'X-Trace-Id': 'private-value\nforged-log' }]) {
    const r = runner();
    r.context.http.request = () => ({
      status: 200, timings: { duration: 1000, waiting: 990 }, headers, json: () => ({}),
    });
    vm.runInContext("request({}, 'GET', '/api/v1/feeds', null, 'GET feeds latest')", r.context);
    assert.ok(r.warnings[0].endsWith('traceId=unavailable'));
    assert.ok(!r.warnings[0].includes('private-value'));
  }
});

test('generation acceptance is measured as a fast API request and warns on delays', () => {
  const r = runner();
  r.context.http.request = () => ({ status: 200, timings: { duration: 999 }, json: () => ({}) });
  vm.runInContext("request({}, 'GET', '/api/v1/feeds', null, 'GET feeds latest')", r.context);
  r.context.http.request = () => ({ status: 202, timings: { duration: 30000 }, json: () => ({}) });
  vm.runInContext("request({}, 'POST', '/api/v1/load-test/diaries', null, 'POST load-test diaries', 202)", r.context);
  assert.equal(r.warnings.length, 1);
  assert.equal(r.metrics.generation_duration.length, 0);
  assert.equal(r.metrics.ordinary_api_duration.length, 2);
});

test('creation accepts HTTP 202 and waits for completion before publishing with HTTP 201', () => {
  const r = runner();
  const calls = [];
  const diary = { id: 'diary', generation: { id: 'job', status: 'PENDING' } };
  const responses = {
    'GET generation-usage': [200, { remainingCount: 2 }],
    'POST load-test diaries': [202, diary],
    'GET generation status': [200, { id: 'job', status: 'SUCCEEDED', diaryId: 'diary', errorCode: null }],
    'POST feeds': [201, { id: 'feed' }],
    'GET feeds/:id': [200, { id: 'feed', isMine: true }],
  };
  r.context.http.request = (_method, _url, _body, options) => {
    const name = options.tags.name;
    if (name === 'GET generation status') assert.equal(_url, 'http://127.0.0.1/api/v1/diary-generations/job');
    calls.push(name);
    const [status, body] = responses[name];
    return { status, timings: { duration: 10 }, json: () => body };
  };
  vm.runInContext("journey = (name, action) => { if (name === 'create_publish') action(); }", r.context);

  r.visit(0);

  assert.deepEqual(calls, [
    'GET generation-usage', 'POST load-test diaries', 'GET generation status', 'POST feeds', 'GET feeds/:id',
  ]);
  assert.deepEqual(r.metrics.business_failures, [false, false, false, false]);
  assert.equal(r.metrics.generation_status_requests.length, 1);
  assert.equal(r.metrics.generation_duration.length, 1);
});

function generationRunner(states) {
  const r = runner();
  r.context.clock = 0;
  r.context.Date = class extends Date { static now() { return r.context.clock; } };
  r.context.sleep = seconds => { r.context.clock += seconds * 1000; };
  let index = 0;
  r.context.http.request = () => ({
    status: 200,
    json: () => ({ id: 'job', status: states[Math.min(index++, states.length - 1)], diaryId: 'diary', errorCode: null }),
  });
  return r;
}

test('generation polling preserves business request counts and measures queue waiting through completion', () => {
  const r = generationRunner(['PENDING', 'PROCESSING', 'SUCCEEDED']);
  vm.runInContext("waitForGeneration({}, { id: 'diary', generation: { id: 'job' } }, 0)", r.context);
  assert.deepEqual(r.metrics.generation_duration, [2000]);
  assert.equal(r.metrics.generation_status_requests.length, 3);
  assert.equal(r.metrics.business_requests.length, 0);
  assert.equal(r.metrics.ordinary_api_duration.length, 0);
});

test('pending jobs have a bounded wait and are not reported as completed', () => {
  const r = generationRunner(['PENDING']);
  assert.throws(() => vm.runInContext("waitForGeneration({}, { id: 'diary', generation: { id: 'job' } }, 0)", r.context), /75 seconds/);
  assert.equal(r.context.clock, 75000);
  assert.equal(r.metrics.generation_duration.length, 0);
});

test('failed generation stops polling and does not count as completion', () => {
  const r = generationRunner(['FAILED']);
  assert.throws(() => vm.runInContext("waitForGeneration({}, { id: 'diary', generation: { id: 'job' } }, 0)", r.context), /has not failed/);
  assert.equal(r.metrics.generation_status_requests.length, 1);
  assert.equal(r.metrics.generation_duration.length, 0);
});

test('polling rejects HTTP errors and mismatched job IDs', () => {
  for (const response of [
    { status: 404 },
    { status: 200, json: () => ({ id: 'other-job', status: 'SUCCEEDED', diaryId: 'diary' }) },
    { status: 200, json: () => ({ id: 'job', status: 'SUCCEEDED', diaryId: 'other-diary' }) },
  ]) {
    const r = generationRunner([]);
    r.context.http.request = () => response;
    assert.throws(() => vm.runInContext("waitForGeneration({}, { id: 'diary', generation: { id: 'job' } }, 0)", r.context));
    assert.equal(r.metrics.generation_duration.length, 0);
  }
});
