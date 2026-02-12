# User Story: US-09-02 - Order Types Support

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-09-02 |
| **Epic** | Epic 09 - OMS Orders V1 |
| **Title** | Order Types Support |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |
| **PRD Reference** | FR-Order-02, NFR-Flexibility-01 |

## User Story

**As a** trader  
**I want** multiple order types available  
**So that** I can execute different trading strategies

## Description

Implement support for all standard order types: market, limit, stop, stop-limit, pegged, and trailing stop orders, with proper price triggers and time-in-force handling.

## Acceptance Criteria

- [ ] Market order execution at best available price
- [ ] Limit orders with price constraints
- [ ] Stop orders with trigger logic
- [ ] Stop-limit orders
- [ ] Pegged orders (mid, bid, ask)
- [ ] Trailing stop orders
- [ ] All time-in-force options (GTC, DAY, IOC, FOK, GTD)
- [ ] Order type validation

## Technical Details

### Order Type DTOs

```typescript
// services/order-service/src/dto/create-order.dto.ts
import { IsString, IsNumber, IsOptional, IsUUID, IsEnum, IsDateString, ValidateIf, Min, Max } from 'class-validator';
import { OrderType, TimeInForce } from '../entities/order.entity';

export class CreateOrderDto {
  @IsOptional()
  @IsString()
  clientOrderId?: string;

  @IsUUID()
  clientId: string;

  @IsUUID()
  instrumentId: string;

  @IsEnum(['buy', 'sell'])
  side: 'buy' | 'sell';

  @IsEnum(OrderType)
  orderType: OrderType;

  @IsOptional()
  @IsEnum(TimeInForce)
  timeInForce?: TimeInForce;

  @IsNumber()
  @Min(0)
  quantity: number;

  // Required for limit orders
  @ValidateIf(o => [OrderType.LIMIT, OrderType.STOP_LIMIT].includes(o.orderType))
  @IsNumber()
  @Min(0)
  price?: number;

  // Required for stop orders
  @ValidateIf(o => [OrderType.STOP, OrderType.STOP_LIMIT, OrderType.TRAILING_STOP].includes(o.orderType))
  @IsNumber()
  @Min(0)
  stopPrice?: number;

  // For pegged orders
  @ValidateIf(o => o.orderType === OrderType.PEGGED)
  @IsEnum(['mid', 'bid', 'ask'])
  pegType?: 'mid' | 'bid' | 'ask';

  @ValidateIf(o => o.orderType === OrderType.PEGGED)
  @IsNumber()
  pegOffset?: number;

  // For trailing stop
  @ValidateIf(o => o.orderType === OrderType.TRAILING_STOP)
  @IsNumber()
  @Min(0)
  trailingAmount?: number;

  @ValidateIf(o => o.orderType === OrderType.TRAILING_STOP)
  @IsEnum(['absolute', 'percentage'])
  trailingType?: 'absolute' | 'percentage';

  // For iceberg orders
  @IsOptional()
  @IsNumber()
  @Min(0)
  displayQuantity?: number;

  // For GTD orders
  @ValidateIf(o => o.timeInForce === TimeInForce.GTD)
  @IsDateString()
  expiresAt?: string;

  // Routing
  @IsOptional()
  @IsEnum(['best', 'split', 'sequential', 'manual'])
  routingStrategy?: string;

  @IsOptional()
  @IsUUID('4', { each: true })
  preferredLps?: string[];

  // Client metadata
  @IsOptional()
  clientMetadata?: Record<string, any>;
}
```

### Order Type Handlers

```typescript
// services/order-service/src/handlers/order-type-handler.interface.ts

export interface OrderTypeHandler {
  orderType: OrderType;
  
  validate(order: OrderEntity): ValidationResult;
  prepareForRouting(order: OrderEntity, marketData: any): RoutingParams;
  handleTrigger(order: OrderEntity, marketData: any): TriggerResult;
  calculateEffectivePrice(order: OrderEntity, marketData: any): number;
}

export interface ValidationResult {
  valid: boolean;
  errors: string[];
}

export interface RoutingParams {
  limitPrice?: number;
  urgency: 'low' | 'medium' | 'high';
  priceType: 'limit' | 'market';
}

export interface TriggerResult {
  triggered: boolean;
  newOrderType?: OrderType;
  limitPrice?: number;
}
```

### Market Order Handler

```typescript
// services/order-service/src/handlers/market-order.handler.ts
import { Injectable } from '@nestjs/common';
import { OrderTypeHandler, ValidationResult, RoutingParams, TriggerResult } from './order-type-handler.interface';
import { OrderEntity, OrderType } from '../entities/order.entity';

@Injectable()
export class MarketOrderHandler implements OrderTypeHandler {
  orderType = OrderType.MARKET;

  validate(order: OrderEntity): ValidationResult {
    const errors: string[] = [];

    // Market orders should not have limit price
    if (order.price && order.price > 0) {
      errors.push('Market orders should not have a limit price');
    }

    // Market orders must be IOC or FOK
    if (!['ioc', 'fok', 'day'].includes(order.timeInForce)) {
      errors.push('Market orders must be IOC, FOK, or DAY');
    }

    return { valid: errors.length === 0, errors };
  }

  prepareForRouting(order: OrderEntity, marketData: any): RoutingParams {
    return {
      urgency: 'high',
      priceType: 'market',
    };
  }

  handleTrigger(order: OrderEntity, marketData: any): TriggerResult {
    // Market orders are always triggered
    return { triggered: true };
  }

  calculateEffectivePrice(order: OrderEntity, marketData: any): number {
    // Use best available price
    return order.side === 'buy' ? marketData.ask : marketData.bid;
  }
}
```

### Limit Order Handler

```typescript
// services/order-service/src/handlers/limit-order.handler.ts
import { Injectable } from '@nestjs/common';
import { OrderTypeHandler, ValidationResult, RoutingParams, TriggerResult } from './order-type-handler.interface';
import { OrderEntity, OrderType } from '../entities/order.entity';

@Injectable()
export class LimitOrderHandler implements OrderTypeHandler {
  orderType = OrderType.LIMIT;

  validate(order: OrderEntity): ValidationResult {
    const errors: string[] = [];

    if (!order.price || order.price <= 0) {
      errors.push('Limit orders require a positive price');
    }

    return { valid: errors.length === 0, errors };
  }

  prepareForRouting(order: OrderEntity, marketData: any): RoutingParams {
    const marketPrice = order.side === 'buy' ? marketData.ask : marketData.bid;
    const isAggressive = order.side === 'buy' 
      ? order.price! >= marketPrice
      : order.price! <= marketPrice;

    return {
      limitPrice: order.price,
      urgency: isAggressive ? 'high' : 'low',
      priceType: 'limit',
    };
  }

  handleTrigger(order: OrderEntity, marketData: any): TriggerResult {
    // Limit orders are always active
    return { triggered: true };
  }

  calculateEffectivePrice(order: OrderEntity, marketData: any): number {
    return order.price!;
  }
}
```

### Stop Order Handler

```typescript
// services/order-service/src/handlers/stop-order.handler.ts
import { Injectable } from '@nestjs/common';
import { OrderTypeHandler, ValidationResult, RoutingParams, TriggerResult } from './order-type-handler.interface';
import { OrderEntity, OrderType } from '../entities/order.entity';

@Injectable()
export class StopOrderHandler implements OrderTypeHandler {
  orderType = OrderType.STOP;

  validate(order: OrderEntity): ValidationResult {
    const errors: string[] = [];

    if (!order.stopPrice || order.stopPrice <= 0) {
      errors.push('Stop orders require a positive stop price');
    }

    return { valid: errors.length === 0, errors };
  }

  prepareForRouting(order: OrderEntity, marketData: any): RoutingParams {
    return {
      urgency: 'high', // Once triggered, execute immediately
      priceType: 'market',
    };
  }

  handleTrigger(order: OrderEntity, marketData: any): TriggerResult {
    const currentPrice = order.side === 'buy' ? marketData.ask : marketData.bid;
    
    let triggered = false;
    if (order.side === 'buy') {
      // Buy stop triggers when price rises to/above stop
      triggered = currentPrice >= order.stopPrice!;
    } else {
      // Sell stop triggers when price falls to/below stop
      triggered = currentPrice <= order.stopPrice!;
    }

    return {
      triggered,
      newOrderType: triggered ? OrderType.MARKET : undefined,
    };
  }

  calculateEffectivePrice(order: OrderEntity, marketData: any): number {
    return order.side === 'buy' ? marketData.ask : marketData.bid;
  }
}
```

### Stop-Limit Order Handler

```typescript
// services/order-service/src/handlers/stop-limit-order.handler.ts
import { Injectable } from '@nestjs/common';
import { OrderTypeHandler, ValidationResult, RoutingParams, TriggerResult } from './order-type-handler.interface';
import { OrderEntity, OrderType } from '../entities/order.entity';

@Injectable()
export class StopLimitOrderHandler implements OrderTypeHandler {
  orderType = OrderType.STOP_LIMIT;

  validate(order: OrderEntity): ValidationResult {
    const errors: string[] = [];

    if (!order.stopPrice || order.stopPrice <= 0) {
      errors.push('Stop-limit orders require a positive stop price');
    }

    if (!order.price || order.price <= 0) {
      errors.push('Stop-limit orders require a positive limit price');
    }

    // Validate price relationship
    if (order.stopPrice && order.price) {
      if (order.side === 'buy' && order.price < order.stopPrice) {
        errors.push('Buy stop-limit: limit price should be >= stop price');
      }
      if (order.side === 'sell' && order.price > order.stopPrice) {
        errors.push('Sell stop-limit: limit price should be <= stop price');
      }
    }

    return { valid: errors.length === 0, errors };
  }

  prepareForRouting(order: OrderEntity, marketData: any): RoutingParams {
    return {
      limitPrice: order.price,
      urgency: 'medium',
      priceType: 'limit',
    };
  }

  handleTrigger(order: OrderEntity, marketData: any): TriggerResult {
    const currentPrice = order.side === 'buy' ? marketData.ask : marketData.bid;
    
    let triggered = false;
    if (order.side === 'buy') {
      triggered = currentPrice >= order.stopPrice!;
    } else {
      triggered = currentPrice <= order.stopPrice!;
    }

    return {
      triggered,
      newOrderType: triggered ? OrderType.LIMIT : undefined,
      limitPrice: order.price,
    };
  }

  calculateEffectivePrice(order: OrderEntity, marketData: any): number {
    return order.price!;
  }
}
```

### Pegged Order Handler

```typescript
// services/order-service/src/handlers/pegged-order.handler.ts
import { Injectable } from '@nestjs/common';
import { OrderTypeHandler, ValidationResult, RoutingParams, TriggerResult } from './order-type-handler.interface';
import { OrderEntity, OrderType } from '../entities/order.entity';

@Injectable()
export class PeggedOrderHandler implements OrderTypeHandler {
  orderType = OrderType.PEGGED;

  validate(order: OrderEntity): ValidationResult {
    const errors: string[] = [];

    const pegType = order.metadata?.pegType;
    if (!pegType || !['mid', 'bid', 'ask'].includes(pegType)) {
      errors.push('Pegged orders require pegType: mid, bid, or ask');
    }

    if (order.pegOffset === undefined) {
      errors.push('Pegged orders require pegOffset');
    }

    return { valid: errors.length === 0, errors };
  }

  prepareForRouting(order: OrderEntity, marketData: any): RoutingParams {
    const effectivePrice = this.calculateEffectivePrice(order, marketData);

    return {
      limitPrice: effectivePrice,
      urgency: 'medium',
      priceType: 'limit',
    };
  }

  handleTrigger(order: OrderEntity, marketData: any): TriggerResult {
    return { triggered: true };
  }

  calculateEffectivePrice(order: OrderEntity, marketData: any): number {
    const pegType = order.metadata?.pegType;
    const offset = order.pegOffset || 0;

    let referencePrice: number;
    switch (pegType) {
      case 'mid':
        referencePrice = (marketData.bid + marketData.ask) / 2;
        break;
      case 'bid':
        referencePrice = marketData.bid;
        break;
      case 'ask':
        referencePrice = marketData.ask;
        break;
      default:
        referencePrice = marketData.mid;
    }

    // Apply offset (positive offset = more aggressive for buys)
    if (order.side === 'buy') {
      return referencePrice + offset;
    } else {
      return referencePrice - offset;
    }
  }
}
```

### Trailing Stop Handler

```typescript
// services/order-service/src/handlers/trailing-stop-order.handler.ts
import { Injectable } from '@nestjs/common';
import { OrderTypeHandler, ValidationResult, RoutingParams, TriggerResult } from './order-type-handler.interface';
import { OrderEntity, OrderType } from '../entities/order.entity';

@Injectable()
export class TrailingStopOrderHandler implements OrderTypeHandler {
  orderType = OrderType.TRAILING_STOP;

  validate(order: OrderEntity): ValidationResult {
    const errors: string[] = [];

    if (!order.trailingAmount || order.trailingAmount <= 0) {
      errors.push('Trailing stop orders require a positive trailing amount');
    }

    const trailingType = order.metadata?.trailingType;
    if (trailingType === 'percentage' && order.trailingAmount > 50) {
      errors.push('Trailing percentage should not exceed 50%');
    }

    return { valid: errors.length === 0, errors };
  }

  prepareForRouting(order: OrderEntity, marketData: any): RoutingParams {
    return {
      urgency: 'high',
      priceType: 'market',
    };
  }

  handleTrigger(order: OrderEntity, marketData: any): TriggerResult {
    const currentPrice = order.side === 'buy' ? marketData.ask : marketData.bid;
    const trailingType = order.metadata?.trailingType || 'absolute';
    
    // Get or initialize extreme price
    let extremePrice = order.metadata?.extremePrice;
    const isFirstCheck = !extremePrice;

    // Update extreme price
    if (order.side === 'sell') {
      // For sell trailing stop, track highest price
      extremePrice = Math.max(extremePrice || currentPrice, currentPrice);
    } else {
      // For buy trailing stop, track lowest price
      extremePrice = Math.min(extremePrice || currentPrice, currentPrice);
    }

    // Calculate trigger price
    let triggerPrice: number;
    if (trailingType === 'percentage') {
      const trailPercent = order.trailingAmount! / 100;
      if (order.side === 'sell') {
        triggerPrice = extremePrice * (1 - trailPercent);
      } else {
        triggerPrice = extremePrice * (1 + trailPercent);
      }
    } else {
      if (order.side === 'sell') {
        triggerPrice = extremePrice - order.trailingAmount!;
      } else {
        triggerPrice = extremePrice + order.trailingAmount!;
      }
    }

    // Check trigger
    let triggered = false;
    if (order.side === 'sell') {
      triggered = currentPrice <= triggerPrice;
    } else {
      triggered = currentPrice >= triggerPrice;
    }

    // Store updated extreme price in metadata
    order.metadata = {
      ...order.metadata,
      extremePrice,
      currentTriggerPrice: triggerPrice,
    };

    return {
      triggered,
      newOrderType: triggered ? OrderType.MARKET : undefined,
    };
  }

  calculateEffectivePrice(order: OrderEntity, marketData: any): number {
    return order.side === 'buy' ? marketData.ask : marketData.bid;
  }
}
```

### Order Type Registry

```typescript
// services/order-service/src/handlers/order-type-registry.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { OrderTypeHandler } from './order-type-handler.interface';
import { OrderType } from '../entities/order.entity';
import { MarketOrderHandler } from './market-order.handler';
import { LimitOrderHandler } from './limit-order.handler';
import { StopOrderHandler } from './stop-order.handler';
import { StopLimitOrderHandler } from './stop-limit-order.handler';
import { PeggedOrderHandler } from './pegged-order.handler';
import { TrailingStopOrderHandler } from './trailing-stop-order.handler';

@Injectable()
export class OrderTypeRegistry implements OnModuleInit {
  private handlers = new Map<OrderType, OrderTypeHandler>();

  constructor(
    private readonly marketHandler: MarketOrderHandler,
    private readonly limitHandler: LimitOrderHandler,
    private readonly stopHandler: StopOrderHandler,
    private readonly stopLimitHandler: StopLimitOrderHandler,
    private readonly peggedHandler: PeggedOrderHandler,
    private readonly trailingStopHandler: TrailingStopOrderHandler,
  ) {}

  onModuleInit() {
    this.register(this.marketHandler);
    this.register(this.limitHandler);
    this.register(this.stopHandler);
    this.register(this.stopLimitHandler);
    this.register(this.peggedHandler);
    this.register(this.trailingStopHandler);
  }

  register(handler: OrderTypeHandler) {
    this.handlers.set(handler.orderType, handler);
  }

  getHandler(orderType: OrderType): OrderTypeHandler {
    const handler = this.handlers.get(orderType);
    if (!handler) {
      throw new Error(`No handler registered for order type: ${orderType}`);
    }
    return handler;
  }

  getAll(): OrderTypeHandler[] {
    return Array.from(this.handlers.values());
  }
}
```

### Time-In-Force Manager

```typescript
// services/order-service/src/services/time-in-force.manager.ts
import { Injectable } from '@nestjs/common';
import { Cron, CronExpression } from '@nestjs/schedule';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, LessThan, In } from 'typeorm';
import { OrderEntity, OrderStatus, TimeInForce } from '../entities/order.entity';
import { OrderStateMachine } from './order-state-machine.service';
import { logger, metrics } from '@orion/observability';

@Injectable()
export class TimeInForceManager {
  constructor(
    @InjectRepository(OrderEntity)
    private readonly orderRepo: Repository<OrderEntity>,
    private readonly stateMachine: OrderStateMachine,
  ) {}

  /**
   * Check if order should be expired based on time-in-force
   */
  shouldExpire(order: OrderEntity): boolean {
    const now = new Date();

    switch (order.timeInForce) {
      case TimeInForce.IOC:
        // Immediate or cancel - should be filled or cancelled immediately
        return true;

      case TimeInForce.FOK:
        // Fill or kill - must be fully filled or cancelled
        return order.filledQuantity < order.quantity;

      case TimeInForce.DAY:
        // Day order - expires at end of trading day
        return this.isPastEndOfDay(order.receivedAt, now);

      case TimeInForce.GTD:
        // Good til date
        return order.expiresAt ? now >= order.expiresAt : false;

      case TimeInForce.GTC:
        // Good til cancelled - never expires automatically
        return false;

      default:
        return false;
    }
  }

  private isPastEndOfDay(orderDate: Date, now: Date): boolean {
    // End of trading day (e.g., 5pm ET)
    const endOfDay = new Date(orderDate);
    endOfDay.setHours(17, 0, 0, 0);
    return now > endOfDay;
  }

  /**
   * Process IOC/FOK orders after routing
   */
  async processImmediateOrders(order: OrderEntity): Promise<void> {
    if (order.timeInForce === TimeInForce.IOC) {
      // Cancel any unfilled portion
      if (order.remainingQuantity > 0 && order.status !== OrderStatus.CANCELLED) {
        await this.stateMachine.transition(order, OrderStatus.CANCELLED, {
          reason: 'IOC order - unfilled portion cancelled',
        });
      }
    }

    if (order.timeInForce === TimeInForce.FOK) {
      // If not fully filled, cancel entire order
      if (order.filledQuantity < order.quantity) {
        await this.stateMachine.transition(order, OrderStatus.CANCELLED, {
          reason: 'FOK order - could not fill entire quantity',
        });
      }
    }
  }

  /**
   * Expire day orders at end of day
   */
  @Cron(CronExpression.EVERY_DAY_AT_5PM)
  async expireDayOrders(): Promise<void> {
    const workingOrders = await this.orderRepo.find({
      where: {
        timeInForce: TimeInForce.DAY,
        status: In([OrderStatus.WORKING, OrderStatus.PARTIAL_FILL]),
      },
    });

    let expiredCount = 0;
    for (const order of workingOrders) {
      try {
        await this.stateMachine.transition(order, OrderStatus.EXPIRED, {
          reason: 'Day order expired at end of trading day',
        });
        expiredCount++;
      } catch (error) {
        logger.error('Failed to expire day order', { orderId: order.id, error });
      }
    }

    logger.info('Expired day orders', { count: expiredCount });
    metrics.increment('orders.expired.day', { count: expiredCount.toString() });
  }

  /**
   * Expire GTD orders past their expiry date
   */
  @Cron(CronExpression.EVERY_MINUTE)
  async expireGtdOrders(): Promise<void> {
    const now = new Date();

    const expiredOrders = await this.orderRepo.find({
      where: {
        timeInForce: TimeInForce.GTD,
        expiresAt: LessThan(now),
        status: In([OrderStatus.WORKING, OrderStatus.PARTIAL_FILL, OrderStatus.HELD]),
      },
    });

    for (const order of expiredOrders) {
      try {
        await this.stateMachine.transition(order, OrderStatus.EXPIRED, {
          reason: 'GTD order expired',
        });
      } catch (error) {
        logger.error('Failed to expire GTD order', { orderId: order.id, error });
      }
    }

    if (expiredOrders.length > 0) {
      logger.info('Expired GTD orders', { count: expiredOrders.length });
    }
  }
}
```

## Definition of Done

- [ ] Market order handler
- [ ] Limit order handler
- [ ] Stop order handler
- [ ] Stop-limit order handler
- [ ] Pegged order handler
- [ ] Trailing stop handler
- [ ] Time-in-force management
- [ ] Order type registry

## Dependencies

- **US-09-01**: Order Entity
- **US-05-01**: Market Data Service (for price triggers)

## Test Cases

```typescript
describe('OrderTypeHandlers', () => {
  describe('MarketOrderHandler', () => {
    it('should validate market order without price', () => {
      const order = createOrder({ orderType: OrderType.MARKET });
      const result = handler.validate(order);
      expect(result.valid).toBe(true);
    });

    it('should reject market order with price', () => {
      const order = createOrder({ orderType: OrderType.MARKET, price: 1.0850 });
      const result = handler.validate(order);
      expect(result.valid).toBe(false);
    });
  });

  describe('StopOrderHandler', () => {
    it('should trigger buy stop when price rises', () => {
      const order = createOrder({ 
        side: 'buy', 
        orderType: OrderType.STOP, 
        stopPrice: 1.0900,
      });
      
      const result = handler.handleTrigger(order, { ask: 1.0905 });
      expect(result.triggered).toBe(true);
      expect(result.newOrderType).toBe(OrderType.MARKET);
    });

    it('should not trigger buy stop when price below', () => {
      const order = createOrder({ 
        side: 'buy', 
        orderType: OrderType.STOP, 
        stopPrice: 1.0900,
      });
      
      const result = handler.handleTrigger(order, { ask: 1.0850 });
      expect(result.triggered).toBe(false);
    });
  });

  describe('TrailingStopOrderHandler', () => {
    it('should update extreme price and trigger', () => {
      const order = createOrder({
        side: 'sell',
        orderType: OrderType.TRAILING_STOP,
        trailingAmount: 0.01,
        metadata: { trailingType: 'absolute' },
      });

      // Price goes up
      let result = handler.handleTrigger(order, { bid: 1.0900 });
      expect(result.triggered).toBe(false);
      expect(order.metadata.extremePrice).toBe(1.0900);

      // Price goes higher
      result = handler.handleTrigger(order, { bid: 1.0950 });
      expect(result.triggered).toBe(false);
      expect(order.metadata.extremePrice).toBe(1.0950);

      // Price drops to trigger
      result = handler.handleTrigger(order, { bid: 1.0840 });
      expect(result.triggered).toBe(true);
    });
  });
});
```
