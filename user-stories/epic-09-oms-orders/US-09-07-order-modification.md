# User Story: US-09-07 - Order Modification and Cancellation

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-09-07 |
| **Epic** | Epic 09 - OMS Orders V1 |
| **Title** | Order Modification and Cancellation |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |
| **PRD Reference** | FR-Order-07, NFR-Performance-02 |

## User Story

**As a** trader  
**I want** to modify or cancel working orders  
**So that** I can adjust my trading strategy in response to market conditions

## Description

Implement order modification (amend) and cancellation capabilities for working orders, including validation, LP communication, and state management.

## Acceptance Criteria

- [ ] Cancel pending/working orders
- [ ] Modify price on working orders
- [ ] Modify quantity on working orders
- [ ] Cancel-replace workflow
- [ ] LP order amendment propagation
- [ ] Amendment history tracking
- [ ] Prevent modification of terminal orders
- [ ] Concurrent modification handling

## Technical Details

### Modification DTO

```typescript
// services/order-service/src/dto/order-modification.dto.ts
import { IsUUID, IsOptional, IsNumber, IsEnum, Min } from 'class-validator';

export class ModifyOrderDto {
  @IsUUID()
  orderId: string;

  @IsOptional()
  @IsNumber()
  @Min(0)
  price?: number;

  @IsOptional()
  @IsNumber()
  @Min(0)
  quantity?: number;

  @IsOptional()
  @IsNumber()
  @Min(0)
  stopPrice?: number;

  @IsOptional()
  reason?: string;
}

export class CancelOrderDto {
  @IsUUID()
  orderId: string;

  @IsOptional()
  reason?: string;
}

export enum ModificationType {
  PRICE_CHANGE = 'price_change',
  QUANTITY_CHANGE = 'quantity_change',
  CANCEL_REPLACE = 'cancel_replace',
  CANCEL = 'cancel',
}
```

### Order Modification Entity

```typescript
// services/order-service/src/entities/order-modification.entity.ts
import { Entity, Column, ManyToOne, CreateDateColumn, JoinColumn } from 'typeorm';
import { OrderEntity } from './order.entity';

@Entity('order_modifications')
export class OrderModificationEntity {
  @Column('uuid', { primaryKey: true, default: () => 'gen_random_uuid()' })
  id: string;

  @Column('uuid')
  orderId: string;

  @ManyToOne(() => OrderEntity)
  @JoinColumn({ name: 'order_id' })
  order: OrderEntity;

  @Column('varchar', { length: 50 })
  modificationType: string;

  @Column('jsonb')
  previousValues: any;

  @Column('jsonb')
  newValues: any;

  @Column('varchar', { length: 20 })
  status: 'pending' | 'accepted' | 'rejected' | 'failed';

  @Column('varchar', { length: 500, nullable: true })
  rejectionReason: string;

  @Column('varchar', { length: 100 })
  requestedBy: string;

  @CreateDateColumn()
  requestedAt: Date;

  @Column('timestamp with time zone', { nullable: true })
  processedAt: Date;
}
```

### Order Modification Service

```typescript
// services/order-service/src/modification/order-modification.service.ts
import { Injectable, BadRequestException, ConflictException } from '@nestjs/common';
import { InjectRepository, InjectEntityManager } from '@nestjs/typeorm';
import { Repository, EntityManager } from 'typeorm';
import { logger, metrics } from '@orion/observability';
import { OrderEntity, OrderStatus } from '../entities/order.entity';
import { OrderModificationEntity } from '../entities/order-modification.entity';
import { ModifyOrderDto, CancelOrderDto, ModificationType } from '../dto/order-modification.dto';
import { OrderStateMachineService } from '../state-machine/order-state-machine.service';
import { OrderRoutingService } from '../routing/order-routing.service';
import { LpGatewayManager } from '@orion/connectivity';
import { EventPublisher } from '@orion/events';

const MODIFIABLE_STATUSES = [
  OrderStatus.PENDING,
  OrderStatus.VALIDATED,
  OrderStatus.WORKING,
  OrderStatus.PARTIAL_FILL,
];

const CANCELLABLE_STATUSES = [
  OrderStatus.PENDING,
  OrderStatus.VALIDATED,
  OrderStatus.WORKING,
  OrderStatus.PARTIAL_FILL,
  OrderStatus.HELD,
];

@Injectable()
export class OrderModificationService {
  constructor(
    @InjectRepository(OrderEntity)
    private readonly orderRepo: Repository<OrderEntity>,
    @InjectRepository(OrderModificationEntity)
    private readonly modificationRepo: Repository<OrderModificationEntity>,
    @InjectEntityManager()
    private readonly entityManager: EntityManager,
    private readonly stateMachine: OrderStateMachineService,
    private readonly routingService: OrderRoutingService,
    private readonly lpGateway: LpGatewayManager,
    private readonly eventPublisher: EventPublisher,
  ) {}

  /**
   * Modify an existing order (price/quantity change)
   */
  async modifyOrder(dto: ModifyOrderDto, userId: string): Promise<OrderEntity> {
    return this.entityManager.transaction(async (manager) => {
      // Lock order for modification
      const order = await manager.findOne(OrderEntity, {
        where: { id: dto.orderId },
        lock: { mode: 'pessimistic_write' },
      });

      if (!order) {
        throw new BadRequestException(`Order not found: ${dto.orderId}`);
      }

      // Validate order is modifiable
      if (!MODIFIABLE_STATUSES.includes(order.status)) {
        throw new BadRequestException(
          `Order in status ${order.status} cannot be modified`,
        );
      }

      // Determine modification type
      const modificationType = this.determineModificationType(dto);

      // Store previous values
      const previousValues = {
        price: order.price,
        quantity: order.quantity,
        stopPrice: order.stopPrice,
      };

      // Create modification record
      const modification = manager.create(OrderModificationEntity, {
        orderId: order.id,
        modificationType,
        previousValues,
        newValues: {
          price: dto.price,
          quantity: dto.quantity,
          stopPrice: dto.stopPrice,
        },
        status: 'pending',
        requestedBy: userId,
      });
      await manager.save(modification);

      try {
        // If order is working at LP, send amendment
        if (order.status === OrderStatus.WORKING || order.status === OrderStatus.PARTIAL_FILL) {
          await this.amendAtLps(order, dto);
        }

        // Apply modifications
        if (dto.price !== undefined) {
          order.price = dto.price;
        }
        if (dto.quantity !== undefined) {
          // Cannot reduce quantity below filled
          if (dto.quantity < Number(order.filledQuantity)) {
            throw new BadRequestException(
              `Cannot reduce quantity below filled amount: ${order.filledQuantity}`,
            );
          }
          order.quantity = dto.quantity;
          order.remainingQuantity = dto.quantity - Number(order.filledQuantity);
        }
        if (dto.stopPrice !== undefined) {
          order.stopPrice = dto.stopPrice;
        }

        order.version += 1;
        await manager.save(order);

        // Update modification status
        modification.status = 'accepted';
        modification.processedAt = new Date();
        await manager.save(modification);

        // Publish event
        await this.publishModificationEvent(order, modification);

        logger.info('Order modified', {
          orderId: order.id,
          modificationType,
          previousValues,
          newValues: dto,
        });

        metrics.increment('order.modification.success');

        return order;
      } catch (error) {
        modification.status = 'failed';
        modification.rejectionReason = error.message;
        modification.processedAt = new Date();
        await manager.save(modification);

        metrics.increment('order.modification.failure');
        throw error;
      }
    });
  }

  /**
   * Cancel an existing order
   */
  async cancelOrder(dto: CancelOrderDto, userId: string): Promise<OrderEntity> {
    return this.entityManager.transaction(async (manager) => {
      // Lock order for cancellation
      const order = await manager.findOne(OrderEntity, {
        where: { id: dto.orderId },
        lock: { mode: 'pessimistic_write' },
      });

      if (!order) {
        throw new BadRequestException(`Order not found: ${dto.orderId}`);
      }

      // Validate order is cancellable
      if (!CANCELLABLE_STATUSES.includes(order.status)) {
        throw new BadRequestException(
          `Order in status ${order.status} cannot be cancelled`,
        );
      }

      // Create modification record for cancel
      const modification = manager.create(OrderModificationEntity, {
        orderId: order.id,
        modificationType: ModificationType.CANCEL,
        previousValues: { status: order.status },
        newValues: { status: OrderStatus.CANCELLED, reason: dto.reason },
        status: 'pending',
        requestedBy: userId,
      });
      await manager.save(modification);

      try {
        // If order is working at LP, send cancellation
        if (order.status === OrderStatus.WORKING || order.status === OrderStatus.PARTIAL_FILL) {
          await this.routingService.cancelWorkingOrders(order);
        }

        // Transition to cancelled
        await this.stateMachine.transition(order, OrderStatus.CANCELLED, {
          reason: dto.reason,
          cancelledBy: userId,
        }, manager);

        // Update modification status
        modification.status = 'accepted';
        modification.processedAt = new Date();
        await manager.save(modification);

        // Publish event
        await this.publishCancellationEvent(order, dto.reason);

        logger.info('Order cancelled', {
          orderId: order.id,
          reason: dto.reason,
          cancelledBy: userId,
        });

        metrics.increment('order.cancellation.success');

        return order;
      } catch (error) {
        modification.status = 'failed';
        modification.rejectionReason = error.message;
        modification.processedAt = new Date();
        await manager.save(modification);

        metrics.increment('order.cancellation.failure');
        throw error;
      }
    });
  }

  /**
   * Cancel and replace order (atomic cancel + new order)
   */
  async cancelReplace(
    orderId: string,
    newOrderData: Partial<OrderEntity>,
    userId: string,
  ): Promise<{ cancelledOrder: OrderEntity; newOrder: OrderEntity }> {
    return this.entityManager.transaction(async (manager) => {
      // Cancel existing order
      const cancelledOrder = await this.cancelOrder({ orderId }, userId);

      // Create new order with reference to original
      const newOrder = manager.create(OrderEntity, {
        ...newOrderData,
        tenantId: cancelledOrder.tenantId,
        clientId: cancelledOrder.clientId,
        replacesOrderId: cancelledOrder.id,
        status: OrderStatus.PENDING,
      });

      await manager.save(newOrder);

      logger.info('Order cancel-replaced', {
        originalOrderId: orderId,
        newOrderId: newOrder.id,
      });

      return { cancelledOrder, newOrder };
    });
  }

  /**
   * Send amendment to LPs
   */
  private async amendAtLps(order: OrderEntity, dto: ModifyOrderDto): Promise<void> {
    for (let i = 0; i < order.routedLps.length; i++) {
      const lpId = order.routedLps[i];
      const lpOrderId = order.workingOrderIds[i];

      if (lpOrderId) {
        try {
          await this.lpGateway.amendOrder(lpId, lpOrderId, {
            price: dto.price,
            quantity: dto.quantity,
          });

          logger.info('LP order amended', {
            orderId: order.id,
            lpId,
            lpOrderId,
          });
        } catch (error) {
          logger.error('Failed to amend LP order', {
            orderId: order.id,
            lpId,
            lpOrderId,
            error,
          });
          throw new ConflictException(
            `Failed to amend order at LP ${lpId}: ${error.message}`,
          );
        }
      }
    }
  }

  private determineModificationType(dto: ModifyOrderDto): ModificationType {
    if (dto.price !== undefined && dto.quantity !== undefined) {
      return ModificationType.CANCEL_REPLACE;
    }
    if (dto.price !== undefined) {
      return ModificationType.PRICE_CHANGE;
    }
    return ModificationType.QUANTITY_CHANGE;
  }

  private async publishModificationEvent(
    order: OrderEntity,
    modification: OrderModificationEntity,
  ): Promise<void> {
    await this.eventPublisher.publish({
      type: 'order.modified',
      aggregateId: order.id,
      aggregateType: 'Order',
      payload: {
        orderId: order.id,
        modificationId: modification.id,
        modificationType: modification.modificationType,
        previousValues: modification.previousValues,
        newValues: modification.newValues,
      },
      metadata: { tenantId: order.tenantId },
    });
  }

  private async publishCancellationEvent(
    order: OrderEntity,
    reason?: string,
  ): Promise<void> {
    await this.eventPublisher.publish({
      type: 'order.cancelled',
      aggregateId: order.id,
      aggregateType: 'Order',
      payload: {
        orderId: order.id,
        reason,
        filledQuantity: order.filledQuantity,
        cancelledQuantity: order.remainingQuantity,
      },
      metadata: { tenantId: order.tenantId },
    });
  }

  /**
   * Get modification history for an order
   */
  async getModificationHistory(orderId: string): Promise<OrderModificationEntity[]> {
    return this.modificationRepo.find({
      where: { orderId },
      order: { requestedAt: 'DESC' },
    });
  }
}
```

### Bulk Cancellation Service

```typescript
// services/order-service/src/modification/bulk-cancellation.service.ts
import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, In } from 'typeorm';
import { logger, metrics } from '@orion/observability';
import { OrderEntity, OrderStatus } from '../entities/order.entity';
import { OrderModificationService } from './order-modification.service';

export interface BulkCancelResult {
  totalRequested: number;
  cancelled: string[];
  failed: { orderId: string; reason: string }[];
}

@Injectable()
export class BulkCancellationService {
  constructor(
    @InjectRepository(OrderEntity)
    private readonly orderRepo: Repository<OrderEntity>,
    private readonly modificationService: OrderModificationService,
  ) {}

  /**
   * Cancel multiple orders by IDs
   */
  async cancelOrders(
    orderIds: string[],
    userId: string,
    reason?: string,
  ): Promise<BulkCancelResult> {
    const result: BulkCancelResult = {
      totalRequested: orderIds.length,
      cancelled: [],
      failed: [],
    };

    for (const orderId of orderIds) {
      try {
        await this.modificationService.cancelOrder({ orderId, reason }, userId);
        result.cancelled.push(orderId);
      } catch (error) {
        result.failed.push({ orderId, reason: error.message });
      }
    }

    logger.info('Bulk cancellation completed', result);
    metrics.increment('order.bulk_cancel', { count: result.cancelled.length });

    return result;
  }

  /**
   * Cancel all working orders for a client
   */
  async cancelAllForClient(
    clientId: string,
    userId: string,
    reason?: string,
  ): Promise<BulkCancelResult> {
    const workingOrders = await this.orderRepo.find({
      where: {
        clientId,
        status: In([
          OrderStatus.PENDING,
          OrderStatus.VALIDATED,
          OrderStatus.WORKING,
          OrderStatus.PARTIAL_FILL,
        ]),
      },
    });

    const orderIds = workingOrders.map(o => o.id);
    return this.cancelOrders(orderIds, userId, reason || `Bulk cancel for client ${clientId}`);
  }

  /**
   * Cancel all working orders for an instrument
   */
  async cancelAllForInstrument(
    instrumentId: string,
    userId: string,
    reason?: string,
  ): Promise<BulkCancelResult> {
    const workingOrders = await this.orderRepo.find({
      where: {
        instrumentId,
        status: In([
          OrderStatus.PENDING,
          OrderStatus.VALIDATED,
          OrderStatus.WORKING,
          OrderStatus.PARTIAL_FILL,
        ]),
      },
    });

    const orderIds = workingOrders.map(o => o.id);
    return this.cancelOrders(orderIds, userId, reason || `Bulk cancel for instrument ${instrumentId}`);
  }
}
```

## Database Schema

```sql
-- Order modifications table
CREATE TABLE order_modifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id),
    modification_type VARCHAR(50) NOT NULL,
    previous_values JSONB NOT NULL,
    new_values JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    rejection_reason VARCHAR(500),
    requested_by VARCHAR(100) NOT NULL,
    requested_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_order_modifications_order ON order_modifications(order_id, requested_at DESC);
CREATE INDEX idx_order_modifications_status ON order_modifications(status);

-- Add replacement reference to orders
ALTER TABLE orders ADD COLUMN replaces_order_id UUID REFERENCES orders(id);
CREATE INDEX idx_orders_replaces ON orders(replaces_order_id);
```

## Definition of Done

- [ ] Cancel pending orders
- [ ] Cancel working orders
- [ ] Modify price
- [ ] Modify quantity
- [ ] Cancel-replace workflow
- [ ] LP amendment propagation
- [ ] Bulk cancellation
- [ ] Modification history
- [ ] Event publishing
- [ ] Concurrent modification handling

## Dependencies

- **US-09-04**: Order State Machine
- **US-09-05**: Order Routing Service

## Test Cases

```typescript
describe('OrderModificationService', () => {
  it('should modify order price', async () => {
    const order = createWorkingOrder({ price: 1.0850 });

    await modificationService.modifyOrder({
      orderId: order.id,
      price: 1.0840,
    }, 'user-1');

    const updated = await orderRepo.findOne(order.id);
    expect(updated.price).toBe(1.0840);
  });

  it('should reject quantity reduction below filled amount', async () => {
    const order = createWorkingOrder({ quantity: 1000000, filledQuantity: 500000 });

    await expect(
      modificationService.modifyOrder({
        orderId: order.id,
        quantity: 400000, // Less than filled
      }, 'user-1'),
    ).rejects.toThrow('Cannot reduce quantity below filled amount');
  });

  it('should cancel working order and notify LP', async () => {
    const order = createWorkingOrder();

    await modificationService.cancelOrder({ orderId: order.id }, 'user-1');

    expect(order.status).toBe(OrderStatus.CANCELLED);
    expect(routingService.cancelWorkingOrders).toHaveBeenCalled();
  });

  it('should not cancel filled order', async () => {
    const order = createOrder({ status: OrderStatus.FILLED });

    await expect(
      modificationService.cancelOrder({ orderId: order.id }, 'user-1'),
    ).rejects.toThrow('cannot be cancelled');
  });

  it('should perform cancel-replace atomically', async () => {
    const originalOrder = createWorkingOrder();

    const { cancelledOrder, newOrder } = await modificationService.cancelReplace(
      originalOrder.id,
      { price: 1.0840 },
      'user-1',
    );

    expect(cancelledOrder.status).toBe(OrderStatus.CANCELLED);
    expect(newOrder.replacesOrderId).toBe(originalOrder.id);
  });

  it('should track modification history', async () => {
    const order = createWorkingOrder();

    await modificationService.modifyOrder({ orderId: order.id, price: 1.0840 }, 'user-1');
    await modificationService.modifyOrder({ orderId: order.id, price: 1.0830 }, 'user-1');

    const history = await modificationService.getModificationHistory(order.id);
    expect(history.length).toBe(2);
  });
});
```
