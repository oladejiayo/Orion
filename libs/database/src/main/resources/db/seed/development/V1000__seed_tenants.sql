-- =============================================================================
-- Orion Platform — Seed: Development Tenants
-- =============================================================================
-- Flyway versioned migration: V1000__seed_tenants.sql
-- (High version number keeps seed data after schema migrations.)
--
-- WHY: Development and testing require consistent tenant data. These tenants
-- represent typical Orion customers with different configurations.
--
-- IMPORTANT: This migration is applied via the flyway-seed profile only.
-- Production environments NEVER run seed migrations.
-- =============================================================================

INSERT INTO tenants (id, name, type, status, settings) VALUES
    ('a0000000-0000-0000-0000-000000000001', 'Acme Capital', 'institutional', 'active',
     '{"max_users": 50, "features": ["rfq", "clob", "analytics"], "default_currency": "USD"}'),
    ('a0000000-0000-0000-0000-000000000002', 'Beta Trading Co', 'institutional', 'active',
     '{"max_users": 20, "features": ["rfq", "analytics"], "default_currency": "EUR"}'),
    ('a0000000-0000-0000-0000-000000000003', 'Gamma Markets', 'broker', 'active',
     '{"max_users": 100, "features": ["rfq", "clob", "analytics", "post_trade"], "default_currency": "GBP"}'),
    ('a0000000-0000-0000-0000-000000000004', 'Delta Securities (Suspended)', 'institutional', 'suspended',
     '{"max_users": 10, "features": ["rfq"], "default_currency": "USD", "suspended_reason": "compliance_review"}')
ON CONFLICT DO NOTHING;
