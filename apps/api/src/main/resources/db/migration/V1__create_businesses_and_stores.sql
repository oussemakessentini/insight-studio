CREATE TABLE businesses (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    slug        VARCHAR(100) NOT NULL,
    currency    CHAR(3)      NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_businesses_slug UNIQUE (slug),
    CONSTRAINT ck_businesses_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE TABLE stores (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_id  BIGINT       NOT NULL,
    code         VARCHAR(50)  NOT NULL,
    name         VARCHAR(200) NOT NULL,
    city         VARCHAR(100),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_stores_business FOREIGN KEY (business_id) REFERENCES businesses (id),
    CONSTRAINT uq_stores_business_code UNIQUE (business_id, code)
);

CREATE INDEX idx_stores_business_id ON stores (business_id);
