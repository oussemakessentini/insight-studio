-- CSV imports of historical sales. Only successful imports are stored: a rejected or dry-run
-- upload writes nothing. content_sha256 detects a file imported twice into the same business.
CREATE TABLE import_batches (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_id     BIGINT        NOT NULL,
    file_name       VARCHAR(255)  NOT NULL,
    content_sha256  CHAR(64)      NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    row_count       INTEGER       NOT NULL,
    sale_count      INTEGER       NOT NULL,
    line_count      INTEGER       NOT NULL,
    total_amount    NUMERIC(14,2) NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_import_batches_business FOREIGN KEY (business_id) REFERENCES businesses (id),
    CONSTRAINT uq_import_batches_business_sha256 UNIQUE (business_id, content_sha256),
    CONSTRAINT ck_import_batches_status CHECK (status IN ('IMPORTED')),
    CONSTRAINT ck_import_batches_row_count CHECK (row_count >= 0),
    CONSTRAINT ck_import_batches_sale_count CHECK (sale_count >= 0),
    CONSTRAINT ck_import_batches_line_count CHECK (line_count >= 0)
);

CREATE INDEX idx_import_batches_business_created ON import_batches (business_id, created_at);

-- Sales created by an import point to their batch; sales entered any other way leave it NULL.
ALTER TABLE sales
    ADD COLUMN import_batch_id BIGINT NULL,
    ADD CONSTRAINT fk_sales_import_batch FOREIGN KEY (import_batch_id) REFERENCES import_batches (id);

CREATE INDEX idx_sales_import_batch_id ON sales (import_batch_id);
