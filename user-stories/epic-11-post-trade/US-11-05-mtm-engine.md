# User Story: US-11-05 - Mark-to-Market Engine

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-11-05 |
| **Epic** | Epic 11 - Post-Trade Services |
| **Title** | Mark-to-Market Engine |
| **Priority** | P1 - High |
| **Story Points** | 5 |
| **PRD Reference** | FR-PostTrade-05, NFR-PostTrade-05 |

## User Story

**As a** risk manager  
**I want** automated mark-to-market valuation of all positions  
**So that** portfolio values and P&L reflect current market prices

## Description

Implement MTM engine for real-time and batch position valuation using live market prices, supporting multiple pricing sources, fallback logic, and audit trail of valuations.

## Acceptance Criteria

- [ ] Real-time MTM from streaming prices
- [ ] Batch MTM for EOD valuation
- [ ] Multiple price source support
- [ ] Price source fallback logic
- [ ] Stale price detection
- [ ] Manual price override
- [ ] MTM history retention
- [ ] Performance: 1M positions/minute

## Technical Details

### MTM Valuation Entity

```typescript
// services/post-trade-service/src/entities/mtm-valuation.entity.ts
import { Entity, Column, CreateDateColumn, Index } from 'typeorm';

export enum PriceSource {
  LIVE_FEED = 'live_feed',
  FIXING = 'fixing',
  MANUAL = 'manual',
  FALLBACK = 'fallback',
  INTERPOLATED = 'interpolated',
}

@Entity('mtm_valuations')
@Index(['tenantId', 'positionId', 'valuationTime'])
export class MtmValuationEntity {
  @Column('uuid', { primaryKey: true, default: () => 'gen_random_uuid()' })
  id: string;

  @Column('uuid')
  tenantId: string;

  @Column('uuid')
  positionId: string;

  @Column('uuid')
  instrumentId: string;

  @Column('timestamp with time zone')
  valuationTime: Date;

  @Column('decimal', { precision: 20, scale: 8 })
  markPrice: number;

  @Column('varchar', { length: 20 })
  priceSource: PriceSource;

  @Column('varchar', { length: 100, nullable: true })
  priceProvider: string;

  @Column('decimal', { precision: 20, scale: 8 })
  quantity: number;

  @Column('decimal', { precision: 20, scale: 4 })
  marketValue: number;

  @Column('decimal', { precision: 20, scale: 4 })
  previousMarketValue: number;

  @Column('decimal', { precision: 20, scale: 4 })
  mtmPnL: number;

  @Column('varchar', { length: 3 })
  currency: string;

  @Column('boolean', { default: false })
  isStale: boolean;

  @Column('varchar', { length: 100, nullable: true })
  overrideBy: string;

  @Column('varchar', { length: 500, nullable: true })
  overrideReason: string;

  @CreateDateColumn()
  createdAt: Date;
}
```

### MTM Engine Service

```typescript
// services/post-trade-service/src/mtm/mtm-engine.service.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { InjectRepository, InjectEntityManager } from '@nestjs/typeorm';
import { Repository, EntityManager, In } from 'typeorm';
import { Redis } from 'ioredis';
import { InjectRedis } from '@nestjs-modules/ioredis';
import { Cron, CronExpression } from '@nestjs/schedule';
import { logger, metrics } from '@orion/observability';
import { EventPublisher } from '@orion/events';
import { PositionEntity } from '../entities/position.entity';
import { MtmValuationEntity, PriceSource } from '../entities/mtm-valuation.entity';
import { PositionManagerService } from '../positions/position-manager.service';
import { MarketDataService, PriceSubscription } from '@orion/market-data';

export interface PriceUpdate {
  instrumentId: string;
  bid: number;
  ask: number;
  mid: number;
  timestamp: Date;
  source: string;
}

export interface MtmConfig {
  tenantId: string;
  useRealtime: boolean;
  stalePriceThresholdSeconds: number;
  priceSources: string[];
  fallbackOrder: string[];
}

@Injectable()
export class MtmEngineService implements OnModuleInit {
  private subscriptions: Map<string, PriceSubscription> = new Map();
  private latestPrices: Map<string, PriceUpdate> = new Map();
  private configs: Map<string, MtmConfig> = new Map();

  constructor(
    @InjectRepository(PositionEntity)
    private readonly positionRepo: Repository<PositionEntity>,
    @InjectRepository(MtmValuationEntity)
    private readonly valuationRepo: Repository<MtmValuationEntity>,
    @InjectEntityManager()
    private readonly entityManager: EntityManager,
    @InjectRedis() private readonly redis: Redis,
    private readonly positionManager: PositionManagerService,
    private readonly marketDataService: MarketDataService,
    private readonly eventPublisher: EventPublisher,
  ) {}

  async onModuleInit() {
    // Subscribe to price updates for active instruments
    await this.initializePriceSubscriptions();
  }

  /**
   * Process real-time price update
   */
  async processPriceUpdate(update: PriceUpdate): Promise<void> {
    const startTime = Date.now();

    // Store latest price
    this.latestPrices.set(update.instrumentId, update);

    // Get all positions with this instrument
    const positions = await this.positionRepo.find({
      where: { instrumentId: update.instrumentId },
    });

    // Update each position's mark price
    for (const position of positions) {
      await this.updatePositionMtm(position, update);
    }

    metrics.histogram('mtm.realtime_update_ms', Date.now() - startTime);
    metrics.increment('mtm.price_updates');
  }

  /**
   * Run batch MTM valuation
   */
  async runBatchMtm(tenantId: string, valuationTime?: Date): Promise<{
    processed: number;
    stale: number;
    errors: number;
  }> {
    const time = valuationTime || new Date();
    const startTime = Date.now();

    const positions = await this.positionRepo.find({
      where: { tenantId },
    });

    let processed = 0;
    let stale = 0;
    let errors = 0;

    // Process in batches for performance
    const batchSize = 1000;
    for (let i = 0; i < positions.length; i += batchSize) {
      const batch = positions.slice(i, i + batchSize);

      await Promise.all(
        batch.map(async position => {
          try {
            const price = await this.getMarkPrice(position.instrumentId, tenantId);
            const isStale = this.isPriceStale(price, tenantId);

            if (isStale) stale++;

            await this.createValuation(position, price, time, isStale);
            processed++;
          } catch (error) {
            logger.error('MTM valuation failed', {
              positionId: position.id,
              error: error.message,
            });
            errors++;
          }
        }),
      );
    }

    const duration = Date.now() - startTime;
    logger.info('Batch MTM completed', {
      tenantId,
      processed,
      stale,
      errors,
      durationMs: duration,
    });

    metrics.histogram('mtm.batch_duration_ms', duration);
    metrics.gauge('mtm.positions_processed', processed);

    return { processed, stale, errors };
  }

  /**
   * Override mark price manually
   */
  async overrideMarkPrice(
    positionId: string,
    price: number,
    userId: string,
    reason: string,
  ): Promise<MtmValuationEntity> {
    const position = await this.positionRepo.findOne({ where: { id: positionId } });
    if (!position) {
      throw new Error('Position not found');
    }

    const previousValue = Number(position.marketValue);
    const newValue = Number(position.quantity) * price;

    // Update position
    position.markPrice = price;
    position.marketValue = newValue;
    position.unrealizedPnL = newValue - Number(position.costBasis);
    position.lastPriceUpdate = new Date();
    await this.positionRepo.save(position);

    // Create valuation record
    const valuation = this.valuationRepo.create({
      tenantId: position.tenantId,
      positionId: position.id,
      instrumentId: position.instrumentId,
      valuationTime: new Date(),
      markPrice: price,
      priceSource: PriceSource.MANUAL,
      quantity: position.quantity,
      marketValue: newValue,
      previousMarketValue: previousValue,
      mtmPnL: newValue - previousValue,
      currency: position.currency,
      overrideBy: userId,
      overrideReason: reason,
    });

    await this.valuationRepo.save(valuation);

    logger.info('Mark price overridden', {
      positionId,
      price,
      userId,
      reason,
    });

    return valuation;
  }

  /**
   * Get valuation history for position
   */
  async getValuationHistory(
    positionId: string,
    startDate: Date,
    endDate: Date,
  ): Promise<MtmValuationEntity[]> {
    return this.valuationRepo.find({
      where: {
        positionId,
        valuationTime: In([startDate, endDate]), // Use Between
      },
      order: { valuationTime: 'DESC' },
    });
  }

  /**
   * Get stale positions
   */
  async getStalePositions(tenantId: string): Promise<{
    position: PositionEntity;
    lastPrice: Date;
    staleDuration: number;
  }[]> {
    const config = this.configs.get(tenantId);
    const threshold = config?.stalePriceThresholdSeconds || 300;
    const cutoff = new Date(Date.now() - threshold * 1000);

    const stalePositions = await this.positionRepo
      .createQueryBuilder('p')
      .where('p.tenantId = :tenantId', { tenantId })
      .andWhere('p.lastPriceUpdate < :cutoff', { cutoff })
      .andWhere('p.quantity != 0')
      .getMany();

    return stalePositions.map(position => ({
      position,
      lastPrice: position.lastPriceUpdate,
      staleDuration: Math.floor((Date.now() - position.lastPriceUpdate.getTime()) / 1000),
    }));
  }

  /**
   * EOD MTM job
   */
  @Cron(CronExpression.EVERY_DAY_AT_10PM)
  async runEodMtm(): Promise<void> {
    logger.info('Starting EOD MTM valuation');

    // Get all tenants with positions
    const tenants = await this.entityManager.query(
      `SELECT DISTINCT tenant_id FROM positions WHERE quantity != 0`,
    );

    for (const { tenant_id } of tenants) {
      await this.runBatchMtm(tenant_id);
    }

    logger.info('EOD MTM completed', { tenantCount: tenants.length });
  }

  /**
   * Intraday MTM refresh
   */
  @Cron('0 */15 * * * *') // Every 15 minutes
  async runIntradayMtm(): Promise<void> {
    // Only during market hours
    const hour = new Date().getHours();
    if (hour < 7 || hour > 18) return;

    const tenants = await this.entityManager.query(
      `SELECT DISTINCT tenant_id FROM positions WHERE quantity != 0`,
    );

    for (const { tenant_id } of tenants) {
      const config = this.configs.get(tenant_id);
      if (config?.useRealtime) continue; // Skip if using real-time

      await this.runBatchMtm(tenant_id);
    }
  }

  private async initializePriceSubscriptions(): Promise<void> {
    // Get all unique instruments with positions
    const instruments = await this.entityManager.query(
      `SELECT DISTINCT instrument_id FROM positions WHERE quantity != 0`,
    );

    for (const { instrument_id } of instruments) {
      await this.subscribeToInstrument(instrument_id);
    }

    logger.info('Price subscriptions initialized', {
      instrumentCount: instruments.length,
    });
  }

  private async subscribeToInstrument(instrumentId: string): Promise<void> {
    if (this.subscriptions.has(instrumentId)) return;

    const subscription = await this.marketDataService.subscribe(
      instrumentId,
      (price: PriceUpdate) => this.processPriceUpdate(price),
    );

    this.subscriptions.set(instrumentId, subscription);
  }

  private async updatePositionMtm(
    position: PositionEntity,
    price: PriceUpdate,
  ): Promise<void> {
    const midPrice = price.mid || (price.bid + price.ask) / 2;
    const previousValue = Number(position.marketValue);
    const newValue = Number(position.quantity) * midPrice;

    // Update position
    await this.positionManager.updateMarkPrice(
      position.tenantId,
      position.instrumentId,
      midPrice,
    );

    // Publish update for real-time subscribers
    await this.redis.publish(`mtm:${position.tenantId}:${position.clientId}`, JSON.stringify({
      positionId: position.id,
      instrumentId: position.instrumentId,
      markPrice: midPrice,
      marketValue: newValue,
      mtmPnL: newValue - previousValue,
      timestamp: price.timestamp,
    }));
  }

  private async getMarkPrice(
    instrumentId: string,
    tenantId: string,
  ): Promise<PriceUpdate> {
    // Try latest real-time price
    const realtime = this.latestPrices.get(instrumentId);
    if (realtime && !this.isPriceStale(realtime, tenantId)) {
      return realtime;
    }

    // Try fixing price
    const fixing = await this.marketDataService.getFixingPrice(instrumentId);
    if (fixing) {
      return {
        instrumentId,
        bid: fixing.price,
        ask: fixing.price,
        mid: fixing.price,
        timestamp: fixing.timestamp,
        source: 'fixing',
      };
    }

    // Use last known price with stale flag
    if (realtime) {
      return { ...realtime, source: 'stale' };
    }

    throw new Error(`No price available for ${instrumentId}`);
  }

  private isPriceStale(price: PriceUpdate, tenantId: string): boolean {
    const config = this.configs.get(tenantId);
    const threshold = (config?.stalePriceThresholdSeconds || 300) * 1000;
    return Date.now() - price.timestamp.getTime() > threshold;
  }

  private async createValuation(
    position: PositionEntity,
    price: PriceUpdate,
    valuationTime: Date,
    isStale: boolean,
  ): Promise<void> {
    const previousValue = Number(position.marketValue);
    const midPrice = price.mid || (price.bid + price.ask) / 2;
    const newValue = Number(position.quantity) * midPrice;

    // Update position
    position.markPrice = midPrice;
    position.marketValue = newValue;
    position.unrealizedPnL = newValue - Number(position.costBasis);
    position.lastPriceUpdate = valuationTime;
    await this.positionRepo.save(position);

    // Create valuation record
    const valuation = this.valuationRepo.create({
      tenantId: position.tenantId,
      positionId: position.id,
      instrumentId: position.instrumentId,
      valuationTime,
      markPrice: midPrice,
      priceSource: isStale ? PriceSource.FALLBACK : PriceSource.LIVE_FEED,
      priceProvider: price.source,
      quantity: position.quantity,
      marketValue: newValue,
      previousMarketValue: previousValue,
      mtmPnL: newValue - previousValue,
      currency: position.currency,
      isStale,
    });

    await this.valuationRepo.save(valuation);
  }
}
```

## Database Schema

```sql
-- MTM valuations table
CREATE TABLE mtm_valuations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    position_id UUID NOT NULL,
    instrument_id UUID NOT NULL,
    valuation_time TIMESTAMP WITH TIME ZONE NOT NULL,
    mark_price DECIMAL(20, 8) NOT NULL,
    price_source VARCHAR(20) NOT NULL,
    price_provider VARCHAR(100),
    quantity DECIMAL(20, 8) NOT NULL,
    market_value DECIMAL(20, 4) NOT NULL,
    previous_market_value DECIMAL(20, 4) NOT NULL,
    mtm_pnl DECIMAL(20, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    is_stale BOOLEAN DEFAULT false,
    override_by VARCHAR(100),
    override_reason VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_mtm_valuations ON mtm_valuations(tenant_id, position_id, valuation_time DESC);
CREATE INDEX idx_mtm_stale ON mtm_valuations(tenant_id, is_stale, valuation_time);

-- MTM configurations
CREATE TABLE mtm_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL UNIQUE,
    use_realtime BOOLEAN DEFAULT true,
    stale_price_threshold_seconds INT DEFAULT 300,
    price_sources JSONB,
    fallback_order JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
```

## Definition of Done

- [ ] Real-time price subscription
- [ ] Batch MTM processing
- [ ] Stale price detection
- [ ] Manual price override
- [ ] Valuation history
- [ ] EOD MTM job
- [ ] Performance > 1M pos/min

## Test Cases

```typescript
describe('MtmEngineService', () => {
  it('should process real-time price update', async () => {
    await createPositions([{ instrumentId: 'EUR/USD', quantity: 100000 }]);

    await mtmEngine.processPriceUpdate({
      instrumentId: 'EUR/USD',
      bid: 1.0850,
      ask: 1.0852,
      mid: 1.0851,
      timestamp: new Date(),
      source: 'reuters',
    });

    const position = await positionRepo.findOne({ where: { instrumentId: 'EUR/USD' } });
    expect(position.markPrice).toBe(1.0851);
  });

  it('should detect stale prices', async () => {
    const oldPrice: PriceUpdate = {
      instrumentId: 'EUR/USD',
      mid: 1.0850,
      timestamp: new Date(Date.now() - 600000), // 10 minutes ago
      source: 'reuters',
    };

    const isStale = mtmEngine['isPriceStale'](oldPrice, 'tenant-1');
    expect(isStale).toBe(true);
  });

  it('should override mark price manually', async () => {
    const position = await createPosition();

    const valuation = await mtmEngine.overrideMarkPrice(
      position.id, 1.0900, 'user-1', 'Market illiquidity'
    );

    expect(valuation.priceSource).toBe(PriceSource.MANUAL);
    expect(valuation.overrideBy).toBe('user-1');
  });
});
```
