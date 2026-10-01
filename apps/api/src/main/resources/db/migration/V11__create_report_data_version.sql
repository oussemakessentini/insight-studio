-- A version number for the data reports are computed from (docs/cube-reports-contract.md §4).
--
-- Statement-level triggers bump it in the same transaction as any change to sales, sale items,
-- products or stores (imports, the demo seeder, catalog setup, tests, manual fixes alike), so it
-- changes exactly when that data changes and only once the change commits. Cube uses it as the
-- refresh key of its rollups, and the API compares it with the version Cube served from, so a
-- report never shows figures computed before the latest change.
--
-- One global row: the rollups are shared by every business, so any change rebuilds them.

CREATE TABLE report_data_version (
    id          SMALLINT     PRIMARY KEY,
    version     BIGINT       NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_report_data_version_single_row CHECK (id = 1)
);

INSERT INTO report_data_version (id, version) VALUES (1, 1);

CREATE FUNCTION bump_report_data_version() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    UPDATE report_data_version SET version = version + 1, updated_at = now() WHERE id = 1;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_sales_report_data_version
    AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON sales
    FOR EACH STATEMENT EXECUTE FUNCTION bump_report_data_version();

CREATE TRIGGER trg_sale_items_report_data_version
    AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON sale_items
    FOR EACH STATEMENT EXECUTE FUNCTION bump_report_data_version();

CREATE TRIGGER trg_products_report_data_version
    AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON products
    FOR EACH STATEMENT EXECUTE FUNCTION bump_report_data_version();

CREATE TRIGGER trg_stores_report_data_version
    AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON stores
    FOR EACH STATEMENT EXECUTE FUNCTION bump_report_data_version();
