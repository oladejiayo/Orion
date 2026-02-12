# User Story: US-05-05 - Dead Letter Queue Handling

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-05-05 |
| **Epic** | Epic 05 - Event Bus Infrastructure |
| **Title** | Dead Letter Queue Handling |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |

## User Story

**As a** platform operator  
**I want** failed events routed to dead letter queues with alerting  
**So that** I can investigate and reprocess failed events

## Description

Implement DLQ infrastructure with retry policies, alerting on DLQ accumulation, and tooling for manual reprocessing of failed events.

## Acceptance Criteria

- [ ] DLQ topics created for each domain
- [ ] Failed events include error context
- [ ] Retry policies configurable per topic
- [ ] Alerting on DLQ thresholds
- [ ] Manual reprocessing tooling
- [ ] DLQ metrics exposed

## Technical Details

### DLQ Topic Naming Convention

```yaml
# config/kafka/dlq-topics.yaml
dlq_topics:
  # Pattern: {original-topic}.dlq
  - name: orion.events.rfq.dlq
    partitions: 3
    retention_ms: 604800000  # 7 days
    
  - name: orion.events.trade.dlq
    partitions: 3
    retention_ms: 604800000
    
  - name: orion.events.market-data.dlq
    partitions: 3
    retention_ms: 604800000
    
  - name: orion.events.position.dlq
    partitions: 3
    retention_ms: 604800000
```

### DLQ Message Schema

```typescript
// libs/event-model/src/dlq/dlq-message.interface.ts
export interface DlqMessage<T = unknown> {
  // Original message context
  originalTopic: string;
  originalPartition: number;
  originalOffset: string;
  originalKey?: string;
  originalTimestamp: string;
  
  // Event data
  event: T;
  
  // Error context
  error: string;
  stack?: string;
  errorCode?: string;
  
  // Processing context
  consumerGroup: string;
  consumerId?: string;
  processingTimestamp: string;
  retryCount: number;
  
  // Metadata
  metadata: {
    tenantId?: string;
    correlationId?: string;
    userId?: string;
  };
}
```

### DLQ Service

```typescript
// libs/event-model/src/dlq/dlq.service.ts
import { Injectable } from '@nestjs/common';
import { Kafka, Producer } from 'kafkajs';
import { logger, metrics } from '@orion/observability';
import { DlqMessage } from './dlq-message.interface';

export interface DlqOptions {
  consumerGroup: string;
  consumerId?: string;
}

@Injectable()
export class DlqService {
  private producer: Producer;

  constructor(
    private readonly kafka: Kafka,
    private readonly options: DlqOptions,
  ) {
    this.producer = kafka.producer();
  }

  async onModuleInit() {
    await this.producer.connect();
  }

  async onModuleDestroy() {
    await this.producer.disconnect();
  }

  /**
   * Send a failed message to DLQ
   */
  async send(params: {
    originalTopic: string;
    originalPartition: number;
    originalOffset: string;
    originalKey?: string;
    originalTimestamp?: string;
    event: unknown;
    error: string;
    stack?: string;
    errorCode?: string;
    retryCount?: number;
    metadata?: Record<string, string>;
  }): Promise<void> {
    const dlqTopic = `${params.originalTopic}.dlq`;
    
    const dlqMessage: DlqMessage = {
      originalTopic: params.originalTopic,
      originalPartition: params.originalPartition,
      originalOffset: params.originalOffset,
      originalKey: params.originalKey,
      originalTimestamp: params.originalTimestamp || new Date().toISOString(),
      event: params.event,
      error: params.error,
      stack: params.stack,
      errorCode: params.errorCode,
      consumerGroup: this.options.consumerGroup,
      consumerId: this.options.consumerId,
      processingTimestamp: new Date().toISOString(),
      retryCount: params.retryCount || 0,
      metadata: params.metadata || {},
    };

    try {
      await this.producer.send({
        topic: dlqTopic,
        messages: [{
          key: params.originalKey,
          value: JSON.stringify(dlqMessage),
          headers: {
            'error-code': params.errorCode || 'PROCESSING_FAILED',
            'original-topic': params.originalTopic,
            'retry-count': String(params.retryCount || 0),
          },
        }],
      });

      metrics.increment('dlq.messages_sent', { 
        originalTopic: params.originalTopic,
        errorCode: params.errorCode || 'PROCESSING_FAILED',
      });

      logger.warn('Message sent to DLQ', {
        dlqTopic,
        originalOffset: params.originalOffset,
        error: params.error,
      });
    } catch (error) {
      logger.error('Failed to send to DLQ', { error, dlqTopic });
      metrics.increment('dlq.send_failed', { dlqTopic });
      throw error;
    }
  }
}
```

### Retry Service with Backoff

```typescript
// libs/event-model/src/dlq/retry.service.ts
import { Injectable } from '@nestjs/common';
import { Kafka, Consumer, Producer } from 'kafkajs';
import { logger, metrics } from '@orion/observability';
import { DlqMessage } from './dlq-message.interface';

export interface RetryPolicy {
  maxRetries: number;
  initialDelayMs: number;
  maxDelayMs: number;
  multiplier: number;
}

const DEFAULT_RETRY_POLICY: RetryPolicy = {
  maxRetries: 3,
  initialDelayMs: 1000,
  maxDelayMs: 60000,
  multiplier: 2,
};

@Injectable()
export class RetryService {
  private consumer: Consumer;
  private producer: Producer;

  constructor(
    private readonly kafka: Kafka,
    private readonly dlqTopics: string[],
    private readonly retryPolicy: RetryPolicy = DEFAULT_RETRY_POLICY,
  ) {
    this.consumer = kafka.consumer({ groupId: 'orion-dlq-retry' });
    this.producer = kafka.producer();
  }

  async start(): Promise<void> {
    await this.consumer.connect();
    await this.producer.connect();

    for (const topic of this.dlqTopics) {
      await this.consumer.subscribe({ topic, fromBeginning: false });
    }

    await this.consumer.run({
      eachMessage: async ({ topic, partition, message }) => {
        await this.processRetry(topic, partition, message);
      },
    });

    logger.info('DLQ retry service started', { topics: this.dlqTopics });
  }

  async stop(): Promise<void> {
    await this.consumer.disconnect();
    await this.producer.disconnect();
  }

  private async processRetry(
    topic: string,
    partition: number,
    message: any,
  ): Promise<void> {
    const dlqMessage: DlqMessage = JSON.parse(message.value.toString());
    const currentRetry = dlqMessage.retryCount + 1;

    if (currentRetry > this.retryPolicy.maxRetries) {
      logger.error('Max retries exceeded, message will not be retried', {
        originalTopic: dlqMessage.originalTopic,
        originalOffset: dlqMessage.originalOffset,
        retryCount: currentRetry,
      });
      
      metrics.increment('dlq.max_retries_exceeded', {
        originalTopic: dlqMessage.originalTopic,
      });
      return;
    }

    // Calculate backoff delay
    const delay = Math.min(
      this.retryPolicy.initialDelayMs * Math.pow(this.retryPolicy.multiplier, currentRetry - 1),
      this.retryPolicy.maxDelayMs,
    );

    logger.info('Scheduling retry', {
      originalTopic: dlqMessage.originalTopic,
      retryCount: currentRetry,
      delayMs: delay,
    });

    // Wait for backoff
    await this.sleep(delay);

    try {
      // Republish to original topic
      await this.producer.send({
        topic: dlqMessage.originalTopic,
        messages: [{
          key: dlqMessage.originalKey,
          value: JSON.stringify(dlqMessage.event),
          headers: {
            'x-retry-count': String(currentRetry),
            'x-original-offset': dlqMessage.originalOffset,
          },
        }],
      });

      metrics.increment('dlq.retry_success', {
        originalTopic: dlqMessage.originalTopic,
        retryCount: String(currentRetry),
      });

      logger.info('Retry successful', {
        originalTopic: dlqMessage.originalTopic,
        retryCount: currentRetry,
      });
    } catch (error) {
      logger.error('Retry failed', {
        originalTopic: dlqMessage.originalTopic,
        error,
      });
      
      metrics.increment('dlq.retry_failed', {
        originalTopic: dlqMessage.originalTopic,
      });
    }
  }

  private sleep(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
  }
}
```

### DLQ Alerting

```typescript
// services/platform-service/src/dlq/dlq-monitor.service.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { Kafka, Admin } from 'kafkajs';
import { logger, metrics } from '@orion/observability';
import { AlertService } from '../alert/alert.service';

interface DlqThreshold {
  topic: string;
  warnThreshold: number;
  criticalThreshold: number;
}

@Injectable()
export class DlqMonitorService implements OnModuleInit {
  private admin: Admin;
  private checkInterval: NodeJS.Timeout;

  private readonly thresholds: DlqThreshold[] = [
    { topic: 'orion.events.rfq.dlq', warnThreshold: 10, criticalThreshold: 50 },
    { topic: 'orion.events.trade.dlq', warnThreshold: 5, criticalThreshold: 20 },
    { topic: 'orion.events.market-data.dlq', warnThreshold: 100, criticalThreshold: 500 },
  ];

  constructor(
    private readonly kafka: Kafka,
    private readonly alertService: AlertService,
  ) {
    this.admin = kafka.admin();
  }

  async onModuleInit() {
    await this.admin.connect();
    this.startMonitoring();
  }

  async onModuleDestroy() {
    if (this.checkInterval) {
      clearInterval(this.checkInterval);
    }
    await this.admin.disconnect();
  }

  private startMonitoring(): void {
    // Check every 60 seconds
    this.checkInterval = setInterval(async () => {
      await this.checkDlqLevels();
    }, 60000);

    // Initial check
    this.checkDlqLevels();
  }

  private async checkDlqLevels(): Promise<void> {
    for (const threshold of this.thresholds) {
      try {
        const offsets = await this.admin.fetchTopicOffsets(threshold.topic);
        
        let totalMessages = 0;
        for (const partition of offsets) {
          const high = parseInt(partition.high, 10);
          const low = parseInt(partition.low, 10);
          totalMessages += (high - low);
        }

        metrics.gauge('dlq.message_count', totalMessages, { topic: threshold.topic });

        if (totalMessages >= threshold.criticalThreshold) {
          await this.alertService.send({
            severity: 'critical',
            title: 'DLQ Critical Threshold Exceeded',
            message: `DLQ ${threshold.topic} has ${totalMessages} messages (threshold: ${threshold.criticalThreshold})`,
            source: 'dlq-monitor',
            tags: { topic: threshold.topic },
          });
        } else if (totalMessages >= threshold.warnThreshold) {
          await this.alertService.send({
            severity: 'warning',
            title: 'DLQ Warning Threshold Exceeded',
            message: `DLQ ${threshold.topic} has ${totalMessages} messages (threshold: ${threshold.warnThreshold})`,
            source: 'dlq-monitor',
            tags: { topic: threshold.topic },
          });
        }
      } catch (error) {
        logger.error('Failed to check DLQ levels', { topic: threshold.topic, error });
      }
    }
  }
}
```

### DLQ Admin CLI

```typescript
// tools/dlq-admin/src/commands.ts
import { Command } from 'commander';
import { Kafka } from 'kafkajs';
import { DlqMessage } from '@orion/event-model';

const kafka = new Kafka({
  clientId: 'dlq-admin',
  brokers: process.env.KAFKA_BROKERS?.split(',') || ['localhost:9092'],
});

const program = new Command();

program
  .name('dlq-admin')
  .description('Dead Letter Queue administration tool')
  .version('1.0.0');

/**
 * List messages in DLQ
 */
program
  .command('list')
  .description('List messages in a DLQ topic')
  .argument('<topic>', 'DLQ topic name')
  .option('-l, --limit <number>', 'Maximum messages to list', '10')
  .action(async (topic: string, options: { limit: string }) => {
    const consumer = kafka.consumer({ groupId: `dlq-admin-${Date.now()}` });
    
    await consumer.connect();
    await consumer.subscribe({ topic, fromBeginning: true });

    const messages: DlqMessage[] = [];
    const limit = parseInt(options.limit, 10);

    await consumer.run({
      eachMessage: async ({ message }) => {
        if (messages.length < limit) {
          const dlqMessage = JSON.parse(message.value?.toString() || '{}');
          messages.push(dlqMessage);
        }
      },
    });

    // Wait for messages
    await new Promise(resolve => setTimeout(resolve, 5000));
    await consumer.disconnect();

    console.table(messages.map(m => ({
      offset: m.originalOffset,
      topic: m.originalTopic,
      error: m.error.substring(0, 50),
      retryCount: m.retryCount,
      timestamp: m.processingTimestamp,
    })));
  });

/**
 * Reprocess a specific message
 */
program
  .command('reprocess')
  .description('Reprocess a message from DLQ')
  .argument('<topic>', 'DLQ topic name')
  .argument('<offset>', 'Message offset to reprocess')
  .action(async (topic: string, offset: string) => {
    const consumer = kafka.consumer({ groupId: `dlq-reprocess-${Date.now()}` });
    const producer = kafka.producer();

    await consumer.connect();
    await producer.connect();

    console.log(`Looking for message at offset ${offset}...`);

    // Find and reprocess the message
    // Implementation would seek to specific offset
    
    await consumer.disconnect();
    await producer.disconnect();
  });

/**
 * Purge DLQ
 */
program
  .command('purge')
  .description('Purge all messages from a DLQ topic')
  .argument('<topic>', 'DLQ topic name')
  .option('--confirm', 'Confirm purge operation')
  .action(async (topic: string, options: { confirm?: boolean }) => {
    if (!options.confirm) {
      console.error('Use --confirm to confirm purge operation');
      process.exit(1);
    }

    const admin = kafka.admin();
    await admin.connect();

    await admin.deleteTopicRecords({
      topic,
      partitions: [
        { partition: 0, offset: '-1' },
        { partition: 1, offset: '-1' },
        { partition: 2, offset: '-1' },
      ],
    });

    console.log(`Purged all messages from ${topic}`);
    await admin.disconnect();
  });

program.parse();
```

### CloudWatch Alarm

```hcl
# terraform/modules/kafka/dlq-alarms.tf

resource "aws_cloudwatch_metric_alarm" "dlq_critical" {
  for_each = toset([
    "orion.events.rfq.dlq",
    "orion.events.trade.dlq",
  ])

  alarm_name          = "dlq-critical-${each.value}"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "dlq.message_count"
  namespace           = "Orion/DLQ"
  period              = 60
  statistic           = "Maximum"
  threshold           = 50
  alarm_description   = "DLQ ${each.value} has exceeded critical threshold"

  dimensions = {
    Topic = each.value
  }

  alarm_actions = [aws_sns_topic.alerts.arn]
  ok_actions    = [aws_sns_topic.alerts.arn]

  tags = {
    Environment = var.environment
    Service     = "orion-platform"
  }
}
```

## Definition of Done

- [ ] DLQ topics created
- [ ] DLQ service sends failed messages
- [ ] Retry service with backoff
- [ ] Alerting on thresholds
- [ ] Admin CLI functional
- [ ] CloudWatch alarms configured
- [ ] Documentation complete

## Dependencies

- **US-05-01**: Kafka Topic Configuration
- **US-05-04**: Event Consumer Library

## Test Cases

```typescript
describe('DlqService', () => {
  it('should send failed message to DLQ topic', async () => {
    const producerSpy = jest.spyOn(producer, 'send');

    await dlqService.send({
      originalTopic: 'orion.events.rfq',
      originalPartition: 0,
      originalOffset: '100',
      event: { rfqId: '123' },
      error: 'Processing failed',
    });

    expect(producerSpy).toHaveBeenCalledWith({
      topic: 'orion.events.rfq.dlq',
      messages: expect.arrayContaining([
        expect.objectContaining({
          headers: expect.objectContaining({
            'error-code': 'PROCESSING_FAILED',
          }),
        }),
      ]),
    });
  });
});

describe('RetryService', () => {
  it('should respect max retries', async () => {
    const dlqMessage: DlqMessage = {
      retryCount: 3, // Already at max
      originalTopic: 'orion.events.rfq',
      event: {},
    };

    await retryService.processRetry('dlq', 0, {
      value: Buffer.from(JSON.stringify(dlqMessage)),
    });

    expect(metrics.increment).toHaveBeenCalledWith(
      'dlq.max_retries_exceeded',
      expect.anything(),
    );
  });
});
```
