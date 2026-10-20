// k6 load test: one arrival-rate scenario per endpoint.
import http from 'k6/http';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';

const BASE_URL = text('BASE_URL', 'http://app:8080');
const MIX_NAME = text('MIX', 'mixed');
const RATE = number('RATE', 1000);
const WARMUP = number('WARMUP', 60);
const DURATION = number('DURATION', 180);
const COMPLETED_TEAMS = number('COMPLETED_TEAMS', 50);
const RUN_ID = text('RUN_ID', 'k6');
const CACHE = text('CACHE', 'unknown');
const RESULT_DIR = text('RESULT_DIR', '');
const TOKENS_DIR = text('TOKENS_DIR', '/tokens');

const TEAM_SIZE = 30;
const TARGET_POINTS = 300;
const P95_TARGET_MS = 50;
const MAX_ERROR_RATE = 0.001;
const MIN_RATE_RATIO = 0.99;
const REQUEST_TIMEOUT = '10s';
const MAX_LATENCY_COVERED_S = 1;
const MIN_VUS_PER_SCENARIO = 20;
const TOTAL_SECONDS = WARMUP + DURATION;
const BUCKET_SECONDS = [5, 10, 15, 20, 30, 60, 120, 300, 600].find((b) => TOTAL_SECONDS / b <= 30) || 600;
const BUCKETS = Math.ceil(TOTAL_SECONDS / BUCKET_SECONDS);

const MIXES = JSON.parse(open('./mixes.json')).mixes;
const ENDPOINTS = ['get', 'progress', 'join', 'claim'];

if (!MIXES[MIX_NAME]) {
  throw new Error(`MIX must be one of ${Object.keys(MIXES).join(', ')}; got "${MIX_NAME}"`);
}
const MIX = MIXES[MIX_NAME].shares;
if (ENDPOINTS.reduce((sum, e) => sum + (MIX[e] || 0), 0) !== 100) {
  throw new Error(`The shares of mix ${MIX_NAME} in mixes.json must add up to 100`);
}
if (RATE <= 0 || DURATION <= 0) {
  throw new Error('RATE and DURATION must be positive');
}
if (!/^[A-Za-z0-9._-]{1,32}$/.test(RUN_ID)) {
  throw new Error('RUN_ID must be 1-32 characters of A-Z a-z 0-9 . _ -');
}

const seeded = new SharedArray('seeded users', () => JSON.parse(open(`${TOKENS_DIR}/tokens-seeded.json`)));
const joiners = new SharedArray('join users', () => JSON.parse(open(`${TOKENS_DIR}/tokens-join.json`)));
const tokensMeta = JSON.parse(open(`${TOKENS_DIR}/tokens-meta.json`));

const CLAIM_USERS = COMPLETED_TEAMS * TEAM_SIZE;
const PROGRESS_FIRST = CLAIM_USERS;
const PROGRESS_USERS = seeded.length - PROGRESS_FIRST;
const MAX_PROGRESS_REQUESTS = PROGRESS_USERS * (TARGET_POINTS / TEAM_SIZE - 1);

if (seeded.length % TEAM_SIZE !== 0 || PROGRESS_USERS <= 0) {
  throw new Error(`tokens-seeded.json has ${seeded.length} users; expected full teams and `
    + `more than COMPLETED_TEAMS (${COMPLETED_TEAMS}) teams`);
}

function scatter(size, offset) {
  const stride = [7919, 7927, 7933, 7937].find((prime) => size % prime !== 0);
  return (iteration) => (iteration * stride + offset) % size;
}
const getUser = scatter(seeded.length, 0);
const progressUser = scatter(PROGRESS_USERS, 0);

const unexpected = new Counter('unexpected_responses');
const ERROR_CODES = ['VALIDATION_FAILED', 'MALFORMED_REQUEST', 'UNAUTHORIZED', 'FORBIDDEN', 'NOT_FOUND',
  'CAMPAIGN_NOT_FOUND', 'NO_ACTIVE_CAMPAIGN', 'METHOD_NOT_ALLOWED', 'CAMPAIGN_NOT_ACTIVE', 'NOT_A_MEMBER',
  'TEAM_COMPLETED', 'CHEST_NOT_READY', 'ALREADY_CLAIMED', 'UNSUPPORTED_MEDIA_TYPE', 'IDEMPOTENCY_KEY_REUSED',
  'RATE_LIMITED', 'INTERNAL_ERROR', 'SERVICE_BUSY', 'NO_RESPONSE', 'NO_ERROR_BODY'];
const poolExhausted = new Counter('pool_exhausted');

function perMinute(endpoint) {
  return Math.round((RATE * MIX[endpoint] * 60) / 100);
}

function plannedSteady(endpoint) {
  return Math.round((perMinute(endpoint) / 60) * DURATION);
}

function vus(endpoint) {
  return Math.max(MIN_VUS_PER_SCENARIO, Math.ceil((perMinute(endpoint) / 60) * MAX_LATENCY_COVERED_S));
}

function scenario(endpoint) {
  return {
    executor: 'ramping-arrival-rate',
    exec: endpoint,
    timeUnit: '1m',
    startRate: 0,
    stages: [
      { target: perMinute(endpoint), duration: `${WARMUP}s` },
      { target: perMinute(endpoint), duration: `${DURATION}s` },
    ],
    preAllocatedVUs: vus(endpoint),
    maxVUs: vus(endpoint),
  };
}

function bucketLabel(index) {
  return (index < 10 ? '0' : '') + index;
}

const scenarios = {};
const thresholds = {
  'http_req_duration{phase:steady}': [`p(95)<${P95_TARGET_MS}`],
  'http_req_failed{phase:steady}': [`rate<${MAX_ERROR_RATE}`],
  'unexpected_responses{status:429}': ['count==0'],
  dropped_iterations: ['count==0'],
  pool_exhausted: ['count==0'],
  'http_reqs{phase:steady}': ['count>=0'],
  'http_req_waiting{phase:steady}': ['max>=0'],
};
for (const code of ERROR_CODES) {
  thresholds[`unexpected_responses{code:${code}}`] = ['count>=0'];
}
const ACTIVE = ENDPOINTS.filter((endpoint) => MIX[endpoint] > 0);
for (const endpoint of ACTIVE) {
  scenarios[endpoint] = scenario(endpoint);
  thresholds[`unexpected_responses{endpoint:${endpoint}}`] = ['count>=0'];
  thresholds[`dropped_iterations{scenario:${endpoint}}`] = ['count>=0'];
  thresholds[`http_req_duration{phase:steady,endpoint:${endpoint}}`] = ['max>=0'];
  thresholds[`http_reqs{phase:steady,endpoint:${endpoint}}`] = ['count>=0'];
  thresholds[`http_req_failed{phase:steady,endpoint:${endpoint}}`] = ['rate>=0'];
  thresholds[`http_req_waiting{phase:steady,endpoint:${endpoint}}`] = ['max>=0'];
}
for (let b = 0; b < BUCKETS; b += 1) {
  thresholds[`http_reqs{bucket:${bucketLabel(b)}}`] = ['count>=0'];
  thresholds[`http_req_duration{bucket:${bucketLabel(b)}}`] = ['max>=0'];
  for (const endpoint of ACTIVE) {
    thresholds[`http_req_duration{bucket:${bucketLabel(b)},endpoint:${endpoint}}`] = ['max>=0'];
  }
}

export const options = {
  scenarios,
  thresholds,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  setupTimeout: '60s',
};

export function setup() {
  const expiresAt = Date.parse(tokensMeta.expiresAt);
  const needed = Date.now() + (WARMUP + DURATION + 600) * 1000;
  if (!(expiresAt > needed)) {
    exec.test.abort(`The tokens expire at ${tokensMeta.expiresAt}, before the end of the test; mint new ones`);
  }

  const progressView = overview(seeded[PROGRESS_FIRST]);
  if (!progressView.team || progressView.team.memberCount !== TEAM_SIZE || progressView.team.completed) {
    exec.test.abort('The first progress user is not in a full, incomplete team: the seed does not match the tokens');
  }
  if (CLAIM_USERS > 0) {
    const claimView = overview(seeded[CLAIM_USERS - 1]);
    if (!claimView.team || !claimView.team.completed || !claimView.canClaim) {
      exec.test.abort('The last claim user cannot claim: the seed does not match COMPLETED_TEAMS');
    }
  }
  if (overview(joiners[0]).team !== null) {
    exec.test.abort('The join users are already members: the database was not reset');
  }
  return { campaignId: progressView.campaign.campaignId, startedAt: Date.now() };
}

function overview(token) {
  const res = http.get(`${BASE_URL}/get`, { headers: authorization(token), tags: { phase: 'setup' } });
  if (res.status !== 200) {
    exec.test.abort(`GET /get during setup failed: ${res.status === 0 ? res.error : `HTTP ${res.status} ${res.body}`}`);
  }
  return res.json();
}

export function get() {
  const n = exec.scenario.iterationInTest;
  send('get', 'GET', '/get', null, seeded[getUser(n)]);
}

export function progress(data) {
  const n = exec.scenario.iterationInTest;
  if (n >= MAX_PROGRESS_REQUESTS) {
    poolExhausted.add(1, { endpoint: 'progress' });
    return;
  }
  const user = PROGRESS_FIRST + progressUser(n);
  send('progress', 'POST', '/progress', { campaignId: data.campaignId, points: 1 }, seeded[user], `${RUN_ID}.p.${n}`);
}

export function join() {
  const n = exec.scenario.iterationInTest;
  if (n >= joiners.length) {
    poolExhausted.add(1, { endpoint: 'join' });
    return;
  }
  send('join', 'POST', '/join', null, joiners[n]);
}

export function claim(data) {
  const n = exec.scenario.iterationInTest;
  if (n >= CLAIM_USERS) {
    poolExhausted.add(1, { endpoint: 'claim' });
    return;
  }
  send('claim', 'POST', '/claim', { campaignId: data.campaignId }, seeded[n], `${RUN_ID}.c.${n}`);
}

function send(endpoint, method, path, body, token, idempotencyKey) {
  const headers = authorization(token);
  if (body !== null) {
    headers['Content-Type'] = 'application/json';
  }
  if (idempotencyKey) {
    headers['Idempotency-Key'] = idempotencyKey;
  }
  const elapsed = exec.scenario.progress * TOTAL_SECONDS;
  const phase = elapsed < WARMUP ? 'warmup' : 'steady';
  const bucket = bucketLabel(Math.min(BUCKETS - 1, Math.floor(elapsed / BUCKET_SECONDS)));
  const tags = { endpoint, phase, bucket };
  const res = http.request(method, `${BASE_URL}${path}`, body === null ? null : JSON.stringify(body), {
    headers,
    tags,
    timeout: REQUEST_TIMEOUT,
  });
  if (res.status !== 200) {
    recordUnexpected(res, tags);
  }
}

function authorization(token) {
  return { Authorization: `Bearer ${token}` };
}

function recordUnexpected(res, tags) {
  const code = errorCode(res);
  unexpected.add(1, { endpoint: tags.endpoint, phase: tags.phase, status: String(res.status), code });
}

function errorCode(res) {
  if (res.status === 0) {
    return 'NO_RESPONSE';
  }
  try {
    const code = JSON.parse(res.body).code;
    return typeof code === 'string' ? code : 'NO_ERROR_BODY';
  } catch (e) {
    return 'NO_ERROR_BODY';
  }
}

export function handleSummary(data) {
  const report = buildReport(data);
  const files = { stdout: report.text };
  if (RESULT_DIR) {
    files[`${RESULT_DIR}/k6-summary.json`] = JSON.stringify(data, null, 2);
    files[`${RESULT_DIR}/k6-report.json`] = JSON.stringify(report.json, null, 2);
    files[`${RESULT_DIR}/k6-report.md`] = report.markdown;
  }
  return files;
}

function buildReport(data) {
  const metric = (name) => (data.metrics[name] ? data.metrics[name].values : null);
  const count = (name) => (metric(name) ? metric(name).count : 0);

  const row = (label, filter, planned, dropped) => {
    const requests = count(`http_reqs{${filter}}`);
    const duration = requests > 0 ? metric(`http_req_duration{${filter}}`) : null;
    const failed = requests > 0 ? metric(`http_req_failed{${filter}}`) : null;
    const waiting = requests > 0 ? metric(`http_req_waiting{${filter}}`) : null;
    return {
      endpoint: label,
      planned,
      requests,
      dropped,
      rps: requests / DURATION,
      avg: duration ? duration.avg : null,
      ttfbAvg: waiting ? waiting.avg : null,
      ttfbP95: waiting ? waiting['p(95)'] : null,
      p50: duration ? duration.med : null,
      p95: duration ? duration['p(95)'] : null,
      p99: duration ? duration['p(99)'] : null,
      max: duration ? duration.max : null,
      errorRate: failed ? failed.rate : null,
    };
  };
  const endpointRows = ACTIVE.map((e) => row(e, `phase:steady,endpoint:${e}`, plannedSteady(e),
    count(`dropped_iterations{scenario:${e}}`)));
  const all = row('all', 'phase:steady', endpointRows.reduce((a, r) => a + r.planned, 0),
    count('dropped_iterations'));
  const rows = [all].concat(endpointRows);

  const timeline = [];
  for (let b = 0; b < BUCKETS; b += 1) {
    const start = b * BUCKET_SECONDS;
    const end = Math.min(TOTAL_SECONDS, start + BUCKET_SECONDS);
    const requests = count(`http_reqs{bucket:${bucketLabel(b)}}`);
    const duration = requests > 0 ? metric(`http_req_duration{bucket:${bucketLabel(b)}}`) : null;
    const p95ByEndpoint = {};
    for (const e of ACTIVE) {
      const d = metric(`http_req_duration{bucket:${bucketLabel(b)},endpoint:${e}}`);
      p95ByEndpoint[e] = d && d.max > 0 ? d['p(95)'] : null;
    }
    timeline.push({
      startSecond: start,
      endSecond: end,
      phase: end <= WARMUP ? 'warm-up' : (start >= WARMUP ? 'steady' : 'both'),
      rps: requests / (end - start),
      p50: duration ? duration.med : null,
      p95: duration ? duration['p(95)'] : null,
      p99: duration ? duration['p(99)'] : null,
      p95ByEndpoint,
    });
  }

  const unexpectedTotal = count('unexpected_responses');
  const unexpectedByCode = {};
  for (const code of ERROR_CODES) {
    const n = count(`unexpected_responses{code:${code}}`);
    if (n > 0) {
      unexpectedByCode[code] = n;
    }
  }
  const listed = Object.values(unexpectedByCode).reduce((a, b) => a + b, 0);
  if (unexpectedTotal > listed) {
    unexpectedByCode.OTHER = unexpectedTotal - listed;
  }
  const unexpectedByEndpoint = {};
  for (const endpoint of ENDPOINTS.filter((e) => MIX[e] > 0)) {
    unexpectedByEndpoint[endpoint] = count(`unexpected_responses{endpoint:${endpoint}}`);
  }
  const rateLimited = count('unexpected_responses{status:429}');
  const dropped = count('dropped_iterations');
  const exhausted = count('pool_exhausted');
  const targets = [
    target(`p95 < ${P95_TARGET_MS} ms`, all.p95 !== null && all.p95 < P95_TARGET_MS, `${fixed(all.p95)} ms`),
    target(`throughput >= ${Math.round(MIN_RATE_RATIO * 100)} % of ${RATE} req/s`,
      all.rps >= MIN_RATE_RATIO * RATE, `${fixed(all.rps, 1)} req/s`),
    target(`HTTP errors < ${MAX_ERROR_RATE * 100} %`, all.errorRate !== null && all.errorRate < MAX_ERROR_RATE,
      percent(all.errorRate)),
    target('no 429 (rate limit)', rateLimited === 0, `${rateLimited}`),
    target('no dropped iterations', dropped === 0, `${dropped}`),
    target('no exhausted user pool', exhausted === 0, `${exhausted}`),
  ];

  if (all.requests === 0) {
    targets.push(target('steady-phase requests were sent', false, '0 (the test was aborted or did not reach it)'));
  }

  const startedAt = data.setup_data ? data.setup_data.startedAt : null;
  const json = {
    settings: { mix: MIX_NAME, shares: MIX, cache: CACHE, rate: RATE, warmupSeconds: WARMUP,
      durationSeconds: DURATION, runId: RUN_ID, baseUrl: BASE_URL, seededUsers: seeded.length,
      completedTeams: COMPLETED_TEAMS, joinUsers: joiners.length, bucketSeconds: BUCKET_SECONDS,
      vusPerScenario: Object.fromEntries(ACTIVE.map((e) => [e, vus(e)])) },
    steadyWindow: startedAt === null ? null : {
      startEpochMs: startedAt + WARMUP * 1000,
      endEpochMs: startedAt + (WARMUP + DURATION) * 1000,
    },
    steady: rows,
    timeline,
    unexpectedResponses: { total: unexpectedTotal, byCode: unexpectedByCode, byEndpoint: unexpectedByEndpoint },
    droppedIterations: dropped,
    poolExhausted: exhausted,
    targets,
    passed: targets.every((t) => t.passed),
  };

  const header = `Team Chest load test: mix=${MIX_NAME} cache=${CACHE} rate=${RATE} req/s `
    + `steady=${DURATION}s after ${WARMUP}s warm-up (run ${RUN_ID})`;
  const table = [
    '| endpoint | planned | sent | dropped | req/s | p50 ms | p95 ms | p99 ms | max ms | errors |',
    '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|',
  ].concat(rows.map((r) => `| ${r.endpoint} | ${r.planned} | ${r.requests} | ${r.dropped} | ${fixed(r.rps, 1)} | `
    + `${fixed(r.p50)} | ${fixed(r.p95)} | ${fixed(r.p99)} | ${fixed(r.max)} | ${percent(r.errorRate)} |`));
  const tableNote = 'planned and sent: steady phase; dropped: whole run (k6 does not split it by phase). '
    + `VUs per endpoint: ${ACTIVE.map((e) => `${e} ${vus(e)}`).join(', ')}.`;
  const timelineTable = [
    `| seconds | phase | req/s | p50 ms | p95 ms | p99 ms | ${ACTIVE.map((e) => `${e} p95`).join(' | ')} |`,
    `|---|---|---:|---:|---:|---:|${ACTIVE.map(() => '---:').join('|')}|`,
  ].concat(timeline.map((t) => `| ${t.startSecond}-${t.endSecond} | ${t.phase} | ${fixed(t.rps, 1)} | `
    + `${fixed(t.p50)} | ${fixed(t.p95)} | ${fixed(t.p99)} | `
    + `${ACTIVE.map((e) => fixed(t.p95ByEndpoint[e])).join(' | ')} |`));
  const targetLines = targets.map((t) => `- ${t.passed ? 'PASS' : 'FAIL'}  ${t.name}: ${t.actual}`);
  const byCode = Object.entries(unexpectedByCode).map(([code, n]) => `${code} ${n}`).join(', ');
  const responsesLine = `Non-200 responses (whole run, warm-up included): ${unexpectedTotal}`
    + (unexpectedTotal > 0 ? ` (${byCode})` : '');

  const timelineNote = `Every ${BUCKET_SECONDS} s of the run (warm-up included), by when the request started. `
    + 'A warm application shows a flat p95 from the start of the steady phase.';
  const markdown = [`# ${header}`, '', '## Steady phase', '', ...table, '', tableNote, '', '## Targets', '',
    ...targetLines, '', responsesLine, '', '## Latency over time', '', timelineNote, '', ...timelineTable, '']
    .join('\n');
  const text = ['', header, '', ...table, tableNote, '', ...targetLines, '', responsesLine, '',
    'Latency over time:', ...timelineTable, '', `Result: ${json.passed ? 'PASS' : 'FAIL'}`, ''].join('\n');
  return { json, markdown, text };
}

function target(name, passed, actual) {
  return { name, passed: Boolean(passed), actual };
}

function text(name, fallback) {
  const value = __ENV[name];
  return value === undefined || value === '' ? fallback : value;
}

function number(name, fallback) {
  const raw = text(name, String(fallback));
  const value = Number(raw);
  if (!Number.isFinite(value) || value < 0) {
    throw new Error(`${name} must be a non-negative number; got "${raw}"`);
  }
  return value;
}

function fixed(value, digits = 2) {
  return value === null || value === undefined ? '-' : value.toFixed(digits);
}

function percent(rate) {
  return rate === null || rate === undefined ? '-' : `${(rate * 100).toFixed(3)} %`;
}
