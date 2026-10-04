-- Chart runs in progress per business, shared by every API instance (docs/dashboards-contract.md §4,
-- docs/operations.md). Each run holds one row while it runs; at most
-- insight.charts.max-concurrent-runs-per-business rows per business are live at once. A row whose
-- instance died is ignored after expires_at (longer than any chart query may run) and purged by the
-- next run of that business.
CREATE TABLE chart_run_slots (
    holder       UUID          PRIMARY KEY,
    business_id  BIGINT        NOT NULL,
    acquired_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_chart_run_slots_business ON chart_run_slots (business_id, expires_at);
