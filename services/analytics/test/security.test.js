// Unit tests for the Cube business isolation (security.js). No dependencies:
//   node --test services/analytics/test/
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const security = require('../security');

const SECRET = 'a'.repeat(40);
const NOW = 1_800_000_000;

function sign(claims, { secret = SECRET, header = { alg: 'HS256', typ: 'JWT' } } = {}) {
  const enc = (value) => Buffer.from(JSON.stringify(value)).toString('base64url');
  const body = `${enc(header)}.${enc(claims)}`;
  const sig = crypto.createHmac('sha256', secret).update(body).digest('base64url');
  return `${body}.${sig}`;
}

const valid = (extra = {}) => ({ businessId: 7, iat: NOW, exp: NOW + 60, ...extra });

function rejects(fn, status, message) {
  assert.throws(fn, (e) => {
    assert.equal(e.status, status);
    if (message) assert.match(e.message, message);
    return true;
  });
}

test('accepts a valid token and keeps only businessId', () => {
  const ctx = security.verifyToken(sign(valid({ role: 'admin' })), SECRET, NOW);
  assert.deepEqual(ctx, { businessId: 7 });
  assert.deepEqual(security.verifyToken(`Bearer ${sign(valid())}`, SECRET, NOW), { businessId: 7 });
});

test('rejects a missing token with 401', () => {
  rejects(() => security.verifyToken(undefined, SECRET, NOW), 401);
  rejects(() => security.verifyToken('', SECRET, NOW), 401);
});

test('rejects a wrong secret, a tampered payload and a tampered signature', () => {
  rejects(() => security.verifyToken(sign(valid(), { secret: 'b'.repeat(40) }), SECRET, NOW), 403, /Invalid token/);
  const [h, , s] = sign(valid()).split('.');
  const forged = Buffer.from(JSON.stringify(valid({ businessId: 8 }))).toString('base64url');
  rejects(() => security.verifyToken(`${h}.${forged}.${s}`, SECRET, NOW), 403, /Invalid token/);
  const token = sign(valid());
  const flipped = token.slice(0, -2) + (token.endsWith('AA') ? 'BB' : 'AA');
  rejects(() => security.verifyToken(flipped, SECRET, NOW), 403, /Invalid token/);
  rejects(() => security.verifyToken('not-a-jwt', SECRET, NOW), 403);
});

test('rejects alg none and other algorithms', () => {
  const enc = (value) => Buffer.from(JSON.stringify(value)).toString('base64url');
  rejects(() => security.verifyToken(`${enc({ alg: 'none' })}.${enc(valid())}.`, SECRET, NOW), 403);
  rejects(() => security.verifyToken(sign(valid(), { header: { alg: 'HS512' } }), SECRET, NOW), 403);
});

test('requires exp, rejects expired and overly long-lived tokens', () => {
  rejects(() => security.verifyToken(sign({ businessId: 7 }), SECRET, NOW), 403, /expire/);
  rejects(() => security.verifyToken(sign(valid({ exp: NOW - 60 })), SECRET, NOW), 403, /expired/);
  rejects(() => security.verifyToken(sign(valid({ exp: NOW + 7200 })), SECRET, NOW), 403, /too long/);
  rejects(() => security.verifyToken(sign(valid({ nbf: NOW + 600 })), SECRET, NOW), 403, /not yet/);
});

test('requires a positive integer businessId', () => {
  for (const businessId of [undefined, 0, -1, 1.5, '7', null, [7], Number.MAX_SAFE_INTEGER + 2]) {
    rejects(() => security.verifyToken(sign(valid({ businessId })), SECRET, NOW), 403, /business/);
  }
});

test('fails closed without a strong secret', () => {
  rejects(() => security.verifyToken(sign(valid(), { secret: 'short' }), 'short', NOW), 403);
  rejects(() => security.verifyToken(sign(valid()), undefined, NOW), 403);
  const placeholder = 'change-me-to-a-long-random-secret';
  rejects(() => security.verifyToken(sign(valid(), { secret: placeholder }), placeholder, NOW), 403);
});

test('checkAuth reads the secret from the environment', async () => {
  const previous = process.env.CUBEJS_API_SECRET;
  process.env.CUBEJS_API_SECRET = SECRET;
  try {
    const req = {};
    const now = Math.floor(Date.now() / 1000);
    await security.checkAuth(req, sign({ businessId: 3, exp: now + 60 }));
    assert.deepEqual(req.securityContext, { businessId: 3 });
    await assert.rejects(security.checkAuth({}, undefined), (e) => e.status === 401);
  } finally {
    if (previous === undefined) delete process.env.CUBEJS_API_SECRET;
    else process.env.CUBEJS_API_SECRET = previous;
  }
});

test('queryRewrite adds a business filter for every referenced cube', async () => {
  const query = {
    measures: ['line_items.revenue'],
    dimensions: ['products.category'],
    timeDimensions: [{ dimension: 'line_items.sold_at', dateRange: ['2026-06-01', '2026-06-30'] }],
    filters: [{ or: [{ member: 'stores.code', operator: 'equals', values: ['A'] }] }],
    order: [{ id: 'line_items.revenue', desc: true }],
  };
  const out = await security.queryRewrite(query, { securityContext: { businessId: 7 } });
  const scope = out.filters.slice(1);
  assert.deepEqual(scope.map((f) => f.member).sort(), ['line_items.business_id', 'products.business_id', 'stores.business_id']);
  scope.forEach((f) => assert.deepEqual([f.operator, f.values], ['equals', ['7']]));
  assert.deepEqual(out.filters[0], query.filters[0], 'user filters are kept and ANDed');
});

test('order_categories (category report) is scoped on its own business_id', async () => {
  const ctx = { securityContext: { businessId: 7 } };
  const report = await security.queryRewrite({
    measures: ['order_categories.count', 'order_categories.revenue', 'order_categories.units', 'order_categories.data_version'],
    dimensions: ['order_categories.category'],
    timeDimensions: [{ dimension: 'order_categories.sold_at', dateRange: ['2026-06-01', '2026-06-30'] }],
    filters: [{ member: 'order_categories.store_id', operator: 'equals', values: ['3'] }],
  }, ctx);
  assert.deepEqual(report.filters.at(-1), { member: 'order_categories.business_id', operator: 'equals', values: ['7'] });
  assert.equal(report.filters.length, 2);

  // The API's self-verifying form: the period and store nested in an OR with the marker rows.
  const verified = await security.queryRewrite({
    measures: ['order_categories.data_version'],
    dimensions: ['order_categories.category'],
    filters: [{ or: [
      { and: [{ member: 'order_categories.sold_at', operator: 'inDateRange', values: ['2026-06-01', '2026-06-30'] },
        { member: 'order_categories.store_id', operator: 'equals', values: ['3'] }] },
      { member: 'order_categories.sold_at', operator: 'notSet' }] }],
  }, ctx);
  assert.deepEqual(verified.filters.slice(1), [{ member: 'order_categories.business_id', operator: 'equals', values: ['7'] }]);
  assert.deepEqual(verified.filters[0], { or: [
    { and: [{ member: 'order_categories.sold_at', operator: 'inDateRange', values: ['2026-06-01', '2026-06-30'] },
      { member: 'order_categories.store_id', operator: 'equals', values: ['3'] }] },
    { member: 'order_categories.sold_at', operator: 'notSet' }] }, 'the OR is kept whole and ANDed with the scope');
});

test('order_products (chart builder) is scoped on its own business_id, including the OR form', async () => {
  const ctx = { securityContext: { businessId: 7 } };
  const byProduct = await security.queryRewrite({
    measures: ['order_products.count', 'order_products.revenue', 'order_products.units', 'order_products.data_version'],
    dimensions: ['order_products.product_id'],
    timeDimensions: [{ dimension: 'order_products.sold_at', dateRange: ['2026-06-01', '2026-06-30'] }],
    filters: [
      { member: 'order_products.store_id', operator: 'equals', values: ['3', '4'] },
      { member: 'order_products.category', operator: 'equals', values: ['Tops'] },
    ],
  }, ctx);
  assert.deepEqual(byProduct.filters.at(-1), { member: 'order_products.business_id', operator: 'equals', values: ['7'] });
  assert.equal(byProduct.filters.length, 3);

  // Distinct orders of filtered items, with the marker rows in an OR (runs on PostgreSQL).
  const distinct = await security.queryRewrite({
    measures: ['order_products.distinct_orders', 'order_products.data_version'],
    timeDimensions: [{ dimension: 'order_products.sold_at', granularity: 'week' }],
    filters: [{ or: [
      { and: [{ member: 'order_products.sold_at', operator: 'inDateRange', values: ['2026-06-01', '2026-06-30'] },
        { member: 'order_products.product_id', operator: 'equals', values: ['12'] }] },
      { member: 'order_products.sold_at', operator: 'notSet' }] }],
  }, ctx);
  assert.deepEqual(distinct.filters.slice(1), [{ member: 'order_products.business_id', operator: 'equals', values: ['7'] }]);

  // A query mixing cubes gets one scope filter per cube.
  const mixed = await security.queryRewrite({ measures: ['order_products.revenue', 'orders.count'] }, ctx);
  assert.deepEqual(mixed.filters.map((f) => f.member).sort(), ['order_products.business_id', 'orders.business_id']);
});

test('order_products cannot be widened to another business by a filter', async () => {
  const out = await security.queryRewrite({
    measures: ['order_products.revenue'],
    filters: [{ or: [{ member: 'order_products.business_id', operator: 'equals', values: ['8'] },
      { member: 'order_products.sold_at', operator: 'notSet' }] }],
  }, { securityContext: { businessId: 7 } });
  // The caller's OR is kept, and the mandatory business filter is ANDed next to it.
  assert.equal(out.filters.length, 2);
  assert.deepEqual(out.filters[1], { member: 'order_products.business_id', operator: 'equals', values: ['7'] });
});

test('every cube of the model has a scope member', () => {
  const fs = require('node:fs');
  const path = require('node:path');
  const dir = path.join(__dirname, '..', 'model', 'cubes');
  const names = fs.readdirSync(dir).filter((f) => f.endsWith('.yml'))
    .flatMap((f) => [...fs.readFileSync(path.join(dir, f), 'utf8').matchAll(/^ {2}- name: (\w+)\r?$/gm)].map((m) => m[1]));
  assert.deepEqual([...names].sort(), Object.keys(security.SCOPE_MEMBER).sort());
});

test('a filter naming another business cannot widen the result', async () => {
  const query = {
    measures: ['orders.revenue'],
    filters: [{ or: [{ member: 'orders.business_id', operator: 'equals', values: ['8'] },
      { member: 'orders.business_id', operator: 'notEquals', values: ['0'] }] }],
  };
  const out = await security.queryRewrite(query, { securityContext: { businessId: 7 } });
  assert.deepEqual(out.filters.at(-1), { member: 'orders.business_id', operator: 'equals', values: ['7'] });
  assert.equal(out.filters.length, 2);
});

test('queryRewrite rejects queries it cannot scope', async () => {
  const ctx = { securityContext: { businessId: 7 } };
  await assert.rejects(security.queryRewrite({ measures: ['secret_cube.count'] }, ctx), (e) => e.status === 403);
  await assert.rejects(security.queryRewrite({}, ctx), (e) => e.status === 403);
  await assert.rejects(security.queryRewrite({ measures: [{ expression: 'x' }] }, ctx), (e) => e.status === 403);
  await assert.rejects(security.queryRewrite({ measures: ['orders.count'], filters: [{ foo: 1 }] }, ctx), (e) => e.status === 403);
  await assert.rejects(security.queryRewrite({ measures: ['orders.count'] }, { securityContext: {} }), (e) => e.status === 403);
  await assert.rejects(security.queryRewrite({ measures: ['orders.count'] }, undefined), (e) => e.status === 403);
});

test('app id is per business; refresh and orchestrator ids are shared', () => {
  assert.equal(security.contextToAppId({ securityContext: { businessId: 7 } }), 'insight_business_7');
  assert.equal(security.contextToAppId({ securityContext: { businessId: 8 } }), 'insight_business_8');
  assert.equal(security.contextToAppId(null), 'insight_refresh');
  assert.equal(security.contextToAppId({ securityContext: {} }), 'insight_refresh');
  assert.equal(security.contextToOrchestratorId(), 'insight_shared');
});

test('the SQL API is refused and API scopes exclude sql, graphql and jobs', async () => {
  await assert.rejects(security.checkSqlAuth(), (e) => e.status === 403);
  assert.deepEqual(await security.contextToApiScopes(), ['data', 'meta']);
});
