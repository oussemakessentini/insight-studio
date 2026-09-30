-- Outgoing account emails (docs/auth.md, "Email"). An email is written here in the same transaction
-- as the token it carries, and a worker on any API instance sends it, so emails survive restarts
-- and a mail server outage. The body holds a secret link until the email is sent or abandoned; it
-- is then erased (ck_mail_outbox_body_only_while_pending). Finished rows are purged after a week.

CREATE TABLE mail_outbox (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind             VARCHAR(40)   NOT NULL,
    recipient        VARCHAR(254)  NOT NULL,
    subject          VARCHAR(300)  NOT NULL,
    body             TEXT          NULL,
    status           VARCHAR(10)   NOT NULL DEFAULT 'PENDING',
    attempts         INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- A worker that claimed the row owns it until then; after a crash another worker takes over.
    locked_until     TIMESTAMPTZ   NULL,
    -- The link inside expires then: an email still unsent is dropped rather than sent dead.
    send_before      TIMESTAMPTZ   NULL,
    -- The SMTP error of the last failed attempt (never the body).
    last_error       VARCHAR(500)  NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ   NULL,
    CONSTRAINT ck_mail_outbox_status CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'EXPIRED')),
    CONSTRAINT ck_mail_outbox_body_only_while_pending CHECK (status = 'PENDING' OR body IS NULL)
);

CREATE INDEX idx_mail_outbox_due ON mail_outbox (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX idx_mail_outbox_finished_at ON mail_outbox (finished_at) WHERE status <> 'PENDING';
