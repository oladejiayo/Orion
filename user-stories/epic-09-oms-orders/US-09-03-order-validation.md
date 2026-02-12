# User Story: US-09-03 - Order Validation Engine

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-09-03 |
| **Epic** | Epic 09 - OMS Orders V1 |
| **Title** | Order Validation Engine |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |
| **PRD Reference** | FR-Order-03, NFR-Performance-01 |

## User Story

**As a** platform operator  
**I want** comprehensive order validation  
**So that** only valid orders proceed to execution

## Description

Implement order validation engine covering field validation, instrument eligibility, client permissions, quantity limits, price reasonability, and duplicate detection with sub-10ms target latency.

## Acceptance Criteria

- [ ] Field validation (required, format)
- [ ] Instrument validation (active, tradeable)
- [ ] Client validation (active, permissions)
- [ ] Quantity validation (min, max, lot size)
- [ ] Price reasonability checks
- [ ] Duplicate order detection
- [ ] < 10ms validation latency
- [ ] Detailed rejection reasons

## Technical Details

### Validation Rules Interface

```typescript
// services/order-service/src/validation/order-validation.interface.ts

export interface OrderValidationContext {
  order: CreateOrderDto;
  instrument?: InstrumentEntity;
  client?: ClientEntity;
  marketData?: { bid: number; ask: number; mid: number };
  recentOrders?: OrderEntity[];
}

export interface ValidationError {
  code: string;
  field?: string;
  message: string;
  severity: 'error' | 'warning';
}

export interface OrderValidationResult {
  valid: boolean;
  errors: ValidationError[];
  warnings: ValidationError[];
  duration: number;
  ruleName: string;
}

export interface OrderValidationRule {
  name: string;
  priority: number;
  parallel: boolean; // Can run in parallel
  
  validate(context: OrderValidationContext): Promise<OrderValidationResult>;
}
```

### Field Validation Rule

```typescript
// services/order-service/src/validation/rules/field-validation.rule.ts
import { Injectable } from '@nestjs/common';
import { OrderValidationRule, OrderValidationContext, OrderValidationResult, ValidationError } from '../order-validation.interface';
import { OrderType, TimeInForce } from '../../entities/order.entity';

@Injectable()
export class FieldValidationRule implements OrderValidationRule {
  name = 'field-validation';
  priority = 10;
  parallel = true;

  async validate(context: OrderValidationContext): Promise<OrderValidationResult> {
    const startTime = Date.now();
    const errors: ValidationError[] = [];
    const warnings: ValidationError[] = [];
    const { order } = context;

    // Required fields
    if (!order.clientId) {
      errors.push({ code: 'MISSING_CLIENT', field: 'clientId', message: 'Client ID is required', severity: 'error' });
    }
    if (!order.instrumentId) {
      errors.push({ code: 'MISSING_INSTRUMENT', field: 'instrumentId', message: 'Instrument ID is required', severity: 'error' });
    }
    if (!order.side || !['buy', 'sell'].includes(order.side)) {
      errors.push({ code: 'INVALID_SIDE', field: 'side', message: 'Side must be buy or sell', severity: 'error' });
    }
    if (!order.quantity || order.quantity <= 0) {
      errors.push({ code: 'INVALID_QUANTITY', field: 'quantity', message: 'Quantity must be positive', severity: 'error' });
    }

    // Order type specific validation
    if (order.orderType === OrderType.LIMIT && (!order.price || order.price <= 0)) {
      errors.push({ code: 'MISSING_LIMIT_PRICE', field: 'price', message: 'Limit price required for limit orders', severity: 'error' });
    }
    if ([OrderType.STOP, OrderType.STOP_LIMIT].includes(order.orderType) && (!order.stopPrice || order.stopPrice <= 0)) {
      errors.push({ code: 'MISSING_STOP_PRICE', field: 'stopPrice', message: 'Stop price required for stop orders', severity: 'error' });
    }

    // Time-in-force validation
    if (order.timeInForce === TimeInForce.GTD && !order.expiresAt) {
      errors.push({ code: 'MISSING_EXPIRY', field: 'expiresAt', message: 'Expiry date required for GTD orders', severity: 'error' });
    }

    // Client order ID format
    if (order.clientOrderId && order.clientOrderId.length > 100) {
      errors.push({ code: 'INVALID_CLIENT_ORDER_ID', field: 'clientOrderId', message: 'Client order ID too long (max 100)', severity: 'error' });
    }

    return {
      valid: errors.length === 0,
      errors,
      warnings,
      duration: Date.now() - startTime,
      ruleName: this.name,
    };
  }
}
```

### Instrument Validation Rule

```typescript
// services/order-service/src/validation/rules/instrument-validation.rule.ts
import { Injectable } from '@nestjs/common';
import { OrderValidationRule, OrderValidationContext, OrderValidationResult, ValidationError } from '../order-validation.interface';
import { InstrumentService } from '@orion/reference-data';

@Injectable()
export class InstrumentValidationRule implements OrderValidationRule {
  name = 'instrument-validation';
  priority = 20;
  parallel = true;

  constructor(private readonly instrumentService: InstrumentService) {}

  async validate(context: OrderValidationContext): Promise<OrderValidationResult> {
    const startTime = Date.now();
    const errors: ValidationError[] = [];
    const warnings: ValidationError[] = [];
    const { order } = context;

    // Instrument lookup (may be pre-fetched in context)
    const instrument = context.instrument || await this.instrumentService.getById(order.instrumentId);

    if (!instrument) {
      errors.push({
        code: 'INSTRUMENT_NOT_FOUND',
        field: 'instrumentId',
        message: `Instrument ${order.instrumentId} not found`,
        severity: 'error',
      });
      return { valid: false, errors, warnings, duration: Date.now() - startTime, ruleName: this.name };
    }

    // Check instrument status
    if (instrument.status !== 'active') {
      errors.push({
        code: 'INSTRUMENT_NOT_ACTIVE',
        field: 'instrumentId',
        message: `Instrument ${instrument.symbol} is ${instrument.status}`,
        severity: 'error',
      });
    }

    // Check trading enabled
    if (!instrument.tradingEnabled) {
      errors.push({
        code: 'INSTRUMENT_NOT_TRADEABLE',
        field: 'instrumentId',
        message: `Trading disabled for ${instrument.symbol}`,
        severity: 'error',
      });
    }

    // Check trading hours
    if (instrument.tradingHours && !this.isWithinTradingHours(instrument.tradingHours)) {
      warnings.push({
        code: 'OUTSIDE_TRADING_HOURS',
        field: 'instrumentId',
        message: `Order submitted outside trading hours for ${instrument.symbol}`,
        severity: 'warning',
      });
    }

    // Quantity validation against instrument limits
    if (instrument.minOrderSize && order.quantity < instrument.minOrderSize) {
      errors.push({
        code: 'QUANTITY_BELOW_MIN',
        field: 'quantity',
        message: `Quantity ${order.quantity} below minimum ${instrument.minOrderSize}`,
        severity: 'error',
      });
    }

    if (instrument.maxOrderSize && order.quantity > instrument.maxOrderSize) {
      errors.push({
        code: 'QUANTITY_ABOVE_MAX',
        field: 'quantity',
        message: `Quantity ${order.quantity} exceeds maximum ${instrument.maxOrderSize}`,
        severity: 'error',
      });
    }

    // Lot size validation
    if (instrument.lotSize && order.quantity % instrument.lotSize !== 0) {
      errors.push({
        code: 'INVALID_LOT_SIZE',
        field: 'quantity',
        message: `Quantity must be multiple of lot size ${instrument.lotSize}`,
        severity: 'error',
      });
    }

    // Tick size validation
    if (instrument.tickSize && order.price) {
      const priceRemainder = (order.price * 1e8) % (instrument.tickSize * 1e8);
      if (priceRemainder !== 0) {
        errors.push({
          code: 'INVALID_TICK_SIZE',
          field: 'price',
          message: `Price must be multiple of tick size ${instrument.tickSize}`,
          severity: 'error',
        });
      }
    }

    return {
      valid: errors.length === 0,
      errors,
      warnings,
      duration: Date.now() - startTime,
      ruleName: this.name,
    };
  }

  private isWithinTradingHours(tradingHours: any): boolean {
    // Implement trading hours check
    return true;
  }
}
```

### Client Validation Rule

```typescript
// services/order-service/src/validation/rules/client-validation.rule.ts
import { Injectable } from '@nestjs/common';
import { OrderValidationRule, OrderValidationContext, OrderValidationResult, ValidationError } from '../order-validation.interface';
import { ClientService } from '@orion/reference-data';

@Injectable()
export class ClientValidationRule implements OrderValidationRule {
  name = 'client-validation';
  priority = 30;
  parallel = true;

  constructor(private readonly clientService: ClientService) {}

  async validate(context: OrderValidationContext): Promise<OrderValidationResult> {
    const startTime = Date.now();
    const errors: ValidationError[] = [];
    const warnings: ValidationError[] = [];
    const { order } = context;

    const client = context.client || await this.clientService.getById(order.clientId);

    if (!client) {
      errors.push({
        code: 'CLIENT_NOT_FOUND',
        field: 'clientId',
        message: `Client ${order.clientId} not found`,
        severity: 'error',
      });
      return { valid: false, errors, warnings, duration: Date.now() - startTime, ruleName: this.name };
    }

    // Check client status
    if (client.status !== 'active') {
      errors.push({
        code: 'CLIENT_NOT_ACTIVE',
        field: 'clientId',
        message: `Client ${client.name} is ${client.status}`,
        severity: 'error',
      });
    }

    // Check trading permissions
    if (!client.permissions?.includes('trade')) {
      errors.push({
        code: 'CLIENT_NO_TRADE_PERMISSION',
        field: 'clientId',
        message: `Client ${client.name} does not have trading permission`,
        severity: 'error',
      });
    }

    // Check instrument permission
    const instrument = context.instrument;
    if (instrument && client.allowedAssetClasses) {
      if (!client.allowedAssetClasses.includes(instrument.assetClass)) {
        errors.push({
          code: 'CLIENT_ASSET_NOT_ALLOWED',
          field: 'instrumentId',
          message: `Client not permitted to trade ${instrument.assetClass}`,
          severity: 'error',
        });
      }
    }

    // Check order type permission
    if (client.allowedOrderTypes && !client.allowedOrderTypes.includes(order.orderType)) {
      errors.push({
        code: 'ORDER_TYPE_NOT_ALLOWED',
        field: 'orderType',
        message: `Client not permitted to use ${order.orderType} orders`,
        severity: 'error',
      });
    }

    return {
      valid: errors.length === 0,
      errors,
      warnings,
      duration: Date.now() - startTime,
      ruleName: this.name,
    };
  }
}
```

### Price Validation Rule

```typescript
// services/order-service/src/validation/rules/price-validation.rule.ts
import { Injectable } from '@nestjs/common';
import { OrderValidationRule, OrderValidationContext, OrderValidationResult, ValidationError } from '../order-validation.interface';
import { OrderType } from '../../entities/order.entity';
import { ConfigService } from '@nestjs/config';

@Injectable()
export class PriceValidationRule implements OrderValidationRule {
  name = 'price-validation';
  priority = 40;
  parallel = true;

  private readonly maxDeviationPercent: number;

  constructor(configService: ConfigService) {
    this.maxDeviationPercent = configService.get('ORDER_MAX_PRICE_DEVIATION_PERCENT', 5);
  }

  async validate(context: OrderValidationContext): Promise<OrderValidationResult> {
    const startTime = Date.now();
    const errors: ValidationError[] = [];
    const warnings: ValidationError[] = [];
    const { order, marketData } = context;

    // Skip for market orders (no price specified)
    if (order.orderType === OrderType.MARKET) {
      return { valid: true, errors, warnings, duration: Date.now() - startTime, ruleName: this.name };
    }

    // Need market data for price validation
    if (!marketData) {
      warnings.push({
        code: 'NO_MARKET_DATA',
        message: 'Price validation skipped - no market data available',
        severity: 'warning',
      });
      return { valid: true, errors, warnings, duration: Date.now() - startTime, ruleName: this.name };
    }

    // Validate limit price
    if (order.price) {
      const mid = marketData.mid;
      const deviationPercent = Math.abs((order.price - mid) / mid) * 100;

      if (deviationPercent > this.maxDeviationPercent) {
        errors.push({
          code: 'PRICE_FAR_FROM_MARKET',
          field: 'price',
          message: `Price ${order.price} deviates ${deviationPercent.toFixed(1)}% from mid ${mid}`,
          severity: 'error',
        });
      } else if (deviationPercent > this.maxDeviationPercent * 0.5) {
        warnings.push({
          code: 'PRICE_DEVIATION_WARNING',
          field: 'price',
          message: `Price ${order.price} deviates ${deviationPercent.toFixed(1)}% from mid`,
          severity: 'warning',
        });
      }

      // Check if limit is on wrong side of market
      if (order.side === 'buy' && order.price > marketData.ask * 1.01) {
        warnings.push({
          code: 'BUY_PRICE_ABOVE_ASK',
          field: 'price',
          message: 'Buy limit price is above current ask',
          severity: 'warning',
        });
      }
      if (order.side === 'sell' && order.price < marketData.bid * 0.99) {
        warnings.push({
          code: 'SELL_PRICE_BELOW_BID',
          field: 'price',
          message: 'Sell limit price is below current bid',
          severity: 'warning',
        });
      }
    }

    // Validate stop price
    if (order.stopPrice) {
      const refPrice = order.side === 'buy' ? marketData.ask : marketData.bid;

      // Buy stop should be above current price
      if (order.side === 'buy' && order.stopPrice <= refPrice) {
        warnings.push({
          code: 'BUY_STOP_BELOW_MARKET',
          field: 'stopPrice',
          message: 'Buy stop price should be above current market price',
          severity: 'warning',
        });
      }

      // Sell stop should be below current price
      if (order.side === 'sell' && order.stopPrice >= refPrice) {
        warnings.push({
          code: 'SELL_STOP_ABOVE_MARKET',
          field: 'stopPrice',
          message: 'Sell stop price should be below current market price',
          severity: 'warning',
        });
      }
    }

    return {
      valid: errors.length === 0,
      errors,
      warnings,
      duration: Date.now() - startTime,
      ruleName: this.name,
    };
  }
}
```

### Duplicate Detection Rule

```typescript
// services/order-service/src/validation/rules/duplicate-detection.rule.ts
import { Injectable } from '@nestjs/common';
import { OrderValidationRule, OrderValidationContext, OrderValidationResult, ValidationError } from '../order-validation.interface';
import { OrderRepository } from '../../repositories/order.repository';

@Injectable()
export class DuplicateDetectionRule implements OrderValidationRule {
  name = 'duplicate-detection';
  priority = 50;
  parallel = false; // Sequential to prevent race conditions

  constructor(private readonly orderRepo: OrderRepository) {}

  async validate(context: OrderValidationContext): Promise<OrderValidationResult> {
    const startTime = Date.now();
    const errors: ValidationError[] = [];
    const warnings: ValidationError[] = [];
    const { order } = context;

    // Check client order ID uniqueness
    if (order.clientOrderId) {
      const existing = await this.orderRepo.findByClientOrderId(
        order.clientId,
        order.clientOrderId,
      );

      if (existing) {
        errors.push({
          code: 'DUPLICATE_CLIENT_ORDER_ID',
          field: 'clientOrderId',
          message: `Order with client order ID ${order.clientOrderId} already exists`,
          severity: 'error',
        });
      }
    }

    // Check for potential duplicate order (same parameters within time window)
    const recentOrders = context.recentOrders || await this.getRecentOrders(order);
    
    const potentialDuplicate = recentOrders.find(recent =>
      recent.instrumentId === order.instrumentId &&
      recent.side === order.side &&
      recent.quantity === order.quantity &&
      recent.price === order.price &&
      recent.orderType === order.orderType,
    );

    if (potentialDuplicate) {
      warnings.push({
        code: 'POTENTIAL_DUPLICATE',
        message: `Similar order ${potentialDuplicate.orderRef} submitted recently`,
        severity: 'warning',
      });
    }

    return {
      valid: errors.length === 0,
      errors,
      warnings,
      duration: Date.now() - startTime,
      ruleName: this.name,
    };
  }

  private async getRecentOrders(order: any): Promise<any[]> {
    const fiveMinutesAgo = new Date(Date.now() - 5 * 60 * 1000);
    
    return this.orderRepo.findMany({
      clientId: order.clientId,
      instrumentId: order.instrumentId,
      fromDate: fiveMinutesAgo,
      includeTerminal: false,
      limit: 10,
    });
  }
}
```

### Order Validation Service

```typescript
// services/order-service/src/validation/order-validation.service.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { logger, metrics } from '@orion/observability';
import { OrderValidationRule, OrderValidationContext, OrderValidationResult, ValidationError } from './order-validation.interface';
import { FieldValidationRule } from './rules/field-validation.rule';
import { InstrumentValidationRule } from './rules/instrument-validation.rule';
import { ClientValidationRule } from './rules/client-validation.rule';
import { PriceValidationRule } from './rules/price-validation.rule';
import { DuplicateDetectionRule } from './rules/duplicate-detection.rule';
import { CreateOrderDto } from '../dto/create-order.dto';
import { InstrumentService } from '@orion/reference-data';
import { ClientService } from '@orion/reference-data';
import { MarketDataService } from '@orion/market-data';

export interface FullValidationResult {
  valid: boolean;
  errors: ValidationError[];
  warnings: ValidationError[];
  ruleResults: OrderValidationResult[];
  totalDuration: number;
}

@Injectable()
export class OrderValidationService implements OnModuleInit {
  private rules: OrderValidationRule[] = [];

  constructor(
    private readonly fieldRule: FieldValidationRule,
    private readonly instrumentRule: InstrumentValidationRule,
    private readonly clientRule: ClientValidationRule,
    private readonly priceRule: PriceValidationRule,
    private readonly duplicateRule: DuplicateDetectionRule,
    private readonly instrumentService: InstrumentService,
    private readonly clientService: ClientService,
    private readonly marketDataService: MarketDataService,
  ) {}

  onModuleInit() {
    this.rules = [
      this.fieldRule,
      this.instrumentRule,
      this.clientRule,
      this.priceRule,
      this.duplicateRule,
    ].sort((a, b) => a.priority - b.priority);

    logger.info('Order validation service initialized', {
      ruleCount: this.rules.length,
    });
  }

  async validateOrder(dto: CreateOrderDto): Promise<FullValidationResult> {
    const startTime = Date.now();
    const allErrors: ValidationError[] = [];
    const allWarnings: ValidationError[] = [];
    const ruleResults: OrderValidationResult[] = [];

    // Pre-fetch data for context
    const [instrument, client, marketData] = await Promise.all([
      this.instrumentService.getById(dto.instrumentId).catch(() => null),
      this.clientService.getById(dto.clientId).catch(() => null),
      this.marketDataService.getQuote(dto.instrumentId).catch(() => null),
    ]);

    const context: OrderValidationContext = {
      order: dto,
      instrument,
      client,
      marketData,
    };

    // Run parallel rules concurrently
    const parallelRules = this.rules.filter(r => r.parallel);
    const sequentialRules = this.rules.filter(r => !r.parallel);

    const parallelResults = await Promise.all(
      parallelRules.map(rule => this.runRule(rule, context)),
    );

    for (const result of parallelResults) {
      ruleResults.push(result);
      allErrors.push(...result.errors);
      allWarnings.push(...result.warnings);
    }

    // Run sequential rules only if parallel rules passed
    if (allErrors.length === 0) {
      for (const rule of sequentialRules) {
        const result = await this.runRule(rule, context);
        ruleResults.push(result);
        allErrors.push(...result.errors);
        allWarnings.push(...result.warnings);

        if (result.errors.length > 0) break;
      }
    }

    const totalDuration = Date.now() - startTime;

    // Metrics
    metrics.timing('order.validation.total', totalDuration);
    metrics.increment('order.validation.complete', {
      valid: (allErrors.length === 0).toString(),
      errorCount: allErrors.length.toString(),
    });

    if (totalDuration > 10) {
      logger.warn('Order validation exceeded SLA', {
        duration: totalDuration,
        ruleTimings: ruleResults.map(r => ({ rule: r.ruleName, duration: r.duration })),
      });
    }

    return {
      valid: allErrors.length === 0,
      errors: allErrors,
      warnings: allWarnings,
      ruleResults,
      totalDuration,
    };
  }

  private async runRule(
    rule: OrderValidationRule,
    context: OrderValidationContext,
  ): Promise<OrderValidationResult> {
    try {
      const result = await rule.validate(context);
      
      logger.debug('Validation rule completed', {
        rule: rule.name,
        valid: result.valid,
        duration: result.duration,
      });

      return result;
    } catch (error) {
      logger.error(`Validation rule ${rule.name} threw error`, { error });

      return {
        valid: false,
        errors: [{
          code: 'VALIDATION_RULE_ERROR',
          message: `Rule ${rule.name} failed: ${error.message}`,
          severity: 'error',
        }],
        warnings: [],
        duration: 0,
        ruleName: rule.name,
      };
    }
  }
}
```

## Definition of Done

- [ ] Field validation rule
- [ ] Instrument validation rule
- [ ] Client validation rule
- [ ] Price validation rule
- [ ] Duplicate detection
- [ ] Validation service orchestration
- [ ] < 10ms latency for validation

## Dependencies

- **US-04-01**: Instrument Service
- **US-04-02**: Client Service
- **US-05-01**: Market Data Service

## Test Cases

```typescript
describe('OrderValidationService', () => {
  it('should validate complete order in < 10ms', async () => {
    const dto = createValidOrderDto();
    const startTime = Date.now();

    const result = await validationService.validateOrder(dto);

    expect(Date.now() - startTime).toBeLessThan(10);
    expect(result.valid).toBe(true);
  });

  it('should reject order with missing fields', async () => {
    const dto = { clientId: 'client-1' }; // Missing required fields

    const result = await validationService.validateOrder(dto as any);

    expect(result.valid).toBe(false);
    expect(result.errors.some(e => e.code === 'MISSING_INSTRUMENT')).toBe(true);
  });

  it('should detect duplicate client order ID', async () => {
    const dto = createValidOrderDto({ clientOrderId: 'existing-id' });
    await createOrder({ clientOrderId: 'existing-id' });

    const result = await validationService.validateOrder(dto);

    expect(result.valid).toBe(false);
    expect(result.errors.some(e => e.code === 'DUPLICATE_CLIENT_ORDER_ID')).toBe(true);
  });

  it('should warn on price far from market', async () => {
    const dto = createValidOrderDto({ price: 2.0 }); // Far from market (mid ~1.0)

    const result = await validationService.validateOrder(dto);

    expect(result.warnings.some(w => w.code === 'PRICE_FAR_FROM_MARKET' || w.code === 'PRICE_DEVIATION_WARNING')).toBe(true);
  });
});
```
