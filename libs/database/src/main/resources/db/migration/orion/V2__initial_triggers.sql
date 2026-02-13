-- =============================================================================
-- Orion Platform — V2: Initial Triggers
-- =============================================================================
-- Flyway versioned migration: V2__initial_triggers.sql
--
-- Creates the update_updated_at() trigger function and applies it to tables
-- that have an updated_at column. This function automatically sets
-- updated_at = NOW() on every UPDATE, removing the need for application code
-- to manage timestamps.
--
-- Tables with triggers:
--   - tenants
--   - users
--   - user_entitlements
-- =============================================================================

-- ─── Trigger Function ────────────────────────────────────────────────────────
-- WHY: Centralizes timestamp management in the database rather than relying on
-- every application/service to remember to set updated_at. This is a common
-- PostgreSQL pattern that ensures data integrity regardless of which client
-- modifies the row.
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ─── Apply Triggers ──────────────────────────────────────────────────────────
-- Each trigger fires BEFORE UPDATE so the new timestamp is written in the
-- same transaction as the row change.

CREATE TRIGGER tenants_updated_at
    BEFORE UPDATE ON tenants
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER user_entitlements_updated_at
    BEFORE UPDATE ON user_entitlements
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
