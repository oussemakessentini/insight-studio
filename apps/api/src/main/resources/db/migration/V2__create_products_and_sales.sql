-- Reporting buckets (days, weeks, months) are computed in the business's local time.
ALTER TABLE businesses
    ADD COLUMN time_zone VARCHAR(64) NOT NULL DEFAULT 'UTC';

CREATE TABLE products (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_id  BIGINT        NOT NULL,
    sku          VARCHAR(50)   NOT NULL,
    name         VARCHAR(200)  NOT NULL,
    category     VARCHAR(100)  NOT NULL,
    list_price   NUMERIC(12,2) NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_products_business FOREIGN KEY (business_id) REFERENCES businesses (id),
    CONSTRAINT uq_products_business_sku UNIQUE (business_id, sku),
    CONSTRAINT ck_products_list_price CHECK (list_price >= 0)
);

CREATE TABLE sales (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id        BIGINT       NOT NULL,
    receipt_number  VARCHAR(40)  NOT NULL,
    sold_at         TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_sales_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT uq_sales_store_receipt UNIQUE (store_id, receipt_number)
);

CREATE INDEX idx_sales_store_sold_at ON sales (store_id, sold_at);
CREATE INDEX idx_sales_sold_at ON sales (sold_at);

-- unit_price is the price actually charged at the time of sale, so historical
-- revenue is unaffected by later changes to products.list_price.
CREATE TABLE sale_items (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sale_id     BIGINT        NOT NULL,
    product_id  BIGINT        NOT NULL,
    quantity    INTEGER       NOT NULL,
    unit_price  NUMERIC(12,2) NOT NULL,
    CONSTRAINT fk_sale_items_sale FOREIGN KEY (sale_id) REFERENCES sales (id) ON DELETE CASCADE,
    CONSTRAINT fk_sale_items_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT uq_sale_items_sale_product UNIQUE (sale_id, product_id),
    CONSTRAINT ck_sale_items_quantity CHECK (quantity > 0),
    CONSTRAINT ck_sale_items_unit_price CHECK (unit_price >= 0)
);

CREATE INDEX idx_sale_items_product_id ON sale_items (product_id);
