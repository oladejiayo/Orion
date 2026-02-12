# User Story: US-06-05 - Market Data Subscription Manager

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-06-05 |
| **Epic** | Epic 06 - Market Data System |
| **Title** | Market Data Subscription Manager |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |
| **PRD Reference** | FR-MD-03 |

## User Story

**As a** market data service  
**I want** to manage client subscriptions efficiently  
**So that** I can route price updates only to interested clients

## Description

Build a subscription management system that tracks which clients are subscribed to which symbols, enabling efficient message routing and resource optimization.

## Acceptance Criteria

- [ ] Client-to-symbol subscription tracking
- [ ] Symbol-to-client reverse lookup
- [ ] Subscription validation against instruments
- [ ] Subscription limits enforcement
- [ ] Subscription metrics exposed
- [ ] Cleanup on client disconnect

## Technical Details

### Subscription Manager Service

```typescript
// services/market-data-service/src/distribution/subscription-manager.service.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { logger, metrics } from '@orion/observability';
import { PriceCacheService } from '../aggregation/price-cache.service';
import { SymbolMappingService } from '../normalization/symbol-mapping.service';
import { BestPrice } from '../aggregation/aggregated-price.interface';

interface SubscriptionStats {
  totalSubscriptions: number;
  uniqueSymbols: number;
  uniqueClients: number;
  subscriptionsBySymbol: Map<string, number>;
}

@Injectable()
export class SubscriptionManager implements OnModuleInit {
  // Client -> Set of symbols
  private readonly clientSubscriptions = new Map<string, Set<string>>();
  
  // Symbol -> Set of clients
  private readonly symbolSubscribers = new Map<string, Set<string>>();
  
  // Valid symbols cache
  private validSymbols = new Set<string>();

  constructor(
    private readonly priceCache: PriceCacheService,
    private readonly symbolMapping: SymbolMappingService,
  ) {}

  async onModuleInit() {
    // Load valid symbols
    this.validSymbols = new Set(this.symbolMapping.getAllSymbols());
    
    // Expose metrics
    setInterval(() => this.updateMetrics(), 10000);
  }

  /**
   * Check if a symbol is valid for subscription
   */
  isValidSymbol(symbol: string): boolean {
    return this.validSymbols.has(symbol);
  }

  /**
   * Subscribe a client to a symbol
   */
  subscribe(clientId: string, symbol: string): boolean {
    if (!this.isValidSymbol(symbol)) {
      logger.warn('Invalid symbol subscription attempt', { clientId, symbol });
      return false;
    }

    // Add to client subscriptions
    if (!this.clientSubscriptions.has(clientId)) {
      this.clientSubscriptions.set(clientId, new Set());
    }
    this.clientSubscriptions.get(clientId)!.add(symbol);

    // Add to symbol subscribers
    if (!this.symbolSubscribers.has(symbol)) {
      this.symbolSubscribers.set(symbol, new Set());
    }
    this.symbolSubscribers.get(symbol)!.add(clientId);

    logger.debug('Client subscribed', { clientId, symbol });
    return true;
  }

  /**
   * Unsubscribe a client from a symbol
   */
  unsubscribe(clientId: string, symbol: string): boolean {
    // Remove from client subscriptions
    const clientSubs = this.clientSubscriptions.get(clientId);
    if (clientSubs) {
      clientSubs.delete(symbol);
      if (clientSubs.size === 0) {
        this.clientSubscriptions.delete(clientId);
      }
    }

    // Remove from symbol subscribers
    const symbolSubs = this.symbolSubscribers.get(symbol);
    if (symbolSubs) {
      symbolSubs.delete(clientId);
      if (symbolSubs.size === 0) {
        this.symbolSubscribers.delete(symbol);
      }
    }

    logger.debug('Client unsubscribed', { clientId, symbol });
    return true;
  }

  /**
   * Remove all subscriptions for a client
   */
  removeClient(clientId: string): void {
    const subscriptions = this.clientSubscriptions.get(clientId);
    
    if (subscriptions) {
      for (const symbol of subscriptions) {
        const symbolSubs = this.symbolSubscribers.get(symbol);
        if (symbolSubs) {
          symbolSubs.delete(clientId);
          if (symbolSubs.size === 0) {
            this.symbolSubscribers.delete(symbol);
          }
        }
      }
      this.clientSubscriptions.delete(clientId);
    }

    logger.debug('Client removed', { clientId });
  }

  /**
   * Get all symbols a client is subscribed to
   */
  getClientSubscriptions(clientId: string): string[] {
    const subs = this.clientSubscriptions.get(clientId);
    return subs ? Array.from(subs) : [];
  }

  /**
   * Get all clients subscribed to a symbol
   */
  getSymbolSubscribers(symbol: string): string[] {
    const subs = this.symbolSubscribers.get(symbol);
    return subs ? Array.from(subs) : [];
  }

  /**
   * Check if a symbol has any subscribers
   */
  hasSubscribers(symbol: string): boolean {
    const subs = this.symbolSubscribers.get(symbol);
    return subs ? subs.size > 0 : false;
  }

  /**
   * Get current prices for symbols
   */
  async getCurrentPrices(symbols: string[]): Promise<BestPrice[]> {
    const prices: BestPrice[] = [];
    
    for (const symbol of symbols) {
      const price = await this.priceCache.getPrice(symbol);
      if (price) {
        prices.push(price);
      }
    }
    
    return prices;
  }

  /**
   * Get subscription statistics
   */
  getStats(): SubscriptionStats {
    const subscriptionsBySymbol = new Map<string, number>();
    
    for (const [symbol, clients] of this.symbolSubscribers) {
      subscriptionsBySymbol.set(symbol, clients.size);
    }

    let totalSubscriptions = 0;
    for (const subs of this.clientSubscriptions.values()) {
      totalSubscriptions += subs.size;
    }

    return {
      totalSubscriptions,
      uniqueSymbols: this.symbolSubscribers.size,
      uniqueClients: this.clientSubscriptions.size,
      subscriptionsBySymbol,
    };
  }

  /**
   * Refresh valid symbols (e.g., when instruments change)
   */
  refreshValidSymbols(): void {
    this.validSymbols = new Set(this.symbolMapping.getAllSymbols());
    logger.info('Valid symbols refreshed', { count: this.validSymbols.size });
  }

  private updateMetrics(): void {
    const stats = this.getStats();
    
    metrics.gauge('subscriptions.total', stats.totalSubscriptions);
    metrics.gauge('subscriptions.unique_symbols', stats.uniqueSymbols);
    metrics.gauge('subscriptions.unique_clients', stats.uniqueClients);
    
    // Top subscribed symbols
    const sortedSymbols = Array.from(stats.subscriptionsBySymbol.entries())
      .sort((a, b) => b[1] - a[1])
      .slice(0, 10);
    
    for (const [symbol, count] of sortedSymbols) {
      metrics.gauge('subscriptions.by_symbol', count, { symbol });
    }
  }
}
```

### Subscription Request/Response Types

```typescript
// services/market-data-service/src/distribution/subscription.types.ts

export interface SubscribeRequest {
  symbols: string[];
  options?: SubscriptionOptions;
}

export interface SubscriptionOptions {
  conflationMs?: number;     // Price update throttling
  includeDepth?: boolean;    // Include order book depth
  depthLevels?: number;      // Number of depth levels
}

export interface SubscribeResponse {
  subscribed: string[];
  errors?: SubscriptionError[];
  snapshot?: PriceSnapshot[];
}

export interface SubscriptionError {
  symbol: string;
  code: 'INVALID_SYMBOL' | 'LIMIT_EXCEEDED' | 'NOT_AUTHORIZED';
  message: string;
}

export interface PriceSnapshot {
  symbol: string;
  bidPrice: number;
  askPrice: number;
  timestamp: number;
}

export interface UnsubscribeRequest {
  symbols: string[];
}

export interface UnsubscribeResponse {
  unsubscribed: string[];
}
```

### Subscription Limits Service

```typescript
// services/market-data-service/src/distribution/subscription-limits.service.ts
import { Injectable } from '@nestjs/common';
import { Pool } from 'pg';
import { logger } from '@orion/observability';

interface TenantLimits {
  maxSubscriptionsPerClient: number;
  maxClientsPerTenant: number;
  allowedAssetClasses: string[];
  conflationMinMs: number;
}

@Injectable()
export class SubscriptionLimitsService {
  private readonly defaultLimits: TenantLimits = {
    maxSubscriptionsPerClient: 100,
    maxClientsPerTenant: 50,
    allowedAssetClasses: ['fx', 'crypto'],
    conflationMinMs: 10,
  };

  private tenantLimits = new Map<string, TenantLimits>();

  constructor(private readonly pool: Pool) {}

  async loadTenantLimits(): Promise<void> {
    const result = await this.pool.query(`
      SELECT 
        tenant_id,
        config->'marketData' as market_data_config
      FROM tenant_configurations
      WHERE config->'marketData' IS NOT NULL
    `);

    for (const row of result.rows) {
      const config = row.market_data_config;
      this.tenantLimits.set(row.tenant_id, {
        maxSubscriptionsPerClient: config.maxSubscriptionsPerClient || this.defaultLimits.maxSubscriptionsPerClient,
        maxClientsPerTenant: config.maxClientsPerTenant || this.defaultLimits.maxClientsPerTenant,
        allowedAssetClasses: config.allowedAssetClasses || this.defaultLimits.allowedAssetClasses,
        conflationMinMs: config.conflationMinMs || this.defaultLimits.conflationMinMs,
      });
    }

    logger.info('Tenant subscription limits loaded', { 
      count: this.tenantLimits.size,
    });
  }

  getLimits(tenantId: string): TenantLimits {
    return this.tenantLimits.get(tenantId) || this.defaultLimits;
  }

  canSubscribe(
    tenantId: string,
    currentCount: number,
    requestedCount: number,
  ): { allowed: boolean; reason?: string } {
    const limits = this.getLimits(tenantId);
    
    if (currentCount + requestedCount > limits.maxSubscriptionsPerClient) {
      return {
        allowed: false,
        reason: `Subscription limit exceeded. Max: ${limits.maxSubscriptionsPerClient}`,
      };
    }
    
    return { allowed: true };
  }

  canSubscribeAssetClass(tenantId: string, assetClass: string): boolean {
    const limits = this.getLimits(tenantId);
    return limits.allowedAssetClasses.includes(assetClass);
  }
}
```

### Subscription Persistence (Optional)

```typescript
// services/market-data-service/src/distribution/subscription-persistence.service.ts
import { Injectable } from '@nestjs/common';
import Redis from 'ioredis';
import { logger } from '@orion/observability';

interface PersistedSubscription {
  clientId: string;
  tenantId: string;
  userId: string;
  symbols: string[];
  createdAt: number;
  options?: Record<string, unknown>;
}

@Injectable()
export class SubscriptionPersistenceService {
  private readonly keyPrefix = 'subscription:';
  private readonly ttlSeconds = 3600; // 1 hour

  constructor(private readonly redis: Redis) {}

  /**
   * Persist client subscriptions (for reconnection)
   */
  async persist(subscription: PersistedSubscription): Promise<void> {
    const key = `${this.keyPrefix}${subscription.clientId}`;
    
    await this.redis.setex(
      key,
      this.ttlSeconds,
      JSON.stringify(subscription),
    );
  }

  /**
   * Retrieve persisted subscriptions
   */
  async retrieve(clientId: string): Promise<PersistedSubscription | null> {
    const key = `${this.keyPrefix}${clientId}`;
    const data = await this.redis.get(key);
    
    return data ? JSON.parse(data) : null;
  }

  /**
   * Retrieve by user (for session recovery)
   */
  async retrieveByUser(tenantId: string, userId: string): Promise<PersistedSubscription[]> {
    const pattern = `${this.keyPrefix}*`;
    const keys = await this.redis.keys(pattern);
    
    if (keys.length === 0) return [];

    const values = await this.redis.mget(keys);
    const subscriptions: PersistedSubscription[] = [];

    for (const value of values) {
      if (value) {
        const sub = JSON.parse(value);
        if (sub.tenantId === tenantId && sub.userId === userId) {
          subscriptions.push(sub);
        }
      }
    }

    return subscriptions;
  }

  /**
   * Remove persisted subscription
   */
  async remove(clientId: string): Promise<void> {
    const key = `${this.keyPrefix}${clientId}`;
    await this.redis.del(key);
  }
}
```

### Admin API for Subscriptions

```typescript
// services/market-data-service/src/distribution/subscription.controller.ts
import { Controller, Get, Query, UseGuards } from '@nestjs/common';
import { AdminGuard } from '@orion/security';
import { SubscriptionManager } from './subscription-manager.service';

@Controller('admin/subscriptions')
@UseGuards(AdminGuard)
export class SubscriptionController {
  constructor(private readonly subscriptionManager: SubscriptionManager) {}

  @Get('stats')
  getStats() {
    return this.subscriptionManager.getStats();
  }

  @Get('by-symbol')
  getBySymbol(@Query('symbol') symbol: string) {
    const subscribers = this.subscriptionManager.getSymbolSubscribers(symbol);
    return {
      symbol,
      subscriberCount: subscribers.length,
      subscribers,
    };
  }

  @Get('by-client')
  getByClient(@Query('clientId') clientId: string) {
    const subscriptions = this.subscriptionManager.getClientSubscriptions(clientId);
    return {
      clientId,
      subscriptionCount: subscriptions.length,
      subscriptions,
    };
  }

  @Get('top-symbols')
  getTopSymbols(@Query('limit') limit: number = 10) {
    const stats = this.subscriptionManager.getStats();
    
    return Array.from(stats.subscriptionsBySymbol.entries())
      .sort((a, b) => b[1] - a[1])
      .slice(0, limit)
      .map(([symbol, count]) => ({ symbol, subscribers: count }));
  }
}
```

## Definition of Done

- [ ] Subscription tracking implemented
- [ ] Reverse lookup (symbol->clients) works
- [ ] Symbol validation active
- [ ] Limits enforced per tenant
- [ ] Metrics exposed
- [ ] Cleanup on disconnect works
- [ ] Tests pass

## Dependencies

- **US-04-01**: Instrument Management
- **US-03-03**: Tenant Context Middleware

## Test Cases

```typescript
describe('SubscriptionManager', () => {
  it('should track client subscriptions', () => {
    manager.subscribe('client-1', 'EUR/USD');
    manager.subscribe('client-1', 'GBP/USD');
    
    const subs = manager.getClientSubscriptions('client-1');
    
    expect(subs).toContain('EUR/USD');
    expect(subs).toContain('GBP/USD');
  });

  it('should track symbol subscribers', () => {
    manager.subscribe('client-1', 'EUR/USD');
    manager.subscribe('client-2', 'EUR/USD');
    
    const subscribers = manager.getSymbolSubscribers('EUR/USD');
    
    expect(subscribers).toContain('client-1');
    expect(subscribers).toContain('client-2');
  });

  it('should reject invalid symbols', () => {
    const result = manager.subscribe('client-1', 'INVALID/SYMBOL');
    
    expect(result).toBe(false);
  });

  it('should cleanup on client removal', () => {
    manager.subscribe('client-1', 'EUR/USD');
    manager.subscribe('client-1', 'GBP/USD');
    
    manager.removeClient('client-1');
    
    expect(manager.getClientSubscriptions('client-1')).toHaveLength(0);
    expect(manager.getSymbolSubscribers('EUR/USD')).not.toContain('client-1');
  });
});
```
