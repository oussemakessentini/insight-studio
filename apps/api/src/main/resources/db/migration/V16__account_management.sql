-- Business settings, audit history, data export and deletion (docs/account-management-contract.md).

-- 1. Audit history: one row per important change, scoped to a business. Rows are written by the API
--    in the same transaction as the change they describe. `details` is built from an allowlist of
--    fields per action and never holds passwords, tokens, links or email bodies. Rows are deleted
--    with their business and purged after the retention period (docs/data-retention.md).
CREATE TABLE audit_events (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_id    BIGINT        NOT NULL,
    -- NULL for changes made by the system (none yet); a deleted account keeps its id (tombstone).
    actor_user_id  BIGINT        NULL,
    action         VARCHAR(60)   NOT NULL,
    target_type    VARCHAR(30)   NULL,
    target_id      BIGINT        NULL,
    details        JSONB         NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_audit_events_business FOREIGN KEY (business_id) REFERENCES businesses (id) ON DELETE CASCADE,
    CONSTRAINT fk_audit_events_actor FOREIGN KEY (actor_user_id) REFERENCES users (id),
    CONSTRAINT ck_audit_events_action CHECK (action ~ '^[a-z_]+\.[a-z_]+$'),
    CONSTRAINT ck_audit_events_details CHECK (jsonb_typeof(details) = 'object')
);

CREATE INDEX idx_audit_events_business_id ON audit_events (business_id, id DESC);
CREATE INDEX idx_audit_events_created_at ON audit_events (created_at);

-- 2. Deleted accounts become tombstones: the row (and its id, which charts, dashboards, imports,
--    invitations and audit events point at) stays, every personal field is erased.
ALTER TABLE users
    ADD COLUMN deleted_at TIMESTAMPTZ NULL,
    ALTER COLUMN email DROP NOT NULL,
    ALTER COLUMN password_hash DROP NOT NULL,
    ADD CONSTRAINT ck_users_deleted_erased CHECK (
        (deleted_at IS NULL AND email IS NOT NULL AND password_hash IS NOT NULL)
        OR (deleted_at IS NOT NULL AND email IS NULL AND password_hash IS NULL
            AND email_verified_at IS NULL AND last_sign_in_at IS NULL));

-- 3. Which business or account an outgoing email belongs to, so deleting either cancels its pending
--    emails (and erases their bodies). No foreign keys: finished rows outlive both until purged.
ALTER TABLE mail_outbox
    ADD COLUMN business_id BIGINT NULL,
    ADD COLUMN user_id     BIGINT NULL;

CREATE INDEX idx_mail_outbox_business_pending ON mail_outbox (business_id) WHERE status = 'PENDING';
CREATE INDEX idx_mail_outbox_user_pending ON mail_outbox (user_id) WHERE status = 'PENDING';

-- 4. Cube purges. Cube's rollups are shared by every business; deleting a business bumps the report
--    data version (triggers, V11), but rollups for a time zone nobody queries again would keep the
--    deleted rows. A business deletion therefore queues one purge, written in the same transaction;
--    a worker on any API instance rebuilds every rollup in every relevant time zone from a data
--    version at least `data_version` (SKIPPED when the API has no Cube). No foreign key: the
--    business is gone.
CREATE TABLE cube_purge_requests (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_id      BIGINT        NOT NULL,
    time_zone        VARCHAR(64)   NOT NULL,
    -- report_data_version after the deletion: rollups built from an older version may hold its rows.
    data_version     BIGINT        NOT NULL,
    status           VARCHAR(10)   NOT NULL DEFAULT 'PENDING',
    attempts         INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    locked_until     TIMESTAMPTZ   NULL,
    last_error       VARCHAR(500)  NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ   NULL,
    CONSTRAINT ck_cube_purge_requests_status CHECK (status IN ('PENDING', 'DONE', 'SKIPPED', 'FAILED'))
);

CREATE INDEX idx_cube_purge_requests_due ON cube_purge_requests (next_attempt_at) WHERE status = 'PENDING';
