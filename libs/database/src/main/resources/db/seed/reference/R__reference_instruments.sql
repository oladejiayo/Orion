-- =============================================================================
-- Orion Platform — Reference Data: Instruments
-- =============================================================================
-- Flyway repeatable migration: R__reference_instruments.sql
--
-- WHY: Reference data (instruments) is environment-independent and needed in
-- all environments. Repeatable migrations (R__ prefix) re-run whenever the
-- file checksum changes, making it easy to add new instruments.
--
-- NOTE: This creates a reference_instruments table that will be used by the
-- Reference Data Service (US-04-01). For now, we define the schema and seed
-- data so development can proceed with realistic instruments.
-- =============================================================================

-- Create reference table if it doesn't exist yet
CREATE TABLE IF NOT EXISTS reference_instruments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    symbol VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    asset_class VARCHAR(50) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    exchange VARCHAR(100),
    is_active BOOLEAN NOT NULL DEFAULT true,
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ref_instruments_symbol ON reference_instruments(symbol);
CREATE INDEX IF NOT EXISTS idx_ref_instruments_asset_class ON reference_instruments(asset_class);

-- Upsert reference instruments
INSERT INTO reference_instruments (symbol, name, asset_class, currency, exchange, metadata) VALUES
    ('EUR/USD', 'Euro / US Dollar', 'FX', 'USD', NULL,
     '{"pip_size": 0.0001, "lot_size": 100000, "min_trade_size": 1000}'),
    ('GBP/USD', 'British Pound / US Dollar', 'FX', 'USD', NULL,
     '{"pip_size": 0.0001, "lot_size": 100000, "min_trade_size": 1000}'),
    ('USD/JPY', 'US Dollar / Japanese Yen', 'FX', 'JPY', NULL,
     '{"pip_size": 0.01, "lot_size": 100000, "min_trade_size": 1000}'),
    ('USD/CHF', 'US Dollar / Swiss Franc', 'FX', 'CHF', NULL,
     '{"pip_size": 0.0001, "lot_size": 100000, "min_trade_size": 1000}'),
    ('AUD/USD', 'Australian Dollar / US Dollar', 'FX', 'USD', NULL,
     '{"pip_size": 0.0001, "lot_size": 100000, "min_trade_size": 1000}'),
    ('US10Y', 'US 10-Year Treasury', 'RATES', 'USD', 'CME',
     '{"tick_size": 0.015625, "contract_size": 100000}'),
    ('DE10Y', 'German 10-Year Bund', 'RATES', 'EUR', 'EUREX',
     '{"tick_size": 0.01, "contract_size": 100000}'),
    ('UK10Y', 'UK 10-Year Gilt', 'RATES', 'GBP', 'ICE',
     '{"tick_size": 0.01, "contract_size": 100000}'),
    ('ITRAXX-MAIN', 'iTraxx Europe Main', 'CREDIT', 'EUR', NULL,
     '{"index_series": 41, "tenor": "5Y"}'),
    ('CDX-IG', 'CDX Investment Grade', 'CREDIT', 'USD', NULL,
     '{"index_series": 42, "tenor": "5Y"}')
ON CONFLICT (symbol) DO UPDATE SET
    name = EXCLUDED.name,
    asset_class = EXCLUDED.asset_class,
    currency = EXCLUDED.currency,
    exchange = EXCLUDED.exchange,
    metadata = EXCLUDED.metadata,
    updated_at = NOW();
