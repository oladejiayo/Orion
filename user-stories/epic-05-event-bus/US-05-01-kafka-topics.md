# User Story: US-05-01 - Kafka Topic Configuration

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-05-01 |
| **Epic** | Epic 05 - Event Bus Infrastructure |
| **Title** | Kafka Topic Configuration |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |
| **PRD Reference** | NFR-EVT-01 |

## User Story

**As a** platform engineer  
**I want** Kafka topics properly configured with partitioning and retention  
**So that** event processing is scalable and reliable

## Description

Define and create all Kafka topics with appropriate partitioning, replication factors, and retention policies. Topics should be created declaratively and versioned in source control.

## Acceptance Criteria

- [ ] All domain topics defined
- [ ] Partitioning by tenant for isolation
- [ ] Retention configured per topic type
- [ ] Topic creation automated
- [ ] Local (Redpanda) and AWS (MSK) configs
- [ ] Schema registry integration

## Technical Details

### Topic Definitions

```yaml
# infra/kafka/topics.yaml
topics:
  # RFQ Domain
  - name: orion.rfq.created
    partitions: 12
    replication: 3
    retention.ms: 604800000  # 7 days
    cleanup.policy: delete
    key: tenant_id
    
  - name: orion.rfq.quoted
    partitions: 12
    replication: 3
    retention.ms: 604800000
    cleanup.policy: delete
    key: rfq_id
    
  - name: orion.rfq.executed
    partitions: 12
    replication: 3
    retention.ms: 2592000000  # 30 days
    cleanup.policy: delete
    key: rfq_id

  - name: orion.rfq.expired
    partitions: 12
    replication: 3
    retention.ms: 604800000
    cleanup.policy: delete
    key: rfq_id

  # Trade Domain
  - name: orion.trade.executed
    partitions: 12
    replication: 3
    retention.ms: 2592000000
    cleanup.policy: delete
    key: trade_id
    
  - name: orion.trade.confirmed
    partitions: 12
    replication: 3
    retention.ms: 2592000000
    cleanup.policy: delete
    key: trade_id

  - name: orion.trade.settled
    partitions: 12
    replication: 3
    retention.ms: 31536000000  # 1 year
    cleanup.policy: delete
    key: trade_id

  # Order Domain
  - name: orion.order.created
    partitions: 12
    replication: 3
    retention.ms: 604800000
    cleanup.policy: delete
    key: order_id

  - name: orion.order.filled
    partitions: 12
    replication: 3
    retention.ms: 2592000000
    cleanup.policy: delete
    key: order_id

  - name: orion.order.cancelled
    partitions: 12
    replication: 3
    retention.ms: 604800000
    cleanup.policy: delete
    key: order_id

  # Market Data (high throughput)
  - name: orion.marketdata.quote
    partitions: 24
    replication: 3
    retention.ms: 86400000  # 1 day
    cleanup.policy: delete
    key: instrument_id
    
  - name: orion.marketdata.trade
    partitions: 24
    replication: 3
    retention.ms: 86400000
    cleanup.policy: delete
    key: instrument_id

  # System Events
  - name: orion.tenant.events
    partitions: 6
    replication: 3
    retention.ms: 2592000000
    cleanup.policy: delete
    key: tenant_id

  - name: orion.instrument.events
    partitions: 6
    replication: 3
    retention.ms: 604800000
    cleanup.policy: delete
    key: instrument_id

  # Dead Letter Queue
  - name: orion.dlq
    partitions: 6
    replication: 3
    retention.ms: 2592000000  # 30 days
    cleanup.policy: delete

  # Outbox relay
  - name: orion.outbox.relay
    partitions: 12
    replication: 3
    retention.ms: 86400000  # 1 day
    cleanup.policy: delete
```

### Topic Management Script

```typescript
// scripts/kafka/manage-topics.ts
import { Kafka, Admin, ITopicConfig } from 'kafkajs';
import { parse } from 'yaml';
import { readFileSync } from 'fs';
import { join } from 'path';

interface TopicDefinition {
  name: string;
  partitions: number;
  replication: number;
  'retention.ms'?: number;
  'cleanup.policy'?: string;
  key?: string;
}

interface TopicsConfig {
  topics: TopicDefinition[];
}

async function manageTopics(action: 'create' | 'delete' | 'describe') {
  const kafka = new Kafka({
    clientId: 'topic-manager',
    brokers: process.env.KAFKA_BROKERS?.split(',') || ['localhost:9092'],
  });

  const admin = kafka.admin();
  await admin.connect();

  try {
    const configPath = join(__dirname, '../../infra/kafka/topics.yaml');
    const config: TopicsConfig = parse(readFileSync(configPath, 'utf8'));

    switch (action) {
      case 'create':
        await createTopics(admin, config.topics);
        break;
      case 'delete':
        await deleteTopics(admin, config.topics);
        break;
      case 'describe':
        await describeTopics(admin, config.topics);
        break;
    }
  } finally {
    await admin.disconnect();
  }
}

async function createTopics(admin: Admin, topics: TopicDefinition[]) {
  const existingTopics = await admin.listTopics();
  const topicsToCreate = topics.filter(t => !existingTopics.includes(t.name));

  if (topicsToCreate.length === 0) {
    console.log('All topics already exist');
    return;
  }

  const topicConfigs: ITopicConfig[] = topicsToCreate.map(t => ({
    topic: t.name,
    numPartitions: t.partitions,
    replicationFactor: t.replication,
    configEntries: [
      { name: 'retention.ms', value: String(t['retention.ms'] || 604800000) },
      { name: 'cleanup.policy', value: t['cleanup.policy'] || 'delete' },
    ],
  }));

  await admin.createTopics({ topics: topicConfigs });
  console.log(`Created ${topicsToCreate.length} topics:`);
  topicsToCreate.forEach(t => console.log(`  - ${t.name}`));
}

async function deleteTopics(admin: Admin, topics: TopicDefinition[]) {
  const topicNames = topics.map(t => t.name);
  await admin.deleteTopics({ topics: topicNames });
  console.log(`Deleted ${topicNames.length} topics`);
}

async function describeTopics(admin: Admin, topics: TopicDefinition[]) {
  const topicNames = topics.map(t => t.name);
  const metadata = await admin.fetchTopicMetadata({ topics: topicNames });
  
  for (const topic of metadata.topics) {
    console.log(`\n${topic.name}:`);
    console.log(`  Partitions: ${topic.partitions.length}`);
    topic.partitions.forEach(p => {
      console.log(`    [${p.partitionId}] Leader: ${p.leader}, Replicas: ${p.replicas.join(',')}`);
    });
  }
}

// Run
const action = process.argv[2] as 'create' | 'delete' | 'describe';
if (!['create', 'delete', 'describe'].includes(action)) {
  console.error('Usage: ts-node manage-topics.ts <create|delete|describe>');
  process.exit(1);
}

manageTopics(action).catch(console.error);
```

### Docker Compose Local Setup

```yaml
# docker-compose.yml (Redpanda for local dev)
services:
  redpanda:
    image: redpandadata/redpanda:v23.3.5
    container_name: orion-redpanda
    command:
      - redpanda
      - start
      - --kafka-addr internal://0.0.0.0:9092,external://0.0.0.0:19092
      - --advertise-kafka-addr internal://redpanda:9092,external://localhost:19092
      - --pandaproxy-addr internal://0.0.0.0:8082,external://0.0.0.0:18082
      - --advertise-pandaproxy-addr internal://redpanda:8082,external://localhost:18082
      - --schema-registry-addr internal://0.0.0.0:8081,external://0.0.0.0:18081
      - --rpc-addr redpanda:33145
      - --advertise-rpc-addr redpanda:33145
      - --mode dev-container
      - --smp 1
      - --memory 1G
    ports:
      - "18081:18081"  # Schema Registry
      - "18082:18082"  # HTTP Proxy
      - "19092:19092"  # Kafka API
    volumes:
      - redpanda-data:/var/lib/redpanda/data
    healthcheck:
      test: ["CMD", "rpk", "cluster", "health"]
      interval: 10s
      timeout: 5s
      retries: 5

  redpanda-console:
    image: redpandadata/console:v2.4.0
    container_name: orion-redpanda-console
    ports:
      - "8080:8080"
    environment:
      - KAFKA_BROKERS=redpanda:9092
      - KAFKA_SCHEMAREGISTRY_ENABLED=true
      - KAFKA_SCHEMAREGISTRY_URLS=http://redpanda:8081
    depends_on:
      redpanda:
        condition: service_healthy

volumes:
  redpanda-data:
```

### AWS MSK Terraform

```hcl
# infra/terraform/modules/msk/main.tf
resource "aws_msk_cluster" "orion" {
  cluster_name           = "orion-${var.environment}"
  kafka_version          = "3.5.1"
  number_of_broker_nodes = var.broker_count

  broker_node_group_info {
    instance_type   = var.instance_type
    client_subnets  = var.subnet_ids
    security_groups = [aws_security_group.msk.id]

    storage_info {
      ebs_storage_info {
        volume_size = var.volume_size_gb
      }
    }
  }

  encryption_info {
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
    encryption_at_rest_kms_key_arn = var.kms_key_arn
  }

  configuration_info {
    arn      = aws_msk_configuration.orion.arn
    revision = aws_msk_configuration.orion.latest_revision
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.msk.name
      }
    }
  }

  tags = {
    Environment = var.environment
    Service     = "orion"
  }
}

resource "aws_msk_configuration" "orion" {
  name              = "orion-${var.environment}-config"
  kafka_versions    = ["3.5.1"]
  
  server_properties = <<PROPERTIES
auto.create.topics.enable=false
default.replication.factor=3
min.insync.replicas=2
num.partitions=12
log.retention.hours=168
log.retention.bytes=-1
PROPERTIES
}

output "bootstrap_brokers_tls" {
  value = aws_msk_cluster.orion.bootstrap_brokers_tls
}
```

## Implementation Steps

1. **Define topic schema**
   - Create YAML configuration
   - Document retention policies
   - Define partitioning strategy

2. **Create management scripts**
   - Topic creation script
   - Validation script
   - CI/CD integration

3. **Set up local environment**
   - Configure Redpanda
   - Add console UI
   - Test topic creation

4. **Create Terraform modules**
   - MSK cluster definition
   - Security groups
   - IAM roles

5. **Add schema registry**
   - Define Avro schemas
   - Configure compatibility
   - Integrate with producers

## Definition of Done

- [ ] All topics defined in YAML
- [ ] Creation script works locally
- [ ] Redpanda running in Docker
- [ ] Terraform module complete
- [ ] Topics created in dev environment
- [ ] Documentation complete

## Dependencies

- **US-01-02**: Docker Compose Local Environment

## Test Cases

```typescript
describe('Topic Management', () => {
  it('should create all defined topics', async () => {
    await manageTopics('create');
    
    const admin = kafka.admin();
    await admin.connect();
    const topics = await admin.listTopics();
    
    expect(topics).toContain('orion.rfq.created');
    expect(topics).toContain('orion.trade.executed');
    expect(topics).toContain('orion.dlq');
    
    await admin.disconnect();
  });

  it('should have correct partition count', async () => {
    const admin = kafka.admin();
    await admin.connect();
    
    const metadata = await admin.fetchTopicMetadata({ topics: ['orion.rfq.created'] });
    expect(metadata.topics[0].partitions.length).toBe(12);
    
    await admin.disconnect();
  });
});
```
