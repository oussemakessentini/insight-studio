-- Saved chart definitions with their revision history (docs/chart-builder-contract.md §5).
--
-- A chart is a named, business-owned definition (JSON, validated by the API against the chart
-- catalogue before it is stored). Every save writes a new immutable revision; chart_definitions
-- points at the current one. Dashboard layouts are a separate, later concern: they will reference
-- charts by id and are not stored here.

CREATE TABLE chart_definitions (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_id       BIGINT        NOT NULL,
    title             VARCHAR(120)  NOT NULL,
    current_revision  INTEGER       NOT NULL DEFAULT 1,
    created_by        BIGINT        NOT NULL,
    updated_by        BIGINT        NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_chart_definitions_business FOREIGN KEY (business_id) REFERENCES businesses (id),
    CONSTRAINT fk_chart_definitions_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_chart_definitions_updated_by FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT uq_chart_definitions_id_business UNIQUE (id, business_id),
    CONSTRAINT ck_chart_definitions_revision CHECK (current_revision >= 1)
);

-- Titles are unique per business, ignoring case (like saved reports).
CREATE UNIQUE INDEX uq_chart_definitions_business_title ON chart_definitions (business_id, lower(title));

CREATE TABLE chart_definition_revisions (
    chart_id        BIGINT        NOT NULL,
    business_id     BIGINT        NOT NULL,
    revision        INTEGER       NOT NULL,
    -- The definition's own format version (definition.schemaVersion), for future migrations.
    schema_version  INTEGER       NOT NULL,
    definition      JSONB         NOT NULL,
    created_by      BIGINT        NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_chart_definition_revisions PRIMARY KEY (chart_id, revision),
    -- A revision always belongs to its chart's business.
    CONSTRAINT fk_chart_definition_revisions_chart FOREIGN KEY (chart_id, business_id)
        REFERENCES chart_definitions (id, business_id) ON DELETE CASCADE,
    CONSTRAINT fk_chart_definition_revisions_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_chart_definition_revisions_revision CHECK (revision >= 1),
    CONSTRAINT ck_chart_definition_revisions_object CHECK (jsonb_typeof(definition) = 'object')
);
