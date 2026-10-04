// Load test (docs/load-testing.md), run by tests/load/run.sh with the grafana/k6 image against
// tests/load/compose.load.yaml. Four scenarios run together for LOAD_DURATION:
//   dashboards  – owners opening dashboards: overview endpoints and saved-chart runs, many at once
//   imports     – sales CSV imports (new receipts every time) at a steady rate
//   exports     – report CSV/PDF downloads, and a few full business ZIP exports (rate limited by design)
//   workers     – signed payment webhooks and invitation emails: the event worker and the mail outbox
// Thresholds fail the run; the summary records latencies, errors, 429s (the chart run limit) and how long
// the background queues took to drain afterwards.
import http from 'k6/http';
import crypto from 'k6/crypto';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const WEB = __ENV.WEB || 'http://web:8080';
const MGMT = __ENV.MGMT || 'http://api:8081';
const PASSWORD = 'correct horse battery staple';
const OWNERS = Number(__ENV.OWNERS || 4);
const DURATION = __ENV.LOAD_DURATION || '2m';
const FAKE_SECRET = 'whsec_fake_insight_studio_local_only';

const chartRateLimited = new Counter('chart_runs_rate_limited');
const reported = {};

/** Logs the first unexpected answer of each scenario (status and the start of the body). */
function report(name, res, ok) {
  if (!ok && !reported[name]) {
    reported[name] = true;
    console.warn(`${name}: HTTP ${res.status} ${String(res.body).slice(0, 200)}`);
  }
  return ok;
}
const queueDrain = new Trend('queue_drain_seconds');

export const options = {
  scenarios: {
    dashboards: { executor: 'constant-vus', vus: 12, duration: DURATION, exec: 'dashboards' },
    imports: { executor: 'constant-arrival-rate', rate: 1, timeUnit: '2s', duration: DURATION, preAllocatedVUs: 3, exec: 'imports' },
    exports: { executor: 'constant-arrival-rate', rate: 1, timeUnit: '3s', duration: DURATION, preAllocatedVUs: 3, exec: 'exports' },
    business_exports: { executor: 'shared-iterations', vus: 1, iterations: 4, maxDuration: DURATION, exec: 'businessExport' },
    webhooks: { executor: 'constant-arrival-rate', rate: 10, timeUnit: '1s', duration: DURATION, preAllocatedVUs: 20, exec: 'webhooks' },
    // 20 invitations per account per hour (rate limit): spread over the owners.
    invitations: { executor: 'shared-iterations', vus: 2, iterations: 60, maxDuration: DURATION, exec: 'invitations' },
  },
  thresholds: {
    'http_req_failed{scenario:dashboards}': ['rate<0.01'],
    'http_req_duration{scenario:dashboards,kind:overview}': ['p(95)<1500'],
    'http_req_duration{scenario:dashboards,kind:chart}': ['p(95)<2000'],
    'http_req_failed{scenario:imports}': ['rate<0.01'],
    'http_req_duration{scenario:imports}': ['p(95)<5000'],
    'http_req_failed{scenario:exports}': ['rate<0.01'],
    'http_req_duration{scenario:exports}': ['p(95)<5000'],
    'http_req_duration{scenario:business_exports}': ['p(95)<20000'],
    'http_req_failed{scenario:webhooks}': ['rate<0.01'],
    'http_req_duration{scenario:webhooks}': ['p(95)<500'],
    'http_req_failed{scenario:invitations}': ['rate<0.01'],
    queue_drain_seconds: ['max<120'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

// A 429 from the chart run limit is the API protecting the database, not a failure.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 399 }, 429));

function cookieValue(jar, name) {
  const cookies = jar.cookiesForURL(WEB);
  return cookies[name] ? cookies[name][0] : '';
}

function signIn(email) {
  const jar = http.cookieJar();
  // A fresh session per owner: signing in on another owner's session would migrate that session (the API
  // protects against session fixation), invalidating the id kept for the previous owner.
  jar.clear(WEB);
  http.get(`${WEB}/api/session`);
  const res = http.post(`${WEB}/api/auth/sign-in`, JSON.stringify({ email, password: PASSWORD }), {
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': cookieValue(jar, 'XSRF-TOKEN') },
  });
  check(res, { 'signed in': (r) => r.status === 200 });
  return { session: cookieValue(jar, 'SESSION'), xsrf: cookieValue(jar, 'XSRF-TOKEN') };
}

export function setup() {
  const business = Number(__ENV.BUSINESS_ID);
  const sessions = [];
  for (let i = 1; i <= OWNERS; i++) {
    sessions.push(signIn(`load${i}@load.test`));
  }
  // Saved charts for the dashboard scenario (the first owner creates them).
  use(sessions[0]);
  const charts = [];
  const definitions = [
    { visualization: 'line', metrics: ['revenue'], groupBy: 'time', granularity: 'week' },
    { visualization: 'bar', metrics: ['revenue'], groupBy: 'store', limit: 10 },
    { visualization: 'bar', metrics: ['units'], groupBy: 'category', limit: 10 },
    { visualization: 'table', metrics: ['revenue', 'orders', 'average_order_value'], groupBy: 'store', limit: 20 },
    { visualization: 'kpi', metrics: ['revenue', 'orders'], groupBy: 'none' },
    { visualization: 'pie', metrics: ['revenue'], groupBy: 'category', limit: 8 },
  ];
  definitions.forEach((d, i) => {
    const res = http.post(`${WEB}/api/charts`, JSON.stringify({
      schemaVersion: 1, title: `Load chart ${i + 1} ${Date.now()}`, engine: 'sql',
      range: { type: 'fixed', from: '2026-03-01', to: '2026-08-31' },
      filters: { storeIds: [], categories: [], productIds: [] }, ...d,
    }), headers(sessions[0], business, true));
    check(res, { 'chart created': (r) => report('chart', r, r.status === 201) });
    if (res.status === 201) charts.push(res.json('id'));
  });
  const sub = `sub_load_${Date.now()}`;
  return { business, sessions, charts, sub };
}

function use(s) {
  const jar = http.cookieJar();
  jar.set(WEB, 'SESSION', s.session);
  jar.set(WEB, 'XSRF-TOKEN', s.xsrf);
}

function headers(s, business, json, tags) {
  const h = { 'X-XSRF-TOKEN': s.xsrf, 'X-Business-Id': String(business) };
  if (json) h['Content-Type'] = 'application/json';
  return { headers: h, tags: tags || {} };
}

export function dashboards(data) {
  const s = data.sessions[__VU % data.sessions.length];
  use(s);
  const range = 'from=2026-03-01&to=2026-08-31';
  const overview = { kind: 'overview' };
  http.batch([
    ['GET', `${WEB}/api/dashboard/summary?${range}`, null, headers(s, data.business, false, overview)],
    ['GET', `${WEB}/api/dashboard/revenue?${range}&granularity=week`, null, headers(s, data.business, false, overview)],
    ['GET', `${WEB}/api/dashboard/sales-by-store?${range}`, null, headers(s, data.business, false, overview)],
    ['GET', `${WEB}/api/dashboard/top-products?${range}&limit=5`, null, headers(s, data.business, false, overview)],
  ]);
  // A dashboard's widgets: the web app runs at most 3 at a time.
  const runs = data.charts.map((id) => ['GET', `${WEB}/api/charts/${id}/data`, null, headers(s, data.business, false, { kind: 'chart' })]);
  for (let i = 0; i < runs.length; i += 3) {
    http.batch(runs.slice(i, i + 3)).forEach((r) => {
      if (r.status === 429) chartRateLimited.add(1);
    });
  }
  sleep(1);
}

export function imports(data) {
  const s = data.sessions[0];
  use(s);
  const id = `${__VU}-${__ITER}-${Date.now()}`;
  let csv = 'store_code,receipt_number,sold_at,sku,quantity,unit_price\n';
  for (let i = 0; i < 200; i++) {
    csv += `BOS,LOAD-${id}-${i},2026-08-${String(1 + (i % 28)).padStart(2, '0')}T1${i % 10}:15:00,TOP-001,${1 + (i % 3)},26.00\n`;
  }
  const res = http.post(`${WEB}/api/imports`, { file: http.file(csv, `load-${id}.csv`, 'text/csv'), dryRun: 'false' },
    headers(s, data.business, false));
  check(res, { 'import accepted': (r) => report('import', r, r.status === 200) });
}

export function exports(data) {
  const s = data.sessions[1 % data.sessions.length];
  use(s);
  const range = 'from=2026-03-01&to=2026-08-31';
  const pick = __ITER % 3;
  const path = pick === 0 ? `/api/reports/monthly.csv?${range}` : pick === 1 ? `/api/reports/monthly.pdf?${range}` : `/api/reports/categories.pdf?${range}`;
  const res = http.get(`${WEB}${path}`, headers(s, data.business, false));
  check(res, { 'export downloaded': (r) => report('export', r, r.status === 200 && r.body.length > 100) });
}

export function businessExport(data) {
  const s = data.sessions[0];
  use(s);
  const res = http.get(`${WEB}/api/businesses/${data.business}/export`, Object.assign(headers(s, data.business, false), { responseType: 'binary' }));
  check(res, { 'business ZIP downloaded': (r) => r.status === 200 && r.body.byteLength > 100000 });
}

export function webhooks(data) {
  // Signed like Stripe (the fake provider's local-only secret); each is recorded once and processed by the
  // event worker, which asks the provider for the subscription (unknown here: ignored, like stray events).
  const body = JSON.stringify({
    id: `evt_load_${__VU}_${__ITER}_${Date.now()}`, object: 'event', type: 'customer.subscription.updated', livemode: false,
    created: Math.floor(Date.now() / 1000),
    data: { object: { id: `${data.sub}_${__ITER % 50}`, object: 'subscription', customer: 'cus_load', metadata: { business_id: String(data.business) } } },
  });
  const t = Math.floor(Date.now() / 1000);
  const signature = `t=${t},v1=${crypto.hmac('sha256', FAKE_SECRET, `${t}.${body}`, 'hex')}`;
  const res = http.post(`${WEB}/api/billing/webhooks/fake`, body, { headers: { 'Content-Type': 'application/json', 'Stripe-Signature': signature } });
  check(res, { 'webhook accepted': (r) => report('webhook', r, r.status === 200) });
}

export function invitations(data) {
  const s = data.sessions[exec.scenario.iterationInTest % data.sessions.length];
  use(s);
  const res = http.post(`${WEB}/api/businesses/${data.business}/invitations`,
    JSON.stringify({ email: `invitee-${__VU}-${__ITER}-${Date.now()}@load.test`, role: 'VIEWER' }), headers(s, data.business, true));
  check(res, { 'invitation sent': (r) => report('invitation', r, r.status === 201) });
  sleep(0.5);
}

function pendingJobs() {
  const text = http.get(`${MGMT}/actuator/prometheus`, { tags: { kind: 'probe' } }).body || '';
  let total = 0;
  for (const line of text.split('\n')) {
    if (line.startsWith('insight_jobs_pending{') && (line.includes('queue="billing_events"') || line.includes('queue="mail_outbox"'))) {
      total += Number(line.split(' ').pop());
    }
  }
  return total;
}

export function teardown() {
  // How long the event worker and the mail outbox take to catch up once the load stops.
  const started = Date.now();
  sleep(16);   // let the queue metrics refresh (every 15 s)
  while (pendingJobs() > 0 && Date.now() - started < 180000) {
    sleep(5);
  }
  const seconds = (Date.now() - started) / 1000;
  queueDrain.add(seconds);
  console.log(`background queues drained in ${seconds.toFixed(0)} s (pending now: ${pendingJobs()})`);
}
