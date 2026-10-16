ALTER TABLE users
    ADD COLUMN customer_id UUID;

ALTER TABLE users
    ADD CONSTRAINT fk_users_customer
        FOREIGN KEY (customer_id) REFERENCES customers(id);

CREATE INDEX idx_users_customer_id ON users(customer_id);

CREATE TABLE user_customer_link_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id UUID NOT NULL REFERENCES users(id),
    target_user_id UUID NOT NULL REFERENCES users(id),
    previous_customer_id UUID,
    new_customer_id UUID,
    action VARCHAR(10) NOT NULL
        CHECK (action IN ('CREATE', 'CHANGE', 'REMOVE')),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    justification TEXT NOT NULL
);

CREATE FUNCTION prevent_user_customer_link_audit_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'user_customer_link_audit is append-only';
END;
$$;

CREATE TRIGGER trg_user_customer_link_audit_append_only
    BEFORE UPDATE OR DELETE ON user_customer_link_audit
    FOR EACH ROW
    EXECUTE FUNCTION prevent_user_customer_link_audit_mutation();
