# User Story: US-10-02 - Pre-Trade Risk Checks

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-10-02 |
| **Epic** | Epic 10 - Risk & Controls |
| **Title** | Pre-Trade Risk Checks |
| **Priority** | P0 - Critical |
| **Story Points** | 8 |
| **PRD Reference** | FR-Risk-02, NFR-Performance-01 |

## User Story

**As a** trading platform  
**I want** to perform pre-trade risk checks on every order  
**So that** orders exceeding risk limits are rejected before execution

## Description

Implement comprehensive pre-trade risk validation including order size checks, position limit checks, exposure checks, loss limit checks, and order rate checks with < 5ms latency target.

## Acceptance Criteria

- [ ] Order size limit check
- [ ] Position limit check
- [ ] Gross exposure check
- [ ] Net exposure check
- [ ] Daily loss limit check
- [ ] Order rate limit check
- [ ] Open orders limit check
- [ ] < 5ms check latency
- [ ] Parallel check execution
- [ ] Check result caching

## Technical Details

### Pre-Trade Check Interface

```typescript
// services/risk-service/src/checks/pre-trade-check.interface.ts
import { OrderEntity } from '@orion/order-service';

export interface PreTradeCheckResult {
  checkType: string;
  status: 'passed' | 'failed' | 'warning';
  currentValue: number;
  limitValue: number;
  utilizationPercent: number;
  message?: string;
  checkTimeMs: number;
}

export interface PreTradeCheckRequest {
  order: OrderEntity;
  tenantId: string;
  clientId: string;
  estimatedPrice?: number;
}

export interface PreTradeCheckResponse {
  orderId: string;
  overallStatus: 'approved' | 'rejected' | 'warning';
  checks: PreTradeCheckResult[];
  totalCheckTimeMs: number;
}

export interface PreTradeCheck {
  name: string;
  priority: number; // Lower = higher priority
  
  check(request: PreTradeCheckRequest): Promise<PreTradeCheckResult>;
}
```

### Order Size Check

```typescript
// services/risk-service/src/checks/order-size.check.ts
import { Injectable } from '@nestjs/common';
import { PreTradeCheck, PreTradeCheckRequest, PreTradeCheckResult } from './pre-trade-check.interface';
import { RiskLimitService } from '../limits/risk-limit.service';
import { RiskLimitType, RiskEntityType } from '../entities/risk-limit.entity';

@Injectable()
export class OrderSizeCheck implements PreTradeCheck {
  name = 'order_size';
  priority = 1;

  constructor(private readonly limitService: RiskLimitService) {}

  async check(request: PreTradeCheckRequest): Promise<PreTradeCheckResult> {
    const startTime = Date.now();

    // Get order size limit (instrument-specific or client-level)
    const limit = await this.limitService.getEffectiveLimit(
      request.tenantId,
      RiskEntityType.CLIENT,
      request.clientId,
      RiskLimitType.MAX_ORDER_SIZE,
      request.order.instrumentId,
    );

    if (!limit) {
      return {
        checkType: this.name,
        status: 'passed',
        currentValue: Number(request.order.quantity),
        limitValue: Infinity,
        utilizationPercent: 0,
        message: 'No order size limit configured',
        checkTimeMs: Date.now() - startTime,
      };
    }

    const orderSize = Number(request.order.quantity);
    const limitValue = Number(limit.limitValue);
    const utilizationPercent = (orderSize / limitValue) * 100;

    let status: 'passed' | 'failed' | 'warning' = 'passed';
    let message: string;

    if (orderSize > limitValue) {
      status = 'failed';
      message = `Order size ${orderSize} exceeds limit ${limitValue}`;
    } else if (utilizationPercent >= Number(limit.criticalThreshold)) {
      status = 'warning';
      message = `Order size at ${utilizationPercent.toFixed(1)}% of limit`;
    }

    return {
      checkType: this.name,
      status,
      currentValue: orderSize,
      limitValue,
      utilizationPercent,
      message,
      checkTimeMs: Date.now() - startTime,
    };
  }
}
```

### Position Limit Check

```typescript
// services/risk-service/src/checks/position-limit.check.ts
import { Injectable } from '@nestjs/common';
import { PreTradeCheck, PreTradeCheckRequest, PreTradeCheckResult } from './pre-trade-check.interface';
import { RiskLimitService } from '../limits/risk-limit.service';
import { RiskCacheService } from '../cache/risk-cache.service';
import { RiskLimitType, RiskEntityType } from '../entities/risk-limit.entity';

@Injectable()
export class PositionLimitCheck implements PreTradeCheck {
  name = 'position_limit';
  priority = 2;

  constructor(
    private readonly limitService: RiskLimitService,
    private readonly riskCache: RiskCacheService,
  ) {}

  async check(request: PreTradeCheckRequest): Promise<PreTradeCheckResult> {
    const startTime = Date.now();

    // Get position limit
    const limit = await this.limitService.getEffectiveLimit(
      request.tenantId,
      RiskEntityType.CLIENT,
      request.clientId,
      RiskLimitType.POSITION_LIMIT,
      request.order.instrumentId,
    );

    if (!limit) {
      return {
        checkType: this.name,
        status: 'passed',
        currentValue: 0,
        limitValue: Infinity,
        utilizationPercent: 0,
        message: 'No position limit configured',
        checkTimeMs: Date.now() - startTime,
      };
    }

    // Get current position from cache
    const currentPosition = await this.riskCache.getPosition(
      request.clientId,
      request.order.instrumentId,
    );

    // Calculate projected position
    const orderQuantity = Number(request.order.quantity);
    const projectedPosition = request.order.side === 'buy'
      ? currentPosition + orderQuantity
      : currentPosition - orderQuantity;

    const absProjected = Math.abs(projectedPosition);
    const limitValue = Number(limit.limitValue);
    const utilizationPercent = (absProjected / limitValue) * 100;

    let status: 'passed' | 'failed' | 'warning' = 'passed';
    let message: string;

    if (absProjected > limitValue) {
      status = 'failed';
      message = `Projected position ${absProjected} would exceed limit ${limitValue}`;
    } else if (utilizationPercent >= Number(limit.criticalThreshold)) {
      status = 'warning';
      message = `Projected position at ${utilizationPercent.toFixed(1)}% of limit`;
    }

    return {
      checkType: this.name,
      status,
      currentValue: absProjected,
      limitValue,
      utilizationPercent,
      message,
      checkTimeMs: Date.now() - startTime,
    };
  }
}
```

### Gross Exposure Check

```typescript
// services/risk-service/src/checks/gross-exposure.check.ts
import { Injectable } from '@nestjs/common';
import { PreTradeCheck, PreTradeCheckRequest, PreTradeCheckResult } from './pre-trade-check.interface';
import { RiskLimitService } from '../limits/risk-limit.service';
import { RiskCacheService } from '../cache/risk-cache.service';
import { RiskLimitType, RiskEntityType } from '../entities/risk-limit.entity';

@Injectable()
export class GrossExposureCheck implements PreTradeCheck {
  name = 'gross_exposure';
  priority = 3;

  constructor(
    private readonly limitService: RiskLimitService,
    private readonly riskCache: RiskCacheService,
  ) {}

  async check(request: PreTradeCheckRequest): Promise<PreTradeCheckResult> {
    const startTime = Date.now();

    const limit = await this.limitService.getEffectiveLimit(
      request.tenantId,
      RiskEntityType.CLIENT,
      request.clientId,
      RiskLimitType.GROSS_EXPOSURE,
    );

    if (!limit) {
      return {
        checkType: this.name,
        status: 'passed',
        currentValue: 0,
        limitValue: Infinity,
        utilizationPercent: 0,
        checkTimeMs: Date.now() - startTime,
      };
    }

    // Get current gross exposure from cache
    const currentExposure = await this.riskCache.getGrossExposure(request.clientId);

    // Calculate order value
    const price = request.estimatedPrice || Number(request.order.price) || 0;
    const orderValue = Number(request.order.quantity) * price;

    // Projected exposure
    const projectedExposure = currentExposure + orderValue;
    const limitValue = Number(limit.limitValue);
    const utilizationPercent = (projectedExposure / limitValue) * 100;

    let status: 'passed' | 'failed' | 'warning' = 'passed';
    let message: string;

    if (projectedExposure > limitValue) {
      status = 'failed';
      message = `Projected gross exposure ${projectedExposure.toFixed(2)} would exceed limit ${limitValue}`;
    } else if (utilizationPercent >= Number(limit.warningThreshold)) {
      status = 'warning';
      message = `Gross exposure at ${utilizationPercent.toFixed(1)}% of limit`;
    }

    return {
      checkType: this.name,
      status,
      currentValue: projectedExposure,
      limitValue,
      utilizationPercent,
      message,
      checkTimeMs: Date.now() - startTime,
    };
  }
}
```

### Daily Loss Check

```typescript
// services/risk-service/src/checks/daily-loss.check.ts
import { Injectable } from '@nestjs/common';
import { PreTradeCheck, PreTradeCheckRequest, PreTradeCheckResult } from './pre-trade-check.interface';
import { RiskLimitService } from '../limits/risk-limit.service';
import { RiskCacheService } from '../cache/risk-cache.service';
import { RiskLimitType, RiskEntityType } from '../entities/risk-limit.entity';

@Injectable()
export class DailyLossCheck implements PreTradeCheck {
  name = 'daily_loss';
  priority = 4;

  constructor(
    private readonly limitService: RiskLimitService,
    private readonly riskCache: RiskCacheService,
  ) {}

  async check(request: PreTradeCheckRequest): Promise<PreTradeCheckResult> {
    const startTime = Date.now();

    const limit = await this.limitService.getEffectiveLimit(
      request.tenantId,
      RiskEntityType.CLIENT,
      request.clientId,
      RiskLimitType.DAILY_LOSS,
    );

    if (!limit) {
      return {
        checkType: this.name,
        status: 'passed',
        currentValue: 0,
        limitValue: Infinity,
        utilizationPercent: 0,
        checkTimeMs: Date.now() - startTime,
      };
    }

    // Get current daily P&L from cache
    const dailyPnL = await this.riskCache.getDailyPnL(request.clientId);

    // Loss is negative P&L
    const currentLoss = Math.abs(Math.min(0, dailyPnL));
    const limitValue = Number(limit.limitValue);
    const utilizationPercent = (currentLoss / limitValue) * 100;

    let status: 'passed' | 'failed' | 'warning' = 'passed';
    let message: string;

    if (currentLoss >= limitValue) {
      status = 'failed';
      message = `Daily loss ${currentLoss.toFixed(2)} has reached limit ${limitValue}. Trading suspended.`;
    } else if (utilizationPercent >= Number(limit.criticalThreshold)) {
      status = 'warning';
      message = `Daily loss at ${utilizationPercent.toFixed(1)}% of limit`;
    }

    return {
      checkType: this.name,
      status,
      currentValue: currentLoss,
      limitValue,
      utilizationPercent,
      message,
      checkTimeMs: Date.now() - startTime,
    };
  }
}
```

### Order Rate Check

```typescript
// services/risk-service/src/checks/order-rate.check.ts
import { Injectable } from '@nestjs/common';
import { Redis } from 'ioredis';
import { InjectRedis } from '@nestjs-modules/ioredis';
import { PreTradeCheck, PreTradeCheckRequest, PreTradeCheckResult } from './pre-trade-check.interface';
import { RiskLimitService } from '../limits/risk-limit.service';
import { RiskLimitType, RiskEntityType } from '../entities/risk-limit.entity';

@Injectable()
export class OrderRateCheck implements PreTradeCheck {
  name = 'order_rate';
  priority = 5;

  constructor(
    private readonly limitService: RiskLimitService,
    @InjectRedis() private readonly redis: Redis,
  ) {}

  async check(request: PreTradeCheckRequest): Promise<PreTradeCheckResult> {
    const startTime = Date.now();

    const limit = await this.limitService.getEffectiveLimit(
      request.tenantId,
      RiskEntityType.CLIENT,
      request.clientId,
      RiskLimitType.ORDER_RATE,
    );

    if (!limit) {
      return {
        checkType: this.name,
        status: 'passed',
        currentValue: 0,
        limitValue: Infinity,
        utilizationPercent: 0,
        checkTimeMs: Date.now() - startTime,
      };
    }

    // Count orders in sliding window (1 minute)
    const windowKey = `rate:orders:${request.clientId}`;
    const windowSize = 60; // seconds

    // Get current count and increment
    const now = Math.floor(Date.now() / 1000);
    
    // Use sorted set with timestamp as score
    await this.redis.zadd(windowKey, now, `${now}:${request.order.id}`);
    
    // Remove entries outside window
    await this.redis.zremrangebyscore(windowKey, '-inf', now - windowSize);
    
    // Count entries in window
    const orderCount = await this.redis.zcard(windowKey);
    
    // Set expiry on key
    await this.redis.expire(windowKey, windowSize + 10);

    const limitValue = Number(limit.limitValue);
    const utilizationPercent = (orderCount / limitValue) * 100;

    let status: 'passed' | 'failed' | 'warning' = 'passed';
    let message: string;

    if (orderCount > limitValue) {
      status = 'failed';
      message = `Order rate ${orderCount}/min exceeds limit ${limitValue}/min`;
    } else if (utilizationPercent >= Number(limit.warningThreshold)) {
      status = 'warning';
      message = `Order rate at ${utilizationPercent.toFixed(1)}% of limit`;
    }

    return {
      checkType: this.name,
      status,
      currentValue: orderCount,
      limitValue,
      utilizationPercent,
      message,
      checkTimeMs: Date.now() - startTime,
    };
  }
}
```

### Pre-Trade Check Engine

```typescript
// services/risk-service/src/checks/pre-trade-check.engine.ts
import { Injectable } from '@nestjs/common';
import { logger, metrics } from '@orion/observability';
import {
  PreTradeCheck,
  PreTradeCheckRequest,
  PreTradeCheckResponse,
  PreTradeCheckResult,
} from './pre-trade-check.interface';
import { OrderSizeCheck } from './order-size.check';
import { PositionLimitCheck } from './position-limit.check';
import { GrossExposureCheck } from './gross-exposure.check';
import { DailyLossCheck } from './daily-loss.check';
import { OrderRateCheck } from './order-rate.check';

@Injectable()
export class PreTradeCheckEngine {
  private checks: PreTradeCheck[];

  constructor(
    private readonly orderSizeCheck: OrderSizeCheck,
    private readonly positionLimitCheck: PositionLimitCheck,
    private readonly grossExposureCheck: GrossExposureCheck,
    private readonly dailyLossCheck: DailyLossCheck,
    private readonly orderRateCheck: OrderRateCheck,
  ) {
    this.checks = [
      this.orderSizeCheck,
      this.positionLimitCheck,
      this.grossExposureCheck,
      this.dailyLossCheck,
      this.orderRateCheck,
    ].sort((a, b) => a.priority - b.priority);
  }

  async runChecks(request: PreTradeCheckRequest): Promise<PreTradeCheckResponse> {
    const startTime = Date.now();
    const results: PreTradeCheckResult[] = [];

    // Run all checks in parallel for speed
    const checkPromises = this.checks.map(async (check) => {
      try {
        return await check.check(request);
      } catch (error) {
        logger.error(`Pre-trade check failed: ${check.name}`, { error });
        return {
          checkType: check.name,
          status: 'failed' as const,
          currentValue: 0,
          limitValue: 0,
          utilizationPercent: 0,
          message: `Check error: ${error.message}`,
          checkTimeMs: 0,
        };
      }
    });

    const checkResults = await Promise.all(checkPromises);
    results.push(...checkResults);

    // Determine overall status
    const hasFailed = results.some(r => r.status === 'failed');
    const hasWarning = results.some(r => r.status === 'warning');

    const overallStatus = hasFailed ? 'rejected' : hasWarning ? 'warning' : 'approved';
    const totalCheckTimeMs = Date.now() - startTime;

    // Record metrics
    metrics.timing('risk.pretrade.check_time', totalCheckTimeMs);
    metrics.increment('risk.pretrade.checks', {
      status: overallStatus,
      orderId: request.order.id,
    });

    // Log rejections
    if (hasFailed) {
      const failedChecks = results.filter(r => r.status === 'failed');
      logger.warn('Pre-trade check rejected order', {
        orderId: request.order.id,
        clientId: request.clientId,
        failedChecks: failedChecks.map(c => ({ type: c.checkType, message: c.message })),
      });
    }

    return {
      orderId: request.order.id,
      overallStatus,
      checks: results,
      totalCheckTimeMs,
    };
  }

  /**
   * Fast-fail check for critical limits
   */
  async quickCheck(request: PreTradeCheckRequest): Promise<boolean> {
    // Only run highest priority checks
    const criticalChecks = this.checks.filter(c => c.priority <= 2);

    const results = await Promise.all(
      criticalChecks.map(c => c.check(request)),
    );

    return results.every(r => r.status !== 'failed');
  }
}
```

### Pre-Trade Check gRPC Service

```typescript
// services/risk-service/src/grpc/pre-trade.grpc.service.ts
import { Injectable } from '@nestjs/common';
import { GrpcService, GrpcMethod } from '@nestjs/microservices';
import { PreTradeCheckEngine } from '../checks/pre-trade-check.engine';

@Injectable()
@GrpcService('PreTradeService')
export class PreTradeGrpcService {
  constructor(private readonly checkEngine: PreTradeCheckEngine) {}

  @GrpcMethod('PreTradeService', 'CheckOrder')
  async checkOrder(request: any): Promise<any> {
    const response = await this.checkEngine.runChecks({
      order: request.order,
      tenantId: request.tenantId,
      clientId: request.clientId,
      estimatedPrice: request.estimatedPrice,
    });

    return {
      orderId: response.orderId,
      approved: response.overallStatus === 'approved',
      overallStatus: response.overallStatus,
      checks: response.checks,
      totalCheckTimeMs: response.totalCheckTimeMs,
    };
  }

  @GrpcMethod('PreTradeService', 'QuickCheck')
  async quickCheck(request: any): Promise<{ approved: boolean }> {
    const approved = await this.checkEngine.quickCheck(request);
    return { approved };
  }
}
```

## Definition of Done

- [ ] Order size check
- [ ] Position limit check
- [ ] Gross exposure check
- [ ] Daily loss check
- [ ] Order rate check
- [ ] Parallel execution
- [ ] < 5ms latency (p95)
- [ ] gRPC integration
- [ ] Metrics and logging

## Dependencies

- **US-10-01**: Risk Limit Configuration
- **US-07-02**: Position Service (for position data)

## Test Cases

```typescript
describe('PreTradeCheckEngine', () => {
  it('should approve order within all limits', async () => {
    const order = createOrder({ quantity: 100000 });
    setClientLimits({ positionLimit: 1000000, orderSize: 500000 });

    const result = await checkEngine.runChecks({
      order,
      tenantId: 'tenant-1',
      clientId: 'client-1',
    });

    expect(result.overallStatus).toBe('approved');
    expect(result.checks.every(c => c.status === 'passed')).toBe(true);
  });

  it('should reject order exceeding position limit', async () => {
    const order = createOrder({ quantity: 600000 });
    setClientLimits({ positionLimit: 500000 });
    setCurrentPosition(100000);

    const result = await checkEngine.runChecks({
      order,
      tenantId: 'tenant-1',
      clientId: 'client-1',
    });

    expect(result.overallStatus).toBe('rejected');
    const positionCheck = result.checks.find(c => c.checkType === 'position_limit');
    expect(positionCheck.status).toBe('failed');
  });

  it('should complete all checks within 5ms', async () => {
    const order = createOrder({ quantity: 100000 });

    const result = await checkEngine.runChecks({
      order,
      tenantId: 'tenant-1',
      clientId: 'client-1',
    });

    expect(result.totalCheckTimeMs).toBeLessThan(5);
  });

  it('should warn when approaching limit', async () => {
    const order = createOrder({ quantity: 85000 });
    setClientLimits({ orderSize: 100000, warningThreshold: 80 });

    const result = await checkEngine.runChecks({
      order,
      tenantId: 'tenant-1',
      clientId: 'client-1',
    });

    expect(result.overallStatus).toBe('warning');
  });

  it('should block trading when daily loss limit reached', async () => {
    setClientLimits({ dailyLoss: 50000 });
    setDailyPnL(-55000);

    const result = await checkEngine.runChecks({
      order: createOrder(),
      tenantId: 'tenant-1',
      clientId: 'client-1',
    });

    expect(result.overallStatus).toBe('rejected');
    const lossCheck = result.checks.find(c => c.checkType === 'daily_loss');
    expect(lossCheck.status).toBe('failed');
  });
});
```
