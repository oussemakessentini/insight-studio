-- Subscription billing (docs/billing-contract.md). One subscription per business; a business without a
-- row is on the Free plan. Plans and their limits are configuration, not data.

-- 1. The business's subscription as last confirmed by the payment provider. It is only ever written
--    from the provider's current state (fetched when an event arrives), never from an event payload,
--    so events processed out of order cannot leave an older state behind.
CREATE TABLE business_subscriptions (
    business_id               BIGINT        PRIMARY KEY,
    provider                  VARCHAR(20)   NOT NULL,
    provider_customer_id      VARCHAR(255)  NULL,
    provider_subscription_id  VARCHAR(255)  NULL,
    -- The plan the provider subscription is for ('pro'); the effective plan also depends on status.
    plan                      VARCHAR(20)   NOT NULL DEFAULT 'free',
    -- Provider status: none, incomplete, incomplete_expired, trialing, active, past_due, unpaid,
    -- canceled, paused.
    status                    VARCHAR(30)   NOT NULL DEFAULT 'none',
    current_period_end        TIMESTAMPTZ   NULL,
    cancel_at_period_end      BOOLEAN       NOT NULL DEFAULT false,
    canceled_at               TIMESTAMPTZ   NULL,
    -- When the provider state above was fetched (diagnostics; ordering never depends on it).
    synced_at                 TIMESTAMPTZ   NULL,
    created_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_business_subscriptions_business FOREIGN KEY (business_id) REFERENCES businesses (id) ON DELETE CASCADE,
    CONSTRAINT ck_business_subscriptions_plan CHECK (plan ~ '^[a-z][a-z0-9_]{0,19}$'),
    CONSTRAINT ck_business_subscriptions_status CHECK (status IN ('none', 'incomplete', 'incomplete_expired',
        'trialing', 'active', 'past_due', 'unpaid', 'canceled', 'paused'))
);

-- A provider customer or subscription belongs to exactly one business.
CREATE UNIQUE INDEX uq_business_subscriptions_customer ON business_subscriptions (provider, provider_customer_id)
    WHERE provider_customer_id IS NOT NULL;
CREATE UNIQUE INDEX uq_business_subscriptions_subscription ON business_subscriptions (provider, provider_subscription_id)
    WHERE provider_subscription_id IS NOT NULL;

-- 2. Webhook events, recorded once (provider, event id) after their signature is verified and processed
--    later by a worker on any instance (lease, retries). Only references are kept, never the payload:
--    payloads may carry customer names, emails and addresses. Finished rows are purged after the
--    retention period; the unique key then no longer protects against a replay that old, which the
--    signature timestamp tolerance (minutes) already refuses.
CREATE TABLE billing_events (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider         VARCHAR(20)   NOT NULL,
    event_id         VARCHAR(255)  NOT NULL,
    event_type       VARCHAR(100)  NOT NULL,
    -- The object the event is about (e.g. a subscription, checkout session or invoice id).
    object_type      VARCHAR(50)   NULL,
    object_id        VARCHAR(255)  NULL,
    -- References copied from the object for routing (never personal data).
    subscription_id  VARCHAR(255)  NULL,
    customer_id      VARCHAR(255)  NULL,
    business_id      BIGINT        NULL,
    status           VARCHAR(10)   NOT NULL DEFAULT 'PENDING',
    attempts         INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    locked_until     TIMESTAMPTZ   NULL,
    last_error       VARCHAR(500)  NULL,
    received_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    processed_at     TIMESTAMPTZ   NULL,
    CONSTRAINT uq_billing_events_event UNIQUE (provider, event_id),
    CONSTRAINT ck_billing_events_status CHECK (status IN ('PENDING', 'PROCESSED', 'IGNORED'))
);

CREATE INDEX idx_billing_events_due ON billing_events (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX idx_billing_events_finished ON billing_events (processed_at) WHERE status <> 'PENDING';

-- 3. Provider subscriptions to cancel because their business was deleted. Written in the deletion's
--    transaction (no foreign key: the business is gone); a worker cancels them at the provider and
--    retries until it succeeds.
CREATE TABLE billing_cancellations (
    id                        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_id               BIGINT        NOT NULL,
    provider                  VARCHAR(20)   NOT NULL,
    provider_subscription_id  VARCHAR(255)  NOT NULL,
    provider_customer_id      VARCHAR(255)  NULL,
    status                    VARCHAR(10)   NOT NULL DEFAULT 'PENDING',
    attempts                  INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    locked_until              TIMESTAMPTZ   NULL,
    last_error                VARCHAR(500)  NULL,
    created_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),
    finished_at               TIMESTAMPTZ   NULL,
    CONSTRAINT uq_billing_cancellations_subscription UNIQUE (provider, provider_subscription_id),
    CONSTRAINT ck_billing_cancellations_status CHECK (status IN ('PENDING', 'DONE'))
);

CREATE INDEX idx_billing_cancellations_due ON billing_cancellations (next_attempt_at) WHERE status = 'PENDING';

-- 4. The local fake provider's own state (development and tests only; never used with a real
--    provider). Kept in PostgreSQL so it survives restarts and works across API instances, like a
--    real provider would.
CREATE TABLE fake_billing_objects (
    id          VARCHAR(64)   PRIMARY KEY,
    kind        VARCHAR(20)   NOT NULL,
    data        JSONB         NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_fake_billing_objects_kind CHECK (kind IN ('customer', 'checkout', 'subscription', 'portal'))
);
