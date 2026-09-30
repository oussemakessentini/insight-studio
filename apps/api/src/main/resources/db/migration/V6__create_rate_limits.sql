-- Rate limits shared by every API instance and kept across restarts (docs/auth.md). One row per
-- counted event; a bucket is a limit name plus the SHA-256 of its subject (an email or client IP),
-- so no email address or IP is stored in clear. Rows older than a day are purged.

CREATE TABLE rate_limit_hits (
    id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bucket  VARCHAR(120)  NOT NULL,
    hit_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_rate_limit_hits_bucket_hit_at ON rate_limit_hits (bucket, hit_at);
CREATE INDEX idx_rate_limit_hits_hit_at ON rate_limit_hits (hit_at);
