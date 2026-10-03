-- Customizable dashboards (docs/dashboards-contract.md §5). A dashboard is a named, business-owned
-- arrangement of saved charts. Its layout (widgets plus desktop and mobile grids of x, y, w, h) is
-- JSONB validated by the API; every save writes a new immutable revision.
--
-- JSONB cannot carry foreign keys, so the charts a revision's layout uses are also listed in
-- dashboard_chart_refs, written in the same transaction. Its composite foreign keys make the
-- database refuse a dashboard that references another business's chart. Deleting a chart removes
-- its reference rows; the layout keeps the widget, which the API then reports as a missing chart.

CREATE TABLE dashboards (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_id       BIGINT        NOT NULL,
    name              VARCHAR(120)  NOT NULL,
    current_revision  INTEGER       NOT NULL DEFAULT 1,
    created_by        BIGINT        NOT NULL,
    updated_by        BIGINT        NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_dashboards_business FOREIGN KEY (business_id) REFERENCES businesses (id),
    CONSTRAINT fk_dashboards_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_dashboards_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT uq_dashboards_id_business UNIQUE (id, business_id),
    CONSTRAINT ck_dashboards_revision CHECK (current_revision >= 1)
);

-- Names are unique per business, ignoring case.
CREATE UNIQUE INDEX uq_dashboards_business_name ON dashboards (business_id, lower(name));

CREATE TABLE dashboard_revisions (
    dashboard_id    BIGINT        NOT NULL,
    business_id     BIGINT        NOT NULL,
    revision        INTEGER       NOT NULL,
    name            VARCHAR(120)  NOT NULL,
    -- The layout's own format version (layout.schemaVersion), for future migrations.
    schema_version  INTEGER       NOT NULL,
    layout          JSONB         NOT NULL,
    created_by      BIGINT        NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_dashboard_revisions PRIMARY KEY (dashboard_id, revision),
    CONSTRAINT fk_dashboard_revisions_dashboard FOREIGN KEY (dashboard_id, business_id)
        REFERENCES dashboards (id, business_id) ON DELETE CASCADE,
    CONSTRAINT fk_dashboard_revisions_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_dashboard_revisions_revision CHECK (revision >= 1),
    CONSTRAINT ck_dashboard_revisions_layout CHECK (
        jsonb_typeof(layout) = 'object'
        AND jsonb_typeof(layout -> 'widgets') = 'array'
        AND jsonb_typeof(layout -> 'desktop') = 'object'
        AND jsonb_typeof(layout -> 'mobile') = 'object')
);

-- The charts the CURRENT layout of each dashboard places (rewritten on every save).
CREATE TABLE dashboard_chart_refs (
    dashboard_id  BIGINT  NOT NULL,
    business_id   BIGINT  NOT NULL,
    chart_id      BIGINT  NOT NULL,
    CONSTRAINT pk_dashboard_chart_refs PRIMARY KEY (dashboard_id, chart_id),
    CONSTRAINT fk_dashboard_chart_refs_dashboard FOREIGN KEY (dashboard_id, business_id)
        REFERENCES dashboards (id, business_id) ON DELETE CASCADE,
    -- Same business as the dashboard, or the insert fails.
    CONSTRAINT fk_dashboard_chart_refs_chart FOREIGN KEY (chart_id, business_id)
        REFERENCES chart_definitions (id, business_id) ON DELETE CASCADE
);

CREATE INDEX idx_dashboard_chart_refs_chart ON dashboard_chart_refs (chart_id, business_id);
