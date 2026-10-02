#!/usr/bin/env node
// Cube report trial (docs/cube-trial.md): compares the Cube report engine with the SQL engine under
// concurrent load, imports, a cold start and an outage, and writes the measurements.
//
//   node services/analytics/scripts/cube-trial.mjs prepare   # once: trial businesses + data
//   node services/analytics/scripts/cube-trial.mjs run       # the scenarios; results in OUT_DIR
//
// Node 20+, no dependencies. Needs two API instances on the SAME database (sessions are shared):
// SQL_API with REPORTS_ENGINE=sql and CUBE_API with REPORTS_ENGINE=cube, and a verified account
// (TRIAL_EMAIL / TRIAL_PASSWORD) that owns the trial businesses. The cold-start and outage scenarios
// restart and stop the Cube container (CUBE_CONTAINER) with the docker CLI; SKIP_DOCKER=1 skips them.
// Exit code: 0 when every acceptance criterion passed, 1 otherwise, 2 on setup errors.

import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const env = (name, fallback) => (process.env[name] ?? fallback);
const SQL_API = env('SQL_API', 'http://localhost:8081').replace(/\/+$/, '');
const CUBE_API = env('CUBE_API', 'http://localhost:8082').replace(/\/+$/, '');
const EMAIL = env('TRIAL_EMAIL', '');
const PASSWORD = env('TRIAL_PASSWORD', '');
const CUBE_CONTAINER = env('CUBE_CONTAINER', 'infra-cube-1');
const SKIP_DOCKER = env('SKIP_DOCKER', '') === '1';
const SECONDS = Number(env('TRIAL_SECONDS', '60'));
const CONCURRENCY = Number(env('TRIAL_CONCURRENCY', '4'));
const IMPORT_EVERY_MS = Number(env('TRIAL_IMPORT_EVERY_SECONDS', '10')) * 1000;
const OUT_DIR = env('OUT_DIR', 'cube-trial-results');

// Acceptance criteria (docs/cube-trial.md, "Pass criteria"); override with environment variables.
const LIMITS = {
  cubeP95Ms: Number(env('LIMIT_CUBE_P95_MS', '2000')),
  max503Share: Number(env('LIMIT_503_SHARE', '0.05')),
  freshWithinMs: Number(env('LIMIT_FRESH_MS', '60000')),
  coldStartMs: Number(env('LIMIT_COLD_START_MS', '180000')),
  recoveryMs: Number(env('LIMIT_RECOVERY_MS', '180000')),
};

const BUSINESSES = [
  { name: 'Trial New York', currency: 'USD', timeZone: 'America/New_York', prefix: 'NY' },
  { name: 'Trial Paris', currency: 'EUR', timeZone: 'Europe/Paris', prefix: 'PA' },
  { name: 'Trial Auckland', currency: 'NZD', timeZone: 'Pacific/Auckland', prefix: 'AK' },
];
const CATEGORIES = ['Tops', 'Bottoms', 'Outerwear', 'Accessories'];
const WINDOWS = [
  ['all data', '2026-01-01', '2026-12-31'],
  ['second quarter', '2026-04-01', '2026-06-30'],
  ['spring DST changes', '2026-03-07', '2026-04-06'],
  ['autumn DST changes', '2026-09-26', '2026-10-31'],
  ['month end', '2026-05-31', '2026-06-01'],
  ['no sales', '2025-01-01', '2025-03-31'],
];

// ------------------------------------------------------------------ HTTP with a shared session

let cookies = {};
let xsrf = '';

function remember(response) {
  for (const header of response.headers.getSetCookie()) {
    const [pair] = header.split(';');
    const index = pair.indexOf('=');
    const name = pair.slice(0, index).trim();
    const value = pair.slice(index + 1).trim();
    if (value === '') delete cookies[name];
    else cookies[name] = value;
  }
  if (cookies['XSRF-TOKEN']) xsrf = decodeURIComponent(cookies['XSRF-TOKEN']);
}

async function call(base, path, { method = 'GET', business = null, json = undefined, form = undefined } = {}) {
  const headers = { Cookie: Object.entries(cookies).map(([k, v]) => `${k}=${v}`).join('; ') };
  if (business !== null) headers['X-Business-Id'] = String(business);
  if (method !== 'GET') headers['X-XSRF-TOKEN'] = xsrf;
  let body;
  if (json !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(json);
  } else if (form !== undefined) {
    body = form;
  }
  const started = performance.now();
  const response = await fetch(base + path, { method, headers, body });
  const buffer = Buffer.from(await response.arrayBuffer());
  const ms = performance.now() - started;
  remember(response);
  return {
    status: response.status,
    ms,
    engine: response.headers.get('x-report-engine'),
    retryAfter: response.headers.get('retry-after'),
    type: response.headers.get('content-type') || '',
    buffer,
    text: buffer.toString('utf8'),
  };
}

async function signIn() {
  if (!EMAIL || !PASSWORD) {
    console.error('Set TRIAL_EMAIL and TRIAL_PASSWORD (a verified account; see docs/cube-trial.md).');
    process.exit(2);
  }
  await call(CUBE_API, '/api/session');
  const response = await call(CUBE_API, '/api/auth/sign-in', { method: 'POST', json: { email: EMAIL, password: PASSWORD } });
  if (response.status !== 200) {
    console.error(`Sign-in failed (${response.status}): ${response.text.slice(0, 200)}`);
    process.exit(2);
  }
}

async function trialBusinesses() {
  const list = JSON.parse((await call(CUBE_API, '/api/businesses')).text);
  return BUSINESSES.map((spec) => ({ ...spec, id: list.find((b) => b.name === spec.name)?.businessId ?? null }));
}

// ------------------------------------------------------------------ prepare

/** Deterministic pseudo-random numbers, so every trial starts from the same data. */
function random(seed) {
  let state = seed >>> 0;
  return () => {
    state = (state * 1664525 + 1013904223) >>> 0;
    return state / 2 ** 32;
  };
}

function historyCsv(spec, seed) {
  const next = random(seed);
  const rows = ['store_code,receipt_number,sold_at,sku,quantity,unit_price'];
  const start = Date.UTC(2026, 0, 1);
  const days = 270; // January to September 2026
  for (let receipt = 1; receipt <= 1500; receipt++) {
    const day = new Date(start + Math.floor(next() * days) * 86_400_000);
    // Local times between 09:00 and 20:59 (never inside a DST gap); every 50th at 23:30 (day edge).
    const hour = receipt % 50 === 0 ? 23 : 9 + Math.floor(next() * 12);
    const minute = receipt % 50 === 0 ? 30 : Math.floor(next() * 60);
    const soldAt = `${day.toISOString().slice(0, 10)}T${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}:00`;
    const store = `${spec.prefix}${1 + Math.floor(next() * 3)}`;
    const lines = 1 + Math.floor(next() * 3);
    const used = new Set();
    for (let line = 0; line < lines; line++) {
      const product = Math.floor(next() * 12);
      if (used.has(product)) continue;
      used.add(product);
      const base = 15 + product * 5;
      // Prices change over time: historical prices must be kept.
      const price = (day.getUTCMonth() >= 5 ? base * 1.1 : base).toFixed(2);
      rows.push([store, `H-${receipt}`, soldAt, `${spec.prefix}-P${product}`, 1 + Math.floor(next() * 3), price].join(','));
    }
  }
  return rows.join('\n') + '\n';
}

async function prepare() {
  await signIn();
  for (const [index, spec] of (await trialBusinesses()).entries()) {
    let id = spec.id;
    if (id === null) {
      const created = await call(CUBE_API, '/api/businesses', { method: 'POST', json: { name: spec.name, currency: spec.currency, timeZone: spec.timeZone } });
      if (created.status !== 201) throw new Error(`Creating ${spec.name}: ${created.status} ${created.text}`);
      id = JSON.parse(created.text).businessId;
    }
    const context = JSON.parse((await call(CUBE_API, '/api/dashboard/context', { business: id })).text);
    if (context.dataRange) {
      console.log(`${spec.name} (${id}) already has data; left as it is.`);
      continue;
    }
    for (let s = 1; s <= 3; s++) {
      await call(CUBE_API, '/api/stores', { method: 'POST', business: id, json: { code: `${spec.prefix}${s}`, name: `${spec.name} store ${s}`, city: null } });
    }
    for (let p = 0; p < 12; p++) {
      await call(CUBE_API, '/api/products', { method: 'POST', business: id,
        json: { sku: `${spec.prefix}-P${p}`, name: `Product ${p}`, category: CATEGORIES[p % CATEGORIES.length], listPrice: 15 + p * 5 } });
    }
    const form = new FormData();
    form.append('file', new Blob([historyCsv(spec, 20261002 + index)], { type: 'text/csv' }), `${spec.prefix}-history.csv`);
    const imported = await call(CUBE_API, '/api/imports?dryRun=false', { method: 'POST', business: id, form });
    if (imported.status !== 200 || !imported.text.includes('IMPORTED')) throw new Error(`Import into ${spec.name}: ${imported.text.slice(0, 300)}`);
    console.log(`${spec.name} (${id}, ${spec.timeZone}): 3 stores, 12 products, history imported.`);
  }
  console.log('Prepared. Add every trial time zone to CUBEJS_SCHEDULED_REFRESH_TIMEZONES (docs/cube-trial.md).');
}

// ------------------------------------------------------------------ measurements

const percentile = (values, p) => {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return Math.round(sorted[Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length))]);
};
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const results = { startedAt: new Date().toISOString(), sqlApi: SQL_API, cubeApi: CUBE_API, limits: LIMITS, scenarios: {} };
const verdicts = [];
const verdict = (scenario, criterion, pass, detail) => {
  verdicts.push({ scenario, criterion, pass, detail });
  console.log(`${pass ? 'PASS' : 'FAIL'}  [${scenario}] ${criterion}${detail ? ` (${detail})` : ''}`);
};

function reportPaths(business) {
  const paths = [];
  for (const [, from, to] of WINDOWS) {
    for (const store of [null, ...business.stores]) {
      for (const kind of ['monthly', 'categories']) {
        paths.push(`/api/reports/${kind}?from=${from}&to=${to}${store ? `&storeId=${store}` : ''}`);
      }
    }
  }
  return paths;
}

const is503 = (r) => r.status === 503 && r.type.includes('application/problem+json') && r.retryAfter !== null;

/** Scenario 1: every report, window and store answers the same through both engines. */
async function correctness(businesses) {
  let compared = 0;
  const mismatches = [];
  for (const business of businesses) {
    for (const path of reportPaths(business)) {
      for (const suffix of ['', '.csv']) {
        const full = suffix ? path.replace('?', `${suffix}?`) : path;
        const [cube, sql] = [await call(CUBE_API, full, { business: business.id }), await call(SQL_API, full, { business: business.id })];
        compared++;
        if (cube.status !== 200 || cube.engine !== 'cube' || !cube.buffer.equals(sql.buffer)) {
          mismatches.push(`${business.name} ${full}: cube ${cube.status}, sql ${sql.status}`);
        }
      }
    }
  }
  results.scenarios.correctness = { compared, mismatches };
  verdict('correctness', 'Cube JSON and CSV equal SQL for every business, window and store', mismatches.length === 0,
    `${compared} compared, ${mismatches.length} mismatches`);
}

/** Runs report requests from CONCURRENCY workers per business for `seconds`. */
async function load(base, businesses, seconds, onTick) {
  const stats = { requests: 0, ok: 0, unavailable: 0, otherErrors: [], latencies: [], byBusiness: {} };
  const until = Date.now() + seconds * 1000;
  const worker = async (business, seed) => {
    const paths = reportPaths(business);
    const next = random(seed);
    while (Date.now() < until) {
      const response = await call(base, paths[Math.floor(next() * paths.length)], { business: business.id });
      stats.requests++;
      stats.byBusiness[business.name] = (stats.byBusiness[business.name] ?? 0) + 1;
      if (response.status === 200) {
        stats.ok++;
        stats.latencies.push(response.ms);
      } else if (is503(response)) {
        stats.unavailable++;
      } else {
        stats.otherErrors.push(`${response.status} ${response.text.slice(0, 120)}`);
      }
    }
  };
  const workers = businesses.flatMap((business, b) =>
    Array.from({ length: CONCURRENCY }, (_, w) => worker(business, 1000 * b + w + 1)));
  if (onTick) workers.push(onTick(until));
  await Promise.all(workers);
  return {
    requests: stats.requests, ok: stats.ok, unavailable: stats.unavailable, otherErrors: stats.otherErrors.slice(0, 10),
    otherErrorCount: stats.otherErrors.length, byBusiness: stats.byBusiness,
    p50: percentile(stats.latencies, 50), p95: percentile(stats.latencies, 95), p99: percentile(stats.latencies, 99),
    perSecond: Math.round((stats.requests / seconds) * 10) / 10,
  };
}

/** Imports two receipts into a business, then waits until its report shows them (or gives up). */
async function importAndWaitForFresh(business, sequence) {
  const month = new Date().toISOString().slice(0, 7);
  const day = `${month}-01`;
  const receipt = `TRIAL-${Date.now()}-${sequence}`;
  const csv = `store_code,receipt_number,sold_at,sku,quantity,unit_price\n${business.prefix}1,${receipt}-a,${day}T12:00:00,${business.prefix}-P0,2,11.11\n${business.prefix}2,${receipt}-b,${day}T23:30:00,${business.prefix}-P1,1,22.22\n`;
  const form = new FormData();
  form.append('file', new Blob([csv], { type: 'text/csv' }), `${receipt}.csv`);
  const imported = await call(CUBE_API, '/api/imports?dryRun=false', { method: 'POST', business: business.id, form });
  const committedAt = performance.now();
  if (imported.status !== 200 || !imported.text.includes('IMPORTED')) return { business: business.name, error: imported.text.slice(0, 200) };
  const path = `/api/reports/monthly?from=${day}&to=${day}`;
  const statuses = [];
  while (performance.now() - committedAt < LIMITS.freshWithinMs) {
    const cube = await call(CUBE_API, path, { business: business.id });
    statuses.push(cube.status);
    if (cube.status === 200) {
      // Imports run one at a time and nothing else writes, so SQL shows exactly the committed data:
      // a Cube answer that differs from it is stale, which must never happen.
      const sql = await call(SQL_API, path, { business: business.id });
      if (cube.text === sql.text) return { business: business.name, freshMs: Math.round(performance.now() - committedAt), statuses };
      return { business: business.name, stale: true, statuses, cube: cube.text.slice(0, 200), sql: sql.text.slice(0, 200) };
    } else if (!is503(cube)) {
      return { business: business.name, error: `${cube.status} ${cube.text.slice(0, 200)}`, statuses };
    }
    await sleep(500);
  }
  return { business: business.name, timedOut: true, statuses };
}

/** Scenario 2: concurrent businesses on both engines, with imports during the Cube run. */
async function concurrency(businesses) {
  const sql = await load(SQL_API, businesses, Math.max(10, Math.round(SECONDS / 2)));
  const imports = [];
  const cube = await load(CUBE_API, businesses, SECONDS, async (until) => {
    let sequence = 0;
    while (Date.now() + IMPORT_EVERY_MS < until) {
      await sleep(IMPORT_EVERY_MS);
      imports.push(await importAndWaitForFresh(businesses[sequence % businesses.length], sequence));
      sequence++;
    }
  });
  results.scenarios.concurrency = { concurrencyPerBusiness: CONCURRENCY, sql, cube, imports };
  const share503 = cube.requests ? cube.unavailable / cube.requests : 0;
  const fresh = imports.filter((i) => i.freshMs !== undefined).map((i) => i.freshMs);
  console.log(`  SQL : ${sql.requests} requests, ${sql.perSecond}/s, p50 ${sql.p50} ms, p95 ${sql.p95} ms`);
  console.log(`  Cube: ${cube.requests} requests, ${cube.perSecond}/s, p50 ${cube.p50} ms, p95 ${cube.p95} ms, 503 ${cube.unavailable}`);
  verdict('concurrency', 'no errors other than 503 problem details', cube.otherErrorCount === 0 && sql.otherErrorCount === 0,
    `cube ${cube.otherErrorCount}, sql ${sql.otherErrorCount}`);
  verdict('concurrency', `Cube p95 latency <= ${LIMITS.cubeP95Ms} ms`, cube.p95 !== null && cube.p95 <= LIMITS.cubeP95Ms, `p95 ${cube.p95} ms`);
  verdict('concurrency', `503 share <= ${LIMITS.max503Share * 100}%`, share503 <= LIMITS.max503Share, `${(share503 * 100).toFixed(1)}%`);
  verdict('imports during reporting', 'no stale figures after an import', imports.every((i) => !i.stale), `${imports.filter((i) => i.stale).length} stale`);
  verdict('imports during reporting', `every import visible within ${LIMITS.freshWithinMs / 1000} s`,
    imports.length > 0 && imports.every((i) => i.freshMs !== undefined),
    `${fresh.length}/${imports.length}, median ${percentile(fresh, 50)} ms, max ${fresh.length ? Math.max(...fresh) : '-'} ms`);
}

function docker(...args) {
  return execFileSync('docker', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
}

/** Polls every business until its report answers 200; returns per-business timings and statuses seen. */
async function waitUntilAnswering(businesses, limitMs) {
  const started = performance.now();
  const outcome = {};
  for (const business of businesses) {
    const statuses = new Set();
    while (performance.now() - started < limitMs) {
      const r = await call(CUBE_API, `/api/reports/monthly?from=2026-01-01&to=2026-12-31`, { business: business.id });
      if (r.status === 200) {
        const sql = await call(SQL_API, `/api/reports/monthly?from=2026-01-01&to=2026-12-31`, { business: business.id });
        outcome[business.name] = { ms: Math.round(performance.now() - started), statuses: [...statuses], equalsSql: r.text === sql.text };
        break;
      }
      statuses.add(is503(r) ? `503 (Retry-After ${r.retryAfter})` : `${r.status} UNEXPECTED`);
      await sleep(2000);
    }
    outcome[business.name] ??= { ms: null, statuses: [...statuses] };
  }
  return outcome;
}

/** Scenario 3: Cube restarted (cold caches, rollups rebuilt). */
async function coldStart(businesses) {
  docker('restart', CUBE_CONTAINER);
  const outcome = await waitUntilAnswering(businesses, LIMITS.coldStartMs);
  results.scenarios.coldStart = outcome;
  const values = Object.values(outcome);
  verdict('cold start', `every business answers within ${LIMITS.coldStartMs / 1000} s`, values.every((v) => v.ms !== null),
    values.map((v) => v.ms).join(', ') + ' ms');
  verdict('cold start', 'only 503 problem details before that, then the same figures as SQL',
    values.every((v) => v.statuses.every((s) => s.startsWith('503')) && v.equalsSql), JSON.stringify(values.map((v) => v.statuses)));
}

/** Scenario 4: Cube stopped, then started again. */
async function outage(businesses) {
  docker('stop', CUBE_CONTAINER);
  const business = businesses[0];
  const answers = {};
  for (const path of ['/api/reports/monthly?from=2026-01-01&to=2026-12-31', '/api/reports/categories.csv?from=2026-01-01&to=2026-12-31',
    '/api/reports/monthly.pdf?from=2026-01-01&to=2026-12-31']) {
    const r = await call(CUBE_API, path, { business: business.id });
    answers[path.split('?')[0]] = { status: r.status, retryAfter: r.retryAfter, problem: r.type.includes('application/problem+json'),
      detail: r.type.includes('json') ? JSON.parse(r.text).detail : null, ms: Math.round(r.ms) };
  }
  docker('start', CUBE_CONTAINER);
  const recovery = await waitUntilAnswering(businesses, LIMITS.recoveryMs);
  results.scenarios.outage = { whileDown: answers, recovery };
  verdict('503 recovery', 'JSON, CSV and PDF answer 503 problem details with Retry-After while Cube is down',
    Object.values(answers).every((a) => a.status === 503 && a.problem && a.retryAfter !== null),
    Object.entries(answers).map(([p, a]) => `${p} ${a.status}/${a.retryAfter}`).join(', '));
  const values = Object.values(recovery);
  verdict('503 recovery', `reports recover within ${LIMITS.recoveryMs / 1000} s with the same figures as SQL`,
    values.every((v) => v.ms !== null && v.equalsSql), values.map((v) => v.ms).join(', ') + ' ms');
}

async function run() {
  await signIn();
  const businesses = (await trialBusinesses()).filter((b) => b.id !== null);
  if (businesses.length < BUSINESSES.length) {
    console.error('Trial businesses are missing: run "prepare" first.');
    process.exit(2);
  }
  for (const business of businesses) {
    const context = JSON.parse((await call(SQL_API, '/api/dashboard/context', { business: business.id })).text);
    business.stores = context.stores.map((s) => s.id);
  }
  results.businesses = businesses.map(({ id, name, timeZone }) => ({ id, name, timeZone }));
  console.log(`Businesses: ${businesses.map((b) => `${b.name} (${b.timeZone})`).join(', ')}`);

  await correctness(businesses);
  await concurrency(businesses);
  if (SKIP_DOCKER) {
    console.log('SKIP_DOCKER=1: cold start and outage scenarios skipped.');
  } else {
    await coldStart(businesses);
    await outage(businesses);
  }

  results.finishedAt = new Date().toISOString();
  results.verdicts = verdicts;
  mkdirSync(OUT_DIR, { recursive: true });
  const stamp = results.startedAt.replace(/[:.]/g, '-');
  writeFileSync(join(OUT_DIR, `cube-trial-${stamp}.json`), JSON.stringify(results, null, 2));
  const lines = [`# Cube trial ${results.startedAt}`, '', `SQL API ${SQL_API}, Cube API ${CUBE_API}`, '',
    '| Scenario | Criterion | Result | Detail |', '|---|---|---|---|',
    ...verdicts.map((v) => `| ${v.scenario} | ${v.criterion} | ${v.pass ? 'PASS' : 'FAIL'} | ${v.detail ?? ''} |`)];
  const c = results.scenarios.concurrency;
  if (c) {
    lines.push('', '| Engine | Requests | Per second | p50 ms | p95 ms | p99 ms | 503 |', '|---|---|---|---|---|---|---|',
      `| SQL | ${c.sql.requests} | ${c.sql.perSecond} | ${c.sql.p50} | ${c.sql.p95} | ${c.sql.p99} | ${c.sql.unavailable} |`,
      `| Cube | ${c.cube.requests} | ${c.cube.perSecond} | ${c.cube.p50} | ${c.cube.p95} | ${c.cube.p99} | ${c.cube.unavailable} |`);
  }
  writeFileSync(join(OUT_DIR, `cube-trial-${stamp}.md`), lines.join('\n') + '\n');
  const passed = verdicts.every((v) => v.pass);
  console.log(`\n${verdicts.filter((v) => v.pass).length}/${verdicts.length} criteria passed; results in ${OUT_DIR}`);
  process.exit(passed ? 0 : 1);
}

const mode = process.argv[2];
if (mode === 'prepare') await prepare();
else if (mode === 'run') await run();
else {
  console.error('Usage: cube-trial.mjs prepare | run   (see docs/cube-trial.md)');
  process.exit(2);
}
