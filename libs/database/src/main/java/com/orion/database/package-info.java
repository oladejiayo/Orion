/**
 * Database migration framework for the Orion platform.
 *
 * <p>This package provides Flyway-based database migration support with multi-database
 * configuration, seed data management, and Spring Boot auto-configuration.
 *
 * <h2>Reinterpretation (US-01-10)</h2>
 *
 * <p>The original story specified node-pg-migrate (a TypeScript/Node.js migration tool). In the
 * Java ecosystem, <strong>Flyway</strong> is the standard equivalent:
 *
 * <ul>
 *   <li>node-pg-migrate → Flyway 10.x
 *   <li>config.js → {@link com.orion.database.migration.FlywayConfigProperties}
 *   <li>npm run db:migrate → {@code mvn flyway:migrate}
 *   <li>Sequential .sql files → Flyway {@code V{n}__{desc}.sql} naming
 *   <li>Seed scripts → High-version migrations ({@code V1000+}) or repeatable ({@code R__})
 * </ul>
 *
 * @see com.orion.database.migration.FlywayMultiDatabaseConfig
 * @see com.orion.database.migration.FlywayConfigProperties
 */
package com.orion.database;
