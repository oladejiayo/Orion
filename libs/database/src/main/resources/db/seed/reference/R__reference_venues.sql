-- =============================================================================
-- Orion Platform — Reference Data: Venues
-- =============================================================================
-- Flyway repeatable migration: R__reference_venues.sql
--
-- WHY: Trading venues (exchanges, ECNs, dealer platforms) are reference data
-- needed across all environments. This defines where trades can be executed.
--
-- NOTE: Will be expanded by the Reference Data Service (US-04-01).
-- =============================================================================

-- Create reference table if it doesn't exist yet
CREATE TABLE IF NOT EXISTS reference_venues (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(50) NOT NULL,
    asset_classes TEXT[] DEFAULT '{}',
    region VARCHAR(50),
    is_active BOOLEAN NOT NULL DEFAULT true,
    connectivity_config JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ref_venues_code ON reference_venues(code);
CREATE INDEX IF NOT EXISTS idx_ref_venues_type ON reference_venues(type);

-- Upsert reference venues
INSERT INTO reference_venues (code, name, type, asset_classes, region, connectivity_config) VALUES
    ('REUTERS', 'Refinitiv / LSEG', 'DEALER_PLATFORM', '{FX,RATES}', 'GLOBAL',
     '{"protocol": "FIX", "version": "4.4", "heartbeat_interval": 30}'),
    ('BLOOMBERG', 'Bloomberg Terminal', 'DEALER_PLATFORM', '{FX,RATES,CREDIT,EQUITIES}', 'GLOBAL',
     '{"protocol": "FIX", "version": "4.2", "heartbeat_interval": 30}'),
    ('TRADEWEB', 'Tradeweb', 'ECN', '{RATES,CREDIT}', 'GLOBAL',
     '{"protocol": "FIX", "version": "4.4", "heartbeat_interval": 15}'),
    ('CME', 'Chicago Mercantile Exchange', 'EXCHANGE', '{RATES,FX}', 'US',
     '{"protocol": "FIX", "version": "4.2", "heartbeat_interval": 30}'),
    ('EUREX', 'Eurex Exchange', 'EXCHANGE', '{RATES,EQUITIES}', 'EU',
     '{"protocol": "FIX", "version": "4.4", "heartbeat_interval": 30}'),
    ('ICE', 'Intercontinental Exchange', 'EXCHANGE', '{RATES,CREDIT}', 'GLOBAL',
     '{"protocol": "FIX", "version": "4.4", "heartbeat_interval": 30}'),
    ('EBS', 'EBS Market', 'ECN', '{FX}', 'GLOBAL',
     '{"protocol": "FIX", "version": "4.4", "heartbeat_interval": 10}'),
    ('CURRENEX', 'Currenex', 'ECN', '{FX}', 'GLOBAL',
     '{"protocol": "FIX", "version": "4.2", "heartbeat_interval": 15}')
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    type = EXCLUDED.type,
    asset_classes = EXCLUDED.asset_classes,
    region = EXCLUDED.region,
    connectivity_config = EXCLUDED.connectivity_config,
    updated_at = NOW();
