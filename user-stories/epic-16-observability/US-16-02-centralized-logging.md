# US-16-02: Centralized Logging

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-16-02 |
| **Epic** | Epic 16: Observability |
| **Title** | Centralized Logging |
| **Priority** | High |
| **Story Points** | 8 |
| **Status** | Ready for Development |

## User Story

**As a** developer  
**I want** centralized log aggregation and search  
**So that** I can troubleshoot issues across all services efficiently

## Acceptance Criteria

### AC1: Fluent Bit Deployment
- **Given** EKS cluster
- **When** Fluent Bit deployed
- **Then**:
  - DaemonSet on all nodes
  - Container logs collected
  - Structured parsing
  - Multi-destination routing

### AC2: CloudWatch Integration
- **Given** log streams
- **When** shipped to CloudWatch
- **Then**:
  - Log groups per service
  - Retention policies
  - Log Insights queries
  - Metric filters

### AC3: OpenSearch for Analytics
- **Given** high-volume logs
- **When** indexed in OpenSearch
- **Then**:
  - Full-text search
  - Log analytics
  - Visualizations
  - Alerting rules

### AC4: Structured Logging
- **Given** application code
- **When** logs emitted
- **Then**:
  - JSON format
  - Correlation IDs
  - Request context
  - Consistent fields

### AC5: Log Retention & Archival
- **Given** compliance requirements
- **When** retention applied
- **Then**:
  - Hot: 30 days (OpenSearch)
  - Warm: 90 days (CloudWatch)
  - Cold: 1 year (S3 Glacier)

## Technical Specification

### Fluent Bit ConfigMap

```yaml
# kubernetes/logging/fluent-bit-configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: fluent-bit-config
  namespace: logging
data:
  fluent-bit.conf: |
    [SERVICE]
        Flush         5
        Grace         30
        Log_Level     info
        Daemon        off
        Parsers_File  parsers.conf
        HTTP_Server   On
        HTTP_Listen   0.0.0.0
        HTTP_Port     2020
        Health_Check  On

    @INCLUDE input.conf
    @INCLUDE filter.conf
    @INCLUDE output.conf

  input.conf: |
    [INPUT]
        Name              tail
        Tag               kube.*
        Path              /var/log/containers/*.log
        Parser            docker
        DB                /var/fluent-bit/state/flb_kube.db
        Mem_Buf_Limit     50MB
        Skip_Long_Lines   On
        Refresh_Interval  10
        Rotate_Wait       30
        storage.type      filesystem
        Read_from_Head    False

    [INPUT]
        Name              systemd
        Tag               host.*
        Systemd_Filter    _SYSTEMD_UNIT=kubelet.service
        Systemd_Filter    _SYSTEMD_UNIT=docker.service
        Read_From_Tail    On

  filter.conf: |
    [FILTER]
        Name                kubernetes
        Match               kube.*
        Kube_URL            https://kubernetes.default.svc:443
        Kube_CA_File        /var/run/secrets/kubernetes.io/serviceaccount/ca.crt
        Kube_Token_File     /var/run/secrets/kubernetes.io/serviceaccount/token
        Kube_Tag_Prefix     kube.var.log.containers.
        Merge_Log           On
        Merge_Log_Key       log_processed
        K8S-Logging.Parser  On
        K8S-Logging.Exclude Off
        Labels              On
        Annotations         Off
        Buffer_Size         0

    [FILTER]
        Name          modify
        Match         kube.*
        Add           cluster orion-prod
        Add           region us-east-1
        Add           environment production

    [FILTER]
        Name          parser
        Match         kube.*
        Key_Name      log
        Parser        json_parser
        Reserve_Data  True
        Preserve_Key  False

    [FILTER]
        Name          nest
        Match         kube.*
        Operation     lift
        Nested_under  kubernetes
        Add_prefix    k8s_

    [FILTER]
        Name          modify
        Match         kube.*
        Rename        k8s_pod_name pod
        Rename        k8s_namespace_name namespace
        Rename        k8s_container_name container
        Remove        k8s_pod_id
        Remove        k8s_docker_id

    # Sensitive data masking
    [FILTER]
        Name          lua
        Match         kube.*
        script        /fluent-bit/scripts/mask_sensitive.lua
        call          mask_sensitive_data

  output.conf: |
    # CloudWatch Logs
    [OUTPUT]
        Name                cloudwatch_logs
        Match               kube.*
        region              ${AWS_REGION}
        log_group_name      /orion/${ENVIRONMENT}/application
        log_stream_prefix   ${HOSTNAME}-
        auto_create_group   true
        log_key             log
        extra_user_agent    fluent-bit

    # OpenSearch for analytics
    [OUTPUT]
        Name                opensearch
        Match               kube.*
        Host                ${OPENSEARCH_HOST}
        Port                443
        Index               orion-logs
        Type                _doc
        AWS_Auth            On
        AWS_Region          ${AWS_REGION}
        tls                 On
        tls.verify          On
        Suppress_Type_Name  On
        Logstash_Format     On
        Logstash_Prefix     orion-logs
        Retry_Limit         5

    # S3 for archival
    [OUTPUT]
        Name                s3
        Match               kube.*
        bucket              orion-logs-archive-${AWS_ACCOUNT_ID}
        region              ${AWS_REGION}
        total_file_size     100M
        upload_timeout      10m
        use_put_object      Off
        s3_key_format       /logs/$TAG[1]/%Y/%m/%d/$UUID.gz
        compression         gzip
        store_dir           /var/fluent-bit/s3

  parsers.conf: |
    [PARSER]
        Name        docker
        Format      json
        Time_Key    time
        Time_Format %Y-%m-%dT%H:%M:%S.%L
        Time_Keep   On

    [PARSER]
        Name        json_parser
        Format      json
        Time_Key    timestamp
        Time_Format %Y-%m-%dT%H:%M:%S.%LZ
        Time_Keep   On

    [PARSER]
        Name        spring_boot
        Format      regex
        Regex       ^(?<timestamp>\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z)\s+(?<level>\w+)\s+\[(?<service>[^\]]+)\]\s+(?<traceId>[^\s]+)\s+(?<spanId>[^\s]+)\s+---\s+\[(?<thread>[^\]]+)\]\s+(?<logger>[^\s]+)\s+:\s+(?<message>.*)$
        Time_Key    timestamp
        Time_Format %Y-%m-%dT%H:%M:%S.%LZ
```

### Fluent Bit DaemonSet

```yaml
# kubernetes/logging/fluent-bit-daemonset.yaml
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: fluent-bit
  namespace: logging
  labels:
    app: fluent-bit
spec:
  selector:
    matchLabels:
      app: fluent-bit
  template:
    metadata:
      labels:
        app: fluent-bit
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "2020"
        prometheus.io/path: "/api/v1/metrics/prometheus"
    spec:
      serviceAccountName: fluent-bit
      tolerations:
        - key: node-role.kubernetes.io/master
          effect: NoSchedule
        - operator: Exists
          effect: NoExecute
        - operator: Exists
          effect: NoSchedule
      containers:
        - name: fluent-bit
          image: public.ecr.aws/aws-observability/aws-for-fluent-bit:2.31.12
          imagePullPolicy: IfNotPresent
          resources:
            requests:
              cpu: 100m
              memory: 128Mi
            limits:
              cpu: 500m
              memory: 512Mi
          ports:
            - containerPort: 2020
              name: metrics
          env:
            - name: AWS_REGION
              value: "us-east-1"
            - name: ENVIRONMENT
              value: "production"
            - name: HOSTNAME
              valueFrom:
                fieldRef:
                  fieldPath: spec.nodeName
            - name: OPENSEARCH_HOST
              valueFrom:
                secretKeyRef:
                  name: opensearch-credentials
                  key: host
          volumeMounts:
            - name: varlog
              mountPath: /var/log
            - name: varlibdockercontainers
              mountPath: /var/lib/docker/containers
              readOnly: true
            - name: fluent-bit-config
              mountPath: /fluent-bit/etc/
            - name: fluent-bit-scripts
              mountPath: /fluent-bit/scripts/
            - name: fluent-bit-state
              mountPath: /var/fluent-bit/state
          livenessProbe:
            httpGet:
              path: /api/v1/health
              port: 2020
            initialDelaySeconds: 10
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /api/v1/health
              port: 2020
            initialDelaySeconds: 10
            periodSeconds: 10
      volumes:
        - name: varlog
          hostPath:
            path: /var/log
        - name: varlibdockercontainers
          hostPath:
            path: /var/lib/docker/containers
        - name: fluent-bit-config
          configMap:
            name: fluent-bit-config
        - name: fluent-bit-scripts
          configMap:
            name: fluent-bit-scripts
        - name: fluent-bit-state
          hostPath:
            path: /var/fluent-bit/state
            type: DirectoryOrCreate
```

### OpenSearch Terraform Module

```hcl
# infrastructure/terraform/modules/opensearch/main.tf

locals {
  name_prefix = "${var.project}-${var.environment}"
}

resource "aws_opensearch_domain" "logs" {
  domain_name    = "${local.name_prefix}-logs"
  engine_version = "OpenSearch_2.11"

  cluster_config {
    instance_type            = var.instance_type
    instance_count           = var.instance_count
    zone_awareness_enabled   = var.multi_az
    dedicated_master_enabled = var.dedicated_master_enabled
    dedicated_master_type    = var.dedicated_master_type
    dedicated_master_count   = var.dedicated_master_enabled ? 3 : 0

    zone_awareness_config {
      availability_zone_count = var.multi_az ? 3 : 1
    }
  }

  ebs_options {
    ebs_enabled = true
    volume_size = var.ebs_volume_size
    volume_type = "gp3"
    iops        = var.ebs_iops
    throughput  = var.ebs_throughput
  }

  vpc_options {
    subnet_ids         = var.subnet_ids
    security_group_ids = [aws_security_group.opensearch.id]
  }

  encrypt_at_rest {
    enabled    = true
    kms_key_id = var.kms_key_id
  }

  node_to_node_encryption {
    enabled = true
  }

  domain_endpoint_options {
    enforce_https       = true
    tls_security_policy = "Policy-Min-TLS-1-2-2019-07"
  }

  advanced_security_options {
    enabled                        = true
    internal_user_database_enabled = false
    
    master_user_options {
      master_user_arn = var.master_user_arn
    }
  }

  log_publishing_options {
    cloudwatch_log_group_arn = aws_cloudwatch_log_group.opensearch_index_slow.arn
    log_type                 = "INDEX_SLOW_LOGS"
  }

  log_publishing_options {
    cloudwatch_log_group_arn = aws_cloudwatch_log_group.opensearch_search_slow.arn
    log_type                 = "SEARCH_SLOW_LOGS"
  }

  auto_tune_options {
    desired_state       = "ENABLED"
    rollback_on_disable = "NO_ROLLBACK"

    maintenance_schedule {
      start_at = "2024-01-01T00:00:00Z"
      duration {
        value = 2
        unit  = "HOURS"
      }
      cron_expression_for_recurrence = "cron(0 0 ? * SUN *)"
    }
  }

  tags = var.tags
}

# Index lifecycle management
resource "null_resource" "opensearch_ilm_policy" {
  depends_on = [aws_opensearch_domain.logs]

  provisioner "local-exec" {
    command = <<-EOT
      curl -XPUT "https://${aws_opensearch_domain.logs.endpoint}/_plugins/_ism/policies/orion-logs-policy" \
        -H 'Content-Type: application/json' \
        -d '{
          "policy": {
            "description": "Orion logs lifecycle policy",
            "default_state": "hot",
            "states": [
              {
                "name": "hot",
                "actions": [
                  {
                    "rollover": {
                      "min_size": "30gb",
                      "min_index_age": "1d"
                    }
                  }
                ],
                "transitions": [
                  {
                    "state_name": "warm",
                    "conditions": {
                      "min_index_age": "7d"
                    }
                  }
                ]
              },
              {
                "name": "warm",
                "actions": [
                  {
                    "replica_count": {
                      "number_of_replicas": 1
                    }
                  }
                ],
                "transitions": [
                  {
                    "state_name": "delete",
                    "conditions": {
                      "min_index_age": "30d"
                    }
                  }
                ]
              },
              {
                "name": "delete",
                "actions": [
                  {
                    "delete": {}
                  }
                ]
              }
            ],
            "ism_template": {
              "index_patterns": ["orion-logs-*"],
              "priority": 100
            }
          }
        }'
    EOT
  }
}
```

### Structured Logging (Java)

```java
// src/main/java/com/orion/logging/StructuredLogger.java
package com.orion.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class StructuredLogger {
    
    private static final ObjectMapper mapper = new ObjectMapper();
    
    public static void info(Logger logger, String message, Map<String, Object> context) {
        try {
            Map<String, Object> logEntry = buildLogEntry("INFO", message, context);
            logger.info(mapper.writeValueAsString(logEntry));
        } catch (Exception e) {
            logger.info(message);
        }
    }
    
    public static void error(Logger logger, String message, Throwable throwable, Map<String, Object> context) {
        try {
            Map<String, Object> logEntry = buildLogEntry("ERROR", message, context);
            logEntry.put("exception", Map.of(
                "type", throwable.getClass().getName(),
                "message", throwable.getMessage(),
                "stackTrace", getStackTrace(throwable)
            ));
            logger.error(mapper.writeValueAsString(logEntry));
        } catch (Exception e) {
            logger.error(message, throwable);
        }
    }
    
    private static Map<String, Object> buildLogEntry(String level, String message, Map<String, Object> context) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("timestamp", java.time.Instant.now().toString());
        entry.put("level", level);
        entry.put("message", message);
        entry.put("service", System.getenv("SERVICE_NAME"));
        entry.put("version", System.getenv("SERVICE_VERSION"));
        
        // Add MDC context (trace IDs, etc.)
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        if (mdcContext != null) {
            entry.put("traceId", mdcContext.get("traceId"));
            entry.put("spanId", mdcContext.get("spanId"));
            entry.put("userId", mdcContext.get("userId"));
            entry.put("tenantId", mdcContext.get("tenantId"));
            entry.put("requestId", mdcContext.get("requestId"));
        }
        
        if (context != null) {
            entry.putAll(context);
        }
        
        return entry;
    }
    
    private static String getStackTrace(Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : throwable.getStackTrace()) {
            sb.append(element.toString()).append("\n");
        }
        return sb.toString();
    }
}
```

### Logback Configuration

```xml
<!-- src/main/resources/logback-spring.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
    
    <springProperty scope="context" name="appName" source="spring.application.name"/>
    <springProperty scope="context" name="environment" source="spring.profiles.active"/>
    
    <!-- JSON encoder for structured logging -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder">
            <providers>
                <timestamp>
                    <timeZone>UTC</timeZone>
                    <pattern>yyyy-MM-dd'T'HH:mm:ss.SSS'Z'</pattern>
                </timestamp>
                <logLevel/>
                <loggerName>
                    <shortenedLoggerNameLength>36</shortenedLoggerNameLength>
                </loggerName>
                <threadName/>
                <message/>
                <stackTrace>
                    <throwableConverter class="net.logstash.logback.stacktrace.ShortenedThrowableConverter">
                        <maxDepthPerThrowable>30</maxDepthPerThrowable>
                        <maxLength>2048</maxLength>
                        <rootCauseFirst>true</rootCauseFirst>
                    </throwableConverter>
                </stackTrace>
                <mdc/>
                <context/>
                <pattern>
                    <pattern>
                        {
                            "service": "${appName}",
                            "environment": "${environment}",
                            "version": "${BUILD_VERSION:-unknown}"
                        }
                    </pattern>
                </pattern>
            </providers>
        </encoder>
    </appender>
    
    <!-- Async appender for performance -->
    <appender name="ASYNC" class="ch.qos.logback.classic.AsyncAppender">
        <queueSize>1024</queueSize>
        <discardingThreshold>0</discardingThreshold>
        <includeCallerData>false</includeCallerData>
        <appender-ref ref="CONSOLE"/>
    </appender>
    
    <!-- Log levels -->
    <logger name="com.orion" level="INFO"/>
    <logger name="org.springframework" level="WARN"/>
    <logger name="org.hibernate" level="WARN"/>
    <logger name="io.netty" level="WARN"/>
    
    <root level="INFO">
        <appender-ref ref="ASYNC"/>
    </root>
</configuration>
```

## Definition of Done

- [ ] Fluent Bit DaemonSet deployed
- [ ] CloudWatch log groups created
- [ ] OpenSearch domain provisioned
- [ ] Index lifecycle policies configured
- [ ] Structured logging implemented
- [ ] Log parsing and enrichment working
- [ ] Sensitive data masking enabled
- [ ] S3 archival configured
- [ ] Grafana log dashboards
- [ ] Documentation complete

## Test Cases

```yaml
test-cases:
  - name: "Logs collected from pods"
    given: "Application running in EKS"
    when: "Application emits logs"
    then: "Logs appear in CloudWatch and OpenSearch"
  
  - name: "Structured log parsing"
    given: "JSON formatted logs"
    when: "Fluent Bit processes logs"
    then: "Fields are properly extracted"
  
  - name: "Log retention"
    given: "Logs older than 30 days"
    when: "Lifecycle policy runs"
    then: "Logs moved to appropriate tier"
```
