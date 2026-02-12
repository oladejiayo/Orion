# User Story: US-05-06 - Idempotent Consumer Pattern

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-05-06 |
| **Epic** | Epic 05 - Event Bus Infrastructure |
| **Title** | Idempotent Consumer Pattern |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |

## User Story

**As a** backend developer  
**I want** event handlers to be idempotent  
**So that** duplicate event delivery doesn't cause data corruption

## Description

Implement idempotent consumer pattern with deduplication tracking to ensure exactly-once processing semantics even when Kafka delivers messages multiple times.

## Acceptance Criteria

- [ ] Idempotency key extraction configurable
- [ ] Processed event tracking with TTL
- [ ] Duplicate detection before processing
- [ ] Transactional processing with idempotency check
- [ ] Metrics for duplicate detection
- [ ] Cleanup of expired records

## Technical Details

### Database Schema

```sql
-- migrations/20240116_create_processed_events.sql
CREATE TABLE processed_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    
    -- Event metadata
    event_id UUID NOT NULL,
    event_timestamp TIMESTAMPTZ NOT NULL,
    
    -- Processing result
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    result_status VARCHAR(20) NOT NULL DEFAULT 'success',
    result_payload JSONB,
    
    -- Constraints
    CONSTRAINT uq_processed_event_key 
        UNIQUE (idempotency_key, consumer_group, tenant_id)
);

-- Index for cleanup job
CREATE INDEX idx_processed_events_timestamp 
    ON processed_events(processed_at);

-- Index for lookups
CREATE INDEX idx_processed_events_lookup 
    ON processed_events(idempotency_key, consumer_group, tenant_id);

-- Partition by month for easier cleanup
-- (Alternative to TTL-based cleanup)
```

### Idempotency Key Strategies

```typescript
// libs/event-model/src/idempotency/key-strategies.ts
import { OrionEvent } from '../envelope';

export type IdempotencyKeyExtractor<T = unknown> = (event: OrionEvent<string, T>) => string;

/**
 * Default: Use event ID as idempotency key
 */
export const eventIdStrategy: IdempotencyKeyExtractor = (event) => event.eventId;

/**
 * Use aggregate ID + version for entity events
 */
export const aggregateVersionStrategy: IdempotencyKeyExtractor = (event) => {
  const payload = event.payload as any;
  return `${event.aggregateType}:${event.aggregateId}:${payload.version || 0}`;
};

/**
 * Use custom field from payload
 */
export function payloadFieldStrategy(fieldPath: string): IdempotencyKeyExtractor {
  return (event) => {
    const parts = fieldPath.split('.');
    let value: any = event.payload;
    
    for (const part of parts) {
      value = value?.[part];
    }
    
    return String(value);
  };
}

/**
 * Composite key from multiple fields
 */
export function compositeKeyStrategy(...fields: string[]): IdempotencyKeyExtractor {
  return (event) => {
    const payload = event.payload as any;
    return fields
      .map(field => String(payload[field] || event[field as keyof typeof event] || ''))
      .join(':');
  };
}
```

### Idempotency Store

```typescript
// libs/event-model/src/idempotency/idempotency-store.ts
import { Injectable } from '@nestjs/common';
import { Pool } from 'pg';
import { logger, metrics } from '@orion/observability';

export interface ProcessedEventRecord {
  idempotencyKey: string;
  eventType: string;
  consumerGroup: string;
  tenantId: string;
  eventId: string;
  eventTimestamp: Date;
  resultStatus: 'success' | 'failed';
  resultPayload?: unknown;
}

@Injectable()
export class IdempotencyStore {
  constructor(private readonly pool: Pool) {}

  /**
   * Check if event was already processed
   */
  async wasProcessed(
    idempotencyKey: string,
    consumerGroup: string,
    tenantId: string,
  ): Promise<boolean> {
    const result = await this.pool.query(
      `SELECT 1 FROM processed_events 
       WHERE idempotency_key = $1 
         AND consumer_group = $2 
         AND tenant_id = $3`,
      [idempotencyKey, consumerGroup, tenantId],
    );

    return result.rowCount > 0;
  }

  /**
   * Get previous processing result
   */
  async getProcessingResult(
    idempotencyKey: string,
    consumerGroup: string,
    tenantId: string,
  ): Promise<ProcessedEventRecord | null> {
    const result = await this.pool.query(
      `SELECT * FROM processed_events 
       WHERE idempotency_key = $1 
         AND consumer_group = $2 
         AND tenant_id = $3`,
      [idempotencyKey, consumerGroup, tenantId],
    );

    if (result.rows.length === 0) return null;

    const row = result.rows[0];
    return {
      idempotencyKey: row.idempotency_key,
      eventType: row.event_type,
      consumerGroup: row.consumer_group,
      tenantId: row.tenant_id,
      eventId: row.event_id,
      eventTimestamp: row.event_timestamp,
      resultStatus: row.result_status,
      resultPayload: row.result_payload,
    };
  }

  /**
   * Mark event as processed (within transaction)
   */
  async markProcessed(
    client: any, // Transaction client
    record: ProcessedEventRecord,
  ): Promise<void> {
    await client.query(
      `INSERT INTO processed_events (
        idempotency_key, event_type, consumer_group, tenant_id,
        event_id, event_timestamp, result_status, result_payload
      ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
      ON CONFLICT (idempotency_key, consumer_group, tenant_id) DO NOTHING`,
      [
        record.idempotencyKey,
        record.eventType,
        record.consumerGroup,
        record.tenantId,
        record.eventId,
        record.eventTimestamp,
        record.resultStatus,
        record.resultPayload ? JSON.stringify(record.resultPayload) : null,
      ],
    );
  }

  /**
   * Cleanup old records
   */
  async cleanup(olderThanDays: number = 7): Promise<number> {
    const result = await this.pool.query(
      `DELETE FROM processed_events 
       WHERE processed_at < NOW() - INTERVAL '${olderThanDays} days'`,
    );

    logger.info('Cleaned up processed events', { 
      deletedCount: result.rowCount,
      olderThanDays,
    });

    return result.rowCount;
  }
}
```

### Idempotent Consumer Wrapper

```typescript
// libs/event-model/src/idempotency/idempotent-consumer.ts
import { Pool, PoolClient } from 'pg';
import { logger, metrics } from '@orion/observability';
import { getCurrentTenant } from '@orion/security';
import { OrionEvent } from '../envelope';
import { IdempotencyStore, ProcessedEventRecord } from './idempotency-store';
import { IdempotencyKeyExtractor, eventIdStrategy } from './key-strategies';

export interface IdempotentHandlerOptions<T> {
  consumerGroup: string;
  keyExtractor?: IdempotencyKeyExtractor<T>;
  skipIdempotencyCheck?: boolean;
}

export class IdempotentConsumer<T = unknown> {
  private readonly keyExtractor: IdempotencyKeyExtractor<T>;

  constructor(
    private readonly pool: Pool,
    private readonly store: IdempotencyStore,
    private readonly options: IdempotentHandlerOptions<T>,
  ) {
    this.keyExtractor = options.keyExtractor || eventIdStrategy;
  }

  /**
   * Process event with idempotency guarantee
   */
  async process(
    event: OrionEvent<string, T>,
    handler: (event: OrionEvent<string, T>, client: PoolClient) => Promise<unknown>,
  ): Promise<{ processed: boolean; result?: unknown }> {
    const tenant = getCurrentTenant();
    const tenantId = tenant?.tenantId || event.metadata?.tenantId;
    
    if (!tenantId) {
      throw new Error('Tenant ID required for idempotent processing');
    }

    const idempotencyKey = this.keyExtractor(event);

    // Fast path: check if already processed
    if (!this.options.skipIdempotencyCheck) {
      const existingResult = await this.store.getProcessingResult(
        idempotencyKey,
        this.options.consumerGroup,
        tenantId,
      );

      if (existingResult) {
        metrics.increment('idempotent.duplicate_detected', {
          eventType: event.eventType,
          consumerGroup: this.options.consumerGroup,
        });

        logger.debug('Duplicate event detected, skipping', {
          eventId: event.eventId,
          idempotencyKey,
        });

        return { 
          processed: false, 
          result: existingResult.resultPayload,
        };
      }
    }

    // Transactional processing with idempotency record
    const client = await this.pool.connect();
    
    try {
      await client.query('BEGIN');

      // Double-check with row lock
      const lockResult = await client.query(
        `SELECT 1 FROM processed_events 
         WHERE idempotency_key = $1 
           AND consumer_group = $2 
           AND tenant_id = $3
         FOR UPDATE SKIP LOCKED`,
        [idempotencyKey, this.options.consumerGroup, tenantId],
      );

      if (lockResult.rowCount > 0) {
        await client.query('ROLLBACK');
        metrics.increment('idempotent.duplicate_detected', {
          eventType: event.eventType,
        });
        return { processed: false };
      }

      // Process the event
      const result = await handler(event, client);

      // Mark as processed
      const record: ProcessedEventRecord = {
        idempotencyKey,
        eventType: event.eventType,
        consumerGroup: this.options.consumerGroup,
        tenantId,
        eventId: event.eventId,
        eventTimestamp: new Date(event.timestamp),
        resultStatus: 'success',
        resultPayload: result,
      };

      await this.store.markProcessed(client, record);

      await client.query('COMMIT');

      metrics.increment('idempotent.processed', {
        eventType: event.eventType,
        consumerGroup: this.options.consumerGroup,
      });

      return { processed: true, result };
    } catch (error) {
      await client.query('ROLLBACK');
      
      metrics.increment('idempotent.processing_failed', {
        eventType: event.eventType,
      });

      throw error;
    } finally {
      client.release();
    }
  }
}
```

### Decorator for Idempotent Handlers

```typescript
// libs/event-model/src/idempotency/decorators.ts
import 'reflect-metadata';
import { IdempotencyKeyExtractor } from './key-strategies';

const IDEMPOTENT_METADATA = 'IDEMPOTENT_METADATA';

export interface IdempotentOptions {
  keyExtractor?: IdempotencyKeyExtractor;
  skipCheck?: boolean;
}

/**
 * Decorator to make an event handler idempotent
 */
export function Idempotent(options: IdempotentOptions = {}): MethodDecorator {
  return (target, propertyKey, descriptor) => {
    Reflect.defineMetadata(IDEMPOTENT_METADATA, options, target, propertyKey);
    return descriptor;
  };
}

/**
 * Get idempotent options for a method
 */
export function getIdempotentOptions(
  target: any,
  propertyKey: string | symbol,
): IdempotentOptions | undefined {
  return Reflect.getMetadata(IDEMPOTENT_METADATA, target, propertyKey);
}
```

### Usage Example

```typescript
// services/trade-service/src/handlers/rfq-executed.handler.ts
import { Injectable } from '@nestjs/common';
import { EventHandlerBase, OnEvent, Idempotent, compositeKeyStrategy } from '@orion/event-model';
import { IdempotentConsumer } from '@orion/event-model';
import { TradeService } from '../trade.service';

interface RfqExecutedPayload {
  rfqId: string;
  quoteId: string;
  executedPrice: number;
  executedQuantity: number;
}

@Injectable()
export class RfqExecutedHandler extends EventHandlerBase {
  private readonly idempotentConsumer: IdempotentConsumer<RfqExecutedPayload>;

  constructor(
    consumer: EventConsumerService,
    pool: Pool,
    store: IdempotencyStore,
    private readonly tradeService: TradeService,
  ) {
    super(consumer);
    
    this.idempotentConsumer = new IdempotentConsumer(pool, store, {
      consumerGroup: 'trade-service',
      // Use rfqId + quoteId as idempotency key
      keyExtractor: compositeKeyStrategy('rfqId', 'quoteId'),
    });
  }

  @OnEvent('rfq.executed')
  @Idempotent({ keyExtractor: compositeKeyStrategy('rfqId', 'quoteId') })
  async handle(event: OrionEvent<'rfq.executed', RfqExecutedPayload>) {
    const { processed, result } = await this.idempotentConsumer.process(
      event,
      async (evt, client) => {
        // This code runs at most once per rfqId+quoteId combination
        return await this.tradeService.createTradeFromExecution(
          evt.payload,
          client,
        );
      },
    );

    if (!processed) {
      logger.info('Duplicate execution event, trade already created', {
        rfqId: event.payload.rfqId,
      });
    }

    return result;
  }
}
```

### Cleanup Job

```typescript
// services/platform-service/src/jobs/idempotency-cleanup.job.ts
import { Injectable } from '@nestjs/common';
import { Cron, CronExpression } from '@nestjs/schedule';
import { IdempotencyStore } from '@orion/event-model';
import { logger, metrics } from '@orion/observability';

@Injectable()
export class IdempotencyCleanupJob {
  constructor(private readonly store: IdempotencyStore) {}

  @Cron(CronExpression.EVERY_DAY_AT_2AM)
  async cleanup() {
    const startTime = Date.now();
    
    try {
      const deleted = await this.store.cleanup(7); // 7 days retention
      
      metrics.gauge('idempotency.cleanup_count', deleted);
      metrics.timing('idempotency.cleanup_duration', Date.now() - startTime);
      
      logger.info('Idempotency cleanup completed', {
        deletedRecords: deleted,
        duration: Date.now() - startTime,
      });
    } catch (error) {
      logger.error('Idempotency cleanup failed', { error });
      metrics.increment('idempotency.cleanup_failed');
    }
  }
}
```

### Redis-Based Idempotency (Alternative)

```typescript
// libs/event-model/src/idempotency/redis-idempotency-store.ts
import { Injectable } from '@nestjs/common';
import Redis from 'ioredis';
import { logger } from '@orion/observability';

@Injectable()
export class RedisIdempotencyStore {
  private readonly keyPrefix = 'idempotent:';
  private readonly defaultTtl = 86400 * 7; // 7 days in seconds

  constructor(private readonly redis: Redis) {}

  /**
   * Check and set idempotency key atomically
   */
  async checkAndSet(
    idempotencyKey: string,
    consumerGroup: string,
    tenantId: string,
    ttlSeconds: number = this.defaultTtl,
  ): Promise<{ isNew: boolean; existingValue?: string }> {
    const key = `${this.keyPrefix}${consumerGroup}:${tenantId}:${idempotencyKey}`;
    
    // Use SET NX to atomically check and set
    const result = await this.redis.set(
      key,
      new Date().toISOString(),
      'EX',
      ttlSeconds,
      'NX',
    );

    if (result === 'OK') {
      return { isNew: true };
    }

    // Key exists, get the value
    const existingValue = await this.redis.get(key);
    return { isNew: false, existingValue: existingValue || undefined };
  }

  /**
   * Store processing result
   */
  async storeResult(
    idempotencyKey: string,
    consumerGroup: string,
    tenantId: string,
    result: unknown,
    ttlSeconds: number = this.defaultTtl,
  ): Promise<void> {
    const key = `${this.keyPrefix}${consumerGroup}:${tenantId}:${idempotencyKey}:result`;
    
    await this.redis.set(
      key,
      JSON.stringify(result),
      'EX',
      ttlSeconds,
    );
  }

  /**
   * Get cached processing result
   */
  async getResult(
    idempotencyKey: string,
    consumerGroup: string,
    tenantId: string,
  ): Promise<unknown | null> {
    const key = `${this.keyPrefix}${consumerGroup}:${tenantId}:${idempotencyKey}:result`;
    const value = await this.redis.get(key);
    return value ? JSON.parse(value) : null;
  }
}
```

## Definition of Done

- [ ] Database schema created
- [ ] Key extraction strategies implemented
- [ ] Idempotent consumer wrapper works
- [ ] Transactional processing verified
- [ ] Decorator pattern functional
- [ ] Cleanup job scheduled
- [ ] Redis alternative available
- [ ] Tests pass

## Dependencies

- **US-05-04**: Event Consumer Library
- **US-03-02**: Database Row-Level Security

## Test Cases

```typescript
describe('IdempotentConsumer', () => {
  it('should process event only once', async () => {
    const handlerSpy = jest.fn().mockResolvedValue({ tradeId: '123' });

    // First processing
    const result1 = await idempotentConsumer.process(event, handlerSpy);
    expect(result1.processed).toBe(true);
    expect(handlerSpy).toHaveBeenCalledTimes(1);

    // Duplicate attempt
    const result2 = await idempotentConsumer.process(event, handlerSpy);
    expect(result2.processed).toBe(false);
    expect(handlerSpy).toHaveBeenCalledTimes(1); // Not called again
  });

  it('should return cached result for duplicates', async () => {
    const expectedResult = { tradeId: '123' };
    const handlerSpy = jest.fn().mockResolvedValue(expectedResult);

    await idempotentConsumer.process(event, handlerSpy);
    const result = await idempotentConsumer.process(event, handlerSpy);

    expect(result.result).toEqual(expectedResult);
  });

  it('should use custom key extractor', async () => {
    const consumer = new IdempotentConsumer(pool, store, {
      consumerGroup: 'test',
      keyExtractor: compositeKeyStrategy('rfqId', 'quoteId'),
    });

    const event1 = createEvent({ rfqId: '1', quoteId: 'a' });
    const event2 = createEvent({ rfqId: '1', quoteId: 'b' }); // Different key

    const handler = jest.fn().mockResolvedValue({});

    await consumer.process(event1, handler);
    await consumer.process(event2, handler);

    expect(handler).toHaveBeenCalledTimes(2); // Both processed
  });

  it('should handle concurrent processing correctly', async () => {
    const handler = jest.fn().mockImplementation(async () => {
      await sleep(100); // Simulate slow processing
      return { id: '123' };
    });

    // Start two concurrent processes for same event
    const [result1, result2] = await Promise.all([
      idempotentConsumer.process(event, handler),
      idempotentConsumer.process(event, handler),
    ]);

    // Only one should actually process
    expect(handler).toHaveBeenCalledTimes(1);
    expect(
      (result1.processed && !result2.processed) ||
      (!result1.processed && result2.processed)
    ).toBe(true);
  });
});
```
