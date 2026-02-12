# US-15-06: Security & Compliance Automation

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-15-06 |
| **Epic** | Epic 15: AWS Infrastructure |
| **Title** | Security & Compliance Automation |
| **Priority** | High |
| **Story Points** | 5 |
| **Status** | Ready for Development |

## User Story

**As a** security engineer  
**I want** automated security controls and compliance checks  
**So that** the platform meets regulatory requirements continuously

## Acceptance Criteria

### AC1: KMS Encryption
- **Given** encryption requirements
- **When** KMS configured
- **Then**:
  - CMKs for each service
  - Automatic rotation
  - Cross-account access
  - Audit logging

### AC2: Secrets Management
- **Given** application secrets
- **When** stored in Secrets Manager
- **Then**:
  - Automatic rotation
  - IRSA access only
  - Audit trail
  - No hardcoded secrets

### AC3: WAF Configuration
- **Given** web application firewall
- **When** traffic flows
- **Then**:
  - OWASP rules applied
  - Rate limiting active
  - Geo-blocking configured
  - Bot protection enabled

### AC4: Security Hub
- **Given** compliance standards
- **When** Security Hub enabled
- **Then**:
  - CIS benchmarks checked
  - PCI-DSS controls
  - Automated remediation
  - Findings aggregation

### AC5: GuardDuty & Detective
- **Given** threat detection needs
- **When** anomalies detected
- **Then**:
  - GuardDuty alerts
  - Auto-containment
  - Investigation tools
  - SNS notifications

## Technical Specification

### KMS Module

```hcl
# infrastructure/terraform/modules/security/kms.tf

locals {
  name_prefix = "${var.project}-${var.environment}"
  
  common_tags = merge(var.tags, {
    Project     = var.project
    Environment = var.environment
    ManagedBy   = "terraform"
  })
}

# Master KMS Key for general encryption
resource "aws_kms_key" "main" {
  description             = "Master encryption key for ${local.name_prefix}"
  deletion_window_in_days = 30
  enable_key_rotation     = true
  multi_region           = var.multi_region_key
  
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "Enable IAM User Permissions"
        Effect = "Allow"
        Principal = {
          AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"
        }
        Action   = "kms:*"
        Resource = "*"
      },
      {
        Sid    = "Allow EKS Service"
        Effect = "Allow"
        Principal = {
          Service = "eks.amazonaws.com"
        }
        Action = [
          "kms:Encrypt",
          "kms:Decrypt",
          "kms:ReEncrypt*",
          "kms:GenerateDataKey*",
          "kms:DescribeKey"
        ]
        Resource = "*"
      },
      {
        Sid    = "Allow RDS Service"
        Effect = "Allow"
        Principal = {
          Service = "rds.amazonaws.com"
        }
        Action = [
          "kms:Encrypt",
          "kms:Decrypt",
          "kms:ReEncrypt*",
          "kms:GenerateDataKey*",
          "kms:DescribeKey"
        ]
        Resource = "*"
      },
      {
        Sid    = "Allow CloudWatch Logs"
        Effect = "Allow"
        Principal = {
          Service = "logs.${var.aws_region}.amazonaws.com"
        }
        Action = [
          "kms:Encrypt",
          "kms:Decrypt",
          "kms:ReEncrypt*",
          "kms:GenerateDataKey*",
          "kms:DescribeKey"
        ]
        Resource = "*"
        Condition = {
          ArnLike = {
            "kms:EncryptionContext:aws:logs:arn" = "arn:aws:logs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:*"
          }
        }
      }
    ]
  })
  
  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-master-key"
  })
}

resource "aws_kms_alias" "main" {
  name          = "alias/${local.name_prefix}-master"
  target_key_id = aws_kms_key.main.key_id
}

# Dedicated KMS Key for Secrets Manager
resource "aws_kms_key" "secrets" {
  description             = "Secrets Manager encryption key for ${local.name_prefix}"
  deletion_window_in_days = 30
  enable_key_rotation     = true
  
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "Enable IAM User Permissions"
        Effect = "Allow"
        Principal = {
          AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"
        }
        Action   = "kms:*"
        Resource = "*"
      },
      {
        Sid    = "Allow Secrets Manager"
        Effect = "Allow"
        Principal = {
          Service = "secretsmanager.amazonaws.com"
        }
        Action = [
          "kms:Encrypt",
          "kms:Decrypt",
          "kms:ReEncrypt*",
          "kms:GenerateDataKey*",
          "kms:DescribeKey"
        ]
        Resource = "*"
      }
    ]
  })
  
  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-secrets-key"
  })
}

resource "aws_kms_alias" "secrets" {
  name          = "alias/${local.name_prefix}-secrets"
  target_key_id = aws_kms_key.secrets.key_id
}

data "aws_caller_identity" "current" {}
```

### Secrets Manager Module

```hcl
# infrastructure/terraform/modules/security/secrets-manager.tf

# Database credentials secret (created by RDS module, referenced here for rotation)
resource "aws_secretsmanager_secret_rotation" "database" {
  secret_id           = var.database_secret_arn
  rotation_lambda_arn = aws_lambda_function.rotate_secret.arn
  
  rotation_rules {
    automatically_after_days = 30
  }
}

# API Keys Secret
resource "aws_secretsmanager_secret" "api_keys" {
  name        = "${local.name_prefix}/api-keys"
  description = "External API keys for ${local.name_prefix}"
  kms_key_id  = aws_kms_key.secrets.arn
  
  tags = local.common_tags
}

# Application Secrets
resource "aws_secretsmanager_secret" "app_secrets" {
  for_each = var.application_secrets
  
  name        = "${local.name_prefix}/${each.key}"
  description = each.value.description
  kms_key_id  = aws_kms_key.secrets.arn
  
  tags = merge(local.common_tags, {
    SecretType = each.value.type
  })
}

# Lambda for secret rotation
resource "aws_lambda_function" "rotate_secret" {
  function_name = "${local.name_prefix}-secret-rotation"
  role          = aws_iam_role.lambda_rotation.arn
  handler       = "index.handler"
  runtime       = "python3.11"
  timeout       = 30
  
  filename         = data.archive_file.rotation_lambda.output_path
  source_code_hash = data.archive_file.rotation_lambda.output_base64sha256
  
  vpc_config {
    subnet_ids         = var.private_subnet_ids
    security_group_ids = [aws_security_group.lambda.id]
  }
  
  environment {
    variables = {
      SECRETS_MANAGER_ENDPOINT = "https://secretsmanager.${var.aws_region}.amazonaws.com"
    }
  }
  
  tags = local.common_tags
}

# IAM Role for Lambda rotation
resource "aws_iam_role" "lambda_rotation" {
  name = "${local.name_prefix}-secret-rotation-role"
  
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRole"
      Effect = "Allow"
      Principal = {
        Service = "lambda.amazonaws.com"
      }
    }]
  })
  
  tags = local.common_tags
}

resource "aws_iam_role_policy" "lambda_rotation" {
  name = "${local.name_prefix}-secret-rotation-policy"
  role = aws_iam_role.lambda_rotation.id
  
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "secretsmanager:DescribeSecret",
          "secretsmanager:GetSecretValue",
          "secretsmanager:PutSecretValue",
          "secretsmanager:UpdateSecretVersionStage"
        ]
        Resource = "*"
        Condition = {
          StringEquals = {
            "secretsmanager:resource/AllowRotationLambdaArn" = aws_lambda_function.rotate_secret.arn
          }
        }
      },
      {
        Effect = "Allow"
        Action = [
          "kms:Decrypt",
          "kms:GenerateDataKey"
        ]
        Resource = aws_kms_key.secrets.arn
      }
    ]
  })
}

# Permission for Secrets Manager to invoke Lambda
resource "aws_lambda_permission" "secrets_manager" {
  statement_id  = "AllowSecretsManager"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.rotate_secret.function_name
  principal     = "secretsmanager.amazonaws.com"
}
```

### WAF Configuration

```hcl
# infrastructure/terraform/modules/security/waf.tf

# WAF Web ACL
resource "aws_wafv2_web_acl" "main" {
  name        = "${local.name_prefix}-waf"
  description = "WAF for ${local.name_prefix} ALB"
  scope       = "REGIONAL"
  
  default_action {
    allow {}
  }
  
  # AWS Managed Rules - Common Rule Set
  rule {
    name     = "AWSManagedRulesCommonRuleSet"
    priority = 1
    
    override_action {
      none {}
    }
    
    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesCommonRuleSet"
        
        rule_action_override {
          name = "SizeRestrictions_BODY"
          action_to_use {
            count {}
          }
        }
      }
    }
    
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name               = "AWSManagedRulesCommonRuleSet"
      sampled_requests_enabled  = true
    }
  }
  
  # AWS Managed Rules - Known Bad Inputs
  rule {
    name     = "AWSManagedRulesKnownBadInputsRuleSet"
    priority = 2
    
    override_action {
      none {}
    }
    
    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
      }
    }
    
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name               = "AWSManagedRulesKnownBadInputsRuleSet"
      sampled_requests_enabled  = true
    }
  }
  
  # AWS Managed Rules - SQL Injection
  rule {
    name     = "AWSManagedRulesSQLiRuleSet"
    priority = 3
    
    override_action {
      none {}
    }
    
    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesSQLiRuleSet"
      }
    }
    
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name               = "AWSManagedRulesSQLiRuleSet"
      sampled_requests_enabled  = true
    }
  }
  
  # Rate Limiting Rule
  rule {
    name     = "RateLimitRule"
    priority = 4
    
    action {
      block {}
    }
    
    statement {
      rate_based_statement {
        limit              = var.waf_rate_limit
        aggregate_key_type = "IP"
      }
    }
    
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name               = "RateLimitRule"
      sampled_requests_enabled  = true
    }
  }
  
  # Geo Blocking Rule (optional)
  dynamic "rule" {
    for_each = length(var.blocked_countries) > 0 ? [1] : []
    
    content {
      name     = "GeoBlockRule"
      priority = 5
      
      action {
        block {}
      }
      
      statement {
        geo_match_statement {
          country_codes = var.blocked_countries
        }
      }
      
      visibility_config {
        cloudwatch_metrics_enabled = true
        metric_name               = "GeoBlockRule"
        sampled_requests_enabled  = true
      }
    }
  }
  
  # Bot Control (AWS Managed)
  rule {
    name     = "AWSManagedRulesBotControlRuleSet"
    priority = 6
    
    override_action {
      none {}
    }
    
    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesBotControlRuleSet"
        
        managed_rule_group_configs {
          aws_managed_rules_bot_control_rule_set {
            inspection_level = "COMMON"
          }
        }
      }
    }
    
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name               = "AWSManagedRulesBotControlRuleSet"
      sampled_requests_enabled  = true
    }
  }
  
  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name               = "${local.name_prefix}-waf"
    sampled_requests_enabled  = true
  }
  
  tags = local.common_tags
}

# Associate WAF with ALB
resource "aws_wafv2_web_acl_association" "alb" {
  resource_arn = var.alb_arn
  web_acl_arn  = aws_wafv2_web_acl.main.arn
}

# WAF Logging
resource "aws_wafv2_web_acl_logging_configuration" "main" {
  log_destination_configs = [aws_cloudwatch_log_group.waf.arn]
  resource_arn           = aws_wafv2_web_acl.main.arn
  
  logging_filter {
    default_behavior = "DROP"
    
    filter {
      behavior = "KEEP"
      
      condition {
        action_condition {
          action = "BLOCK"
        }
      }
      
      requirement = "MEETS_ANY"
    }
  }
}

resource "aws_cloudwatch_log_group" "waf" {
  name              = "aws-waf-logs-${local.name_prefix}"
  retention_in_days = var.log_retention_days
  kms_key_id        = aws_kms_key.main.arn
  
  tags = local.common_tags
}
```

### Security Hub & GuardDuty

```hcl
# infrastructure/terraform/modules/security/security-hub.tf

# Enable Security Hub
resource "aws_securityhub_account" "main" {
  enable_default_standards = false
  control_finding_generator = "SECURITY_CONTROL"
  auto_enable_controls     = true
}

# Enable CIS AWS Foundations Benchmark
resource "aws_securityhub_standards_subscription" "cis" {
  depends_on    = [aws_securityhub_account.main]
  standards_arn = "arn:aws:securityhub:${var.aws_region}::standards/cis-aws-foundations-benchmark/v/1.4.0"
}

# Enable AWS Foundational Security Best Practices
resource "aws_securityhub_standards_subscription" "aws_best_practices" {
  depends_on    = [aws_securityhub_account.main]
  standards_arn = "arn:aws:securityhub:${var.aws_region}::standards/aws-foundational-security-best-practices/v/1.0.0"
}

# Enable PCI DSS (for financial services)
resource "aws_securityhub_standards_subscription" "pci_dss" {
  count         = var.enable_pci_dss ? 1 : 0
  depends_on    = [aws_securityhub_account.main]
  standards_arn = "arn:aws:securityhub:${var.aws_region}::standards/pci-dss/v/3.2.1"
}

# GuardDuty
resource "aws_guardduty_detector" "main" {
  enable = true
  
  datasources {
    s3_logs {
      enable = true
    }
    kubernetes {
      audit_logs {
        enable = true
      }
    }
    malware_protection {
      scan_ec2_instance_with_findings {
        ebs_volumes {
          enable = true
        }
      }
    }
  }
  
  finding_publishing_frequency = "FIFTEEN_MINUTES"
  
  tags = local.common_tags
}

# GuardDuty EKS Protection
resource "aws_guardduty_detector_feature" "eks_runtime" {
  detector_id = aws_guardduty_detector.main.id
  name        = "EKS_RUNTIME_MONITORING"
  status      = "ENABLED"
  
  additional_configuration {
    name   = "EKS_ADDON_MANAGEMENT"
    status = "ENABLED"
  }
}

# SNS Topic for Security Findings
resource "aws_sns_topic" "security_alerts" {
  name              = "${local.name_prefix}-security-alerts"
  kms_master_key_id = aws_kms_key.main.id
  
  tags = local.common_tags
}

# CloudWatch Event Rule for GuardDuty findings
resource "aws_cloudwatch_event_rule" "guardduty_findings" {
  name        = "${local.name_prefix}-guardduty-findings"
  description = "Capture GuardDuty findings"
  
  event_pattern = jsonencode({
    source      = ["aws.guardduty"]
    detail-type = ["GuardDuty Finding"]
    detail = {
      severity = [{ numeric = [">=", 4] }]  # Medium and above
    }
  })
  
  tags = local.common_tags
}

resource "aws_cloudwatch_event_target" "guardduty_sns" {
  rule      = aws_cloudwatch_event_rule.guardduty_findings.name
  target_id = "send-to-sns"
  arn       = aws_sns_topic.security_alerts.arn
  
  input_transformer {
    input_paths = {
      severity    = "$.detail.severity"
      finding     = "$.detail.type"
      description = "$.detail.description"
      region      = "$.region"
      account     = "$.account"
    }
    
    input_template = <<EOF
{
  "message": "GuardDuty Finding: <finding>",
  "severity": <severity>,
  "description": "<description>",
  "account": "<account>",
  "region": "<region>"
}
EOF
  }
}

# CloudWatch Event Rule for Security Hub findings
resource "aws_cloudwatch_event_rule" "securityhub_findings" {
  name        = "${local.name_prefix}-securityhub-findings"
  description = "Capture Security Hub critical/high findings"
  
  event_pattern = jsonencode({
    source      = ["aws.securityhub"]
    detail-type = ["Security Hub Findings - Imported"]
    detail = {
      findings = {
        Severity = {
          Label = ["CRITICAL", "HIGH"]
        }
        Compliance = {
          Status = ["FAILED"]
        }
      }
    }
  })
  
  tags = local.common_tags
}

resource "aws_cloudwatch_event_target" "securityhub_sns" {
  rule      = aws_cloudwatch_event_rule.securityhub_findings.name
  target_id = "send-to-sns"
  arn       = aws_sns_topic.security_alerts.arn
}
```

### Outputs

```hcl
# infrastructure/terraform/modules/security/outputs.tf

output "kms_key_arn" {
  description = "Master KMS key ARN"
  value       = aws_kms_key.main.arn
}

output "kms_key_id" {
  description = "Master KMS key ID"
  value       = aws_kms_key.main.key_id
}

output "secrets_kms_key_arn" {
  description = "Secrets Manager KMS key ARN"
  value       = aws_kms_key.secrets.arn
}

output "waf_web_acl_arn" {
  description = "WAF Web ACL ARN"
  value       = aws_wafv2_web_acl.main.arn
}

output "guardduty_detector_id" {
  description = "GuardDuty detector ID"
  value       = aws_guardduty_detector.main.id
}

output "security_alerts_topic_arn" {
  description = "Security alerts SNS topic ARN"
  value       = aws_sns_topic.security_alerts.arn
}
```

## Definition of Done

- [ ] KMS keys created with rotation
- [ ] Secrets Manager with rotation Lambda
- [ ] WAF with OWASP rules
- [ ] Rate limiting configured
- [ ] Bot protection enabled
- [ ] Security Hub enabled
- [ ] GuardDuty enabled
- [ ] Security alerts to SNS
- [ ] Compliance standards enabled
- [ ] Documentation complete

## Test Cases

```go
// tests/security_test.go
package test

import (
	"testing"
	"github.com/gruntwork-io/terratest/modules/terraform"
	"github.com/stretchr/testify/assert"
)

func TestSecurityModule(t *testing.T) {
	terraformOptions := &terraform.Options{
		TerraformDir: "../modules/security",
	}

	defer terraform.Destroy(t, terraformOptions)
	terraform.InitAndApply(t, terraformOptions)

	kmsArn := terraform.Output(t, terraformOptions, "kms_key_arn")
	assert.NotEmpty(t, kmsArn)
	assert.Contains(t, kmsArn, "arn:aws:kms:")
}
```
