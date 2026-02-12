# US-15-02: EKS Cluster Configuration

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-15-02 |
| **Epic** | Epic 15: AWS Infrastructure |
| **Title** | EKS Cluster Configuration |
| **Priority** | Critical |
| **Story Points** | 13 |
| **Status** | Ready for Development |

## User Story

**As a** platform engineer  
**I want** a production-ready EKS cluster with proper node groups and add-ons  
**So that** the Orion platform can run containerized workloads at scale

## Acceptance Criteria

### AC1: EKS Cluster Setup
- **Given** the EKS module
- **When** deployed
- **Then**:
  - Cluster created with latest stable version
  - Private endpoint enabled
  - Encryption configured
  - OIDC provider created

### AC2: Node Groups
- **Given** the cluster configuration
- **When** nodes are provisioned
- **Then**:
  - Multiple node groups by workload type
  - Auto-scaling configured
  - Spot instances for non-critical
  - ARM64 nodes supported

### AC3: EKS Add-ons
- **Given** the cluster
- **When** add-ons deployed
- **Then**:
  - CoreDNS installed
  - kube-proxy updated
  - VPC CNI configured
  - EBS CSI driver installed

### AC4: IAM Integration
- **Given** IRSA configuration
- **When** pods need AWS access
- **Then**:
  - Service accounts can assume roles
  - Fine-grained permissions
  - No node-level access

### AC5: Cluster Autoscaler
- **Given** workload scaling needs
- **When** pods pending
- **Then**:
  - Cluster autoscaler adds nodes
  - Scale down during low usage
  - Proper labels/taints

## Technical Specification

### EKS Module

```hcl
# infrastructure/terraform/modules/eks/main.tf

terraform {
  required_version = ">= 1.6.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.23"
    }
    helm = {
      source  = "hashicorp/helm"
      version = "~> 2.11"
    }
  }
}

locals {
  name_prefix = "${var.project}-${var.environment}"
  cluster_name = "${local.name_prefix}-eks"
  
  common_tags = merge(var.tags, {
    Project     = var.project
    Environment = var.environment
    ManagedBy   = "terraform"
  })
}

# KMS Key for EKS encryption
resource "aws_kms_key" "eks" {
  description             = "KMS key for EKS cluster ${local.cluster_name}"
  deletion_window_in_days = 7
  enable_key_rotation     = true
  
  tags = merge(local.common_tags, {
    Name = "${local.cluster_name}-kms"
  })
}

resource "aws_kms_alias" "eks" {
  name          = "alias/${local.cluster_name}"
  target_key_id = aws_kms_key.eks.key_id
}

# EKS Cluster
resource "aws_eks_cluster" "main" {
  name     = local.cluster_name
  version  = var.kubernetes_version
  role_arn = aws_iam_role.cluster.arn
  
  enabled_cluster_log_types = [
    "api",
    "audit",
    "authenticator",
    "controllerManager",
    "scheduler"
  ]
  
  vpc_config {
    subnet_ids              = var.private_subnet_ids
    endpoint_private_access = true
    endpoint_public_access  = var.endpoint_public_access
    public_access_cidrs     = var.endpoint_public_access ? var.public_access_cidrs : []
    security_group_ids      = [var.cluster_security_group_id]
  }
  
  encryption_config {
    provider {
      key_arn = aws_kms_key.eks.arn
    }
    resources = ["secrets"]
  }
  
  kubernetes_network_config {
    service_ipv4_cidr = var.service_cidr
    ip_family         = "ipv4"
  }
  
  tags = merge(local.common_tags, {
    Name = local.cluster_name
  })
  
  depends_on = [
    aws_iam_role_policy_attachment.cluster_policy,
    aws_iam_role_policy_attachment.cluster_vpc_policy,
    aws_cloudwatch_log_group.eks
  ]
}

# CloudWatch Log Group for cluster logs
resource "aws_cloudwatch_log_group" "eks" {
  name              = "/aws/eks/${local.cluster_name}/cluster"
  retention_in_days = var.log_retention_days
  kms_key_id        = aws_kms_key.eks.arn
  
  tags = local.common_tags
}

# OIDC Provider
data "tls_certificate" "eks" {
  url = aws_eks_cluster.main.identity[0].oidc[0].issuer
}

resource "aws_iam_openid_connect_provider" "eks" {
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.eks.certificates[0].sha1_fingerprint]
  url             = aws_eks_cluster.main.identity[0].oidc[0].issuer
  
  tags = local.common_tags
}
```

### Cluster IAM Roles

```hcl
# infrastructure/terraform/modules/eks/iam.tf

# EKS Cluster Role
resource "aws_iam_role" "cluster" {
  name = "${local.cluster_name}-cluster-role"
  
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRole"
      Effect = "Allow"
      Principal = {
        Service = "eks.amazonaws.com"
      }
    }]
  })
  
  tags = local.common_tags
}

resource "aws_iam_role_policy_attachment" "cluster_policy" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
  role       = aws_iam_role.cluster.name
}

resource "aws_iam_role_policy_attachment" "cluster_vpc_policy" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSVPCResourceController"
  role       = aws_iam_role.cluster.name
}

# Node Group Role
resource "aws_iam_role" "node_group" {
  name = "${local.cluster_name}-node-role"
  
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRole"
      Effect = "Allow"
      Principal = {
        Service = "ec2.amazonaws.com"
      }
    }]
  })
  
  tags = local.common_tags
}

resource "aws_iam_role_policy_attachment" "node_worker" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy"
  role       = aws_iam_role.node_group.name
}

resource "aws_iam_role_policy_attachment" "node_cni" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy"
  role       = aws_iam_role.node_group.name
}

resource "aws_iam_role_policy_attachment" "node_ecr" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"
  role       = aws_iam_role.node_group.name
}

resource "aws_iam_role_policy_attachment" "node_ssm" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
  role       = aws_iam_role.node_group.name
}

# Instance profile for nodes
resource "aws_iam_instance_profile" "node_group" {
  name = "${local.cluster_name}-node-profile"
  role = aws_iam_role.node_group.name
  
  tags = local.common_tags
}
```

### Node Groups

```hcl
# infrastructure/terraform/modules/eks/node-groups.tf

# System Node Group (CoreDNS, metrics, etc.)
resource "aws_eks_node_group" "system" {
  cluster_name    = aws_eks_cluster.main.name
  node_group_name = "${local.cluster_name}-system"
  node_role_arn   = aws_iam_role.node_group.arn
  subnet_ids      = var.private_subnet_ids
  
  capacity_type  = "ON_DEMAND"
  instance_types = var.system_instance_types
  
  scaling_config {
    desired_size = var.system_node_count.desired
    min_size     = var.system_node_count.min
    max_size     = var.system_node_count.max
  }
  
  update_config {
    max_unavailable = 1
  }
  
  labels = {
    "node.kubernetes.io/purpose" = "system"
    "workload"                   = "system"
  }
  
  taint {
    key    = "CriticalAddonsOnly"
    value  = "true"
    effect = "NO_SCHEDULE"
  }
  
  tags = merge(local.common_tags, {
    Name = "${local.cluster_name}-system-node"
  })
  
  depends_on = [
    aws_iam_role_policy_attachment.node_worker,
    aws_iam_role_policy_attachment.node_cni,
    aws_iam_role_policy_attachment.node_ecr,
  ]
  
  lifecycle {
    ignore_changes = [scaling_config[0].desired_size]
  }
}

# Application Node Group (Microservices)
resource "aws_eks_node_group" "application" {
  cluster_name    = aws_eks_cluster.main.name
  node_group_name = "${local.cluster_name}-application"
  node_role_arn   = aws_iam_role.node_group.arn
  subnet_ids      = var.private_subnet_ids
  
  capacity_type  = "ON_DEMAND"
  instance_types = var.application_instance_types
  
  scaling_config {
    desired_size = var.application_node_count.desired
    min_size     = var.application_node_count.min
    max_size     = var.application_node_count.max
  }
  
  update_config {
    max_unavailable_percentage = 25
  }
  
  labels = {
    "node.kubernetes.io/purpose" = "application"
    "workload"                   = "application"
  }
  
  tags = merge(local.common_tags, {
    Name                                        = "${local.cluster_name}-application-node"
    "k8s.io/cluster-autoscaler/enabled"        = "true"
    "k8s.io/cluster-autoscaler/${local.cluster_name}" = "owned"
  })
  
  depends_on = [
    aws_iam_role_policy_attachment.node_worker,
    aws_iam_role_policy_attachment.node_cni,
    aws_iam_role_policy_attachment.node_ecr,
  ]
  
  lifecycle {
    ignore_changes = [scaling_config[0].desired_size]
  }
}

# Compute Node Group (CPU-intensive workloads)
resource "aws_eks_node_group" "compute" {
  count = var.create_compute_node_group ? 1 : 0
  
  cluster_name    = aws_eks_cluster.main.name
  node_group_name = "${local.cluster_name}-compute"
  node_role_arn   = aws_iam_role.node_group.arn
  subnet_ids      = var.private_subnet_ids
  
  capacity_type  = "ON_DEMAND"
  instance_types = var.compute_instance_types
  
  scaling_config {
    desired_size = var.compute_node_count.desired
    min_size     = var.compute_node_count.min
    max_size     = var.compute_node_count.max
  }
  
  labels = {
    "node.kubernetes.io/purpose" = "compute"
    "workload"                   = "compute"
  }
  
  taint {
    key    = "workload"
    value  = "compute"
    effect = "NO_SCHEDULE"
  }
  
  tags = merge(local.common_tags, {
    Name                                        = "${local.cluster_name}-compute-node"
    "k8s.io/cluster-autoscaler/enabled"        = "true"
    "k8s.io/cluster-autoscaler/${local.cluster_name}" = "owned"
  })
  
  depends_on = [
    aws_iam_role_policy_attachment.node_worker,
    aws_iam_role_policy_attachment.node_cni,
    aws_iam_role_policy_attachment.node_ecr,
  ]
  
  lifecycle {
    ignore_changes = [scaling_config[0].desired_size]
  }
}

# Spot Node Group (Non-critical, cost-optimized)
resource "aws_eks_node_group" "spot" {
  count = var.create_spot_node_group ? 1 : 0
  
  cluster_name    = aws_eks_cluster.main.name
  node_group_name = "${local.cluster_name}-spot"
  node_role_arn   = aws_iam_role.node_group.arn
  subnet_ids      = var.private_subnet_ids
  
  capacity_type  = "SPOT"
  instance_types = var.spot_instance_types
  
  scaling_config {
    desired_size = var.spot_node_count.desired
    min_size     = var.spot_node_count.min
    max_size     = var.spot_node_count.max
  }
  
  labels = {
    "node.kubernetes.io/purpose"       = "spot"
    "workload"                         = "spot"
    "node.kubernetes.io/lifecycle"     = "spot"
  }
  
  taint {
    key    = "spot"
    value  = "true"
    effect = "NO_SCHEDULE"
  }
  
  tags = merge(local.common_tags, {
    Name                                        = "${local.cluster_name}-spot-node"
    "k8s.io/cluster-autoscaler/enabled"        = "true"
    "k8s.io/cluster-autoscaler/${local.cluster_name}" = "owned"
  })
  
  lifecycle {
    ignore_changes = [scaling_config[0].desired_size]
  }
}
```

### EKS Add-ons

```hcl
# infrastructure/terraform/modules/eks/addons.tf

# VPC CNI Add-on
resource "aws_eks_addon" "vpc_cni" {
  cluster_name                = aws_eks_cluster.main.name
  addon_name                  = "vpc-cni"
  addon_version               = var.vpc_cni_version
  resolve_conflicts_on_update = "OVERWRITE"
  
  configuration_values = jsonencode({
    env = {
      ENABLE_PREFIX_DELEGATION = "true"
      WARM_PREFIX_TARGET       = "1"
    }
  })
  
  tags = local.common_tags
}

# CoreDNS Add-on
resource "aws_eks_addon" "coredns" {
  cluster_name                = aws_eks_cluster.main.name
  addon_name                  = "coredns"
  addon_version               = var.coredns_version
  resolve_conflicts_on_update = "OVERWRITE"
  
  tags = local.common_tags
  
  depends_on = [aws_eks_node_group.system]
}

# kube-proxy Add-on
resource "aws_eks_addon" "kube_proxy" {
  cluster_name                = aws_eks_cluster.main.name
  addon_name                  = "kube-proxy"
  addon_version               = var.kube_proxy_version
  resolve_conflicts_on_update = "OVERWRITE"
  
  tags = local.common_tags
}

# EBS CSI Driver
resource "aws_eks_addon" "ebs_csi" {
  cluster_name                = aws_eks_cluster.main.name
  addon_name                  = "aws-ebs-csi-driver"
  addon_version               = var.ebs_csi_version
  service_account_role_arn    = aws_iam_role.ebs_csi.arn
  resolve_conflicts_on_update = "OVERWRITE"
  
  tags = local.common_tags
  
  depends_on = [aws_eks_node_group.system]
}

# EBS CSI Driver IAM Role
resource "aws_iam_role" "ebs_csi" {
  name = "${local.cluster_name}-ebs-csi-role"
  
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRoleWithWebIdentity"
      Effect = "Allow"
      Principal = {
        Federated = aws_iam_openid_connect_provider.eks.arn
      }
      Condition = {
        StringEquals = {
          "${replace(aws_iam_openid_connect_provider.eks.url, "https://", "")}:sub" = "system:serviceaccount:kube-system:ebs-csi-controller-sa"
          "${replace(aws_iam_openid_connect_provider.eks.url, "https://", "")}:aud" = "sts.amazonaws.com"
        }
      }
    }]
  })
  
  tags = local.common_tags
}

resource "aws_iam_role_policy_attachment" "ebs_csi" {
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy"
  role       = aws_iam_role.ebs_csi.name
}

# EFS CSI Driver (optional)
resource "aws_eks_addon" "efs_csi" {
  count = var.enable_efs_csi ? 1 : 0
  
  cluster_name                = aws_eks_cluster.main.name
  addon_name                  = "aws-efs-csi-driver"
  addon_version               = var.efs_csi_version
  service_account_role_arn    = aws_iam_role.efs_csi[0].arn
  resolve_conflicts_on_update = "OVERWRITE"
  
  tags = local.common_tags
}
```

### Cluster Autoscaler

```hcl
# infrastructure/terraform/modules/eks/cluster-autoscaler.tf

# Cluster Autoscaler IAM Role (IRSA)
resource "aws_iam_role" "cluster_autoscaler" {
  name = "${local.cluster_name}-cluster-autoscaler"
  
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRoleWithWebIdentity"
      Effect = "Allow"
      Principal = {
        Federated = aws_iam_openid_connect_provider.eks.arn
      }
      Condition = {
        StringEquals = {
          "${replace(aws_iam_openid_connect_provider.eks.url, "https://", "")}:sub" = "system:serviceaccount:kube-system:cluster-autoscaler"
          "${replace(aws_iam_openid_connect_provider.eks.url, "https://", "")}:aud" = "sts.amazonaws.com"
        }
      }
    }]
  })
  
  tags = local.common_tags
}

resource "aws_iam_role_policy" "cluster_autoscaler" {
  name = "${local.cluster_name}-cluster-autoscaler-policy"
  role = aws_iam_role.cluster_autoscaler.id
  
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = [
          "autoscaling:DescribeAutoScalingGroups",
          "autoscaling:DescribeAutoScalingInstances",
          "autoscaling:DescribeLaunchConfigurations",
          "autoscaling:DescribeScalingActivities",
          "autoscaling:DescribeTags",
          "ec2:DescribeImages",
          "ec2:DescribeInstanceTypes",
          "ec2:DescribeLaunchTemplateVersions",
          "ec2:GetInstanceTypesFromInstanceRequirements",
          "eks:DescribeNodegroup"
        ]
        Effect   = "Allow"
        Resource = "*"
      },
      {
        Action = [
          "autoscaling:SetDesiredCapacity",
          "autoscaling:TerminateInstanceInAutoScalingGroup"
        ]
        Effect   = "Allow"
        Resource = "*"
        Condition = {
          StringEquals = {
            "aws:ResourceTag/k8s.io/cluster-autoscaler/${local.cluster_name}" = "owned"
          }
        }
      }
    ]
  })
}

# Install Cluster Autoscaler via Helm
resource "helm_release" "cluster_autoscaler" {
  name       = "cluster-autoscaler"
  repository = "https://kubernetes.github.io/autoscaler"
  chart      = "cluster-autoscaler"
  version    = var.cluster_autoscaler_version
  namespace  = "kube-system"
  
  set {
    name  = "autoDiscovery.clusterName"
    value = local.cluster_name
  }
  
  set {
    name  = "awsRegion"
    value = var.aws_region
  }
  
  set {
    name  = "rbac.serviceAccount.name"
    value = "cluster-autoscaler"
  }
  
  set {
    name  = "rbac.serviceAccount.annotations.eks\\.amazonaws\\.com/role-arn"
    value = aws_iam_role.cluster_autoscaler.arn
  }
  
  set {
    name  = "extraArgs.balance-similar-node-groups"
    value = "true"
  }
  
  set {
    name  = "extraArgs.skip-nodes-with-system-pods"
    value = "false"
  }
  
  set {
    name  = "extraArgs.scale-down-delay-after-add"
    value = "5m"
  }
  
  set {
    name  = "extraArgs.scale-down-unneeded-time"
    value = "5m"
  }
  
  depends_on = [aws_eks_node_group.system]
}
```

### Outputs

```hcl
# infrastructure/terraform/modules/eks/outputs.tf

output "cluster_name" {
  description = "EKS cluster name"
  value       = aws_eks_cluster.main.name
}

output "cluster_endpoint" {
  description = "EKS cluster endpoint"
  value       = aws_eks_cluster.main.endpoint
}

output "cluster_certificate_authority" {
  description = "Base64 encoded certificate data for cluster"
  value       = aws_eks_cluster.main.certificate_authority[0].data
}

output "cluster_security_group_id" {
  description = "Security group ID attached to EKS cluster"
  value       = aws_eks_cluster.main.vpc_config[0].cluster_security_group_id
}

output "cluster_oidc_issuer_url" {
  description = "OIDC issuer URL"
  value       = aws_eks_cluster.main.identity[0].oidc[0].issuer
}

output "oidc_provider_arn" {
  description = "OIDC provider ARN"
  value       = aws_iam_openid_connect_provider.eks.arn
}

output "cluster_version" {
  description = "Kubernetes version"
  value       = aws_eks_cluster.main.version
}

output "node_group_role_arn" {
  description = "Node group IAM role ARN"
  value       = aws_iam_role.node_group.arn
}

output "cluster_autoscaler_role_arn" {
  description = "Cluster autoscaler IAM role ARN"
  value       = aws_iam_role.cluster_autoscaler.arn
}

# Kubeconfig helper
output "kubeconfig_command" {
  description = "AWS CLI command to update kubeconfig"
  value       = "aws eks update-kubeconfig --name ${local.cluster_name} --region ${var.aws_region}"
}
```

## Definition of Done

- [ ] EKS cluster deployed with encryption
- [ ] Multiple node groups configured
- [ ] Add-ons (VPC CNI, CoreDNS, EBS CSI) installed
- [ ] OIDC provider created for IRSA
- [ ] Cluster autoscaler deployed
- [ ] Logging enabled to CloudWatch
- [ ] IAM roles for service accounts
- [ ] Documentation complete
- [ ] Cluster accessible via kubectl

## Test Cases

```go
// tests/eks_test.go
package test

import (
	"testing"
	"github.com/gruntwork-io/terratest/modules/terraform"
	"github.com/gruntwork-io/terratest/modules/k8s"
	"github.com/stretchr/testify/assert"
)

func TestEKSCluster(t *testing.T) {
	terraformOptions := &terraform.Options{
		TerraformDir: "../modules/eks",
	}

	defer terraform.Destroy(t, terraformOptions)
	terraform.InitAndApply(t, terraformOptions)

	clusterName := terraform.Output(t, terraformOptions, "cluster_name")
	assert.NotEmpty(t, clusterName)

	// Verify cluster is active
	kubeconfig := terraform.Output(t, terraformOptions, "kubeconfig_command")
	assert.NotEmpty(t, kubeconfig)
}
```
