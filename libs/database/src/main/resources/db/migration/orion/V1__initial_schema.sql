-- =============================================================================
-- Orion Platform — V1: Initial Schema
-- =============================================================================
-- Flyway versioned migration: V1__initial_schema.sql
--
-- Creates the foundational tables for the Orion platform:
--   - tenants:           Multi-tenant organization management
--   - users:             User accounts scoped to tenants
--   - user_roles:        Role assignments (RBAC)
--   - user_entitlements: Trading permissions and rate limits
--   - outbox_events:     Transactional outbox for reliable event publishing
--   - processed_events:  Idempotent event consumption tracking
--   - audit_log:         Append-only audit trail
--
-- Reinterpretation: Identical SQL to the TypeScript story's 001_initial_schema.sql.
-- Flyway naming (V1__) replaces sequential numbering (001_).
-- =============================================================================

-- ─── Extensions ──────────────────────────────────────────────────────────────
-- pgcrypto provides gen_random_uuid() for UUID primary keys.
-- The init-scripts/postgres/01-init-databases.sql already creates these
-- extensions, but we include IF NOT EXISTS for safety.
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ─── Tenants ─────────────────────────────────────────────────────────────────
-- WHY: Every entity in Orion is tenant-scoped. This is the root of the
-- multi-tenancy hierarchy. The 'settings' JSONB column allows per-tenant
-- configuration without schema changes.
CREATE TABLE tenants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    type VARCHAR(50) NOT NULL DEFAULT 'standard',
    status VARCHAR(50) NOT NULL DEFAULT 'active',
    settings JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tenants_status ON tenants(status);

-- ─── Users ───────────────────────────────────────────────────────────────────
-- WHY: User accounts are scoped to tenants. The UNIQUE constraints on
-- (tenant_id, email) and (tenant_id, username) allow the same email to
-- exist in different tenants (important for multi-tenant SaaS).
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    email VARCHAR(255) NOT NULL,
    username VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    status VARCHAR(50) NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(tenant_id, email),
    UNIQUE(tenant_id, username)
);

CREATE INDEX idx_users_tenant_id ON users(tenant_id);
CREATE INDEX idx_users_email ON users(email);

-- ─── User Roles ──────────────────────────────────────────────────────────────
-- WHY: RBAC (Role-Based Access Control). A user can have multiple roles
-- (e.g., TRADER + VIEWER). ON DELETE CASCADE ensures role records are
-- cleaned up when the user is removed.
CREATE TABLE user_roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, role)
);

CREATE INDEX idx_user_roles_user_id ON user_roles(user_id);

-- ─── User Entitlements ───────────────────────────────────────────────────────
-- WHY: Trading platforms need fine-grained permission controls. This table
-- defines what asset classes, instruments, and venues a user can trade,
-- plus rate limits to prevent accidental floods.
-- TEXT[] (PostgreSQL arrays) allow flexible multi-value fields without joins.
CREATE TABLE user_entitlements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    asset_classes TEXT[] DEFAULT '{}',
    instruments TEXT[] DEFAULT '{}',
    venues TEXT[] DEFAULT '{}',
    max_notional DECIMAL(20, 4),
    rfq_rate_limit INTEGER DEFAULT 10,
    order_rate_limit INTEGER DEFAULT 100,
    max_open_orders INTEGER DEFAULT 100,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id)
);

-- ─── Outbox Events ───────────────────────────────────────────────────────────
-- WHY: The Transactional Outbox pattern ensures events are published reliably.
-- Instead of publishing to Kafka directly (which can fail after DB commit),
-- we write events to this table in the same transaction, then a background
-- poller publishes them to Kafka. The partial index on unpublished events
-- makes polling efficient.
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type VARCHAR(255) NOT NULL,
    event_id UUID NOT NULL UNIQUE,
    tenant_id UUID NOT NULL,
    correlation_id UUID,
    entity_type VARCHAR(100),
    entity_id VARCHAR(255),
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ,
    retry_count INTEGER DEFAULT 0,
    last_error TEXT
);

-- Partial index: only unpublished events need to be queried by the poller
CREATE INDEX idx_outbox_unpublished ON outbox_events(created_at)
    WHERE published_at IS NULL;
CREATE INDEX idx_outbox_tenant ON outbox_events(tenant_id);

-- ─── Processed Events ────────────────────────────────────────────────────────
-- WHY: Idempotent event consumption. When a Kafka consumer processes an event,
-- it records the event_id here. If the same event is delivered again (at-least-
-- once delivery), the consumer checks this table and skips duplicates.
-- The UNIQUE constraint on (tenant_id, consumer_group, event_id) prevents
-- double-processing within a consumer group.
CREATE TABLE processed_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    consumer_group VARCHAR(255) NOT NULL,
    event_id UUID NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(tenant_id, consumer_group, event_id)
);

CREATE INDEX idx_processed_events_lookup
    ON processed_events(tenant_id, consumer_group, event_id);

-- ─── Audit Log ───────────────────────────────────────────────────────────────
-- WHY: Regulatory requirement for financial platforms. Every state change must
-- be recorded with who did it, what changed, and when. This table is APPEND-ONLY
-- (no UPDATE/DELETE triggers) — old_value and new_value capture the diff.
-- The INET type for ip_address is PostgreSQL-native and supports both IPv4/IPv6.
CREATE TABLE audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    user_id UUID,
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(100) NOT NULL,
    entity_id VARCHAR(255) NOT NULL,
    old_value JSONB,
    new_value JSONB,
    correlation_id UUID,
    ip_address INET,
    user_agent TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_log_tenant ON audit_log(tenant_id);
CREATE INDEX idx_audit_log_entity ON audit_log(entity_type, entity_id);
CREATE INDEX idx_audit_log_created ON audit_log(created_at);
CREATE INDEX idx_audit_log_correlation ON audit_log(correlation_id);
