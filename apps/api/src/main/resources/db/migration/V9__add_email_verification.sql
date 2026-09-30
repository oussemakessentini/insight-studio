-- Email verification (docs/auth.md, "Email verification"). An account proves it controls its
-- address by opening an emailed link, accepting an invitation sent to it, or resetting its
-- password by email. Until then it can sign in and read, but cannot create or change businesses.

ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMPTZ NULL;

-- Accounts created before verification existed keep working as they did: treat them as verified.
UPDATE users SET email_verified_at = created_at;

-- Only the SHA-256 of a verification token is stored; the token itself is emailed once.
CREATE TABLE email_verification_tokens (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    token_sha256  CHAR(64)     NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL,
    used_at       TIMESTAMPTZ  NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_email_verification_tokens_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_email_verification_tokens_sha256 UNIQUE (token_sha256)
);

CREATE INDEX idx_email_verification_tokens_user_id ON email_verification_tokens (user_id);
