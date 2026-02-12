package com.orion.observability.testing;

import com.orion.observability.CorrelationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link TestCorrelationContextFactory} — validates default creation,
 * random creation, and customization methods.
 */
@DisplayName("TestCorrelationContextFactory")
class TestCorrelationContextFactoryTest {

    @Nested
    @DisplayName("createDefault")
    class CreateDefault {

        @Test
        @DisplayName("should create context with all default values")
        void shouldCreateWithDefaults() {
            CorrelationContext ctx = TestCorrelationContextFactory.createDefault();

            assertThat(ctx.correlationId()).isEqualTo(TestCorrelationContextFactory.DEFAULT_CORRELATION_ID);
            assertThat(ctx.tenantId()).isEqualTo(TestCorrelationContextFactory.DEFAULT_TENANT_ID);
            assertThat(ctx.userId()).isEqualTo(TestCorrelationContextFactory.DEFAULT_USER_ID);
            assertThat(ctx.requestId()).isEqualTo(TestCorrelationContextFactory.DEFAULT_REQUEST_ID);
            assertThat(ctx.spanId()).isNull();
            assertThat(ctx.traceId()).isNull();
        }
    }

    @Nested
    @DisplayName("createRandom")
    class CreateRandom {

        @Test
        @DisplayName("should create context with random correlationId and requestId")
        void shouldCreateWithRandomIds() {
            CorrelationContext ctx1 = TestCorrelationContextFactory.createRandom();
            CorrelationContext ctx2 = TestCorrelationContextFactory.createRandom();

            // Different random IDs
            assertThat(ctx1.correlationId()).isNotEqualTo(ctx2.correlationId());
            assertThat(ctx1.requestId()).isNotEqualTo(ctx2.requestId());

            // Deterministic defaults for tenant/user
            assertThat(ctx1.tenantId()).isEqualTo(TestCorrelationContextFactory.DEFAULT_TENANT_ID);
            assertThat(ctx1.userId()).isEqualTo(TestCorrelationContextFactory.DEFAULT_USER_ID);
        }
    }

    @Nested
    @DisplayName("forTenant")
    class ForTenant {

        @Test
        @DisplayName("should create context for specific tenant")
        void shouldCreateForTenant() {
            CorrelationContext ctx = TestCorrelationContextFactory.forTenant("goldman-sachs");

            assertThat(ctx.tenantId()).isEqualTo("goldman-sachs");
            assertThat(ctx.correlationId()).isEqualTo(TestCorrelationContextFactory.DEFAULT_CORRELATION_ID);
        }
    }

    @Nested
    @DisplayName("forUser")
    class ForUser {

        @Test
        @DisplayName("should create context for specific user")
        void shouldCreateForUser() {
            CorrelationContext ctx = TestCorrelationContextFactory.forUser("trader-jane");

            assertThat(ctx.userId()).isEqualTo("trader-jane");
            assertThat(ctx.correlationId()).isEqualTo(TestCorrelationContextFactory.DEFAULT_CORRELATION_ID);
        }
    }

    @Nested
    @DisplayName("create (custom)")
    class CreateCustom {

        @Test
        @DisplayName("should create fully customized context")
        void shouldCreateCustom() {
            CorrelationContext ctx = TestCorrelationContextFactory.create(
                    "my-corr", "my-tenant", "my-user", "my-req");

            assertThat(ctx.correlationId()).isEqualTo("my-corr");
            assertThat(ctx.tenantId()).isEqualTo("my-tenant");
            assertThat(ctx.userId()).isEqualTo("my-user");
            assertThat(ctx.requestId()).isEqualTo("my-req");
        }
    }
}
