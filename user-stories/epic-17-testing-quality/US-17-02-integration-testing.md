# US-17-02: Integration Testing

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-17-02 |
| **Epic** | Epic 17: Testing & Quality |
| **Title** | Integration Testing |
| **Priority** | High |
| **Story Points** | 8 |
| **Status** | Ready for Development |

## User Story

**As a** developer  
**I want** integration tests with real dependencies  
**So that** I can verify service interactions work correctly

## Acceptance Criteria

### AC1: Testcontainers Setup
- **Given** integration tests
- **When** containers needed
- **Then**:
  - PostgreSQL container
  - Kafka container
  - Redis container
  - LocalStack for AWS

### AC2: Database Integration Tests
- **Given** repository layer
- **When** tests run
- **Then**:
  - Real database queries
  - Transaction rollback
  - Schema migrations
  - Data fixtures

### AC3: Kafka Integration Tests
- **Given** event producers/consumers
- **When** messages sent
- **Then**:
  - Message delivery verified
  - Serialization tested
  - Consumer behavior validated

### AC4: API Integration Tests
- **Given** REST endpoints
- **When** HTTP requests made
- **Then**:
  - Full request/response cycle
  - Authentication tested
  - Error handling verified

### AC5: Test Data Management
- **Given** test fixtures
- **When** tests run
- **Then**:
  - Isolated test data
  - Automatic cleanup
  - Reproducible state

## Technical Specification

### Testcontainers Configuration

```java
// src/test/java/com/orion/testcontainers/ContainerConfig.java
package com.orion.testcontainers;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

import static org.testcontainers.containers.localstack.LocalStackContainer.Service.*;

@TestConfiguration(proxyBeanMethods = false)
public class ContainerConfig {

    @Bean
    @ServiceConnection
    public PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
            .withDatabaseName("orion_test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(true);
    }

    @Bean
    @ServiceConnection
    public KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"))
            .withReuse(true);
    }

    @Bean
    @ServiceConnection(name = "redis")
    public GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withReuse(true);
    }

    @Bean
    public LocalStackContainer localStackContainer() {
        return new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
            .withServices(S3, SQS, DYNAMODB, SECRETSMANAGER)
            .withReuse(true);
    }
}
```

### Base Integration Test Class

```java
// src/test/java/com/orion/IntegrationTest.java
package com.orion;

import com.orion.testcontainers.ContainerConfig;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import(ContainerConfig.class)
@Transactional
@Tag("integration")
public @interface IntegrationTest {
}
```

### Repository Integration Tests

```java
// src/test/java/com/orion/order/repository/OrderRepositoryIntegrationTest.java
package com.orion.order.repository;

import com.orion.IntegrationTest;
import com.orion.order.domain.Order;
import com.orion.order.domain.OrderSide;
import com.orion.order.domain.OrderStatus;
import com.orion.order.domain.OrderType;
import com.orion.testutil.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

@IntegrationTest
@DisplayName("Order Repository Integration Tests")
class OrderRepositoryIntegrationTest {

    @Autowired
    private OrderRepository orderRepository;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
    }

    @Nested
    @DisplayName("Basic CRUD Operations")
    class CrudOperations {

        @Test
        @DisplayName("should save and retrieve order")
        void shouldSaveAndRetrieveOrder() {
            // Given
            Order order = TestDataFactory.validOrder().build();

            // When
            Order saved = orderRepository.save(order);
            Optional<Order> retrieved = orderRepository.findById(saved.getId());

            // Then
            assertThat(retrieved).isPresent();
            assertThat(retrieved.get())
                .usingRecursiveComparison()
                .ignoringFields("id", "createdAt", "updatedAt")
                .isEqualTo(order);
        }

        @Test
        @DisplayName("should update order status")
        void shouldUpdateOrderStatus() {
            // Given
            Order order = orderRepository.save(TestDataFactory.validOrder().build());

            // When
            order.setStatus(OrderStatus.FILLED);
            Order updated = orderRepository.save(order);

            // Then
            assertThat(updated.getStatus()).isEqualTo(OrderStatus.FILLED);
        }
    }

    @Nested
    @DisplayName("Custom Queries")
    class CustomQueries {

        @Test
        @DisplayName("should find orders by client and status")
        void shouldFindByClientAndStatus() {
            // Given
            String clientId = "client-123";
            orderRepository.saveAll(List.of(
                TestDataFactory.validOrder().clientId(clientId).status(OrderStatus.PENDING).build(),
                TestDataFactory.validOrder().clientId(clientId).status(OrderStatus.PENDING).build(),
                TestDataFactory.validOrder().clientId(clientId).status(OrderStatus.FILLED).build(),
                TestDataFactory.validOrder().clientId("other-client").status(OrderStatus.PENDING).build()
            ));

            // When
            List<Order> orders = orderRepository.findByClientIdAndStatus(clientId, OrderStatus.PENDING);

            // Then
            assertThat(orders).hasSize(2);
            assertThat(orders).allMatch(o -> o.getClientId().equals(clientId));
            assertThat(orders).allMatch(o -> o.getStatus() == OrderStatus.PENDING);
        }

        @Test
        @DisplayName("should find orders by symbol with pagination")
        void shouldFindBySymbolWithPagination() {
            // Given
            String symbol = "AAPL";
            for (int i = 0; i < 25; i++) {
                orderRepository.save(TestDataFactory.validOrder().symbol(symbol).build());
            }

            // When
            Page<Order> page = orderRepository.findBySymbol(
                symbol,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"))
            );

            // Then
            assertThat(page.getTotalElements()).isEqualTo(25);
            assertThat(page.getTotalPages()).isEqualTo(3);
            assertThat(page.getContent()).hasSize(10);
        }

        @Test
        @DisplayName("should find orders in date range")
        void shouldFindOrdersInDateRange() {
            // Given
            Instant now = Instant.now();
            Instant yesterday = now.minus(1, ChronoUnit.DAYS);
            Instant lastWeek = now.minus(7, ChronoUnit.DAYS);

            orderRepository.saveAll(List.of(
                TestDataFactory.validOrder().createdAt(now).build(),
                TestDataFactory.validOrder().createdAt(yesterday).build(),
                TestDataFactory.validOrder().createdAt(lastWeek).build()
            ));

            // When
            List<Order> orders = orderRepository.findByCreatedAtBetween(
                now.minus(2, ChronoUnit.DAYS),
                now.plus(1, ChronoUnit.DAYS)
            );

            // Then
            assertThat(orders).hasSize(2);
        }

        @Test
        @DisplayName("should aggregate order volume by symbol")
        void shouldAggregateOrderVolume() {
            // Given
            orderRepository.saveAll(List.of(
                TestDataFactory.validOrder().symbol("AAPL").quantity(BigDecimal.valueOf(100)).build(),
                TestDataFactory.validOrder().symbol("AAPL").quantity(BigDecimal.valueOf(200)).build(),
                TestDataFactory.validOrder().symbol("GOOGL").quantity(BigDecimal.valueOf(50)).build()
            ));

            // When
            List<OrderVolumeProjection> volumes = orderRepository.getOrderVolumeBySymbol();

            // Then
            assertThat(volumes).hasSize(2);
            
            OrderVolumeProjection aaplVolume = volumes.stream()
                .filter(v -> v.getSymbol().equals("AAPL"))
                .findFirst()
                .orElseThrow();
            assertThat(aaplVolume.getTotalQuantity()).isEqualByComparingTo("300");
        }
    }
}
```

### Kafka Integration Tests

```java
// src/test/java/com/orion/order/event/OrderEventIntegrationTest.java
package com.orion.order.event;

import com.orion.IntegrationTest;
import com.orion.order.domain.Order;
import com.orion.order.domain.OrderStatus;
import com.orion.order.event.dto.OrderCreatedEvent;
import com.orion.order.event.dto.OrderFilledEvent;
import com.orion.testutil.TestDataFactory;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@IntegrationTest
@DisplayName("Order Event Integration Tests")
class OrderEventIntegrationTest {

    @Autowired
    private OrderEventPublisher orderEventPublisher;

    @Autowired
    private ConsumerFactory<String, Object> consumerFactory;

    private Consumer<String, OrderCreatedEvent> orderCreatedConsumer;
    private Consumer<String, OrderFilledEvent> orderFilledConsumer;

    @BeforeEach
    void setUp() {
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
            "test-group-" + UUID.randomUUID(),
            "true",
            embeddedKafka
        );
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        orderCreatedConsumer = createConsumer(OrderCreatedEvent.class);
        orderCreatedConsumer.subscribe(Collections.singleton("orders.created"));

        orderFilledConsumer = createConsumer(OrderFilledEvent.class);
        orderFilledConsumer.subscribe(Collections.singleton("orders.filled"));
    }

    @AfterEach
    void tearDown() {
        orderCreatedConsumer.close();
        orderFilledConsumer.close();
    }

    @Test
    @DisplayName("should publish order created event")
    void shouldPublishOrderCreatedEvent() {
        // Given
        Order order = TestDataFactory.validOrder()
            .id(UUID.randomUUID())
            .build();

        // When
        orderEventPublisher.publishOrderCreated(order);

        // Then
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ConsumerRecord<String, OrderCreatedEvent> record = 
                KafkaTestUtils.getSingleRecord(orderCreatedConsumer, "orders.created");
            
            assertThat(record.value().getOrderId()).isEqualTo(order.getId());
            assertThat(record.value().getSymbol()).isEqualTo(order.getSymbol());
            assertThat(record.key()).isEqualTo(order.getId().toString());
        });
    }

    @Test
    @DisplayName("should publish order filled event with fill details")
    void shouldPublishOrderFilledEvent() {
        // Given
        Order order = TestDataFactory.validOrder()
            .id(UUID.randomUUID())
            .status(OrderStatus.FILLED)
            .filledQuantity(order.getQuantity())
            .averagePrice(order.getPrice())
            .build();

        // When
        orderEventPublisher.publishOrderFilled(order);

        // Then
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ConsumerRecord<String, OrderFilledEvent> record = 
                KafkaTestUtils.getSingleRecord(orderFilledConsumer, "orders.filled");
            
            assertThat(record.value().getOrderId()).isEqualTo(order.getId());
            assertThat(record.value().getFilledQuantity()).isEqualByComparingTo(order.getFilledQuantity());
            assertThat(record.value().getAveragePrice()).isEqualByComparingTo(order.getAveragePrice());
        });
    }

    private <T> Consumer<String, T> createConsumer(Class<T> valueType) {
        Map<String, Object> props = consumerFactory.getConfigurationProperties();
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        
        JsonDeserializer<T> valueDeserializer = new JsonDeserializer<>(valueType);
        valueDeserializer.addTrustedPackages("*");
        
        return new DefaultKafkaConsumerFactory<>(
            props,
            new StringDeserializer(),
            valueDeserializer
        ).createConsumer();
    }
}
```

### API Integration Tests

```java
// src/test/java/com/orion/order/controller/OrderControllerIntegrationTest.java
package com.orion.order.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orion.IntegrationTest;
import com.orion.order.domain.Order;
import com.orion.order.domain.OrderStatus;
import com.orion.order.dto.CreateOrderRequest;
import com.orion.order.repository.OrderRepository;
import com.orion.testutil.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@IntegrationTest
@DisplayName("Order Controller Integration Tests")
class OrderControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderRepository orderRepository;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
    }

    @Nested
    @DisplayName("POST /api/v1/orders")
    class CreateOrderEndpoint {

        @Test
        @WithMockUser(username = "trader", roles = "TRADER")
        @DisplayName("should create order with valid request")
        void shouldCreateOrder() throws Exception {
            // Given
            CreateOrderRequest request = TestDataFactory.validCreateOrderRequest().build();

            // When
            ResultActions result = mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

            // Then
            result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.quantity").value(100));
        }

        @Test
        @WithMockUser(username = "trader", roles = "TRADER")
        @DisplayName("should return 400 for invalid request")
        void shouldReturn400ForInvalidRequest() throws Exception {
            // Given
            CreateOrderRequest request = CreateOrderRequest.builder()
                .symbol("")  // Invalid: empty symbol
                .quantity(BigDecimal.valueOf(-100))  // Invalid: negative quantity
                .build();

            // When
            ResultActions result = mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

            // Then
            result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors", hasSize(greaterThanOrEqualTo(2))));
        }

        @Test
        @DisplayName("should return 401 for unauthenticated request")
        void shouldReturn401ForUnauthenticated() throws Exception {
            // Given
            CreateOrderRequest request = TestDataFactory.validCreateOrderRequest().build();

            // When
            ResultActions result = mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

            // Then
            result.andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/orders/{id}")
    class GetOrderEndpoint {

        @Test
        @WithMockUser(username = "trader", roles = "TRADER")
        @DisplayName("should return order by id")
        void shouldReturnOrderById() throws Exception {
            // Given
            Order order = orderRepository.save(TestDataFactory.validOrder().build());

            // When
            ResultActions result = mockMvc.perform(get("/api/v1/orders/{id}", order.getId()));

            // Then
            result.andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(order.getId().toString()))
                .andExpect(jsonPath("$.symbol").value(order.getSymbol()));
        }

        @Test
        @WithMockUser(username = "trader", roles = "TRADER")
        @DisplayName("should return 404 for non-existent order")
        void shouldReturn404ForNonExistent() throws Exception {
            // When
            ResultActions result = mockMvc.perform(get("/api/v1/orders/{id}", UUID.randomUUID()));

            // Then
            result.andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/orders/{id}")
    class CancelOrderEndpoint {

        @Test
        @WithMockUser(username = "trader", roles = "TRADER")
        @DisplayName("should cancel pending order")
        void shouldCancelPendingOrder() throws Exception {
            // Given
            Order order = orderRepository.save(
                TestDataFactory.validOrder()
                    .status(OrderStatus.PENDING)
                    .build()
            );

            // When
            ResultActions result = mockMvc.perform(delete("/api/v1/orders/{id}", order.getId()));

            // Then
            result.andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        }

        @Test
        @WithMockUser(username = "trader", roles = "TRADER")
        @DisplayName("should return 409 for already filled order")
        void shouldReturn409ForFilledOrder() throws Exception {
            // Given
            Order order = orderRepository.save(
                TestDataFactory.validOrder()
                    .status(OrderStatus.FILLED)
                    .build()
            );

            // When
            ResultActions result = mockMvc.perform(delete("/api/v1/orders/{id}", order.getId()));

            // Then
            result.andExpect(status().isConflict());
        }
    }
}
```

### LocalStack AWS Integration Tests

```java
// src/test/java/com/orion/aws/S3IntegrationTest.java
package com.orion.aws;

import com.orion.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.localstack.LocalStackContainer;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@DisplayName("S3 Integration Tests")
class S3IntegrationTest {

    @Autowired
    private S3Client s3Client;

    @Autowired
    private LocalStackContainer localStack;

    private static final String TEST_BUCKET = "test-bucket";

    @BeforeEach
    void setUp() {
        // Create test bucket if not exists
        try {
            s3Client.createBucket(CreateBucketRequest.builder()
                .bucket(TEST_BUCKET)
                .build());
        } catch (BucketAlreadyExistsException | BucketAlreadyOwnedByYouException e) {
            // Bucket already exists, ignore
        }
    }

    @Test
    @DisplayName("should upload and download file")
    void shouldUploadAndDownloadFile() {
        // Given
        String key = "test-file.txt";
        String content = "Hello, World!";

        // When - Upload
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(TEST_BUCKET)
                .key(key)
                .build(),
            RequestBody.fromString(content)
        );

        // Then - Download and verify
        GetObjectResponse response = s3Client.getObject(
            GetObjectRequest.builder()
                .bucket(TEST_BUCKET)
                .key(key)
                .build()
        ).response();

        assertThat(response.contentLength()).isGreaterThan(0);
    }
}
```

## Definition of Done

- [ ] Testcontainers configured
- [ ] PostgreSQL container working
- [ ] Kafka container working
- [ ] Redis container working
- [ ] LocalStack for AWS services
- [ ] Repository tests passing
- [ ] Kafka event tests passing
- [ ] API integration tests
- [ ] Test data management
- [ ] Documentation complete

## Test Cases

```yaml
test-cases:
  - name: "Database rollback"
    given: "Test modifies database"
    when: "Test completes"
    then: "Changes rolled back"
  
  - name: "Container reuse"
    given: "Multiple test classes"
    when: "Tests run sequentially"
    then: "Containers reused for speed"
  
  - name: "Kafka message verification"
    given: "Event published"
    when: "Consumer reads topic"
    then: "Message content verified"
```
