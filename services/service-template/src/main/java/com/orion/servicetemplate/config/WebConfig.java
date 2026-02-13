package com.orion.servicetemplate.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC configuration — CORS, interceptors, view resolvers.
 *
 * <p>WHY: Replaces Express CORS middleware from the TypeScript story. Spring's {@link
 * WebMvcConfigurer} is the idiomatic way to configure cross-origin requests. Allows the React
 * frontend (Vite dev server on localhost:5173 or CRA on localhost:3000) to call the API during
 * development.
 *
 * <p>In production, CORS origins should be externalized to config.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // WHY: Allow local development frontends to call the API.
        // Production origins should be configured via environment properties.
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:3000", "http://localhost:5173")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
