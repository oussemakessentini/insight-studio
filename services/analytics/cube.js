// Cube configuration. Connection settings come from CUBEJS_* environment
// variables (see infra/compose.yaml); the data model lives in ./model.
//
// Every request is scoped to one business by ./security.js: checkAuth requires a short-lived
// HS256 JWT with a `businessId` claim, and queryRewrite adds that business as a mandatory filter
// to every query. A custom checkAuth runs in dev mode too, so dev mode cannot bypass it.
const security = require('./security');

module.exports = {
  checkAuth: security.checkAuth,
  queryRewrite: security.queryRewrite,
  contextToAppId: security.contextToAppId,
  contextToOrchestratorId: security.contextToOrchestratorId,
  contextToApiScopes: security.contextToApiScopes,
  checkSqlAuth: security.checkSqlAuth,

  // Rollups are shared by all businesses (business_id is a rollup dimension), so the refresh
  // worker needs a single context; it has no business and gets the refresh app id.
  scheduledRefreshContexts: async () => [{ securityContext: {} }],

  // Pre-aggregations are built per query timezone. Build the reporting
  // timezone(s) up front; the demo business reports in America/New_York.
  scheduledRefreshTimeZones: (process.env.CUBEJS_SCHEDULED_REFRESH_TIMEZONES || 'America/New_York')
    .split(',')
    .map((zone) => zone.trim())
    .filter(Boolean),
};
