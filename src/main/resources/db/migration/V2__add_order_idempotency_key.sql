ALTER TABLE orders
    ADD COLUMN idempotency_key VARCHAR(100);

CREATE UNIQUE INDEX uk_orders_idempotency_key
    ON orders(idempotency_key)
    WHERE idempotency_key IS NOT NULL;