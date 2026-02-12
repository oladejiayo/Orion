# User Story: US-11-04 - P&L Calculator

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-11-04 |
| **Epic** | Epic 11 - Post-Trade Services |
| **Title** | P&L Calculator |
| **Priority** | P0 - Critical |
| **Story Points** | 8 |
| **PRD Reference** | FR-PostTrade-04, NFR-PostTrade-04 |

## User Story

**As a** trader/portfolio manager  
**I want** real-time and historical P&L calculations  
**So that** I can monitor performance and make informed trading decisions

## Description

Implement P&L calculation engine supporting realized/unrealized P&L, daily/MTD/YTD aggregations, client/instrument/strategy breakdowns, and multi-currency handling with proper FX translation.

## Acceptance Criteria

- [ ] Real-time unrealized P&L
- [ ] Realized P&L from trades
- [ ] Daily P&L attribution
- [ ] Multi-currency consolidation
- [ ] Client and instrument breakdown
- [ ] Time-weighted return calculation
- [ ] P&L history storage
- [ ] WebSocket streaming updates

## Technical Details

### P&L Summary Entity

```typescript
// services/post-trade-service/src/entities/pnl-summary.entity.ts
import { Entity, Column, CreateDateColumn, Index } from 'typeorm';

@Entity('pnl_summaries')
@Index(['tenantId', 'entityType', 'entityId', 'summaryDate'])
export class PnlSummaryEntity {
  @Column('uuid', { primaryKey: true, default: () => 'gen_random_uuid()' })
  id: string;

  @Column('uuid')
  tenantId: string;

  @Column('varchar', { length: 20 })
  entityType: string; // client, instrument, strategy, tenant

  @Column('varchar', { length: 100 })
  entityId: string;

  @Column('date')
  summaryDate: Date;

  @Column('varchar', { length: 3 })
  baseCurrency: string;

  @Column('decimal', { precision: 20, scale: 4 })
  realizedPnL: number;

  @Column('decimal', { precision: 20, scale: 4 })
  unrealizedPnL: number;

  @Column('decimal', { precision: 20, scale: 4 })
  totalPnL: number;

  @Column('decimal', { precision: 20, scale: 4 })
  tradingPnL: number;

  @Column('decimal', { precision: 20, scale: 4 })
  fxPnL: number;

  @Column('decimal', { precision: 20, scale: 4 })
  fees: number;

  @Column('decimal', { precision: 20, scale: 4 })
  netPnL: number;

  @Column('decimal', { precision: 20, scale: 4, nullable: true })
  mtdPnL: number;

  @Column('decimal', { precision: 20, scale: 4, nullable: true })
  ytdPnL: number;

  @Column('jsonb', { nullable: true })
  breakdown: {
    byInstrument?: Record<string, number>;
    byCurrency?: Record<string, number>;
    byStrategy?: Record<string, number>;
  };

  @CreateDateColumn()
  createdAt: Date;
}
```

### P&L Calculator Service

```typescript
// services/post-trade-service/src/pnl/pnl-calculator.service.ts
import { Injectable } from '@nestjs/common';
import { InjectRepository, InjectEntityManager } from '@nestjs/typeorm';
import { Repository, EntityManager, Between } from 'typeorm';
import { Redis } from 'ioredis';
import { InjectRedis } from '@nestjs-modules/ioredis';
import { Cron, CronExpression } from '@nestjs/schedule';
import { logger, metrics } from '@orion/observability';
import { EventPublisher } from '@orion/events';
import { PositionEntity } from '../entities/position.entity';
import { TradeEntity } from '../entities/trade.entity';
import { PnlSummaryEntity } from '../entities/pnl-summary.entity';
import { FxRateService } from '@orion/market-data';

export interface PnlSnapshot {
  tenantId: string;
  clientId: string;
  baseCurrency: string;
  realizedPnL: number;
  unrealizedPnL: number;
  totalPnL: number;
  tradingPnL: number;
  fxPnL: number;
  fees: number;
  netPnL: number;
  positions: PositionPnl[];
  timestamp: Date;
}

export interface PositionPnl {
  instrumentId: string;
  symbol: string;
  quantity: number;
  averageCost: number;
  markPrice: number;
  unrealizedPnL: number;
  unrealizedPnLPct: number;
  realizedPnLToday: number;
  localCurrency: string;
  baseCurrencyPnL: number;
}

@Injectable()
export class PnlCalculatorService {
  private readonly cachePrefix = 'pnl:';

  constructor(
    @InjectRepository(PositionEntity)
    private readonly positionRepo: Repository<PositionEntity>,
    @InjectRepository(TradeEntity)
    private readonly tradeRepo: Repository<TradeEntity>,
    @InjectRepository(PnlSummaryEntity)
    private readonly summaryRepo: Repository<PnlSummaryEntity>,
    @InjectEntityManager()
    private readonly entityManager: EntityManager,
    @InjectRedis() private readonly redis: Redis,
    private readonly fxRateService: FxRateService,
    private readonly eventPublisher: EventPublisher,
  ) {}

  /**
   * Calculate real-time P&L for client
   */
  async calculateClientPnl(
    tenantId: string,
    clientId: string,
    baseCurrency: string = 'USD',
  ): Promise<PnlSnapshot> {
    const startTime = Date.now();

    // Get all positions for client
    const positions = await this.positionRepo.find({
      where: { tenantId, clientId },
    });

    // Get today's trades for realized P&L
    const today = new Date();
    today.setHours(0, 0, 0, 0);

    const trades = await this.tradeRepo.find({
      where: {
        tenantId,
        clientId,
        tradeDate: today,
      },
    });

    // Calculate position P&L with FX translation
    const positionPnls: PositionPnl[] = [];
    let totalUnrealizedPnL = 0;
    let totalRealizedPnL = 0;
    let totalFxPnL = 0;
    let totalFees = 0;

    for (const position of positions) {
      const fxRate = position.currency === baseCurrency
        ? 1
        : await this.fxRateService.getRate(position.currency, baseCurrency);

      const unrealizedLocal = Number(position.unrealizedPnL);
      const unrealizedBase = unrealizedLocal * fxRate;
      const realizedLocal = Number(position.realizedPnLToday);
      const realizedBase = realizedLocal * fxRate;

      // Calculate FX P&L component
      const costBasisBase = Number(position.costBasis) * fxRate;
      const marketValueBase = Number(position.marketValue) * fxRate;
      const tradingPnL = unrealizedLocal * fxRate;
      const fxPnL = marketValueBase - tradingPnL - costBasisBase;

      positionPnls.push({
        instrumentId: position.instrumentId,
        symbol: position.instrumentId, // Get from reference data
        quantity: Number(position.quantity),
        averageCost: Number(position.averageCost),
        markPrice: Number(position.markPrice),
        unrealizedPnL: unrealizedLocal,
        unrealizedPnLPct: Number(position.costBasis) > 0
          ? (unrealizedLocal / Number(position.costBasis)) * 100
          : 0,
        realizedPnLToday: realizedLocal,
        localCurrency: position.currency,
        baseCurrencyPnL: unrealizedBase + realizedBase,
      });

      totalUnrealizedPnL += unrealizedBase;
      totalRealizedPnL += realizedBase;
      totalFxPnL += fxPnL;
    }

    // Calculate fees from trades
    for (const trade of trades) {
      if (trade.fees) {
        for (const fee of trade.fees) {
          const fxRate = fee.currency === baseCurrency
            ? 1
            : await this.fxRateService.getRate(fee.currency, baseCurrency);
          totalFees += fee.amount * fxRate;
        }
      }
    }

    const snapshot: PnlSnapshot = {
      tenantId,
      clientId,
      baseCurrency,
      realizedPnL: totalRealizedPnL,
      unrealizedPnL: totalUnrealizedPnL,
      totalPnL: totalRealizedPnL + totalUnrealizedPnL,
      tradingPnL: totalRealizedPnL + totalUnrealizedPnL - totalFxPnL,
      fxPnL: totalFxPnL,
      fees: totalFees,
      netPnL: totalRealizedPnL + totalUnrealizedPnL - totalFees,
      positions: positionPnls,
      timestamp: new Date(),
    };

    // Cache the snapshot
    await this.cacheSnapshot(snapshot);

    metrics.histogram('pnl.calculation_latency_ms', Date.now() - startTime);

    return snapshot;
  }

  /**
   * Calculate daily P&L summary
   */
  async calculateDailySummary(
    tenantId: string,
    entityType: string,
    entityId: string,
    date: Date,
    baseCurrency: string,
  ): Promise<PnlSummaryEntity> {
    const dateStart = new Date(date);
    dateStart.setHours(0, 0, 0, 0);
    const dateEnd = new Date(date);
    dateEnd.setHours(23, 59, 59, 999);

    // Get trades for the day
    const trades = await this.tradeRepo.find({
      where: {
        tenantId,
        ...(entityType === 'client' ? { clientId: entityId } : {}),
        ...(entityType === 'instrument' ? { instrumentId: entityId } : {}),
        tradeDate: Between(dateStart, dateEnd),
      },
    });

    // Calculate realized P&L from trades
    let realizedPnL = 0;
    let totalFees = 0;
    const byInstrument: Record<string, number> = {};
    const byCurrency: Record<string, number> = {};

    for (const trade of trades) {
      // Get position for average cost
      const position = await this.positionRepo.findOne({
        where: {
          tenantId,
          clientId: trade.clientId,
          instrumentId: trade.instrumentId,
        },
      });

      if (position) {
        const tradePnL = Number(position.realizedPnLToday);
        const fxRate = position.currency === baseCurrency
          ? 1
          : await this.fxRateService.getRate(position.currency, baseCurrency);

        realizedPnL += tradePnL * fxRate;

        // Track by instrument and currency
        byInstrument[trade.instrumentId] = (byInstrument[trade.instrumentId] || 0) + tradePnL * fxRate;
        byCurrency[position.currency] = (byCurrency[position.currency] || 0) + tradePnL;
      }

      // Sum fees
      if (trade.fees) {
        for (const fee of trade.fees) {
          const fxRate = fee.currency === baseCurrency
            ? 1
            : await this.fxRateService.getRate(fee.currency, baseCurrency);
          totalFees += fee.amount * fxRate;
        }
      }
    }

    // Get current unrealized P&L
    const positions = await this.positionRepo.find({
      where: {
        tenantId,
        ...(entityType === 'client' ? { clientId: entityId } : {}),
        ...(entityType === 'instrument' ? { instrumentId: entityId } : {}),
      },
    });

    let unrealizedPnL = 0;
    for (const position of positions) {
      const fxRate = position.currency === baseCurrency
        ? 1
        : await this.fxRateService.getRate(position.currency, baseCurrency);
      unrealizedPnL += Number(position.unrealizedPnL) * fxRate;
    }

    // Calculate MTD and YTD
    const mtdPnL = await this.calculateMtdPnl(tenantId, entityType, entityId, date, baseCurrency);
    const ytdPnL = await this.calculateYtdPnl(tenantId, entityType, entityId, date, baseCurrency);

    // Create or update summary
    let summary = await this.summaryRepo.findOne({
      where: {
        tenantId,
        entityType,
        entityId,
        summaryDate: dateStart,
      },
    });

    if (!summary) {
      summary = this.summaryRepo.create({
        tenantId,
        entityType,
        entityId,
        summaryDate: dateStart,
        baseCurrency,
      });
    }

    summary.realizedPnL = realizedPnL;
    summary.unrealizedPnL = unrealizedPnL;
    summary.totalPnL = realizedPnL + unrealizedPnL;
    summary.tradingPnL = realizedPnL + unrealizedPnL; // Simplified
    summary.fxPnL = 0; // Calculate separately
    summary.fees = totalFees;
    summary.netPnL = summary.totalPnL - totalFees;
    summary.mtdPnL = mtdPnL;
    summary.ytdPnL = ytdPnL;
    summary.breakdown = { byInstrument, byCurrency };

    await this.summaryRepo.save(summary);

    return summary;
  }

  /**
   * Calculate time-weighted return (TWR)
   */
  async calculateTWR(
    tenantId: string,
    clientId: string,
    startDate: Date,
    endDate: Date,
  ): Promise<{
    twr: number;
    periodReturns: { date: Date; return: number }[];
  }> {
    const summaries = await this.summaryRepo.find({
      where: {
        tenantId,
        entityType: 'client',
        entityId: clientId,
        summaryDate: Between(startDate, endDate),
      },
      order: { summaryDate: 'ASC' },
    });

    if (summaries.length === 0) {
      return { twr: 0, periodReturns: [] };
    }

    // Calculate daily returns and compound
    const periodReturns: { date: Date; return: number }[] = [];
    let compoundReturn = 1;

    for (let i = 0; i < summaries.length; i++) {
      const summary = summaries[i];
      const previousValue = i === 0
        ? await this.getStartingValue(tenantId, clientId, startDate)
        : await this.getValueAtDate(tenantId, clientId, summaries[i - 1].summaryDate);

      if (previousValue > 0) {
        const dailyReturn = Number(summary.totalPnL) / previousValue;
        periodReturns.push({
          date: summary.summaryDate,
          return: dailyReturn,
        });
        compoundReturn *= (1 + dailyReturn);
      }
    }

    const twr = (compoundReturn - 1) * 100; // As percentage

    return { twr, periodReturns };
  }

  /**
   * Get P&L attribution by factor
   */
  async getPnlAttribution(
    tenantId: string,
    clientId: string,
    startDate: Date,
    endDate: Date,
  ): Promise<{
    totalPnL: number;
    byInstrument: { id: string; name: string; pnl: number; percentage: number }[];
    byCurrency: { currency: string; pnl: number; percentage: number }[];
    byComponent: {
      trading: number;
      fx: number;
      fees: number;
    };
  }> {
    const summaries = await this.summaryRepo.find({
      where: {
        tenantId,
        entityType: 'client',
        entityId: clientId,
        summaryDate: Between(startDate, endDate),
      },
    });

    const aggregated = {
      totalPnL: 0,
      trading: 0,
      fx: 0,
      fees: 0,
      byInstrument: {} as Record<string, number>,
      byCurrency: {} as Record<string, number>,
    };

    for (const summary of summaries) {
      aggregated.totalPnL += Number(summary.totalPnL);
      aggregated.trading += Number(summary.tradingPnL);
      aggregated.fx += Number(summary.fxPnL);
      aggregated.fees += Number(summary.fees);

      if (summary.breakdown?.byInstrument) {
        for (const [id, pnl] of Object.entries(summary.breakdown.byInstrument)) {
          aggregated.byInstrument[id] = (aggregated.byInstrument[id] || 0) + pnl;
        }
      }
      if (summary.breakdown?.byCurrency) {
        for (const [currency, pnl] of Object.entries(summary.breakdown.byCurrency)) {
          aggregated.byCurrency[currency] = (aggregated.byCurrency[currency] || 0) + pnl;
        }
      }
    }

    return {
      totalPnL: aggregated.totalPnL,
      byInstrument: Object.entries(aggregated.byInstrument).map(([id, pnl]) => ({
        id,
        name: id, // Get from reference data
        pnl,
        percentage: aggregated.totalPnL !== 0 ? (pnl / aggregated.totalPnL) * 100 : 0,
      })),
      byCurrency: Object.entries(aggregated.byCurrency).map(([currency, pnl]) => ({
        currency,
        pnl,
        percentage: aggregated.totalPnL !== 0 ? (pnl / aggregated.totalPnL) * 100 : 0,
      })),
      byComponent: {
        trading: aggregated.trading,
        fx: aggregated.fx,
        fees: aggregated.fees,
      },
    };
  }

  /**
   * End-of-day P&L calculation
   */
  @Cron(CronExpression.EVERY_DAY_AT_11PM)
  async runEodPnlCalculation(): Promise<void> {
    const today = new Date();
    today.setHours(0, 0, 0, 0);

    // Get all active clients
    const clients = await this.entityManager.query(
      `SELECT DISTINCT tenant_id, client_id FROM positions WHERE quantity != 0`,
    );

    for (const { tenant_id, client_id } of clients) {
      try {
        await this.calculateDailySummary(
          tenant_id,
          'client',
          client_id,
          today,
          'USD',
        );
      } catch (error) {
        logger.error('Failed to calculate daily P&L', {
          tenantId: tenant_id,
          clientId: client_id,
          error: error.message,
        });
      }
    }

    logger.info('EOD P&L calculation completed', { clientCount: clients.length });
  }

  /**
   * Stream P&L updates
   */
  async streamPnlUpdates(tenantId: string, clientId: string): Promise<void> {
    const snapshot = await this.calculateClientPnl(tenantId, clientId);

    await this.redis.publish(
      `pnl:${tenantId}:${clientId}`,
      JSON.stringify(snapshot),
    );
  }

  private async calculateMtdPnl(
    tenantId: string,
    entityType: string,
    entityId: string,
    date: Date,
    baseCurrency: string,
  ): Promise<number> {
    const monthStart = new Date(date.getFullYear(), date.getMonth(), 1);

    const result = await this.summaryRepo
      .createQueryBuilder('s')
      .select('SUM(s.totalPnL)', 'mtd')
      .where('s.tenantId = :tenantId', { tenantId })
      .andWhere('s.entityType = :entityType', { entityType })
      .andWhere('s.entityId = :entityId', { entityId })
      .andWhere('s.summaryDate >= :monthStart', { monthStart })
      .andWhere('s.summaryDate <= :date', { date })
      .getRawOne();

    return result?.mtd || 0;
  }

  private async calculateYtdPnl(
    tenantId: string,
    entityType: string,
    entityId: string,
    date: Date,
    baseCurrency: string,
  ): Promise<number> {
    const yearStart = new Date(date.getFullYear(), 0, 1);

    const result = await this.summaryRepo
      .createQueryBuilder('s')
      .select('SUM(s.totalPnL)', 'ytd')
      .where('s.tenantId = :tenantId', { tenantId })
      .andWhere('s.entityType = :entityType', { entityType })
      .andWhere('s.entityId = :entityId', { entityId })
      .andWhere('s.summaryDate >= :yearStart', { yearStart })
      .andWhere('s.summaryDate <= :date', { date })
      .getRawOne();

    return result?.ytd || 0;
  }

  private async cacheSnapshot(snapshot: PnlSnapshot): Promise<void> {
    const key = `${this.cachePrefix}${snapshot.tenantId}:${snapshot.clientId}`;
    await this.redis.setex(key, 60, JSON.stringify(snapshot));
  }

  private async getStartingValue(
    tenantId: string,
    clientId: string,
    date: Date,
  ): Promise<number> {
    // Get portfolio value at start of period
    return 0;
  }

  private async getValueAtDate(
    tenantId: string,
    clientId: string,
    date: Date,
  ): Promise<number> {
    // Get portfolio value at specific date
    return 0;
  }
}
```

## Database Schema

```sql
-- P&L summaries table
CREATE TABLE pnl_summaries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    entity_type VARCHAR(20) NOT NULL,
    entity_id VARCHAR(100) NOT NULL,
    summary_date DATE NOT NULL,
    base_currency VARCHAR(3) NOT NULL,
    realized_pnl DECIMAL(20, 4) NOT NULL,
    unrealized_pnl DECIMAL(20, 4) NOT NULL,
    total_pnl DECIMAL(20, 4) NOT NULL,
    trading_pnl DECIMAL(20, 4) NOT NULL,
    fx_pnl DECIMAL(20, 4) NOT NULL,
    fees DECIMAL(20, 4) NOT NULL,
    net_pnl DECIMAL(20, 4) NOT NULL,
    mtd_pnl DECIMAL(20, 4),
    ytd_pnl DECIMAL(20, 4),
    breakdown JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_pnl_summary ON pnl_summaries(tenant_id, entity_type, entity_id, summary_date);
```

## Definition of Done

- [ ] Real-time P&L calculation
- [ ] Daily summary generation
- [ ] Multi-currency support
- [ ] P&L attribution
- [ ] TWR calculation
- [ ] WebSocket streaming
- [ ] EOD batch processing

## Test Cases

```typescript
describe('PnlCalculatorService', () => {
  it('should calculate client P&L with FX translation', async () => {
    await createPositions([
      { currency: 'EUR', quantity: 100000, unrealizedPnL: 5000 },
      { currency: 'USD', quantity: 50000, unrealizedPnL: 2000 },
    ]);

    const snapshot = await pnlCalculator.calculateClientPnl('tenant-1', 'client-1', 'USD');

    expect(snapshot.unrealizedPnL).toBeGreaterThan(7000); // EUR converted at rate
    expect(snapshot.positions.length).toBe(2);
  });

  it('should calculate daily summary with MTD/YTD', async () => {
    const summary = await pnlCalculator.calculateDailySummary(
      'tenant-1', 'client', 'client-1', new Date(), 'USD'
    );

    expect(summary.mtdPnL).toBeDefined();
    expect(summary.ytdPnL).toBeDefined();
  });

  it('should calculate time-weighted return', async () => {
    await createDailySummaries();

    const { twr } = await pnlCalculator.calculateTWR(
      'tenant-1', 'client-1',
      new Date('2024-01-01'), new Date('2024-01-31')
    );

    expect(twr).toBeDefined();
  });
});
```
