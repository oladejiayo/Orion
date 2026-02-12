-- =============================================================================
-- Orion Platform — PostgreSQL Initialization Script
-- =============================================================================
-- Runs automatically on FIRST container start (when data volume is empty).
-- Creates per-service databases and an application user with limited privileges.
-- =============================================================================

-- Create per-service databases so each microservice has its own schema namespace
CREATE DATABASE orion_rfq;
CREATE DATABASE orion_marketdata;
CREATE DATABASE orion_posttrade;
CREATE DATABASE orion_analytics;

-- Create application user with limited privileges (not the superuser)
CREATE USER orion_app WITH PASSWORD 'orion_app_password';

-- Grant privileges to the app user on all databases
GRANT ALL PRIVILEGES ON DATABASE orion TO orion_app;
GRANT ALL PRIVILEGES ON DATABASE orion_rfq TO orion_app;
GRANT ALL PRIVILEGES ON DATABASE orion_marketdata TO orion_app;
GRANT ALL PRIVILEGES ON DATABASE orion_posttrade TO orion_app;
GRANT ALL PRIVILEGES ON DATABASE orion_analytics TO orion_app;

-- Useful extensions for all databases
-- uuid-ossp: Generate UUIDs (e.g., for primary keys)
-- pgcrypto:  Cryptographic functions (e.g., password hashing)
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
