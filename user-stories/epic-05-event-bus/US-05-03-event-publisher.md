# User Story: US-05-03 - Event Publisher Library

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-05-03 |
| **Epic** | Epic 05 - Event Bus Infrastructure |
| **Title** | Event Publisher Library |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |

## User Story

**As a** backend developer  
**I want** a simple API to publish events  
**So that** I can focus on business logic without Kafka complexity

## Description

Create a shared library that provides a clean abstraction for publishing events. The library handles serialization, tenant context injection, correlation ID propagation, and integrates with the outbox pattern.

## Acceptance Criteria

- [ ] Simple publish API: `eventBus.publish(event)`
- [ ] Automatic tenant context injection
- [ ] Correlation ID propagation
- [ ] Type-safe event definitions
- [ ] Outbox integration for transactional safety
- [ ] Direct publish option for non-transactional cases

## Technical Details

### Event Bus Interface

```typescript
// libs/event-model/src/event-bus/event-bus.interface.ts
import { OrionEvent, EventMetadata } from '../envelope';

export interface PublishOptions {
  /**
   * Use outbox pattern for transactional safety (default: true)
   */
  useOutbox?: boolean;
  
  /**
   * Topic override (default: derived from event type)
   */
  topic?: string;
  
  /**
   * Partition key (default: aggregateId)
   */
  partitionKey?: string;
}

export interface EventBus {
  /**
   * Publish a single event
   */
  publish<T>(event: OrionEvent<string, T>, options?: PublishOptions): Promise<void>;

  /**
   * Publish multiple events atomically
   */
  publishAll<T>(events: OrionEvent<string, T>[], options?: PublishOptions): Promise<void>;

  /**
   * Subscribe to events (for consumers)
   */
  subscribe<T>(
    eventType: string | string[],
    handler: (event: OrionEvent<string, T>) => Promise<void>,
  ): Promise<void>;
}
```

### Event Bus Implementation

```typescript
// libs/event-model/src/event-bus/event-bus.service.ts
import { Injectable, OnModuleInit, OnModuleDestroy } from '@nestjs/common';
import { Kafka, Producer } from 'kafkajs';
import { OutboxService } from '../outbox/outbox.service';
import { TenantAwarePool, getCurrentTenant } from '@orion/security';
import { logger, metrics } from '@orion/observability';
import { EventBus, PublishOptions } from './event-bus.interface';
import { OrionEvent } from '../envelope';

@Injectable()
export class EventBusService implements EventBus, OnModuleInit, OnModuleDestroy {
  private producer: Producer;

  constructor(
    private readonly kafka: Kafka,
    private readonly outbox: OutboxService,
    private readonly pool: TenantAwarePool,
  ) {
    this.producer = kafka.producer({
      idempotent: true,
      transactionalId: `orion-producer-${process.pid}`,
    });
  }

  async onModuleInit() {
    await this.producer.connect();
    logger.info('Event bus producer connected');
  }

  async onModuleDestroy() {
    await this.producer.disconnect();
    logger.info('Event bus producer disconnected');
  }

  async publish<T>(
    event: Omit<OrionEvent<string, T>, 'eventId' | 'timestamp'>,
    options: PublishOptions = {},
  ): Promise<void> {
    const { useOutbox = true, topic, partitionKey } = options;
    
    // Enrich with context
    const enrichedEvent = this.enrichEvent(event);

    if (useOutbox) {
      // Write to outbox within current transaction context
      await this.publishViaOutbox(enrichedEvent);
    } else {
      // Publish directly to Kafka (use for non-critical events)
      await this.publishDirect(enrichedEvent, topic, partitionKey);
    }

    metrics.increment('events.published', {
      eventType: event.eventType,
      method: useOutbox ? 'outbox' : 'direct',
    });
  }

  async publishAll<T>(
    events: Array<Omit<OrionEvent<string, T>, 'eventId' | 'timestamp'>>,
    options: PublishOptions = {},
  ): Promise<void> {
    for (const event of events) {
      await this.publish(event, options);
    }
  }

  private enrichEvent<T>(event: Omit<OrionEvent<string, T>, 'eventId' | 'timestamp'>): OrionEvent<string, T> {
    const tenant = getCurrentTenant();
    
    return {
      ...event,
      eventId: crypto.randomUUID(),
      timestamp: new Date().toISOString(),
      metadata: {
        ...event.metadata,
        tenantId: tenant?.tenantId || event.metadata?.tenantId,
        userId: tenant?.userId || event.metadata?.userId,
        correlationId: tenant?.correlationId || event.metadata?.correlationId || crypto.randomUUID(),
        source: process.env.SERVICE_NAME || 'unknown',
      },
    };
  }

  private async publishViaOutbox<T>(event: OrionEvent<string, T>): Promise<void> {
    const client = await this.pool.getClient();
    
    try {
      await this.outbox.writeToOutbox(client, {
        eventType: event.eventType,
        aggregateType: event.aggregateType,
        aggregateId: event.aggregateId,
        payload: event.payload,
        metadata: event.metadata,
      });
    } finally {
      client.release();
    }
  }

  private async publishDirect<T>(
    event: OrionEvent<string, T>,
    topicOverride?: string,
    partitionKey?: string,
  ): Promise<void> {
    const topic = topicOverride || this.getTopicForEvent(event.eventType);
    const key = partitionKey || event.aggregateId;

    await this.producer.send({
      topic,
      messages: [{
        key,
        value: JSON.stringify(event),
        headers: {
          'x-event-id': event.eventId,
          'x-event-type': event.eventType,
          'x-tenant-id': event.metadata?.tenantId || '',
          'x-correlation-id': event.metadata?.correlationId || '',
        },
      }],
    });
  }

  private getTopicForEvent(eventType: string): string {
    const [domain, action] = eventType.split('.');
    return `orion.${domain}.${action}`;
  }

  // Subscription methods moved to consumer library
  async subscribe(): Promise<void> {
    throw new Error('Use EventConsumer for subscriptions');
  }
}
```

### Type-Safe Event Builders

```typescript
// libs/event-model/src/events/builders.ts
import { OrionEvent, EventMetadata } from '../envelope';

type EventBuilder<T extends string, P> = {
  type: T;
  build: (payload: P, aggregateId: string, metadata?: Partial<EventMetadata>) => Omit<OrionEvent<T, P>, 'eventId' | 'timestamp'>;
};

function createEventBuilder<T extends string, P>(
  eventType: T,
  aggregateType: string,
): EventBuilder<T, P> {
  return {
    type: eventType,
    build: (payload, aggregateId, metadata = {}) => ({
      eventType,
      aggregateType,
      aggregateId,
      payload,
      metadata: metadata as EventMetadata,
    }),
  };
}

// RFQ Events
export const RfqCreated = createEventBuilder<'rfq.created', {
  rfqId: string;
  instrumentId: string;
  side: 'buy' | 'sell';
  quantity: number;
  clientId?: string;
}>('rfq.created', 'rfq');

export const RfqQuoted = createEventBuilder<'rfq.quoted', {
  rfqId: string;
  quoteId: string;
  lpId: string;
  bidPrice?: number;
  askPrice?: number;
  validUntil: string;
}>('rfq.quoted', 'rfq');

export const RfqExecuted = createEventBuilder<'rfq.executed', {
  rfqId: string;
  tradeId: string;
  quoteId: string;
  executedPrice: number;
  executedQuantity: number;
}>('rfq.executed', 'rfq');

// Trade Events
export const TradeExecuted = createEventBuilder<'trade.executed', {
  tradeId: string;
  instrumentId: string;
  buyerId: string;
  sellerId: string;
  price: number;
  quantity: number;
  executedAt: string;
}>('trade.executed', 'trade');

export const TradeConfirmed = createEventBuilder<'trade.confirmed', {
  tradeId: string;
  confirmationType: 'buyer' | 'seller' | 'both';
  confirmedAt: string;
}>('trade.confirmed', 'trade');

// Order Events
export const OrderCreated = createEventBuilder<'order.created', {
  orderId: string;
  instrumentId: string;
  side: 'buy' | 'sell';
  type: 'market' | 'limit';
  quantity: number;
  price?: number;
}>('order.created', 'order');

export const OrderFilled = createEventBuilder<'order.filled', {
  orderId: string;
  fillPrice: number;
  fillQuantity: number;
  remainingQuantity: number;
  tradeId: string;
}>('order.filled', 'order');
```

### Usage Examples

```typescript
// Using event builders in service
import { Injectable } from '@nestjs/common';
import { EventBus, RfqCreated, RfqQuoted } from '@orion/event-model';

@Injectable()
export class RfqService {
  constructor(private readonly eventBus: EventBus) {}

  async createRfq(dto: CreateRfqDto): Promise<Rfq> {
    // ... create RFQ in database ...

    // Type-safe event publishing
    await this.eventBus.publish(
      RfqCreated.build(
        {
          rfqId: rfq.id,
          instrumentId: rfq.instrumentId,
          side: rfq.side,
          quantity: rfq.quantity,
        },
        rfq.id,
      ),
    );

    return rfq;
  }

  async addQuote(rfqId: string, quote: Quote): Promise<void> {
    // ... save quote ...

    await this.eventBus.publish(
      RfqQuoted.build(
        {
          rfqId,
          quoteId: quote.id,
          lpId: quote.lpId,
          bidPrice: quote.bidPrice,
          askPrice: quote.askPrice,
          validUntil: quote.validUntil.toISOString(),
        },
        rfqId,
      ),
    );
  }
}
```

### NestJS Module

```typescript
// libs/event-model/src/event-bus/event-bus.module.ts
import { Module, DynamicModule, Global } from '@nestjs/common';
import { Kafka } from 'kafkajs';
import { EventBusService } from './event-bus.service';
import { OutboxService } from '../outbox/outbox.service';

export interface EventBusModuleOptions {
  brokers: string[];
  clientId: string;
}

@Global()
@Module({})
export class EventBusModule {
  static forRoot(options: EventBusModuleOptions): DynamicModule {
    const kafkaProvider = {
      provide: Kafka,
      useFactory: () => new Kafka({
        clientId: options.clientId,
        brokers: options.brokers,
      }),
    };

    return {
      module: EventBusModule,
      providers: [
        kafkaProvider,
        OutboxService,
        EventBusService,
        {
          provide: 'EVENT_BUS',
          useExisting: EventBusService,
        },
      ],
      exports: [EventBusService, 'EVENT_BUS', OutboxService],
    };
  }
}
```

## Definition of Done

- [ ] EventBus interface defined
- [ ] Publisher implementation complete
- [ ] Event builders created
- [ ] NestJS module works
- [ ] Outbox integration tested
- [ ] Direct publish option works
- [ ] TypeScript types exported

## Dependencies

- **US-05-01**: Kafka Topic Configuration
- **US-05-02**: Transactional Outbox Pattern

## Test Cases

```typescript
describe('EventBusService', () => {
  it('should enrich event with tenant context', async () => {
    await runWithTenant({ tenantId: 'test-tenant', userId: 'user-1' }, async () => {
      const publishSpy = jest.spyOn(outbox, 'writeToOutbox');
      
      await eventBus.publish(
        RfqCreated.build({ rfqId: '123', ... }, '123'),
      );
      
      expect(publishSpy).toHaveBeenCalledWith(
        expect.anything(),
        expect.objectContaining({
          metadata: expect.objectContaining({
            tenantId: 'test-tenant',
            userId: 'user-1',
          }),
        }),
      );
    });
  });

  it('should publish directly when useOutbox is false', async () => {
    const sendSpy = jest.spyOn(producer, 'send');
    
    await eventBus.publish(
      RfqCreated.build({ rfqId: '123', ... }, '123'),
      { useOutbox: false },
    );
    
    expect(sendSpy).toHaveBeenCalled();
  });
});
```
