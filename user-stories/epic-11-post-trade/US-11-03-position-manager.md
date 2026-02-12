# User Story: US-11-03 - Position Manager

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-11-03 |
| **Epic** | Epic 11 - Post-Trade Services |
| **Title** | Position Manager |
| **Priority** | P0 - Critical |
| **Story Points** | 8 |
| **PRD Reference** | FR-PostTrade-03, NFR-PostTrade-03 |

## User Story

**As a** portfolio manager  
**I want** real-time position tracking across all instruments and clients  
**So that** I have accurate visibility into current holdings and exposure

## Description

Implement position management service that maintains real-time positions, handles position updates from trades, supports position transfers, and provides position snapshots for reporting and risk calculations.

## Acceptance Criteria

- [ ] Real-time position updates from trades
- [ ] Support all instrument types
- [ ] Average cost calculation
- [ ] Position transfer between accounts
- [ ] Corporate action adjustments
- [ ] End-of-day position snapshot
- [ ] Position history retention
- [ ] Cache for real-time access

## Technical Details

### Position Entity

```typescript
// services/post-trade-service/src/entities/position.entity.ts
import { Entity, Column, CreateDateColumn, UpdateDateColumn, Index } from 'typeorm';

@Entity('positions')
@Index(['tenantId', 'clientId', 'instrumentId'], { unique: true })
@Index(['tenantId', 'clientId'])
export class PositionEntity {
  @Column('uuid', { primaryKey: true, default: () => 'gen_random_uuid()' })
  id: string;

  @Column('uuid')
  tenantId: string;

  @Column('uuid')
  clientId: string;

  @Column('uuid')
  instrumentId: string;

  @Column('varchar', { length: 20 })
  instrumentType: string;

  @Column('decimal', { precision: 20, scale: 8 })
  quantity: number;

  @Column('decimal', { precision: 20, scale: 8 })
  averageCost: number;

  @Column('decimal', { precision: 20, scale: 4 })
  costBasis: number;

  @Column('decimal', { precision: 20, scale: 8 })
  markPrice: number;

  @Column('decimal', { precision: 20, scale: 4 })
  marketValue: number;

  @Column('decimal', { precision: 20, scale: 4 })
  unrealizedPnL: number;

  @Column('decimal', { precision: 20, scale: 4 })
  realizedPnL: number;

  @Column('decimal', { precision: 20, scale: 4, default: 0 })
  realizedPnLToday: number;

  @Column('varchar', { length: 3 })
  currency: string;

  @Column('timestamp with time zone')
  lastTradeDate: Date;

  @Column('timestamp with time zone')
  lastPriceUpdate: Date;

  @Column('int', { default: 1 })
  version: number;

  @Column('jsonb', { nullable: true })
  metadata: {
    settledQuantity?: number;
    pendingQuantity?: number;
    blockedQuantity?: number;
    tradeCount?: number;
  };

  @CreateDateColumn()
  createdAt: Date;

  @UpdateDateColumn()
  updatedAt: Date;
}
```

### Position History Entity

```typescript
// services/post-trade-service/src/entities/position-history.entity.ts
import { Entity, Column, CreateDateColumn, Index } from 'typeorm';

@Entity('position_history')
@Index(['tenantId', 'clientId', 'instrumentId', 'snapshotDate'])
export class PositionHistoryEntity {
  @Column('uuid', { primaryKey: true, default: () => 'gen_random_uuid()' })
  id: string;

  @Column('uuid')
  tenantId: string;

  @Column('uuid')
  positionId: string;

  @Column('uuid')
  clientId: string;

  @Column('uuid')
  instrumentId: string;

  @Column('date')
  snapshotDate: Date;

  @Column('decimal', { precision: 20, scale: 8 })
  quantity: number;

  @Column('decimal', { precision: 20, scale: 8 })
  averageCost: number;

  @Column('decimal', { precision: 20, scale: 8 })
  markPrice: number;

  @Column('decimal', { precision: 20, scale: 4 })
  marketValue: number;

  @Column('decimal', { precision: 20, scale: 4 })
  unrealizedPnL: number;

  @Column('decimal', { precision: 20, scale: 4 })
  realizedPnL: number;

  @Column('varchar', { length: 20 })
  snapshotType: string; // eod, intraday, manual

  @CreateDateColumn()
  createdAt: Date;
}
```

### Position Manager Service

```typescript
// services/post-trade-service/src/positions/position-manager.service.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { InjectRepository, InjectEntityManager } from '@nestjs/typeorm';
import { Repository, EntityManager, MoreThan } from 'typeorm';
import { Redis } from 'ioredis';
import { InjectRedis } from '@nestjs-modules/ioredis';
import { Cron, CronExpression } from '@nestjs/schedule';
import { logger, metrics } from '@orion/observability';
import { EventPublisher } from '@orion/events';
import { PositionEntity } from '../entities/position.entity';
import { PositionHistoryEntity } from '../entities/position-history.entity';
import { TradeEntity } from '../entities/trade.entity';

export interface PositionUpdateDto {
  tenantId: string;
  clientId: string;
  instrumentId: string;
  instrumentType: string;
  quantity: number;
  price: number;
  side: 'buy' | 'sell';
  currency: string;
  tradeId: string;
}

@Injectable()
export class PositionManagerService implements OnModuleInit {
  private readonly cachePrefix = 'position:';

  constructor(
    @InjectRepository(PositionEntity)
    private readonly positionRepo: Repository<PositionEntity>,
    @InjectRepository(PositionHistoryEntity)
    private readonly historyRepo: Repository<PositionHistoryEntity>,
    @InjectEntityManager()
    private readonly entityManager: EntityManager,
    @InjectRedis() private readonly redis: Redis,
    private readonly eventPublisher: EventPublisher,
  ) {}

  async onModuleInit() {
    // Load positions into cache on startup
    await this.warmCache();
  }

  /**
   * Update position from trade
   */
  async updatePositionFromTrade(dto: PositionUpdateDto): Promise<PositionEntity> {
    const startTime = Date.now();

    const position = await this.entityManager.transaction(async manager => {
      // Get or create position with lock
      let position = await manager
        .createQueryBuilder(PositionEntity, 'p')
        .setLock('pessimistic_write')
        .where('p.tenantId = :tenantId', { tenantId: dto.tenantId })
        .andWhere('p.clientId = :clientId', { clientId: dto.clientId })
        .andWhere('p.instrumentId = :instrumentId', { instrumentId: dto.instrumentId })
        .getOne();

      if (!position) {
        position = manager.create(PositionEntity, {
          tenantId: dto.tenantId,
          clientId: dto.clientId,
          instrumentId: dto.instrumentId,
          instrumentType: dto.instrumentType,
          quantity: 0,
          averageCost: 0,
          costBasis: 0,
          markPrice: dto.price,
          marketValue: 0,
          unrealizedPnL: 0,
          realizedPnL: 0,
          realizedPnLToday: 0,
          currency: dto.currency,
          lastTradeDate: new Date(),
          lastPriceUpdate: new Date(),
          metadata: { tradeCount: 0 },
        });
      }

      // Calculate new position
      const signedQuantity = dto.side === 'buy' ? dto.quantity : -dto.quantity;
      const previousQuantity = Number(position.quantity);
      const newQuantity = previousQuantity + signedQuantity;

      // Calculate average cost and realized P&L
      if (this.isIncreasingPosition(previousQuantity, signedQuantity)) {
        // Adding to position - update average cost
        const previousCost = previousQuantity * Number(position.averageCost);
        const newCost = Math.abs(signedQuantity) * dto.price;
        position.averageCost = Math.abs(newQuantity) > 0
          ? (previousCost + newCost) / Math.abs(newQuantity)
          : 0;
        position.costBasis = Math.abs(newQuantity) * Number(position.averageCost);
      } else {
        // Reducing/closing position - calculate realized P&L
        const closedQuantity = Math.min(Math.abs(previousQuantity), Math.abs(signedQuantity));
        const realizedPnL = closedQuantity * (dto.price - Number(position.averageCost));
        
        if (dto.side === 'sell' && previousQuantity > 0) {
          position.realizedPnL = Number(position.realizedPnL) + realizedPnL;
          position.realizedPnLToday = Number(position.realizedPnLToday) + realizedPnL;
        } else if (dto.side === 'buy' && previousQuantity < 0) {
          position.realizedPnL = Number(position.realizedPnL) - realizedPnL;
          position.realizedPnLToday = Number(position.realizedPnLToday) - realizedPnL;
        }

        // If flipping position, reset average cost
        if (Math.sign(previousQuantity) !== Math.sign(newQuantity) && newQuantity !== 0) {
          position.averageCost = dto.price;
        }

        position.costBasis = Math.abs(newQuantity) * Number(position.averageCost);
      }

      // Update position
      position.quantity = newQuantity;
      position.markPrice = dto.price;
      position.marketValue = newQuantity * dto.price;
      position.unrealizedPnL = position.marketValue - position.costBasis;
      position.lastTradeDate = new Date();
      position.version += 1;
      position.metadata = {
        ...position.metadata,
        tradeCount: (position.metadata?.tradeCount || 0) + 1,
      };

      await manager.save(position);

      // Create outbox event
      await manager.query(
        `INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload)
         VALUES ($1, $2, $3, $4)`,
        ['Position', position.id, 'position.updated', JSON.stringify({
          position,
          trade: { id: dto.tradeId, quantity: dto.quantity, price: dto.price, side: dto.side },
        })],
      );

      return position;
    });

    // Update cache
    await this.updateCache(position);

    metrics.histogram('position.update_latency_ms', Date.now() - startTime);
    logger.info('Position updated', {
      positionId: position.id,
      quantity: position.quantity,
      averageCost: position.averageCost,
    });

    return position;
  }

  /**
   * Transfer position between accounts
   */
  async transferPosition(
    tenantId: string,
    fromClientId: string,
    toClientId: string,
    instrumentId: string,
    quantity: number,
    reason: string,
  ): Promise<{ fromPosition: PositionEntity; toPosition: PositionEntity }> {
    return this.entityManager.transaction(async manager => {
      // Get source position with lock
      const fromPosition = await manager
        .createQueryBuilder(PositionEntity, 'p')
        .setLock('pessimistic_write')
        .where('p.tenantId = :tenantId', { tenantId })
        .andWhere('p.clientId = :clientId', { clientId: fromClientId })
        .andWhere('p.instrumentId = :instrumentId', { instrumentId })
        .getOne();

      if (!fromPosition || Number(fromPosition.quantity) < quantity) {
        throw new Error('Insufficient position for transfer');
      }

      // Get or create destination position
      let toPosition = await manager
        .createQueryBuilder(PositionEntity, 'p')
        .setLock('pessimistic_write')
        .where('p.tenantId = :tenantId', { tenantId })
        .andWhere('p.clientId = :clientId', { clientId: toClientId })
        .andWhere('p.instrumentId = :instrumentId', { instrumentId })
        .getOne();

      if (!toPosition) {
        toPosition = manager.create(PositionEntity, {
          tenantId,
          clientId: toClientId,
          instrumentId,
          instrumentType: fromPosition.instrumentType,
          quantity: 0,
          averageCost: 0,
          costBasis: 0,
          markPrice: fromPosition.markPrice,
          marketValue: 0,
          unrealizedPnL: 0,
          realizedPnL: 0,
          currency: fromPosition.currency,
          lastTradeDate: new Date(),
          lastPriceUpdate: new Date(),
        });
      }

      // Transfer at source average cost
      const transferCost = Number(fromPosition.averageCost);

      // Update source position
      fromPosition.quantity = Number(fromPosition.quantity) - quantity;
      fromPosition.costBasis = Number(fromPosition.quantity) * Number(fromPosition.averageCost);
      fromPosition.marketValue = Number(fromPosition.quantity) * Number(fromPosition.markPrice);
      fromPosition.unrealizedPnL = fromPosition.marketValue - fromPosition.costBasis;
      fromPosition.version += 1;

      // Update destination position (weighted average cost)
      const existingCost = Number(toPosition.quantity) * Number(toPosition.averageCost);
      const transferTotal = quantity * transferCost;
      const newQuantity = Number(toPosition.quantity) + quantity;
      toPosition.averageCost = newQuantity > 0 ? (existingCost + transferTotal) / newQuantity : 0;
      toPosition.quantity = newQuantity;
      toPosition.costBasis = newQuantity * Number(toPosition.averageCost);
      toPosition.marketValue = newQuantity * Number(toPosition.markPrice);
      toPosition.unrealizedPnL = toPosition.marketValue - toPosition.costBasis;
      toPosition.version += 1;

      await manager.save(fromPosition);
      await manager.save(toPosition);

      // Update caches
      await this.updateCache(fromPosition);
      await this.updateCache(toPosition);

      logger.info('Position transferred', {
        from: fromClientId,
        to: toClientId,
        quantity,
        reason,
      });

      return { fromPosition, toPosition };
    });
  }

  /**
   * Apply corporate action adjustment
   */
  async applyCorporateAction(
    tenantId: string,
    instrumentId: string,
    action: {
      type: 'split' | 'dividend' | 'merger';
      ratio?: number;
      cashAmount?: number;
      newInstrumentId?: string;
    },
  ): Promise<void> {
    const positions = await this.positionRepo.find({
      where: { tenantId, instrumentId },
    });

    for (const position of positions) {
      await this.entityManager.transaction(async manager => {
        const locked = await manager
          .createQueryBuilder(PositionEntity, 'p')
          .setLock('pessimistic_write')
          .where('p.id = :id', { id: position.id })
          .getOne();

        switch (action.type) {
          case 'split':
            locked.quantity = Number(locked.quantity) * action.ratio;
            locked.averageCost = Number(locked.averageCost) / action.ratio;
            break;

          case 'dividend':
            locked.realizedPnL = Number(locked.realizedPnL) + (Number(locked.quantity) * action.cashAmount);
            break;

          case 'merger':
            // Create new position in merged instrument
            break;
        }

        locked.version += 1;
        await manager.save(locked);

        await this.updateCache(locked);
      });
    }

    logger.info('Corporate action applied', {
      instrumentId,
      actionType: action.type,
      positionsAffected: positions.length,
    });
  }

  /**
   * Update mark price for position
   */
  async updateMarkPrice(
    tenantId: string,
    instrumentId: string,
    markPrice: number,
  ): Promise<void> {
    const positions = await this.positionRepo.find({
      where: { tenantId, instrumentId, quantity: MoreThan(0) },
    });

    for (const position of positions) {
      position.markPrice = markPrice;
      position.marketValue = Number(position.quantity) * markPrice;
      position.unrealizedPnL = position.marketValue - Number(position.costBasis);
      position.lastPriceUpdate = new Date();

      await this.positionRepo.save(position);
      await this.updateCache(position);
    }
  }

  /**
   * Get position by client and instrument
   */
  async getPosition(
    tenantId: string,
    clientId: string,
    instrumentId: string,
  ): Promise<PositionEntity | null> {
    // Try cache first
    const cacheKey = this.getCacheKey(tenantId, clientId, instrumentId);
    const cached = await this.redis.get(cacheKey);

    if (cached) {
      return JSON.parse(cached);
    }

    const position = await this.positionRepo.findOne({
      where: { tenantId, clientId, instrumentId },
    });

    if (position) {
      await this.updateCache(position);
    }

    return position;
  }

  /**
   * Get all positions for client
   */
  async getClientPositions(tenantId: string, clientId: string): Promise<PositionEntity[]> {
    return this.positionRepo.find({
      where: { tenantId, clientId },
    });
  }

  /**
   * End-of-day snapshot
   */
  @Cron(CronExpression.EVERY_DAY_AT_11PM)
  async createEodSnapshot(): Promise<void> {
    const today = new Date();
    today.setHours(0, 0, 0, 0);

    const positions = await this.positionRepo.find();

    const snapshots = positions.map(pos => ({
      tenantId: pos.tenantId,
      positionId: pos.id,
      clientId: pos.clientId,
      instrumentId: pos.instrumentId,
      snapshotDate: today,
      quantity: pos.quantity,
      averageCost: pos.averageCost,
      markPrice: pos.markPrice,
      marketValue: pos.marketValue,
      unrealizedPnL: pos.unrealizedPnL,
      realizedPnL: pos.realizedPnL,
      snapshotType: 'eod',
    }));

    await this.historyRepo.insert(snapshots);

    // Reset daily realized P&L
    await this.positionRepo
      .createQueryBuilder()
      .update()
      .set({ realizedPnLToday: 0 })
      .execute();

    logger.info('EOD position snapshot created', { count: snapshots.length });
  }

  private isIncreasingPosition(previousQty: number, change: number): boolean {
    // Same sign or from zero
    return previousQty === 0 || Math.sign(previousQty) === Math.sign(change);
  }

  private getCacheKey(tenantId: string, clientId: string, instrumentId: string): string {
    return `${this.cachePrefix}${tenantId}:${clientId}:${instrumentId}`;
  }

  private async updateCache(position: PositionEntity): Promise<void> {
    const key = this.getCacheKey(position.tenantId, position.clientId, position.instrumentId);
    await this.redis.setex(key, 3600, JSON.stringify(position));

    // Publish for real-time subscribers
    await this.redis.publish('position:updated', JSON.stringify(position));
  }

  private async warmCache(): Promise<void> {
    const positions = await this.positionRepo.find({
      where: { quantity: MoreThan(0) },
    });

    for (const position of positions) {
      await this.updateCache(position);
    }

    logger.info('Position cache warmed', { count: positions.length });
  }
}
```

## Database Schema

```sql
-- Positions table
CREATE TABLE positions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    client_id UUID NOT NULL,
    instrument_id UUID NOT NULL,
    instrument_type VARCHAR(20) NOT NULL,
    quantity DECIMAL(20, 8) NOT NULL,
    average_cost DECIMAL(20, 8) NOT NULL,
    cost_basis DECIMAL(20, 4) NOT NULL,
    mark_price DECIMAL(20, 8) NOT NULL,
    market_value DECIMAL(20, 4) NOT NULL,
    unrealized_pnl DECIMAL(20, 4) NOT NULL,
    realized_pnl DECIMAL(20, 4) NOT NULL,
    realized_pnl_today DECIMAL(20, 4) DEFAULT 0,
    currency VARCHAR(3) NOT NULL,
    last_trade_date TIMESTAMP WITH TIME ZONE NOT NULL,
    last_price_update TIMESTAMP WITH TIME ZONE NOT NULL,
    version INT DEFAULT 1,
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(tenant_id, client_id, instrument_id)
);

CREATE INDEX idx_positions_tenant_client ON positions(tenant_id, client_id);
CREATE INDEX idx_positions_instrument ON positions(tenant_id, instrument_id);

-- Position history (TimescaleDB hypertable)
CREATE TABLE position_history (
    id UUID DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    position_id UUID NOT NULL,
    client_id UUID NOT NULL,
    instrument_id UUID NOT NULL,
    snapshot_date DATE NOT NULL,
    quantity DECIMAL(20, 8) NOT NULL,
    average_cost DECIMAL(20, 8) NOT NULL,
    mark_price DECIMAL(20, 8) NOT NULL,
    market_value DECIMAL(20, 4) NOT NULL,
    unrealized_pnl DECIMAL(20, 4) NOT NULL,
    realized_pnl DECIMAL(20, 4) NOT NULL,
    snapshot_type VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

SELECT create_hypertable('position_history', 'snapshot_date');
CREATE INDEX idx_position_history ON position_history(tenant_id, client_id, instrument_id, snapshot_date DESC);
```

## Definition of Done

- [ ] Position updates from trades
- [ ] Average cost calculation
- [ ] Realized P&L tracking
- [ ] Position transfers
- [ ] Corporate actions
- [ ] Mark price updates
- [ ] EOD snapshots
- [ ] Position cache

## Dependencies

- **US-11-01**: Trade Confirmation (trade events)
- **US-05-***: Market Data (mark prices)

## Test Cases

```typescript
describe('PositionManagerService', () => {
  it('should create new position on first buy', async () => {
    const position = await positionManager.updatePositionFromTrade({
      tenantId: 'tenant-1',
      clientId: 'client-1',
      instrumentId: 'EUR/USD',
      instrumentType: 'fx_spot',
      quantity: 100000,
      price: 1.0850,
      side: 'buy',
      currency: 'USD',
      tradeId: 'trade-1',
    });

    expect(position.quantity).toBe(100000);
    expect(position.averageCost).toBe(1.0850);
  });

  it('should calculate realized P&L on partial close', async () => {
    // Create initial position
    await createPosition({ quantity: 100000, averageCost: 1.0850 });

    // Sell half at higher price
    const position = await positionManager.updatePositionFromTrade({
      ...baseDto,
      quantity: 50000,
      price: 1.0900,
      side: 'sell',
    });

    expect(position.quantity).toBe(50000);
    expect(position.realizedPnL).toBe(250); // (1.0900 - 1.0850) * 50000
  });

  it('should transfer position between accounts', async () => {
    await createPosition({ clientId: 'from', quantity: 100000, averageCost: 1.0850 });

    const { fromPosition, toPosition } = await positionManager.transferPosition(
      'tenant-1', 'from', 'to', 'EUR/USD', 30000, 'Account transfer'
    );

    expect(fromPosition.quantity).toBe(70000);
    expect(toPosition.quantity).toBe(30000);
    expect(toPosition.averageCost).toBe(1.0850);
  });
});
```
