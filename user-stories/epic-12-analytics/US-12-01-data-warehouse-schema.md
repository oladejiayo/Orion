# US-12-01: Data Warehouse Schema

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-12-01 |
| **Epic** | Epic 12: Analytics & Data Products |
| **Title** | Data Warehouse Schema |
| **Priority** | High |
| **Story Points** | 13 |
| **Status** | Ready for Development |

## User Story

**As a** data analyst  
**I want** a well-designed dimensional data warehouse schema  
**So that** I can perform efficient analytical queries across trading, risk, and operational data

## Acceptance Criteria

### AC1: Fact Table Implementation
- **Given** trading events occur in the platform
- **When** events are processed by ETL
- **Then** fact records are created with:
  - Proper foreign keys to dimensions
  - Degenerate dimensions for transaction IDs
  - Calculated metrics (notional, fees, latency)
  - Partitioning by date for efficient queries

### AC2: Dimension Tables with SCD Type 2
- **Given** dimension attributes change over time (e.g., client segment)
- **When** the ETL detects changes
- **Then** SCD Type 2 is applied:
  - Previous record marked with validTo date
  - New record created with validFrom = current date
  - isCurrent flag updated appropriately
  - Historical analysis preserves point-in-time accuracy

### AC3: Pre-Aggregated Rollups
- **Given** common analytical queries (daily summaries, client metrics)
- **When** aggregation jobs run
- **Then** materialized aggregate tables are populated:
  - Daily trade summaries by client/instrument/LP
  - Weekly and monthly rollups
  - Running totals and averages
  - Automatic refresh schedules

### AC4: Time Dimension
- **Given** date-based analysis requirements
- **When** the time dimension is populated
- **Then** it includes:
  - All dates for the operational range (10 years)
  - Calendar attributes (year, quarter, month, week)
  - Fiscal period attributes
  - Holiday and weekend flags
  - Trading day indicators

### AC5: Multi-Tenant Isolation
- **Given** the warehouse serves multiple tenants
- **When** queries are executed
- **Then** tenant isolation is enforced:
  - All fact tables include tenantId
  - Row-level security policies applied
  - No cross-tenant data leakage
  - Aggregate tables partitioned by tenant

## Technical Specification

### Fact Tables Entity Definitions

```typescript
// src/analytics/entities/fact-trade.entity.ts
import { Entity, PrimaryGeneratedColumn, Column, Index, CreateDateColumn } from 'typeorm';

@Entity('fact_trades')
@Index(['tenantId', 'tradeDate'])
@Index(['tenantId', 'clientId', 'tradeDate'])
@Index(['tenantId', 'instrumentId', 'tradeDate'])
@Index(['tenantId', 'lpId', 'tradeDate'])
export class FactTradeEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ type: 'uuid' })
  @Index()
  tenantId: string;

  @Column({ type: 'date' })
  tradeDate: Date;

  @Column({ type: 'date' })
  settlementDate: Date;

  @Column({ type: 'integer' })
  tradeDateKey: number; // YYYYMMDD for join with DimTime

  @Column({ type: 'uuid' })
  clientId: string;

  @Column({ type: 'uuid' })
  instrumentId: string;

  @Column({ type: 'uuid', nullable: true })
  lpId: string;

  // Degenerate dimensions
  @Column({ type: 'varchar', length: 50 })
  tradeReference: string;

  @Column({ type: 'uuid' })
  orderId: string;

  @Column({ type: 'varchar', length: 10 })
  side: string; // BUY, SELL

  @Column({ type: 'varchar', length: 20 })
  orderType: string;

  // Measures
  @Column({ type: 'decimal', precision: 18, scale: 8 })
  quantity: number;

  @Column({ type: 'decimal', precision: 18, scale: 8 })
  price: number;

  @Column({ type: 'decimal', precision: 18, scale: 2 })
  notionalValue: number;

  @Column({ type: 'decimal', precision: 18, scale: 4 })
  fees: number;

  @Column({ type: 'decimal', precision: 18, scale: 2 })
  netAmount: number;

  @Column({ type: 'varchar', length: 3 })
  currency: string;

  @Column({ type: 'varchar', length: 50, nullable: true })
  executionVenue: string;

  @Column({ type: 'integer', default: 0 })
  fillLatencyMs: number;

  @Column({ type: 'integer', default: 1 })
  fillCount: number;

  @Column({ type: 'boolean', default: false })
  isPartialFill: boolean;

  @CreateDateColumn()
  createdAt: Date;
}

// src/analytics/entities/fact-quote.entity.ts
@Entity('fact_quotes')
@Index(['tenantId', 'quoteDate'])
@Index(['tenantId', 'instrumentId', 'quoteDate'])
@Index(['tenantId', 'lpId', 'quoteDate'])
export class FactQuoteEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ type: 'uuid' })
  tenantId: string;

  @Column({ type: 'timestamp with time zone' })
  quoteTimestamp: Date;

  @Column({ type: 'date' })
  quoteDate: Date;

  @Column({ type: 'integer' })
  quoteDateKey: number;

  @Column({ type: 'uuid' })
  instrumentId: string;

  @Column({ type: 'uuid' })
  lpId: string;

  // Measures
  @Column({ type: 'decimal', precision: 18, scale: 8 })
  bidPrice: number;

  @Column({ type: 'decimal', precision: 18, scale: 8 })
  askPrice: number;

  @Column({ type: 'decimal', precision: 18, scale: 2 })
  bidSize: number;

  @Column({ type: 'decimal', precision: 18, scale: 2 })
  askSize: number;

  @Column({ type: 'decimal', precision: 18, scale: 8 })
  midPrice: number;

  @Column({ type: 'decimal', precision: 10, scale: 6 })
  spread: number;

  @Column({ type: 'decimal', precision: 8, scale: 4 })
  spreadBps: number;

  @Column({ type: 'boolean', default: false })
  isStale: boolean;

  @Column({ type: 'integer', default: 0 })
  latencyMs: number;

  @CreateDateColumn()
  createdAt: Date;
}

// src/analytics/entities/fact-risk-snapshot.entity.ts
@Entity('fact_risk_snapshots')
@Index(['tenantId', 'snapshotDate'])
@Index(['tenantId', 'clientId', 'snapshotDate'])
export class FactRiskSnapshotEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ type: 'uuid' })
  tenantId: string;

  @Column({ type: 'date' })
  snapshotDate: Date;

  @Column({ type: 'integer' })
  snapshotDateKey: number;

  @Column({ type: 'uuid' })
  clientId: string;

  // Measures
  @Column({ type: 'decimal', precision: 18, scale: 2 })
  grossExposure: number;

  @Column({ type: 'decimal', precision: 18, scale: 2 })
  netExposure: number;

  @Column({ type: 'decimal', precision: 18, scale: 2 })
  creditLimit: number;

  @Column({ type: 'decimal', precision: 8, scale: 4 })
  utilizationPct: number;

  @Column({ type: 'decimal', precision: 18, scale: 2 })
  var95: number;

  @Column({ type: 'decimal', precision: 18, scale: 2 })
  var99: number;

  @Column({ type: 'decimal', precision: 8, scale: 4 })
  riskScore: number;

  @Column({ type: 'integer', default: 0 })
  softBreachCount: number;

  @Column({ type: 'integer', default: 0 })
  hardBreachCount: number;

  @Column({ type: 'integer', default: 0 })
  openPositionCount: number;

  @CreateDateColumn()
  createdAt: Date;
}

// src/analytics/entities/fact-order.entity.ts
@Entity('fact_orders')
@Index(['tenantId', 'orderDate'])
@Index(['tenantId', 'clientId', 'orderDate'])
export class FactOrderEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ type: 'uuid' })
  tenantId: string;

  @Column({ type: 'date' })
  orderDate: Date;

  @Column({ type: 'integer' })
  orderDateKey: number;

  @Column({ type: 'uuid' })
  clientId: string;

  @Column({ type: 'uuid' })
  instrumentId: string;

  @Column({ type: 'uuid' })
  orderId: string;

  @Column({ type: 'varchar', length: 20 })
  orderType: string;

  @Column({ type: 'varchar', length: 10 })
  side: string;

  @Column({ type: 'varchar', length: 20 })
  finalStatus: string;

  // Measures
  @Column({ type: 'decimal', precision: 18, scale: 8 })
  requestedQuantity: number;

  @Column({ type: 'decimal', precision: 18, scale: 8 })
  filledQuantity: number;

  @Column({ type: 'decimal', precision: 8, scale: 4 })
  fillRate: number;

  @Column({ type: 'decimal', precision: 18, scale: 8, nullable: true })
  limitPrice: number;

  @Column({ type: 'decimal', precision: 18, scale: 8, nullable: true })
  avgFillPrice: number;

  @Column({ type: 'integer' })
  fillCount: number;

  @Column({ type: 'integer' })
  totalLatencyMs: number;

  @Column({ type: 'integer' })
  routingLatencyMs: number;

  @Column({ type: 'boolean', default: false })
  wasRejected: boolean;

  @Column({ type: 'boolean', default: false })
  wasCancelled: boolean;

  @CreateDateColumn()
  createdAt: Date;
}
```

### Dimension Tables

```typescript
// src/analytics/entities/dim-client.entity.ts
import { Entity, PrimaryGeneratedColumn, Column, Index, CreateDateColumn } from 'typeorm';

@Entity('dim_clients')
@Index(['clientId', 'isCurrent'])
@Index(['tenantId', 'isCurrent'])
export class DimClientEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ type: 'uuid' })
  tenantId: string;

  @Column({ type: 'uuid' })
  clientId: string; // Business key

  @Column({ type: 'varchar', length: 50 })
  clientCode: string;

  @Column({ type: 'varchar', length: 255 })
  clientName: string;

  @Column({ type: 'varchar', length: 50 })
  clientType: string; // INSTITUTIONAL, CORPORATE, RETAIL

  @Column({ type: 'varchar', length: 50 })
  region: string;

  @Column({ type: 'varchar', length: 50 })
  segment: string; // TIER_1, TIER_2, TIER_3

  @Column({ type: 'varchar', length: 50 })
  industry: string;

  @Column({ type: 'date' })
  onboardDate: Date;

  @Column({ type: 'varchar', length: 20 })
  status: string;

  // SCD Type 2 columns
  @Column({ type: 'timestamp with time zone' })
  validFrom: Date;

  @Column({ type: 'timestamp with time zone', nullable: true })
  validTo: Date;

  @Column({ type: 'boolean', default: true })
  isCurrent: boolean;

  @Column({ type: 'integer', default: 1 })
  version: number;

  @CreateDateColumn()
  createdAt: Date;
}

// src/analytics/entities/dim-instrument.entity.ts
@Entity('dim_instruments')
@Index(['instrumentId', 'isCurrent'])
export class DimInstrumentEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ type: 'uuid' })
  instrumentId: string; // Business key

  @Column({ type: 'varchar', length: 20 })
  symbol: string;

  @Column({ type: 'varchar', length: 255 })
  name: string;

  @Column({ type: 'varchar', length: 20 })
  assetClass: string; // FX, EQUITY, FIXED_INCOME, COMMODITY

  @Column({ type: 'varchar', length: 30 })
  instrumentType: string; // SPOT, FORWARD, SWAP, OPTION

  @Column({ type: 'varchar', length: 3 })
  baseCurrency: string;

  @Column({ type: 'varchar', length: 3 })
  quoteCurrency: string;

  @Column({ type: 'varchar', length: 50, nullable: true })
  exchange: string;

  @Column({ type: 'integer', default: 2 })
  pricePrecision: number;

  @Column({ type: 'integer', default: 2 })
  quantityPrecision: number;

  // SCD Type 2 columns
  @Column({ type: 'timestamp with time zone' })
  validFrom: Date;

  @Column({ type: 'timestamp with time zone', nullable: true })
  validTo: Date;

  @Column({ type: 'boolean', default: true })
  isCurrent: boolean;

  @Column({ type: 'integer', default: 1 })
  version: number;

  @CreateDateColumn()
  createdAt: Date;
}

// src/analytics/entities/dim-lp.entity.ts
@Entity('dim_liquidity_providers')
@Index(['lpId', 'isCurrent'])
export class DimLpEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ type: 'uuid' })
  lpId: string; // Business key

  @Column({ type: 'varchar', length: 50 })
  lpCode: string;

  @Column({ type: 'varchar', length: 255 })
  lpName: string;

  @Column({ type: 'varchar', length: 50 })
  lpType: string; // BANK, ECN, EXCHANGE, INTERNAL

  @Column({ type: 'varchar', length: 50 })
  region: string;

  @Column({ type: 'varchar', length: 20 })
  tier: string;

  @Column({ type: 'boolean', default: true })
  isActive: boolean;

  // SCD Type 2 columns
  @Column({ type: 'timestamp with time zone' })
  validFrom: Date;

  @Column({ type: 'timestamp with time zone', nullable: true })
  validTo: Date;

  @Column({ type: 'boolean', default: true })
  isCurrent: boolean;

  @CreateDateColumn()
  createdAt: Date;
}

// src/analytics/entities/dim-time.entity.ts
@Entity('dim_time')
@Index(['dateKey'], { unique: true })
export class DimTimeEntity {
  @PrimaryGeneratedColumn()
  id: number;

  @Column({ type: 'integer' })
  dateKey: number; // YYYYMMDD

  @Column({ type: 'date' })
  date: Date;

  @Column({ type: 'integer' })
  year: number;

  @Column({ type: 'integer' })
  quarter: number;

  @Column({ type: 'integer' })
  month: number;

  @Column({ type: 'varchar', length: 20 })
  monthName: string;

  @Column({ type: 'integer' })
  week: number;

  @Column({ type: 'integer' })
  dayOfWeek: number; // 0 = Sunday

  @Column({ type: 'varchar', length: 10 })
  dayName: string;

  @Column({ type: 'integer' })
  dayOfMonth: number;

  @Column({ type: 'integer' })
  dayOfYear: number;

  @Column({ type: 'boolean', default: false })
  isWeekend: boolean;

  @Column({ type: 'boolean', default: false })
  isHoliday: boolean;

  @Column({ type: 'varchar', length: 100, nullable: true })
  holidayName: string;

  @Column({ type: 'boolean', default: true })
  isTradingDay: boolean;

  // Fiscal calendar
  @Column({ type: 'integer' })
  fiscalYear: number;

  @Column({ type: 'integer' })
  fiscalQuarter: number;

  @Column({ type: 'integer' })
  fiscalMonth: number;

  // Relative flags (updated nightly)
  @Column({ type: 'boolean', default: false })
  isToday: boolean;

  @Column({ type: 'boolean', default: false })
  isYesterday: boolean;

  @Column({ type: 'boolean', default: false })
  isCurrentWeek: boolean;

  @Column({ type: 'boolean', default: false })
  isCurrentMonth: boolean;

  @Column({ type: 'boolean', default: false })
  isCurrentQuarter: boolean;

  @Column({ type: 'boolean', default: false })
  isCurrentYear: boolean;
}
```

### Aggregate Tables

```typescript
// src/analytics/entities/agg-daily-trade-summary.entity.ts
import { Entity, PrimaryGeneratedColumn, Column, Index, CreateDateColumn, UpdateDateColumn } from 'typeorm';

@Entity('agg_daily_trade_summary')
@Index(['tenantId', 'tradeDate'])
@Index(['tenantId', 'clientId', 'tradeDate'])
@Index(['tenantId', 'instrumentId', 'tradeDate'])
export class AggDailyTradeSummaryEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ type: 'uuid' })
  tenantId: string;

  @Column({ type: 'date' })
  tradeDate: Date;

  @Column({ type: 'integer' })
  tradeDateKey: number;

  // Grouping dimensions (nullable for different aggregation levels)
  @Column({ type: 'uuid', nullable: true })
  clientId: string;

  @Column({ type: 'uuid', nullable: true })
  instrumentId: string;

  @Column({ type: 'uuid', nullable: true })
  lpId: string;

  @Column({ type: 'varchar', length: 20, nullable: true })
  assetClass: string;

  @Column({ type: 'varchar', length: 3, nullable: true })
  currency: string;

  // Aggregation level indicator
  @Column({ type: 'varchar', length: 50 })
  aggregationLevel: string; // 'TENANT', 'CLIENT', 'INSTRUMENT', 'LP', 'CLIENT_INSTRUMENT', etc.

  // Measures
  @Column({ type: 'integer', default: 0 })
  tradeCount: number;

  @Column({ type: 'integer', default: 0 })
  buyCount: number;

  @Column({ type: 'integer', default: 0 })
  sellCount: number;

  @Column({ type: 'decimal', precision: 18, scale: 8, default: 0 })
  totalVolume: number;

  @Column({ type: 'decimal', precision: 18, scale: 8, default: 0 })
  buyVolume: number;

  @Column({ type: 'decimal', precision: 18, scale: 8, default: 0 })
  sellVolume: number;

  @Column({ type: 'decimal', precision: 18, scale: 2, default: 0 })
  totalNotional: number;

  @Column({ type: 'decimal', precision: 18, scale: 4, default: 0 })
  totalFees: number;

  @Column({ type: 'decimal', precision: 18, scale: 8, nullable: true })
  avgPrice: number;

  @Column({ type: 'decimal', precision: 18, scale: 8, nullable: true })
  vwapPrice: number;

  @Column({ type: 'decimal', precision: 18, scale: 8, nullable: true })
  minPrice: number;

  @Column({ type: 'decimal', precision: 18, scale: 8, nullable: true })
  maxPrice: number;

  @Column({ type: 'decimal', precision: 10, scale: 2, default: 0 })
  avgLatencyMs: number;

  @Column({ type: 'integer', default: 0 })
  maxLatencyMs: number;

  @CreateDateColumn()
  createdAt: Date;

  @UpdateDateColumn()
  updatedAt: Date;
}

// src/analytics/entities/agg-client-metrics.entity.ts
@Entity('agg_client_metrics')
@Index(['tenantId', 'metricDate'])
@Index(['tenantId', 'clientId', 'metricDate'])
export class AggClientMetricsEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ type: 'uuid' })
  tenantId: string;

  @Column({ type: 'date' })
  metricDate: Date;

  @Column({ type: 'uuid' })
  clientId: string;

  @Column({ type: 'varchar', length: 20 })
  periodType: string; // 'DAILY', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'YEARLY'

  // Activity metrics
  @Column({ type: 'integer', default: 0 })
  tradingDays: number;

  @Column({ type: 'integer', default: 0 })
  totalTrades: number;

  @Column({ type: 'integer', default: 0 })
  uniqueInstruments: number;

  // Volume metrics
  @Column({ type: 'decimal', precision: 18, scale: 8, default: 0 })
  totalVolume: number;

  @Column({ type: 'decimal', precision: 18, scale: 2, default: 0 })
  totalNotional: number;

  @Column({ type: 'decimal', precision: 18, scale: 2, default: 0 })
  avgTradeSize: number;

  // Profitability metrics
  @Column({ type: 'decimal', precision: 18, scale: 4, default: 0 })
  totalFees: number;

  @Column({ type: 'decimal', precision: 18, scale: 2, default: 0 })
  realizedPnl: number;

  // Risk metrics
  @Column({ type: 'decimal', precision: 8, scale: 4, default: 0 })
  avgUtilization: number;

  @Column({ type: 'decimal', precision: 8, scale: 4, default: 0 })
  maxUtilization: number;

  @Column({ type: 'integer', default: 0 })
  breachCount: number;

  @Column({ type: 'decimal', precision: 8, scale: 4, nullable: true })
  riskScore: number;

  // Engagement score
  @Column({ type: 'decimal', precision: 8, scale: 4, nullable: true })
  engagementScore: number;

  @CreateDateColumn()
  createdAt: Date;

  @UpdateDateColumn()
  updatedAt: Date;
}

// src/analytics/entities/agg-lp-performance.entity.ts
@Entity('agg_lp_performance')
@Index(['tenantId', 'metricDate'])
@Index(['tenantId', 'lpId', 'metricDate'])
export class AggLpPerformanceEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ type: 'uuid' })
  tenantId: string;

  @Column({ type: 'date' })
  metricDate: Date;

  @Column({ type: 'uuid' })
  lpId: string;

  @Column({ type: 'uuid', nullable: true })
  instrumentId: string;

  @Column({ type: 'varchar', length: 20 })
  periodType: string;

  // Quote metrics
  @Column({ type: 'integer', default: 0 })
  quoteCount: number;

  @Column({ type: 'decimal', precision: 10, scale: 6, default: 0 })
  avgSpread: number;

  @Column({ type: 'decimal', precision: 8, scale: 4, default: 0 })
  avgSpreadBps: number;

  @Column({ type: 'decimal', precision: 8, scale: 4, default: 0 })
  quoteAvailabilityPct: number;

  @Column({ type: 'decimal', precision: 10, scale: 2, default: 0 })
  avgLatencyMs: number;

  // Trade metrics
  @Column({ type: 'integer', default: 0 })
  tradeCount: number;

  @Column({ type: 'decimal', precision: 18, scale: 2, default: 0 })
  totalNotional: number;

  @Column({ type: 'decimal', precision: 8, scale: 4, default: 0 })
  fillRate: number;

  @Column({ type: 'integer', default: 0 })
  rejectionCount: number;

  // Ranking
  @Column({ type: 'integer', nullable: true })
  overallRank: number;

  @Column({ type: 'decimal', precision: 8, scale: 4, nullable: true })
  performanceScore: number;

  @CreateDateColumn()
  createdAt: Date;

  @UpdateDateColumn()
  updatedAt: Date;
}
```

### Time Dimension Generator

```typescript
// src/analytics/services/time-dimension.service.ts
import { Injectable, Logger } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { DimTimeEntity } from '../entities/dim-time.entity';

@Injectable()
export class TimeDimensionService {
  private readonly logger = new Logger(TimeDimensionService.name);

  constructor(
    @InjectRepository(DimTimeEntity)
    private readonly dimTimeRepo: Repository<DimTimeEntity>,
  ) {}

  async populateTimeDimension(startYear: number, endYear: number): Promise<void> {
    this.logger.log(`Populating time dimension from ${startYear} to ${endYear}`);

    const holidays = await this.loadHolidays(); // Load from external source
    const batch: Partial<DimTimeEntity>[] = [];

    for (let year = startYear; year <= endYear; year++) {
      for (let month = 0; month < 12; month++) {
        const daysInMonth = new Date(year, month + 1, 0).getDate();
        
        for (let day = 1; day <= daysInMonth; day++) {
          const date = new Date(year, month, day);
          const dateKey = year * 10000 + (month + 1) * 100 + day;
          
          const dayOfWeek = date.getDay();
          const isWeekend = dayOfWeek === 0 || dayOfWeek === 6;
          const holidayInfo = holidays.get(dateKey);
          const isHoliday = !!holidayInfo;
          
          const entry: Partial<DimTimeEntity> = {
            dateKey,
            date,
            year,
            quarter: Math.floor(month / 3) + 1,
            month: month + 1,
            monthName: date.toLocaleString('en-US', { month: 'long' }),
            week: this.getWeekNumber(date),
            dayOfWeek,
            dayName: date.toLocaleString('en-US', { weekday: 'long' }),
            dayOfMonth: day,
            dayOfYear: this.getDayOfYear(date),
            isWeekend,
            isHoliday,
            holidayName: holidayInfo?.name || null,
            isTradingDay: !isWeekend && !isHoliday,
            fiscalYear: month >= 6 ? year + 1 : year, // July fiscal year start
            fiscalQuarter: this.getFiscalQuarter(month),
            fiscalMonth: ((month + 6) % 12) + 1,
            isToday: false,
            isYesterday: false,
            isCurrentWeek: false,
            isCurrentMonth: false,
            isCurrentQuarter: false,
            isCurrentYear: false,
          };

          batch.push(entry);

          if (batch.length >= 1000) {
            await this.dimTimeRepo.upsert(batch, ['dateKey']);
            batch.length = 0;
          }
        }
      }
    }

    if (batch.length > 0) {
      await this.dimTimeRepo.upsert(batch, ['dateKey']);
    }

    this.logger.log('Time dimension populated successfully');
  }

  async updateRelativeFlags(): Promise<void> {
    const today = new Date();
    const todayKey = this.toDateKey(today);

    // Reset all flags
    await this.dimTimeRepo.update({}, {
      isToday: false,
      isYesterday: false,
      isCurrentWeek: false,
      isCurrentMonth: false,
      isCurrentQuarter: false,
      isCurrentYear: false,
    });

    // Set today
    await this.dimTimeRepo.update({ dateKey: todayKey }, { isToday: true });

    // Set yesterday
    const yesterday = new Date(today);
    yesterday.setDate(yesterday.getDate() - 1);
    await this.dimTimeRepo.update(
      { dateKey: this.toDateKey(yesterday) },
      { isYesterday: true },
    );

    // Set current week
    const weekStart = this.getWeekStart(today);
    const weekEnd = new Date(weekStart);
    weekEnd.setDate(weekEnd.getDate() + 6);
    await this.dimTimeRepo
      .createQueryBuilder()
      .update()
      .set({ isCurrentWeek: true })
      .where('dateKey >= :start AND dateKey <= :end', {
        start: this.toDateKey(weekStart),
        end: this.toDateKey(weekEnd),
      })
      .execute();

    // Set current month
    await this.dimTimeRepo.update(
      { year: today.getFullYear(), month: today.getMonth() + 1 },
      { isCurrentMonth: true },
    );

    // Set current quarter
    const quarter = Math.floor(today.getMonth() / 3) + 1;
    await this.dimTimeRepo.update(
      { year: today.getFullYear(), quarter },
      { isCurrentQuarter: true },
    );

    // Set current year
    await this.dimTimeRepo.update(
      { year: today.getFullYear() },
      { isCurrentYear: true },
    );

    this.logger.log('Relative time flags updated');
  }

  private toDateKey(date: Date): number {
    return date.getFullYear() * 10000 + (date.getMonth() + 1) * 100 + date.getDate();
  }

  private getWeekNumber(date: Date): number {
    const d = new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()));
    const dayNum = d.getUTCDay() || 7;
    d.setUTCDate(d.getUTCDate() + 4 - dayNum);
    const yearStart = new Date(Date.UTC(d.getUTCFullYear(), 0, 1));
    return Math.ceil((((d.getTime() - yearStart.getTime()) / 86400000) + 1) / 7);
  }

  private getDayOfYear(date: Date): number {
    const start = new Date(date.getFullYear(), 0, 0);
    const diff = date.getTime() - start.getTime();
    return Math.floor(diff / (1000 * 60 * 60 * 24));
  }

  private getFiscalQuarter(month: number): number {
    // July = FQ1
    const fiscalMonth = (month + 6) % 12;
    return Math.floor(fiscalMonth / 3) + 1;
  }

  private getWeekStart(date: Date): Date {
    const d = new Date(date);
    const day = d.getDay();
    const diff = d.getDate() - day + (day === 0 ? -6 : 1);
    return new Date(d.setDate(diff));
  }

  private async loadHolidays(): Promise<Map<number, { name: string }>> {
    // In production, load from configuration or external service
    const holidays = new Map<number, { name: string }>();
    // Add US federal holidays as example
    // This would be expanded with proper holiday calendars
    return holidays;
  }
}
```

## Database Schema

```sql
-- =====================================================
-- FACT TABLES
-- =====================================================

-- Fact: Trades (partitioned by month)
CREATE TABLE fact_trades (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    trade_date DATE NOT NULL,
    settlement_date DATE NOT NULL,
    trade_date_key INTEGER NOT NULL,
    client_id UUID NOT NULL,
    instrument_id UUID NOT NULL,
    lp_id UUID,
    trade_reference VARCHAR(50) NOT NULL,
    order_id UUID NOT NULL,
    side VARCHAR(10) NOT NULL,
    order_type VARCHAR(20) NOT NULL,
    quantity DECIMAL(18, 8) NOT NULL,
    price DECIMAL(18, 8) NOT NULL,
    notional_value DECIMAL(18, 2) NOT NULL,
    fees DECIMAL(18, 4) NOT NULL DEFAULT 0,
    net_amount DECIMAL(18, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    execution_venue VARCHAR(50),
    fill_latency_ms INTEGER DEFAULT 0,
    fill_count INTEGER DEFAULT 1,
    is_partial_fill BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW()
) PARTITION BY RANGE (trade_date);

-- Create monthly partitions
CREATE TABLE fact_trades_2026_01 PARTITION OF fact_trades
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
CREATE TABLE fact_trades_2026_02 PARTITION OF fact_trades
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
-- Continue for all months...

-- Indexes on fact_trades
CREATE INDEX idx_fact_trades_tenant_date ON fact_trades(tenant_id, trade_date);
CREATE INDEX idx_fact_trades_client ON fact_trades(tenant_id, client_id, trade_date);
CREATE INDEX idx_fact_trades_instrument ON fact_trades(tenant_id, instrument_id, trade_date);
CREATE INDEX idx_fact_trades_lp ON fact_trades(tenant_id, lp_id, trade_date);

-- Row-level security
ALTER TABLE fact_trades ENABLE ROW LEVEL SECURITY;

CREATE POLICY fact_trades_tenant_isolation ON fact_trades
    USING (tenant_id = current_setting('app.tenant_id')::UUID);

-- Fact: Quotes (TimescaleDB hypertable for time-series)
CREATE TABLE fact_quotes (
    id UUID DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    quote_timestamp TIMESTAMPTZ NOT NULL,
    quote_date DATE NOT NULL,
    quote_date_key INTEGER NOT NULL,
    instrument_id UUID NOT NULL,
    lp_id UUID NOT NULL,
    bid_price DECIMAL(18, 8) NOT NULL,
    ask_price DECIMAL(18, 8) NOT NULL,
    bid_size DECIMAL(18, 2) NOT NULL,
    ask_size DECIMAL(18, 2) NOT NULL,
    mid_price DECIMAL(18, 8) NOT NULL,
    spread DECIMAL(10, 6) NOT NULL,
    spread_bps DECIMAL(8, 4) NOT NULL,
    is_stale BOOLEAN DEFAULT FALSE,
    latency_ms INTEGER DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (id, quote_timestamp)
);

-- Convert to TimescaleDB hypertable
SELECT create_hypertable('fact_quotes', 'quote_timestamp', chunk_time_interval => INTERVAL '1 day');

-- Compression policy for older data
ALTER TABLE fact_quotes SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'tenant_id, instrument_id, lp_id'
);

SELECT add_compression_policy('fact_quotes', INTERVAL '7 days');

-- Fact: Risk Snapshots
CREATE TABLE fact_risk_snapshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    snapshot_date DATE NOT NULL,
    snapshot_date_key INTEGER NOT NULL,
    client_id UUID NOT NULL,
    gross_exposure DECIMAL(18, 2) NOT NULL,
    net_exposure DECIMAL(18, 2) NOT NULL,
    credit_limit DECIMAL(18, 2) NOT NULL,
    utilization_pct DECIMAL(8, 4) NOT NULL,
    var_95 DECIMAL(18, 2) NOT NULL,
    var_99 DECIMAL(18, 2) NOT NULL,
    risk_score DECIMAL(8, 4),
    soft_breach_count INTEGER DEFAULT 0,
    hard_breach_count INTEGER DEFAULT 0,
    open_position_count INTEGER DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_risk_snapshots_tenant_date ON fact_risk_snapshots(tenant_id, snapshot_date);
CREATE INDEX idx_risk_snapshots_client ON fact_risk_snapshots(tenant_id, client_id, snapshot_date);

ALTER TABLE fact_risk_snapshots ENABLE ROW LEVEL SECURITY;
CREATE POLICY risk_snapshots_tenant_isolation ON fact_risk_snapshots
    USING (tenant_id = current_setting('app.tenant_id')::UUID);

-- =====================================================
-- DIMENSION TABLES (SCD Type 2)
-- =====================================================

-- Dimension: Clients
CREATE TABLE dim_clients (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    client_id UUID NOT NULL,
    client_code VARCHAR(50) NOT NULL,
    client_name VARCHAR(255) NOT NULL,
    client_type VARCHAR(50) NOT NULL,
    region VARCHAR(50),
    segment VARCHAR(50),
    industry VARCHAR(50),
    onboard_date DATE,
    status VARCHAR(20) NOT NULL,
    valid_from TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    valid_to TIMESTAMPTZ,
    is_current BOOLEAN NOT NULL DEFAULT TRUE,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_dim_clients_current ON dim_clients(client_id, is_current) WHERE is_current = TRUE;
CREATE INDEX idx_dim_clients_tenant ON dim_clients(tenant_id, is_current);
CREATE UNIQUE INDEX idx_dim_clients_business_key ON dim_clients(client_id, valid_from);

-- Dimension: Instruments
CREATE TABLE dim_instruments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    instrument_id UUID NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    name VARCHAR(255) NOT NULL,
    asset_class VARCHAR(20) NOT NULL,
    instrument_type VARCHAR(30) NOT NULL,
    base_currency VARCHAR(3) NOT NULL,
    quote_currency VARCHAR(3),
    exchange VARCHAR(50),
    price_precision INTEGER DEFAULT 2,
    quantity_precision INTEGER DEFAULT 2,
    valid_from TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    valid_to TIMESTAMPTZ,
    is_current BOOLEAN NOT NULL DEFAULT TRUE,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_dim_instruments_current ON dim_instruments(instrument_id, is_current) WHERE is_current = TRUE;
CREATE UNIQUE INDEX idx_dim_instruments_business_key ON dim_instruments(instrument_id, valid_from);

-- Dimension: Liquidity Providers
CREATE TABLE dim_liquidity_providers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lp_id UUID NOT NULL,
    lp_code VARCHAR(50) NOT NULL,
    lp_name VARCHAR(255) NOT NULL,
    lp_type VARCHAR(50) NOT NULL,
    region VARCHAR(50),
    tier VARCHAR(20),
    is_active BOOLEAN DEFAULT TRUE,
    valid_from TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    valid_to TIMESTAMPTZ,
    is_current BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_dim_lp_current ON dim_liquidity_providers(lp_id, is_current) WHERE is_current = TRUE;

-- Dimension: Time
CREATE TABLE dim_time (
    id SERIAL PRIMARY KEY,
    date_key INTEGER UNIQUE NOT NULL,
    date DATE NOT NULL,
    year INTEGER NOT NULL,
    quarter INTEGER NOT NULL,
    month INTEGER NOT NULL,
    month_name VARCHAR(20) NOT NULL,
    week INTEGER NOT NULL,
    day_of_week INTEGER NOT NULL,
    day_name VARCHAR(10) NOT NULL,
    day_of_month INTEGER NOT NULL,
    day_of_year INTEGER NOT NULL,
    is_weekend BOOLEAN DEFAULT FALSE,
    is_holiday BOOLEAN DEFAULT FALSE,
    holiday_name VARCHAR(100),
    is_trading_day BOOLEAN DEFAULT TRUE,
    fiscal_year INTEGER NOT NULL,
    fiscal_quarter INTEGER NOT NULL,
    fiscal_month INTEGER NOT NULL,
    is_today BOOLEAN DEFAULT FALSE,
    is_yesterday BOOLEAN DEFAULT FALSE,
    is_current_week BOOLEAN DEFAULT FALSE,
    is_current_month BOOLEAN DEFAULT FALSE,
    is_current_quarter BOOLEAN DEFAULT FALSE,
    is_current_year BOOLEAN DEFAULT FALSE
);

CREATE INDEX idx_dim_time_date ON dim_time(date);
CREATE INDEX idx_dim_time_year_month ON dim_time(year, month);

-- =====================================================
-- AGGREGATE TABLES
-- =====================================================

-- Aggregate: Daily Trade Summary
CREATE TABLE agg_daily_trade_summary (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    trade_date DATE NOT NULL,
    trade_date_key INTEGER NOT NULL,
    client_id UUID,
    instrument_id UUID,
    lp_id UUID,
    asset_class VARCHAR(20),
    currency VARCHAR(3),
    aggregation_level VARCHAR(50) NOT NULL,
    trade_count INTEGER DEFAULT 0,
    buy_count INTEGER DEFAULT 0,
    sell_count INTEGER DEFAULT 0,
    total_volume DECIMAL(18, 8) DEFAULT 0,
    buy_volume DECIMAL(18, 8) DEFAULT 0,
    sell_volume DECIMAL(18, 8) DEFAULT 0,
    total_notional DECIMAL(18, 2) DEFAULT 0,
    total_fees DECIMAL(18, 4) DEFAULT 0,
    avg_price DECIMAL(18, 8),
    vwap_price DECIMAL(18, 8),
    min_price DECIMAL(18, 8),
    max_price DECIMAL(18, 8),
    avg_latency_ms DECIMAL(10, 2) DEFAULT 0,
    max_latency_ms INTEGER DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_agg_daily_tenant_date ON agg_daily_trade_summary(tenant_id, trade_date);
CREATE INDEX idx_agg_daily_client ON agg_daily_trade_summary(tenant_id, client_id, trade_date);
CREATE INDEX idx_agg_daily_instrument ON agg_daily_trade_summary(tenant_id, instrument_id, trade_date);
CREATE UNIQUE INDEX idx_agg_daily_unique ON agg_daily_trade_summary(
    tenant_id, trade_date, aggregation_level, 
    COALESCE(client_id, '00000000-0000-0000-0000-000000000000'),
    COALESCE(instrument_id, '00000000-0000-0000-0000-000000000000'),
    COALESCE(lp_id, '00000000-0000-0000-0000-000000000000')
);

ALTER TABLE agg_daily_trade_summary ENABLE ROW LEVEL SECURITY;
CREATE POLICY agg_daily_tenant_isolation ON agg_daily_trade_summary
    USING (tenant_id = current_setting('app.tenant_id')::UUID);

-- Aggregate: Client Metrics
CREATE TABLE agg_client_metrics (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    metric_date DATE NOT NULL,
    client_id UUID NOT NULL,
    period_type VARCHAR(20) NOT NULL,
    trading_days INTEGER DEFAULT 0,
    total_trades INTEGER DEFAULT 0,
    unique_instruments INTEGER DEFAULT 0,
    total_volume DECIMAL(18, 8) DEFAULT 0,
    total_notional DECIMAL(18, 2) DEFAULT 0,
    avg_trade_size DECIMAL(18, 2) DEFAULT 0,
    total_fees DECIMAL(18, 4) DEFAULT 0,
    realized_pnl DECIMAL(18, 2) DEFAULT 0,
    avg_utilization DECIMAL(8, 4) DEFAULT 0,
    max_utilization DECIMAL(8, 4) DEFAULT 0,
    breach_count INTEGER DEFAULT 0,
    risk_score DECIMAL(8, 4),
    engagement_score DECIMAL(8, 4),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_agg_client_metrics_tenant ON agg_client_metrics(tenant_id, metric_date);
CREATE INDEX idx_agg_client_metrics_client ON agg_client_metrics(tenant_id, client_id, metric_date);
CREATE UNIQUE INDEX idx_agg_client_metrics_unique ON agg_client_metrics(tenant_id, client_id, metric_date, period_type);

-- Aggregate: LP Performance
CREATE TABLE agg_lp_performance (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    metric_date DATE NOT NULL,
    lp_id UUID NOT NULL,
    instrument_id UUID,
    period_type VARCHAR(20) NOT NULL,
    quote_count INTEGER DEFAULT 0,
    avg_spread DECIMAL(10, 6) DEFAULT 0,
    avg_spread_bps DECIMAL(8, 4) DEFAULT 0,
    quote_availability_pct DECIMAL(8, 4) DEFAULT 0,
    avg_latency_ms DECIMAL(10, 2) DEFAULT 0,
    trade_count INTEGER DEFAULT 0,
    total_notional DECIMAL(18, 2) DEFAULT 0,
    fill_rate DECIMAL(8, 4) DEFAULT 0,
    rejection_count INTEGER DEFAULT 0,
    overall_rank INTEGER,
    performance_score DECIMAL(8, 4),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_agg_lp_perf_tenant ON agg_lp_performance(tenant_id, metric_date);
CREATE INDEX idx_agg_lp_perf_lp ON agg_lp_performance(tenant_id, lp_id, metric_date);

-- =====================================================
-- MATERIALIZED VIEWS
-- =====================================================

-- Real-time daily summary (refreshed frequently)
CREATE MATERIALIZED VIEW mv_realtime_daily_summary AS
SELECT 
    tenant_id,
    DATE(trade_date) as trade_date,
    COUNT(*) as trade_count,
    SUM(CASE WHEN side = 'BUY' THEN 1 ELSE 0 END) as buy_count,
    SUM(CASE WHEN side = 'SELL' THEN 1 ELSE 0 END) as sell_count,
    SUM(quantity) as total_volume,
    SUM(notional_value) as total_notional,
    SUM(fees) as total_fees,
    AVG(price) as avg_price,
    SUM(quantity * price) / NULLIF(SUM(quantity), 0) as vwap_price,
    AVG(fill_latency_ms) as avg_latency_ms
FROM fact_trades
WHERE trade_date = CURRENT_DATE
GROUP BY tenant_id, DATE(trade_date);

CREATE UNIQUE INDEX ON mv_realtime_daily_summary(tenant_id, trade_date);

-- Refresh function
CREATE OR REPLACE FUNCTION refresh_realtime_summary()
RETURNS void AS $$
BEGIN
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_realtime_daily_summary;
END;
$$ LANGUAGE plpgsql;
```

## Definition of Done

- [ ] All fact table entities implemented with proper indexes
- [ ] SCD Type 2 implemented for all dimension tables
- [ ] Time dimension populated for 10-year range
- [ ] Aggregate tables created with proper granularity
- [ ] Row-level security enabled on all tables
- [ ] Monthly partitioning configured for large fact tables
- [ ] TimescaleDB hypertables for time-series data
- [ ] Materialized views for common queries
- [ ] Migration scripts created and tested
- [ ] Unit tests for dimension services
- [ ] Integration tests for data integrity

## Dependencies

- PostgreSQL 15+ with TimescaleDB extension
- TypeORM with migration support
- Reference data from Epic 04
- Event sourcing infrastructure from Epic 01

## Test Cases

### Unit Tests
```typescript
describe('TimeDimensionService', () => {
  it('should populate time dimension for date range', async () => {
    await service.populateTimeDimension(2026, 2026);
    const count = await dimTimeRepo.count();
    expect(count).toBe(365); // 2026 is not a leap year
  });

  it('should correctly identify weekends', async () => {
    const saturday = await dimTimeRepo.findOne({ where: { dateKey: 20260214 } });
    expect(saturday.isWeekend).toBe(true);
    expect(saturday.dayName).toBe('Saturday');
  });

  it('should update relative flags correctly', async () => {
    await service.updateRelativeFlags();
    const today = await dimTimeRepo.findOne({ where: { isToday: true } });
    expect(today).toBeDefined();
  });
});
```

### Integration Tests
```typescript
describe('Warehouse Schema Integration', () => {
  it('should enforce tenant isolation on fact tables', async () => {
    await connection.query(`SET app.tenant_id = '${tenantA}'`);
    const trades = await factTradeRepo.find();
    trades.forEach(t => expect(t.tenantId).toBe(tenantA));
  });

  it('should maintain SCD Type 2 history', async () => {
    const client = await dimClientRepo.findOne({
      where: { clientId, isCurrent: true }
    });
    expect(client.version).toBe(2);
    
    const history = await dimClientRepo.find({
      where: { clientId },
      order: { validFrom: 'ASC' }
    });
    expect(history.length).toBe(2);
  });
});
```
