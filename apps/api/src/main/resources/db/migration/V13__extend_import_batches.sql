-- Imports of stores and products next to sales, with an explicit mode, and a history that records
-- the outcome of every real import attempt (docs/catalog-imports-contract.md §5).
--
-- Existing rows are all successful sales imports; they keep their values and get kind 'sales',
-- mode 'create_only' and zero catalogue counts. Nothing is deleted or rewritten.

ALTER TABLE import_batches
    ADD COLUMN kind            VARCHAR(10)  NOT NULL DEFAULT 'sales',
    ADD COLUMN mode            VARCHAR(20)  NOT NULL DEFAULT 'create_only',
    ADD COLUMN created_count   INTEGER      NOT NULL DEFAULT 0,
    ADD COLUMN updated_count   INTEGER      NOT NULL DEFAULT 0,
    ADD COLUMN unchanged_count INTEGER      NOT NULL DEFAULT 0,
    ADD COLUMN error_count     INTEGER      NOT NULL DEFAULT 0,
    ADD COLUMN created_by      BIGINT       NULL,
    ADD CONSTRAINT fk_import_batches_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    ADD CONSTRAINT ck_import_batches_kind CHECK (kind IN ('sales', 'stores', 'products')),
    ADD CONSTRAINT ck_import_batches_mode CHECK (mode IN ('create_only', 'create_or_update')),
    -- Sales are only ever added; a receipt is never updated by an import.
    ADD CONSTRAINT ck_import_batches_sales_mode CHECK (kind <> 'sales' OR mode = 'create_only'),
    ADD CONSTRAINT ck_import_batches_counts CHECK (
        created_count >= 0 AND updated_count >= 0 AND unchanged_count >= 0 AND error_count >= 0);

-- A REJECTED attempt is history only: it wrote no data and records how many errors stopped it.
ALTER TABLE import_batches DROP CONSTRAINT ck_import_batches_status;
ALTER TABLE import_batches ADD CONSTRAINT ck_import_batches_status CHECK (status IN ('IMPORTED', 'REJECTED'));

-- Duplicate protection applies per business and kind, and only to files that were imported: the
-- same file may be retried after a rejection, and a stores file may also be imported as products.
ALTER TABLE import_batches DROP CONSTRAINT uq_import_batches_business_sha256;
CREATE UNIQUE INDEX uq_import_batches_imported_sha256
    ON import_batches (business_id, kind, content_sha256)
    WHERE status = 'IMPORTED';

CREATE INDEX idx_import_batches_business_kind_created ON import_batches (business_id, kind, created_at);
