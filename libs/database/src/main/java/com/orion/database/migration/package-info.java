/**
 * Flyway migration configuration and utilities.
 *
 * <p>Contains:
 *
 * <ul>
 *   <li>{@link com.orion.database.migration.FlywayConfigProperties} — externalized configuration
 *       for multi-database Flyway setup
 *   <li>{@link com.orion.database.migration.FlywayMultiDatabaseConfig} — Spring
 *       {@code @Configuration} that creates per-database Flyway beans
 *   <li>{@link com.orion.database.migration.MigrationService} — programmatic migration status and
 *       operations
 * </ul>
 */
package com.orion.database.migration;
