# User Story: US-06-03 - Best Price Aggregation Engine

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-06-03 |
| **Epic** | Epic 06 - Market Data System |
| **Title** | Best Price Aggregation Engine |
| **Priority** | P0 - Critical |
| **Story Points** | 8 |
| **PRD Reference** | FR-MD-02 |

## User Story

**As a** trader  
**I want** to see the best bid and offer across all liquidity providers  
**So that** I can make informed trading decisions with optimal pricing

## Description

Build an aggregation engine that combines prices from multiple sources per symbol, calculates best bid/offer, VWAP, and composite pricing in real-time.

## Acceptance Criteria

- [ ] Best bid/ask calculated across all sources
- [ ] VWAP calculation for size tiers
- [ ] Composite pricing with configurable weights
- [ ] Stale source detection and exclusion
- [ ] Real-time aggregated prices published
- [ ] Per-symbol aggregation state maintained

## Technical Details

### Aggregated Price Interface

```typescript
// services/market-data-service/src/aggregation/aggregated-price.interface.ts
export interface SourcePrice {
  source: string;
  bidPrice: number;
  bidSize: number;
  askPrice: number;
  askSize: number;
  timestamp: number;
  isStale: boolean;
}

export interface BestPrice {
  symbol: string;
  
  // Best bid across all sources
  bestBid: {
    price: number;
    size: number;
    source: string;
  };
  
  // Best ask across all sources
  bestAsk: {
    price: number;
    size: number;
    source: string;
  };
  
  // Composite pricing
  midPrice: number;
  spread: number;
  spreadBps: number;
  
  // Volume-weighted metrics
  vwapBid: number;
  vwapAsk: number;
  totalBidDepth: number;
  totalAskDepth: number;
  
  // Source information
  activeSources: string[];
  staleSources: string[];
  sourceCount: number;
  
  // Timestamps
  timestamp: number;
  lastUpdate: number;
}

export interface DepthLevel {
  price: number;
  size: number;
  source: string;
}

export interface AggregatedDepth {
  symbol: string;
  bids: DepthLevel[];
  asks: DepthLevel[];
  timestamp: number;
}
```

### Aggregation Engine

```typescript
// services/market-data-service/src/aggregation/aggregation-engine.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { logger, metrics } from '@orion/observability';
import { EventBus, EventConsumerService } from '@orion/event-model';
import { NormalizedPrice } from '../normalization/normalized-price.interface';
import { BestPrice, SourcePrice, AggregatedDepth, DepthLevel } from './aggregated-price.interface';

interface SymbolState {
  sources: Map<string, SourcePrice>;
  lastAggregation: number;
  sequenceNumber: number;
}

@Injectable()
export class AggregationEngine implements OnModuleInit {
  private readonly symbolStates = new Map<string, SymbolState>();
  private readonly stalenessThresholdMs = 5000;  // 5 seconds
  private readonly aggregationIntervalMs = 10;    // 10ms minimum

  constructor(
    private readonly eventBus: EventBus,
    private readonly consumer: EventConsumerService,
  ) {}

  async onModuleInit() {
    // Subscribe to normalized prices
    this.consumer.registerHandler({
      eventTypes: ['market-data.normalized-price'],
      handle: async (event) => {
        await this.handleNormalizedPrice(event.payload as NormalizedPrice);
      },
    });

    // Start stale source cleanup
    setInterval(() => this.cleanupStaleSources(), 1000);
  }

  async handleNormalizedPrice(price: NormalizedPrice): Promise<void> {
    const { symbol, source } = price;

    // Get or create symbol state
    let state = this.symbolStates.get(symbol);
    if (!state) {
      state = {
        sources: new Map(),
        lastAggregation: 0,
        sequenceNumber: 0,
      };
      this.symbolStates.set(symbol, state);
    }

    // Update source price
    state.sources.set(source, {
      source,
      bidPrice: price.bidPrice,
      bidSize: price.bidSize,
      askPrice: price.askPrice,
      askSize: price.askSize,
      timestamp: price.normalizedTimestamp,
      isStale: false,
    });

    // Throttle aggregation
    const now = Date.now();
    if (now - state.lastAggregation >= this.aggregationIntervalMs) {
      await this.aggregate(symbol, state);
      state.lastAggregation = now;
    }
  }

  private async aggregate(symbol: string, state: SymbolState): Promise<void> {
    const activeSources: SourcePrice[] = [];
    const staleSources: string[] = [];
    const now = Date.now();

    // Filter active vs stale sources
    for (const [source, price] of state.sources) {
      if (now - price.timestamp > this.stalenessThresholdMs) {
        price.isStale = true;
        staleSources.push(source);
      } else {
        activeSources.push(price);
      }
    }

    if (activeSources.length === 0) {
      logger.debug('No active sources for symbol', { symbol });
      return;
    }

    // Calculate best bid (highest)
    const bestBidSource = activeSources.reduce((best, current) =>
      current.bidPrice > best.bidPrice ? current : best
    );

    // Calculate best ask (lowest)
    const bestAskSource = activeSources.reduce((best, current) =>
      current.askPrice < best.askPrice ? current : best
    );

    // Calculate VWAP
    const { vwapBid, totalBidDepth } = this.calculateVwapBid(activeSources);
    const { vwapAsk, totalAskDepth } = this.calculateVwapAsk(activeSources);

    // Build aggregated price
    const bestPrice: BestPrice = {
      symbol,
      bestBid: {
        price: bestBidSource.bidPrice,
        size: bestBidSource.bidSize,
        source: bestBidSource.source,
      },
      bestAsk: {
        price: bestAskSource.askPrice,
        size: bestAskSource.askSize,
        source: bestAskSource.source,
      },
      midPrice: (bestBidSource.bidPrice + bestAskSource.askPrice) / 2,
      spread: bestAskSource.askPrice - bestBidSource.bidPrice,
      spreadBps: ((bestAskSource.askPrice - bestBidSource.bidPrice) / 
                  ((bestBidSource.bidPrice + bestAskSource.askPrice) / 2)) * 10000,
      vwapBid,
      vwapAsk,
      totalBidDepth,
      totalAskDepth,
      activeSources: activeSources.map(s => s.source),
      staleSources,
      sourceCount: activeSources.length,
      timestamp: now,
      lastUpdate: Math.max(...activeSources.map(s => s.timestamp)),
    };

    // Publish aggregated price
    await this.eventBus.publish({
      topic: 'orion.market-data.aggregated',
      eventType: 'market-data.best-price',
      aggregateType: 'market-data',
      aggregateId: symbol,
      payload: bestPrice,
      metadata: {
        symbol,
        sourceCount: String(activeSources.length),
      },
    });

    metrics.gauge('aggregation.sources', activeSources.length, { symbol });
    metrics.gauge('aggregation.spread_bps', bestPrice.spreadBps, { symbol });
  }

  private calculateVwapBid(sources: SourcePrice[]): { vwapBid: number; totalBidDepth: number } {
    let weightedSum = 0;
    let totalSize = 0;

    for (const source of sources) {
      weightedSum += source.bidPrice * source.bidSize;
      totalSize += source.bidSize;
    }

    return {
      vwapBid: totalSize > 0 ? weightedSum / totalSize : 0,
      totalBidDepth: totalSize,
    };
  }

  private calculateVwapAsk(sources: SourcePrice[]): { vwapAsk: number; totalAskDepth: number } {
    let weightedSum = 0;
    let totalSize = 0;

    for (const source of sources) {
      weightedSum += source.askPrice * source.askSize;
      totalSize += source.askSize;
    }

    return {
      vwapAsk: totalSize > 0 ? weightedSum / totalSize : 0,
      totalAskDepth: totalSize,
    };
  }

  private cleanupStaleSources(): void {
    const now = Date.now();
    
    for (const [symbol, state] of this.symbolStates) {
      for (const [source, price] of state.sources) {
        if (now - price.timestamp > this.stalenessThresholdMs * 2) {
          state.sources.delete(source);
          logger.debug('Removed stale source', { symbol, source });
        }
      }
    }
  }

  /**
   * Get current best price for a symbol
   */
  getBestPrice(symbol: string): BestPrice | null {
    const state = this.symbolStates.get(symbol);
    if (!state || state.sources.size === 0) {
      return null;
    }

    // Recalculate on-demand
    const activeSources = Array.from(state.sources.values())
      .filter(s => !s.isStale);
    
    if (activeSources.length === 0) return null;

    const bestBidSource = activeSources.reduce((best, current) =>
      current.bidPrice > best.bidPrice ? current : best
    );
    const bestAskSource = activeSources.reduce((best, current) =>
      current.askPrice < best.askPrice ? current : best
    );

    return {
      symbol,
      bestBid: {
        price: bestBidSource.bidPrice,
        size: bestBidSource.bidSize,
        source: bestBidSource.source,
      },
      bestAsk: {
        price: bestAskSource.askPrice,
        size: bestAskSource.askSize,
        source: bestAskSource.source,
      },
      midPrice: (bestBidSource.bidPrice + bestAskSource.askPrice) / 2,
      spread: bestAskSource.askPrice - bestBidSource.bidPrice,
      spreadBps: ((bestAskSource.askPrice - bestBidSource.bidPrice) / 
                  ((bestBidSource.bidPrice + bestAskSource.askPrice) / 2)) * 10000,
      vwapBid: 0,
      vwapAsk: 0,
      totalBidDepth: 0,
      totalAskDepth: 0,
      activeSources: activeSources.map(s => s.source),
      staleSources: [],
      sourceCount: activeSources.length,
      timestamp: Date.now(),
      lastUpdate: Math.max(...activeSources.map(s => s.timestamp)),
    };
  }

  /**
   * Get aggregated depth (order book)
   */
  getAggregatedDepth(symbol: string, levels: number = 5): AggregatedDepth | null {
    const state = this.symbolStates.get(symbol);
    if (!state) return null;

    const activeSources = Array.from(state.sources.values())
      .filter(s => !s.isStale);

    // Aggregate bids (sorted descending by price)
    const bids: DepthLevel[] = activeSources
      .map(s => ({ price: s.bidPrice, size: s.bidSize, source: s.source }))
      .sort((a, b) => b.price - a.price)
      .slice(0, levels);

    // Aggregate asks (sorted ascending by price)
    const asks: DepthLevel[] = activeSources
      .map(s => ({ price: s.askPrice, size: s.askSize, source: s.source }))
      .sort((a, b) => a.price - b.price)
      .slice(0, levels);

    return {
      symbol,
      bids,
      asks,
      timestamp: Date.now(),
    };
  }
}
```

### Redis Price Cache

```typescript
// services/market-data-service/src/aggregation/price-cache.service.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import Redis from 'ioredis';
import { logger } from '@orion/observability';
import { EventConsumerService } from '@orion/event-model';
import { BestPrice } from './aggregated-price.interface';

@Injectable()
export class PriceCacheService implements OnModuleInit {
  private readonly keyPrefix = 'price:';
  private readonly ttlSeconds = 60;

  constructor(
    private readonly redis: Redis,
    private readonly consumer: EventConsumerService,
  ) {}

  async onModuleInit() {
    // Subscribe to aggregated prices and cache
    this.consumer.registerHandler({
      eventTypes: ['market-data.best-price'],
      handle: async (event) => {
        const price = event.payload as BestPrice;
        await this.cachePrice(price);
      },
    });
  }

  private async cachePrice(price: BestPrice): Promise<void> {
    const key = `${this.keyPrefix}${price.symbol}`;
    
    await this.redis.setex(
      key,
      this.ttlSeconds,
      JSON.stringify(price),
    );
  }

  /**
   * Get cached price for symbol
   */
  async getPrice(symbol: string): Promise<BestPrice | null> {
    const key = `${this.keyPrefix}${symbol}`;
    const data = await this.redis.get(key);
    
    return data ? JSON.parse(data) : null;
  }

  /**
   * Get prices for multiple symbols
   */
  async getPrices(symbols: string[]): Promise<Map<string, BestPrice>> {
    const keys = symbols.map(s => `${this.keyPrefix}${s}`);
    const values = await this.redis.mget(keys);
    
    const result = new Map<string, BestPrice>();
    
    symbols.forEach((symbol, index) => {
      if (values[index]) {
        result.set(symbol, JSON.parse(values[index]!));
      }
    });

    return result;
  }

  /**
   * Get all cached prices
   */
  async getAllPrices(): Promise<BestPrice[]> {
    const keys = await this.redis.keys(`${this.keyPrefix}*`);
    
    if (keys.length === 0) return [];

    const values = await this.redis.mget(keys);
    return values
      .filter(v => v !== null)
      .map(v => JSON.parse(v!));
  }
}
```

### Weighted Composite Pricing

```typescript
// services/market-data-service/src/aggregation/composite-pricing.service.ts
import { Injectable } from '@nestjs/common';
import { SourcePrice } from './aggregated-price.interface';

interface SourceWeight {
  source: string;
  weight: number;
}

export interface CompositePrice {
  symbol: string;
  compositeBid: number;
  compositeAsk: number;
  compositeMid: number;
  weights: SourceWeight[];
  timestamp: number;
}

@Injectable()
export class CompositePricingService {
  // Default weights (can be loaded from config/DB)
  private readonly sourceWeights: Map<string, number> = new Map([
    ['lp-alpha', 0.3],
    ['lp-beta', 0.25],
    ['binance', 0.2],
    ['coinbase', 0.15],
    ['kraken', 0.1],
  ]);

  /**
   * Calculate weighted composite price
   */
  calculateComposite(symbol: string, sources: SourcePrice[]): CompositePrice {
    const activeSources = sources.filter(s => !s.isStale);
    
    if (activeSources.length === 0) {
      throw new Error('No active sources for composite calculation');
    }

    // Calculate total available weight
    let totalWeight = 0;
    const sourceWeightsList: SourceWeight[] = [];

    for (const source of activeSources) {
      const weight = this.sourceWeights.get(source.source) || 0.1;
      totalWeight += weight;
      sourceWeightsList.push({ source: source.source, weight });
    }

    // Normalize weights
    const normalizedWeights = sourceWeightsList.map(sw => ({
      source: sw.source,
      weight: sw.weight / totalWeight,
    }));

    // Calculate weighted prices
    let compositeBid = 0;
    let compositeAsk = 0;

    for (let i = 0; i < activeSources.length; i++) {
      const source = activeSources[i];
      const normalizedWeight = normalizedWeights[i].weight;
      
      compositeBid += source.bidPrice * normalizedWeight;
      compositeAsk += source.askPrice * normalizedWeight;
    }

    return {
      symbol,
      compositeBid,
      compositeAsk,
      compositeMid: (compositeBid + compositeAsk) / 2,
      weights: normalizedWeights,
      timestamp: Date.now(),
    };
  }

  /**
   * Update source weight
   */
  setSourceWeight(source: string, weight: number): void {
    if (weight < 0 || weight > 1) {
      throw new Error('Weight must be between 0 and 1');
    }
    this.sourceWeights.set(source, weight);
  }
}
```

## Definition of Done

- [ ] Best bid/ask aggregation works
- [ ] VWAP calculation correct
- [ ] Stale source detection active
- [ ] Composite pricing available
- [ ] Redis caching implemented
- [ ] Aggregated prices published
- [ ] Tests pass

## Dependencies

- **US-06-02**: Price Normalization Layer

## Test Cases

```typescript
describe('AggregationEngine', () => {
  it('should calculate best bid across sources', () => {
    const sources: SourcePrice[] = [
      { source: 'lp-a', bidPrice: 100.50, askPrice: 100.60, bidSize: 1000, askSize: 1000, timestamp: Date.now(), isStale: false },
      { source: 'lp-b', bidPrice: 100.55, askPrice: 100.65, bidSize: 500, askSize: 500, timestamp: Date.now(), isStale: false },
    ];

    const bestPrice = engine.aggregate('EUR/USD', sources);

    expect(bestPrice.bestBid.price).toBe(100.55);  // Highest bid
    expect(bestPrice.bestBid.source).toBe('lp-b');
  });

  it('should exclude stale sources', () => {
    const sources: SourcePrice[] = [
      { source: 'lp-a', bidPrice: 100.50, askPrice: 100.60, bidSize: 1000, askSize: 1000, timestamp: Date.now(), isStale: false },
      { source: 'lp-b', bidPrice: 100.55, askPrice: 100.65, bidSize: 500, askSize: 500, timestamp: Date.now() - 10000, isStale: true },
    ];

    const bestPrice = engine.aggregate('EUR/USD', sources);

    expect(bestPrice.activeSources).toEqual(['lp-a']);
    expect(bestPrice.staleSources).toEqual(['lp-b']);
  });

  it('should calculate VWAP correctly', () => {
    const sources: SourcePrice[] = [
      { source: 'lp-a', bidPrice: 100, askPrice: 101, bidSize: 1000, askSize: 1000, timestamp: Date.now(), isStale: false },
      { source: 'lp-b', bidPrice: 102, askPrice: 103, bidSize: 1000, askSize: 1000, timestamp: Date.now(), isStale: false },
    ];

    const bestPrice = engine.aggregate('TEST', sources);

    // VWAP = (100*1000 + 102*1000) / 2000 = 101
    expect(bestPrice.vwapBid).toBe(101);
  });
});
```
