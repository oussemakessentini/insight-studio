-- Payment-provider calls never run inside a database transaction (docs/billing-api.md, "Provider calls").
-- Each call that creates something at the provider is a durable operation: recorded (with its
-- idempotency key) in a short transaction before the call, completed in another short transaction
-- after it. A call that timed out is retried with the same key, so the provider returns the object it
-- may already have created instead of a second one.

CREATE TABLE billing_operations (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- No foreign key: an operation outlives its business (a checkout created while the business was
    -- being deleted must still be expired at the provider).
    business_id      BIGINT        NOT NULL,
    provider         VARCHAR(20)   NOT NULL,
    kind             VARCHAR(20)   NOT NULL,
    -- Sent as the provider's idempotency key (Stripe keeps keys at least 24 hours).
    idempotency_key  VARCHAR(100)  NOT NULL,
    -- PENDING: not known to have succeeded (in flight, timed out or waiting for a retry);
    -- SUCCEEDED; FAILED: the provider answered with an error (that key is spent);
    -- ABANDONED: no longer wanted (business deleted, key too old to reuse).
    status           VARCHAR(10)   NOT NULL DEFAULT 'PENDING',
    plan             VARCHAR(20)   NULL,
    customer_id      VARCHAR(255)  NULL,
    checkout_id      VARCHAR(255)  NULL,
    attempts         INTEGER       NOT NULL DEFAULT 0,
    -- For worker-run operations (expire_checkout): when it is due, and the worker's lease.
    next_attempt_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    locked_until     TIMESTAMPTZ   NULL,
    last_error       VARCHAR(500)  NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ   NULL,
    CONSTRAINT uq_billing_operations_key UNIQUE (provider, idempotency_key),
    CONSTRAINT ck_billing_operations_kind CHECK (kind IN ('create_customer', 'create_checkout', 'expire_checkout')),
    CONSTRAINT ck_billing_operations_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'ABANDONED'))
);

CREATE INDEX idx_billing_operations_business_pending ON billing_operations (business_id, kind) WHERE status = 'PENDING';
CREATE INDEX idx_billing_operations_due ON billing_operations (next_attempt_at)
    WHERE status = 'PENDING' AND kind = 'expire_checkout';
CREATE INDEX idx_billing_operations_finished ON billing_operations (finished_at) WHERE status <> 'PENDING';
-- One open expiry per checkout session.
CREATE UNIQUE INDEX uq_billing_operations_expire ON billing_operations (provider, checkout_id)
    WHERE kind = 'expire_checkout';

-- The event worker fetches a subscription's state from the provider outside any transaction. A lease per
-- subscription keeps two workers from writing two fetches of the same subscription out of order.
CREATE TABLE billing_subscription_leases (
    provider         VARCHAR(20)   NOT NULL,
    subscription_id  VARCHAR(255)  NOT NULL,
    holder           UUID          NOT NULL,
    locked_until     TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_billing_subscription_leases PRIMARY KEY (provider, subscription_id)
);

-- The fake provider honours idempotency keys like Stripe does: it records which object a key created.
ALTER TABLE fake_billing_objects DROP CONSTRAINT ck_fake_billing_objects_kind;
ALTER TABLE fake_billing_objects ADD CONSTRAINT ck_fake_billing_objects_kind
    CHECK (kind IN ('customer', 'checkout', 'subscription', 'portal', 'idempotency'));
