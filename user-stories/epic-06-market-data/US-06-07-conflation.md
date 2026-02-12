# User Story: US-06-07 - Market Data Conflation

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-06-07 |
| **Epic** | Epic 06 - Market Data System |
| **Title** | Market Data Conflation |
| **Priority** | P1 - High |
| **Story Points** | 5 |
| **PRD Reference** | FR-MD-03 |

## User Story

**As a** client application  
**I want** to receive price updates at a configurable rate  
**So that** I can balance data freshness with processing capacity

## Description

Implement conflation (throttling) of price updates on a per-client basis, allowing clients to specify their desired update frequency to prevent overwhelming slower consumers.

## Acceptance Criteria

- [ ] Per-client conflation intervals configurable
- [ ] Always send latest price (no outdated data)
- [ ] Conflation at distribution layer, not source
- [ ] Support for different intervals per symbol
- [ ] Metrics on conflation effectiveness
- [ ] Configurable via subscription options

## Technical Details

### Conflation Service

```typescript
// services/market-data-service/src/distribution/conflation.service.ts
import { Injectable } from '@nestjs/common';
import { logger, metrics } from '@orion/observability';
import { BestPrice } from '../aggregation/aggregated-price.interface';

interface ConflationState {
  latestPrice: BestPrice | null;
  lastSent: number;
  pendingSend: boolean;
  timer: NodeJS.Timeout | null;
}

interface ClientConflationConfig {
  defaultIntervalMs: number;
  symbolOverrides: Map<string, number>;
}

@Injectable()
export class ConflationService {
  // clientId -> symbol -> conflation state
  private readonly conflationStates = new Map<string, Map<string, ConflationState>>();
  
  // clientId -> config
  private readonly clientConfigs = new Map<string, ClientConflationConfig>();

  // Callback to send price to client
  private sendCallback?: (clientId: string, price: BestPrice) => void;

  /**
   * Set the callback for sending conflated prices
   */
  setSendCallback(callback: (clientId: string, price: BestPrice) => void): void {
    this.sendCallback = callback;
  }

  /**
   * Configure conflation for a client
   */
  configureClient(
    clientId: string,
    config: {
      defaultIntervalMs?: number;
      symbolIntervals?: Record<string, number>;
    },
  ): void {
    const symbolOverrides = new Map<string, number>();
    
    if (config.symbolIntervals) {
      for (const [symbol, interval] of Object.entries(config.symbolIntervals)) {
        symbolOverrides.set(symbol, interval);
      }
    }

    this.clientConfigs.set(clientId, {
      defaultIntervalMs: config.defaultIntervalMs || 100, // Default 100ms
      symbolOverrides,
    });

    logger.debug('Client conflation configured', { clientId, config });
  }

  /**
   * Get conflation interval for a client/symbol
   */
  getInterval(clientId: string, symbol: string): number {
    const config = this.clientConfigs.get(clientId);
    if (!config) {
      return 100; // Default fallback
    }

    return config.symbolOverrides.get(symbol) || config.defaultIntervalMs;
  }

  /**
   * Process an incoming price update for a client
   */
  processPrice(clientId: string, price: BestPrice): void {
    const { symbol } = price;
    
    // Get or create client's conflation states
    if (!this.conflationStates.has(clientId)) {
      this.conflationStates.set(clientId, new Map());
    }
    const clientStates = this.conflationStates.get(clientId)!;

    // Get or create state for this symbol
    if (!clientStates.has(symbol)) {
      clientStates.set(symbol, {
        latestPrice: null,
        lastSent: 0,
        pendingSend: false,
        timer: null,
      });
    }
    const state = clientStates.get(symbol)!;

    // Always store latest price
    state.latestPrice = price;

    const now = Date.now();
    const interval = this.getInterval(clientId, symbol);

    // Check if we should send immediately
    if (now - state.lastSent >= interval) {
      this.sendPrice(clientId, symbol, state);
    } else if (!state.pendingSend) {
      // Schedule delayed send
      const delay = interval - (now - state.lastSent);
      state.pendingSend = true;
      
      state.timer = setTimeout(() => {
        this.sendPrice(clientId, symbol, state);
      }, delay);
    }
    // Otherwise, a send is already pending - latest price will be sent

    metrics.increment('conflation.prices_received', { symbol });
  }

  private sendPrice(
    clientId: string,
    symbol: string,
    state: ConflationState,
  ): void {
    if (!state.latestPrice || !this.sendCallback) {
      return;
    }

    // Clear any pending timer
    if (state.timer) {
      clearTimeout(state.timer);
      state.timer = null;
    }

    // Send the latest price
    this.sendCallback(clientId, state.latestPrice);

    // Update state
    state.lastSent = Date.now();
    state.pendingSend = false;

    metrics.increment('conflation.prices_sent', { symbol });
  }

  /**
   * Remove conflation state for a client
   */
  removeClient(clientId: string): void {
    const clientStates = this.conflationStates.get(clientId);
    
    if (clientStates) {
      // Clear all timers
      for (const state of clientStates.values()) {
        if (state.timer) {
          clearTimeout(state.timer);
        }
      }
      this.conflationStates.delete(clientId);
    }

    this.clientConfigs.delete(clientId);
  }

  /**
   * Remove conflation for a specific symbol
   */
  removeSubscription(clientId: string, symbol: string): void {
    const clientStates = this.conflationStates.get(clientId);
    
    if (clientStates) {
      const state = clientStates.get(symbol);
      if (state?.timer) {
        clearTimeout(state.timer);
      }
      clientStates.delete(symbol);
    }
  }

  /**
   * Get conflation statistics
   */
  getStats(): {
    totalClients: number;
    totalSymbols: number;
    avgConflationRatio: number;
  } {
    let totalSymbols = 0;
    
    for (const clientStates of this.conflationStates.values()) {
      totalSymbols += clientStates.size;
    }

    return {
      totalClients: this.conflationStates.size,
      totalSymbols,
      avgConflationRatio: 0, // Would need to track received vs sent
    };
  }
}
```

### Conflation-Aware Gateway

```typescript
// services/market-data-service/src/distribution/conflated-gateway.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { EventConsumerService } from '@orion/event-model';
import { MarketDataGateway } from './market-data.gateway';
import { ConflationService } from './conflation.service';
import { SubscriptionManager } from './subscription-manager.service';
import { BestPrice } from '../aggregation/aggregated-price.interface';

@Injectable()
export class ConflatedGatewayService implements OnModuleInit {
  constructor(
    private readonly consumer: EventConsumerService,
    private readonly gateway: MarketDataGateway,
    private readonly conflation: ConflationService,
    private readonly subscriptions: SubscriptionManager,
  ) {}

  async onModuleInit() {
    // Configure conflation to send via gateway
    this.conflation.setSendCallback((clientId, price) => {
      this.gateway.sendToClient(clientId, 'price', price);
    });

    // Subscribe to aggregated prices
    this.consumer.registerHandler({
      eventTypes: ['market-data.best-price'],
      handle: async (event) => {
        const price = event.payload as BestPrice;
        await this.distributePrice(price);
      },
    });
  }

  private async distributePrice(price: BestPrice): Promise<void> {
    // Get all clients subscribed to this symbol
    const subscribers = this.subscriptions.getSymbolSubscribers(price.symbol);

    // Process through conflation for each subscriber
    for (const clientId of subscribers) {
      this.conflation.processPrice(clientId, price);
    }
  }
}
```

### Extended Subscription Options

```typescript
// services/market-data-service/src/distribution/subscription-with-conflation.ts
import { SubscribeMessage, ConnectedSocket, MessageBody } from '@nestjs/websockets';
import { Socket } from 'socket.io';
import { ConflationService } from './conflation.service';

interface SubscribeWithConflationRequest {
  symbols: string[];
  conflationMs?: number;          // Global conflation for this subscription
  symbolConflation?: {            // Per-symbol overrides
    [symbol: string]: number;
  };
}

export class SubscriptionWithConflation {
  constructor(private readonly conflation: ConflationService) {}

  @SubscribeMessage('subscribe')
  async handleSubscribe(
    @ConnectedSocket() client: Socket,
    @MessageBody() data: SubscribeWithConflationRequest,
  ) {
    const { symbols, conflationMs, symbolConflation } = data;

    // Configure conflation for this client
    this.conflation.configureClient(client.id, {
      defaultIntervalMs: conflationMs || 100,
      symbolIntervals: symbolConflation,
    });

    // ... rest of subscription logic
  }

  @SubscribeMessage('updateConflation')
  handleUpdateConflation(
    @ConnectedSocket() client: Socket,
    @MessageBody() data: {
      conflationMs?: number;
      symbolConflation?: Record<string, number>;
    },
  ) {
    this.conflation.configureClient(client.id, {
      defaultIntervalMs: data.conflationMs,
      symbolIntervals: data.symbolConflation,
    });

    return { updated: true };
  }
}
```

### Adaptive Conflation

```typescript
// services/market-data-service/src/distribution/adaptive-conflation.service.ts
import { Injectable } from '@nestjs/common';
import { logger, metrics } from '@orion/observability';
import { ConflationService } from './conflation.service';

interface ClientPerformance {
  clientId: string;
  avgProcessingTime: number;
  queueDepth: number;
  lastAdjustment: number;
}

@Injectable()
export class AdaptiveConflationService {
  private readonly clientPerformance = new Map<string, ClientPerformance>();
  private readonly minInterval = 10;    // Minimum 10ms
  private readonly maxInterval = 1000;  // Maximum 1 second
  private readonly targetQueueDepth = 10;

  constructor(private readonly conflation: ConflationService) {}

  /**
   * Record client message processing time
   */
  recordProcessingTime(clientId: string, durationMs: number): void {
    let perf = this.clientPerformance.get(clientId);
    
    if (!perf) {
      perf = {
        clientId,
        avgProcessingTime: durationMs,
        queueDepth: 0,
        lastAdjustment: 0,
      };
      this.clientPerformance.set(clientId, perf);
    }

    // Exponential moving average
    perf.avgProcessingTime = perf.avgProcessingTime * 0.9 + durationMs * 0.1;

    // Check if we need to adjust
    this.maybeAdjust(clientId, perf);
  }

  /**
   * Record client queue depth (for slow consumers)
   */
  recordQueueDepth(clientId: string, depth: number): void {
    const perf = this.clientPerformance.get(clientId);
    if (perf) {
      perf.queueDepth = depth;
      this.maybeAdjust(clientId, perf);
    }
  }

  private maybeAdjust(clientId: string, perf: ClientPerformance): void {
    const now = Date.now();
    
    // Don't adjust more than once per second
    if (now - perf.lastAdjustment < 1000) {
      return;
    }

    const currentInterval = this.conflation.getInterval(clientId, '*');
    let newInterval = currentInterval;

    // If queue is backing up, increase interval
    if (perf.queueDepth > this.targetQueueDepth * 2) {
      newInterval = Math.min(currentInterval * 1.5, this.maxInterval);
    } else if (perf.queueDepth < this.targetQueueDepth && perf.avgProcessingTime < currentInterval * 0.5) {
      // If processing is fast and queue is low, decrease interval
      newInterval = Math.max(currentInterval * 0.75, this.minInterval);
    }

    if (newInterval !== currentInterval) {
      this.conflation.configureClient(clientId, {
        defaultIntervalMs: newInterval,
      });

      logger.info('Adaptive conflation adjusted', {
        clientId,
        oldInterval: currentInterval,
        newInterval,
        queueDepth: perf.queueDepth,
        avgProcessingTime: perf.avgProcessingTime,
      });

      metrics.gauge('conflation.adaptive_interval', newInterval, { clientId });
      perf.lastAdjustment = now;
    }
  }

  /**
   * Remove client tracking
   */
  removeClient(clientId: string): void {
    this.clientPerformance.delete(clientId);
  }
}
```

### Conflation Metrics Dashboard Query

```typescript
// Grafana dashboard query examples
const grafanaQueries = {
  // Conflation ratio by symbol
  conflationRatio: `
    sum(rate(conflation_prices_sent_total[1m])) by (symbol) /
    sum(rate(conflation_prices_received_total[1m])) by (symbol)
  `,
  
  // Average conflation interval
  avgInterval: `
    avg(conflation_adaptive_interval) by (clientId)
  `,
  
  // Dropped updates (conflated)
  droppedUpdates: `
    sum(rate(conflation_prices_received_total[1m])) -
    sum(rate(conflation_prices_sent_total[1m]))
  `,
};
```

## Definition of Done

- [ ] Per-client conflation implemented
- [ ] Always sends latest price
- [ ] Configurable intervals working
- [ ] Per-symbol overrides supported
- [ ] Adaptive conflation optional
- [ ] Metrics exposed
- [ ] Tests pass

## Dependencies

- **US-06-04**: Market Data Distribution (WebSocket)
- **US-06-05**: Market Data Subscription Manager

## Test Cases

```typescript
describe('ConflationService', () => {
  it('should conflate rapid updates', async () => {
    const sent: BestPrice[] = [];
    conflation.setSendCallback((clientId, price) => sent.push(price));
    conflation.configureClient('client-1', { defaultIntervalMs: 100 });

    // Send 10 updates rapidly
    for (let i = 0; i < 10; i++) {
      conflation.processPrice('client-1', mockPrice(i));
    }

    await sleep(150);

    // Should have sent 2 updates (initial + after interval)
    expect(sent.length).toBeLessThan(10);
    // Last price should be the latest
    expect(sent[sent.length - 1]).toEqual(mockPrice(9));
  });

  it('should support per-symbol intervals', () => {
    conflation.configureClient('client-1', {
      defaultIntervalMs: 100,
      symbolIntervals: {
        'BTC/USD': 50,    // Faster for crypto
        'EUR/USD': 200,   // Slower for FX
      },
    });

    expect(conflation.getInterval('client-1', 'BTC/USD')).toBe(50);
    expect(conflation.getInterval('client-1', 'EUR/USD')).toBe(200);
    expect(conflation.getInterval('client-1', 'GBP/USD')).toBe(100);
  });

  it('should clean up on client removal', () => {
    conflation.configureClient('client-1', { defaultIntervalMs: 100 });
    conflation.processPrice('client-1', mockPrice());

    conflation.removeClient('client-1');

    expect(conflation.getStats().totalClients).toBe(0);
  });
});
```
