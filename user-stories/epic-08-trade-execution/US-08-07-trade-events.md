# User Story: US-08-07 - Trade Event Publishing

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-08-07 |
| **Epic** | Epic 08 - Trade Execution |
| **Title** | Trade Event Publishing |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |
| **PRD Reference** | FR-Trade-07, NFR-Event-01 |

## User Story

**As a** platform architect  
**I want** reliable trade event publishing  
**So that** downstream systems receive all trade lifecycle events with guaranteed delivery

## Description

Implement trade event publishing using transactional outbox pattern, ensuring exactly-once delivery to Kafka topics for consumption by settlement, risk, reporting, and external systems.

## Acceptance Criteria

- [ ] Transactional outbox implementation
- [ ] Kafka producer with idempotency
- [ ] Event schemas for all trade lifecycle events
- [ ] Dead letter queue for failures
- [ ] Event replay capability
- [ ] Consumer group management
- [ ] Event monitoring and alerting

## Technical Details

### Trade Event Types

```typescript
// libs/events/src/trade/trade-event.types.ts

export enum TradeEventType {
  // Lifecycle events
  TRADE_CREATED = 'trade.created',
  TRADE_VALIDATED = 'trade.validated',
  TRADE_REJECTED = 'trade.rejected',
  TRADE_BOOKED = 'trade.booked',
  TRADE_AMENDED = 'trade.amended',
  TRADE_CANCELLED = 'trade.cancelled',
  TRADE_ALLOCATED = 'trade.allocated',
  TRADE_SETTLING = 'trade.settling',
  TRADE_SETTLED = 'trade.settled',
  
  // Sub-events
  TRADE_ENRICHED = 'trade.enriched',
  TRADE_FEES_CALCULATED = 'trade.fees.calculated',
  TRADE_ALLOCATION_CREATED = 'trade.allocation.created',
  TRADE_ALLOCATION_CONFIRMED = 'trade.allocation.confirmed',
  TRADE_AMENDMENT_PENDING = 'trade.amendment.pending',
  TRADE_AMENDMENT_APPROVED = 'trade.amendment.approved',
  TRADE_AMENDMENT_REJECTED = 'trade.amendment.rejected',
}

export interface TradeEventBase {
  eventId: string;
  eventType: TradeEventType;
  timestamp: string;
  version: string;
  correlationId?: string;
  causationId?: string;
  metadata: {
    tenantId: string;
    userId?: string;
    source: string;
  };
}

export interface TradeCreatedEvent extends TradeEventBase {
  eventType: TradeEventType.TRADE_CREATED;
  payload: {
    tradeId: string;
    tradeRef: string;
    sourceType: string;
    sourceId?: string;
    clientId: string;
    counterpartyId: string;
    instrumentId: string;
    symbol: string;
    side: 'buy' | 'sell';
    quantity: number;
    price: number;
    notionalAmount: number;
    currency: string;
    tradeDate: string;
    settlementDate: string;
    status: string;
  };
}

export interface TradeBookedEvent extends TradeEventBase {
  eventType: TradeEventType.TRADE_BOOKED;
  payload: {
    tradeId: string;
    tradeRef: string;
    bookedAt: string;
    bookedBy?: string;
    previousStatus: string;
  };
}

export interface TradeAmendedEvent extends TradeEventBase {
  eventType: TradeEventType.TRADE_AMENDED;
  payload: {
    tradeId: string;
    tradeRef: string;
    previousVersion: number;
    newVersion: number;
    changes: Record<string, { from: any; to: any }>;
    reason: string;
    amendedBy?: string;
  };
}

export interface TradeCancelledEvent extends TradeEventBase {
  eventType: TradeEventType.TRADE_CANCELLED;
  payload: {
    tradeId: string;
    tradeRef: string;
    previousStatus: string;
    reason: string;
    category?: string;
    cancelledBy?: string;
  };
}

export interface TradeAllocatedEvent extends TradeEventBase {
  eventType: TradeEventType.TRADE_ALLOCATED;
  payload: {
    tradeId: string;
    tradeRef: string;
    allocations: Array<{
      allocationId: string;
      accountId: string;
      quantity: number;
      percentage: number;
      notional: number;
    }>;
    allocatedBy?: string;
  };
}

export interface TradeSettledEvent extends TradeEventBase {
  eventType: TradeEventType.TRADE_SETTLED;
  payload: {
    tradeId: string;
    tradeRef: string;
    settlementId: string;
    settledAmount: number;
    settledAt: string;
  };
}

export type TradeEvent =
  | TradeCreatedEvent
  | TradeBookedEvent
  | TradeAmendedEvent
  | TradeCancelledEvent
  | TradeAllocatedEvent
  | TradeSettledEvent;
```

### Outbox Entity

```typescript
// libs/events/src/outbox/outbox.entity.ts
import { Entity, PrimaryGeneratedColumn, Column, CreateDateColumn, Index } from 'typeorm';

export enum OutboxStatus {
  PENDING = 'pending',
  PROCESSING = 'processing',
  PUBLISHED = 'published',
  FAILED = 'failed',
  DEAD_LETTER = 'dead_letter',
}

@Entity('event_outbox')
@Index(['status', 'createdAt'])
@Index(['aggregateType', 'aggregateId'])
@Index(['eventType'])
export class OutboxEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column('uuid')
  tenantId: string;

  @Column('varchar', { length: 100 })
  aggregateType: string; // 'trade', 'rfq', 'order'

  @Column('uuid')
  aggregateId: string;

  @Column('varchar', { length: 100 })
  eventType: string;

  @Column('varchar', { length: 100 })
  topic: string;

  @Column('varchar', { length: 255, nullable: true })
  partitionKey: string;

  @Column('jsonb')
  payload: Record<string, any>;

  @Column({
    type: 'enum',
    enum: OutboxStatus,
    default: OutboxStatus.PENDING,
  })
  status: OutboxStatus;

  @Column('int', { default: 0 })
  retryCount: number;

  @Column('int', { default: 5 })
  maxRetries: number;

  @Column('text', { nullable: true })
  lastError: string;

  @Column('timestamptz', { nullable: true })
  processedAt: Date;

  @Column('timestamptz', { nullable: true })
  publishedAt: Date;

  @Column('varchar', { length: 50, nullable: true })
  kafkaOffset: string;

  @CreateDateColumn()
  createdAt: Date;

  @Column('int', { default: 1 })
  version: number;
}
```

### Trade Event Publisher Service

```typescript
// services/trade-service/src/events/trade-event-publisher.service.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, EntityManager } from 'typeorm';
import { v4 as uuidv4 } from 'uuid';
import { logger } from '@orion/observability';
import { OutboxEntity, OutboxStatus } from './outbox.entity';
import { TradeEvent, TradeEventType, TradeEventBase } from './trade-event.types';
import { TradeEntity } from '../entities/trade.entity';
import { getCurrentTenant, getCurrentUser } from '@orion/security';

const TOPIC_MAPPING: Record<TradeEventType, string> = {
  [TradeEventType.TRADE_CREATED]: 'orion.trades.lifecycle',
  [TradeEventType.TRADE_VALIDATED]: 'orion.trades.lifecycle',
  [TradeEventType.TRADE_REJECTED]: 'orion.trades.lifecycle',
  [TradeEventType.TRADE_BOOKED]: 'orion.trades.lifecycle',
  [TradeEventType.TRADE_AMENDED]: 'orion.trades.amendments',
  [TradeEventType.TRADE_CANCELLED]: 'orion.trades.lifecycle',
  [TradeEventType.TRADE_ALLOCATED]: 'orion.trades.allocations',
  [TradeEventType.TRADE_SETTLING]: 'orion.trades.settlement',
  [TradeEventType.TRADE_SETTLED]: 'orion.trades.settlement',
  [TradeEventType.TRADE_ENRICHED]: 'orion.trades.enrichment',
  [TradeEventType.TRADE_FEES_CALCULATED]: 'orion.trades.fees',
  [TradeEventType.TRADE_ALLOCATION_CREATED]: 'orion.trades.allocations',
  [TradeEventType.TRADE_ALLOCATION_CONFIRMED]: 'orion.trades.allocations',
  [TradeEventType.TRADE_AMENDMENT_PENDING]: 'orion.trades.amendments',
  [TradeEventType.TRADE_AMENDMENT_APPROVED]: 'orion.trades.amendments',
  [TradeEventType.TRADE_AMENDMENT_REJECTED]: 'orion.trades.amendments',
};

@Injectable()
export class TradeEventPublisherService {
  constructor(
    @InjectRepository(OutboxEntity)
    private readonly outboxRepo: Repository<OutboxEntity>,
  ) {}

  /**
   * Publish event within a transaction (writes to outbox)
   */
  async publishWithTransaction(
    manager: EntityManager,
    eventType: TradeEventType,
    payload: Record<string, any>,
    options?: {
      correlationId?: string;
      causationId?: string;
      partitionKey?: string;
    },
  ): Promise<string> {
    const eventId = uuidv4();
    const tenant = getCurrentTenant();
    const user = getCurrentUser();

    const event: TradeEventBase & { payload: any } = {
      eventId,
      eventType,
      timestamp: new Date().toISOString(),
      version: '1.0',
      correlationId: options?.correlationId,
      causationId: options?.causationId,
      metadata: {
        tenantId: tenant?.tenantId || '',
        userId: user?.id,
        source: 'trade-service',
      },
      payload,
    };

    const outbox = new OutboxEntity();
    outbox.tenantId = tenant?.tenantId || '';
    outbox.aggregateType = 'trade';
    outbox.aggregateId = payload.tradeId;
    outbox.eventType = eventType;
    outbox.topic = TOPIC_MAPPING[eventType] || 'orion.trades.default';
    outbox.partitionKey = options?.partitionKey || payload.tradeId;
    outbox.payload = event;
    outbox.status = OutboxStatus.PENDING;

    await manager.save(outbox);

    logger.debug('Trade event queued to outbox', {
      eventId,
      eventType,
      tradeId: payload.tradeId,
    });

    return eventId;
  }

  /**
   * Build trade created event
   */
  buildCreatedEvent(trade: TradeEntity): Record<string, any> {
    return {
      tradeId: trade.id,
      tradeRef: trade.tradeRef,
      sourceType: trade.sourceType,
      sourceId: trade.sourceId,
      clientId: trade.clientId,
      counterpartyId: trade.counterpartyId,
      instrumentId: trade.instrumentId,
      symbol: trade.symbol,
      side: trade.side,
      quantity: trade.quantity,
      price: trade.price,
      notionalAmount: trade.notionalAmount,
      currency: trade.currency,
      tradeDate: trade.tradeDate.toISOString(),
      settlementDate: trade.settlementDate.toISOString(),
      status: trade.status,
    };
  }

  /**
   * Build trade booked event
   */
  buildBookedEvent(trade: TradeEntity, previousStatus: string): Record<string, any> {
    return {
      tradeId: trade.id,
      tradeRef: trade.tradeRef,
      bookedAt: trade.bookedAt?.toISOString(),
      bookedBy: trade.bookedBy,
      previousStatus,
    };
  }

  /**
   * Build trade amended event
   */
  buildAmendedEvent(
    trade: TradeEntity,
    previousVersion: number,
    changes: Record<string, any>,
    reason: string,
  ): Record<string, any> {
    return {
      tradeId: trade.id,
      tradeRef: trade.tradeRef,
      previousVersion,
      newVersion: trade.version,
      changes,
      reason,
      amendedBy: trade.amendedBy,
    };
  }
}
```

### Outbox Processor Service

```typescript
// libs/events/src/outbox/outbox-processor.service.ts
import { Injectable, OnModuleInit, OnModuleDestroy } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, LessThan, In } from 'typeorm';
import { Kafka, Producer, CompressionTypes } from 'kafkajs';
import { logger, metrics } from '@orion/observability';
import { OutboxEntity, OutboxStatus } from './outbox.entity';
import { ConfigService } from '@nestjs/config';

@Injectable()
export class OutboxProcessorService implements OnModuleInit, OnModuleDestroy {
  private kafka: Kafka;
  private producer: Producer;
  private isRunning = false;
  private processingInterval: NodeJS.Timeout;

  constructor(
    @InjectRepository(OutboxEntity)
    private readonly outboxRepo: Repository<OutboxEntity>,
    private readonly configService: ConfigService,
  ) {
    this.kafka = new Kafka({
      clientId: 'orion-outbox-processor',
      brokers: this.configService.get<string[]>('kafka.brokers', ['localhost:9092']),
    });

    this.producer = this.kafka.producer({
      idempotent: true,
      maxInFlightRequests: 5,
      transactionalId: 'orion-trade-outbox',
    });
  }

  async onModuleInit() {
    await this.producer.connect();
    this.startProcessing();
    logger.info('Outbox processor started');
  }

  async onModuleDestroy() {
    this.isRunning = false;
    clearInterval(this.processingInterval);
    await this.producer.disconnect();
    logger.info('Outbox processor stopped');
  }

  private startProcessing() {
    this.isRunning = true;
    
    // Poll for pending events every 100ms
    this.processingInterval = setInterval(async () => {
      if (this.isRunning) {
        await this.processPendingEvents();
      }
    }, 100);
  }

  private async processPendingEvents() {
    const batchSize = 100;

    try {
      // Get pending events
      const events = await this.outboxRepo.find({
        where: {
          status: In([OutboxStatus.PENDING, OutboxStatus.PROCESSING]),
        },
        order: { createdAt: 'ASC' },
        take: batchSize,
      });

      if (events.length === 0) return;

      // Mark as processing
      await this.outboxRepo.update(
        { id: In(events.map(e => e.id)) },
        { status: OutboxStatus.PROCESSING },
      );

      // Group by topic for batch publishing
      const byTopic = this.groupByTopic(events);

      for (const [topic, topicEvents] of Object.entries(byTopic)) {
        await this.publishBatch(topic, topicEvents);
      }

      metrics.increment('outbox.events.published', { count: events.length.toString() });
    } catch (error) {
      logger.error('Outbox processing error', { error });
      metrics.increment('outbox.errors');
    }
  }

  private groupByTopic(events: OutboxEntity[]): Record<string, OutboxEntity[]> {
    return events.reduce((acc, event) => {
      if (!acc[event.topic]) {
        acc[event.topic] = [];
      }
      acc[event.topic].push(event);
      return acc;
    }, {} as Record<string, OutboxEntity[]>);
  }

  private async publishBatch(topic: string, events: OutboxEntity[]) {
    const messages = events.map(event => ({
      key: event.partitionKey,
      value: JSON.stringify(event.payload),
      headers: {
        eventId: event.payload.eventId,
        eventType: event.eventType,
        tenantId: event.tenantId,
        timestamp: event.payload.timestamp,
      },
    }));

    try {
      const result = await this.producer.send({
        topic,
        messages,
        compression: CompressionTypes.GZIP,
      });

      // Mark as published
      await this.outboxRepo.update(
        { id: In(events.map(e => e.id)) },
        {
          status: OutboxStatus.PUBLISHED,
          publishedAt: new Date(),
        },
      );

      logger.debug('Published batch to Kafka', {
        topic,
        count: events.length,
        partition: result[0]?.partition,
        offset: result[0]?.baseOffset,
      });
    } catch (error) {
      // Handle publish failure
      for (const event of events) {
        await this.handlePublishFailure(event, error);
      }
    }
  }

  private async handlePublishFailure(event: OutboxEntity, error: Error) {
    event.retryCount += 1;
    event.lastError = error.message;

    if (event.retryCount >= event.maxRetries) {
      event.status = OutboxStatus.DEAD_LETTER;
      logger.error('Event moved to dead letter', {
        eventId: event.payload.eventId,
        eventType: event.eventType,
        retries: event.retryCount,
      });
    } else {
      event.status = OutboxStatus.PENDING; // Will retry
    }

    await this.outboxRepo.save(event);
  }

  /**
   * Replay events for a specific trade (for debugging/recovery)
   */
  async replayEvents(tradeId: string, fromDate?: Date): Promise<number> {
    const where: any = {
      aggregateId: tradeId,
      status: OutboxStatus.PUBLISHED,
    };

    if (fromDate) {
      where.createdAt = LessThan(fromDate);
    }

    const events = await this.outboxRepo.find({
      where,
      order: { createdAt: 'ASC' },
    });

    // Re-publish events
    for (const event of events) {
      await this.producer.send({
        topic: event.topic,
        messages: [{
          key: event.partitionKey,
          value: JSON.stringify(event.payload),
          headers: {
            eventId: event.payload.eventId,
            eventType: event.eventType,
            replay: 'true',
            originalTimestamp: event.payload.timestamp,
          },
        }],
      });
    }

    logger.info('Replayed events', { tradeId, count: events.length });
    return events.length;
  }
}
```

### Dead Letter Handler

```typescript
// libs/events/src/outbox/dead-letter-handler.service.ts
import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Cron, CronExpression } from '@nestjs/schedule';
import { logger, metrics } from '@orion/observability';
import { OutboxEntity, OutboxStatus } from './outbox.entity';
import { AlertService } from '@orion/alerts';

@Injectable()
export class DeadLetterHandlerService {
  constructor(
    @InjectRepository(OutboxEntity)
    private readonly outboxRepo: Repository<OutboxEntity>,
    private readonly alertService: AlertService,
  ) {}

  @Cron(CronExpression.EVERY_5_MINUTES)
  async checkDeadLetterQueue() {
    const count = await this.outboxRepo.count({
      where: { status: OutboxStatus.DEAD_LETTER },
    });

    if (count > 0) {
      logger.warn('Dead letter queue has events', { count });
      metrics.gauge('outbox.dead_letter.count', count);

      // Alert if threshold exceeded
      if (count > 10) {
        await this.alertService.send({
          severity: 'warning',
          title: 'Trade Event Dead Letter Queue',
          message: `${count} events in dead letter queue`,
          metadata: { component: 'outbox-processor' },
        });
      }
    }
  }

  async getDeadLetterEvents(limit = 100): Promise<OutboxEntity[]> {
    return this.outboxRepo.find({
      where: { status: OutboxStatus.DEAD_LETTER },
      order: { createdAt: 'DESC' },
      take: limit,
    });
  }

  async retryDeadLetter(eventId: string): Promise<void> {
    await this.outboxRepo.update(
      { id: eventId, status: OutboxStatus.DEAD_LETTER },
      { status: OutboxStatus.PENDING, retryCount: 0 },
    );
  }

  async discardDeadLetter(eventId: string, reason: string): Promise<void> {
    const event = await this.outboxRepo.findOne({ where: { id: eventId } });
    if (event) {
      event.lastError = `Discarded: ${reason}`;
      await this.outboxRepo.save(event);
      
      logger.info('Dead letter event discarded', { eventId, reason });
    }
  }
}
```

### Event Consumer Example

```typescript
// services/settlement-service/src/consumers/trade-event.consumer.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { Kafka, Consumer, EachMessagePayload } from 'kafkajs';
import { logger, metrics } from '@orion/observability';
import { TradeEvent, TradeEventType } from '@orion/events';

@Injectable()
export class TradeEventConsumer implements OnModuleInit {
  private consumer: Consumer;

  constructor() {
    const kafka = new Kafka({
      clientId: 'settlement-service',
      brokers: ['localhost:9092'],
    });

    this.consumer = kafka.consumer({
      groupId: 'settlement-service-trades',
      sessionTimeout: 30000,
      heartbeatInterval: 3000,
    });
  }

  async onModuleInit() {
    await this.consumer.connect();
    await this.consumer.subscribe({
      topics: ['orion.trades.lifecycle', 'orion.trades.settlement'],
      fromBeginning: false,
    });

    await this.consumer.run({
      eachMessage: async (payload) => this.handleMessage(payload),
    });

    logger.info('Trade event consumer started');
  }

  private async handleMessage({ topic, partition, message }: EachMessagePayload) {
    const startTime = Date.now();

    try {
      const event: TradeEvent = JSON.parse(message.value!.toString());
      
      logger.debug('Received trade event', {
        eventType: event.eventType,
        tradeId: event.payload.tradeId,
      });

      // Route to appropriate handler
      switch (event.eventType) {
        case TradeEventType.TRADE_BOOKED:
          await this.handleTradeBooked(event);
          break;
        case TradeEventType.TRADE_ALLOCATED:
          await this.handleTradeAllocated(event);
          break;
        case TradeEventType.TRADE_CANCELLED:
          await this.handleTradeCancelled(event);
          break;
        default:
          logger.debug('Ignoring event type', { eventType: event.eventType });
      }

      metrics.timing('trade.event.processing', Date.now() - startTime);
      metrics.increment('trade.events.processed', { eventType: event.eventType });
    } catch (error) {
      logger.error('Failed to process trade event', {
        topic,
        partition,
        offset: message.offset,
        error,
      });
      metrics.increment('trade.events.errors');
      throw error; // Trigger retry
    }
  }

  private async handleTradeBooked(event: any): Promise<void> {
    // Create settlement instruction
    logger.info('Processing booked trade for settlement', {
      tradeId: event.payload.tradeId,
    });
  }

  private async handleTradeAllocated(event: any): Promise<void> {
    // Create settlement instructions for each allocation
    logger.info('Processing allocated trade', {
      tradeId: event.payload.tradeId,
      allocations: event.payload.allocations.length,
    });
  }

  private async handleTradeCancelled(event: any): Promise<void> {
    // Cancel pending settlements
    logger.info('Cancelling settlements for trade', {
      tradeId: event.payload.tradeId,
    });
  }
}
```

### Outbox SQL Schema

```sql
-- migrations/010_event_outbox_schema.sql

CREATE TABLE event_outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    topic VARCHAR(100) NOT NULL,
    partition_key VARCHAR(255),
    payload JSONB NOT NULL,
    status VARCHAR(20) DEFAULT 'pending',
    retry_count INT DEFAULT 0,
    max_retries INT DEFAULT 5,
    last_error TEXT,
    processed_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    kafka_offset VARCHAR(50),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    version INT DEFAULT 1
);

-- Indexes for efficient polling
CREATE INDEX idx_outbox_status_created ON event_outbox(status, created_at);
CREATE INDEX idx_outbox_aggregate ON event_outbox(aggregate_type, aggregate_id);
CREATE INDEX idx_outbox_event_type ON event_outbox(event_type);
CREATE INDEX idx_outbox_topic ON event_outbox(topic);

-- Partition by month for scalability (optional)
-- CREATE TABLE event_outbox_202401 PARTITION OF event_outbox
--     FOR VALUES FROM ('2024-01-01') TO ('2024-02-01');

-- Cleanup job for old published events (keep 30 days)
CREATE OR REPLACE FUNCTION cleanup_published_outbox()
RETURNS void AS $$
BEGIN
    DELETE FROM event_outbox
    WHERE status = 'published'
    AND published_at < NOW() - INTERVAL '30 days';
END;
$$ LANGUAGE plpgsql;
```

## Definition of Done

- [ ] Outbox entity and schema
- [ ] Event publisher service
- [ ] Outbox processor with Kafka
- [ ] Dead letter handling
- [ ] Event replay capability
- [ ] Consumer example
- [ ] Monitoring dashboards

## Dependencies

- **US-08-01**: Trade Entity
- **US-16-02**: Kafka Infrastructure

## Test Cases

```typescript
describe('TradeEventPublisher', () => {
  it('should write event to outbox within transaction', async () => {
    await dataSource.transaction(async (manager) => {
      const trade = createTrade();
      await manager.save(trade);

      await eventPublisher.publishWithTransaction(
        manager,
        TradeEventType.TRADE_CREATED,
        eventPublisher.buildCreatedEvent(trade),
      );
    });

    const outboxEvents = await outboxRepo.find();
    expect(outboxEvents.length).toBe(1);
    expect(outboxEvents[0].status).toBe(OutboxStatus.PENDING);
  });

  it('should process pending events', async () => {
    await insertOutboxEvent({ status: OutboxStatus.PENDING });

    await processorService.processPendingEvents();

    const event = await outboxRepo.findOne();
    expect(event.status).toBe(OutboxStatus.PUBLISHED);
  });

  it('should move to dead letter after max retries', async () => {
    const event = await insertOutboxEvent({
      status: OutboxStatus.PENDING,
      retryCount: 4,
      maxRetries: 5,
    });

    // Simulate failure
    await processorService.handlePublishFailure(event, new Error('Kafka unavailable'));

    const updated = await outboxRepo.findOne({ where: { id: event.id } });
    expect(updated.status).toBe(OutboxStatus.DEAD_LETTER);
  });
});
```
