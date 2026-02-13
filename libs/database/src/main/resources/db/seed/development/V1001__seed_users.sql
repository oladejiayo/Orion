-- =============================================================================
-- Orion Platform — Seed: Development Users
-- =============================================================================
-- Flyway versioned migration: V1001__seed_users.sql
--
-- WHY: Development requires users with different roles and entitlements to test
-- authorization flows, RFQ workflows, and admin operations.
--
-- DEPENDS ON: V1000__seed_tenants.sql (tenant UUIDs must exist)
-- =============================================================================

-- ─── Users for Acme Capital ─────────────────────────────────────────────────
INSERT INTO users (id, tenant_id, email, username, display_name, status) VALUES
    ('b0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001',
     'alice@acmecapital.com', 'alice', 'Alice Johnson', 'active'),
    ('b0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000001',
     'bob@acmecapital.com', 'bob', 'Bob Smith', 'active'),
    ('b0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000001',
     'admin@acmecapital.com', 'acme_admin', 'Acme Admin', 'active')
ON CONFLICT DO NOTHING;

-- ─── Users for Beta Trading ─────────────────────────────────────────────────
INSERT INTO users (id, tenant_id, email, username, display_name, status) VALUES
    ('b0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000002',
     'charlie@betatrading.com', 'charlie', 'Charlie Brown', 'active')
ON CONFLICT DO NOTHING;

-- ─── Users for Gamma Markets ────────────────────────────────────────────────
INSERT INTO users (id, tenant_id, email, username, display_name, status) VALUES
    ('b0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000003',
     'diana@gammamarkets.com', 'diana', 'Diana Prince', 'active'),
    ('b0000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000003',
     'admin@gammamarkets.com', 'gamma_admin', 'Gamma Admin', 'active')
ON CONFLICT DO NOTHING;

-- ─── Roles ──────────────────────────────────────────────────────────────────
INSERT INTO user_roles (user_id, role) VALUES
    ('b0000000-0000-0000-0000-000000000001', 'TRADER'),
    ('b0000000-0000-0000-0000-000000000001', 'VIEWER'),
    ('b0000000-0000-0000-0000-000000000002', 'TRADER'),
    ('b0000000-0000-0000-0000-000000000003', 'ADMIN'),
    ('b0000000-0000-0000-0000-000000000003', 'VIEWER'),
    ('b0000000-0000-0000-0000-000000000004', 'TRADER'),
    ('b0000000-0000-0000-0000-000000000005', 'TRADER'),
    ('b0000000-0000-0000-0000-000000000005', 'VIEWER'),
    ('b0000000-0000-0000-0000-000000000006', 'ADMIN')
ON CONFLICT DO NOTHING;

-- ─── Entitlements ───────────────────────────────────────────────────────────
INSERT INTO user_entitlements (user_id, asset_classes, instruments, venues,
                               max_notional, rfq_rate_limit, order_rate_limit, max_open_orders) VALUES
    ('b0000000-0000-0000-0000-000000000001',
     '{FX,RATES}', '{EUR/USD,GBP/USD,USD/JPY}', '{REUTERS,BLOOMBERG}',
     10000000.0000, 20, 200, 50),
    ('b0000000-0000-0000-0000-000000000002',
     '{FX}', '{EUR/USD,GBP/USD}', '{REUTERS}',
     5000000.0000, 10, 100, 25),
    ('b0000000-0000-0000-0000-000000000004',
     '{FX,RATES,CREDIT}', '{EUR/USD}', '{REUTERS,BLOOMBERG,TRADEWEB}',
     25000000.0000, 50, 500, 100),
    ('b0000000-0000-0000-0000-000000000005',
     '{FX,RATES,CREDIT,EQUITIES}', '{}', '{}',
     100000000.0000, 100, 1000, 500)
ON CONFLICT DO NOTHING;
