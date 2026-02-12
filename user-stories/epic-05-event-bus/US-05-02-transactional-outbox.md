# User Story: US-05-02 - Transactional Outbox Pattern

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-05-02 |
| **Epic** | Epic 05 - Event Bus Infrastructure |
| **Title** | Transactional Outbox Pattern |
| **Priority** | P0 - Critical |
| **Story Points** | 8 |
| **PRD Reference** | NFR-EVT-02 |

## User Story

**As a** platform architect  
**I want** database changes and events published atomically  
**So that** we never have inconsistent state between database and event bus

## Description

Implement the transactional outbox pattern where events are first written to an outbox table within the same database transaction as the domain changes, then asynchronously relayed to Kafka. This guarantees that if a database transaction commits, the event will eventually be published.

## Acceptance Criteria

- [ ] Outbox table stores pending events
- [ ] Events written in same transaction as domain data
- [ ] Relay service polls and publishes events
- [ ] Published events marked as processed
- [ ] Retry logic for failed publishes
- [ ] Ordering preserved within aggregate

## Technical Details

### Outbox Table Schema

```sql
-- Migration: 020_create_outbox.sql
CREATE TABLE outbox (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL,
  aggregate_type VARCHAR(100) NOT NULL,
  aggregate_id UUID NOT NULL,
  event_type VARCHAR(100) NOT NULL,
  payload JSONB NOT NULL,
  metadata JSONB NOT NULL DEFAULT '{}',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  processed_at TIMESTAMPTZ,
  retry_count INT NOT NULL DEFAULT 0,
  last_error TEXT,
  
  -- Ordering
  sequence_number BIGSERIAL
);

-- Index for relay polling (unprocessed events)
CREATE INDEX idx_outbox_pending ON outbox(created_at) 
  WHERE processed_at IS NULL;

-- Index for aggregate ordering
CREATE INDEX idx_outbox_aggregate ON outbox(aggregate_type, aggregate_id, sequence_number);

-- Cleanup old processed events (partition by month for easy cleanup)
CREATE INDEX idx_outbox_cleanup ON outbox(processed_at) 
  WHERE processed_at IS NOT NULL;
```

### Outbox Writer

```typescript
// libs/event-model/src/outbox/outbox.service.ts
import { Injectable } from '@nestjs/common';
import { PoolClient } from 'pg';
import { OrionEvent, EventMetadata } from '../envelope';

@Injectable()
export class OutboxService {
  /**
   * Write event to outbox within existing transaction
   * MUST be called within an active database transaction
   */
  async writeToOutbox<T>(
    client: PoolClient,
    event: {
      eventType: string;
      aggregateType: string;
      aggregateId: string;
      payload: T;
      metadata: EventMetadata;
    },
  ): Promise<string> {
    const result = await client.query(`
      INSERT INTO outbox (
        tenant_id, aggregate_type, aggregate_id, event_type, payload, metadata
      ) VALUES ($1, $2, $3, $4, $5, $6)
      RETURNING id
    `, [
      event.metadata.tenantId,
      event.aggregateType,
      event.aggregateId,
      event.eventType,
      JSON.stringify(event.payload),
      JSON.stringify(event.metadata),
    ]);

    return result.rows[0].id;
  }

  /**
   * Write multiple events to outbox in order
   */
  async writeMultipleToOutbox(
    client: PoolClient,
    events: Array<{
      eventType: string;
      aggregateType: string;
      aggregateId: string;
      payload: unknown;
      metadata: EventMetadata;
    }>,
  ): Promise<string[]> {
    const ids: string[] = [];
    
    for (const event of events) {
      const id = await this.writeToOutbox(client, event);
      ids.push(id);
    }
    
    return ids;
  }
}
```

### Transaction Helper with Outbox

```typescript
// libs/database/src/transaction-with-events.ts
import { TenantAwarePool } from './tenant-pool';
import { OutboxService } from '@orion/event-model';
import { PoolClient } from 'pg';

export interface TransactionContext {
  client: PoolClient;
  publishEvent: <T>(event: {
    eventType: string;
    aggregateType: string;
    aggregateId: string;
    payload: T;
  }) => Promise<void>;
}

export async function transactionWithEvents<T>(
  pool: TenantAwarePool,
  outbox: OutboxService,
  fn: (ctx: TransactionContext) => Promise<T>,
): Promise<T> {
  const client = await pool.getClient();
  const pendingEvents: Array<{
    eventType: string;
    aggregateType: string;
    aggregateId: string;
    payload: unknown;
    metadata: any;
  }> = [];

  const context: TransactionContext = {
    client,
    publishEvent: async (event) => {
      const tenant = TenantAwarePool.getCurrentTenant()!;
      pendingEvents.push({
        ...event,
        metadata: {
          tenantId: tenant.tenantId,
          userId: tenant.userId,
          correlationId: tenant.correlationId,
          timestamp: new Date().toISOString(),
        },
      });
    },
  };

  try {
    await client.query('BEGIN');
    
    // Execute business logic
    const result = await fn(context);
    
    // Write all events to outbox within same transaction
    for (const event of pendingEvents) {
      await outbox.writeToOutbox(client, event);
    }
    
    await client.query('COMMIT');
    return result;
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally {
    client.release();
  }
}
```

### Outbox Relay Service

```typescript
// services/outbox-relay/src/relay.service.ts
import { Injectable, OnModuleInit, OnModuleDestroy } from '@nestjs/common';
import { Pool } from 'pg';
import { Kafka, Producer } from 'kafkajs';
import { logger, metrics } from '@orion/observability';

const BATCH_SIZE = 100;
const POLL_INTERVAL_MS = 100;
const MAX_RETRIES = 5;

@Injectable()
export class OutboxRelayService implements OnModuleInit, OnModuleDestroy {
  private producer: Producer;
  private running = false;
  private pollTimeout?: NodeJS.Timeout;

  constructor(
    private readonly pool: Pool,
    private readonly kafka: Kafka,
  ) {
    this.producer = kafka.producer({
      idempotent: true,
      maxInFlightRequests: 5,
    });
  }

  async onModuleInit() {
    await this.producer.connect();
    this.running = true;
    this.poll();
    logger.info('Outbox relay started');
  }

  async onModuleDestroy() {
    this.running = false;
    if (this.pollTimeout) {
      clearTimeout(this.pollTimeout);
    }
    await this.producer.disconnect();
    logger.info('Outbox relay stopped');
  }

  private async poll() {
    if (!this.running) return;

    try {
      await this.processOutbox();
    } catch (error) {
      logger.error('Outbox relay error', { error });
      metrics.increment('outbox.relay.error');
    }

    this.pollTimeout = setTimeout(() => this.poll(), POLL_INTERVAL_MS);
  }

  private async processOutbox() {
    const client = await this.pool.connect();
    
    try {
      // Lock and fetch batch of pending events
      await client.query('BEGIN');
      
      const result = await client.query(`
        SELECT id, tenant_id, aggregate_type, aggregate_id, 
               event_type, payload, metadata, retry_count
        FROM outbox
        WHERE processed_at IS NULL
          AND retry_count < $1
        ORDER BY sequence_number
        LIMIT $2
        FOR UPDATE SKIP LOCKED
      `, [MAX_RETRIES, BATCH_SIZE]);

      if (result.rows.length === 0) {
        await client.query('COMMIT');
        return;
      }

      const events = result.rows;
      const processedIds: string[] = [];
      const failedEvents: Array<{ id: string; error: string }> = [];

      // Group events by topic for batching
      const messagesByTopic = new Map<string, Array<{
        key: string;
        value: string;
        headers: Record<string, string>;
      }>>();

      for (const event of events) {
        const topic = this.getTopicForEvent(event.event_type);
        
        if (!messagesByTopic.has(topic)) {
          messagesByTopic.set(topic, []);
        }

        const envelope = {
          eventId: event.id,
          eventType: event.event_type,
          aggregateType: event.aggregate_type,
          aggregateId: event.aggregate_id,
          payload: event.payload,
          metadata: {
            ...event.metadata,
            tenantId: event.tenant_id,
          },
          timestamp: new Date().toISOString(),
        };

        messagesByTopic.get(topic)!.push({
          key: event.aggregate_id,
          value: JSON.stringify(envelope),
          headers: {
            'x-tenant-id': event.tenant_id,
            'x-event-type': event.event_type,
            'x-correlation-id': event.metadata.correlationId || '',
          },
        });
      }

      // Send to Kafka
      for (const [topic, messages] of messagesByTopic) {
        try {
          await this.producer.send({
            topic,
            messages,
            acks: -1, // Wait for all replicas
          });

          // Mark as processed
          const ids = events
            .filter(e => this.getTopicForEvent(e.event_type) === topic)
            .map(e => e.id);
          processedIds.push(...ids);
          
          metrics.increment('outbox.relay.published', { topic }, messages.length);
        } catch (error) {
          // Mark for retry
          const ids = events
            .filter(e => this.getTopicForEvent(e.event_type) === topic)
            .map(e => e.id);
          ids.forEach(id => failedEvents.push({ id, error: (error as Error).message }));
          
          logger.error('Failed to publish to topic', { topic, error });
        }
      }

      // Update processed events
      if (processedIds.length > 0) {
        await client.query(`
          UPDATE outbox
          SET processed_at = NOW()
          WHERE id = ANY($1)
        `, [processedIds]);
      }

      // Update failed events
      for (const { id, error } of failedEvents) {
        await client.query(`
          UPDATE outbox
          SET retry_count = retry_count + 1, last_error = $2
          WHERE id = $1
        `, [id, error]);
      }

      await client.query('COMMIT');
      
      logger.info('Outbox batch processed', {
        total: events.length,
        published: processedIds.length,
        failed: failedEvents.length,
      });
    } catch (error) {
      await client.query('ROLLBACK');
      throw error;
    } finally {
      client.release();
    }
  }

  private getTopicForEvent(eventType: string): string {
    // Map event types to topics
    const mapping: Record<string, string> = {
      'rfq.created': 'orion.rfq.created',
      'rfq.quoted': 'orion.rfq.quoted',
      'rfq.executed': 'orion.rfq.executed',
      'trade.executed': 'orion.trade.executed',
      'trade.confirmed': 'orion.trade.confirmed',
      'order.created': 'orion.order.created',
      'order.filled': 'orion.order.filled',
      'tenant.created': 'orion.tenant.events',
      'instrument.created': 'orion.instrument.events',
    };

    return mapping[eventType] || 'orion.events.default';
  }
}
```

### Usage Example

```typescript
// services/rfq-service/src/application/rfq.service.ts
import { Injectable } from '@nestjs/common';
import { transactionWithEvents } from '@orion/database';

@Injectable()
export class RfqService {
  async createRfq(dto: CreateRfqDto): Promise<Rfq> {
    return transactionWithEvents(this.pool, this.outbox, async (ctx) => {
      // Insert RFQ into database
      const result = await ctx.client.query(`
        INSERT INTO rfqs (tenant_id, instrument_id, side, quantity, status)
        VALUES ($1, $2, $3, $4, 'pending')
        RETURNING *
      `, [dto.tenantId, dto.instrumentId, dto.side, dto.quantity]);

      const rfq = result.rows[0];

      // Queue event for publication (written to outbox in same transaction)
      await ctx.publishEvent({
        eventType: 'rfq.created',
        aggregateType: 'rfq',
        aggregateId: rfq.id,
        payload: {
          rfqId: rfq.id,
          instrumentId: rfq.instrument_id,
          side: rfq.side,
          quantity: rfq.quantity,
        },
      });

      return rfq;
    });
  }
}
```

## Definition of Done

- [ ] Outbox table created
- [ ] Outbox writer service works
- [ ] Transaction helper tested
- [ ] Relay service running
- [ ] Retry logic handles failures
- [ ] Ordering preserved
- [ ] Metrics exposed

## Dependencies

- **US-05-01**: Kafka Topic Configuration
- **US-01-10**: Database Migration Framework

## Test Cases

```typescript
describe('Transactional Outbox', () => {
  it('should write event in same transaction as data', async () => {
    const rfq = await rfqService.createRfq({
      instrumentId: 'inst-1',
      side: 'buy',
      quantity: 100,
    });

    // Check RFQ exists
    const savedRfq = await pool.query('SELECT * FROM rfqs WHERE id = $1', [rfq.id]);
    expect(savedRfq.rows.length).toBe(1);

    // Check outbox event exists
    const outboxEvent = await pool.query(
      'SELECT * FROM outbox WHERE aggregate_id = $1',
      [rfq.id]
    );
    expect(outboxEvent.rows.length).toBe(1);
    expect(outboxEvent.rows[0].event_type).toBe('rfq.created');
  });

  it('should rollback both data and event on error', async () => {
    await expect(rfqService.createRfq({
      instrumentId: null, // Will cause constraint violation
      side: 'buy',
      quantity: 100,
    })).rejects.toThrow();

    // Neither RFQ nor event should exist
    const rfqs = await pool.query('SELECT COUNT(*) FROM rfqs');
    const events = await pool.query('SELECT COUNT(*) FROM outbox');
    
    expect(parseInt(rfqs.rows[0].count)).toBe(0);
    expect(parseInt(events.rows[0].count)).toBe(0);
  });

  it('should relay events to Kafka', async () => {
    const rfq = await rfqService.createRfq({...});

    // Wait for relay to process
    await new Promise(resolve => setTimeout(resolve, 500));

    // Check event was processed
    const outboxEvent = await pool.query(
      'SELECT processed_at FROM outbox WHERE aggregate_id = $1',
      [rfq.id]
    );
    expect(outboxEvent.rows[0].processed_at).not.toBeNull();
  });
});
```

## Notes

- Relay service should run as separate process for scaling
- Consider CDC (Change Data Capture) for higher throughput
- Clean up processed events periodically
- Monitor relay lag as key metric
