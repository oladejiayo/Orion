# User Story: US-08-04 - Trade Enrichment Pipeline

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-08-04 |
| **Epic** | Epic 08 - Trade Execution |
| **Title** | Trade Enrichment Pipeline |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |
| **PRD Reference** | FR-Trade-04, NFR-Performance-02 |

## User Story

**As a** platform operator  
**I want** automatic trade enrichment  
**So that** trades have complete reference data, market data, and calculated fees

## Description

Implement trade enrichment pipeline that adds reference data (instrument details, counterparty info), market data (spot rates, benchmark rates), and calculates applicable fees (commission, spread, regulatory).

## Acceptance Criteria

- [ ] Instrument enrichment (full details, identifiers)
- [ ] Counterparty enrichment (names, LEI, accounts)
- [ ] Market data enrichment (spot rates, benchmarks)
- [ ] Fee calculation pipeline
- [ ] Settlement instruction enrichment
- [ ] Parallel enrichment for performance
- [ ] Enrichment audit trail

## Technical Details

### Enrichment Pipeline Interface

```typescript
// services/trade-service/src/enrichment/enrichment-pipeline.interface.ts

export interface EnrichmentContext {
  trade: TradeEntity;
  originalTrade: TradeEntity; // Pre-enrichment snapshot
  tenantId: string;
  metadata: Record<string, any>;
}

export interface EnrichmentResult {
  enricherName: string;
  success: boolean;
  fieldsEnriched: string[];
  errors?: string[];
  duration: number;
}

export interface Enricher {
  name: string;
  priority: number;
  required: boolean;
  
  enrich(context: EnrichmentContext): Promise<EnrichmentResult>;
}

export interface EnrichmentPipelineResult {
  success: boolean;
  trade: TradeEntity;
  results: EnrichmentResult[];
  totalDuration: number;
}
```

### Instrument Enricher

```typescript
// services/trade-service/src/enrichment/enrichers/instrument.enricher.ts
import { Injectable } from '@nestjs/common';
import { Enricher, EnrichmentContext, EnrichmentResult } from '../enrichment-pipeline.interface';
import { InstrumentService } from '@orion/reference-data';

@Injectable()
export class InstrumentEnricher implements Enricher {
  name = 'instrument-enricher';
  priority = 10;
  required = true;

  constructor(private readonly instrumentService: InstrumentService) {}

  async enrich(context: EnrichmentContext): Promise<EnrichmentResult> {
    const startTime = Date.now();
    const fieldsEnriched: string[] = [];
    const errors: string[] = [];

    try {
      const instrument = await this.instrumentService.getById(context.trade.instrumentId);
      
      if (!instrument) {
        return {
          enricherName: this.name,
          success: false,
          fieldsEnriched: [],
          errors: [`Instrument ${context.trade.instrumentId} not found`],
          duration: Date.now() - startTime,
        };
      }

      // Enrich instrument details
      context.trade.symbol = instrument.symbol;
      fieldsEnriched.push('symbol');

      context.trade.assetClass = instrument.assetClass;
      fieldsEnriched.push('assetClass');

      context.trade.baseCurrency = instrument.baseCurrency;
      fieldsEnriched.push('baseCurrency');

      context.trade.quoteCurrency = instrument.quoteCurrency;
      fieldsEnriched.push('quoteCurrency');

      // Store instrument identifiers
      context.trade.metadata = {
        ...context.trade.metadata,
        instrument: {
          isin: instrument.isin,
          cusip: instrument.cusip,
          sedol: instrument.sedol,
          ric: instrument.ric,
          bbgTicker: instrument.bbgTicker,
          tickSize: instrument.tickSize,
          lotSize: instrument.lotSize,
          settlementCycle: instrument.settlementCycle,
        },
      };
      fieldsEnriched.push('metadata.instrument');

      // Calculate settlement date if not provided
      if (!context.trade.settlementDate && instrument.settlementCycle) {
        context.trade.settlementDate = await this.calculateSettlementDate(
          context.trade.tradeDate,
          instrument.settlementCycle,
          instrument.settlementCalendars || ['US'],
        );
        fieldsEnriched.push('settlementDate');
      }

      return {
        enricherName: this.name,
        success: true,
        fieldsEnriched,
        duration: Date.now() - startTime,
      };
    } catch (error) {
      return {
        enricherName: this.name,
        success: false,
        fieldsEnriched,
        errors: [error.message],
        duration: Date.now() - startTime,
      };
    }
  }

  private async calculateSettlementDate(
    tradeDate: Date,
    cycle: string,
    calendars: string[],
  ): Promise<Date> {
    // T+N settlement calculation
    const days = parseInt(cycle.replace('T+', ''), 10) || 2;
    const settlement = new Date(tradeDate);
    settlement.setDate(settlement.getDate() + days);
    return settlement;
  }
}
```

### Counterparty Enricher

```typescript
// services/trade-service/src/enrichment/enrichers/counterparty.enricher.ts
import { Injectable } from '@nestjs/common';
import { Enricher, EnrichmentContext, EnrichmentResult } from '../enrichment-pipeline.interface';
import { CounterpartyService, SettlementInstructionService } from '@orion/reference-data';

@Injectable()
export class CounterpartyEnricher implements Enricher {
  name = 'counterparty-enricher';
  priority = 20;
  required = true;

  constructor(
    private readonly counterpartyService: CounterpartyService,
    private readonly ssiService: SettlementInstructionService,
  ) {}

  async enrich(context: EnrichmentContext): Promise<EnrichmentResult> {
    const startTime = Date.now();
    const fieldsEnriched: string[] = [];
    const errors: string[] = [];

    try {
      // Enrich client details
      const client = await this.counterpartyService.getById(context.trade.clientId);
      if (client) {
        context.trade.clientName = client.name;
        fieldsEnriched.push('clientName');

        context.trade.metadata = {
          ...context.trade.metadata,
          client: {
            lei: client.lei,
            shortName: client.shortName,
            classification: client.classification,
            region: client.region,
            segment: client.segment,
          },
        };
        fieldsEnriched.push('metadata.client');
      } else {
        errors.push(`Client ${context.trade.clientId} not found`);
      }

      // Enrich counterparty details
      const counterparty = await this.counterpartyService.getById(context.trade.counterpartyId);
      if (counterparty) {
        context.trade.counterpartyName = counterparty.name;
        fieldsEnriched.push('counterpartyName');

        context.trade.metadata = {
          ...context.trade.metadata,
          counterparty: {
            lei: counterparty.lei,
            shortName: counterparty.shortName,
            bicCode: counterparty.bicCode,
          },
        };
        fieldsEnriched.push('metadata.counterparty');
      } else {
        errors.push(`Counterparty ${context.trade.counterpartyId} not found`);
      }

      // Get settlement instructions
      const ssi = await this.ssiService.getDefault({
        counterpartyId: context.trade.counterpartyId,
        currency: context.trade.currency,
        assetClass: context.trade.assetClass,
      });

      if (ssi) {
        context.trade.settlementInstructions = {
          ourAccount: ssi.ourAccount,
          theirAccount: ssi.theirAccount,
          correspondentBank: ssi.correspondentBank,
          intermediaryBank: ssi.intermediaryBank,
          paymentReference: ssi.paymentReference,
        };
        fieldsEnriched.push('settlementInstructions');
      }

      return {
        enricherName: this.name,
        success: errors.length === 0,
        fieldsEnriched,
        errors: errors.length > 0 ? errors : undefined,
        duration: Date.now() - startTime,
      };
    } catch (error) {
      return {
        enricherName: this.name,
        success: false,
        fieldsEnriched,
        errors: [error.message],
        duration: Date.now() - startTime,
      };
    }
  }
}
```

### Market Data Enricher

```typescript
// services/trade-service/src/enrichment/enrichers/market-data.enricher.ts
import { Injectable } from '@nestjs/common';
import { Enricher, EnrichmentContext, EnrichmentResult } from '../enrichment-pipeline.interface';
import { MarketDataService } from '@orion/market-data';

@Injectable()
export class MarketDataEnricher implements Enricher {
  name = 'market-data-enricher';
  priority = 30;
  required = false;

  constructor(private readonly marketDataService: MarketDataService) {}

  async enrich(context: EnrichmentContext): Promise<EnrichmentResult> {
    const startTime = Date.now();
    const fieldsEnriched: string[] = [];

    try {
      const { trade } = context;

      // Get spot rate at execution time
      const spotRate = await this.marketDataService.getSpotRate({
        symbol: trade.symbol,
        timestamp: trade.executionTime || trade.tradeDate,
      });

      if (spotRate) {
        context.trade.metadata = {
          ...context.trade.metadata,
          marketData: {
            spotMid: spotRate.mid,
            spotBid: spotRate.bid,
            spotAsk: spotRate.ask,
            spotSpread: spotRate.ask - spotRate.bid,
            source: spotRate.source,
            timestamp: spotRate.timestamp,
          },
        };
        fieldsEnriched.push('metadata.marketData');

        // Calculate spread to mid
        const mid = spotRate.mid;
        const tradePrice = Number(trade.price);
        const spreadBps = ((tradePrice - mid) / mid) * 10000;
        
        context.trade.metadata.marketData.spreadToMidBps = Math.round(spreadBps * 100) / 100;
        fieldsEnriched.push('metadata.marketData.spreadToMidBps');
      }

      // Get benchmark rates if applicable
      if (trade.assetClass === 'fx') {
        const benchmark = await this.getBenchmarkRate(trade.symbol, trade.tradeDate);
        if (benchmark) {
          context.trade.metadata = {
            ...context.trade.metadata,
            benchmark: {
              fixingRate: benchmark.rate,
              fixingTime: benchmark.fixingTime,
              source: benchmark.source,
            },
          };
          fieldsEnriched.push('metadata.benchmark');
        }
      }

      return {
        enricherName: this.name,
        success: true,
        fieldsEnriched,
        duration: Date.now() - startTime,
      };
    } catch (error) {
      return {
        enricherName: this.name,
        success: true, // Not required, so still successful
        fieldsEnriched,
        errors: [error.message],
        duration: Date.now() - startTime,
      };
    }
  }

  private async getBenchmarkRate(symbol: string, date: Date): Promise<any> {
    return this.marketDataService.getBenchmark({
      symbol,
      date,
      type: 'WMR', // WM/Reuters fixing
    });
  }
}
```

### Fee Calculation Enricher

```typescript
// services/trade-service/src/enrichment/enrichers/fee-calculation.enricher.ts
import { Injectable } from '@nestjs/common';
import { Enricher, EnrichmentContext, EnrichmentResult } from '../enrichment-pipeline.interface';
import { FeeScheduleService } from '@orion/billing';
import { TradeFeeEntity } from '../../entities/trade-fee.entity';

@Injectable()
export class FeeCalculationEnricher implements Enricher {
  name = 'fee-calculation-enricher';
  priority = 50;
  required = true;

  constructor(private readonly feeScheduleService: FeeScheduleService) {}

  async enrich(context: EnrichmentContext): Promise<EnrichmentResult> {
    const startTime = Date.now();
    const fieldsEnriched: string[] = [];
    const errors: string[] = [];

    try {
      const { trade } = context;
      const fees: TradeFeeEntity[] = [];

      // Get client's fee schedule
      const feeSchedule = await this.feeScheduleService.getForClient({
        clientId: trade.clientId,
        assetClass: trade.assetClass,
        instrumentType: trade.symbol,
      });

      // Commission calculation
      const commission = this.calculateCommission(trade, feeSchedule);
      if (commission) {
        fees.push(commission);
      }

      // Spread fee
      const spreadFee = this.calculateSpreadFee(trade, feeSchedule);
      if (spreadFee) {
        fees.push(spreadFee);
      }

      // Platform fee
      const platformFee = this.calculatePlatformFee(trade, feeSchedule);
      if (platformFee) {
        fees.push(platformFee);
      }

      // Regulatory fees
      const regulatoryFees = await this.calculateRegulatoryFees(trade);
      fees.push(...regulatoryFees);

      // Set fees on trade
      context.trade.fees = fees;
      fieldsEnriched.push('fees');

      // Calculate total fees
      const totalFees = fees.reduce((sum, fee) => sum + Number(fee.amount), 0);
      context.trade.totalFees = totalFees;
      fieldsEnriched.push('totalFees');

      // Calculate all-in price
      const notional = Number(trade.notionalAmount);
      const feesBps = (totalFees / notional) * 10000;
      context.trade.metadata = {
        ...context.trade.metadata,
        feeAnalysis: {
          totalFees,
          totalFeesBps: Math.round(feesBps * 100) / 100,
          feeBreakdown: fees.map(f => ({
            type: f.feeType,
            amount: f.amount,
            currency: f.currency,
          })),
        },
      };
      fieldsEnriched.push('metadata.feeAnalysis');

      return {
        enricherName: this.name,
        success: true,
        fieldsEnriched,
        duration: Date.now() - startTime,
      };
    } catch (error) {
      return {
        enricherName: this.name,
        success: false,
        fieldsEnriched,
        errors: [error.message],
        duration: Date.now() - startTime,
      };
    }
  }

  private calculateCommission(trade: any, feeSchedule: any): TradeFeeEntity | null {
    if (!feeSchedule?.commissionRate) return null;

    const notional = Number(trade.notionalAmount);
    const rate = feeSchedule.commissionRate; // In bps
    const amount = (notional * rate) / 10000;

    if (amount < (feeSchedule.minimumCommission || 0)) {
      return {
        feeType: 'commission',
        amount: feeSchedule.minimumCommission,
        currency: trade.currency,
        description: 'Minimum commission applied',
      } as TradeFeeEntity;
    }

    if (feeSchedule.maximumCommission && amount > feeSchedule.maximumCommission) {
      return {
        feeType: 'commission',
        amount: feeSchedule.maximumCommission,
        currency: trade.currency,
        description: 'Maximum commission cap applied',
      } as TradeFeeEntity;
    }

    return {
      feeType: 'commission',
      amount,
      currency: trade.currency,
      description: `Commission at ${rate} bps`,
    } as TradeFeeEntity;
  }

  private calculateSpreadFee(trade: any, feeSchedule: any): TradeFeeEntity | null {
    if (!feeSchedule?.spreadMarkup) return null;

    const notional = Number(trade.notionalAmount);
    const markup = feeSchedule.spreadMarkup; // In bps
    const amount = (notional * markup) / 10000;

    return {
      feeType: 'spread',
      amount,
      currency: trade.currency,
      description: `Spread markup at ${markup} bps`,
    } as TradeFeeEntity;
  }

  private calculatePlatformFee(trade: any, feeSchedule: any): TradeFeeEntity | null {
    if (!feeSchedule?.platformFee) return null;

    return {
      feeType: 'platform',
      amount: feeSchedule.platformFee,
      currency: trade.currency,
      description: 'Platform fee',
    } as TradeFeeEntity;
  }

  private async calculateRegulatoryFees(trade: any): Promise<TradeFeeEntity[]> {
    const fees: TradeFeeEntity[] = [];
    const notional = Number(trade.notionalAmount);

    // SEC fee for US equities
    if (trade.assetClass === 'equity' && trade.metadata?.instrument?.region === 'US') {
      if (trade.side === 'sell') {
        const secFeeRate = 0.0000278; // $27.80 per million
        const secFee = notional * secFeeRate;
        fees.push({
          feeType: 'regulatory',
          amount: Math.max(0.01, secFee),
          currency: 'USD',
          description: 'SEC Transaction Fee',
        } as TradeFeeEntity);
      }
    }

    return fees;
  }
}
```

### Enrichment Pipeline Service

```typescript
// services/trade-service/src/enrichment/enrichment-pipeline.service.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { logger, metrics } from '@orion/observability';
import { Enricher, EnrichmentContext, EnrichmentPipelineResult } from './enrichment-pipeline.interface';
import { InstrumentEnricher } from './enrichers/instrument.enricher';
import { CounterpartyEnricher } from './enrichers/counterparty.enricher';
import { MarketDataEnricher } from './enrichers/market-data.enricher';
import { FeeCalculationEnricher } from './enrichers/fee-calculation.enricher';
import { TradeEntity } from '../entities/trade.entity';
import { getCurrentTenant } from '@orion/security';

@Injectable()
export class EnrichmentPipelineService implements OnModuleInit {
  private enrichers: Enricher[] = [];

  constructor(
    private readonly instrumentEnricher: InstrumentEnricher,
    private readonly counterpartyEnricher: CounterpartyEnricher,
    private readonly marketDataEnricher: MarketDataEnricher,
    private readonly feeCalculationEnricher: FeeCalculationEnricher,
  ) {}

  onModuleInit() {
    this.enrichers = [
      this.instrumentEnricher,
      this.counterpartyEnricher,
      this.marketDataEnricher,
      this.feeCalculationEnricher,
    ].sort((a, b) => a.priority - b.priority);

    logger.info('Enrichment pipeline initialized', {
      enricherCount: this.enrichers.length,
      enrichers: this.enrichers.map(e => e.name),
    });
  }

  async enrichTrade(trade: TradeEntity): Promise<EnrichmentPipelineResult> {
    const startTime = Date.now();
    const originalTrade = { ...trade };
    const results: any[] = [];

    const context: EnrichmentContext = {
      trade,
      originalTrade,
      tenantId: getCurrentTenant()?.tenantId || '',
      metadata: {},
    };

    // Group enrichers by dependency (independent ones can run in parallel)
    const independentEnrichers = this.enrichers.filter(e => e.priority <= 30);
    const dependentEnrichers = this.enrichers.filter(e => e.priority > 30);

    // Run independent enrichers in parallel
    const parallelResults = await Promise.all(
      independentEnrichers.map(enricher => this.runEnricher(enricher, context)),
    );
    results.push(...parallelResults);

    // Check if any required enricher failed
    const failedRequired = parallelResults.find(
      r => !r.success && independentEnrichers.find(e => e.name === r.enricherName)?.required,
    );

    if (failedRequired) {
      return {
        success: false,
        trade: context.trade,
        results,
        totalDuration: Date.now() - startTime,
      };
    }

    // Run dependent enrichers sequentially
    for (const enricher of dependentEnrichers) {
      const result = await this.runEnricher(enricher, context);
      results.push(result);

      if (!result.success && enricher.required) {
        return {
          success: false,
          trade: context.trade,
          results,
          totalDuration: Date.now() - startTime,
        };
      }
    }

    // Mark enrichment complete
    context.trade.enrichedAt = new Date();
    context.trade.enrichmentVersion = '1.0';

    const totalDuration = Date.now() - startTime;
    metrics.timing('trade.enrichment.total', totalDuration);
    metrics.increment('trade.enrichment.complete');

    return {
      success: true,
      trade: context.trade,
      results,
      totalDuration,
    };
  }

  private async runEnricher(
    enricher: Enricher,
    context: EnrichmentContext,
  ): Promise<any> {
    const startTime = Date.now();

    try {
      const result = await enricher.enrich(context);
      
      metrics.timing(`trade.enrichment.${enricher.name}`, result.duration);
      
      logger.debug('Enricher completed', {
        enricher: enricher.name,
        success: result.success,
        fieldsEnriched: result.fieldsEnriched,
        duration: result.duration,
      });

      return result;
    } catch (error) {
      logger.error(`Enricher ${enricher.name} threw error`, { error });

      return {
        enricherName: enricher.name,
        success: false,
        fieldsEnriched: [],
        errors: [error.message],
        duration: Date.now() - startTime,
      };
    }
  }
}
```

### Enrichment Module

```typescript
// services/trade-service/src/enrichment/enrichment.module.ts
import { Module } from '@nestjs/common';
import { EnrichmentPipelineService } from './enrichment-pipeline.service';
import { InstrumentEnricher } from './enrichers/instrument.enricher';
import { CounterpartyEnricher } from './enrichers/counterparty.enricher';
import { MarketDataEnricher } from './enrichers/market-data.enricher';
import { FeeCalculationEnricher } from './enrichers/fee-calculation.enricher';
import { ReferenceDataModule } from '@orion/reference-data';
import { MarketDataModule } from '@orion/market-data';
import { BillingModule } from '@orion/billing';

@Module({
  imports: [
    ReferenceDataModule,
    MarketDataModule,
    BillingModule,
  ],
  providers: [
    EnrichmentPipelineService,
    InstrumentEnricher,
    CounterpartyEnricher,
    MarketDataEnricher,
    FeeCalculationEnricher,
  ],
  exports: [EnrichmentPipelineService],
})
export class EnrichmentModule {}
```

## Definition of Done

- [ ] Instrument enricher
- [ ] Counterparty enricher
- [ ] Market data enricher
- [ ] Fee calculation enricher
- [ ] Pipeline orchestration
- [ ] Parallel execution support
- [ ] Error handling and logging

## Dependencies

- **US-04-01**: Instrument Service
- **US-04-02**: Counterparty Service
- **US-05-01**: Market Data Service

## Test Cases

```typescript
describe('EnrichmentPipelineService', () => {
  it('should enrich trade with instrument details', async () => {
    const trade = createBaseTrade({ instrumentId: 'eurusd' });
    
    const result = await enrichmentService.enrichTrade(trade);

    expect(result.success).toBe(true);
    expect(result.trade.symbol).toBe('EURUSD');
    expect(result.trade.assetClass).toBe('fx');
    expect(result.trade.metadata.instrument).toBeDefined();
  });

  it('should calculate fees correctly', async () => {
    const trade = createBaseTrade({ notionalAmount: 1000000 });
    
    const result = await enrichmentService.enrichTrade(trade);

    expect(result.success).toBe(true);
    expect(result.trade.fees.length).toBeGreaterThan(0);
    expect(result.trade.totalFees).toBeGreaterThan(0);
  });

  it('should run independent enrichers in parallel', async () => {
    const trade = createBaseTrade();
    const startTime = Date.now();
    
    await enrichmentService.enrichTrade(trade);
    
    // If parallel, should be faster than sequential
    expect(Date.now() - startTime).toBeLessThan(500);
  });
});
```
