package com.orion.servicetemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orion.servicetemplate.config.ServiceTemplateProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for the service template Spring Boot application.
 *
 * <p>WHY: Verifies that the full Spring context loads successfully and that all auto-configured
 * beans, filters, and endpoints work together. Uses the 'test' profile which requires no external
 * infrastructure.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Service Template Application")
class ServiceTemplateApplicationTest {

    @Autowired private ApplicationContext context;
    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("Spring context loads successfully")
    void contextLoads() {
        // WHY: If the context fails to load, ALL other tests are meaningless.
        // This catches missing beans, circular dependencies, invalid config.
        assertThat(context).isNotNull();
    }

    @Test
    @DisplayName("Service properties are loaded from test profile")
    void servicePropertiesAreLoaded() {
        var props = context.getBean(ServiceTemplateProperties.class);
        assertThat(props.name()).isEqualTo("service-template-test");
        assertThat(props.environment()).isEqualTo("test");
    }

    @Test
    @DisplayName("Service info endpoint returns service name")
    void serviceInfoEndpointReturnsServiceName() throws Exception {
        mockMvc.perform(get("/api/v1/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("service-template-test"))
                .andExpect(jsonPath("$.status").value("running"));
    }

    @Test
    @DisplayName("Actuator health endpoint is available")
    void actuatorHealthEndpointIsAvailable() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Correlation ID header is set on responses")
    void correlationIdHeaderIsSetOnResponse() throws Exception {
        mockMvc.perform(get("/api/v1/info"))
                .andExpect(status().isOk())
                .andExpect(
                        result -> {
                            String correlationId =
                                    result.getResponse().getHeader("X-Correlation-ID");
                            assertThat(correlationId).isNotBlank();
                        });
    }
}
