# US-16-03: Distributed Tracing

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-16-03 |
| **Epic** | Epic 16: Observability |
| **Title** | Distributed Tracing |
| **Priority** | High |
| **Story Points** | 8 |
| **Status** | Ready for Development |

## User Story

**As a** developer  
**I want** end-to-end distributed tracing  
**So that** I can understand request flow and identify latency bottlenecks

## Acceptance Criteria

### AC1: OpenTelemetry SDK Integration
- **Given** application services
- **When** instrumented with OTel
- **Then**:
  - Auto-instrumentation enabled
  - Custom spans created
  - Context propagation
  - Baggage support

### AC2: Tempo Backend
- **Given** trace data volume
- **When** Tempo deployed
- **Then**:
  - High-throughput ingestion
  - S3 backend storage
  - Query frontend
  - Trace-to-logs correlation

### AC3: Trace Visualization
- **Given** Grafana integration
- **When** traces queried
- **Then**:
  - Timeline view
  - Service dependency graph
  - Trace search
  - Metric-to-trace linking

### AC4: Sampling Strategy
- **Given** production traffic
- **When** sampling configured
- **Then**:
  - Head-based sampling
  - Tail-based sampling for errors
  - Rate limiting
  - Cost optimization

### AC5: Cross-Service Correlation
- **Given** trace context
- **When** request spans services
- **Then**:
  - W3C Trace Context headers
  - Correlation across Kafka
  - Database query tracing
  - HTTP client tracing

## Technical Specification

### OpenTelemetry Collector Deployment

```yaml
# kubernetes/tracing/otel-collector.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: otel-collector-config
  namespace: tracing
data:
  collector.yaml: |
    receivers:
      otlp:
        protocols:
          grpc:
            endpoint: 0.0.0.0:4317
          http:
            endpoint: 0.0.0.0:4318
      
      jaeger:
        protocols:
          thrift_http:
            endpoint: 0.0.0.0:14268
          grpc:
            endpoint: 0.0.0.0:14250
      
      zipkin:
        endpoint: 0.0.0.0:9411

    processors:
      batch:
        timeout: 5s
        send_batch_size: 1000
        send_batch_max_size: 1500
      
      memory_limiter:
        check_interval: 1s
        limit_mib: 1800
        spike_limit_mib: 500
      
      # Tail-based sampling
      tail_sampling:
        decision_wait: 10s
        num_traces: 100000
        expected_new_traces_per_sec: 1000
        policies:
          # Always sample errors
          - name: errors
            type: status_code
            status_code:
              status_codes: [ERROR]
          
          # Always sample slow traces
          - name: latency
            type: latency
            latency:
              threshold_ms: 1000
          
          # Sample 10% of successful traces
          - name: probabilistic
            type: probabilistic
            probabilistic:
              sampling_percentage: 10
          
          # Always sample specific operations
          - name: critical-operations
            type: string_attribute
            string_attribute:
              key: http.target
              values: ["/api/v1/orders", "/api/v1/trades"]
      
      # Resource detection for Kubernetes
      resourcedetection:
        detectors: [env, ec2, eks]
        timeout: 5s
        override: false
      
      # Add custom attributes
      attributes:
        actions:
          - key: environment
            value: production
            action: insert
          - key: cluster
            value: orion-prod
            action: insert

    exporters:
      otlp/tempo:
        endpoint: tempo-distributor.tracing:4317
        tls:
          insecure: true
      
      # Debug exporter for development
      logging:
        verbosity: detailed
        sampling_initial: 5
        sampling_thereafter: 200
      
      # AWS X-Ray for AWS native tracing
      awsxray:
        region: us-east-1
        indexed_attributes:
          - service.name
          - http.method
          - http.status_code

    extensions:
      health_check:
        endpoint: 0.0.0.0:13133
      zpages:
        endpoint: 0.0.0.0:55679
      pprof:
        endpoint: 0.0.0.0:1777

    service:
      extensions: [health_check, zpages, pprof]
      pipelines:
        traces:
          receivers: [otlp, jaeger, zipkin]
          processors: [memory_limiter, resourcedetection, attributes, tail_sampling, batch]
          exporters: [otlp/tempo, awsxray]
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: otel-collector
  namespace: tracing
spec:
  replicas: 3
  selector:
    matchLabels:
      app: otel-collector
  template:
    metadata:
      labels:
        app: otel-collector
    spec:
      containers:
        - name: otel-collector
          image: otel/opentelemetry-collector-contrib:0.92.0
          args:
            - --config=/etc/otel/collector.yaml
          ports:
            - containerPort: 4317  # OTLP gRPC
            - containerPort: 4318  # OTLP HTTP
            - containerPort: 14268 # Jaeger HTTP
            - containerPort: 14250 # Jaeger gRPC
            - containerPort: 9411  # Zipkin
            - containerPort: 13133 # Health check
          resources:
            requests:
              cpu: 500m
              memory: 1Gi
            limits:
              cpu: 2000m
              memory: 2Gi
          volumeMounts:
            - name: config
              mountPath: /etc/otel
          livenessProbe:
            httpGet:
              path: /
              port: 13133
            initialDelaySeconds: 10
          readinessProbe:
            httpGet:
              path: /
              port: 13133
            initialDelaySeconds: 5
      volumes:
        - name: config
          configMap:
            name: otel-collector-config
```

### Tempo Deployment

```yaml
# helm/tempo/values.yaml
tempo:
  storage:
    trace:
      backend: s3
      s3:
        bucket: orion-traces-${AWS_ACCOUNT_ID}
        region: us-east-1
        endpoint: s3.us-east-1.amazonaws.com
  
  receivers:
    otlp:
      protocols:
        grpc:
          endpoint: 0.0.0.0:4317
        http:
          endpoint: 0.0.0.0:4318
  
  query_frontend:
    search:
      concurrent_jobs: 1000
      target_bytes_per_job: 104857600

  compactor:
    compaction:
      block_retention: 168h  # 7 days
      compacted_block_retention: 1h
      compaction_window: 1h
      max_block_bytes: 107374182400  # 100GB

  distributor:
    receivers:
      otlp:
        protocols:
          grpc:
            endpoint: 0.0.0.0:4317
          http:
            endpoint: 0.0.0.0:4318
    
    ring:
      kvstore:
        store: memberlist

  ingester:
    lifecycler:
      ring:
        replication_factor: 3

  metrics_generator:
    enabled: true
    processor:
      service_graphs:
        enabled: true
        dimensions:
          - service.namespace
          - http.method
      span_metrics:
        enabled: true
        dimensions:
          - service.namespace
          - http.method
          - http.status_code
    storage:
      path: /var/tempo/generator
      wal:
      remote_write:
        - url: http://prometheus-operated:9090/api/v1/write

# Replicas configuration
distributor:
  replicas: 3
  resources:
    requests:
      cpu: 500m
      memory: 512Mi

ingester:
  replicas: 3
  resources:
    requests:
      cpu: 1000m
      memory: 2Gi
  persistence:
    enabled: true
    size: 50Gi
    storageClass: gp3

querier:
  replicas: 2
  resources:
    requests:
      cpu: 500m
      memory: 1Gi

queryFrontend:
  replicas: 2
  resources:
    requests:
      cpu: 500m
      memory: 512Mi

compactor:
  replicas: 1
  resources:
    requests:
      cpu: 500m
      memory: 1Gi
```

### Spring Boot OpenTelemetry Configuration

```java
// src/main/java/com/orion/tracing/TracingConfig.java
package com.orion.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class TracingConfig {

    @Value("${spring.application.name}")
    private String serviceName;

    @Value("${otel.exporter.otlp.endpoint:http://otel-collector:4317}")
    private String otlpEndpoint;

    @Value("${otel.traces.sampler.ratio:0.1}")
    private double samplerRatio;

    @Bean
    public OpenTelemetry openTelemetry() {
        Resource resource = Resource.getDefault()
            .merge(Resource.create(Attributes.builder()
                .put(ResourceAttributes.SERVICE_NAME, serviceName)
                .put(ResourceAttributes.SERVICE_VERSION, getVersion())
                .put(ResourceAttributes.DEPLOYMENT_ENVIRONMENT, getEnvironment())
                .put(ResourceAttributes.HOST_NAME, getHostname())
                .build()));

        OtlpGrpcSpanExporter spanExporter = OtlpGrpcSpanExporter.builder()
            .setEndpoint(otlpEndpoint)
            .setTimeout(10, TimeUnit.SECONDS)
            .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(BatchSpanProcessor.builder(spanExporter)
                .setMaxQueueSize(2048)
                .setMaxExportBatchSize(512)
                .setScheduleDelay(5, TimeUnit.SECONDS)
                .build())
            .setSampler(Sampler.traceIdRatioBased(samplerRatio))
            .build();

        return OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .buildAndRegisterGlobal();
    }

    @Bean
    public Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer(serviceName);
    }

    private String getVersion() {
        return System.getenv().getOrDefault("SERVICE_VERSION", "unknown");
    }

    private String getEnvironment() {
        return System.getenv().getOrDefault("ENVIRONMENT", "development");
    }

    private String getHostname() {
        return System.getenv().getOrDefault("HOSTNAME", "unknown");
    }
}
```

### Custom Span Instrumentation

```java
// src/main/java/com/orion/tracing/TracingService.java
package com.orion.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.function.Supplier;

@Service
public class TracingService {

    private final Tracer tracer;

    public TracingService(Tracer tracer) {
        this.tracer = tracer;
    }

    public <T> T traceOperation(String operationName, Map<String, String> attributes, Supplier<T> operation) {
        Span span = tracer.spanBuilder(operationName)
            .setSpanKind(SpanKind.INTERNAL)
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Add custom attributes
            if (attributes != null) {
                attributes.forEach((key, value) -> 
                    span.setAttribute(AttributeKey.stringKey(key), value));
            }

            T result = operation.get();
            span.setStatus(StatusCode.OK);
            return result;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    public void addBusinessEvent(String eventName, Map<String, Object> eventData) {
        Span currentSpan = Span.current();
        
        Attributes.Builder attributesBuilder = Attributes.builder();
        eventData.forEach((key, value) -> {
            if (value instanceof String) {
                attributesBuilder.put(key, (String) value);
            } else if (value instanceof Long) {
                attributesBuilder.put(key, (Long) value);
            } else if (value instanceof Double) {
                attributesBuilder.put(key, (Double) value);
            } else if (value instanceof Boolean) {
                attributesBuilder.put(key, (Boolean) value);
            }
        });

        currentSpan.addEvent(eventName, attributesBuilder.build());
    }

    public Span startDatabaseSpan(String operation, String dbSystem, String dbName) {
        return tracer.spanBuilder(operation)
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("db.system", dbSystem)
            .setAttribute("db.name", dbName)
            .startSpan();
    }

    public Span startKafkaProducerSpan(String topic) {
        return tracer.spanBuilder("kafka.produce " + topic)
            .setSpanKind(SpanKind.PRODUCER)
            .setAttribute("messaging.system", "kafka")
            .setAttribute("messaging.destination", topic)
            .setAttribute("messaging.destination_kind", "topic")
            .startSpan();
    }

    public Span startKafkaConsumerSpan(String topic, String consumerGroup) {
        return tracer.spanBuilder("kafka.consume " + topic)
            .setSpanKind(SpanKind.CONSUMER)
            .setAttribute("messaging.system", "kafka")
            .setAttribute("messaging.destination", topic)
            .setAttribute("messaging.kafka.consumer_group", consumerGroup)
            .startSpan();
    }
}
```

### Kafka Tracing Interceptor

```java
// src/main/java/com/orion/tracing/KafkaTracingConfig.java
package com.orion.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.kafka.KafkaTelemetry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

@Configuration
public class KafkaTracingConfig {

    @Bean
    public KafkaTelemetry kafkaTelemetry(OpenTelemetry openTelemetry) {
        return KafkaTelemetry.create(openTelemetry);
    }

    @Bean
    public <K, V> ProducerFactory<K, V> producerFactory(
            ProducerFactory<K, V> delegate,
            KafkaTelemetry kafkaTelemetry) {
        
        return new ProducerFactory<>() {
            @Override
            public Producer<K, V> createProducer() {
                return kafkaTelemetry.wrap(delegate.createProducer());
            }
        };
    }

    @Bean
    public <K, V> KafkaTemplate<K, V> kafkaTemplate(ProducerFactory<K, V> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public <K, V> ConcurrentKafkaListenerContainerFactory<K, V> kafkaListenerContainerFactory(
            ConsumerFactory<K, V> consumerFactory,
            KafkaTelemetry kafkaTelemetry) {
        
        ConcurrentKafkaListenerContainerFactory<K, V> factory = 
            new ConcurrentKafkaListenerContainerFactory<>();
        
        factory.setConsumerFactory(new ConsumerFactory<>() {
            @Override
            public Consumer<K, V> createConsumer(String groupId, String clientIdPrefix, String clientIdSuffix) {
                return kafkaTelemetry.wrap(
                    consumerFactory.createConsumer(groupId, clientIdPrefix, clientIdSuffix));
            }
        });
        
        return factory;
    }
}
```

### Grafana Tempo Data Source

```yaml
# grafana/provisioning/datasources/tempo.yaml
apiVersion: 1
datasources:
  - name: Tempo
    type: tempo
    access: proxy
    url: http://tempo-query-frontend:3100
    jsonData:
      httpMethod: GET
      tracesToLogs:
        datasourceUid: loki
        tags: ['service.name', 'http.method']
        mappedTags: [{ key: 'service.name', value: 'service' }]
        mapTagNamesEnabled: true
        spanStartTimeShift: '-1h'
        spanEndTimeShift: '1h'
        filterByTraceID: true
        filterBySpanID: false
      tracesToMetrics:
        datasourceUid: prometheus
        tags: [{ key: 'service.name', value: 'service' }]
        queries:
          - name: 'Request Rate'
            query: 'sum(rate(http_server_requests_seconds_count{$$__tags}[5m]))'
          - name: 'Error Rate'
            query: 'sum(rate(http_server_requests_seconds_count{$$__tags,status=~"5.."}[5m]))'
      serviceMap:
        datasourceUid: prometheus
      search:
        hide: false
      nodeGraph:
        enabled: true
      lokiSearch:
        datasourceUid: loki
```

## Definition of Done

- [ ] OpenTelemetry Collector deployed
- [ ] Tempo backend with S3 storage
- [ ] Auto-instrumentation enabled
- [ ] Custom spans for business operations
- [ ] Kafka trace propagation
- [ ] Tail-based sampling configured
- [ ] Trace-to-logs correlation
- [ ] Service dependency graph
- [ ] Grafana trace explorer
- [ ] Documentation complete

## Test Cases

```yaml
test-cases:
  - name: "End-to-end trace"
    given: "Request through API gateway"
    when: "Request processed by multiple services"
    then: "Complete trace visible in Grafana"
  
  - name: "Error trace capture"
    given: "500 error in downstream service"
    when: "Sampling evaluates trace"
    then: "Error trace always captured"
  
  - name: "Kafka correlation"
    given: "Message produced to Kafka"
    when: "Consumer processes message"
    then: "Trace context propagated"
```
