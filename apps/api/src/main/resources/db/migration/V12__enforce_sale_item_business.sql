-- A sale item's sale and product must belong to the same business (docs/cube-reports.md,
-- "Limitations"; until now only application code ensured it).
--
-- Enforced with composite foreign keys on a business_id carried by sales and sale_items:
--   sales      (store_id,   business_id) -> stores   (id, business_id)
--   sale_items (sale_id,    business_id) -> sales    (id, business_id)
--   sale_items (product_id, business_id) -> products (id, business_id)
-- business_id is derived, never chosen by callers: BEFORE triggers copy it from the sale's store
-- and the item's sale, so existing INSERT statements (import, demo seeder) keep working unchanged.
-- Moving a store to another business cascades to its sales and their items, and is then refused if
-- any of those items' products stays in the old business; a product with sales cannot change business.
--
-- Existing data is never deleted or rewritten to make the constraints fit: if any sale item already
-- references another business's product, this migration stops with an error and changes nothing.

DO $$
DECLARE
    mismatched BIGINT;
BEGIN
    SELECT COUNT(*) INTO mismatched
    FROM sale_items si
    JOIN sales s ON s.id = si.sale_id
    JOIN stores st ON st.id = s.store_id
    JOIN products p ON p.id = si.product_id
    WHERE p.business_id <> st.business_id;
    IF mismatched > 0 THEN
        RAISE EXCEPTION 'V12 stopped: % sale item(s) reference a product of another business than their sale''s store. '
            'Nothing was changed. Review them with: SELECT si.id, si.sale_id, st.business_id AS sale_business, '
            'si.product_id, p.business_id AS product_business FROM sale_items si JOIN sales s ON s.id = si.sale_id '
            'JOIN stores st ON st.id = s.store_id JOIN products p ON p.id = si.product_id '
            'WHERE p.business_id <> st.business_id; then correct them and run the migration again.', mismatched;
    END IF;
END;
$$;

-- (id, business_id) pairs that composite foreign keys can reference (stores has one since V10).
ALTER TABLE products ADD CONSTRAINT uq_products_id_business UNIQUE (id, business_id);

-- Sales carry their store's business.
ALTER TABLE sales ADD COLUMN business_id BIGINT;
UPDATE sales s SET business_id = st.business_id FROM stores st WHERE st.id = s.store_id;
ALTER TABLE sales ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE sales ADD CONSTRAINT uq_sales_id_business UNIQUE (id, business_id);
ALTER TABLE sales ADD CONSTRAINT fk_sales_store_business
    FOREIGN KEY (store_id, business_id) REFERENCES stores (id, business_id) ON UPDATE CASCADE;

-- Sale items carry their sale's business, which must also be their product's.
ALTER TABLE sale_items ADD COLUMN business_id BIGINT;
UPDATE sale_items si SET business_id = s.business_id FROM sales s WHERE s.id = si.sale_id;
ALTER TABLE sale_items ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE sale_items ADD CONSTRAINT fk_sale_items_sale_business
    FOREIGN KEY (sale_id, business_id) REFERENCES sales (id, business_id) ON UPDATE CASCADE ON DELETE CASCADE;
ALTER TABLE sale_items ADD CONSTRAINT fk_sale_items_product_business
    FOREIGN KEY (product_id, business_id) REFERENCES products (id, business_id);

CREATE INDEX idx_sale_items_product_business ON sale_items (product_id, business_id);

-- business_id is derived from the parent row on every insert or re-parenting; a value supplied by
-- the caller is ignored, so it can never disagree with the store or the sale.
CREATE FUNCTION set_sale_business_id() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    NEW.business_id := (SELECT st.business_id FROM stores st WHERE st.id = NEW.store_id);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sales_business_id
    BEFORE INSERT OR UPDATE OF store_id ON sales
    FOR EACH ROW EXECUTE FUNCTION set_sale_business_id();

CREATE FUNCTION set_sale_item_business_id() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    NEW.business_id := (SELECT s.business_id FROM sales s WHERE s.id = NEW.sale_id);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sale_items_business_id
    BEFORE INSERT OR UPDATE OF sale_id ON sale_items
    FOR EACH ROW EXECUTE FUNCTION set_sale_item_business_id();
