package com.orion.servicetemplate.api;

import com.orion.servicetemplate.config.ServiceTemplateProperties;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service info REST endpoint — demonstrates the standard API controller pattern.
 *
 * <p>WHY: Every Orion service exposes a lightweight info endpoint for operational visibility. This
 * controller demonstrates:
 *
 * <ul>
 *   <li>Constructor injection (not field injection)
 *   <li>API versioning via {@code /api/v1} prefix
 *   <li>Using {@link ServiceTemplateProperties} for config-driven responses
 * </ul>
 *
 * <p>Actuator provides {@code /actuator/info} for build metadata; this endpoint adds
 * service-specific runtime information.
 */
@RestController
@RequestMapping("/api/v1")
public class ServiceInfoController {

    private final ServiceTemplateProperties properties;

    // WHY: Constructor injection — Spring resolves dependencies at startup.
    // No @Autowired needed on single-constructor beans (Spring 4.3+).
    public ServiceInfoController(ServiceTemplateProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/info")
    public Map<String, Object> serviceInfo() {
        return Map.of(
                "name", properties.name(),
                "environment", properties.environment(),
                "description", properties.description() != null ? properties.description() : "",
                "status", "running",
                "timestamp", Instant.now().toString());
    }
}
