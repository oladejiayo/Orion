# US-17-01: Unit Testing Framework

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-17-01 |
| **Epic** | Epic 17: Testing & Quality |
| **Title** | Unit Testing Framework |
| **Priority** | Critical |
| **Story Points** | 8 |
| **Status** | Ready for Development |

## User Story

**As a** developer  
**I want** a comprehensive unit testing framework  
**So that** I can verify business logic with confidence

## Acceptance Criteria

### AC1: Java Unit Testing
- **Given** Spring Boot services
- **When** unit tests run
- **Then**:
  - JUnit 5 with extensions
  - Mockito for mocking
  - AssertJ for assertions
  - 80%+ code coverage

### AC2: TypeScript/React Testing
- **Given** frontend application
- **When** tests run
- **Then**:
  - Vitest for unit tests
  - React Testing Library
  - Mock service worker
  - Component coverage

### AC3: Test Organization
- **Given** test suites
- **When** organized
- **Then**:
  - Follows naming conventions
  - Grouped by feature
  - Fast execution
  - Isolated tests

### AC4: Mocking Strategy
- **Given** external dependencies
- **When** mocking applied
- **Then**:
  - Repository layer mocked
  - External APIs mocked
  - Time/random controlled
  - No flakiness

### AC5: Coverage Reporting
- **Given** test execution
- **When** coverage generated
- **Then**:
  - Line coverage tracked
  - Branch coverage tracked
  - Mutation testing
  - Coverage trends

## Technical Specification

### Java Unit Testing Configuration

```xml
<!-- pom.xml - Testing Dependencies -->
<dependencies>
    <!-- JUnit 5 -->
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>5.10.1</version>
        <scope>test</scope>
    </dependency>
    
    <!-- Mockito -->
    <dependency>
        <groupId>org.mockito</groupId>
        <artifactId>mockito-core</artifactId>
        <version>5.8.0</version>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.mockito</groupId>
        <artifactId>mockito-junit-jupiter</artifactId>
        <version>5.8.0</version>
        <scope>test</scope>
    </dependency>
    
    <!-- AssertJ -->
    <dependency>
        <groupId>org.assertj</groupId>
        <artifactId>assertj-core</artifactId>
        <version>3.25.1</version>
        <scope>test</scope>
    </dependency>
    
    <!-- Spring Boot Test -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
        <exclusions>
            <exclusion>
                <groupId>org.junit.vintage</groupId>
                <artifactId>junit-vintage-engine</artifactId>
            </exclusion>
        </exclusions>
    </dependency>
    
    <!-- ArchUnit for architecture tests -->
    <dependency>
        <groupId>com.tngtech.archunit</groupId>
        <artifactId>archunit-junit5</artifactId>
        <version>1.2.1</version>
        <scope>test</scope>
    </dependency>
    
    <!-- Instancio for test data generation -->
    <dependency>
        <groupId>org.instancio</groupId>
        <artifactId>instancio-junit</artifactId>
        <version>3.7.1</version>
        <scope>test</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.jacoco</groupId>
            <artifactId>jacoco-maven-plugin</artifactId>
            <version>0.8.11</version>
            <executions>
                <execution>
                    <goals>
                        <goal>prepare-agent</goal>
                    </goals>
                </execution>
                <execution>
                    <id>report</id>
                    <phase>test</phase>
                    <goals>
                        <goal>report</goal>
                    </goals>
                </execution>
                <execution>
                    <id>check</id>
                    <goals>
                        <goal>check</goal>
                    </goals>
                    <configuration>
                        <rules>
                            <rule>
                                <element>BUNDLE</element>
                                <limits>
                                    <limit>
                                        <counter>LINE</counter>
                                        <value>COVEREDRATIO</value>
                                        <minimum>0.80</minimum>
                                    </limit>
                                    <limit>
                                        <counter>BRANCH</counter>
                                        <value>COVEREDRATIO</value>
                                        <minimum>0.70</minimum>
                                    </limit>
                                </limits>
                            </rule>
                        </rules>
                    </configuration>
                </execution>
            </executions>
        </plugin>
        
        <!-- Mutation Testing -->
        <plugin>
            <groupId>org.pitest</groupId>
            <artifactId>pitest-maven</artifactId>
            <version>1.15.3</version>
            <dependencies>
                <dependency>
                    <groupId>org.pitest</groupId>
                    <artifactId>pitest-junit5-plugin</artifactId>
                    <version>1.2.1</version>
                </dependency>
            </dependencies>
            <configuration>
                <targetClasses>
                    <param>com.orion.*</param>
                </targetClasses>
                <targetTests>
                    <param>com.orion.*</param>
                </targetTests>
                <mutationThreshold>60</mutationThreshold>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### Order Service Unit Test Example

```java
// src/test/java/com/orion/order/service/OrderServiceTest.java
package com.orion.order.service;

import com.orion.order.domain.Order;
import com.orion.order.domain.OrderStatus;
import com.orion.order.domain.OrderType;
import com.orion.order.dto.CreateOrderRequest;
import com.orion.order.dto.OrderResponse;
import com.orion.order.exception.InsufficientBalanceException;
import com.orion.order.exception.OrderValidationException;
import com.orion.order.repository.OrderRepository;
import com.orion.order.validation.OrderValidator;
import com.orion.order.event.OrderEventPublisher;
import com.orion.position.client.PositionServiceClient;
import org.instancio.Instancio;
import org.instancio.junit.InstancioExtension;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith({MockitoExtension.class, InstancioExtension.class})
@DisplayName("Order Service Unit Tests")
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;
    
    @Mock
    private OrderValidator orderValidator;
    
    @Mock
    private OrderEventPublisher eventPublisher;
    
    @Mock
    private PositionServiceClient positionClient;
    
    @Spy
    private Clock clock = Clock.fixed(Instant.parse("2024-01-15T10:00:00Z"), ZoneId.UTC);
    
    @InjectMocks
    private OrderService orderService;
    
    @Captor
    private ArgumentCaptor<Order> orderCaptor;

    @Nested
    @DisplayName("Create Order")
    class CreateOrderTests {
        
        @Test
        @DisplayName("should create order successfully with valid request")
        void shouldCreateOrderSuccessfully() {
            // Given
            CreateOrderRequest request = CreateOrderRequest.builder()
                .symbol("AAPL")
                .side(OrderSide.BUY)
                .type(OrderType.LIMIT)
                .quantity(BigDecimal.valueOf(100))
                .price(BigDecimal.valueOf(150.00))
                .clientId("client-123")
                .build();
            
            given(orderValidator.validate(any())).willReturn(true);
            given(positionClient.checkBuyingPower(anyString(), any()))
                .willReturn(true);
            given(orderRepository.save(any(Order.class)))
                .willAnswer(invocation -> {
                    Order order = invocation.getArgument(0);
                    order.setId(UUID.randomUUID());
                    return order;
                });
            
            // When
            OrderResponse response = orderService.createOrder(request);
            
            // Then
            assertThat(response)
                .isNotNull()
                .satisfies(r -> {
                    assertThat(r.getSymbol()).isEqualTo("AAPL");
                    assertThat(r.getStatus()).isEqualTo(OrderStatus.PENDING);
                    assertThat(r.getQuantity()).isEqualByComparingTo("100");
                });
            
            then(orderRepository).should().save(orderCaptor.capture());
            Order savedOrder = orderCaptor.getValue();
            assertThat(savedOrder.getCreatedAt()).isEqualTo(Instant.parse("2024-01-15T10:00:00Z"));
            
            then(eventPublisher).should().publishOrderCreated(any(Order.class));
        }
        
        @Test
        @DisplayName("should reject order when validation fails")
        void shouldRejectOrderWhenValidationFails() {
            // Given
            CreateOrderRequest request = Instancio.create(CreateOrderRequest.class);
            given(orderValidator.validate(any()))
                .willThrow(new OrderValidationException("Invalid quantity"));
            
            // When/Then
            assertThatThrownBy(() -> orderService.createOrder(request))
                .isInstanceOf(OrderValidationException.class)
                .hasMessageContaining("Invalid quantity");
            
            then(orderRepository).should(never()).save(any());
            then(eventPublisher).should(never()).publishOrderCreated(any());
        }
        
        @Test
        @DisplayName("should reject buy order when insufficient buying power")
        void shouldRejectOrderWhenInsufficientBuyingPower() {
            // Given
            CreateOrderRequest request = CreateOrderRequest.builder()
                .symbol("AAPL")
                .side(OrderSide.BUY)
                .type(OrderType.MARKET)
                .quantity(BigDecimal.valueOf(1000000))
                .clientId("client-123")
                .build();
            
            given(orderValidator.validate(any())).willReturn(true);
            given(positionClient.checkBuyingPower(anyString(), any()))
                .willReturn(false);
            
            // When/Then
            assertThatThrownBy(() -> orderService.createOrder(request))
                .isInstanceOf(InsufficientBalanceException.class);
        }
        
        @ParameterizedTest
        @EnumSource(OrderType.class)
        @DisplayName("should create order for all order types")
        void shouldCreateOrderForAllTypes(OrderType orderType) {
            // Given
            CreateOrderRequest request = CreateOrderRequest.builder()
                .symbol("AAPL")
                .side(OrderSide.BUY)
                .type(orderType)
                .quantity(BigDecimal.valueOf(100))
                .price(orderType.requiresPrice() ? BigDecimal.valueOf(150) : null)
                .clientId("client-123")
                .build();
            
            given(orderValidator.validate(any())).willReturn(true);
            given(positionClient.checkBuyingPower(anyString(), any())).willReturn(true);
            given(orderRepository.save(any())).willAnswer(i -> {
                Order o = i.getArgument(0);
                o.setId(UUID.randomUUID());
                return o;
            });
            
            // When
            OrderResponse response = orderService.createOrder(request);
            
            // Then
            assertThat(response.getType()).isEqualTo(orderType);
        }
    }
    
    @Nested
    @DisplayName("Cancel Order")
    class CancelOrderTests {
        
        @Test
        @DisplayName("should cancel pending order")
        void shouldCancelPendingOrder() {
            // Given
            UUID orderId = UUID.randomUUID();
            Order existingOrder = Order.builder()
                .id(orderId)
                .status(OrderStatus.PENDING)
                .build();
            
            given(orderRepository.findById(orderId)).willReturn(Optional.of(existingOrder));
            given(orderRepository.save(any())).willAnswer(i -> i.getArgument(0));
            
            // When
            OrderResponse response = orderService.cancelOrder(orderId);
            
            // Then
            assertThat(response.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            then(eventPublisher).should().publishOrderCancelled(any());
        }
        
        @ParameterizedTest
        @ValueSource(strings = {"FILLED", "CANCELLED", "REJECTED"})
        @DisplayName("should not cancel terminal status orders")
        void shouldNotCancelTerminalOrders(String statusName) {
            // Given
            UUID orderId = UUID.randomUUID();
            OrderStatus status = OrderStatus.valueOf(statusName);
            Order existingOrder = Order.builder()
                .id(orderId)
                .status(status)
                .build();
            
            given(orderRepository.findById(orderId)).willReturn(Optional.of(existingOrder));
            
            // When/Then
            assertThatThrownBy(() -> orderService.cancelOrder(orderId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot cancel");
        }
    }
    
    @Nested
    @DisplayName("Order Calculations")
    class OrderCalculationTests {
        
        @Test
        @DisplayName("should calculate order value correctly")
        void shouldCalculateOrderValue() {
            // Given
            BigDecimal quantity = BigDecimal.valueOf(100);
            BigDecimal price = BigDecimal.valueOf(150.50);
            
            // When
            BigDecimal value = orderService.calculateOrderValue(quantity, price);
            
            // Then
            assertThat(value).isEqualByComparingTo("15050.00");
        }
        
        @Test
        @DisplayName("should calculate commission correctly")
        void shouldCalculateCommission() {
            // Given
            BigDecimal orderValue = BigDecimal.valueOf(10000);
            
            // When
            BigDecimal commission = orderService.calculateCommission(orderValue);
            
            // Then
            assertThat(commission)
                .isGreaterThan(BigDecimal.ZERO)
                .isLessThan(orderValue.multiply(BigDecimal.valueOf(0.01)));
        }
    }
}
```

### React Component Testing

```typescript
// src/components/OrderForm/__tests__/OrderForm.test.tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { OrderForm } from '../OrderForm';
import { useCreateOrder } from '@/hooks/useOrders';
import { OrderType, OrderSide } from '@/types/order';

// Mock the hook
vi.mock('@/hooks/useOrders', () => ({
  useCreateOrder: vi.fn(),
}));

const mockCreateOrder = vi.fn();

describe('OrderForm', () => {
  let queryClient: QueryClient;
  const user = userEvent.setup();

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });
    
    vi.mocked(useCreateOrder).mockReturnValue({
      mutate: mockCreateOrder,
      mutateAsync: vi.fn(),
      isPending: false,
      isError: false,
      error: null,
      reset: vi.fn(),
    } as any);
    
    mockCreateOrder.mockClear();
  });

  const renderOrderForm = (props = {}) => {
    return render(
      <QueryClientProvider client={queryClient}>
        <OrderForm symbol="AAPL" {...props} />
      </QueryClientProvider>
    );
  };

  describe('Form Rendering', () => {
    it('should render all form fields', () => {
      renderOrderForm();
      
      expect(screen.getByLabelText(/symbol/i)).toBeInTheDocument();
      expect(screen.getByLabelText(/order type/i)).toBeInTheDocument();
      expect(screen.getByLabelText(/side/i)).toBeInTheDocument();
      expect(screen.getByLabelText(/quantity/i)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /submit/i })).toBeInTheDocument();
    });

    it('should show price field only for limit orders', async () => {
      renderOrderForm();
      
      // Initially market order selected - no price field
      expect(screen.queryByLabelText(/price/i)).not.toBeInTheDocument();
      
      // Select limit order
      await user.click(screen.getByLabelText(/order type/i));
      await user.click(screen.getByRole('option', { name: /limit/i }));
      
      // Price field should appear
      expect(screen.getByLabelText(/price/i)).toBeInTheDocument();
    });
  });

  describe('Form Submission', () => {
    it('should submit valid order', async () => {
      renderOrderForm();
      
      // Fill form
      await user.click(screen.getByLabelText(/order type/i));
      await user.click(screen.getByRole('option', { name: /limit/i }));
      
      await user.click(screen.getByRole('radio', { name: /buy/i }));
      await user.type(screen.getByLabelText(/quantity/i), '100');
      await user.type(screen.getByLabelText(/price/i), '150.50');
      
      // Submit
      await user.click(screen.getByRole('button', { name: /submit/i }));
      
      await waitFor(() => {
        expect(mockCreateOrder).toHaveBeenCalledWith({
          symbol: 'AAPL',
          type: OrderType.LIMIT,
          side: OrderSide.BUY,
          quantity: 100,
          price: 150.50,
        });
      });
    });

    it('should show validation errors for invalid input', async () => {
      renderOrderForm();
      
      // Try to submit without filling required fields
      await user.click(screen.getByRole('button', { name: /submit/i }));
      
      await waitFor(() => {
        expect(screen.getByText(/quantity is required/i)).toBeInTheDocument();
      });
      
      expect(mockCreateOrder).not.toHaveBeenCalled();
    });

    it('should disable submit button while submitting', async () => {
      vi.mocked(useCreateOrder).mockReturnValue({
        mutate: mockCreateOrder,
        isPending: true,
        isError: false,
      } as any);
      
      renderOrderForm();
      
      expect(screen.getByRole('button', { name: /submit/i })).toBeDisabled();
    });
  });

  describe('Error Handling', () => {
    it('should display error message on submission failure', async () => {
      vi.mocked(useCreateOrder).mockReturnValue({
        mutate: mockCreateOrder,
        isPending: false,
        isError: true,
        error: new Error('Insufficient buying power'),
      } as any);
      
      renderOrderForm();
      
      expect(screen.getByText(/insufficient buying power/i)).toBeInTheDocument();
    });
  });
});
```

### Test Utilities

```java
// src/test/java/com/orion/testutil/TestDataFactory.java
package com.orion.testutil;

import com.orion.order.domain.*;
import com.orion.position.domain.*;
import org.instancio.Instancio;
import org.instancio.settings.Keys;
import org.instancio.settings.Settings;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.instancio.Select.field;

public class TestDataFactory {
    
    private static final Settings DEFAULT_SETTINGS = Settings.create()
        .set(Keys.STRING_MIN_LENGTH, 5)
        .set(Keys.STRING_MAX_LENGTH, 20)
        .set(Keys.COLLECTION_MIN_SIZE, 1)
        .set(Keys.COLLECTION_MAX_SIZE, 5);
    
    public static Order.OrderBuilder validOrder() {
        return Order.builder()
            .id(UUID.randomUUID())
            .symbol("AAPL")
            .side(OrderSide.BUY)
            .type(OrderType.LIMIT)
            .quantity(BigDecimal.valueOf(100))
            .price(BigDecimal.valueOf(150.00))
            .status(OrderStatus.PENDING)
            .clientId("test-client")
            .createdAt(Instant.now());
    }
    
    public static Order randomOrder() {
        return Instancio.of(Order.class)
            .withSettings(DEFAULT_SETTINGS)
            .set(field(Order::getQuantity), BigDecimal.valueOf(100))
            .set(field(Order::getPrice), BigDecimal.valueOf(150.00))
            .create();
    }
    
    public static Position.PositionBuilder validPosition() {
        return Position.builder()
            .id(UUID.randomUUID())
            .symbol("AAPL")
            .quantity(BigDecimal.valueOf(100))
            .averageCost(BigDecimal.valueOf(145.00))
            .marketValue(BigDecimal.valueOf(15000))
            .unrealizedPnl(BigDecimal.valueOf(500))
            .accountId("account-123");
    }
    
    public static CreateOrderRequest.CreateOrderRequestBuilder validCreateOrderRequest() {
        return CreateOrderRequest.builder()
            .symbol("AAPL")
            .side(OrderSide.BUY)
            .type(OrderType.LIMIT)
            .quantity(BigDecimal.valueOf(100))
            .price(BigDecimal.valueOf(150.00))
            .clientId("client-123");
    }
}
```

### Architecture Tests

```java
// src/test/java/com/orion/ArchitectureTest.java
package com.orion;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@DisplayName("Architecture Tests")
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void setup() {
        classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.orion");
    }

    @Test
    @DisplayName("should follow layered architecture")
    void shouldFollowLayeredArchitecture() {
        layeredArchitecture()
            .consideringAllDependencies()
            .layer("Controllers").definedBy("..controller..")
            .layer("Services").definedBy("..service..")
            .layer("Repositories").definedBy("..repository..")
            .layer("Domain").definedBy("..domain..")
            
            .whereLayer("Controllers").mayNotBeAccessedByAnyLayer()
            .whereLayer("Services").mayOnlyBeAccessedByLayers("Controllers")
            .whereLayer("Repositories").mayOnlyBeAccessedByLayers("Services")
            .whereLayer("Domain").mayBeAccessedByAnyLayer()
            
            .check(classes);
    }

    @Test
    @DisplayName("controllers should not depend on repositories")
    void controllersShouldNotDependOnRepositories() {
        noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAPackage("..repository..")
            .check(classes);
    }

    @Test
    @DisplayName("services should be annotated with @Service")
    void servicesShouldBeAnnotated() {
        classes()
            .that().resideInAPackage("..service..")
            .and().haveSimpleNameEndingWith("Service")
            .should().beAnnotatedWith(org.springframework.stereotype.Service.class)
            .check(classes);
    }

    @Test
    @DisplayName("there should be no cyclic dependencies between packages")
    void noCyclicDependencies() {
        slices()
            .matching("com.orion.(*)..")
            .should().beFreeOfCycles()
            .check(classes);
    }
}
```

## Definition of Done

- [ ] JUnit 5 configured with extensions
- [ ] Mockito integration working
- [ ] AssertJ assertions available
- [ ] Instancio for test data
- [ ] React Testing Library setup
- [ ] Vitest configured for frontend
- [ ] 80% line coverage achieved
- [ ] 70% branch coverage achieved
- [ ] Mutation testing enabled
- [ ] Architecture tests passing
- [ ] Documentation complete

## Test Cases

```yaml
test-cases:
  - name: "Coverage threshold enforcement"
    given: "Test suite runs"
    when: "Coverage below 80%"
    then: "Build fails"
  
  - name: "Test isolation"
    given: "Tests run in parallel"
    when: "Tests use shared state"
    then: "No test interference"
  
  - name: "Mutation testing"
    given: "Mutation tests run"
    when: "Mutations survive"
    then: "Test gaps identified"
```
