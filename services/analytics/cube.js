// Cube configuration. Connection settings come from CUBEJS_* environment
// variables (see infra/compose.yaml); the data model lives in ./model.
module.exports = {
  // Pre-aggregations are built per query timezone. Build the reporting
  // timezone(s) up front; the demo business reports in America/New_York.
  scheduledRefreshTimeZones: (process.env.CUBEJS_SCHEDULED_REFRESH_TIMEZONES || 'America/New_York')
    .split(',')
    .map((zone) => zone.trim())
    .filter(Boolean),
};
