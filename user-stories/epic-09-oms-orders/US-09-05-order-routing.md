# User Story: US-09-05 - Order Routing Service

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-09-05 |
| **Epic** | Epic 09 - OMS Orders V1 |
| **Title** | Order Routing Service |
| **Priority** | P0 - Critical |
| **Story Points** | 8 |
| **PRD Reference** | FR-Order-05, NFR-Performance-02 |

## User Story

**As a** trading platform  
**I want** smart order routing  
**So that** orders are executed at best available prices across LPs

## Description

Implement order routing service supporting best execution routing, split routing, sequential routing, and manual LP selection with circuit breakers and failover.

## Acceptance Criteria

- [ ] Best execution routing (best price)
- [ ] Split routing (across multiple LPs)
- [ ] Sequential routing (fallback)
- [ ] Manual LP selection
- [ ] Circuit breaker per LP
- [ ] Failover handling
- [ ] Routing metrics
- [ ] < 50ms routing latency

## Technical Details

### Routing Strategy Interface

```typescript
// services/order-service/src/routing/routing-strategy.interface.ts
import { OrderEntity } from '../entities/order.entity';

export interface LpQuote {
  lpId: string;
  lpName: string;
  price: number;
  quantity: number;
  validUntil: Date;
  metadata?: any;
}

export interface RoutingDecision {
  lpId: string;
  quantity: number;
  price: number;
  priority: number;
}

export interface RoutingResult {
  strategy: string;
  decisions: RoutingDecision[];
  totalQuantity: number;
  bestPrice: number;
  routingTime: number;
}

export interface RoutingStrategy {
  name: string;
  
  route(order: OrderEntity, quotes: LpQuote[]): Promise<RoutingResult>;
}
```

### Best Execution Strategy

```typescript
// services/order-service/src/routing/strategies/best-execution.strategy.ts
import { Injectable } from '@nestjs/common';
import { RoutingStrategy, LpQuote, RoutingResult, RoutingDecision } from '../routing-strategy.interface';
import { OrderEntity } from '../../entities/order.entity';

@Injectable()
export class BestExecutionStrategy implements RoutingStrategy {
  name = 'best';

  async route(order: OrderEntity, quotes: LpQuote[]): Promise<RoutingResult> {
    const startTime = Date.now();
    const decisions: RoutingDecision[] = [];

    if (quotes.length === 0) {
      return {
        strategy: this.name,
        decisions: [],
        totalQuantity: 0,
        bestPrice: 0,
        routingTime: Date.now() - startTime,
      };
    }

    // Sort quotes by price (best first)
    const sortedQuotes = [...quotes].sort((a, b) => {
      if (order.side === 'buy') {
        // For buy orders, lower price is better
        return a.price - b.price;
      } else {
        // For sell orders, higher price is better
        return b.price - a.price;
      }
    });

    let remainingQuantity = Number(order.remainingQuantity);
    let priority = 1;

    // Fill from best quotes
    for (const quote of sortedQuotes) {
      if (remainingQuantity <= 0) break;
      if (new Date() > quote.validUntil) continue;

      const fillQuantity = Math.min(remainingQuantity, quote.quantity);

      // Check price constraint for limit orders
      if (order.price) {
        if (order.side === 'buy' && quote.price > Number(order.price)) continue;
        if (order.side === 'sell' && quote.price < Number(order.price)) continue;
      }

      decisions.push({
        lpId: quote.lpId,
        quantity: fillQuantity,
        price: quote.price,
        priority: priority++,
      });

      remainingQuantity -= fillQuantity;
    }

    const totalQuantity = decisions.reduce((sum, d) => sum + d.quantity, 0);
    const bestPrice = decisions.length > 0 ? decisions[0].price : 0;

    return {
      strategy: this.name,
      decisions,
      totalQuantity,
      bestPrice,
      routingTime: Date.now() - startTime,
    };
  }
}
```

### Split Routing Strategy

```typescript
// services/order-service/src/routing/strategies/split-routing.strategy.ts
import { Injectable } from '@nestjs/common';
import { RoutingStrategy, LpQuote, RoutingResult, RoutingDecision } from '../routing-strategy.interface';
import { OrderEntity } from '../../entities/order.entity';

@Injectable()
export class SplitRoutingStrategy implements RoutingStrategy {
  name = 'split';

  async route(order: OrderEntity, quotes: LpQuote[]): Promise<RoutingResult> {
    const startTime = Date.now();
    const decisions: RoutingDecision[] = [];

    if (quotes.length === 0) {
      return {
        strategy: this.name,
        decisions: [],
        totalQuantity: 0,
        bestPrice: 0,
        routingTime: Date.now() - startTime,
      };
    }

    // Filter valid quotes
    const validQuotes = quotes.filter(q => new Date() < q.validUntil);
    
    if (validQuotes.length === 0) {
      return {
        strategy: this.name,
        decisions: [],
        totalQuantity: 0,
        bestPrice: 0,
        routingTime: Date.now() - startTime,
      };
    }

    // Calculate total available quantity
    const totalAvailable = validQuotes.reduce((sum, q) => sum + q.quantity, 0);
    const orderQuantity = Number(order.remainingQuantity);

    if (totalAvailable === 0) {
      return {
        strategy: this.name,
        decisions: [],
        totalQuantity: 0,
        bestPrice: 0,
        routingTime: Date.now() - startTime,
      };
    }

    // Split proportionally based on available quantity
    let priority = 1;
    let allocatedQuantity = 0;

    for (const quote of validQuotes) {
      const proportion = quote.quantity / totalAvailable;
      let allocation = Math.floor(orderQuantity * proportion);

      // Ensure we don't exceed available
      allocation = Math.min(allocation, quote.quantity);

      // Check price constraint
      if (order.price) {
        if (order.side === 'buy' && quote.price > Number(order.price)) continue;
        if (order.side === 'sell' && quote.price < Number(order.price)) continue;
      }

      if (allocation > 0) {
        decisions.push({
          lpId: quote.lpId,
          quantity: allocation,
          price: quote.price,
          priority: priority++,
        });
        allocatedQuantity += allocation;
      }
    }

    // Handle any remainder (round up to best LP)
    const remainder = orderQuantity - allocatedQuantity;
    if (remainder > 0 && decisions.length > 0) {
      // Sort by price and add to best
      decisions.sort((a, b) => {
        if (order.side === 'buy') return a.price - b.price;
        return b.price - a.price;
      });
      decisions[0].quantity += remainder;
    }

    const totalQuantity = decisions.reduce((sum, d) => sum + d.quantity, 0);
    const weightedPrice = decisions.reduce((sum, d) => sum + d.price * d.quantity, 0) / totalQuantity;

    return {
      strategy: this.name,
      decisions,
      totalQuantity,
      bestPrice: weightedPrice,
      routingTime: Date.now() - startTime,
    };
  }
}
```

### Sequential Routing Strategy

```typescript
// services/order-service/src/routing/strategies/sequential-routing.strategy.ts
import { Injectable } from '@nestjs/common';
import { RoutingStrategy, LpQuote, RoutingResult, RoutingDecision } from '../routing-strategy.interface';
import { OrderEntity } from '../../entities/order.entity';

@Injectable()
export class SequentialRoutingStrategy implements RoutingStrategy {
  name = 'sequential';

  async route(order: OrderEntity, quotes: LpQuote[]): Promise<RoutingResult> {
    const startTime = Date.now();
    const decisions: RoutingDecision[] = [];

    // Sort by preferred LP order (could be from client settings)
    const preferredLps = order.metadata?.preferredLps || [];
    
    const sortedQuotes = [...quotes].sort((a, b) => {
      const aIndex = preferredLps.indexOf(a.lpId);
      const bIndex = preferredLps.indexOf(b.lpId);
      
      // Preferred LPs first
      if (aIndex >= 0 && bIndex < 0) return -1;
      if (aIndex < 0 && bIndex >= 0) return 1;
      if (aIndex >= 0 && bIndex >= 0) return aIndex - bIndex;
      
      // Then by price
      if (order.side === 'buy') return a.price - b.price;
      return b.price - a.price;
    });

    // Route to first valid LP only
    for (const quote of sortedQuotes) {
      if (new Date() > quote.validUntil) continue;

      // Check price constraint
      if (order.price) {
        if (order.side === 'buy' && quote.price > Number(order.price)) continue;
        if (order.side === 'sell' && quote.price < Number(order.price)) continue;
      }

      const fillQuantity = Math.min(Number(order.remainingQuantity), quote.quantity);

      decisions.push({
        lpId: quote.lpId,
        quantity: fillQuantity,
        price: quote.price,
        priority: 1,
      });

      // Only use first LP in sequential mode
      break;
    }

    return {
      strategy: this.name,
      decisions,
      totalQuantity: decisions.reduce((sum, d) => sum + d.quantity, 0),
      bestPrice: decisions[0]?.price || 0,
      routingTime: Date.now() - startTime,
    };
  }
}
```

### LP Circuit Breaker

```typescript
// services/order-service/src/routing/lp-circuit-breaker.ts
import { Injectable } from '@nestjs/common';
import { Redis } from 'ioredis';
import { InjectRedis } from '@nestjs-modules/ioredis';
import { logger, metrics } from '@orion/observability';

export enum CircuitState {
  CLOSED = 'closed',
  OPEN = 'open',
  HALF_OPEN = 'half_open',
}

interface CircuitStatus {
  state: CircuitState;
  failures: number;
  lastFailure?: Date;
  openedAt?: Date;
}

@Injectable()
export class LpCircuitBreaker {
  private readonly failureThreshold = 5;
  private readonly resetTimeout = 30000; // 30 seconds
  private readonly halfOpenRequests = 3;

  constructor(@InjectRedis() private readonly redis: Redis) {}

  async getCircuitStatus(lpId: string): Promise<CircuitStatus> {
    const key = `circuit:lp:${lpId}`;
    const data = await this.redis.hgetall(key);

    if (!data || !data.state) {
      return { state: CircuitState.CLOSED, failures: 0 };
    }

    return {
      state: data.state as CircuitState,
      failures: parseInt(data.failures, 10) || 0,
      lastFailure: data.lastFailure ? new Date(data.lastFailure) : undefined,
      openedAt: data.openedAt ? new Date(data.openedAt) : undefined,
    };
  }

  async isOpen(lpId: string): Promise<boolean> {
    const status = await this.getCircuitStatus(lpId);

    if (status.state === CircuitState.CLOSED) {
      return false;
    }

    if (status.state === CircuitState.OPEN) {
      // Check if we should transition to half-open
      if (status.openedAt) {
        const elapsed = Date.now() - status.openedAt.getTime();
        if (elapsed >= this.resetTimeout) {
          await this.transitionToHalfOpen(lpId);
          return false; // Allow request
        }
      }
      return true;
    }

    // Half-open: allow limited requests
    return false;
  }

  async recordSuccess(lpId: string): Promise<void> {
    const status = await this.getCircuitStatus(lpId);

    if (status.state === CircuitState.HALF_OPEN) {
      // Successful request in half-open, reset circuit
      await this.resetCircuit(lpId);
      logger.info('Circuit breaker reset', { lpId });
    }

    metrics.increment('circuit.success', { lpId });
  }

  async recordFailure(lpId: string, error?: string): Promise<void> {
    const key = `circuit:lp:${lpId}`;
    const status = await this.getCircuitStatus(lpId);
    const newFailures = status.failures + 1;

    await this.redis.hset(key, {
      failures: newFailures,
      lastFailure: new Date().toISOString(),
      state: status.state,
    });

    if (newFailures >= this.failureThreshold && status.state === CircuitState.CLOSED) {
      await this.openCircuit(lpId);
      logger.warn('Circuit breaker opened', { lpId, failures: newFailures });
    }

    if (status.state === CircuitState.HALF_OPEN) {
      await this.openCircuit(lpId);
      logger.warn('Circuit breaker reopened from half-open', { lpId });
    }

    metrics.increment('circuit.failure', { lpId, error: error || 'unknown' });
  }

  private async openCircuit(lpId: string): Promise<void> {
    const key = `circuit:lp:${lpId}`;
    await this.redis.hset(key, {
      state: CircuitState.OPEN,
      openedAt: new Date().toISOString(),
    });
    await this.redis.expire(key, 300); // TTL 5 minutes
  }

  private async transitionToHalfOpen(lpId: string): Promise<void> {
    const key = `circuit:lp:${lpId}`;
    await this.redis.hset(key, {
      state: CircuitState.HALF_OPEN,
      failures: 0,
    });
  }

  private async resetCircuit(lpId: string): Promise<void> {
    const key = `circuit:lp:${lpId}`;
    await this.redis.del(key);
  }

  async getAllCircuitStates(): Promise<Record<string, CircuitStatus>> {
    const keys = await this.redis.keys('circuit:lp:*');
    const states: Record<string, CircuitStatus> = {};

    for (const key of keys) {
      const lpId = key.replace('circuit:lp:', '');
      states[lpId] = await this.getCircuitStatus(lpId);
    }

    return states;
  }
}
```

### Order Routing Service

```typescript
// services/order-service/src/routing/order-routing.service.ts
import { Injectable } from '@nestjs/common';
import { logger, metrics } from '@orion/observability';
import { OrderEntity, OrderStatus } from '../entities/order.entity';
import { RoutingStrategy, LpQuote, RoutingResult, RoutingDecision } from './routing-strategy.interface';
import { BestExecutionStrategy } from './strategies/best-execution.strategy';
import { SplitRoutingStrategy } from './strategies/split-routing.strategy';
import { SequentialRoutingStrategy } from './strategies/sequential-routing.strategy';
import { LpCircuitBreaker } from './lp-circuit-breaker';
import { LpGatewayManager } from '@orion/connectivity';
import { OrderStateMachineService } from '../state-machine/order-state-machine.service';

export interface RouteOrderResult {
  success: boolean;
  workingOrders: string[];
  routing: RoutingResult;
  errors?: string[];
}

@Injectable()
export class OrderRoutingService {
  private strategies: Map<string, RoutingStrategy>;

  constructor(
    private readonly bestExecution: BestExecutionStrategy,
    private readonly splitRouting: SplitRoutingStrategy,
    private readonly sequentialRouting: SequentialRoutingStrategy,
    private readonly circuitBreaker: LpCircuitBreaker,
    private readonly lpGateway: LpGatewayManager,
    private readonly stateMachine: OrderStateMachineService,
  ) {
    this.strategies = new Map([
      ['best', this.bestExecution],
      ['split', this.splitRouting],
      ['sequential', this.sequentialRouting],
    ]);
  }

  async routeOrder(order: OrderEntity): Promise<RouteOrderResult> {
    const startTime = Date.now();

    try {
      // Get strategy
      const strategy = this.strategies.get(order.routingStrategy || 'best');
      if (!strategy) {
        throw new Error(`Unknown routing strategy: ${order.routingStrategy}`);
      }

      // Get available LPs for instrument
      const availableLps = await this.getAvailableLps(order);

      // Filter out LPs with open circuits
      const healthyLps = await this.filterHealthyLps(availableLps);

      if (healthyLps.length === 0) {
        logger.warn('No healthy LPs available for routing', {
          orderId: order.id,
          instrument: order.instrumentId,
        });

        return {
          success: false,
          workingOrders: [],
          routing: {
            strategy: strategy.name,
            decisions: [],
            totalQuantity: 0,
            bestPrice: 0,
            routingTime: 0,
          },
          errors: ['No healthy LPs available'],
        };
      }

      // Get quotes from LPs
      const quotes = await this.getQuotes(order, healthyLps);

      if (quotes.length === 0) {
        return {
          success: false,
          workingOrders: [],
          routing: {
            strategy: strategy.name,
            decisions: [],
            totalQuantity: 0,
            bestPrice: 0,
            routingTime: 0,
          },
          errors: ['No quotes received from LPs'],
        };
      }

      // Execute routing strategy
      const routing = await strategy.route(order, quotes);

      if (routing.decisions.length === 0) {
        return {
          success: false,
          workingOrders: [],
          routing,
          errors: ['No valid routing decisions'],
        };
      }

      // Send orders to LPs
      const workingOrders = await this.sendToLps(order, routing.decisions);

      // Update order with routing info
      order.routedLps = routing.decisions.map(d => d.lpId);
      order.workingOrderIds = workingOrders;

      // Transition to WORKING
      await this.stateMachine.transition(order, OrderStatus.WORKING);

      // Metrics
      metrics.timing('order.routing.time', Date.now() - startTime);
      metrics.increment('order.routing.success');

      return {
        success: true,
        workingOrders,
        routing,
      };
    } catch (error) {
      logger.error('Order routing failed', { orderId: order.id, error });
      metrics.increment('order.routing.failure');

      return {
        success: false,
        workingOrders: [],
        routing: {
          strategy: order.routingStrategy || 'best',
          decisions: [],
          totalQuantity: 0,
          bestPrice: 0,
          routingTime: Date.now() - startTime,
        },
        errors: [error.message],
      };
    }
  }

  private async getAvailableLps(order: OrderEntity): Promise<string[]> {
    // Get LPs that support this instrument
    return this.lpGateway.getLpsForInstrument(order.instrumentId);
  }

  private async filterHealthyLps(lpIds: string[]): Promise<string[]> {
    const healthy: string[] = [];

    for (const lpId of lpIds) {
      const isOpen = await this.circuitBreaker.isOpen(lpId);
      if (!isOpen) {
        healthy.push(lpId);
      }
    }

    return healthy;
  }

  private async getQuotes(order: OrderEntity, lpIds: string[]): Promise<LpQuote[]> {
    const quotePromises = lpIds.map(async (lpId) => {
      try {
        const quote = await this.lpGateway.getQuote(lpId, {
          instrumentId: order.instrumentId,
          side: order.side,
          quantity: Number(order.remainingQuantity),
        });

        if (quote) {
          return {
            lpId,
            lpName: quote.lpName,
            price: order.side === 'buy' ? quote.ask : quote.bid,
            quantity: quote.quantity,
            validUntil: new Date(quote.validUntil),
          };
        }
        return null;
      } catch (error) {
        await this.circuitBreaker.recordFailure(lpId, error.message);
        return null;
      }
    });

    const results = await Promise.all(quotePromises);
    return results.filter((q): q is LpQuote => q !== null);
  }

  private async sendToLps(
    order: OrderEntity,
    decisions: RoutingDecision[],
  ): Promise<string[]> {
    const workingOrderIds: string[] = [];

    for (const decision of decisions) {
      try {
        const lpOrderId = await this.lpGateway.sendOrder(decision.lpId, {
          clientOrderId: order.orderRef,
          instrumentId: order.instrumentId,
          side: order.side,
          quantity: decision.quantity,
          price: order.price || decision.price,
          orderType: order.orderType,
          timeInForce: order.timeInForce,
        });

        workingOrderIds.push(lpOrderId);
        await this.circuitBreaker.recordSuccess(decision.lpId);

        logger.info('Order sent to LP', {
          orderId: order.id,
          lpId: decision.lpId,
          lpOrderId,
          quantity: decision.quantity,
        });
      } catch (error) {
        await this.circuitBreaker.recordFailure(decision.lpId, error.message);
        logger.error('Failed to send order to LP', {
          orderId: order.id,
          lpId: decision.lpId,
          error,
        });
      }
    }

    return workingOrderIds;
  }

  /**
   * Cancel working orders at LPs
   */
  async cancelWorkingOrders(order: OrderEntity): Promise<void> {
    for (let i = 0; i < order.routedLps.length; i++) {
      const lpId = order.routedLps[i];
      const lpOrderId = order.workingOrderIds[i];

      if (lpOrderId) {
        try {
          await this.lpGateway.cancelOrder(lpId, lpOrderId);
          logger.info('Working order cancelled at LP', {
            orderId: order.id,
            lpId,
            lpOrderId,
          });
        } catch (error) {
          logger.error('Failed to cancel working order at LP', {
            orderId: order.id,
            lpId,
            lpOrderId,
            error,
          });
        }
      }
    }
  }
}
```

## Definition of Done

- [ ] Best execution strategy
- [ ] Split routing strategy
- [ ] Sequential routing strategy
- [ ] Circuit breaker implementation
- [ ] LP quote aggregation
- [ ] Working order tracking
- [ ] Failover handling
- [ ] < 50ms routing latency

## Dependencies

- **US-03-01**: LP Gateway Manager
- **US-09-04**: Order State Machine

## Test Cases

```typescript
describe('OrderRoutingService', () => {
  it('should route to best price LP', async () => {
    mockLpGateway.getQuote.mockResolvedValueOnce({ lpId: 'lp-1', ask: 1.0860 });
    mockLpGateway.getQuote.mockResolvedValueOnce({ lpId: 'lp-2', ask: 1.0850 });

    const order = createOrder({ side: 'buy', routingStrategy: 'best' });
    const result = await routingService.routeOrder(order);

    expect(result.success).toBe(true);
    expect(result.routing.decisions[0].lpId).toBe('lp-2'); // Best price
  });

  it('should skip LP with open circuit', async () => {
    await circuitBreaker.openCircuit('lp-1');

    const order = createOrder();
    const result = await routingService.routeOrder(order);

    expect(result.routing.decisions.every(d => d.lpId !== 'lp-1')).toBe(true);
  });

  it('should split across LPs proportionally', async () => {
    mockLpGateway.getQuote.mockResolvedValueOnce({ lpId: 'lp-1', quantity: 500000 });
    mockLpGateway.getQuote.mockResolvedValueOnce({ lpId: 'lp-2', quantity: 500000 });

    const order = createOrder({ quantity: 1000000, routingStrategy: 'split' });
    const result = await routingService.routeOrder(order);

    expect(result.routing.decisions.length).toBe(2);
    expect(result.routing.totalQuantity).toBe(1000000);
  });

  it('should fail gracefully when no LPs available', async () => {
    mockLpGateway.getLpsForInstrument.mockResolvedValue([]);

    const order = createOrder();
    const result = await routingService.routeOrder(order);

    expect(result.success).toBe(false);
    expect(result.errors).toContain('No healthy LPs available');
  });
});
```
