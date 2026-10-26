CREATE FUNCTION prevent_runtime_admin_user_write()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT'
            AND NEW.role = 'ADMIN'
            AND session_user <> 'app_bootstrap'
            AND NOT EXISTS (
                SELECT 1
                FROM pg_catalog.pg_roles
                WHERE rolname = session_user
                  AND rolsuper
            ) THEN
        RAISE EXCEPTION 'only the bootstrap role may create ADMIN users'
            USING ERRCODE = '42501';
    END IF;

    IF TG_OP = 'UPDATE'
            AND NEW.role = 'ADMIN'
            AND OLD.role IS DISTINCT FROM NEW.role THEN
        RAISE EXCEPTION 'promotion to ADMIN is not allowed'
            USING ERRCODE = '42501';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_prevent_runtime_admin_user_write
    BEFORE INSERT OR UPDATE OF role ON users
    FOR EACH ROW
    EXECUTE FUNCTION prevent_runtime_admin_user_write();

GRANT USAGE ON SCHEMA public TO app_runtime, app_bootstrap;
GRANT SELECT, INSERT, UPDATE ON users, customers, products, orders, order_items TO app_runtime;
GRANT SELECT, INSERT ON user_customer_link_audit TO app_runtime;
GRANT SELECT, INSERT ON users TO app_bootstrap;

REVOKE ALL ON FUNCTION prevent_runtime_admin_user_write() FROM PUBLIC;
