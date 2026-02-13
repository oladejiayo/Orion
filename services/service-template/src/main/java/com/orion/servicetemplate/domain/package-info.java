/**
 * Domain layer — business entities, value objects, events, and domain services.
 *
 * <p>This package follows Clean Architecture / Hexagonal Architecture:
 *
 * <ul>
 *   <li>Domain MUST NOT depend on infrastructure or api packages
 *   <li>Domain contains pure business logic with no framework dependencies
 *   <li>Other layers depend on domain, never the reverse
 * </ul>
 *
 * <p>When creating a new service from this template, add:
 *
 * <ul>
 *   <li>{@code entities/} — JPA entities or domain aggregates
 *   <li>{@code events/} — domain events published via Kafka
 *   <li>{@code services/} — domain services encapsulating business rules
 *   <li>{@code ports/} — interfaces (ports) for infrastructure adapters
 * </ul>
 */
package com.orion.servicetemplate.domain;
