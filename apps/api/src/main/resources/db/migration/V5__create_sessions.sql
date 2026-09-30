-- Server-side sessions shared by every API instance and kept across restarts (Spring Session JDBC,
-- docs/auth.md). This is Spring Session's own PostgreSQL schema; Flyway owns it, so Spring Session
-- never creates tables (spring.session.jdbc.initialize-schema=never).
-- Attributes hold only the signed-in user's id, email and session version (plain Java types), so
-- a new application version can always read sessions written by an older one.

CREATE TABLE spring_session (
    primary_id             CHAR(36)      NOT NULL,
    session_id             CHAR(36)      NOT NULL,
    creation_time          BIGINT        NOT NULL,
    last_access_time       BIGINT        NOT NULL,
    max_inactive_interval  INT           NOT NULL,
    expiry_time            BIGINT        NOT NULL,
    principal_name         VARCHAR(100)  NULL,
    CONSTRAINT pk_spring_session PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX uq_spring_session_session_id ON spring_session (session_id);
CREATE INDEX idx_spring_session_expiry_time ON spring_session (expiry_time);
CREATE INDEX idx_spring_session_principal_name ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id  CHAR(36)      NOT NULL,
    attribute_name      VARCHAR(200)  NOT NULL,
    attribute_bytes     BYTEA         NOT NULL,
    CONSTRAINT pk_spring_session_attributes PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT fk_spring_session_attributes_session FOREIGN KEY (session_primary_id)
        REFERENCES spring_session (primary_id) ON DELETE CASCADE
);
