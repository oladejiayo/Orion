# User Story: US-09-06 - Fill Processing Engine

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-09-06 |
| **Epic** | Epic 09 - OMS Orders V1 |
| **Title** | Fill Processing Engine |
| **Priority** | P0 - Critical |
| **Story Points** | 8 |
| **PRD Reference** | FR-Order-06, NFR-Performance-02 |

## User Story

**As a** trading platform  
**I want** to process execution reports from LPs  
**So that** orders are updated with fill information and trades are created

## Description

Implement fill processing engine that handles execution reports from LPs, updates order fill quantities, calculates average price, creates trades, and manages partial/complete fill scenarios.

## Acceptance Criteria

- [ ] Process execution reports from LPs
- [ ] Create OrderFill records
- [ ] Update order filled/remaining quantities
- [ ] Calculate weighted average price
- [ ] Handle partial fills
- [ ] Handle complete fills
- [ ] Create trades from fills
- [ ] Idempotent fill processing
- [ ] Real-time fill notifications

## Technical Details

### Fill Processing Interface

```typescript
// services/order-service/src/fills/fill-processor.interface.ts
export interface ExecutionReport {
  lpId: string;
  lpOrderId: string;
  lpFillId: string;
  instrumentId: string;
  side: 'buy' | 'sell';
  quantity: number;
  price: number;
  executedAt: Date;
  status: 'partial_fill' | 'fill' | 'cancelled' | 'rejected';
  rejectionReason?: string;
  metadata?: any;
}

export interface FillResult {
  fillId: string;
  orderId: string;
  tradeId?: string;
  remainingQuantity: number;
  averagePrice: number;
  isComplete: boolean;
}
```

### Order Fill Entity

```typescript
// services/order-service/src/entities/order-fill.entity.ts
import { Entity, Column, ManyToOne, CreateDateColumn, Index, JoinColumn } from 'typeorm';
import { OrderEntity } from './order.entity';

@Entity('order_fills')
@Index(['orderId', 'createdAt'])
@Index(['lpId', 'lpFillId'], { unique: true })
export class OrderFillEntity {
  @Column('uuid', { primaryKey: true, default: () => 'gen_random_uuid()' })
  id: string;

  @Column('uuid')
  orderId: string;

  @ManyToOne(() => OrderEntity, order => order.fills)
  @JoinColumn({ name: 'order_id' })
  order: OrderEntity;

  @Column('varchar', { length: 30 })
  fillRef: string;

  @Column('varchar', { length: 50 })
  lpId: string;

  @Column('varchar', { length: 100 })
  lpOrderId: string;

  @Column('varchar', { length: 100 })
  lpFillId: string;

  @Column('decimal', { precision: 20, scale: 8 })
  quantity: number;

  @Column('decimal', { precision: 20, scale: 10 })
  price: number;

  @Column('timestamp with time zone')
  executedAt: Date;

  @Column('uuid', { nullable: true })
  tradeId: string;

  @Column('jsonb', { nullable: true })
  metadata: any;

  @CreateDateColumn()
  createdAt: Date;
}
```

### Fill Processing Service

```typescript
// services/order-service/src/fills/fill-processor.service.ts
import { Injectable } from '@nestjs/common';
import { InjectRepository, InjectEntityManager } from '@nestjs/typeorm';
import { Repository, EntityManager } from 'typeorm';
import { logger, metrics } from '@orion/observability';
import { ExecutionReport, FillResult } from './fill-processor.interface';
import { OrderFillEntity } from '../entities/order-fill.entity';
import { OrderEntity, OrderStatus } from '../entities/order.entity';
import { OrderStateMachineService } from '../state-machine/order-state-machine.service';
import { TradeCreationService } from '@orion/trade-service';
import { generateReference } from '@orion/common';
import { EventPublisher } from '@orion/events';

@Injectable()
export class FillProcessorService {
  constructor(
    @InjectRepository(OrderEntity)
    private readonly orderRepo: Repository<OrderEntity>,
    @InjectRepository(OrderFillEntity)
    private readonly fillRepo: Repository<OrderFillEntity>,
    @InjectEntityManager()
    private readonly entityManager: EntityManager,
    private readonly stateMachine: OrderStateMachineService,
    private readonly tradeService: TradeCreationService,
    private readonly eventPublisher: EventPublisher,
  ) {}

  async processExecutionReport(report: ExecutionReport): Promise<FillResult> {
    const startTime = Date.now();

    // Use transaction for atomicity
    return this.entityManager.transaction(async (manager) => {
      // Check for duplicate fill (idempotency)
      const existingFill = await manager.findOne(OrderFillEntity, {
        where: { lpId: report.lpId, lpFillId: report.lpFillId },
      });

      if (existingFill) {
        logger.info('Duplicate fill ignored', { lpFillId: report.lpFillId });
        return {
          fillId: existingFill.id,
          orderId: existingFill.orderId,
          tradeId: existingFill.tradeId,
          remainingQuantity: 0, // Would need to fetch order
          averagePrice: 0,
          isComplete: false,
        };
      }

      // Find order by LP order ID
      const order = await manager.findOne(OrderEntity, {
        where: { workingOrderIds: JSON.stringify([report.lpOrderId]) },
        lock: { mode: 'pessimistic_write' },
      });

      if (!order) {
        // Try finding by LP ID and client order ref in metadata
        throw new Error(`Order not found for LP order ID: ${report.lpOrderId}`);
      }

      // Handle based on status
      if (report.status === 'rejected') {
        return this.handleRejection(manager, order, report);
      }

      if (report.status === 'cancelled') {
        return this.handleCancellation(manager, order, report);
      }

      // Process fill
      return this.processFill(manager, order, report);
    }).finally(() => {
      metrics.timing('fill.processing.time', Date.now() - startTime);
    });
  }

  private async processFill(
    manager: EntityManager,
    order: OrderEntity,
    report: ExecutionReport,
  ): Promise<FillResult> {
    // Create fill record
    const fill = manager.create(OrderFillEntity, {
      orderId: order.id,
      fillRef: generateReference('FL'),
      lpId: report.lpId,
      lpOrderId: report.lpOrderId,
      lpFillId: report.lpFillId,
      quantity: report.quantity,
      price: report.price,
      executedAt: report.executedAt,
      metadata: report.metadata,
    });

    await manager.save(fill);

    // Update order quantities
    const previousFilled = Number(order.filledQuantity);
    const newFilled = previousFilled + report.quantity;
    const orderQuantity = Number(order.quantity);
    const remaining = orderQuantity - newFilled;

    // Calculate weighted average price
    const previousTotal = previousFilled * Number(order.averagePrice || 0);
    const fillTotal = report.quantity * report.price;
    const newAveragePrice = (previousTotal + fillTotal) / newFilled;

    order.filledQuantity = newFilled;
    order.remainingQuantity = remaining;
    order.averagePrice = newAveragePrice;

    // Determine if complete fill
    const isComplete = remaining <= 0;

    if (isComplete) {
      await this.stateMachine.transition(order, OrderStatus.FILLED, {}, manager);
    } else {
      await this.stateMachine.transition(order, OrderStatus.PARTIAL_FILL, {}, manager);
    }

    await manager.save(order);

    // Create trade
    const trade = await this.createTradeFromFill(manager, order, fill);
    fill.tradeId = trade.id;
    await manager.save(fill);

    // Publish fill event
    await this.publishFillEvent(order, fill, isComplete);

    logger.info('Fill processed', {
      orderId: order.id,
      fillId: fill.id,
      quantity: report.quantity,
      price: report.price,
      filledQuantity: newFilled,
      remainingQuantity: remaining,
      isComplete,
    });

    metrics.increment('order.fill.processed');

    return {
      fillId: fill.id,
      orderId: order.id,
      tradeId: trade.id,
      remainingQuantity: remaining,
      averagePrice: newAveragePrice,
      isComplete,
    };
  }

  private async createTradeFromFill(
    manager: EntityManager,
    order: OrderEntity,
    fill: OrderFillEntity,
  ): Promise<{ id: string }> {
    const trade = await this.tradeService.createTradeInTransaction(manager, {
      tenantId: order.tenantId,
      instrumentId: order.instrumentId,
      side: order.side,
      quantity: fill.quantity,
      price: fill.price,
      orderId: order.id,
      clientId: order.clientId,
      counterpartyId: fill.lpId,
      tradeDate: fill.executedAt,
      settlementDate: await this.calculateSettlementDate(order.instrumentId, fill.executedAt),
      source: 'order_fill',
    });

    return trade;
  }

  private async calculateSettlementDate(instrumentId: string, tradeDate: Date): Promise<Date> {
    // Simplified - would lookup instrument settlement convention
    const settlementDate = new Date(tradeDate);
    settlementDate.setDate(settlementDate.getDate() + 2); // T+2
    return settlementDate;
  }

  private async handleRejection(
    manager: EntityManager,
    order: OrderEntity,
    report: ExecutionReport,
  ): Promise<FillResult> {
    await this.stateMachine.transition(order, OrderStatus.REJECTED, {
      reason: report.rejectionReason,
    }, manager);

    logger.warn('Order rejected by LP', {
      orderId: order.id,
      lpId: report.lpId,
      reason: report.rejectionReason,
    });

    return {
      fillId: '',
      orderId: order.id,
      remainingQuantity: Number(order.remainingQuantity),
      averagePrice: Number(order.averagePrice) || 0,
      isComplete: true,
    };
  }

  private async handleCancellation(
    manager: EntityManager,
    order: OrderEntity,
    report: ExecutionReport,
  ): Promise<FillResult> {
    // Only cancel if no fills
    if (Number(order.filledQuantity) === 0) {
      await this.stateMachine.transition(order, OrderStatus.CANCELLED, {}, manager);
    }

    logger.info('LP cancelled order', {
      orderId: order.id,
      lpId: report.lpId,
    });

    return {
      fillId: '',
      orderId: order.id,
      remainingQuantity: Number(order.remainingQuantity),
      averagePrice: Number(order.averagePrice) || 0,
      isComplete: Number(order.filledQuantity) === 0,
    };
  }

  private async publishFillEvent(
    order: OrderEntity,
    fill: OrderFillEntity,
    isComplete: boolean,
  ): Promise<void> {
    await this.eventPublisher.publish({
      type: isComplete ? 'order.filled' : 'order.partial_fill',
      aggregateId: order.id,
      aggregateType: 'Order',
      payload: {
        orderId: order.id,
        fillId: fill.id,
        tradeId: fill.tradeId,
        quantity: fill.quantity,
        price: fill.price,
        filledQuantity: order.filledQuantity,
        remainingQuantity: order.remainingQuantity,
        averagePrice: order.averagePrice,
      },
      metadata: {
        tenantId: order.tenantId,
      },
    });
  }

  /**
   * Get all fills for an order
   */
  async getOrderFills(orderId: string): Promise<OrderFillEntity[]> {
    return this.fillRepo.find({
      where: { orderId },
      order: { executedAt: 'ASC' },
    });
  }
}
```

### Fill Aggregator for Multiple LP Fills

```typescript
// services/order-service/src/fills/fill-aggregator.service.ts
import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { OrderFillEntity } from '../entities/order-fill.entity';
import { OrderEntity } from '../entities/order.entity';

export interface AggregatedFills {
  orderId: string;
  totalFilledQuantity: number;
  weightedAveragePrice: number;
  fillCount: number;
  fillsByLp: Record<string, { quantity: number; averagePrice: number; fillCount: number }>;
  firstFillTime: Date;
  lastFillTime: Date;
}

@Injectable()
export class FillAggregatorService {
  constructor(
    @InjectRepository(OrderFillEntity)
    private readonly fillRepo: Repository<OrderFillEntity>,
  ) {}

  async aggregateFills(orderId: string): Promise<AggregatedFills> {
    const fills = await this.fillRepo.find({
      where: { orderId },
      order: { executedAt: 'ASC' },
    });

    if (fills.length === 0) {
      return {
        orderId,
        totalFilledQuantity: 0,
        weightedAveragePrice: 0,
        fillCount: 0,
        fillsByLp: {},
        firstFillTime: new Date(),
        lastFillTime: new Date(),
      };
    }

    let totalQuantity = 0;
    let totalValue = 0;
    const fillsByLp: Record<string, { quantity: number; totalValue: number; fillCount: number }> = {};

    for (const fill of fills) {
      const quantity = Number(fill.quantity);
      const price = Number(fill.price);

      totalQuantity += quantity;
      totalValue += quantity * price;

      if (!fillsByLp[fill.lpId]) {
        fillsByLp[fill.lpId] = { quantity: 0, totalValue: 0, fillCount: 0 };
      }
      fillsByLp[fill.lpId].quantity += quantity;
      fillsByLp[fill.lpId].totalValue += quantity * price;
      fillsByLp[fill.lpId].fillCount += 1;
    }

    // Calculate averages
    const result: AggregatedFills = {
      orderId,
      totalFilledQuantity: totalQuantity,
      weightedAveragePrice: totalQuantity > 0 ? totalValue / totalQuantity : 0,
      fillCount: fills.length,
      fillsByLp: {},
      firstFillTime: fills[0].executedAt,
      lastFillTime: fills[fills.length - 1].executedAt,
    };

    for (const [lpId, data] of Object.entries(fillsByLp)) {
      result.fillsByLp[lpId] = {
        quantity: data.quantity,
        averagePrice: data.totalValue / data.quantity,
        fillCount: data.fillCount,
      };
    }

    return result;
  }

  /**
   * Calculate slippage from order price to fill price
   */
  async calculateSlippage(order: OrderEntity, fills: OrderFillEntity[]): Promise<number> {
    if (!order.price || fills.length === 0) return 0;

    const totalQuantity = fills.reduce((sum, f) => sum + Number(f.quantity), 0);
    const totalValue = fills.reduce((sum, f) => sum + Number(f.quantity) * Number(f.price), 0);
    const avgFillPrice = totalValue / totalQuantity;

    const expectedPrice = Number(order.price);

    if (order.side === 'buy') {
      // Positive slippage = paid more than expected
      return avgFillPrice - expectedPrice;
    } else {
      // Positive slippage = received less than expected
      return expectedPrice - avgFillPrice;
    }
  }
}
```

### Execution Report Consumer (Kafka)

```typescript
// services/order-service/src/fills/execution-report.consumer.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { ConsumerService, Message } from '@orion/kafka';
import { logger, metrics } from '@orion/observability';
import { FillProcessorService } from './fill-processor.service';
import { ExecutionReport } from './fill-processor.interface';

@Injectable()
export class ExecutionReportConsumer implements OnModuleInit {
  private readonly topic = 'lp.execution.reports';

  constructor(
    private readonly consumer: ConsumerService,
    private readonly fillProcessor: FillProcessorService,
  ) {}

  async onModuleInit(): Promise<void> {
    await this.consumer.subscribe({
      topic: this.topic,
      groupId: 'order-service-fills',
      handler: this.handleMessage.bind(this),
    });
  }

  private async handleMessage(message: Message): Promise<void> {
    const report: ExecutionReport = JSON.parse(message.value.toString());

    try {
      await this.fillProcessor.processExecutionReport(report);
    } catch (error) {
      logger.error('Failed to process execution report', {
        report,
        error,
      });

      // Don't throw - message will be marked as processed
      // Errors should be sent to dead letter topic
      await this.sendToDeadLetter(message, error);
    }
  }

  private async sendToDeadLetter(message: Message, error: Error): Promise<void> {
    // Implementation for dead letter handling
    metrics.increment('fill.processing.dead_letter');
  }
}
```

## Database Schema

```sql
-- Order fills table
CREATE TABLE order_fills (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id),
    fill_ref VARCHAR(30) NOT NULL,
    lp_id VARCHAR(50) NOT NULL,
    lp_order_id VARCHAR(100) NOT NULL,
    lp_fill_id VARCHAR(100) NOT NULL,
    quantity DECIMAL(20, 8) NOT NULL,
    price DECIMAL(20, 10) NOT NULL,
    executed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    trade_id UUID,
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(lp_id, lp_fill_id)
);

CREATE INDEX idx_order_fills_order_id ON order_fills(order_id, created_at);
CREATE INDEX idx_order_fills_lp ON order_fills(lp_id, lp_fill_id);
CREATE INDEX idx_order_fills_trade ON order_fills(trade_id);

-- Partitioned fill archive (if high volume)
CREATE TABLE order_fills_archive (
    LIKE order_fills INCLUDING ALL
) PARTITION BY RANGE (created_at);

-- Create monthly partitions
CREATE TABLE order_fills_archive_2024_01 PARTITION OF order_fills_archive
    FOR VALUES FROM ('2024-01-01') TO ('2024-02-01');
```

## Definition of Done

- [ ] Process execution reports
- [ ] Create fill records
- [ ] Update order quantities
- [ ] Calculate average price
- [ ] Handle partial fills
- [ ] Create trades from fills
- [ ] Idempotent processing
- [ ] Fill aggregation
- [ ] Slippage calculation
- [ ] Kafka consumer integration

## Dependencies

- **US-08-02**: Trade Creation Service
- **US-09-04**: Order State Machine

## Test Cases

```typescript
describe('FillProcessorService', () => {
  it('should process partial fill and update quantities', async () => {
    const order = createOrder({ quantity: 1000000, filledQuantity: 0 });
    const report = createExecutionReport({ quantity: 500000, price: 1.0850 });

    const result = await fillProcessor.processExecutionReport(report);

    expect(result.isComplete).toBe(false);
    expect(result.remainingQuantity).toBe(500000);
    expect(order.status).toBe(OrderStatus.PARTIAL_FILL);
  });

  it('should process complete fill and transition to FILLED', async () => {
    const order = createOrder({ quantity: 1000000, filledQuantity: 500000 });
    const report = createExecutionReport({ quantity: 500000, price: 1.0850 });

    const result = await fillProcessor.processExecutionReport(report);

    expect(result.isComplete).toBe(true);
    expect(result.remainingQuantity).toBe(0);
  });

  it('should calculate weighted average price correctly', async () => {
    const order = createOrder({ quantity: 1000000 });

    // First fill
    await fillProcessor.processExecutionReport({
      quantity: 400000,
      price: 1.0840,
    });

    // Second fill
    await fillProcessor.processExecutionReport({
      quantity: 600000,
      price: 1.0860,
    });

    const expectedAvg = (400000 * 1.0840 + 600000 * 1.0860) / 1000000;
    expect(order.averagePrice).toBeCloseTo(expectedAvg, 10);
  });

  it('should be idempotent for duplicate fills', async () => {
    const report = createExecutionReport({ lpFillId: 'fill-123' });

    await fillProcessor.processExecutionReport(report);
    const result = await fillProcessor.processExecutionReport(report);

    const fills = await fillRepo.find({ where: { lpFillId: 'fill-123' } });
    expect(fills.length).toBe(1);
  });

  it('should create trade from fill', async () => {
    const order = createOrder();
    const report = createExecutionReport({ quantity: 100000, price: 1.0850 });

    const result = await fillProcessor.processExecutionReport(report);

    expect(result.tradeId).toBeDefined();
    expect(tradeService.createTradeInTransaction).toHaveBeenCalled();
  });
});
```
