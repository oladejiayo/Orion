# US-17-05: Performance Testing

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-17-05 |
| **Epic** | Epic 17: Testing & Quality |
| **Title** | Performance Testing |
| **Priority** | High |
| **Story Points** | 8 |
| **Status** | Ready for Development |

## User Story

**As a** performance engineer  
**I want** comprehensive load and stress testing  
**So that** we can validate system performance under production-like conditions

## Acceptance Criteria

### AC1: Load Testing with k6
- **Given** k6 test framework
- **When** load tests execute
- **Then**:
  - Concurrent user simulation
  - Realistic traffic patterns
  - Response time measurements
  - Throughput validation

### AC2: Stress Testing
- **Given** stress test scenarios
- **When** system under stress
- **Then**:
  - Breaking point identified
  - Graceful degradation
  - Recovery behavior validated
  - Resource limits documented

### AC3: Soak Testing
- **Given** extended test duration
- **When** soak test runs
- **Then**:
  - Memory leak detection
  - Connection pool stability
  - Performance consistency
  - Resource trend analysis

### AC4: Performance Baselines
- **Given** performance tests
- **When** results collected
- **Then**:
  - Baseline established
  - Regression detection
  - Historical trends
  - SLA validation

### AC5: Backend Performance (Gatling)
- **Given** Java services
- **When** Gatling tests run
- **Then**:
  - API latency measured
  - Database performance
  - Kafka throughput
  - Service dependencies

## Technical Specification

### k6 Configuration

```javascript
// k6/config.js
export const environments = {
  development: {
    baseUrl: 'http://localhost:8080',
    thresholds: {
      http_req_duration: ['p(95)<500'],
      http_req_failed: ['rate<0.05'],
    },
  },
  staging: {
    baseUrl: 'https://staging-api.orion.example.com',
    thresholds: {
      http_req_duration: ['p(95)<300', 'p(99)<500'],
      http_req_failed: ['rate<0.01'],
    },
  },
  production: {
    baseUrl: 'https://api.orion.example.com',
    thresholds: {
      http_req_duration: ['p(95)<200', 'p(99)<400'],
      http_req_failed: ['rate<0.001'],
    },
  },
};

export const scenarios = {
  // Smoke test - verify system works
  smoke: {
    executor: 'constant-vus',
    vus: 1,
    duration: '1m',
  },
  
  // Average load test
  average_load: {
    executor: 'ramping-vus',
    startVUs: 0,
    stages: [
      { duration: '2m', target: 50 },
      { duration: '5m', target: 50 },
      { duration: '2m', target: 0 },
    ],
  },
  
  // Stress test - find breaking point
  stress: {
    executor: 'ramping-vus',
    startVUs: 0,
    stages: [
      { duration: '2m', target: 100 },
      { duration: '5m', target: 100 },
      { duration: '2m', target: 200 },
      { duration: '5m', target: 200 },
      { duration: '2m', target: 300 },
      { duration: '5m', target: 300 },
      { duration: '5m', target: 0 },
    ],
  },
  
  // Spike test - sudden traffic
  spike: {
    executor: 'ramping-vus',
    startVUs: 0,
    stages: [
      { duration: '30s', target: 50 },
      { duration: '1m', target: 50 },
      { duration: '10s', target: 500 },
      { duration: '3m', target: 500 },
      { duration: '10s', target: 50 },
      { duration: '2m', target: 50 },
      { duration: '30s', target: 0 },
    ],
  },
  
  // Soak test - extended duration
  soak: {
    executor: 'constant-vus',
    vus: 100,
    duration: '4h',
  },
};
```

### k6 Order Service Load Test

```javascript
// k6/tests/order-service-load.js
import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { randomItem, randomIntBetween } from 'https://jslib.k6.io/k6-utils/1.2.0/index.js';

// Custom metrics
const orderCreationDuration = new Trend('order_creation_duration');
const orderSuccessRate = new Rate('order_success_rate');
const ordersCreated = new Counter('orders_created');

// Test configuration
export const options = {
  scenarios: {
    order_flow: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '2m', target: 50 },
        { duration: '5m', target: 50 },
        { duration: '2m', target: 100 },
        { duration: '5m', target: 100 },
        { duration: '2m', target: 0 },
      ],
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<300', 'p(99)<500'],
    http_req_failed: ['rate<0.01'],
    order_creation_duration: ['p(95)<500'],
    order_success_rate: ['rate>0.99'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SYMBOLS = ['AAPL', 'GOOGL', 'MSFT', 'AMZN', 'TSLA', 'META', 'NVDA'];

// Setup - authenticate and get token
export function setup() {
  const loginRes = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({
    email: __ENV.TEST_USER_EMAIL,
    password: __ENV.TEST_USER_PASSWORD,
  }), {
    headers: { 'Content-Type': 'application/json' },
  });

  check(loginRes, { 'login successful': (r) => r.status === 200 });

  return {
    token: loginRes.json('accessToken'),
    userId: loginRes.json('userId'),
  };
}

export default function(data) {
  const headers = {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${data.token}`,
  };

  group('Order Lifecycle', () => {
    // Create order
    const orderPayload = JSON.stringify({
      symbol: randomItem(SYMBOLS),
      side: randomItem(['BUY', 'SELL']),
      type: 'LIMIT',
      quantity: randomIntBetween(10, 1000),
      price: randomIntBetween(100, 500) + Math.random(),
    });

    const createStart = new Date();
    const createRes = http.post(`${BASE_URL}/api/v1/orders`, orderPayload, { headers });
    const createDuration = new Date() - createStart;
    
    orderCreationDuration.add(createDuration);
    
    const createSuccess = check(createRes, {
      'order created': (r) => r.status === 201,
      'order has id': (r) => r.json('id') !== undefined,
    });
    
    orderSuccessRate.add(createSuccess);
    
    if (createSuccess) {
      ordersCreated.add(1);
      const orderId = createRes.json('id');

      // Get order details
      sleep(0.5);
      const getRes = http.get(`${BASE_URL}/api/v1/orders/${orderId}`, { headers });
      check(getRes, {
        'order retrieved': (r) => r.status === 200,
        'order status valid': (r) => ['PENDING', 'FILLED', 'CANCELLED'].includes(r.json('status')),
      });

      // List orders
      sleep(0.5);
      const listRes = http.get(`${BASE_URL}/api/v1/orders?page=0&size=20`, { headers });
      check(listRes, {
        'orders listed': (r) => r.status === 200,
        'has content': (r) => r.json('content') !== undefined,
      });
    }
  });

  group('Position Query', () => {
    const positionsRes = http.get(`${BASE_URL}/api/v1/positions`, { headers });
    check(positionsRes, {
      'positions retrieved': (r) => r.status === 200,
      'response is array': (r) => Array.isArray(r.json()),
    });
  });

  group('Market Data', () => {
    const symbol = randomItem(SYMBOLS);
    const quoteRes = http.get(`${BASE_URL}/api/v1/market-data/quotes/${symbol}`, { headers });
    check(quoteRes, {
      'quote retrieved': (r) => r.status === 200,
      'has bid/ask': (r) => r.json('bid') !== undefined && r.json('ask') !== undefined,
    });
  });

  sleep(randomIntBetween(1, 3));
}

export function teardown(data) {
  console.log('Test completed. Cleaning up...');
}

// HTML report generation hook
export function handleSummary(data) {
  return {
    'report.html': htmlReport(data),
    'summary.json': JSON.stringify(data),
    stdout: textSummary(data, { indent: ' ', enableColors: true }),
  };
}
```

### WebSocket Performance Test

```javascript
// k6/tests/websocket-load.js
import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Rate, Counter, Trend } from 'k6/metrics';

const messageLatency = new Trend('ws_message_latency');
const messagesReceived = new Counter('ws_messages_received');
const connectionErrors = new Rate('ws_connection_errors');

export const options = {
  stages: [
    { duration: '1m', target: 100 },
    { duration: '5m', target: 100 },
    { duration: '1m', target: 500 },
    { duration: '5m', target: 500 },
    { duration: '2m', target: 0 },
  ],
  thresholds: {
    ws_message_latency: ['p(95)<100'],
    ws_connection_errors: ['rate<0.01'],
  },
};

const WS_URL = __ENV.WS_URL || 'ws://localhost:8080/ws/market-data';

export default function() {
  const symbols = ['AAPL', 'GOOGL', 'MSFT'];
  
  const res = ws.connect(WS_URL, {}, function(socket) {
    socket.on('open', () => {
      // Subscribe to symbols
      symbols.forEach(symbol => {
        socket.send(JSON.stringify({
          action: 'subscribe',
          symbol: symbol,
        }));
      });
    });

    socket.on('message', (data) => {
      const message = JSON.parse(data);
      const latency = Date.now() - message.timestamp;
      messageLatency.add(latency);
      messagesReceived.add(1);

      check(message, {
        'has symbol': (m) => m.symbol !== undefined,
        'has price': (m) => m.price !== undefined,
        'latency < 100ms': () => latency < 100,
      });
    });

    socket.on('error', (e) => {
      connectionErrors.add(1);
      console.error('WebSocket error:', e);
    });

    // Keep connection open for duration
    socket.setTimeout(() => {
      symbols.forEach(symbol => {
        socket.send(JSON.stringify({
          action: 'unsubscribe',
          symbol: symbol,
        }));
      });
      socket.close();
    }, 60000);
  });

  check(res, { 'connection successful': (r) => r && r.status === 101 });
}
```

### Gatling Simulation (Java)

```scala
// src/test/scala/com/orion/performance/OrderServiceSimulation.scala
package com.orion.performance

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

class OrderServiceSimulation extends Simulation {

  val httpProtocol = http
    .baseUrl(sys.env.getOrElse("BASE_URL", "http://localhost:8080"))
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .shareConnections

  // Data feeders
  val symbolFeeder = csv("symbols.csv").random
  val userFeeder = csv("users.csv").circular

  // Authentication
  val authenticate = exec(
    http("Login")
      .post("/api/v1/auth/login")
      .body(StringBody("""{"email":"${email}","password":"${password}"}"""))
      .check(
        status.is(200),
        jsonPath("$.accessToken").saveAs("token"),
        jsonPath("$.userId").saveAs("userId")
      )
  ).exitHereIfFailed

  // Order creation scenario
  val createOrder = exec(
    http("Create Order")
      .post("/api/v1/orders")
      .header("Authorization", "Bearer ${token}")
      .body(StringBody(
        """{
          "symbol": "${symbol}",
          "side": "${side}",
          "type": "LIMIT",
          "quantity": ${quantity},
          "price": ${price}
        }"""
      ))
      .check(
        status.in(201, 400), // 400 for validation errors
        jsonPath("$.id").optional.saveAs("orderId")
      )
  )

  val getOrder = exec(
    http("Get Order")
      .get("/api/v1/orders/${orderId}")
      .header("Authorization", "Bearer ${token}")
      .check(status.is(200))
  )

  val listOrders = exec(
    http("List Orders")
      .get("/api/v1/orders")
      .header("Authorization", "Bearer ${token}")
      .queryParam("page", "0")
      .queryParam("size", "20")
      .check(
        status.is(200),
        jsonPath("$.totalElements").saveAs("totalOrders")
      )
  )

  val getPositions = exec(
    http("Get Positions")
      .get("/api/v1/positions")
      .header("Authorization", "Bearer ${token}")
      .check(status.is(200))
  )

  val getMarketData = feed(symbolFeeder).exec(
    http("Get Quote")
      .get("/api/v1/market-data/quotes/${symbol}")
      .header("Authorization", "Bearer ${token}")
      .check(
        status.is(200),
        jsonPath("$.bid").exists,
        jsonPath("$.ask").exists
      )
  )

  // Trading scenario
  val tradingScenario = scenario("Trading Flow")
    .feed(userFeeder)
    .exec(authenticate)
    .exec(getPositions)
    .pause(1.second, 2.seconds)
    .repeat(10) {
      feed(symbolFeeder)
        .feed(Iterator.continually(Map(
          "side" -> (if (scala.util.Random.nextBoolean()) "BUY" else "SELL"),
          "quantity" -> (scala.util.Random.nextInt(990) + 10),
          "price" -> (scala.util.Random.nextDouble() * 400 + 100)
        )))
        .exec(createOrder)
        .pause(500.milliseconds, 1.second)
        .doIf("${orderId.exists()}") {
          exec(getOrder)
        }
    }
    .exec(listOrders)

  // Market data scenario
  val marketDataScenario = scenario("Market Data")
    .feed(userFeeder)
    .exec(authenticate)
    .repeat(100) {
      exec(getMarketData)
        .pause(100.milliseconds, 500.milliseconds)
    }

  // Load profiles
  setUp(
    tradingScenario.inject(
      rampUsers(100).during(2.minutes),
      constantUsersPerSec(20).during(5.minutes),
      rampUsersPerSec(20).to(50).during(2.minutes),
      constantUsersPerSec(50).during(5.minutes)
    ).protocols(httpProtocol),
    
    marketDataScenario.inject(
      rampUsers(200).during(2.minutes),
      constantUsersPerSec(50).during(10.minutes)
    ).protocols(httpProtocol)
  ).assertions(
    global.responseTime.percentile(95).lt(300),
    global.responseTime.percentile(99).lt(500),
    global.successfulRequests.percent.gt(99),
    details("Create Order").responseTime.percentile(95).lt(500),
    details("Get Quote").responseTime.percentile(95).lt(100)
  )
}
```

### Database Performance Test

```java
// src/test/java/com/orion/performance/DatabasePerformanceTest.java
package com.orion.performance;

import com.orion.IntegrationTest;
import com.orion.order.domain.Order;
import com.orion.order.repository.OrderRepository;
import com.orion.testutil.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("Database Performance Tests")
class DatabasePerformanceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("perf_test")
        .withReuse(true);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
    }

    @Test
    @DisplayName("Bulk insert performance - 10,000 orders under 5 seconds")
    void bulkInsertPerformance() {
        List<Order> orders = IntStream.range(0, 10_000)
            .mapToObj(i -> TestDataFactory.validOrder().build())
            .toList();

        Instant start = Instant.now();
        orderRepository.saveAll(orders);
        Duration duration = Duration.between(start, Instant.now());

        assertThat(duration).isLessThan(Duration.ofSeconds(5));
        assertThat(orderRepository.count()).isEqualTo(10_000);

        System.out.printf("Bulk insert of 10,000 orders: %d ms%n", duration.toMillis());
    }

    @Test
    @DisplayName("Concurrent read performance - 100 concurrent queries")
    void concurrentReadPerformance() throws Exception {
        // Setup: create test data
        List<Order> orders = IntStream.range(0, 1_000)
            .mapToObj(i -> TestDataFactory.validOrder().build())
            .toList();
        orderRepository.saveAll(orders);

        ExecutorService executor = Executors.newFixedThreadPool(100);
        List<CompletableFuture<Long>> futures = new ArrayList<>();

        Instant start = Instant.now();

        for (int i = 0; i < 100; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                Instant queryStart = Instant.now();
                orderRepository.findAll();
                return Duration.between(queryStart, Instant.now()).toMillis();
            }, executor));
        }

        List<Long> durations = futures.stream()
            .map(CompletableFuture::join)
            .toList();

        Duration totalDuration = Duration.between(start, Instant.now());

        double avgDuration = durations.stream().mapToLong(Long::longValue).average().orElse(0);
        long maxDuration = durations.stream().mapToLong(Long::longValue).max().orElse(0);

        System.out.printf("100 concurrent queries: total=%dms, avg=%,.0fms, max=%dms%n",
            totalDuration.toMillis(), avgDuration, maxDuration);

        assertThat(totalDuration).isLessThan(Duration.ofSeconds(10));
        assertThat(maxDuration).isLessThan(1000);

        executor.shutdown();
    }

    @Test
    @DisplayName("Query with index performance")
    void indexedQueryPerformance() {
        // Setup: create test data with specific client
        String testClientId = "perf-test-client";
        List<Order> orders = IntStream.range(0, 10_000)
            .mapToObj(i -> TestDataFactory.validOrder()
                .clientId(i < 100 ? testClientId : "other-client-" + i)
                .build())
            .toList();
        orderRepository.saveAll(orders);

        // Warm up
        orderRepository.findByClientId(testClientId);

        // Test indexed query performance
        Instant start = Instant.now();
        List<Order> clientOrders = orderRepository.findByClientId(testClientId);
        Duration duration = Duration.between(start, Instant.now());

        assertThat(clientOrders).hasSize(100);
        assertThat(duration).isLessThan(Duration.ofMillis(100));

        System.out.printf("Indexed query (100 of 10,000): %d ms%n", duration.toMillis());
    }
}
```

### CI/CD Performance Pipeline

```yaml
# .github/workflows/performance-tests.yml
name: Performance Tests

on:
  schedule:
    - cron: '0 2 * * *'  # Daily at 2 AM
  workflow_dispatch:
    inputs:
      environment:
        description: 'Target environment'
        required: true
        default: 'staging'
        type: choice
        options:
          - staging
          - production
      scenario:
        description: 'Test scenario'
        required: true
        default: 'average_load'
        type: choice
        options:
          - smoke
          - average_load
          - stress
          - spike
          - soak

env:
  K6_CLOUD_TOKEN: ${{ secrets.K6_CLOUD_TOKEN }}

jobs:
  k6-load-test:
    runs-on: ubuntu-latest
    
    steps:
      - uses: actions/checkout@v4
      
      - name: Setup k6
        uses: grafana/setup-k6-action@v1
      
      - name: Run k6 Load Test
        run: |
          k6 run \
            --env BASE_URL=${{ vars.BASE_URL }} \
            --env TEST_USER_EMAIL=${{ secrets.TEST_USER_EMAIL }} \
            --env TEST_USER_PASSWORD=${{ secrets.TEST_USER_PASSWORD }} \
            --out cloud \
            k6/tests/order-service-load.js
      
      - name: Upload Results
        uses: actions/upload-artifact@v4
        with:
          name: k6-results
          path: |
            report.html
            summary.json
          retention-days: 30

  gatling-test:
    runs-on: ubuntu-latest
    
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'maven'
      
      - name: Run Gatling Tests
        run: |
          cd services/order-service
          mvn gatling:test \
            -Dgatling.simulationClass=com.orion.performance.OrderServiceSimulation \
            -DBASE_URL=${{ vars.BASE_URL }}
      
      - name: Upload Gatling Report
        uses: actions/upload-artifact@v4
        with:
          name: gatling-report
          path: services/order-service/target/gatling/
          retention-days: 30

  analyze-results:
    needs: [k6-load-test, gatling-test]
    runs-on: ubuntu-latest
    
    steps:
      - name: Download k6 Results
        uses: actions/download-artifact@v4
        with:
          name: k6-results
          path: results/k6
      
      - name: Download Gatling Report
        uses: actions/download-artifact@v4
        with:
          name: gatling-report
          path: results/gatling
      
      - name: Analyze and Report
        run: |
          # Parse and analyze results
          python scripts/analyze-perf-results.py \
            --k6-results results/k6/summary.json \
            --gatling-results results/gatling/
      
      - name: Post to Slack
        if: failure()
        uses: slackapi/slack-github-action@v1
        with:
          payload: |
            {
              "text": "⚠️ Performance Test Failed",
              "blocks": [
                {
                  "type": "section",
                  "text": {
                    "type": "mrkdwn",
                    "text": "*Performance Regression Detected*\nEnvironment: ${{ inputs.environment }}\nScenario: ${{ inputs.scenario }}"
                  }
                }
              ]
            }
        env:
          SLACK_WEBHOOK_URL: ${{ secrets.SLACK_WEBHOOK }}
```

## Definition of Done

- [ ] k6 load tests implemented
- [ ] Gatling simulations created
- [ ] WebSocket performance tests
- [ ] Database performance tests
- [ ] Performance baselines established
- [ ] CI/CD integration complete
- [ ] Dashboards configured
- [ ] Alerting on regression
- [ ] Documentation complete

## Test Cases

```yaml
test-cases:
  - name: "Load test baseline"
    given: "System under normal load"
    when: "100 concurrent users"
    then: "P95 latency < 300ms"
  
  - name: "Stress test limits"
    given: "System under stress"
    when: "Load increased until failure"
    then: "Breaking point documented"
  
  - name: "Soak test stability"
    given: "Extended test duration"
    when: "4 hours at 100 users"
    then: "No memory leaks, stable performance"
```
