# User Story: US-04-04 - Reference Data Caching

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-04-04 |
| **Epic** | Epic 04 - Reference Data Management |
| **Title** | Reference Data Caching |
| **Priority** | P1 - High |
| **Story Points** | 3 |

## User Story

**As a** system architect  
**I want** reference data cached at multiple levels  
**So that** lookups are fast and database load is minimized

## Description

Implement multi-level caching (local + Redis) for reference data with cache invalidation on updates. This ensures sub-10ms lookups for frequently accessed instruments and counterparties.

## Acceptance Criteria

- [ ] Local in-memory cache with TTL
- [ ] Redis distributed cache
- [ ] Cache invalidation on updates
- [ ] Cache-aside pattern for reads
- [ ] Metrics for hit/miss rates
- [ ] Warm-up on service start

## Technical Details

### Cache Service

```typescript
// libs/cache/src/reference-cache.service.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { Redis } from 'ioredis';
import { EventBus } from '@orion/event-model';
import { metrics } from '@orion/observability';

interface CacheEntry<T> {
  data: T;
  expiry: number;
}

const LOCAL_TTL_MS = 60_000; // 1 minute
const REDIS_TTL_SEC = 300; // 5 minutes

@Injectable()
export class ReferenceCacheService implements OnModuleInit {
  private localCache = new Map<string, CacheEntry<unknown>>();
  
  constructor(
    private readonly redis: Redis,
    private readonly eventBus: EventBus,
  ) {}

  async onModuleInit() {
    // Subscribe to invalidation events
    await this.eventBus.subscribe('instrument.updated', (e) => this.invalidate('instrument', e.payload.instrumentId));
    await this.eventBus.subscribe('instrument.created', (e) => this.invalidate('instrument', e.payload.instrumentId));
    await this.eventBus.subscribe('counterparty.updated', (e) => this.invalidate('counterparty', e.payload.counterpartyId));
  }

  async get<T>(type: string, id: string, loader: () => Promise<T>): Promise<T> {
    const key = `${type}:${id}`;
    
    // Check local cache
    const local = this.localCache.get(key) as CacheEntry<T> | undefined;
    if (local && local.expiry > Date.now()) {
      metrics.increment('cache.hit', { level: 'local', type });
      return local.data;
    }

    // Check Redis
    const redisData = await this.redis.get(key);
    if (redisData) {
      const data = JSON.parse(redisData) as T;
      this.setLocal(key, data);
      metrics.increment('cache.hit', { level: 'redis', type });
      return data;
    }

    // Load from source
    metrics.increment('cache.miss', { type });
    const data = await loader();
    await this.set(type, id, data);
    return data;
  }

  async set<T>(type: string, id: string, data: T): Promise<void> {
    const key = `${type}:${id}`;
    
    // Set in Redis
    await this.redis.setex(key, REDIS_TTL_SEC, JSON.stringify(data));
    
    // Set in local cache
    this.setLocal(key, data);
  }

  async invalidate(type: string, id: string): Promise<void> {
    const key = `${type}:${id}`;
    
    // Clear local cache
    this.localCache.delete(key);
    
    // Clear Redis
    await this.redis.del(key);
    
    // Broadcast invalidation to other instances
    await this.redis.publish('cache:invalidate', key);
    
    metrics.increment('cache.invalidate', { type });
  }

  async warmUp(type: string, loader: () => Promise<{ id: string; data: unknown }[]>): Promise<void> {
    const items = await loader();
    
    for (const item of items) {
      await this.set(type, item.id, item.data);
    }
    
    metrics.gauge('cache.warmup', items.length, { type });
  }

  private setLocal<T>(key: string, data: T): void {
    this.localCache.set(key, {
      data,
      expiry: Date.now() + LOCAL_TTL_MS,
    });
  }
}
```

### Cached Repository Pattern

```typescript
// services/reference-data-service/src/infrastructure/cached-instrument.repository.ts
import { Injectable } from '@nestjs/common';
import { InstrumentRepository } from './instrument.repository';
import { ReferenceCacheService } from '@orion/cache';
import { Instrument } from '../domain/instrument.entity';
import { getCurrentTenant } from '@orion/security';

@Injectable()
export class CachedInstrumentRepository {
  constructor(
    private readonly repo: InstrumentRepository,
    private readonly cache: ReferenceCacheService,
  ) {}

  async findById(id: string): Promise<Instrument | null> {
    const tenant = getCurrentTenant()!;
    return this.cache.get(
      'instrument',
      `${tenant.tenantId}:${id}`,
      () => this.repo.findById(id),
    );
  }

  async findBySymbol(symbol: string): Promise<Instrument | null> {
    const tenant = getCurrentTenant()!;
    return this.cache.get(
      'instrument:symbol',
      `${tenant.tenantId}:${symbol}`,
      () => this.repo.findBySymbol(symbol),
    );
  }

  // Non-cached operations delegate directly
  async create(data: Partial<Instrument>): Promise<Instrument> {
    return this.repo.create(data);
  }

  async update(id: string, data: Partial<Instrument>): Promise<Instrument> {
    // Invalidation handled by event subscription
    return this.repo.update(id, data);
  }
}
```

### Cache Warm-Up

```typescript
// services/reference-data-service/src/cache/cache-warmup.service.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { ReferenceCacheService } from '@orion/cache';
import { InstrumentRepository } from '../infrastructure/instrument.repository';
import { TenantAwarePool } from '@orion/database';
import { logger } from '@orion/observability';

@Injectable()
export class CacheWarmupService implements OnModuleInit {
  constructor(
    private readonly cache: ReferenceCacheService,
    private readonly instrumentRepo: InstrumentRepository,
  ) {}

  async onModuleInit() {
    await this.warmUpActiveInstruments();
    await this.warmUpActiveCounterparties();
    logger.info('Cache warm-up completed');
  }

  private async warmUpActiveInstruments(): Promise<void> {
    await TenantAwarePool.runAsAdmin(async () => {
      const instruments = await this.instrumentRepo.findAll({ 
        status: 'active',
        limit: 10000,
      });
      
      await this.cache.warmUp('instrument', async () =>
        instruments.items.map(i => ({ id: `${i.tenantId}:${i.id}`, data: i }))
      );
    });
  }
}
```

## Definition of Done

- [ ] Local cache working
- [ ] Redis cache working
- [ ] Invalidation on updates
- [ ] Warm-up on startup
- [ ] Hit/miss metrics exposed
- [ ] Tests verify caching

## Dependencies

- **US-04-03**: Reference Data Service
- **US-01-02**: Docker Compose (Redis)

## Test Cases

```typescript
describe('ReferenceCacheService', () => {
  it('should return from local cache on second call', async () => {
    let loadCount = 0;
    const loader = async () => { loadCount++; return { id: '1', name: 'Test' }; };
    
    await cache.get('test', '1', loader);
    await cache.get('test', '1', loader);
    
    expect(loadCount).toBe(1); // Loader called only once
  });

  it('should invalidate across levels', async () => {
    await cache.set('instrument', '1', { id: '1', symbol: 'BTC' });
    await cache.invalidate('instrument', '1');
    
    const redisValue = await redis.get('instrument:1');
    expect(redisValue).toBeNull();
  });
});
```
