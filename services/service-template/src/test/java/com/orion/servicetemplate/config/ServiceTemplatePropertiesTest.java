package com.orion.servicetemplate.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ServiceTemplateProperties} record.
 *
 * <p>WHY: Validates the compact constructor defaults and constraints without starting Spring
 * context. Fast, isolated, and deterministic.
 */
@DisplayName("ServiceTemplateProperties")
class ServiceTemplatePropertiesTest {

    @Test
    @DisplayName("accepts valid properties")
    void acceptsValidProperties() {
        var props = new ServiceTemplateProperties("my-service", "production", "A service", 9090);
        assertThat(props.name()).isEqualTo("my-service");
        assertThat(props.environment()).isEqualTo("production");
        assertThat(props.description()).isEqualTo("A service");
        assertThat(props.grpcPort()).isEqualTo(9090);
    }

    @Test
    @DisplayName("defaults environment to 'development' when null")
    void defaultsEnvironmentWhenNull() {
        var props = new ServiceTemplateProperties("my-service", null, null, 9090);
        assertThat(props.environment()).isEqualTo("development");
    }

    @Test
    @DisplayName("defaults grpcPort to 9090 when zero or negative")
    void defaultsGrpcPortWhenZero() {
        var props = new ServiceTemplateProperties("my-service", "dev", null, 0);
        assertThat(props.grpcPort()).isEqualTo(9090);

        var props2 = new ServiceTemplateProperties("my-service", "dev", null, -1);
        assertThat(props2.grpcPort()).isEqualTo(9090);
    }
}
