# User Story: US-06-02 - Price Normalization Layer

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-06-02 |
| **Epic** | Epic 06 - Market Data System |
| **Title** | Price Normalization Layer |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |
| **PRD Reference** | FR-MD-01 |

## User Story

**As a** trading system developer  
**I want** prices normalized to a common format with unified symbols  
**So that** downstream systems work with consistent data

## Description

Build a normalization layer that maps LP-specific symbols to Orion canonical symbols, normalizes price precision, and enriches with instrument metadata.

## Acceptance Criteria

- [ ] Symbol mapping from LP to canonical format
- [ ] Price precision normalization per instrument
- [ ] Timestamp standardization to UTC
- [ ] Invalid price filtering (stale, crossed markets)
- [ ] Enrichment with instrument metadata
- [ ] Normalized prices published to separate topic

## Technical Details

### Symbol Mapping Configuration

```typescript
// services/market-data-service/src/normalization/symbol-mapping.ts
export interface SymbolMapping {
  canonical: string;           // Orion symbol (e.g., EUR/USD)
  assetClass: 'fx' | 'crypto' | 'equity' | 'fixed-income';
  baseCurrency: string;
  quoteCurrency: string;
  pricePrecision: number;      // Decimal places for price
  sizePrecision: number;       // Decimal places for size
  minimumSize: number;
  lotSize: number;
  sourceSymbols: {
    [source: string]: string;  // LP-specific symbols
  };
}

// Symbol mappings loaded from database
export const symbolMappings: SymbolMapping[] = [
  // FX
  {
    canonical: 'EUR/USD',
    assetClass: 'fx',
    baseCurrency: 'EUR',
    quoteCurrency: 'USD',
    pricePrecision: 5,
    sizePrecision: 0,
    minimumSize: 1000,
    lotSize: 1000000,
    sourceSymbols: {
      'lp-alpha': 'EUR/USD',
      'lp-beta': 'EURUSD',
      'refinitiv': 'EUR=',
    },
  },
  // Crypto
  {
    canonical: 'BTC/USD',
    assetClass: 'crypto',
    baseCurrency: 'BTC',
    quoteCurrency: 'USD',
    pricePrecision: 2,
    sizePrecision: 8,
    minimumSize: 0.0001,
    lotSize: 1,
    sourceSymbols: {
      'binance': 'BTCUSDT',     // Note: USDT pair mapped to USD
      'coinbase': 'BTC-USD',
      'kraken': 'XBT/USD',
    },
  },
  {
    canonical: 'ETH/USD',
    assetClass: 'crypto',
    baseCurrency: 'ETH',
    quoteCurrency: 'USD',
    pricePrecision: 2,
    sizePrecision: 8,
    minimumSize: 0.001,
    lotSize: 1,
    sourceSymbols: {
      'binance': 'ETHUSDT',
      'coinbase': 'ETH-USD',
      'kraken': 'ETH/USD',
    },
  },
];
```

### Normalized Price Interface

```typescript
// services/market-data-service/src/normalization/normalized-price.interface.ts
export interface NormalizedPrice {
  // Identification
  symbol: string;              // Canonical symbol
  source: string;              // LP identifier
  
  // Prices (normalized precision)
  bidPrice: number;
  bidSize: number;
  askPrice: number;
  askSize: number;
  midPrice: number;
  spread: number;
  spreadBps: number;           // Spread in basis points
  
  // Timestamps
  sourceTimestamp: number;     // Original LP timestamp (UTC ms)
  normalizedTimestamp: number; // Normalization time (UTC ms)
  
  // Metadata
  assetClass: string;
  baseCurrency: string;
  quoteCurrency: string;
  pricePrecision: number;
  
  // Tracking
  sequenceNumber: number;
  sourceSequence: number;
}
```

### Normalization Service

```typescript
// services/market-data-service/src/normalization/normalization.service.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { logger, metrics } from '@orion/observability';
import { EventBus, EventConsumerService, OnEvent } from '@orion/event-model';
import { RawPriceUpdate } from '../ingestion/feed-handler.interface';
import { NormalizedPrice } from './normalized-price.interface';
import { SymbolMappingService } from './symbol-mapping.service';
import { PriceValidatorService } from './price-validator.service';

@Injectable()
export class NormalizationService implements OnModuleInit {
  private sequenceNumber = 0;

  constructor(
    private readonly eventBus: EventBus,
    private readonly consumer: EventConsumerService,
    private readonly symbolMapping: SymbolMappingService,
    private readonly validator: PriceValidatorService,
  ) {}

  async onModuleInit() {
    // Subscribe to raw price events
    this.consumer.registerHandler({
      eventTypes: ['market-data.raw-price'],
      handle: async (event) => {
        await this.handleRawPrice(event.payload as RawPriceUpdate);
      },
    });
  }

  async handleRawPrice(raw: RawPriceUpdate): Promise<void> {
    const startTime = Date.now();

    try {
      // 1. Resolve canonical symbol
      const mapping = this.symbolMapping.resolveSymbol(raw.source, raw.sourceSymbol);
      
      if (!mapping) {
        metrics.increment('normalization.unmapped_symbol', {
          source: raw.source,
          symbol: raw.sourceSymbol,
        });
        return;
      }

      // 2. Parse and normalize prices
      const bidPrice = this.normalizePrice(raw.bidPrice, mapping.pricePrecision);
      const askPrice = this.normalizePrice(raw.askPrice, mapping.pricePrecision);
      const bidSize = this.normalizeSize(raw.bidSize, mapping.sizePrecision);
      const askSize = this.normalizeSize(raw.askSize, mapping.sizePrecision);

      // 3. Calculate derived values
      const midPrice = (bidPrice + askPrice) / 2;
      const spread = askPrice - bidPrice;
      const spreadBps = (spread / midPrice) * 10000;

      // 4. Build normalized price
      const normalized: NormalizedPrice = {
        symbol: mapping.canonical,
        source: raw.source,
        bidPrice,
        bidSize,
        askPrice,
        askSize,
        midPrice: this.roundToDecimal(midPrice, mapping.pricePrecision),
        spread: this.roundToDecimal(spread, mapping.pricePrecision),
        spreadBps: this.roundToDecimal(spreadBps, 2),
        sourceTimestamp: raw.sourceTimestamp,
        normalizedTimestamp: Date.now(),
        assetClass: mapping.assetClass,
        baseCurrency: mapping.baseCurrency,
        quoteCurrency: mapping.quoteCurrency,
        pricePrecision: mapping.pricePrecision,
        sequenceNumber: ++this.sequenceNumber,
        sourceSequence: raw.sequenceNumber,
      };

      // 5. Validate price
      const validation = this.validator.validate(normalized);
      
      if (!validation.valid) {
        logger.warn('Invalid price filtered', {
          symbol: normalized.symbol,
          source: normalized.source,
          reason: validation.reason,
        });
        metrics.increment('normalization.invalid_price', {
          reason: validation.reason || 'unknown',
        });
        return;
      }

      // 6. Publish normalized price
      await this.eventBus.publish({
        topic: 'orion.market-data.normalized',
        eventType: 'market-data.normalized-price',
        aggregateType: 'market-data',
        aggregateId: `${normalized.symbol}:${normalized.source}`,
        payload: normalized,
        metadata: {
          symbol: normalized.symbol,
          source: normalized.source,
          assetClass: normalized.assetClass,
        },
      });

      metrics.timing('normalization.latency', Date.now() - startTime);
      metrics.increment('normalization.success', { symbol: normalized.symbol });

    } catch (error) {
      logger.error('Normalization failed', { error, raw });
      metrics.increment('normalization.errors');
    }
  }

  private normalizePrice(value: string, precision: number): number {
    const num = parseFloat(value);
    return this.roundToDecimal(num, precision);
  }

  private normalizeSize(value: string, precision: number): number {
    const num = parseFloat(value);
    return this.roundToDecimal(num, precision);
  }

  private roundToDecimal(value: number, decimals: number): number {
    const factor = Math.pow(10, decimals);
    return Math.round(value * factor) / factor;
  }
}
```

### Symbol Mapping Service

```typescript
// services/market-data-service/src/normalization/symbol-mapping.service.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { Pool } from 'pg';
import { logger } from '@orion/observability';
import { SymbolMapping, symbolMappings } from './symbol-mapping';

@Injectable()
export class SymbolMappingService implements OnModuleInit {
  // Reverse lookup: source -> sourceSymbol -> mapping
  private mappingIndex = new Map<string, Map<string, SymbolMapping>>();
  // Canonical lookup
  private canonicalIndex = new Map<string, SymbolMapping>();

  constructor(private readonly pool: Pool) {}

  async onModuleInit() {
    await this.loadMappings();
  }

  private async loadMappings(): Promise<void> {
    // Load additional mappings from database
    const result = await this.pool.query(`
      SELECT 
        i.symbol as canonical,
        i.asset_class,
        i.base_currency,
        i.quote_currency,
        i.price_precision,
        i.size_precision,
        i.minimum_size,
        i.lot_size,
        sm.source,
        sm.source_symbol
      FROM instruments i
      LEFT JOIN symbol_mappings sm ON i.id = sm.instrument_id
      WHERE i.status = 'active'
    `);

    // Build index from static config + DB
    this.buildIndex([...symbolMappings, ...this.parseDbMappings(result.rows)]);
    
    logger.info('Symbol mappings loaded', { 
      count: this.canonicalIndex.size,
    });
  }

  private parseDbMappings(rows: any[]): SymbolMapping[] {
    // Group by canonical symbol
    const grouped = new Map<string, SymbolMapping>();
    
    for (const row of rows) {
      let mapping = grouped.get(row.canonical);
      
      if (!mapping) {
        mapping = {
          canonical: row.canonical,
          assetClass: row.asset_class,
          baseCurrency: row.base_currency,
          quoteCurrency: row.quote_currency,
          pricePrecision: row.price_precision,
          sizePrecision: row.size_precision,
          minimumSize: parseFloat(row.minimum_size),
          lotSize: parseFloat(row.lot_size),
          sourceSymbols: {},
        };
        grouped.set(row.canonical, mapping);
      }

      if (row.source && row.source_symbol) {
        mapping.sourceSymbols[row.source] = row.source_symbol;
      }
    }

    return Array.from(grouped.values());
  }

  private buildIndex(mappings: SymbolMapping[]): void {
    for (const mapping of mappings) {
      // Canonical index
      this.canonicalIndex.set(mapping.canonical, mapping);

      // Source -> symbol index
      for (const [source, sourceSymbol] of Object.entries(mapping.sourceSymbols)) {
        if (!this.mappingIndex.has(source)) {
          this.mappingIndex.set(source, new Map());
        }
        this.mappingIndex.get(source)!.set(sourceSymbol, mapping);
      }
    }
  }

  /**
   * Resolve LP symbol to canonical mapping
   */
  resolveSymbol(source: string, sourceSymbol: string): SymbolMapping | null {
    const sourceMap = this.mappingIndex.get(source);
    if (!sourceMap) {
      return null;
    }
    return sourceMap.get(sourceSymbol) || null;
  }

  /**
   * Get mapping by canonical symbol
   */
  getByCanonical(canonical: string): SymbolMapping | null {
    return this.canonicalIndex.get(canonical) || null;
  }

  /**
   * Get all canonical symbols
   */
  getAllSymbols(): string[] {
    return Array.from(this.canonicalIndex.keys());
  }

  /**
   * Reload mappings (e.g., on instrument change)
   */
  async reload(): Promise<void> {
    this.mappingIndex.clear();
    this.canonicalIndex.clear();
    await this.loadMappings();
  }
}
```

### Price Validator Service

```typescript
// services/market-data-service/src/normalization/price-validator.service.ts
import { Injectable } from '@nestjs/common';
import { logger, metrics } from '@orion/observability';
import { NormalizedPrice } from './normalized-price.interface';

export interface ValidationResult {
  valid: boolean;
  reason?: string;
}

interface PriceCache {
  lastPrice: number;
  lastTimestamp: number;
}

@Injectable()
export class PriceValidatorService {
  private priceCache = new Map<string, PriceCache>();
  
  // Configuration
  private readonly maxStalenessMs = 5000;         // 5 seconds
  private readonly maxSpreadBps = 500;            // 5% spread
  private readonly maxPriceChangePercent = 10;    // 10% price movement

  validate(price: NormalizedPrice): ValidationResult {
    // 1. Basic validation
    if (isNaN(price.bidPrice) || isNaN(price.askPrice)) {
      return { valid: false, reason: 'invalid_numbers' };
    }

    if (price.bidPrice <= 0 || price.askPrice <= 0) {
      return { valid: false, reason: 'non_positive_price' };
    }

    // 2. Crossed market check (bid > ask)
    if (price.bidPrice >= price.askPrice) {
      return { valid: false, reason: 'crossed_market' };
    }

    // 3. Excessive spread check
    if (price.spreadBps > this.maxSpreadBps) {
      return { valid: false, reason: 'excessive_spread' };
    }

    // 4. Staleness check
    const age = Date.now() - price.sourceTimestamp;
    if (age > this.maxStalenessMs) {
      return { valid: false, reason: 'stale_price' };
    }

    // 5. Price movement check (circuit breaker)
    const cacheKey = `${price.symbol}:${price.source}`;
    const cached = this.priceCache.get(cacheKey);

    if (cached) {
      const priceChange = Math.abs(price.midPrice - cached.lastPrice) / cached.lastPrice * 100;
      
      if (priceChange > this.maxPriceChangePercent) {
        logger.warn('Large price movement detected', {
          symbol: price.symbol,
          source: price.source,
          previousPrice: cached.lastPrice,
          newPrice: price.midPrice,
          changePercent: priceChange,
        });
        
        // Could be legitimate, but log for monitoring
        metrics.increment('validation.large_price_move', {
          symbol: price.symbol,
        });
      }
    }

    // Update cache
    this.priceCache.set(cacheKey, {
      lastPrice: price.midPrice,
      lastTimestamp: price.normalizedTimestamp,
    });

    return { valid: true };
  }

  /**
   * Clear price cache (e.g., for testing)
   */
  clearCache(): void {
    this.priceCache.clear();
  }
}
```

### Database Schema for Symbol Mappings

```sql
-- migrations/20240117_create_symbol_mappings.sql
CREATE TABLE symbol_mappings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    instrument_id UUID NOT NULL REFERENCES instruments(id) ON DELETE CASCADE,
    source VARCHAR(50) NOT NULL,           -- LP identifier
    source_symbol VARCHAR(50) NOT NULL,    -- LP-specific symbol
    
    -- Constraints
    UNIQUE (source, source_symbol),
    UNIQUE (instrument_id, source)
);

CREATE INDEX idx_symbol_mappings_source 
    ON symbol_mappings(source, source_symbol);

-- Example data
INSERT INTO symbol_mappings (instrument_id, source, source_symbol)
SELECT id, 'binance', 'BTCUSDT' FROM instruments WHERE symbol = 'BTC/USD';
```

## Definition of Done

- [ ] Symbol mapping service implemented
- [ ] Price normalization logic complete
- [ ] Validation rules active
- [ ] Invalid prices filtered with logging
- [ ] Normalized prices published to Kafka
- [ ] Metrics exposed
- [ ] Tests pass

## Dependencies

- **US-06-01**: Market Data Ingestion Service
- **US-04-01**: Instrument Management

## Test Cases

```typescript
describe('NormalizationService', () => {
  it('should normalize raw price correctly', async () => {
    const raw: RawPriceUpdate = {
      source: 'binance',
      sourceSymbol: 'BTCUSDT',
      bidPrice: '50000.123456',
      askPrice: '50001.987654',
      bidSize: '1.5',
      askSize: '2.0',
      sourceTimestamp: Date.now(),
      sequenceNumber: 1,
    };

    const normalized = await service.handleRawPrice(raw);

    expect(publishSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        payload: expect.objectContaining({
          symbol: 'BTC/USD',
          bidPrice: 50000.12,  // Rounded to 2 decimals
          askPrice: 50001.99,
        }),
      }),
    );
  });

  it('should filter crossed markets', async () => {
    const raw: RawPriceUpdate = {
      source: 'test',
      sourceSymbol: 'TEST',
      bidPrice: '100.00',
      askPrice: '99.00',  // Crossed!
      bidSize: '1',
      askSize: '1',
      sourceTimestamp: Date.now(),
      sequenceNumber: 1,
    };

    await service.handleRawPrice(raw);

    expect(publishSpy).not.toHaveBeenCalled();
  });
});
```
