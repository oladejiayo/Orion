package com.orion.servicetemplate;

import com.orion.servicetemplate.config.ServiceTemplateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Orion Service Template — reference Spring Boot application.
 *
 * <p>WHY: This is the entry point that every Orion microservice follows. Copy this module to create
 * a new service, then customize:
 *
 * <ol>
 *   <li>Rename packages from {@code com.orion.servicetemplate} to {@code com.orion.yourservice}
 *   <li>Update {@code orion.service.name} in application.yml
 *   <li>Add domain entities, services, and controllers
 *   <li>Uncomment JPA/Kafka dependencies in pom.xml as needed
 * </ol>
 *
 * <p>Key features configured by default:
 *
 * <ul>
 *   <li>Graceful shutdown ({@code server.shutdown=graceful})
 *   <li>Actuator health, metrics, Prometheus endpoints
 *   <li>Correlation ID propagation (HTTP filter + gRPC interceptor)
 *   <li>Structured error handling (RFC 7807 ProblemDetail)
 *   <li>CORS configuration for local development
 * </ul>
 */
@SpringBootApplication
@EnableConfigurationProperties(ServiceTemplateProperties.class)
public class ServiceTemplateApplication {

    private static final Logger log = LoggerFactory.getLogger(ServiceTemplateApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(ServiceTemplateApplication.class, args);
        log.info("Orion Service Template started successfully");
    }
}
