# Epic 15: AWS Infrastructure & Deployment

## Epic Overview

| Field | Value |
|-------|-------|
| **Epic ID** | EPIC-15 |
| **Epic Name** | AWS Infrastructure & Deployment |
| **Epic Owner** | Platform Engineering / DevOps Lead |
| **Priority** | Critical |
| **Target Release** | Q4 2024 |
| **Total Story Points** | 55 |

## Description

This epic covers the infrastructure-as-code (IaC), deployment pipelines, and AWS service configurations required to run the Orion Liquidity & Data Platform in production. It includes multi-environment setup, security hardening, disaster recovery, and automated deployment processes using Terraform, AWS CDK, and GitHub Actions.

## Business Value

- **Repeatable Deployments**: Infrastructure as code enables consistent, auditable deployments
- **High Availability**: Multi-AZ/region architecture ensures 99.99% uptime
- **Security Compliance**: Automated security controls meet regulatory requirements
- **Cost Optimization**: Right-sizing and auto-scaling reduce infrastructure costs
- **Disaster Recovery**: Automated failover and backup processes minimize RTO/RPO

## User Stories

| Story ID | Title | Points | Priority |
|----------|-------|--------|----------|
| US-15-01 | Terraform Foundation & VPC Setup | 13 | Critical |
| US-15-02 | EKS Cluster Configuration | 13 | Critical |
| US-15-03 | Database Infrastructure (RDS/Aurora) | 8 | Critical |
| US-15-04 | Message Queue Infrastructure | 8 | High |
| US-15-05 | CI/CD Pipeline Configuration | 8 | High |
| US-15-06 | Security & Compliance Automation | 5 | High |

## Technical Architecture

### Infrastructure Stack

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           AWS Cloud                                      │
├─────────────────────────────────────────────────────────────────────────┤
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                         VPC (10.0.0.0/16)                         │   │
│  │  ┌────────────────┐  ┌────────────────┐  ┌────────────────┐      │   │
│  │  │ Public Subnet  │  │ Public Subnet  │  │ Public Subnet  │      │   │
│  │  │    (AZ-a)      │  │    (AZ-b)      │  │    (AZ-c)      │      │   │
│  │  │ 10.0.1.0/24    │  │ 10.0.2.0/24    │  │ 10.0.3.0/24    │      │   │
│  │  │ ┌──────────┐   │  │ ┌──────────┐   │  │ ┌──────────┐   │      │   │
│  │  │ │   ALB    │   │  │ │   ALB    │   │  │ │   ALB    │   │      │   │
│  │  │ │   NAT    │   │  │ │   NAT    │   │  │ │   NAT    │   │      │   │
│  │  │ └──────────┘   │  │ └──────────┘   │  │ └──────────┘   │      │   │
│  │  └────────────────┘  └────────────────┘  └────────────────┘      │   │
│  │  ┌────────────────┐  ┌────────────────┐  ┌────────────────┐      │   │
│  │  │ Private Subnet │  │ Private Subnet │  │ Private Subnet │      │   │
│  │  │    (AZ-a)      │  │    (AZ-b)      │  │    (AZ-c)      │      │   │
│  │  │ 10.0.11.0/24   │  │ 10.0.12.0/24   │  │ 10.0.13.0/24   │      │   │
│  │  │ ┌──────────┐   │  │ ┌──────────┐   │  │ ┌──────────┐   │      │   │
│  │  │ │   EKS    │   │  │ │   EKS    │   │  │ │   EKS    │   │      │   │
│  │  │ │  Nodes   │   │  │ │  Nodes   │   │  │ │  Nodes   │   │      │   │
│  │  │ └──────────┘   │  │ └──────────┘   │  │ └──────────┘   │      │   │
│  │  └────────────────┘  └────────────────┘  └────────────────┘      │   │
│  │  ┌────────────────┐  ┌────────────────┐  ┌────────────────┐      │   │
│  │  │   DB Subnet    │  │   DB Subnet    │  │   DB Subnet    │      │   │
│  │  │    (AZ-a)      │  │    (AZ-b)      │  │    (AZ-c)      │      │   │
│  │  │ 10.0.21.0/24   │  │ 10.0.22.0/24   │  │ 10.0.23.0/24   │      │   │
│  │  │ ┌──────────┐   │  │ ┌──────────┐   │  │ ┌──────────┐   │      │   │
│  │  │ │  Aurora  │   │  │ │  Aurora  │   │  │ │ElastiCache│   │      │   │
│  │  │ │ Primary  │   │  │ │ Replica  │   │  │ │  Redis   │   │      │   │
│  │  │ └──────────┘   │  │ └──────────┘   │  │ └──────────┘   │      │   │
│  │  └────────────────┘  └────────────────┘  └────────────────┘      │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐          │
│  │    Route 53     │  │   CloudFront    │  │      WAF        │          │
│  │   DNS + Health  │  │       CDN       │  │   Protection    │          │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘          │
│                                                                          │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐          │
│  │    MSK/SQS      │  │  Secrets Mgr    │  │   CloudWatch    │          │
│  │  Message Queue  │  │   + KMS Keys    │  │   Monitoring    │          │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘          │
└─────────────────────────────────────────────────────────────────────────┘
```

### Environment Strategy

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Multi-Environment Setup                           │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌─────────────┐   ┌─────────────┐   ┌─────────────┐   ┌─────────────┐  │
│  │     DEV     │   │   STAGING   │   │     UAT     │   │    PROD     │  │
│  │ us-east-1   │   │ us-east-1   │   │ us-east-1   │   │ us-east-1   │  │
│  │             │   │             │   │             │   │ us-west-2   │  │
│  │ Single AZ   │   │ Multi-AZ    │   │ Multi-AZ    │   │ Multi-Region│  │
│  │ t3 instances│   │ t3 instances│   │ m5 instances│   │ m5 instances│  │
│  │ RDS (small) │   │ RDS (med)   │   │ Aurora      │   │ Aurora      │  │
│  └─────────────┘   └─────────────┘   └─────────────┘   └─────────────┘  │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### CI/CD Pipeline

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     GitHub Actions CI/CD Pipeline                        │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│    ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐            │
│    │  Code   │───►│  Build  │───►│  Test   │───►│  Scan   │            │
│    │  Push   │    │ (Maven/ │    │ (Unit/  │    │ (SAST/  │            │
│    │         │    │  npm)   │    │  Int)   │    │  SCA)   │            │
│    └─────────┘    └─────────┘    └─────────┘    └─────────┘            │
│                                        │                                │
│                                        ▼                                │
│    ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐            │
│    │ Deploy  │◄───│  Approve│◄───│ Deploy  │◄───│  Push   │            │
│    │  PROD   │    │  (Gate) │    │ STAGING │    │   ECR   │            │
│    └─────────┘    └─────────┘    └─────────┘    └─────────┘            │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

## Technology Stack

### IaC & Automation
- **Terraform** v1.6+ - Primary IaC tool
- **AWS CDK** v2 - Complex resource patterns
- **Terragrunt** - Environment management
- **GitHub Actions** - CI/CD orchestration
- **ArgoCD** - GitOps for Kubernetes

### AWS Services
- **Compute**: EKS, EC2, Lambda
- **Database**: Aurora PostgreSQL, ElastiCache
- **Messaging**: MSK (Kafka), SQS, EventBridge
- **Storage**: S3, EFS
- **Networking**: VPC, ALB, CloudFront, Route53
- **Security**: WAF, KMS, Secrets Manager, IAM
- **Monitoring**: CloudWatch, X-Ray

## Dependencies

- Epic 01 (IAM) - RBAC policies for deployments
- Epic 02 (Gateway) - Ingress configurations
- Epic 16 (Observability) - Monitoring setup

## Acceptance Criteria (Epic Level)

- [ ] All environments provisionable via single command
- [ ] Blue/green deployments with automatic rollback
- [ ] Infrastructure changes require PR approval
- [ ] Automated security scanning in pipeline
- [ ] 99.99% availability in production
- [ ] RTO < 1 hour, RPO < 5 minutes
- [ ] Cost allocation tags on all resources
