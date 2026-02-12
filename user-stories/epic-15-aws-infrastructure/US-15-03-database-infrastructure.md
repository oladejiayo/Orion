# US-15-03: Database Infrastructure (Aurora/RDS)

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-15-03 |
| **Epic** | Epic 15: AWS Infrastructure |
| **Title** | Database Infrastructure |
| **Priority** | Critical |
| **Story Points** | 8 |
| **Status** | Ready for Development |

## User Story

**As a** platform engineer  
**I want** highly available Aurora PostgreSQL clusters  
**So that** the platform has reliable and performant database services

## Acceptance Criteria

### AC1: Aurora Cluster Setup
- **Given** the database module
- **When** deployed
- **Then**:
  - Aurora PostgreSQL cluster created
  - Multi-AZ replicas configured
  - Encryption at rest enabled
  - Parameter groups customized

### AC2: High Availability
- **Given** production requirements
- **When** primary fails
- **Then**:
  - Automatic failover occurs
  - < 30 second recovery
  - Applications reconnect

### AC3: Backup Configuration
- **Given** data protection needs
- **When** backups run
- **Then**:
  - Automated daily backups
  - 35-day retention
  - Point-in-time recovery
  - Cross-region backup copies

### AC4: Monitoring & Alerts
- **Given** database operations
- **When** issues occur
- **Then**:
  - CloudWatch metrics collected
  - Performance Insights enabled
  - Alerts for thresholds

### AC5: Security Configuration
- **Given** compliance requirements
- **When** database accessed
- **Then**:
  - IAM authentication available
  - SSL/TLS enforced
  - Security group restricted
  - Audit logging enabled

## Technical Specification

### Aurora Module

```hcl
# infrastructure/terraform/modules/rds/main.tf

terraform {
  required_version = ">= 1.6.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.5"
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

# Random password for master user
resource "random_password" "master" {
  length  = 32
  special = false
}

# Store password in Secrets Manager
resource "aws_secretsmanager_secret" "db_credentials" {
  name        = "${local.name_prefix}/aurora/master"
  description = "Aurora PostgreSQL master credentials"
  kms_key_id  = var.kms_key_arn
  
  tags = local.common_tags
}

resource "aws_secretsmanager_secret_version" "db_credentials" {
  secret_id = aws_secretsmanager_secret.db_credentials.id
  secret_string = jsonencode({
    username = var.master_username
    password = random_password.master.result
    host     = aws_rds_cluster.main.endpoint
    port     = aws_rds_cluster.main.port
    dbname   = var.database_name
  })
}

# Aurora Cluster Parameter Group
resource "aws_rds_cluster_parameter_group" "main" {
  name        = "${local.name_prefix}-aurora-pg15"
  family      = "aurora-postgresql15"
  description = "Aurora PostgreSQL 15 cluster parameter group"
  
  parameter {
    name  = "log_statement"
    value = "ddl"
  }
  
  parameter {
    name  = "log_min_duration_statement"
    value = "1000"  # Log queries > 1 second
  }
  
  parameter {
    name  = "shared_preload_libraries"
    value = "pg_stat_statements,auto_explain"
  }
  
  parameter {
    name  = "track_activity_query_size"
    value = "4096"
  }
  
  parameter {
    name  = "pg_stat_statements.track"
    value = "all"
  }
  
  parameter {
    name         = "rds.force_ssl"
    value        = "1"
    apply_method = "pending-reboot"
  }
  
  tags = local.common_tags
}

# Aurora DB Parameter Group
resource "aws_db_parameter_group" "main" {
  name        = "${local.name_prefix}-aurora-pg15-instance"
  family      = "aurora-postgresql15"
  description = "Aurora PostgreSQL 15 instance parameter group"
  
  parameter {
    name  = "log_connections"
    value = "1"
  }
  
  parameter {
    name  = "log_disconnections"
    value = "1"
  }
  
  parameter {
    name  = "log_lock_waits"
    value = "1"
  }
  
  tags = local.common_tags
}

# Aurora Cluster
resource "aws_rds_cluster" "main" {
  cluster_identifier = "${local.name_prefix}-aurora"
  engine             = "aurora-postgresql"
  engine_version     = var.engine_version
  engine_mode        = "provisioned"
  
  database_name   = var.database_name
  master_username = var.master_username
  master_password = random_password.master.result
  
  db_subnet_group_name            = var.db_subnet_group_name
  vpc_security_group_ids          = [var.database_security_group_id]
  db_cluster_parameter_group_name = aws_rds_cluster_parameter_group.main.name
  
  storage_encrypted = true
  kms_key_id        = var.kms_key_arn
  
  backup_retention_period      = var.backup_retention_days
  preferred_backup_window      = "03:00-04:00"
  preferred_maintenance_window = "sun:04:00-sun:05:00"
  
  enabled_cloudwatch_logs_exports = ["postgresql"]
  
  deletion_protection = var.deletion_protection
  skip_final_snapshot = var.skip_final_snapshot
  final_snapshot_identifier = var.skip_final_snapshot ? null : "${local.name_prefix}-aurora-final-${formatdate("YYYYMMDDHHmmss", timestamp())}"
  
  iam_database_authentication_enabled = true
  
  serverlessv2_scaling_configuration {
    min_capacity = var.serverless_min_capacity
    max_capacity = var.serverless_max_capacity
  }
  
  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-aurora"
  })
  
  lifecycle {
    ignore_changes = [
      master_password,
      final_snapshot_identifier
    ]
  }
}

# Aurora Cluster Instances
resource "aws_rds_cluster_instance" "main" {
  count = var.instance_count
  
  identifier           = "${local.name_prefix}-aurora-${count.index + 1}"
  cluster_identifier   = aws_rds_cluster.main.id
  instance_class       = var.use_serverless ? "db.serverless" : var.instance_class
  engine               = aws_rds_cluster.main.engine
  engine_version       = aws_rds_cluster.main.engine_version
  
  db_parameter_group_name = aws_db_parameter_group.main.name
  
  publicly_accessible     = false
  auto_minor_version_upgrade = true
  
  performance_insights_enabled          = true
  performance_insights_retention_period = 7
  performance_insights_kms_key_id       = var.kms_key_arn
  
  monitoring_interval = 60
  monitoring_role_arn = aws_iam_role.rds_monitoring.arn
  
  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-aurora-${count.index + 1}"
  })
}
```

### Enhanced Monitoring

```hcl
# infrastructure/terraform/modules/rds/monitoring.tf

# Enhanced Monitoring IAM Role
resource "aws_iam_role" "rds_monitoring" {
  name = "${local.name_prefix}-rds-monitoring"
  
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRole"
      Effect = "Allow"
      Principal = {
        Service = "monitoring.rds.amazonaws.com"
      }
    }]
  })
  
  tags = local.common_tags
}

resource "aws_iam_role_policy_attachment" "rds_monitoring" {
  role       = aws_iam_role.rds_monitoring.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}

# CloudWatch Alarms
resource "aws_cloudwatch_metric_alarm" "cpu_high" {
  alarm_name          = "${local.name_prefix}-aurora-cpu-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "CPUUtilization"
  namespace           = "AWS/RDS"
  period              = 300
  statistic           = "Average"
  threshold           = 80
  alarm_description   = "Aurora CPU utilization is too high"
  
  dimensions = {
    DBClusterIdentifier = aws_rds_cluster.main.cluster_identifier
  }
  
  alarm_actions = var.alarm_sns_topic_arns
  ok_actions    = var.alarm_sns_topic_arns
  
  tags = local.common_tags
}

resource "aws_cloudwatch_metric_alarm" "connections_high" {
  alarm_name          = "${local.name_prefix}-aurora-connections-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "DatabaseConnections"
  namespace           = "AWS/RDS"
  period              = 300
  statistic           = "Average"
  threshold           = var.max_connections_threshold
  alarm_description   = "Aurora connection count is high"
  
  dimensions = {
    DBClusterIdentifier = aws_rds_cluster.main.cluster_identifier
  }
  
  alarm_actions = var.alarm_sns_topic_arns
  
  tags = local.common_tags
}

resource "aws_cloudwatch_metric_alarm" "freeable_memory_low" {
  alarm_name          = "${local.name_prefix}-aurora-memory-low"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 3
  metric_name         = "FreeableMemory"
  namespace           = "AWS/RDS"
  period              = 300
  statistic           = "Average"
  threshold           = 1073741824  # 1 GB
  alarm_description   = "Aurora freeable memory is low"
  
  dimensions = {
    DBClusterIdentifier = aws_rds_cluster.main.cluster_identifier
  }
  
  alarm_actions = var.alarm_sns_topic_arns
  
  tags = local.common_tags
}

resource "aws_cloudwatch_metric_alarm" "replica_lag" {
  alarm_name          = "${local.name_prefix}-aurora-replica-lag"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "AuroraReplicaLag"
  namespace           = "AWS/RDS"
  period              = 60
  statistic           = "Maximum"
  threshold           = 100  # 100ms
  alarm_description   = "Aurora replica lag is high"
  
  dimensions = {
    DBClusterIdentifier = aws_rds_cluster.main.cluster_identifier
  }
  
  alarm_actions = var.alarm_sns_topic_arns
  
  tags = local.common_tags
}
```

### Cross-Region Backup (DR)

```hcl
# infrastructure/terraform/modules/rds/backup.tf

# Global Cluster for cross-region replication (prod only)
resource "aws_rds_global_cluster" "main" {
  count = var.enable_global_cluster ? 1 : 0
  
  global_cluster_identifier = "${local.name_prefix}-aurora-global"
  source_db_cluster_identifier = aws_rds_cluster.main.arn
  force_destroy                = false
  
  lifecycle {
    prevent_destroy = true
  }
}

# Cross-region automated backups
resource "aws_db_instance_automated_backups_replication" "main" {
  count = var.enable_cross_region_backup ? 1 : 0
  
  source_db_instance_arn = aws_rds_cluster_instance.main[0].arn
  kms_key_id             = var.dr_kms_key_arn
  retention_period       = var.backup_retention_days
  
  # This is in the DR region
  provider = aws.dr_region
}

# Event subscription for cluster events
resource "aws_db_event_subscription" "main" {
  name      = "${local.name_prefix}-aurora-events"
  sns_topic = var.events_sns_topic_arn
  
  source_type = "db-cluster"
  source_ids  = [aws_rds_cluster.main.id]
  
  event_categories = [
    "availability",
    "deletion",
    "failover",
    "failure",
    "maintenance",
    "notification",
    "recovery",
  ]
  
  tags = local.common_tags
}
```

### ElastiCache (Redis)

```hcl
# infrastructure/terraform/modules/rds/elasticache.tf

# ElastiCache Subnet Group (if not using VPC module output)
resource "aws_elasticache_subnet_group" "main" {
  count = var.create_redis ? 1 : 0
  
  name        = "${local.name_prefix}-redis-subnet"
  description = "Subnet group for Redis cluster"
  subnet_ids  = var.database_subnet_ids
  
  tags = local.common_tags
}

# ElastiCache Parameter Group
resource "aws_elasticache_parameter_group" "main" {
  count = var.create_redis ? 1 : 0
  
  name   = "${local.name_prefix}-redis7"
  family = "redis7"
  
  parameter {
    name  = "maxmemory-policy"
    value = "volatile-lru"
  }
  
  parameter {
    name  = "notify-keyspace-events"
    value = "Ex"  # Expired events
  }
  
  tags = local.common_tags
}

# ElastiCache Replication Group (Redis Cluster)
resource "aws_elasticache_replication_group" "main" {
  count = var.create_redis ? 1 : 0
  
  replication_group_id = "${local.name_prefix}-redis"
  description          = "Redis cluster for ${local.name_prefix}"
  
  engine               = "redis"
  engine_version       = var.redis_version
  node_type            = var.redis_node_type
  port                 = 6379
  parameter_group_name = aws_elasticache_parameter_group.main[0].name
  
  num_cache_clusters = var.redis_num_cache_clusters
  
  automatic_failover_enabled = var.redis_num_cache_clusters > 1
  multi_az_enabled           = var.redis_num_cache_clusters > 1
  
  subnet_group_name  = var.elasticache_subnet_group_name
  security_group_ids = [var.redis_security_group_id]
  
  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  kms_key_id                 = var.kms_key_arn
  
  snapshot_retention_limit = 7
  snapshot_window          = "05:00-06:00"
  maintenance_window       = "sun:06:00-sun:07:00"
  
  auto_minor_version_upgrade = true
  
  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-redis"
  })
}

# Redis CloudWatch Alarms
resource "aws_cloudwatch_metric_alarm" "redis_cpu" {
  count = var.create_redis ? 1 : 0
  
  alarm_name          = "${local.name_prefix}-redis-cpu-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "CPUUtilization"
  namespace           = "AWS/ElastiCache"
  period              = 300
  statistic           = "Average"
  threshold           = 75
  alarm_description   = "Redis CPU utilization is high"
  
  dimensions = {
    CacheClusterId = "${local.name_prefix}-redis"
  }
  
  alarm_actions = var.alarm_sns_topic_arns
  
  tags = local.common_tags
}

resource "aws_cloudwatch_metric_alarm" "redis_memory" {
  count = var.create_redis ? 1 : 0
  
  alarm_name          = "${local.name_prefix}-redis-memory-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "DatabaseMemoryUsagePercentage"
  namespace           = "AWS/ElastiCache"
  period              = 300
  statistic           = "Average"
  threshold           = 80
  alarm_description   = "Redis memory usage is high"
  
  dimensions = {
    CacheClusterId = "${local.name_prefix}-redis"
  }
  
  alarm_actions = var.alarm_sns_topic_arns
  
  tags = local.common_tags
}
```

### Outputs

```hcl
# infrastructure/terraform/modules/rds/outputs.tf

output "cluster_endpoint" {
  description = "Aurora cluster writer endpoint"
  value       = aws_rds_cluster.main.endpoint
}

output "cluster_reader_endpoint" {
  description = "Aurora cluster reader endpoint"
  value       = aws_rds_cluster.main.reader_endpoint
}

output "cluster_port" {
  description = "Aurora cluster port"
  value       = aws_rds_cluster.main.port
}

output "cluster_identifier" {
  description = "Aurora cluster identifier"
  value       = aws_rds_cluster.main.cluster_identifier
}

output "cluster_arn" {
  description = "Aurora cluster ARN"
  value       = aws_rds_cluster.main.arn
}

output "secrets_manager_secret_arn" {
  description = "Secrets Manager ARN for DB credentials"
  value       = aws_secretsmanager_secret.db_credentials.arn
}

output "database_name" {
  description = "Database name"
  value       = var.database_name
}

# Redis outputs
output "redis_endpoint" {
  description = "Redis primary endpoint"
  value       = var.create_redis ? aws_elasticache_replication_group.main[0].primary_endpoint_address : null
}

output "redis_reader_endpoint" {
  description = "Redis reader endpoint"
  value       = var.create_redis ? aws_elasticache_replication_group.main[0].reader_endpoint_address : null
}

output "redis_port" {
  description = "Redis port"
  value       = var.create_redis ? 6379 : null
}
```

## Definition of Done

- [ ] Aurora PostgreSQL cluster deployed
- [ ] Multi-AZ replicas configured
- [ ] Parameter groups customized
- [ ] Encryption at rest and in transit
- [ ] Secrets Manager integration
- [ ] Performance Insights enabled
- [ ] CloudWatch alarms configured
- [ ] Backup and retention policies
- [ ] ElastiCache Redis cluster
- [ ] Documentation complete

## Test Cases

```go
// tests/rds_test.go
package test

import (
	"testing"
	"github.com/gruntwork-io/terratest/modules/terraform"
	"github.com/stretchr/testify/assert"
)

func TestAuroraCluster(t *testing.T) {
	terraformOptions := &terraform.Options{
		TerraformDir: "../modules/rds",
		Vars: map[string]interface{}{
			"project":       "orion",
			"environment":   "test",
			"instance_count": 1,
		},
	}

	defer terraform.Destroy(t, terraformOptions)
	terraform.InitAndApply(t, terraformOptions)

	endpoint := terraform.Output(t, terraformOptions, "cluster_endpoint")
	assert.NotEmpty(t, endpoint)
}
```
