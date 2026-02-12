# US-16-01: Metrics & Monitoring Stack

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-16-01 |
| **Epic** | Epic 16: Observability |
| **Title** | Metrics & Monitoring Stack |
| **Priority** | Critical |
| **Story Points** | 13 |
| **Status** | Ready for Development |

## User Story

**As a** site reliability engineer  
**I want** a comprehensive metrics collection and visualization stack  
**So that** I can monitor system health and performance in real-time

## Acceptance Criteria

### AC1: Prometheus Deployment
- **Given** the EKS cluster
- **When** Prometheus deployed
- **Then**:
  - High availability configuration
  - Service discovery enabled
  - Retention configured
  - Recording rules applied

### AC2: VictoriaMetrics for Long-term Storage
- **Given** metrics data volume
- **When** VictoriaMetrics configured
- **Then**:
  - Remote write from Prometheus
  - 90-day retention
  - Downsampling enabled
  - Query federation

### AC3: Grafana Deployment
- **Given** visualization needs
- **When** Grafana deployed
- **Then**:
  - SSO authentication
  - Data source configuration
  - Folder organization
  - Team permissions

### AC4: Application Metrics
- **Given** Spring Boot services
- **When** metrics exposed
- **Then**:
  - Micrometer integration
  - Custom business metrics
  - JVM metrics
  - HTTP request metrics

### AC5: Kubernetes Metrics
- **Given** cluster infrastructure
- **When** collectors deployed
- **Then**:
  - kube-state-metrics running
  - node-exporter on all nodes
  - Container metrics collected
  - Pod resource metrics

## Technical Specification

### Prometheus Helm Values

```yaml
# helm/prometheus/values.yaml
prometheus:
  prometheusSpec:
    replicas: 2
    
    # Resource allocation
    resources:
      requests:
        memory: "4Gi"
        cpu: "1000m"
      limits:
        memory: "8Gi"
        cpu: "2000m"
    
    # Storage configuration
    retention: 15d
    retentionSize: "50GB"
    
    storageSpec:
      volumeClaimTemplate:
        spec:
          storageClassName: gp3
          accessModes: ["ReadWriteOnce"]
          resources:
            requests:
              storage: 100Gi
    
    # External labels for federation
    externalLabels:
      cluster: orion-prod
      environment: production
      region: us-east-1
    
    # Remote write to VictoriaMetrics
    remoteWrite:
      - url: "http://victoria-metrics-single:8428/api/v1/write"
        queueConfig:
          capacity: 10000
          maxSamplesPerSend: 5000
          maxShards: 50
    
    # Service discovery
    serviceMonitorSelector:
      matchLabels:
        release: prometheus
    
    podMonitorSelector:
      matchLabels:
        release: prometheus
    
    # Security context
    securityContext:
      runAsNonRoot: true
      runAsUser: 65534
      fsGroup: 65534
    
    # Affinity for HA
    affinity:
      podAntiAffinity:
        requiredDuringSchedulingIgnoredDuringExecution:
          - labelSelector:
              matchExpressions:
                - key: app.kubernetes.io/name
                  operator: In
                  values:
                    - prometheus
            topologyKey: kubernetes.io/hostname

  # Alertmanager configuration
  alertmanager:
    enabled: true
    alertmanagerSpec:
      replicas: 2
      storage:
        volumeClaimTemplate:
          spec:
            storageClassName: gp3
            accessModes: ["ReadWriteOnce"]
            resources:
              requests:
                storage: 10Gi

# Grafana configuration
grafana:
  enabled: true
  replicas: 2
  
  adminPassword: "${GRAFANA_ADMIN_PASSWORD}"
  
  persistence:
    enabled: true
    size: 10Gi
    storageClassName: gp3
  
  grafana.ini:
    server:
      root_url: https://grafana.orion.example.com
    auth.generic_oauth:
      enabled: true
      name: Orion SSO
      client_id: ${OAUTH_CLIENT_ID}
      client_secret: ${OAUTH_CLIENT_SECRET}
      scopes: openid profile email
      auth_url: https://auth.orion.example.com/oauth/authorize
      token_url: https://auth.orion.example.com/oauth/token
      api_url: https://auth.orion.example.com/userinfo
      role_attribute_path: contains(groups[*], 'admin') && 'Admin' || 'Viewer'
    security:
      admin_password: ${GRAFANA_ADMIN_PASSWORD}
      secret_key: ${GRAFANA_SECRET_KEY}
    database:
      type: postgres
      host: aurora-postgres.orion.local
      name: grafana
      user: grafana
      password: ${GRAFANA_DB_PASSWORD}
  
  datasources:
    datasources.yaml:
      apiVersion: 1
      datasources:
        - name: Prometheus
          type: prometheus
          url: http://prometheus-operated:9090
          access: proxy
          isDefault: true
        - name: VictoriaMetrics
          type: prometheus
          url: http://victoria-metrics-single:8428
          access: proxy
        - name: Loki
          type: loki
          url: http://loki-gateway:3100
          access: proxy
        - name: Tempo
          type: tempo
          url: http://tempo-query-frontend:3100
          access: proxy

# kube-state-metrics
kubeStateMetrics:
  enabled: true

# Node exporter
nodeExporter:
  enabled: true
  resources:
    requests:
      memory: "64Mi"
      cpu: "100m"
    limits:
      memory: "128Mi"
      cpu: "200m"
```

### VictoriaMetrics Deployment

```yaml
# helm/victoria-metrics/values.yaml
victoria-metrics-single:
  server:
    enabled: true
    
    resources:
      requests:
        memory: "4Gi"
        cpu: "1000m"
      limits:
        memory: "8Gi"
        cpu: "2000m"
    
    persistentVolume:
      enabled: true
      size: 500Gi
      storageClass: gp3
    
    # Retention
    retentionPeriod: 90d
    
    # Downsampling
    downsampling:
      enabled: true
      retentionPolicy:
        - duration: 7d
          keep: []  # Keep all raw data
        - duration: 30d
          keep: [5m]  # 5-minute resolution
        - duration: 90d
          keep: [1h]  # 1-hour resolution
    
    extraArgs:
      envflag.enable: "true"
      envflag.prefix: VM_
      loggerFormat: json
      dedup.minScrapeInterval: 30s
    
    service:
      type: ClusterIP
      servicePort: 8428
```

### ServiceMonitor for Applications

```yaml
# kubernetes/monitoring/service-monitors.yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: orion-services
  namespace: monitoring
  labels:
    release: prometheus
spec:
  selector:
    matchLabels:
      app.kubernetes.io/part-of: orion
  namespaceSelector:
    matchNames:
      - orion
  endpoints:
    - port: http
      path: /actuator/prometheus
      interval: 30s
      scrapeTimeout: 10s
      scheme: http
      honorLabels: true
      relabelings:
        - sourceLabels: [__meta_kubernetes_pod_label_app]
          targetLabel: service
        - sourceLabels: [__meta_kubernetes_namespace]
          targetLabel: namespace
        - sourceLabels: [__meta_kubernetes_pod_name]
          targetLabel: pod
---
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: orion-frontend
  namespace: monitoring
  labels:
    release: prometheus
spec:
  selector:
    matchLabels:
      app: orion-frontend
  namespaceSelector:
    matchNames:
      - orion
  endpoints:
    - port: metrics
      path: /metrics
      interval: 30s
```

### Prometheus Recording Rules

```yaml
# kubernetes/monitoring/recording-rules.yaml
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: orion-recording-rules
  namespace: monitoring
  labels:
    release: prometheus
spec:
  groups:
    - name: orion.sla
      interval: 30s
      rules:
        # Request rate
        - record: orion:http_requests:rate5m
          expr: |
            sum(rate(http_server_requests_seconds_count{namespace="orion"}[5m])) by (service, method, uri, status)
        
        # Error rate
        - record: orion:http_errors:rate5m
          expr: |
            sum(rate(http_server_requests_seconds_count{namespace="orion",status=~"5.."}[5m])) by (service)
            /
            sum(rate(http_server_requests_seconds_count{namespace="orion"}[5m])) by (service)
        
        # Latency percentiles
        - record: orion:http_latency:p50
          expr: |
            histogram_quantile(0.50, sum(rate(http_server_requests_seconds_bucket{namespace="orion"}[5m])) by (service, le))
        
        - record: orion:http_latency:p95
          expr: |
            histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{namespace="orion"}[5m])) by (service, le))
        
        - record: orion:http_latency:p99
          expr: |
            histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{namespace="orion"}[5m])) by (service, le))
    
    - name: orion.business
      interval: 1m
      rules:
        # Orders per minute
        - record: orion:orders:rate1m
          expr: |
            sum(rate(orders_submitted_total{namespace="orion"}[1m])) by (asset_class, order_type)
        
        # Order fill rate
        - record: orion:orders:fill_rate
          expr: |
            sum(orders_filled_total{namespace="orion"})
            /
            sum(orders_submitted_total{namespace="orion"})
        
        # Position updates per minute
        - record: orion:positions:updates_rate1m
          expr: |
            sum(rate(positions_updated_total{namespace="orion"}[1m]))
    
    - name: orion.infrastructure
      interval: 30s
      rules:
        # Kafka consumer lag
        - record: orion:kafka:consumer_lag
          expr: |
            sum(kafka_consumer_lag{namespace="orion"}) by (topic, consumer_group)
        
        # Database connection pool
        - record: orion:db:connection_pool_usage
          expr: |
            sum(hikaricp_connections_active{namespace="orion"}) by (pool)
            /
            sum(hikaricp_connections_max{namespace="orion"}) by (pool)
        
        # Redis hit rate
        - record: orion:redis:hit_rate
          expr: |
            sum(rate(redis_cache_hits_total{namespace="orion"}[5m]))
            /
            (sum(rate(redis_cache_hits_total{namespace="orion"}[5m])) + sum(rate(redis_cache_misses_total{namespace="orion"}[5m])))
```

### Spring Boot Micrometer Configuration

```java
// src/main/java/com/orion/config/MetricsConfig.java
package com.orion.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class MetricsConfig {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> registry.config()
            .commonTags(Tags.of(
                Tag.of("application", "orion"),
                Tag.of("service", "${spring.application.name}"),
                Tag.of("environment", "${spring.profiles.active:unknown}")
            ))
            .meterFilter(MeterFilter.deny(id -> {
                // Filter out high-cardinality metrics
                String uri = id.getTag("uri");
                return uri != null && uri.contains("/actuator");
            }))
            .meterFilter(new MeterFilter() {
                @Override
                public DistributionStatisticConfig configure(
                    io.micrometer.core.instrument.Meter.Id id,
                    DistributionStatisticConfig config) {
                    
                    if (id.getName().startsWith("http.server.requests")) {
                        return DistributionStatisticConfig.builder()
                            .percentilesHistogram(true)
                            .percentiles(0.5, 0.75, 0.95, 0.99)
                            .serviceLevelObjectives(
                                Duration.ofMillis(50).toNanos(),
                                Duration.ofMillis(100).toNanos(),
                                Duration.ofMillis(250).toNanos(),
                                Duration.ofMillis(500).toNanos(),
                                Duration.ofSeconds(1).toNanos()
                            )
                            .minimumExpectedValue(Duration.ofMillis(1).toNanos())
                            .maximumExpectedValue(Duration.ofSeconds(10).toNanos())
                            .build()
                            .merge(config);
                    }
                    return config;
                }
            });
    }

    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }
}
```

### Custom Business Metrics

```java
// src/main/java/com/orion/metrics/BusinessMetrics.java
package com.orion.metrics;

import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class BusinessMetrics {
    
    private final Counter ordersSubmitted;
    private final Counter ordersFilled;
    private final Counter ordersRejected;
    private final Timer orderProcessingTime;
    private final DistributionSummary orderSize;
    private final AtomicInteger activeOrders;
    
    public BusinessMetrics(MeterRegistry registry) {
        // Order counters
        this.ordersSubmitted = Counter.builder("orders.submitted")
            .description("Total orders submitted")
            .tag("type", "counter")
            .register(registry);
        
        this.ordersFilled = Counter.builder("orders.filled")
            .description("Total orders filled")
            .register(registry);
        
        this.ordersRejected = Counter.builder("orders.rejected")
            .description("Total orders rejected")
            .register(registry);
        
        // Order processing timer
        this.orderProcessingTime = Timer.builder("orders.processing.time")
            .description("Time to process an order")
            .publishPercentileHistogram()
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);
        
        // Order size distribution
        this.orderSize = DistributionSummary.builder("orders.size")
            .description("Distribution of order sizes")
            .baseUnit("USD")
            .publishPercentileHistogram()
            .register(registry);
        
        // Active orders gauge
        this.activeOrders = new AtomicInteger(0);
        Gauge.builder("orders.active", activeOrders, AtomicInteger::get)
            .description("Currently active orders")
            .register(registry);
    }
    
    public void recordOrderSubmitted(String assetClass, String orderType) {
        ordersSubmitted.increment();
        activeOrders.incrementAndGet();
    }
    
    public void recordOrderFilled(double size) {
        ordersFilled.increment();
        orderSize.record(size);
        activeOrders.decrementAndGet();
    }
    
    public void recordOrderRejected(String reason) {
        ordersRejected.increment();
        activeOrders.decrementAndGet();
    }
    
    public Timer.Sample startOrderProcessing() {
        return Timer.start();
    }
    
    public void stopOrderProcessing(Timer.Sample sample) {
        sample.stop(orderProcessingTime);
    }
}
```

### Application Properties

```yaml
# application.yml - Metrics configuration
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
      base-path: /actuator
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
    prometheus:
      enabled: true
  metrics:
    export:
      prometheus:
        enabled: true
        step: 30s
    distribution:
      percentiles-histogram:
        http.server.requests: true
        method.execution: true
      slo:
        http.server.requests: 50ms,100ms,250ms,500ms,1s,5s
    tags:
      application: ${spring.application.name}
      region: ${AWS_REGION:us-east-1}
```

## Definition of Done

- [ ] Prometheus deployed with HA
- [ ] VictoriaMetrics for long-term storage
- [ ] Grafana with SSO authentication
- [ ] kube-state-metrics running
- [ ] node-exporter on all nodes
- [ ] ServiceMonitors configured
- [ ] Recording rules deployed
- [ ] Application metrics exposed
- [ ] Dashboards configured
- [ ] Documentation complete

## Test Cases

```yaml
test-cases:
  - name: "Prometheus collects metrics"
    given: "Services running in EKS"
    when: "Prometheus scrapes endpoints"
    then: "Metrics visible in Prometheus UI"
  
  - name: "Long-term storage"
    given: "Metrics older than 15 days"
    when: "Queried in Grafana"
    then: "Data returned from VictoriaMetrics"
  
  - name: "Recording rules"
    given: "Raw metrics collected"
    when: "Recording rules evaluate"
    then: "Aggregated metrics available"
```
