-- Accounts, business memberships and password recovery (docs/accounts-contract.md §2).
-- Businesses are unchanged: a business without members is reachable only as the configured
-- public demo, never by another business's members.

CREATE TABLE users (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email            VARCHAR(254) NOT NULL,
    password_hash    VARCHAR(100) NOT NULL,
    display_name     VARCHAR(100) NOT NULL,
    -- Incremented by a password change or reset; sessions carrying an older value are rejected.
    session_version  INTEGER      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_sign_in_at  TIMESTAMPTZ  NULL
);

-- Emails are compared case-insensitively.
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));

CREATE TABLE memberships (
    user_id      BIGINT       NOT NULL,
    business_id  BIGINT       NOT NULL,
    role         VARCHAR(10)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_memberships PRIMARY KEY (user_id, business_id),
    CONSTRAINT fk_memberships_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_memberships_business FOREIGN KEY (business_id) REFERENCES businesses (id),
    CONSTRAINT ck_memberships_role CHECK (role IN ('OWNER', 'ADMIN', 'VIEWER'))
);

CREATE INDEX idx_memberships_business_id ON memberships (business_id);

-- Only the SHA-256 of a reset token is stored; the token itself is sent to the user once.
CREATE TABLE password_reset_tokens (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    token_sha256  CHAR(64)     NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL,
    used_at       TIMESTAMPTZ  NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_password_reset_tokens_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_password_reset_tokens_sha256 UNIQUE (token_sha256)
);

CREATE INDEX idx_password_reset_tokens_user_id ON password_reset_tokens (user_id);
