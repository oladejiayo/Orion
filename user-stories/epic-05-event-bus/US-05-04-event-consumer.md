# User Story: US-05-04 - Event Consumer Library

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-05-04 |
| **Epic** | Epic 05 - Event Bus Infrastructure |
| **Title** | Event Consumer Library |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |

## User Story

**As a** backend developer  
**I want** a simple API to subscribe to and handle events  
**So that** I can react to domain events without Kafka complexity

## Description

Create a shared library for consuming Kafka events with automatic deserialization, tenant context restoration, error handling, and consumer group management.

## Acceptance Criteria

- [ ] Declarative event handler registration
- [ ] Automatic tenant context restoration
- [ ] Error handling with DLQ routing
- [ ] Consumer group management
- [ ] Commit strategies (auto/manual)
- [ ] Graceful shutdown with rebalancing

## Technical Details

### Consumer Interface

```typescript
// libs/event-model/src/consumer/consumer.interface.ts
import { OrionEvent } from '../envelope';

export interface ConsumerOptions {
  groupId: string;
  topics: string[];
  fromBeginning?: boolean;
  autoCommit?: boolean;
  maxRetries?: number;
}

export interface EventHandler<T = unknown> {
  eventTypes: string[];
  handle(event: OrionEvent<string, T>): Promise<void>;
}
```

### Event Consumer Service

```typescript
// libs/event-model/src/consumer/event-consumer.service.ts
import { Injectable, OnModuleInit, OnModuleDestroy } from '@nestjs/common';
import { Kafka, Consumer, EachMessagePayload } from 'kafkajs';
import { tenantContextStorage } from '@orion/security';
import { logger, metrics } from '@orion/observability';
import { OrionEvent } from '../envelope';
import { ConsumerOptions, EventHandler } from './consumer.interface';
import { DlqService } from '../dlq/dlq.service';

@Injectable()
export class EventConsumerService implements OnModuleInit, OnModuleDestroy {
  private consumer: Consumer;
  private handlers = new Map<string, EventHandler>();
  private running = false;

  constructor(
    private readonly kafka: Kafka,
    private readonly options: ConsumerOptions,
    private readonly dlq: DlqService,
  ) {
    this.consumer = kafka.consumer({
      groupId: options.groupId,
      sessionTimeout: 30000,
      heartbeatInterval: 3000,
    });
  }

  async onModuleInit() {
    await this.consumer.connect();
    await this.consumer.subscribe({
      topics: this.options.topics,
      fromBeginning: this.options.fromBeginning ?? false,
    });

    this.running = true;
    await this.consumer.run({
      autoCommit: this.options.autoCommit ?? true,
      eachMessage: async (payload) => {
        await this.handleMessage(payload);
      },
    });

    logger.info('Event consumer started', {
      groupId: this.options.groupId,
      topics: this.options.topics,
    });
  }

  async onModuleDestroy() {
    this.running = false;
    await this.consumer.disconnect();
    logger.info('Event consumer stopped');
  }

  /**
   * Register an event handler
   */
  registerHandler(handler: EventHandler): void {
    for (const eventType of handler.eventTypes) {
      this.handlers.set(eventType, handler);
      logger.info('Registered handler', { eventType, handler: handler.constructor.name });
    }
  }

  private async handleMessage(payload: EachMessagePayload): Promise<void> {
    const { topic, partition, message } = payload;
    const startTime = Date.now();

    let event: OrionEvent<string, unknown>;
    
    try {
      // Parse event
      event = JSON.parse(message.value?.toString() || '{}');
    } catch (error) {
      logger.error('Failed to parse event', { topic, partition, error });
      metrics.increment('consumer.parse_error', { topic });
      return; // Skip unparseable messages
    }

    const handler = this.handlers.get(event.eventType);
    
    if (!handler) {
      logger.debug('No handler for event type', { eventType: event.eventType });
      return;
    }

    // Restore tenant context from event metadata
    const context = {
      tenantId: event.metadata?.tenantId || '',
      userId: event.metadata?.userId,
      correlationId: event.metadata?.correlationId || crypto.randomUUID(),
      roles: [],
      permissions: [],
      isAdmin: false,
    };

    try {
      await tenantContextStorage.run(context, async () => {
        await handler.handle(event);
      });

      metrics.timing('consumer.process_time', Date.now() - startTime, {
        topic,
        eventType: event.eventType,
      });
      metrics.increment('consumer.success', { eventType: event.eventType });
      
      logger.debug('Event processed', {
        eventId: event.eventId,
        eventType: event.eventType,
        duration: Date.now() - startTime,
      });
    } catch (error) {
      logger.error('Event handler failed', {
        eventId: event.eventId,
        eventType: event.eventType,
        error,
      });

      metrics.increment('consumer.error', { eventType: event.eventType });

      // Send to DLQ
      await this.dlq.send({
        originalTopic: topic,
        originalPartition: partition,
        originalOffset: message.offset,
        event,
        error: (error as Error).message,
        stack: (error as Error).stack,
      });
    }
  }
}
```

### Decorator-Based Handlers

```typescript
// libs/event-model/src/consumer/decorators.ts
import 'reflect-metadata';

const EVENT_HANDLER_METADATA = 'EVENT_HANDLER_METADATA';

export interface EventHandlerMetadata {
  eventTypes: string[];
}

/**
 * Decorator to mark a method as an event handler
 */
export function OnEvent(...eventTypes: string[]): MethodDecorator {
  return (target, propertyKey, descriptor) => {
    const existingHandlers: EventHandlerMetadata[] = 
      Reflect.getMetadata(EVENT_HANDLER_METADATA, target.constructor) || [];
    
    existingHandlers.push({
      eventTypes,
    });
    
    Reflect.defineMetadata(EVENT_HANDLER_METADATA, existingHandlers, target.constructor);
    return descriptor;
  };
}

/**
 * Get all event handlers from a class
 */
export function getEventHandlers(target: any): EventHandlerMetadata[] {
  return Reflect.getMetadata(EVENT_HANDLER_METADATA, target.constructor) || [];
}
```

### Handler Base Class

```typescript
// libs/event-model/src/consumer/event-handler.base.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { EventConsumerService } from './event-consumer.service';
import { OrionEvent } from '../envelope';
import { getEventHandlers } from './decorators';

@Injectable()
export abstract class EventHandlerBase implements OnModuleInit {
  constructor(protected readonly consumer: EventConsumerService) {}

  async onModuleInit() {
    const handlers = getEventHandlers(this);
    
    for (const metadata of handlers) {
      this.consumer.registerHandler({
        eventTypes: metadata.eventTypes,
        handle: async (event: OrionEvent<string, unknown>) => {
          const methodName = this.getHandlerMethod(event.eventType);
          if (methodName && typeof (this as any)[methodName] === 'function') {
            await (this as any)[methodName](event);
          }
        },
      });
    }
  }

  private getHandlerMethod(eventType: string): string | null {
    const handlers = getEventHandlers(this);
    const handler = handlers.find(h => h.eventTypes.includes(eventType));
    // Convention: method name based on event type
    return handler ? `handle${this.toPascalCase(eventType)}` : null;
  }

  private toPascalCase(str: string): string {
    return str
      .split('.')
      .map(part => part.charAt(0).toUpperCase() + part.slice(1))
      .join('');
  }
}
```

### Usage Example

```typescript
// services/notification-service/src/handlers/rfq-event.handler.ts
import { Injectable } from '@nestjs/common';
import { EventHandlerBase, OnEvent } from '@orion/event-model';
import { OrionEvent } from '@orion/event-model';
import { NotificationService } from '../notification.service';

interface RfqCreatedPayload {
  rfqId: string;
  instrumentId: string;
  side: 'buy' | 'sell';
  quantity: number;
}

interface RfqExecutedPayload {
  rfqId: string;
  tradeId: string;
  executedPrice: number;
  executedQuantity: number;
}

@Injectable()
export class RfqEventHandler extends EventHandlerBase {
  constructor(
    consumer: EventConsumerService,
    private readonly notifications: NotificationService,
  ) {
    super(consumer);
  }

  @OnEvent('rfq.created')
  async handleRfqCreated(event: OrionEvent<'rfq.created', RfqCreatedPayload>) {
    const { rfqId, instrumentId, quantity } = event.payload;
    
    // Notify liquidity providers
    await this.notifications.notifyLPs({
      type: 'new_rfq',
      rfqId,
      instrumentId,
      quantity,
    });
  }

  @OnEvent('rfq.executed')
  async handleRfqExecuted(event: OrionEvent<'rfq.executed', RfqExecutedPayload>) {
    const { rfqId, tradeId, executedPrice } = event.payload;
    
    // Send trade confirmation
    await this.notifications.sendTradeConfirmation({
      rfqId,
      tradeId,
      price: executedPrice,
    });
  }
}
```

### Consumer Module

```typescript
// libs/event-model/src/consumer/consumer.module.ts
import { Module, DynamicModule } from '@nestjs/common';
import { Kafka } from 'kafkajs';
import { EventConsumerService } from './event-consumer.service';
import { DlqService } from '../dlq/dlq.service';
import { ConsumerOptions } from './consumer.interface';

@Module({})
export class EventConsumerModule {
  static forRoot(options: ConsumerOptions & { brokers: string[] }): DynamicModule {
    const kafkaProvider = {
      provide: Kafka,
      useFactory: () => new Kafka({
        clientId: `consumer-${options.groupId}`,
        brokers: options.brokers,
      }),
    };

    return {
      module: EventConsumerModule,
      providers: [
        kafkaProvider,
        {
          provide: 'CONSUMER_OPTIONS',
          useValue: options,
        },
        DlqService,
        {
          provide: EventConsumerService,
          useFactory: (kafka: Kafka, dlq: DlqService) => 
            new EventConsumerService(kafka, options, dlq),
          inject: [Kafka, DlqService],
        },
      ],
      exports: [EventConsumerService],
    };
  }
}
```

## Definition of Done

- [ ] Consumer service implemented
- [ ] Decorator-based handlers work
- [ ] Tenant context restored
- [ ] DLQ routing on errors
- [ ] Graceful shutdown works
- [ ] Module configuration complete
- [ ] Tests verify behavior

## Dependencies

- **US-05-01**: Kafka Topic Configuration
- **US-05-05**: Dead Letter Queue Handling

## Test Cases

```typescript
describe('EventConsumerService', () => {
  it('should restore tenant context from event', async () => {
    let capturedTenantId: string | undefined;
    
    consumer.registerHandler({
      eventTypes: ['test.event'],
      handle: async (event) => {
        capturedTenantId = getCurrentTenant()?.tenantId;
      },
    });

    await simulateMessage({
      eventType: 'test.event',
      payload: {},
      metadata: { tenantId: 'test-tenant' },
    });

    expect(capturedTenantId).toBe('test-tenant');
  });

  it('should route failed messages to DLQ', async () => {
    const dlqSpy = jest.spyOn(dlq, 'send');
    
    consumer.registerHandler({
      eventTypes: ['failing.event'],
      handle: async () => { throw new Error('Handler failed'); },
    });

    await simulateMessage({
      eventType: 'failing.event',
      payload: {},
    });

    expect(dlqSpy).toHaveBeenCalled();
  });
});
```
