-- Saved report definitions (docs/saved-reports-contract.md §5). A definition belongs to one
-- business; its optional store must be a store of the same business (composite foreign key), so a
-- definition can never point at another business's data even if application checks were bypassed.

-- Lets (id, business_id) be referenced: a store id together with the business that owns it.
ALTER TABLE stores ADD CONSTRAINT uq_stores_id_business UNIQUE (id, business_id);

CREATE TABLE saved_reports (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_id      BIGINT        NOT NULL,
    name             VARCHAR(120)  NOT NULL,
    kind             VARCHAR(20)   NOT NULL,
    range_type       VARCHAR(10)   NOT NULL,
    date_from        DATE          NULL,
    date_to          DATE          NULL,
    relative_preset  VARCHAR(30)   NULL,
    store_id         BIGINT        NULL,
    created_by       BIGINT        NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_saved_reports_business FOREIGN KEY (business_id) REFERENCES businesses (id),
    CONSTRAINT fk_saved_reports_store FOREIGN KEY (store_id, business_id) REFERENCES stores (id, business_id),
    CONSTRAINT fk_saved_reports_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_saved_reports_kind CHECK (kind IN ('monthly', 'categories')),
    CONSTRAINT ck_saved_reports_range CHECK (
        (range_type = 'fixed' AND date_from IS NOT NULL AND date_to IS NOT NULL AND date_from <= date_to
            AND relative_preset IS NULL)
        OR (range_type = 'relative' AND relative_preset IS NOT NULL AND date_from IS NULL AND date_to IS NULL)),
    CONSTRAINT ck_saved_reports_preset CHECK (relative_preset IS NULL OR relative_preset IN (
        'last_7_days', 'last_30_days', 'last_90_days', 'last_365_days', 'month_to_date', 'previous_month',
        'last_3_months', 'last_12_months', 'quarter_to_date', 'previous_quarter', 'year_to_date', 'previous_year'))
);

CREATE UNIQUE INDEX uq_saved_reports_business_name ON saved_reports (business_id, lower(name));
