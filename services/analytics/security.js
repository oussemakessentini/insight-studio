// Business isolation for the Cube API (see docs/analytics.md, "Security model").
//
// Every request must carry a short-lived HS256 JWT signed with CUBEJS_API_SECRET whose
// `businessId` claim names the one business the caller may read. The Spring API mints these
// tokens from the resolved membership; the browser never talks to Cube.
//
//  - checkAuth      verifies the token and keeps only { businessId } as the security context;
//  - queryRewrite   ANDs `<cube>.business_id = businessId` onto every query for every cube the
//                   query touches, and rejects queries that reference anything it cannot scope;
//  - contextToAppId gives each business its own app id (compiled model + query cache identity);
//  - checkSqlAuth   refuses the SQL (Postgres wire) API, which is not used.
//
// Plain CommonJS with no dependencies beyond Node's crypto so it can be unit-tested outside
// the Cube image (services/analytics/test/security.test.js).

const crypto = require('node:crypto');

let CubejsHandlerError;
try {
  ({ CubejsHandlerError } = require('@cubejs-backend/api-gateway'));
} catch {
  // Outside the Cube image (unit tests): same shape, so tests can assert status codes.
  CubejsHandlerError = class CubejsHandlerError extends Error {
    constructor(status, type, message) {
      super(message || type);
      this.status = status;
      this.type = type;
    }
  };
}

/** Shortest secret accepted, in characters (HS256 wants at least 256 bits of key). */
const MIN_SECRET_LENGTH = 32;
/** Longest token lifetime accepted: tokens further in the future are refused. */
const MAX_TOKEN_LIFETIME_SECONDS = 3600;
/** Clock skew tolerated between the token issuer and Cube. */
const CLOCK_SKEW_SECONDS = 30;

/**
 * Member that scopes each cube to one business. A query may only reference cubes listed here;
 * adding a cube to the model means adding it here (queries on it are rejected until then).
 */
const SCOPE_MEMBER = {
  orders: 'orders.business_id',
  line_items: 'line_items.business_id',
  order_categories: 'order_categories.business_id',
  stores: 'stores.business_id',
  products: 'products.business_id',
  businesses: 'businesses.id',
};

const unauthorized = (message) => new CubejsHandlerError(401, 'Unauthorized', message);
const forbidden = (message) => new CubejsHandlerError(403, 'Forbidden', message);

function decodeSegment(segment) {
  if (!/^[A-Za-z0-9_-]+$/.test(segment)) throw new Error('not base64url');
  return JSON.parse(Buffer.from(segment, 'base64url').toString('utf8'));
}

/**
 * Verifies an HS256 JWT and returns the trusted security context { businessId }.
 * Throws a 401 CubejsHandlerError for a missing token and 403 for any invalid one.
 */
function verifyToken(token, secret, nowSeconds = Math.floor(Date.now() / 1000)) {
  if (typeof secret !== 'string' || secret.length < MIN_SECRET_LENGTH || secret.startsWith('change-me')) {
    // Fail closed: a missing, weak or placeholder (infra/.env.example) secret must never make tokens easier to forge.
    throw forbidden('Cube is not configured with a valid CUBEJS_API_SECRET');
  }
  if (typeof token !== 'string' || token.length === 0) {
    throw unauthorized('Authorization header is required');
  }
  const raw = token.startsWith('Bearer ') ? token.slice('Bearer '.length) : token;
  const parts = raw.split('.');
  if (parts.length !== 3) throw forbidden('Invalid token');

  let header;
  let claims;
  try {
    header = decodeSegment(parts[0]);
    claims = decodeSegment(parts[1]);
  } catch {
    throw forbidden('Invalid token');
  }
  // Only HS256: rejects "none" and algorithm-confusion tricks.
  if (!header || header.alg !== 'HS256' || (header.typ !== undefined && header.typ !== 'JWT')) {
    throw forbidden('Invalid token');
  }

  const expected = crypto.createHmac('sha256', secret).update(`${parts[0]}.${parts[1]}`).digest();
  let actual;
  try {
    if (!/^[A-Za-z0-9_-]+$/.test(parts[2])) throw new Error('not base64url');
    actual = Buffer.from(parts[2], 'base64url');
  } catch {
    throw forbidden('Invalid token');
  }
  if (actual.length !== expected.length || !crypto.timingSafeEqual(actual, expected)) {
    throw forbidden('Invalid token');
  }

  if (!claims || typeof claims !== 'object' || Array.isArray(claims)) throw forbidden('Invalid token');
  const { exp, nbf, businessId } = claims;
  if (!Number.isInteger(exp)) throw forbidden('Token must expire');
  if (exp <= nowSeconds - CLOCK_SKEW_SECONDS) throw forbidden('Token expired');
  if (exp > nowSeconds + MAX_TOKEN_LIFETIME_SECONDS + CLOCK_SKEW_SECONDS) {
    throw forbidden('Token lifetime too long');
  }
  if (nbf !== undefined && (!Number.isInteger(nbf) || nbf > nowSeconds + CLOCK_SKEW_SECONDS)) {
    throw forbidden('Token not yet valid');
  }
  if (!Number.isSafeInteger(businessId) || businessId <= 0) {
    throw forbidden('Token must name a business');
  }
  // Keep only what the rest of the pipeline may rely on.
  return { businessId };
}

/** Cube `checkAuth`: sets req.securityContext from a verified token, or throws. */
async function checkAuth(req, authorization) {
  req.securityContext = verifyToken(authorization, process.env.CUBEJS_API_SECRET);
}

function businessIdOf(securityContext) {
  const id = securityContext && securityContext.businessId;
  if (!Number.isSafeInteger(id) || id <= 0) {
    throw forbidden('Query has no business scope');
  }
  return id;
}

function memberName(value, where) {
  if (typeof value !== 'string') {
    // Member expressions (SQL API) and anything else we cannot inspect are refused.
    throw forbidden(`Unsupported ${where} in query`);
  }
  return value;
}

function collectFilterMembers(filters, add) {
  if (filters === undefined || filters === null) return;
  if (!Array.isArray(filters)) throw forbidden('Unsupported filters in query');
  for (const filter of filters) {
    if (!filter || typeof filter !== 'object') throw forbidden('Unsupported filters in query');
    if (filter.or !== undefined) collectFilterMembers(filter.or, add);
    if (filter.and !== undefined) collectFilterMembers(filter.and, add);
    if (filter.member !== undefined) add(memberName(filter.member, 'filter'));
    if (filter.dimension !== undefined) add(memberName(filter.dimension, 'filter'));
    if (filter.or === undefined && filter.and === undefined
        && filter.member === undefined && filter.dimension === undefined) {
      throw forbidden('Unsupported filters in query');
    }
  }
}

/** Every cube name a (normalized or raw) REST query references. */
function referencedCubes(query) {
  const cubes = new Set();
  const add = (member) => {
    const cube = member.split('.')[0];
    if (!cube) throw forbidden('Unsupported member in query');
    cubes.add(cube);
  };
  for (const key of ['measures', 'dimensions', 'segments']) {
    const list = query[key];
    if (list === undefined || list === null) continue;
    if (!Array.isArray(list)) throw forbidden(`Unsupported ${key} in query`);
    list.forEach((m) => add(memberName(m, key)));
  }
  const timeDimensions = query.timeDimensions || [];
  if (!Array.isArray(timeDimensions)) throw forbidden('Unsupported timeDimensions in query');
  timeDimensions.forEach((td) => add(memberName(td && td.dimension, 'time dimension')));
  collectFilterMembers(query.filters, add);
  if (query.order) {
    const entries = Array.isArray(query.order) ? query.order : Object.entries(query.order);
    for (const entry of entries) {
      const id = Array.isArray(entry) ? entry[0] : entry && entry.id;
      add(memberName(id, 'order'));
    }
  }
  return cubes;
}

/**
 * Cube `queryRewrite`: adds a mandatory `business_id = <token businessId>` filter for every
 * referenced cube. Top-level filters are ANDed, so user filters can only narrow the result.
 */
function scopeQuery(query, securityContext) {
  const businessId = businessIdOf(securityContext);
  if (!query || typeof query !== 'object') throw forbidden('Invalid query');
  const cubes = referencedCubes(query);
  if (cubes.size === 0) throw forbidden('Query references no cube');
  const scopeFilters = [];
  for (const cube of cubes) {
    const member = SCOPE_MEMBER[cube];
    if (!member) throw forbidden(`Cube '${cube}' cannot be scoped to a business`);
    scopeFilters.push({ member, operator: 'equals', values: [String(businessId)] });
  }
  return { ...query, filters: [...(query.filters || []), ...scopeFilters] };
}

async function queryRewrite(query, context) {
  return scopeQuery(query, context && context.securityContext);
}

/**
 * One app id per business: compiled model and in-memory caches are keyed by it. Requests
 * without a business never pass checkAuth; only the background refresh (no security context)
 * gets the shared refresh id.
 */
function contextToAppId(context) {
  const securityContext = context && context.securityContext;
  const id = securityContext && securityContext.businessId;
  return Number.isSafeInteger(id) && id > 0 ? `insight_business_${id}` : 'insight_refresh';
}

/**
 * One shared orchestrator: rollups are shared across businesses and carry business_id as a
 * dimension, so one refresh builds them for everyone and the scope filter selects the rows.
 * Result-cache keys include the generated SQL, which contains the business filter, so cached
 * results are never shared between businesses.
 */
function contextToOrchestratorId() {
  return 'insight_shared';
}

/** Only the REST data and metadata endpoints; no /sql, GraphQL or pre-aggregation jobs. */
async function contextToApiScopes() {
  return ['data', 'meta'];
}

/** The SQL (Postgres wire) API is not used; refuse every login. */
async function checkSqlAuth() {
  throw forbidden('The SQL API is disabled');
}

module.exports = {
  MAX_TOKEN_LIFETIME_SECONDS,
  MIN_SECRET_LENGTH,
  SCOPE_MEMBER,
  verifyToken,
  checkAuth,
  scopeQuery,
  queryRewrite,
  referencedCubes,
  contextToAppId,
  contextToOrchestratorId,
  contextToApiScopes,
  checkSqlAuth,
};
