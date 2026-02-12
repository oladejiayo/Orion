# US-15-04: Message Queue Infrastructure

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-15-04 |
| **Epic** | Epic 15: AWS Infrastructure |
| **Title** | Message Queue Infrastructure |
| **Priority** | High |
| **Story Points** | 8 |
| **Status** | Ready for Development |

## User Story

**As a** platform engineer  
**I want** managed message queue infrastructure  
**So that** services can communicate asynchronously with high throughput

## Acceptance Criteria

### AC1: MSK Cluster Setup
- **Given** the MSK module
- **When** deployed
- **Then**:
  - Apache Kafka cluster created
  - Multi-AZ brokers
  - Encryption enabled
  - Authentication configured

### AC2: Topic Management
- **Given** the Kafka cluster
- **When** topics needed
- **Then**:
  - Topics auto-created
  - Replication factor = 3
  - Retention policies set

### AC3: SQS Queues
- **Given** queue requirements
- **When** SQS deployed
- **Then**:
  - Standard queues created
  - Dead-letter queues configured
  - Encryption enabled
  - Metrics enabled

### AC4: EventBridge
- **Given** event-driven patterns
- **When** EventBridge configured
- **Then**:
  - Custom event bus created
  - Rules for routing
  - Archive enabled
  - Replay capability

### AC5: Monitoring
- **Given** message infrastructure
- **When** monitoring enabled
- **Then**:
  - CloudWatch metrics
  - Consumer lag alerts
  - Queue depth alerts

## Technical Specification

### MSK Module

```hcl
# infrastructure/terraform/modules/msk/main.tf

terraform {
  required_version = ">= 1.6.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

locals {
  name_prefix = "${var.project}-${var.environment}"
  
  common_tags = merge(var.tags, {
    Project     = var.project
    Environment = var.environment
    ManagedBy   = "terraform"
  })
}

# MSK Configuration
resource "aws_msk_configuration" "main" {
  name           = "${local.name_prefix}-msk-config"
  kafka_versions = [var.kafka_version]
  
  server_properties = <<PROPERTIES
auto.create.topics.enable=true
default.replication.factor=3
min.insync.replicas=2
num.io.threads=8
num.network.threads=5
num.partitions=6
num.replica.fetchers=2
replica.lag.time.max.ms=30000
socket.receive.buffer.bytes=102400
socket.request.max.bytes=104857600
socket.send.buffer.bytes=102400
unclean.leader.election.enable=false
zookeeper.session.timeout.ms=18000
log.retention.hours=168
log.segment.bytes=1073741824
log.retention.check.interval.ms=300000
message.max.bytes=10485760
compression.type=producer
PROPERTIES
}

# MSK Cluster
resource "aws_msk_cluster" "main" {
  cluster_name           = "${local.name_prefix}-msk"
  kafka_version          = var.kafka_version
  number_of_broker_nodes = var.broker_count
  
  broker_node_group_info {
    instance_type   = var.broker_instance_type
    client_subnets  = var.private_subnet_ids
    security_groups = [var.msk_security_group_id]
    
    storage_info {
      ebs_storage_info {
        volume_size = var.broker_volume_size
        provisioned_throughput {
          enabled           = var.enable_provisioned_throughput
          volume_throughput = var.enable_provisioned_throughput ? var.volume_throughput : null
        }
      }
    }
    
    connectivity_info {
      public_access {
        type = "DISABLED"
      }
    }
  }
  
  configuration_info {
    arn      = aws_msk_configuration.main.arn
    revision = aws_msk_configuration.main.latest_revision
  }
  
  encryption_info {
    encryption_at_rest_kms_key_arn = var.kms_key_arn
    
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }
  
  client_authentication {
    sasl {
      iam   = true
      scram = var.enable_scram_auth
    }
    
    unauthenticated = false
  }
  
  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.msk.name
      }
      
      s3 {
        enabled = var.enable_s3_logs
        bucket  = var.s3_logs_bucket
        prefix  = "msk/${local.name_prefix}"
      }
    }
  }
  
  open_monitoring {
    prometheus {
      jmx_exporter {
        enabled_in_broker = true
      }
      node_exporter {
        enabled_in_broker = true
      }
    }
  }
  
  enhanced_monitoring = var.enhanced_monitoring_level
  
  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-msk"
  })
}

# CloudWatch Log Group for MSK
resource "aws_cloudwatch_log_group" "msk" {
  name              = "/aws/msk/${local.name_prefix}"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.kms_key_arn
  
  tags = local.common_tags
}

# MSK Serverless (alternative for lower environments)
resource "aws_msk_serverless_cluster" "main" {
  count = var.use_serverless ? 1 : 0
  
  cluster_name = "${local.name_prefix}-msk-serverless"
  
  vpc_config {
    subnet_ids         = var.private_subnet_ids
    security_group_ids = [var.msk_security_group_id]
  }
  
  client_authentication {
    sasl {
      iam {
        enabled = true
      }
    }
  }
  
  tags = local.common_tags
}
```

### MSK Security Group

```hcl
# infrastructure/terraform/modules/msk/security.tf

resource "aws_security_group" "msk" {
  name        = "${local.name_prefix}-msk-sg"
  description = "Security group for MSK cluster"
  vpc_id      = var.vpc_id
  
  # Kafka broker ports
  ingress {
    description     = "Kafka TLS from EKS"
    from_port       = 9094
    to_port         = 9094
    protocol        = "tcp"
    security_groups = [var.eks_nodes_security_group_id]
  }
  
  # Kafka IAM auth port
  ingress {
    description     = "Kafka IAM from EKS"
    from_port       = 9098
    to_port         = 9098
    protocol        = "tcp"
    security_groups = [var.eks_nodes_security_group_id]
  }
  
  # ZooKeeper (internal cluster communication)
  ingress {
    description = "ZooKeeper internal"
    from_port   = 2181
    to_port     = 2181
    protocol    = "tcp"
    self        = true
  }
  
  # JMX Exporter
  ingress {
    description = "JMX Exporter"
    from_port   = 11001
    to_port     = 11002
    protocol    = "tcp"
    self        = true
  }
  
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  
  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-msk-sg"
  })
}
```

### SQS Queues Module

```hcl
# infrastructure/terraform/modules/sqs/main.tf

locals {
  name_prefix = "${var.project}-${var.environment}"
  
  common_tags = merge(var.tags, {
    Project     = var.project
    Environment = var.environment
    ManagedBy   = "terraform"
  })
}

# Main Queue
resource "aws_sqs_queue" "main" {
  for_each = var.queues
  
  name = "${local.name_prefix}-${each.key}"
  
  visibility_timeout_seconds = each.value.visibility_timeout
  message_retention_seconds  = each.value.retention_seconds
  max_message_size           = each.value.max_message_size
  delay_seconds              = each.value.delay_seconds
  receive_wait_time_seconds  = each.value.receive_wait_time
  
  # Server-side encryption
  sqs_managed_sse_enabled = each.value.use_sqs_managed_sse
  kms_master_key_id       = each.value.use_sqs_managed_sse ? null : var.kms_key_id
  kms_data_key_reuse_period_seconds = each.value.use_sqs_managed_sse ? null : 300
  
  # Dead letter queue
  redrive_policy = each.value.enable_dlq ? jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq[each.key].arn
    maxReceiveCount     = each.value.max_receive_count
  }) : null
  
  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-${each.key}"
    Type = each.value.fifo ? "FIFO" : "Standard"
  })
}

# Dead Letter Queues
resource "aws_sqs_queue" "dlq" {
  for_each = { for k, v in var.queues : k => v if v.enable_dlq }
  
  name = "${local.name_prefix}-${each.key}-dlq"
  
  message_retention_seconds = 1209600  # 14 days
  
  sqs_managed_sse_enabled = true
  
  tags = merge(local.common_tags, {
    Name     = "${local.name_prefix}-${each.key}-dlq"
    Type     = "DeadLetterQueue"
    MainQueue = each.key
  })
}

# Queue Policy
resource "aws_sqs_queue_policy" "main" {
  for_each = var.queues
  
  queue_url = aws_sqs_queue.main[each.key].id
  
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AllowEKSAccess"
        Effect = "Allow"
        Principal = {
          AWS = var.allowed_principal_arns
        }
        Action = [
          "sqs:SendMessage",
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes"
        ]
        Resource = aws_sqs_queue.main[each.key].arn
      }
    ]
  })
}

# CloudWatch Alarms for Queue Depth
resource "aws_cloudwatch_metric_alarm" "queue_depth" {
  for_each = var.queues
  
  alarm_name          = "${local.name_prefix}-${each.key}-depth"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "ApproximateNumberOfMessagesVisible"
  namespace           = "AWS/SQS"
  period              = 300
  statistic           = "Average"
  threshold           = each.value.depth_threshold
  alarm_description   = "Queue ${each.key} depth is high"
  
  dimensions = {
    QueueName = aws_sqs_queue.main[each.key].name
  }
  
  alarm_actions = var.alarm_sns_topic_arns
  
  tags = local.common_tags
}

# DLQ Alarm (any messages = problem)
resource "aws_cloudwatch_metric_alarm" "dlq_messages" {
  for_each = { for k, v in var.queues : k => v if v.enable_dlq }
  
  alarm_name          = "${local.name_prefix}-${each.key}-dlq-messages"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "ApproximateNumberOfMessagesVisible"
  namespace           = "AWS/SQS"
  period              = 60
  statistic           = "Sum"
  threshold           = 0
  alarm_description   = "Dead letter queue ${each.key} has messages"
  
  dimensions = {
    QueueName = aws_sqs_queue.dlq[each.key].name
  }
  
  alarm_actions = var.alarm_sns_topic_arns
  
  tags = local.common_tags
}
```

### EventBridge Module

```hcl
# infrastructure/terraform/modules/eventbridge/main.tf

locals {
  name_prefix = "${var.project}-${var.environment}"
  
  common_tags = merge(var.tags, {
    Project     = var.project
    Environment = var.environment
    ManagedBy   = "terraform"
  })
}

# Custom Event Bus
resource "aws_cloudwatch_event_bus" "main" {
  name = "${local.name_prefix}-events"
  
  tags = local.common_tags
}

# Event Archive (for replay)
resource "aws_cloudwatch_event_archive" "main" {
  name             = "${local.name_prefix}-archive"
  event_source_arn = aws_cloudwatch_event_bus.main.arn
  retention_days   = var.archive_retention_days
  
  event_pattern = jsonencode({
    source = [{ prefix = "orion." }]
  })
}

# Event Rules
resource "aws_cloudwatch_event_rule" "main" {
  for_each = var.event_rules
  
  name           = "${local.name_prefix}-${each.key}"
  description    = each.value.description
  event_bus_name = aws_cloudwatch_event_bus.main.name
  event_pattern  = jsonencode(each.value.pattern)
  state          = each.value.enabled ? "ENABLED" : "DISABLED"
  
  tags = local.common_tags
}

# Rule Targets
resource "aws_cloudwatch_event_target" "main" {
  for_each = var.event_rules
  
  rule           = aws_cloudwatch_event_rule.main[each.key].name
  event_bus_name = aws_cloudwatch_event_bus.main.name
  target_id      = each.key
  arn            = each.value.target_arn
  
  dynamic "sqs_target" {
    for_each = each.value.target_type == "sqs" ? [1] : []
    content {
      message_group_id = each.value.message_group_id
    }
  }
  
  dynamic "retry_policy" {
    for_each = each.value.retry_policy != null ? [each.value.retry_policy] : []
    content {
      maximum_event_age_in_seconds = retry_policy.value.max_age
      maximum_retry_attempts       = retry_policy.value.max_retries
    }
  }
  
  dynamic "dead_letter_config" {
    for_each = each.value.dlq_arn != null ? [1] : []
    content {
      arn = each.value.dlq_arn
    }
  }
}

# Schema Registry
resource "aws_schemas_registry" "main" {
  name        = "${local.name_prefix}-schemas"
  description = "Schema registry for Orion events"
  
  tags = local.common_tags
}

# Event Schemas
resource "aws_schemas_schema" "main" {
  for_each = var.event_schemas
  
  name          = each.key
  registry_name = aws_schemas_registry.main.name
  type          = "OpenApi3"
  content       = each.value.schema_content
  description   = each.value.description
  
  tags = local.common_tags
}
```

### Variables

```hcl
# infrastructure/terraform/modules/msk/variables.tf

variable "project" {
  description = "Project name"
  type        = string
}

variable "environment" {
  description = "Environment name"
  type        = string
}

variable "kafka_version" {
  description = "Apache Kafka version"
  type        = string
  default     = "3.5.1"
}

variable "broker_count" {
  description = "Number of broker nodes"
  type        = number
  default     = 3
}

variable "broker_instance_type" {
  description = "Instance type for brokers"
  type        = string
  default     = "kafka.m5.large"
}

variable "broker_volume_size" {
  description = "EBS volume size in GB per broker"
  type        = number
  default     = 500
}

variable "private_subnet_ids" {
  description = "Private subnet IDs for brokers"
  type        = list(string)
}

variable "msk_security_group_id" {
  description = "Security group ID for MSK"
  type        = string
}

variable "kms_key_arn" {
  description = "KMS key ARN for encryption"
  type        = string
}

variable "enhanced_monitoring_level" {
  description = "Enhanced monitoring level"
  type        = string
  default     = "PER_BROKER"
  validation {
    condition     = contains(["DEFAULT", "PER_BROKER", "PER_TOPIC_PER_BROKER", "PER_TOPIC_PER_PARTITION"], var.enhanced_monitoring_level)
    error_message = "Must be DEFAULT, PER_BROKER, PER_TOPIC_PER_BROKER, or PER_TOPIC_PER_PARTITION."
  }
}

variable "use_serverless" {
  description = "Use MSK Serverless instead of provisioned"
  type        = bool
  default     = false
}

variable "tags" {
  description = "Additional tags"
  type        = map(string)
  default     = {}
}
```

### Outputs

```hcl
# infrastructure/terraform/modules/msk/outputs.tf

output "cluster_arn" {
  description = "MSK cluster ARN"
  value       = var.use_serverless ? aws_msk_serverless_cluster.main[0].arn : aws_msk_cluster.main.arn
}

output "bootstrap_brokers_tls" {
  description = "Bootstrap brokers (TLS)"
  value       = var.use_serverless ? null : aws_msk_cluster.main.bootstrap_brokers_tls
}

output "bootstrap_brokers_iam" {
  description = "Bootstrap brokers (IAM auth)"
  value       = var.use_serverless ? aws_msk_serverless_cluster.main[0].bootstrap_brokers_sasl_iam : aws_msk_cluster.main.bootstrap_brokers_sasl_iam
}

output "zookeeper_connect_string" {
  description = "ZooKeeper connection string"
  value       = var.use_serverless ? null : aws_msk_cluster.main.zookeeper_connect_string
}

output "security_group_id" {
  description = "MSK security group ID"
  value       = aws_security_group.msk.id
}

# SQS Outputs
output "queue_urls" {
  description = "SQS queue URLs"
  value       = { for k, v in aws_sqs_queue.main : k => v.url }
}

output "queue_arns" {
  description = "SQS queue ARNs"
  value       = { for k, v in aws_sqs_queue.main : k => v.arn }
}

output "dlq_arns" {
  description = "Dead letter queue ARNs"
  value       = { for k, v in aws_sqs_queue.dlq : k => v.arn }
}

# EventBridge Outputs
output "event_bus_arn" {
  description = "EventBridge event bus ARN"
  value       = aws_cloudwatch_event_bus.main.arn
}

output "event_bus_name" {
  description = "EventBridge event bus name"
  value       = aws_cloudwatch_event_bus.main.name
}
```

## Definition of Done

- [ ] MSK Kafka cluster deployed
- [ ] Multi-AZ broker configuration
- [ ] Encryption enabled (at rest and in transit)
- [ ] IAM authentication configured
- [ ] SQS queues with DLQs created
- [ ] EventBridge custom bus configured
- [ ] Event archive enabled
- [ ] CloudWatch alarms for all queues
- [ ] Consumer lag monitoring
- [ ] Documentation complete

## Test Cases

```go
// tests/msk_test.go
package test

import (
	"testing"
	"github.com/gruntwork-io/terratest/modules/terraform"
	"github.com/stretchr/testify/assert"
)

func TestMSKCluster(t *testing.T) {
	terraformOptions := &terraform.Options{
		TerraformDir: "../modules/msk",
	}

	defer terraform.Destroy(t, terraformOptions)
	terraform.InitAndApply(t, terraformOptions)

	bootstrapBrokers := terraform.Output(t, terraformOptions, "bootstrap_brokers_iam")
	assert.NotEmpty(t, bootstrapBrokers)
}
```
