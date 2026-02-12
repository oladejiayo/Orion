# User Story: US-09-04 - Order State Machine

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-09-04 |
| **Epic** | Epic 09 - OMS Orders V1 |
| **Title** | Order State Machine |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |
| **PRD Reference** | FR-Order-04, NFR-Reliability-01 |

## User Story

**As a** platform developer  
**I want** a robust order state machine  
**So that** order state transitions are valid and auditable

## Description

Implement finite state machine for order lifecycle management with validated transitions, transition guards, event publishing on state changes, and complete transition history.

## Acceptance Criteria

- [ ] All order states defined
- [ ] Valid transitions enforced
- [ ] Transition guards
- [ ] Events published on transition
- [ ] Transition history recorded
- [ ] Optimistic locking
- [ ] Concurrent transition protection

## Technical Details

### Order State Definition

```typescript
// services/order-service/src/state-machine/order-states.ts
import { OrderStatus } from '../entities/order.entity';

export interface StateTransition {
  from: OrderStatus;
  to: OrderStatus;
  event: string;
  guard?: string;
}

export const ORDER_TRANSITIONS: StateTransition[] = [
  // Initial validation
  { from: OrderStatus.PENDING, to: OrderStatus.VALIDATED, event: 'VALIDATE' },
  { from: OrderStatus.PENDING, to: OrderStatus.REJECTED, event: 'REJECT' },
  { from: OrderStatus.PENDING, to: OrderStatus.HELD, event: 'HOLD', guard: 'canHold' },
  
  // Routing
  { from: OrderStatus.VALIDATED, to: OrderStatus.WORKING, event: 'ROUTE' },
  { from: OrderStatus.VALIDATED, to: OrderStatus.REJECTED, event: 'REJECT' },
  { from: OrderStatus.VALIDATED, to: OrderStatus.HELD, event: 'HOLD' },
  
  // From hold
  { from: OrderStatus.HELD, to: OrderStatus.WORKING, event: 'RELEASE' },
  { from: OrderStatus.HELD, to: OrderStatus.CANCELLED, event: 'CANCEL' },
  { from: OrderStatus.HELD, to: OrderStatus.EXPIRED, event: 'EXPIRE' },
  
  // Execution
  { from: OrderStatus.WORKING, to: OrderStatus.PARTIAL_FILL, event: 'PARTIAL_FILL' },
  { from: OrderStatus.WORKING, to: OrderStatus.FILLED, event: 'FILL' },
  { from: OrderStatus.WORKING, to: OrderStatus.CANCELLED, event: 'CANCEL' },
  { from: OrderStatus.WORKING, to: OrderStatus.EXPIRED, event: 'EXPIRE' },
  { from: OrderStatus.WORKING, to: OrderStatus.DONE_FOR_DAY, event: 'DONE_FOR_DAY' },
  
  // Partial fills
  { from: OrderStatus.PARTIAL_FILL, to: OrderStatus.PARTIAL_FILL, event: 'PARTIAL_FILL' },
  { from: OrderStatus.PARTIAL_FILL, to: OrderStatus.FILLED, event: 'FILL' },
  { from: OrderStatus.PARTIAL_FILL, to: OrderStatus.CANCELLED, event: 'CANCEL' },
  { from: OrderStatus.PARTIAL_FILL, to: OrderStatus.EXPIRED, event: 'EXPIRE' },
  { from: OrderStatus.PARTIAL_FILL, to: OrderStatus.DONE_FOR_DAY, event: 'DONE_FOR_DAY' },
];

export const TERMINAL_STATES = [
  OrderStatus.FILLED,
  OrderStatus.CANCELLED,
  OrderStatus.REJECTED,
  OrderStatus.EXPIRED,
];

export function isValidTransition(from: OrderStatus, to: OrderStatus): boolean {
  return ORDER_TRANSITIONS.some(t => t.from === from && t.to === to);
}

export function getTransition(from: OrderStatus, to: OrderStatus): StateTransition | undefined {
  return ORDER_TRANSITIONS.find(t => t.from === from && t.to === to);
}
```

### Transition Guards

```typescript
// services/order-service/src/state-machine/transition-guards.ts
import { Injectable } from '@nestjs/common';
import { OrderEntity, OrderStatus } from '../entities/order.entity';

export interface TransitionContext {
  order: OrderEntity;
  targetStatus: OrderStatus;
  payload?: any;
  userId?: string;
}

@Injectable()
export class TransitionGuards {
  /**
   * Check if order can be held
   */
  canHold(context: TransitionContext): boolean {
    const { order } = context;
    // Only certain order types can be held
    return !['ioc', 'fok'].includes(order.timeInForce);
  }

  /**
   * Check if order can be cancelled
   */
  canCancel(context: TransitionContext): boolean {
    const { order } = context;
    // Cannot cancel if already terminal
    return !TERMINAL_STATES.includes(order.status);
  }

  /**
   * Check if order can be filled
   */
  canFill(context: TransitionContext): boolean {
    const { order, payload } = context;
    if (!payload?.fillQuantity) return false;
    return payload.fillQuantity > 0 && payload.fillQuantity <= order.remainingQuantity;
  }

  /**
   * Check if partial fill is valid
   */
  canPartialFill(context: TransitionContext): boolean {
    const { order, payload } = context;
    if (!payload?.fillQuantity) return false;
    return payload.fillQuantity > 0 && payload.fillQuantity < order.remainingQuantity;
  }

  /**
   * Execute guard by name
   */
  executeGuard(guardName: string, context: TransitionContext): boolean {
    const guard = (this as any)[guardName];
    if (typeof guard !== 'function') {
      throw new Error(`Guard ${guardName} not found`);
    }
    return guard.call(this, context);
  }
}
```

### Order State Machine Service

```typescript
// services/order-service/src/state-machine/order-state-machine.service.ts
import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, DataSource } from 'typeorm';
import { logger, metrics } from '@orion/observability';
import { EventPublisher } from '@orion/events';
import { OrderEntity, OrderStatus } from '../entities/order.entity';
import { OrderHistoryEntity } from '../entities/order-history.entity';
import { TransitionGuards, TransitionContext } from './transition-guards';
import { isValidTransition, getTransition, TERMINAL_STATES } from './order-states';
import { getCurrentUser } from '@orion/security';

export interface TransitionResult {
  success: boolean;
  order: OrderEntity;
  previousStatus: OrderStatus;
  newStatus: OrderStatus;
  error?: string;
}

export interface TransitionOptions {
  reason?: string;
  fillQuantity?: number;
  fillPrice?: number;
  fillId?: string;
  bypassGuard?: boolean;
  metadata?: Record<string, any>;
}

@Injectable()
export class OrderStateMachineService {
  constructor(
    @InjectRepository(OrderEntity)
    private readonly orderRepo: Repository<OrderEntity>,
    @InjectRepository(OrderHistoryEntity)
    private readonly historyRepo: Repository<OrderHistoryEntity>,
    private readonly dataSource: DataSource,
    private readonly guards: TransitionGuards,
    private readonly eventPublisher: EventPublisher,
  ) {}

  /**
   * Transition order to new state
   */
  async transition(
    order: OrderEntity,
    targetStatus: OrderStatus,
    options: TransitionOptions = {},
  ): Promise<TransitionResult> {
    const startTime = Date.now();
    const previousStatus = order.status;

    // Validate transition
    if (!isValidTransition(previousStatus, targetStatus)) {
      logger.warn('Invalid state transition attempted', {
        orderId: order.id,
        from: previousStatus,
        to: targetStatus,
      });

      return {
        success: false,
        order,
        previousStatus,
        newStatus: previousStatus,
        error: `Invalid transition from ${previousStatus} to ${targetStatus}`,
      };
    }

    // Get transition definition
    const transitionDef = getTransition(previousStatus, targetStatus);

    // Execute guard if defined
    if (transitionDef?.guard && !options.bypassGuard) {
      const context: TransitionContext = {
        order,
        targetStatus,
        payload: options,
        userId: getCurrentUser()?.id,
      };

      const guardResult = this.guards.executeGuard(transitionDef.guard, context);
      if (!guardResult) {
        return {
          success: false,
          order,
          previousStatus,
          newStatus: previousStatus,
          error: `Guard ${transitionDef.guard} rejected transition`,
        };
      }
    }

    // Execute transition with optimistic locking
    const queryRunner = this.dataSource.createQueryRunner();
    await queryRunner.connect();
    await queryRunner.startTransaction();

    try {
      // Re-fetch with lock to prevent concurrent transitions
      const lockedOrder = await queryRunner.manager.findOne(OrderEntity, {
        where: { id: order.id },
        lock: { mode: 'pessimistic_write' },
      });

      if (!lockedOrder) {
        throw new Error(`Order ${order.id} not found`);
      }

      // Verify status hasn't changed
      if (lockedOrder.status !== previousStatus) {
        throw new Error(
          `Order status changed from ${previousStatus} to ${lockedOrder.status}`,
        );
      }

      // Record history
      await this.recordHistory(queryRunner.manager, lockedOrder, targetStatus, options);

      // Apply transition
      this.applyTransition(lockedOrder, targetStatus, options);

      // Save order
      await queryRunner.manager.save(lockedOrder);

      await queryRunner.commitTransaction();

      // Publish event
      await this.publishTransitionEvent(lockedOrder, previousStatus, targetStatus, options);

      // Metrics
      metrics.increment('order.transitions', {
        from: previousStatus,
        to: targetStatus,
      });
      metrics.timing('order.transition.time', Date.now() - startTime);

      return {
        success: true,
        order: lockedOrder,
        previousStatus,
        newStatus: targetStatus,
      };
    } catch (error) {
      await queryRunner.rollbackTransaction();
      
      logger.error('Order transition failed', {
        orderId: order.id,
        from: previousStatus,
        to: targetStatus,
        error,
      });

      return {
        success: false,
        order,
        previousStatus,
        newStatus: previousStatus,
        error: error.message,
      };
    } finally {
      await queryRunner.release();
    }
  }

  /**
   * Apply fill to order and transition
   */
  async applyFill(
    order: OrderEntity,
    fillQuantity: number,
    fillPrice: number,
    fillId: string,
  ): Promise<TransitionResult> {
    const newFilledQty = Number(order.filledQuantity) + fillQuantity;
    const isComplete = newFilledQty >= Number(order.quantity);

    const targetStatus = isComplete ? OrderStatus.FILLED : OrderStatus.PARTIAL_FILL;

    return this.transition(order, targetStatus, {
      fillQuantity,
      fillPrice,
      fillId,
    });
  }

  private applyTransition(
    order: OrderEntity,
    targetStatus: OrderStatus,
    options: TransitionOptions,
  ): void {
    const now = new Date();

    // Update status
    order.status = targetStatus;
    order.version += 1;

    // Apply status-specific updates
    switch (targetStatus) {
      case OrderStatus.VALIDATED:
        order.validatedAt = now;
        break;

      case OrderStatus.WORKING:
        order.workedAt = now;
        break;

      case OrderStatus.PARTIAL_FILL:
      case OrderStatus.FILLED:
        if (options.fillQuantity && options.fillPrice) {
          const prevFilled = Number(order.filledQuantity);
          const prevAvg = Number(order.averagePrice) || 0;
          const newFilled = prevFilled + options.fillQuantity;
          
          // Calculate new average price
          order.filledQuantity = newFilled;
          order.remainingQuantity = Number(order.quantity) - newFilled;
          order.averagePrice = (prevAvg * prevFilled + options.fillPrice * options.fillQuantity) / newFilled;
          order.totalNotional = newFilled * Number(order.averagePrice);
        }
        if (targetStatus === OrderStatus.FILLED) {
          order.completedAt = now;
        }
        break;

      case OrderStatus.CANCELLED:
      case OrderStatus.REJECTED:
      case OrderStatus.EXPIRED:
        order.completedAt = now;
        order.statusReason = options.reason;
        break;
    }
  }

  private async recordHistory(
    manager: any,
    order: OrderEntity,
    newStatus: OrderStatus,
    options: TransitionOptions,
  ): Promise<void> {
    const history = new OrderHistoryEntity();
    history.orderId = order.id;
    history.version = order.version;
    history.previousStatus = order.status;
    history.newStatus = newStatus;
    history.event = getTransition(order.status, newStatus)?.event || 'TRANSITION';
    history.snapshot = JSON.parse(JSON.stringify(order));
    history.reason = options.reason;
    history.metadata = options.metadata;
    history.performedBy = getCurrentUser()?.id;
    history.performedAt = new Date();

    await manager.save(history);
  }

  private async publishTransitionEvent(
    order: OrderEntity,
    previousStatus: OrderStatus,
    newStatus: OrderStatus,
    options: TransitionOptions,
  ): Promise<void> {
    const eventType = this.getEventType(newStatus);

    await this.eventPublisher.publish(eventType, {
      orderId: order.id,
      orderRef: order.orderRef,
      previousStatus,
      newStatus,
      filledQuantity: order.filledQuantity,
      remainingQuantity: order.remainingQuantity,
      averagePrice: order.averagePrice,
      reason: options.reason,
      timestamp: new Date().toISOString(),
    });
  }

  private getEventType(status: OrderStatus): string {
    const eventMap: Record<OrderStatus, string> = {
      [OrderStatus.PENDING]: 'order.created',
      [OrderStatus.VALIDATED]: 'order.validated',
      [OrderStatus.REJECTED]: 'order.rejected',
      [OrderStatus.HELD]: 'order.held',
      [OrderStatus.WORKING]: 'order.working',
      [OrderStatus.PARTIAL_FILL]: 'order.partial_fill',
      [OrderStatus.FILLED]: 'order.filled',
      [OrderStatus.CANCELLED]: 'order.cancelled',
      [OrderStatus.EXPIRED]: 'order.expired',
      [OrderStatus.DONE_FOR_DAY]: 'order.done_for_day',
    };

    return eventMap[status] || 'order.status_changed';
  }

  /**
   * Get order transition history
   */
  async getHistory(orderId: string): Promise<OrderHistoryEntity[]> {
    return this.historyRepo.find({
      where: { orderId },
      order: { performedAt: 'ASC' },
    });
  }
}
```

### Order History Entity

```typescript
// services/order-service/src/entities/order-history.entity.ts
import { Entity, PrimaryGeneratedColumn, Column, CreateDateColumn, Index } from 'typeorm';
import { OrderStatus } from './order.entity';

@Entity('order_history')
@Index(['orderId', 'version'])
@Index(['performedAt'])
export class OrderHistoryEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column('uuid')
  orderId: string;

  @Column('int')
  version: number;

  @Column('varchar', { length: 50 })
  event: string;

  @Column({ type: 'enum', enum: OrderStatus })
  previousStatus: OrderStatus;

  @Column({ type: 'enum', enum: OrderStatus })
  newStatus: OrderStatus;

  @Column('jsonb')
  snapshot: Record<string, any>;

  @Column('text', { nullable: true })
  reason?: string;

  @Column('jsonb', { nullable: true })
  metadata?: Record<string, any>;

  @Column('uuid', { nullable: true })
  performedBy?: string;

  @CreateDateColumn()
  performedAt: Date;
}
```

### State Machine SQL Schema

```sql
-- migrations/012_order_history_schema.sql

CREATE TABLE order_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id),
    version INT NOT NULL,
    event VARCHAR(50) NOT NULL,
    previous_status order_status NOT NULL,
    new_status order_status NOT NULL,
    snapshot JSONB NOT NULL,
    reason TEXT,
    metadata JSONB,
    performed_by UUID,
    performed_at TIMESTAMPTZ DEFAULT NOW(),
    
    CONSTRAINT order_history_version_unique UNIQUE (order_id, version)
);

-- Indexes
CREATE INDEX idx_order_history_order ON order_history(order_id);
CREATE INDEX idx_order_history_performed_at ON order_history(performed_at);

-- Append-only trigger
CREATE OR REPLACE FUNCTION prevent_history_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Order history is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER order_history_immutable
BEFORE UPDATE OR DELETE ON order_history
FOR EACH ROW EXECUTE FUNCTION prevent_history_modification();
```

## Definition of Done

- [ ] State transitions defined
- [ ] Transition guards
- [ ] State machine service
- [ ] History recording
- [ ] Event publishing
- [ ] Optimistic locking
- [ ] Concurrent protection

## Dependencies

- **US-09-01**: Order Entity

## Test Cases

```typescript
describe('OrderStateMachineService', () => {
  it('should transition from PENDING to VALIDATED', async () => {
    const order = await createOrder({ status: OrderStatus.PENDING });

    const result = await stateMachine.transition(order, OrderStatus.VALIDATED);

    expect(result.success).toBe(true);
    expect(result.order.status).toBe(OrderStatus.VALIDATED);
    expect(result.order.validatedAt).toBeDefined();
  });

  it('should reject invalid transition', async () => {
    const order = await createOrder({ status: OrderStatus.PENDING });

    const result = await stateMachine.transition(order, OrderStatus.FILLED);

    expect(result.success).toBe(false);
    expect(result.error).toContain('Invalid transition');
  });

  it('should record history on transition', async () => {
    const order = await createOrder({ status: OrderStatus.PENDING });

    await stateMachine.transition(order, OrderStatus.VALIDATED);

    const history = await stateMachine.getHistory(order.id);
    expect(history.length).toBe(1);
    expect(history[0].previousStatus).toBe(OrderStatus.PENDING);
    expect(history[0].newStatus).toBe(OrderStatus.VALIDATED);
  });

  it('should calculate average price on fill', async () => {
    const order = await createOrder({ 
      status: OrderStatus.WORKING, 
      quantity: 1000,
      filledQuantity: 0,
    });

    // First fill
    await stateMachine.applyFill(order, 500, 1.0850, 'fill-1');
    expect(order.averagePrice).toBe(1.0850);

    // Second fill at different price
    await stateMachine.applyFill(order, 500, 1.0860, 'fill-2');
    expect(order.averagePrice).toBe(1.0855); // Weighted average
    expect(order.status).toBe(OrderStatus.FILLED);
  });

  it('should handle concurrent transitions', async () => {
    const order = await createOrder({ status: OrderStatus.WORKING });

    // Attempt concurrent transitions
    const [result1, result2] = await Promise.all([
      stateMachine.transition(order, OrderStatus.CANCELLED),
      stateMachine.transition(order, OrderStatus.PARTIAL_FILL, { fillQuantity: 100, fillPrice: 1.0850 }),
    ]);

    // Only one should succeed
    expect([result1.success, result2.success]).toContain(true);
    expect([result1.success, result2.success]).toContain(false);
  });
});
```
