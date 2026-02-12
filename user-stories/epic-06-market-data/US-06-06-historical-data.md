# User Story: US-06-06 - Historical Data Service

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-06-06 |
| **Epic** | Epic 06 - Market Data System |
| **Title** | Historical Data Service |
| **Priority** | P1 - High |
| **Story Points** | 5 |
| **PRD Reference** | FR-MD-02 |

## User Story

**As a** trader or analyst  
**I want** to query historical price data and OHLC bars  
**So that** I can analyze price trends and backtest strategies

## Description

Build a historical data service that stores tick data, calculates OHLC bars at multiple intervals, and provides efficient query APIs.

## Acceptance Criteria

- [ ] Tick data stored in TimescaleDB
- [ ] OHLC bars calculated for 1m, 5m, 15m, 1h, 1d intervals
- [ ] Query API with time range filters
- [ ] Aggregation queries (average, min, max)
- [ ] Data retention policies (hot/warm/cold)
- [ ] S3 archival for long-term storage

## Technical Details

### Database Schema (TimescaleDB)

```sql
-- migrations/20240118_create_price_history.sql

-- Enable TimescaleDB extension
CREATE EXTENSION IF NOT EXISTS timescaledb;

-- Tick data table
CREATE TABLE price_ticks (
    time TIMESTAMPTZ NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    source VARCHAR(50) NOT NULL,
    bid_price NUMERIC(20, 8) NOT NULL,
    ask_price NUMERIC(20, 8) NOT NULL,
    bid_size NUMERIC(20, 8),
    ask_size NUMERIC(20, 8),
    mid_price NUMERIC(20, 8) NOT NULL,
    spread NUMERIC(20, 8) NOT NULL,
    tenant_id UUID NOT NULL
);

-- Convert to hypertable
SELECT create_hypertable('price_ticks', 'time', chunk_time_interval => INTERVAL '1 day');

-- Create indexes
CREATE INDEX idx_price_ticks_symbol_time ON price_ticks (symbol, time DESC);
CREATE INDEX idx_price_ticks_tenant ON price_ticks (tenant_id, symbol, time DESC);

-- OHLC bars table
CREATE TABLE ohlc_bars (
    time TIMESTAMPTZ NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    interval VARCHAR(5) NOT NULL,  -- '1m', '5m', '15m', '1h', '1d'
    open NUMERIC(20, 8) NOT NULL,
    high NUMERIC(20, 8) NOT NULL,
    low NUMERIC(20, 8) NOT NULL,
    close NUMERIC(20, 8) NOT NULL,
    volume NUMERIC(20, 8),
    vwap NUMERIC(20, 8),
    tick_count INTEGER NOT NULL,
    tenant_id UUID NOT NULL,
    
    PRIMARY KEY (symbol, interval, time)
);

-- Convert to hypertable
SELECT create_hypertable('ohlc_bars', 'time', chunk_time_interval => INTERVAL '1 day');

-- Create indexes
CREATE INDEX idx_ohlc_symbol_interval ON ohlc_bars (symbol, interval, time DESC);

-- Continuous aggregates for OHLC (1-minute bars from ticks)
CREATE MATERIALIZED VIEW ohlc_1m
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 minute', time) AS bucket,
    symbol,
    tenant_id,
    first(mid_price, time) AS open,
    max(mid_price) AS high,
    min(mid_price) AS low,
    last(mid_price, time) AS close,
    sum(bid_size + ask_size) AS volume,
    sum(mid_price * (bid_size + ask_size)) / NULLIF(sum(bid_size + ask_size), 0) AS vwap,
    count(*) AS tick_count
FROM price_ticks
GROUP BY bucket, symbol, tenant_id;

-- Refresh policy for continuous aggregate
SELECT add_continuous_aggregate_policy('ohlc_1m',
    start_offset => INTERVAL '2 hours',
    end_offset => INTERVAL '1 minute',
    schedule_interval => INTERVAL '1 minute'
);

-- Retention policy: Keep raw ticks for 7 days
SELECT add_retention_policy('price_ticks', INTERVAL '7 days');

-- Keep OHLC bars for 90 days
SELECT add_retention_policy('ohlc_bars', INTERVAL '90 days');
```

### Historical Data Service

```typescript
// services/market-data-service/src/historical/historical-data.service.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { Pool } from 'pg';
import { logger, metrics } from '@orion/observability';
import { EventConsumerService } from '@orion/event-model';
import { getCurrentTenant } from '@orion/security';
import { BestPrice } from '../aggregation/aggregated-price.interface';

export interface TickData {
  time: Date;
  symbol: string;
  source: string;
  bidPrice: number;
  askPrice: number;
  midPrice: number;
  spread: number;
}

export interface OHLCBar {
  time: Date;
  symbol: string;
  interval: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
  vwap: number;
  tickCount: number;
}

export interface TimeRange {
  start: Date;
  end: Date;
}

type OHLCInterval = '1m' | '5m' | '15m' | '1h' | '1d';

@Injectable()
export class HistoricalDataService implements OnModuleInit {
  private insertBatch: BestPrice[] = [];
  private readonly batchSize = 1000;
  private readonly flushIntervalMs = 5000;

  constructor(
    private readonly pool: Pool,
    private readonly consumer: EventConsumerService,
  ) {}

  async onModuleInit() {
    // Subscribe to aggregated prices for storage
    this.consumer.registerHandler({
      eventTypes: ['market-data.best-price'],
      handle: async (event) => {
        await this.queueForInsert(event.payload as BestPrice);
      },
    });

    // Start batch flush interval
    setInterval(() => this.flushBatch(), this.flushIntervalMs);

    logger.info('Historical data service started');
  }

  private async queueForInsert(price: BestPrice): Promise<void> {
    this.insertBatch.push(price);

    if (this.insertBatch.length >= this.batchSize) {
      await this.flushBatch();
    }
  }

  private async flushBatch(): Promise<void> {
    if (this.insertBatch.length === 0) return;

    const batch = this.insertBatch;
    this.insertBatch = [];

    const startTime = Date.now();

    try {
      // Build bulk insert query
      const values = batch.map((p, i) => {
        const offset = i * 9;
        return `($${offset + 1}, $${offset + 2}, $${offset + 3}, $${offset + 4}, $${offset + 5}, $${offset + 6}, $${offset + 7}, $${offset + 8}, $${offset + 9})`;
      }).join(',');

      const params = batch.flatMap(p => [
        new Date(p.timestamp),
        p.symbol,
        p.bestBid.source,  // Use best bid source as representative
        p.bestBid.price,
        p.bestAsk.price,
        null,  // bid_size (aggregated, not stored)
        null,  // ask_size
        p.midPrice,
        p.spread,
      ]);

      await this.pool.query(
        `INSERT INTO price_ticks (time, symbol, source, bid_price, ask_price, bid_size, ask_size, mid_price, spread)
         VALUES ${values}`,
        params,
      );

      metrics.timing('historical.insert_batch', Date.now() - startTime);
      metrics.increment('historical.ticks_stored', { count: String(batch.length) });

    } catch (error) {
      logger.error('Failed to insert tick batch', { error, batchSize: batch.length });
      metrics.increment('historical.insert_errors');
    }
  }

  /**
   * Query tick data for a symbol
   */
  async getTicks(
    symbol: string,
    range: TimeRange,
    limit: number = 1000,
  ): Promise<TickData[]> {
    const tenant = getCurrentTenant();
    
    const result = await this.pool.query(
      `SELECT time, symbol, source, bid_price, ask_price, mid_price, spread
       FROM price_ticks
       WHERE symbol = $1 AND time >= $2 AND time <= $3
         AND tenant_id = $4
       ORDER BY time DESC
       LIMIT $5`,
      [symbol, range.start, range.end, tenant?.tenantId, limit],
    );

    return result.rows.map(row => ({
      time: row.time,
      symbol: row.symbol,
      source: row.source,
      bidPrice: parseFloat(row.bid_price),
      askPrice: parseFloat(row.ask_price),
      midPrice: parseFloat(row.mid_price),
      spread: parseFloat(row.spread),
    }));
  }

  /**
   * Query OHLC bars for a symbol
   */
  async getOHLC(
    symbol: string,
    interval: OHLCInterval,
    range: TimeRange,
    limit: number = 500,
  ): Promise<OHLCBar[]> {
    const tenant = getCurrentTenant();
    
    // Use continuous aggregate for 1m bars
    const table = interval === '1m' ? 'ohlc_1m' : 'ohlc_bars';
    const timeColumn = interval === '1m' ? 'bucket' : 'time';

    const result = await this.pool.query(
      `SELECT ${timeColumn} as time, symbol, open, high, low, close, volume, vwap, tick_count
       FROM ${table}
       WHERE symbol = $1 AND ${timeColumn} >= $2 AND ${timeColumn} <= $3
         AND tenant_id = $4
         ${interval !== '1m' ? "AND interval = $6" : ''}
       ORDER BY ${timeColumn} DESC
       LIMIT $5`,
      interval === '1m'
        ? [symbol, range.start, range.end, tenant?.tenantId, limit]
        : [symbol, range.start, range.end, tenant?.tenantId, limit, interval],
    );

    return result.rows.map(row => ({
      time: row.time,
      symbol: row.symbol,
      interval,
      open: parseFloat(row.open),
      high: parseFloat(row.high),
      low: parseFloat(row.low),
      close: parseFloat(row.close),
      volume: parseFloat(row.volume || '0'),
      vwap: parseFloat(row.vwap || '0'),
      tickCount: parseInt(row.tick_count, 10),
    }));
  }

  /**
   * Calculate OHLC bars for larger intervals (5m, 15m, 1h, 1d)
   */
  async calculateOHLC(
    symbol: string,
    interval: OHLCInterval,
    range: TimeRange,
  ): Promise<OHLCBar[]> {
    const tenant = getCurrentTenant();
    const intervalStr = this.intervalToPostgres(interval);

    const result = await this.pool.query(
      `SELECT 
         time_bucket($1, time) AS bucket,
         symbol,
         first(mid_price, time) AS open,
         max(mid_price) AS high,
         min(mid_price) AS low,
         last(mid_price, time) AS close,
         sum(bid_size + ask_size) AS volume,
         sum(mid_price * (bid_size + ask_size)) / NULLIF(sum(bid_size + ask_size), 0) AS vwap,
         count(*) AS tick_count
       FROM price_ticks
       WHERE symbol = $2 AND time >= $3 AND time <= $4
         AND tenant_id = $5
       GROUP BY bucket, symbol
       ORDER BY bucket DESC`,
      [intervalStr, symbol, range.start, range.end, tenant?.tenantId],
    );

    return result.rows.map(row => ({
      time: row.bucket,
      symbol: row.symbol,
      interval,
      open: parseFloat(row.open),
      high: parseFloat(row.high),
      low: parseFloat(row.low),
      close: parseFloat(row.close),
      volume: parseFloat(row.volume || '0'),
      vwap: parseFloat(row.vwap || '0'),
      tickCount: parseInt(row.tick_count, 10),
    }));
  }

  private intervalToPostgres(interval: OHLCInterval): string {
    switch (interval) {
      case '1m': return '1 minute';
      case '5m': return '5 minutes';
      case '15m': return '15 minutes';
      case '1h': return '1 hour';
      case '1d': return '1 day';
      default: return '1 minute';
    }
  }

  /**
   * Get statistics for a time period
   */
  async getStatistics(
    symbol: string,
    range: TimeRange,
  ): Promise<{
    minPrice: number;
    maxPrice: number;
    avgPrice: number;
    avgSpread: number;
    tickCount: number;
  }> {
    const tenant = getCurrentTenant();

    const result = await this.pool.query(
      `SELECT 
         min(mid_price) as min_price,
         max(mid_price) as max_price,
         avg(mid_price) as avg_price,
         avg(spread) as avg_spread,
         count(*) as tick_count
       FROM price_ticks
       WHERE symbol = $1 AND time >= $2 AND time <= $3
         AND tenant_id = $4`,
      [symbol, range.start, range.end, tenant?.tenantId],
    );

    const row = result.rows[0];
    return {
      minPrice: parseFloat(row.min_price || '0'),
      maxPrice: parseFloat(row.max_price || '0'),
      avgPrice: parseFloat(row.avg_price || '0'),
      avgSpread: parseFloat(row.avg_spread || '0'),
      tickCount: parseInt(row.tick_count, 10),
    };
  }
}
```

### Historical Data Controller

```typescript
// services/market-data-service/src/historical/historical-data.controller.ts
import { Controller, Get, Query, UseGuards, ParseIntPipe, DefaultValuePipe } from '@nestjs/common';
import { JwtAuthGuard } from '@orion/security';
import { HistoricalDataService, OHLCBar, TickData } from './historical-data.service';

@Controller('market-data/historical')
@UseGuards(JwtAuthGuard)
export class HistoricalDataController {
  constructor(private readonly historicalService: HistoricalDataService) {}

  @Get('ticks')
  async getTicks(
    @Query('symbol') symbol: string,
    @Query('start') start: string,
    @Query('end') end: string,
    @Query('limit', new DefaultValuePipe(1000), ParseIntPipe) limit: number,
  ): Promise<{ data: TickData[] }> {
    const data = await this.historicalService.getTicks(
      symbol,
      { start: new Date(start), end: new Date(end) },
      Math.min(limit, 10000),
    );

    return { data };
  }

  @Get('ohlc')
  async getOHLC(
    @Query('symbol') symbol: string,
    @Query('interval') interval: '1m' | '5m' | '15m' | '1h' | '1d',
    @Query('start') start: string,
    @Query('end') end: string,
    @Query('limit', new DefaultValuePipe(500), ParseIntPipe) limit: number,
  ): Promise<{ data: OHLCBar[] }> {
    const data = await this.historicalService.getOHLC(
      symbol,
      interval,
      { start: new Date(start), end: new Date(end) },
      Math.min(limit, 1000),
    );

    return { data };
  }

  @Get('statistics')
  async getStatistics(
    @Query('symbol') symbol: string,
    @Query('start') start: string,
    @Query('end') end: string,
  ) {
    return this.historicalService.getStatistics(
      symbol,
      { start: new Date(start), end: new Date(end) },
    );
  }
}
```

### S3 Archival Service

```typescript
// services/market-data-service/src/historical/archival.service.ts
import { Injectable } from '@nestjs/common';
import { S3Client, PutObjectCommand } from '@aws-sdk/client-s3';
import { Pool } from 'pg';
import { Cron, CronExpression } from '@nestjs/schedule';
import { logger, metrics } from '@orion/observability';
import * as parquet from 'parquetjs';

@Injectable()
export class ArchivalService {
  private readonly s3: S3Client;
  private readonly bucketName: string;

  constructor(private readonly pool: Pool) {
    this.s3 = new S3Client({});
    this.bucketName = process.env.MARKET_DATA_ARCHIVE_BUCKET || 'orion-market-data-archive';
  }

  @Cron(CronExpression.EVERY_DAY_AT_2AM)
  async archiveOldData() {
    const startTime = Date.now();
    
    try {
      // Archive data older than 7 days
      const cutoffDate = new Date();
      cutoffDate.setDate(cutoffDate.getDate() - 7);

      // Get distinct symbols for the day
      const symbols = await this.getSymbolsForDate(cutoffDate);

      for (const symbol of symbols) {
        await this.archiveSymbolDay(symbol, cutoffDate);
      }

      logger.info('Daily archival complete', {
        date: cutoffDate.toISOString(),
        symbolCount: symbols.length,
        duration: Date.now() - startTime,
      });

    } catch (error) {
      logger.error('Archival failed', { error });
      metrics.increment('archival.errors');
    }
  }

  private async getSymbolsForDate(date: Date): Promise<string[]> {
    const result = await this.pool.query(
      `SELECT DISTINCT symbol FROM price_ticks
       WHERE time >= $1 AND time < $2`,
      [date, new Date(date.getTime() + 86400000)],
    );

    return result.rows.map(r => r.symbol);
  }

  private async archiveSymbolDay(symbol: string, date: Date): Promise<void> {
    // Fetch data for the day
    const result = await this.pool.query(
      `SELECT * FROM price_ticks
       WHERE symbol = $1 AND time >= $2 AND time < $3
       ORDER BY time`,
      [symbol, date, new Date(date.getTime() + 86400000)],
    );

    if (result.rows.length === 0) return;

    // Create Parquet file
    const schema = new parquet.ParquetSchema({
      time: { type: 'TIMESTAMP_MILLIS' },
      symbol: { type: 'UTF8' },
      source: { type: 'UTF8' },
      bid_price: { type: 'DOUBLE' },
      ask_price: { type: 'DOUBLE' },
      mid_price: { type: 'DOUBLE' },
      spread: { type: 'DOUBLE' },
    });

    const fileName = `/tmp/ticks_${symbol.replace('/', '-')}_${date.toISOString().split('T')[0]}.parquet`;
    const writer = await parquet.ParquetWriter.openFile(schema, fileName);

    for (const row of result.rows) {
      await writer.appendRow({
        time: row.time.getTime(),
        symbol: row.symbol,
        source: row.source,
        bid_price: parseFloat(row.bid_price),
        ask_price: parseFloat(row.ask_price),
        mid_price: parseFloat(row.mid_price),
        spread: parseFloat(row.spread),
      });
    }

    await writer.close();

    // Upload to S3
    const key = `ticks/${date.getFullYear()}/${(date.getMonth() + 1).toString().padStart(2, '0')}/${date.getDate().toString().padStart(2, '0')}/${symbol.replace('/', '-')}.parquet`;

    await this.s3.send(new PutObjectCommand({
      Bucket: this.bucketName,
      Key: key,
      Body: require('fs').createReadStream(fileName),
      ContentType: 'application/octet-stream',
    }));

    logger.info('Archived symbol data', { symbol, date: date.toISOString(), key });
    metrics.increment('archival.files_uploaded');
  }
}
```

## Definition of Done

- [ ] TimescaleDB schema created
- [ ] Tick data insertion working
- [ ] OHLC continuous aggregates configured
- [ ] Query APIs functional
- [ ] Retention policies active
- [ ] S3 archival job running
- [ ] Tests pass

## Dependencies

- **US-06-03**: Best Price Aggregation Engine
- **US-01-08**: Database Configuration

## Test Cases

```typescript
describe('HistoricalDataService', () => {
  it('should store tick data in batches', async () => {
    for (let i = 0; i < 1000; i++) {
      await service.queueForInsert(mockPrice());
    }

    await service.flushBatch();

    const ticks = await service.getTicks('EUR/USD', {
      start: new Date(Date.now() - 60000),
      end: new Date(),
    });

    expect(ticks.length).toBeGreaterThan(0);
  });

  it('should calculate OHLC bars correctly', async () => {
    // Insert known prices
    const prices = [100, 105, 95, 102];  // O=100, H=105, L=95, C=102

    for (const price of prices) {
      await insertTick('TEST/USD', price);
    }

    const bars = await service.getOHLC('TEST/USD', '1m', {
      start: new Date(Date.now() - 60000),
      end: new Date(),
    });

    expect(bars[0].open).toBe(100);
    expect(bars[0].high).toBe(105);
    expect(bars[0].low).toBe(95);
    expect(bars[0].close).toBe(102);
  });
});
```
