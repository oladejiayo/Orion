# US-17-03: Contract Testing

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-17-03 |
| **Epic** | Epic 17: Testing & Quality |
| **Title** | Contract Testing |
| **Priority** | High |
| **Story Points** | 5 |
| **Status** | Ready for Development |

## User Story

**As a** service developer  
**I want** consumer-driven contract testing  
**So that** API changes don't break dependent services

## Acceptance Criteria

### AC1: Pact Consumer Tests
- **Given** service consumers
- **When** contracts defined
- **Then**:
  - Expected requests defined
  - Expected responses defined
  - Pact files generated
  - Published to broker

### AC2: Pact Provider Verification
- **Given** provider service
- **When** contracts verified
- **Then**:
  - All consumer contracts pass
  - Provider states handled
  - Pending pacts supported

### AC3: Pact Broker Integration
- **Given** CI/CD pipeline
- **When** contracts published
- **Then**:
  - Versioned contracts
  - Can-I-Deploy checks
  - Webhook notifications

### AC4: OpenAPI Contract Tests
- **Given** OpenAPI specifications
- **When** implementation tested
- **Then**:
  - Schema validation
  - Request/response matching
  - Breaking change detection

### AC5: GraphQL Contract Tests
- **Given** GraphQL schema
- **When** queries tested
- **Then**:
  - Schema compatibility
  - Query validation
  - Mutation testing

## Technical Specification

### Pact Consumer Test (Java)

```java
// src/test/java/com/orion/position/client/OrderServiceContractTest.java
package com.orion.position.client;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTest;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.orion.order.dto.OrderResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@PactConsumerTest
@PactTestFor(providerName = "order-service")
@DisplayName("Order Service Contract - Position Service Consumer")
class OrderServiceContractTest {

    private static final UUID ORDER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Pact(consumer = "position-service")
    V4Pact getOrderById(PactDslWithProvider builder) {
        return builder
            .given("order exists", Map.of("orderId", ORDER_ID.toString()))
            .uponReceiving("get order by id")
            .path("/api/v1/orders/" + ORDER_ID)
            .method("GET")
            .headers(Map.of("Accept", "application/json"))
            .willRespondWith()
            .status(200)
            .headers(Map.of("Content-Type", "application/json"))
            .body(new PactDslJsonBody()
                .uuid("id", ORDER_ID)
                .stringType("symbol", "AAPL")
                .stringMatcher("side", "BUY|SELL", "BUY")
                .stringMatcher("type", "MARKET|LIMIT|STOP", "LIMIT")
                .decimalType("quantity", 100.0)
                .decimalType("price", 150.50)
                .stringMatcher("status", "PENDING|FILLED|CANCELLED|REJECTED", "PENDING")
                .stringType("clientId", "client-123")
                .datetime("createdAt", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            )
            .toPact(V4Pact.class);
    }

    @Pact(consumer = "position-service")
    V4Pact getOrderNotFound(PactDslWithProvider builder) {
        return builder
            .given("order does not exist")
            .uponReceiving("get order by non-existent id")
            .path("/api/v1/orders/" + UUID.randomUUID())
            .method("GET")
            .headers(Map.of("Accept", "application/json"))
            .willRespondWith()
            .status(404)
            .headers(Map.of("Content-Type", "application/json"))
            .body(new PactDslJsonBody()
                .stringType("error", "Not Found")
                .stringType("message", "Order not found")
            )
            .toPact(V4Pact.class);
    }

    @Pact(consumer = "position-service")
    V4Pact getOrdersByClient(PactDslWithProvider builder) {
        return builder
            .given("client has orders", Map.of("clientId", "client-123"))
            .uponReceiving("get orders by client id")
            .path("/api/v1/orders")
            .query("clientId=client-123&status=FILLED")
            .method("GET")
            .headers(Map.of("Accept", "application/json"))
            .willRespondWith()
            .status(200)
            .headers(Map.of("Content-Type", "application/json"))
            .body(new PactDslJsonBody()
                .array("content")
                    .object()
                        .uuid("id")
                        .stringType("symbol", "AAPL")
                        .stringValue("status", "FILLED")
                    .closeObject()
                .closeArray()
                .integerType("totalElements", 1)
                .integerType("totalPages", 1)
            )
            .toPact(V4Pact.class);
    }

    @Test
    @PactTestFor(pactMethod = "getOrderById")
    @DisplayName("should get order by id")
    void shouldGetOrderById(MockServer mockServer) {
        // Given
        OrderServiceClient client = new OrderServiceClient(
            new RestTemplate(),
            mockServer.getUrl()
        );

        // When
        OrderResponse response = client.getOrderById(ORDER_ID);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(ORDER_ID);
        assertThat(response.getSymbol()).isEqualTo("AAPL");
        assertThat(response.getStatus()).isEqualTo("PENDING");
    }

    @Test
    @PactTestFor(pactMethod = "getOrderNotFound")
    @DisplayName("should handle order not found")
    void shouldHandleOrderNotFound(MockServer mockServer) {
        // Given
        OrderServiceClient client = new OrderServiceClient(
            new RestTemplate(),
            mockServer.getUrl()
        );

        // When/Then
        assertThatThrownBy(() -> client.getOrderById(UUID.randomUUID()))
            .hasMessageContaining("Not Found");
    }
}
```

### Pact Provider Verification (Java)

```java
// src/test/java/com/orion/order/pact/OrderServiceProviderTest.java
package com.orion.order.pact;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactBroker;
import au.com.dius.pact.provider.junitsupport.loader.PactBrokerAuth;
import com.orion.IntegrationTest;
import com.orion.order.domain.Order;
import com.orion.order.domain.OrderStatus;
import com.orion.order.repository.OrderRepository;
import com.orion.testutil.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;
import java.util.UUID;

@IntegrationTest
@Provider("order-service")
@PactBroker(
    url = "${PACT_BROKER_URL:http://localhost:9292}",
    authentication = @PactBrokerAuth(
        token = "${PACT_BROKER_TOKEN}"
    ),
    consumerVersionSelectors = {
        @VersionSelector(tag = "main"),
        @VersionSelector(deployedOrReleased = true)
    }
)
class OrderServiceProviderTest {

    @LocalServerPort
    private int port;

    @Autowired
    private OrderRepository orderRepository;

    @BeforeEach
    void setUp(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget("localhost", port));
        orderRepository.deleteAll();
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verifyPact(PactVerificationContext context) {
        context.verifyInteraction();
    }

    @State("order exists")
    void orderExists(Map<String, Object> params) {
        UUID orderId = UUID.fromString((String) params.get("orderId"));
        Order order = TestDataFactory.validOrder()
            .id(orderId)
            .status(OrderStatus.PENDING)
            .build();
        orderRepository.save(order);
    }

    @State("order does not exist")
    void orderDoesNotExist() {
        // No setup needed - database is empty
    }

    @State("client has orders")
    void clientHasOrders(Map<String, Object> params) {
        String clientId = (String) params.get("clientId");
        Order order = TestDataFactory.validOrder()
            .clientId(clientId)
            .status(OrderStatus.FILLED)
            .build();
        orderRepository.save(order);
    }
}
```

### TypeScript Consumer Contract Test

```typescript
// src/services/__tests__/orderService.pact.test.ts
import { PactV4, MatchersV3 } from '@pact-foundation/pact';
import { orderServiceClient } from '../orderServiceClient';
import { Order, OrderStatus, OrderSide, OrderType } from '@/types/order';

const { like, uuid, datetime, decimal, regex } = MatchersV3;

describe('Order Service Contract', () => {
  const provider = new PactV4({
    consumer: 'trading-frontend',
    provider: 'order-service',
    logLevel: 'warn',
  });

  const ORDER_ID = '550e8400-e29b-41d4-a716-446655440000';

  describe('GET /api/v1/orders/:id', () => {
    it('returns an order when it exists', async () => {
      await provider
        .addInteraction()
        .given('order exists', { orderId: ORDER_ID })
        .uponReceiving('a request for an existing order')
        .withRequest('GET', `/api/v1/orders/${ORDER_ID}`, (builder) => {
          builder.headers({ Accept: 'application/json' });
        })
        .willRespondWith(200, (builder) => {
          builder
            .headers({ 'Content-Type': 'application/json' })
            .jsonBody({
              id: uuid(ORDER_ID),
              symbol: like('AAPL'),
              side: regex('BUY|SELL', 'BUY'),
              type: regex('MARKET|LIMIT|STOP', 'LIMIT'),
              quantity: decimal(100),
              price: decimal(150.5),
              status: regex('PENDING|FILLED|CANCELLED|REJECTED', 'PENDING'),
              clientId: like('client-123'),
              createdAt: datetime("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"),
            });
        })
        .executeTest(async (mockServer) => {
          const client = orderServiceClient(mockServer.url);
          const order = await client.getOrderById(ORDER_ID);

          expect(order.id).toBe(ORDER_ID);
          expect(order.symbol).toBe('AAPL');
          expect(order.status).toBe(OrderStatus.PENDING);
        });
    });

    it('returns 404 when order does not exist', async () => {
      const nonExistentId = '00000000-0000-0000-0000-000000000000';

      await provider
        .addInteraction()
        .given('order does not exist')
        .uponReceiving('a request for a non-existent order')
        .withRequest('GET', `/api/v1/orders/${nonExistentId}`, (builder) => {
          builder.headers({ Accept: 'application/json' });
        })
        .willRespondWith(404, (builder) => {
          builder
            .headers({ 'Content-Type': 'application/json' })
            .jsonBody({
              error: like('Not Found'),
              message: like('Order not found'),
            });
        })
        .executeTest(async (mockServer) => {
          const client = orderServiceClient(mockServer.url);

          await expect(client.getOrderById(nonExistentId)).rejects.toThrow(
            'Order not found'
          );
        });
    });
  });

  describe('POST /api/v1/orders', () => {
    it('creates a new order', async () => {
      const createOrderRequest = {
        symbol: 'AAPL',
        side: OrderSide.BUY,
        type: OrderType.LIMIT,
        quantity: 100,
        price: 150.5,
      };

      await provider
        .addInteraction()
        .given('user is authenticated')
        .uponReceiving('a request to create an order')
        .withRequest('POST', '/api/v1/orders', (builder) => {
          builder
            .headers({
              'Content-Type': 'application/json',
              Accept: 'application/json',
            })
            .jsonBody({
              symbol: like('AAPL'),
              side: regex('BUY|SELL', 'BUY'),
              type: regex('MARKET|LIMIT|STOP', 'LIMIT'),
              quantity: decimal(100),
              price: decimal(150.5),
            });
        })
        .willRespondWith(201, (builder) => {
          builder
            .headers({ 'Content-Type': 'application/json' })
            .jsonBody({
              id: uuid(),
              symbol: like('AAPL'),
              side: like('BUY'),
              type: like('LIMIT'),
              quantity: decimal(100),
              price: decimal(150.5),
              status: like('PENDING'),
              createdAt: datetime("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"),
            });
        })
        .executeTest(async (mockServer) => {
          const client = orderServiceClient(mockServer.url);
          const order = await client.createOrder(createOrderRequest);

          expect(order.id).toBeDefined();
          expect(order.symbol).toBe('AAPL');
          expect(order.status).toBe(OrderStatus.PENDING);
        });
    });
  });
});
```

### GitHub Actions - Contract Testing

```yaml
# .github/workflows/contract-tests.yml
name: Contract Tests

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]

env:
  PACT_BROKER_URL: ${{ secrets.PACT_BROKER_URL }}
  PACT_BROKER_TOKEN: ${{ secrets.PACT_BROKER_TOKEN }}

jobs:
  consumer-tests:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        consumer: [position-service, trading-frontend, market-data-service]
    
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'maven'
      
      - name: Run Consumer Contract Tests
        run: |
          cd services/${{ matrix.consumer }}
          mvn test -Dtest=*ContractTest -DfailIfNoTests=false
      
      - name: Publish Pacts
        run: |
          cd services/${{ matrix.consumer }}
          mvn pact:publish \
            -Dpact.broker.url=$PACT_BROKER_URL \
            -Dpact.broker.token=$PACT_BROKER_TOKEN \
            -Dpact.consumer.version=${{ github.sha }} \
            -Dpact.consumer.tags=${{ github.ref_name }}

  provider-verification:
    needs: consumer-tests
    runs-on: ubuntu-latest
    strategy:
      matrix:
        provider: [order-service, position-service]
    
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'maven'
      
      - name: Verify Provider Contracts
        run: |
          cd services/${{ matrix.provider }}
          mvn test -Dtest=*ProviderTest \
            -Dpact.verifier.publishResults=true \
            -Dpact.provider.version=${{ github.sha }} \
            -Dpact.provider.branch=${{ github.ref_name }}

  can-i-deploy:
    needs: provider-verification
    runs-on: ubuntu-latest
    
    steps:
      - name: Can I Deploy?
        uses: pactflow/actions/can-i-deploy@v1
        with:
          broker_url: ${{ secrets.PACT_BROKER_URL }}
          broker_token: ${{ secrets.PACT_BROKER_TOKEN }}
          pacticipant: ${{ github.event.repository.name }}
          version: ${{ github.sha }}
          to_environment: staging
```

### Pact Broker Configuration

```hcl
# infrastructure/terraform/modules/pact-broker/main.tf

resource "aws_ecs_service" "pact_broker" {
  name            = "${local.name_prefix}-pact-broker"
  cluster         = var.ecs_cluster_id
  task_definition = aws_ecs_task_definition.pact_broker.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.pact_broker.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.pact_broker.arn
    container_name   = "pact-broker"
    container_port   = 9292
  }
}

resource "aws_ecs_task_definition" "pact_broker" {
  family                   = "${local.name_prefix}-pact-broker"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = var.ecs_execution_role_arn
  task_role_arn            = var.ecs_task_role_arn

  container_definitions = jsonencode([{
    name  = "pact-broker"
    image = "pactfoundation/pact-broker:latest"
    
    portMappings = [{
      containerPort = 9292
      hostPort      = 9292
      protocol      = "tcp"
    }]
    
    environment = [
      { name = "PACT_BROKER_DATABASE_URL", value = "postgres://${var.db_username}:${var.db_password}@${var.db_host}/pact_broker" },
      { name = "PACT_BROKER_BASIC_AUTH_USERNAME", value = "admin" },
      { name = "PACT_BROKER_BASIC_AUTH_PASSWORD", value = var.broker_password },
      { name = "PACT_BROKER_BASE_URL", value = "https://pact.orion.example.com" },
      { name = "PACT_BROKER_WEBHOOK_HOST_WHITELIST", value = "github.com slack.com" }
    ]
    
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.pact_broker.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "pact-broker"
      }
    }
  }])
}
```

## Definition of Done

- [ ] Pact consumer tests implemented
- [ ] Pact provider verification working
- [ ] Pact Broker deployed
- [ ] CI/CD integration complete
- [ ] Can-I-Deploy checks enabled
- [ ] Webhook notifications configured
- [ ] OpenAPI schema validation
- [ ] Documentation complete

## Test Cases

```yaml
test-cases:
  - name: "Consumer pact generation"
    given: "Consumer test runs"
    when: "Mock interactions defined"
    then: "Pact file generated and published"
  
  - name: "Provider verification"
    given: "Pacts in broker"
    when: "Provider tests run"
    then: "All consumer contracts verified"
  
  - name: "Can-I-Deploy"
    given: "Service ready for deploy"
    when: "Can-I-Deploy check runs"
    then: "Deploy allowed only if contracts pass"
```
