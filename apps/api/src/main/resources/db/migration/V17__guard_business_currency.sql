-- A business's currency may change only while it holds no amounts (docs/account-management-contract.md
-- §1): products (list prices) and sales are stored as plain numbers in that currency, so changing it
-- would relabel them. The API checks this, but a check alone races with a product or sale being
-- written at the same moment, and products and sales are created on several paths (catalog, imports,
-- the demo seeder, manual SQL). The database therefore coordinates them with one transaction-level
-- advisory lock per business:
--
--   * every INSERT into products or sales takes it SHARED (BEFORE the row is written, so concurrent
--     writers never wait on each other);
--   * a currency change takes it EXCLUSIVE, then refuses the change when the business has any
--     product or sale. Under READ COMMITTED that check runs after the lock is granted and sees every
--     writer that committed before it; writers that start later wait and write in the new currency.
--
-- The API takes the exclusive lock before it locks the business row (and only takes FOR NO KEY UPDATE
-- on that row), so a writer holding the shared lock and the row's KEY SHARE (foreign key checks) can
-- never deadlock with it.
--
-- Lock key: (1296387705, business_id mod 2^31 - 1). Two businesses sharing a key only serialise each
-- other's currency changes and writes; correctness is unaffected.

CREATE FUNCTION lock_business_money_shared() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock_shared(1296387705, (NEW.business_id % 2147483647)::integer);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_products_money_lock
    BEFORE INSERT ON products
    FOR EACH ROW EXECUTE FUNCTION lock_business_money_shared();

-- Named to fire after trg_sales_business_id (V12, same event, alphabetical order), which derives
-- sales.business_id from the store.
CREATE TRIGGER trg_sales_money_lock
    BEFORE INSERT ON sales
    FOR EACH ROW EXECUTE FUNCTION lock_business_money_shared();

CREATE FUNCTION guard_business_currency() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(1296387705, (NEW.id % 2147483647)::integer);
    IF EXISTS (SELECT 1 FROM products WHERE business_id = NEW.id)
            OR EXISTS (SELECT 1 FROM sales WHERE business_id = NEW.id) THEN
        RAISE EXCEPTION 'Business % holds amounts in %; its currency cannot change.', NEW.id, OLD.currency
            USING ERRCODE = 'IS001';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_businesses_currency_guard
    BEFORE UPDATE OF currency ON businesses
    FOR EACH ROW
    WHEN (OLD.currency IS DISTINCT FROM NEW.currency)
    EXECUTE FUNCTION guard_business_currency();
