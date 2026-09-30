-- Invitations to join a business (docs/auth.md, "Invitations"). They replace adding an existing
-- account by email, which told the inviter whether an account existed. Only the SHA-256 of an
-- invitation token is stored; the token itself is emailed once. An invitation is usable while it is
-- neither accepted, revoked nor expired, and only by the account whose email it was sent to.

CREATE TABLE invitations (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_id   BIGINT        NOT NULL,
    email         VARCHAR(254)  NOT NULL,
    role          VARCHAR(10)   NOT NULL,
    token_sha256  CHAR(64)      NOT NULL,
    invited_by    BIGINT        NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ   NOT NULL,
    accepted_at   TIMESTAMPTZ   NULL,
    accepted_by   BIGINT        NULL,
    revoked_at    TIMESTAMPTZ   NULL,
    CONSTRAINT fk_invitations_business FOREIGN KEY (business_id) REFERENCES businesses (id),
    CONSTRAINT fk_invitations_invited_by FOREIGN KEY (invited_by) REFERENCES users (id),
    CONSTRAINT fk_invitations_accepted_by FOREIGN KEY (accepted_by) REFERENCES users (id),
    CONSTRAINT ck_invitations_role CHECK (role IN ('OWNER', 'ADMIN', 'VIEWER')),
    CONSTRAINT uq_invitations_token_sha256 UNIQUE (token_sha256)
);

-- At most one open invitation per business and address (a new one replaces the old).
CREATE UNIQUE INDEX uq_invitations_open_email ON invitations (business_id, lower(email))
    WHERE accepted_at IS NULL AND revoked_at IS NULL;
