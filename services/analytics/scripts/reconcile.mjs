#!/usr/bin/env node
// Reconciles Cube's numbers with the Spring API's for the same business, windows and stores.
//
//   CUBE_URL=http://localhost:4000 API_URL=http://localhost:8080 CUBEJS_API_SECRET=... \
//     node services/analytics/scripts/reconcile.mjs
//
// Node 20+, no dependencies. The Cube JWT is signed locally (HS256) with CUBEJS_API_SECRET,
// which is read from the environment only. Exits 1 on any mismatch beyond 0.005, 2 on errors.

import { createHmac } from 'node:crypto';

const CUBE_URL = (process.env.CUBE_URL || 'http://localhost:4000').replace(/\/+$/, '');
const API_URL = (process.env.API_URL || 'http://localhost:8080').replace(/\/+$/, '');
const SECRET = process.env.CUBEJS_API_SECRET;
const TOLERANCE = 0.005;

if (!SECRET) {
  console.error('CUBEJS_API_SECRET is not set (use the same value the Cube server runs with).');
  process.exit(2);
}

// ---------------------------------------------------------------- helpers

const b64url = (value) => Buffer.from(value).toString('base64url');

function cubeToken() {
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = b64url(JSON.stringify({ iat: now, exp: now + 3600 }));
  const signature = createHmac('sha256', SECRET).update(`${header}.${payload}`).digest('base64url');
  return `${header}.${payload}.${signature}`;
}

const TOKEN = cubeToken();
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function apiGet(path, params = {}) {
  const url = new URL(API_URL + path);
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null) url.searchParams.set(key, String(value));
  }
  const res = await fetch(url);
  if (!res.ok) throw new Error(`API ${res.status} for ${url}: ${await res.text()}`);
  return res.json();
}

const preAggregationUse = { total: 0, served: 0, names: new Set() };

async function cubeLoad(query) {
  for (let attempt = 0; attempt < 120; attempt++) {
    const res = await fetch(`${CUBE_URL}/cubejs-api/v1/load`, {
      method: 'POST',
      headers: { Authorization: TOKEN, 'Content-Type': 'application/json' },
      body: JSON.stringify({ query }),
    });
    const body = await res.json().catch(() => ({}));
    if (body.error === 'Continue wait') {
      await sleep(1000);
      continue;
    }
    if (!res.ok || body.error) {
      throw new Error(`Cube ${res.status}: ${body.error || JSON.stringify(body)}\nquery: ${JSON.stringify(query)}`);
    }
    const used = Object.keys(body.usedPreAggregations || {});
    preAggregationUse.total++;
    if (used.length > 0) {
      preAggregationUse.served++;
      used.forEach((name) => preAggregationUse.names.add(name.replace(/_[a-z0-9]{8}_[a-z0-9]{8}_[a-z0-9]+$/, '')));
    }
    return body.data;
  }
  throw new Error(`Cube did not answer in time for ${JSON.stringify(query)}`);
}

const num = (value) => (value === null || value === undefined ? 0 : Number(value));
const round2 = (value) => Math.round((value + Number.EPSILON) * 100) / 100;
const addDays = (isoDate, days) => {
  const d = new Date(`${isoDate}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
};

let checks = 0;
const mismatches = [];

function compare(label, cubeValue, apiValue) {
  checks++;
  const diff = Math.abs(num(cubeValue) - num(apiValue));
  if (!(diff <= TOLERANCE)) {
    mismatches.push(`${label}: cube=${cubeValue} api=${apiValue} (diff ${diff.toFixed(4)})`);
  }
}

// Not calling process.exit() after fetch() avoids a libuv assertion on Windows (Node 24).
main().then(
  (code) => {
    process.exitCode = code;
  },
  (error) => {
    console.error(error.stack || String(error));
    process.exitCode = 2;
  },
);

async function main() {
  // -------------------------------------------------------------- setup

  const context = await apiGet('/api/dashboard/context');
  const { slug, timeZone } = context.business;
  if (!context.dataRange) {
    console.error(`Business '${slug}' has no sales; nothing to reconcile.`);
    return 2;
  }
  const { from: firstDay, to: lastDay } = context.dataRange;

  const stores = [{ id: null, label: 'all stores' }, ...context.stores.map((s) => ({ id: s.id, label: `store ${s.code}` }))];
  const windows = [
    { name: 'last 7 days', from: addDays(lastDay, -6), to: lastDay },
    { name: 'last 30 days', from: addDays(lastDay, -29), to: lastDay },
    { name: 'Jun-Aug 2026', from: '2026-06-01', to: '2026-08-31' },
    { name: 'single day', from: lastDay, to: lastDay },
    { name: 'first day', from: firstDay, to: firstDay },
    { name: 'all data', from: firstDay, to: lastDay },
  ];

  console.log(`Business ${slug} (${timeZone}), data ${firstDay}..${lastDay}, ${context.stores.length} stores`);
  console.log(`Cube ${CUBE_URL}  API ${API_URL}\n`);

  const businessFilter = { member: 'businesses.slug', operator: 'equals', values: [slug] };
  const storeFilter = (member, storeId) =>
    storeId === null ? [] : [{ member, operator: 'equals', values: [String(storeId)] }];

  // ---------------------------------------------------------------- 1. summary totals

  for (const w of windows) {
    for (const store of stores) {
      const label = `summary ${w.name} [${w.from}..${w.to}] ${store.label}`;
      const [rows, summary] = await Promise.all([
        cubeLoad({
          measures: ['orders.revenue', 'orders.count', 'orders.units', 'orders.average_order_value'],
          timeDimensions: [{ dimension: 'orders.sold_at', dateRange: [w.from, w.to] }],
          filters: [businessFilter, ...storeFilter('orders.store_id', store.id)],
          timezone: timeZone,
        }),
        apiGet('/api/dashboard/summary', { from: w.from, to: w.to, storeId: store.id }),
      ]);
      const row = rows[0] || {};
      compare(`${label} revenue`, round2(num(row['orders.revenue'])), summary.revenue.value);
      compare(`${label} orders`, row['orders.count'], summary.orders.value);
      compare(`${label} units`, row['orders.units'], summary.unitsSold.value);
      compare(`${label} AOV`, round2(num(row['orders.average_order_value'])), summary.averageOrderValue.value);
      console.log(
        `${label.padEnd(62)} revenue ${String(summary.revenue.value).padStart(11)}  orders ${String(summary.orders.value).padStart(6)}  units ${String(summary.unitsSold.value).padStart(6)}`,
      );
    }
  }

  // ---------------------------------------------------------------- 2. revenue series

  const series = [
    { granularity: 'month', from: firstDay, to: lastDay },
    { granularity: 'week', from: '2026-06-01', to: '2026-08-31' },
  ];
  for (const s of series) {
    for (const store of stores) {
      const [rows, apiSeries] = await Promise.all([
        cubeLoad({
          measures: ['orders.revenue', 'orders.count'],
          timeDimensions: [{ dimension: 'orders.sold_at', granularity: s.granularity, dateRange: [s.from, s.to] }],
          filters: [businessFilter, ...storeFilter('orders.store_id', store.id)],
          timezone: timeZone,
          order: { 'orders.sold_at': 'asc' },
        }),
        apiGet('/api/dashboard/revenue', { from: s.from, to: s.to, storeId: store.id, granularity: s.granularity }),
      ]);
      const cubeByStart = new Map(rows.map((r) => [r[`orders.sold_at.${s.granularity}`].slice(0, 10), r]));
      for (const point of apiSeries.points) {
        const row = cubeByStart.get(point.periodStart) || {};
        cubeByStart.delete(point.periodStart);
        const label = `${s.granularity} ${point.periodStart} ${store.label}`;
        compare(`${label} revenue`, round2(num(row['orders.revenue'])), point.revenue);
        compare(`${label} orders`, row['orders.count'], point.orders);
      }
      for (const start of cubeByStart.keys()) {
        mismatches.push(`${s.granularity} ${start} ${store.label}: bucket present in Cube but not in the API`);
      }
      console.log(`${`${s.granularity} revenue ${s.from}..${s.to} ${store.label}`.padEnd(62)} ${apiSeries.points.length} buckets`);
    }
  }

  // ---------------------------------------------------------------- 3. category revenue

  async function apiCategoryTotals(from, to, storeId) {
    const totals = new Map();
    for (let page = 0; ; page++) {
      const res = await apiGet('/api/products', { from, to, storeId, page, size: 100 });
      for (const item of res.items) {
        const t = totals.get(item.category) || { revenue: 0, units: 0 };
        t.revenue += num(item.revenue);
        t.units += num(item.unitsSold);
        totals.set(item.category, t);
      }
      if (page + 1 >= res.totalPages) return totals;
    }
  }

  for (const w of windows) {
    for (const store of stores) {
      const [rows, apiTotals] = await Promise.all([
        cubeLoad({
          measures: ['line_items.revenue', 'line_items.units'],
          dimensions: ['products.category'],
          timeDimensions: [{ dimension: 'line_items.sold_at', dateRange: [w.from, w.to] }],
          filters: [businessFilter, ...storeFilter('line_items.store_id', store.id)],
          timezone: timeZone,
        }),
        apiCategoryTotals(w.from, w.to, store.id),
      ]);
      const cubeByCategory = new Map(rows.map((r) => [r['products.category'], r]));
      for (const [category, t] of apiTotals) {
        const row = cubeByCategory.get(category) || {};
        cubeByCategory.delete(category);
        const label = `category ${category} ${w.name} ${store.label}`;
        compare(`${label} revenue`, round2(num(row['line_items.revenue'])), round2(t.revenue));
        compare(`${label} units`, row['line_items.units'], t.units);
      }
      for (const category of cubeByCategory.keys()) {
        mismatches.push(`category ${category} ${w.name} ${store.label}: present in Cube but not in the API`);
      }
      console.log(`${`categories ${w.name} ${store.label}`.padEnd(62)} ${apiTotals.size} categories`);
    }
  }

  // ---------------------------------------------------------------- result

  console.log(
    `\nCube queries served from pre-aggregations: ${preAggregationUse.served}/${preAggregationUse.total}` +
      (preAggregationUse.names.size ? ` (${[...preAggregationUse.names].join(', ')})` : ''),
  );
  console.log(`Checks: ${checks}, mismatches: ${mismatches.length}`);
  if (mismatches.length > 0) {
    for (const m of mismatches.slice(0, 50)) console.log(`  MISMATCH ${m}`);
    if (mismatches.length > 50) console.log(`  ... and ${mismatches.length - 50} more`);
    return 1;
  }
  console.log('OK: Cube matches the API.');
  return 0;
}
