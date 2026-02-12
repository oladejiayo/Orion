# US-12-02: ETL Pipeline

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-12-02 |
| **Epic** | Epic 12: Analytics & Data Products |
| **Title** | ETL Pipeline |
| **Priority** | High |
| **Story Points** | 13 |
| **Status** | Ready for Development |

## User Story

**As a** data engineer  
**I want** a robust ETL pipeline that transforms operational data into analytical facts  
**So that** the data warehouse stays synchronized with real-time platform activity

## Acceptance Criteria

### AC1: Change Data Capture (CDC)
- **Given** transactions occur in operational databases
- **When** the CDC process runs
- **Then** changes are captured:
  - Debezium captures PostgreSQL WAL changes
  - Changes published to Kafka CDC topics
  - At-least-once delivery guarantee
  - Schema evolution handled gracefully

### AC2: Real-Time Fact Loading
- **Given** trade, quote, and order events are published
- **When** the streaming ETL processes events
- **Then** facts are loaded:
  - Sub-5 minute latency from event to warehouse
  - Idempotent processing (deduplication)
  - Foreign key resolution to dimensions
  - Calculated fields computed (notional, spreads)

### AC3: Dimension Synchronization
- **Given** reference data changes in operational systems
- **When** the dimension sync process runs
- **Then** dimensions are updated:
  - SCD Type 2 for slowly changing attributes
  - SCD Type 1 for non-historical attributes
  - New records created for new entities
  - Surrogate keys maintained

### AC4: Aggregation Jobs
- **Given** fact data exists in the warehouse
- **When** aggregation jobs run (scheduled)
- **Then** aggregates are computed:
  - Daily summaries computed at end of day
  - Running aggregates updated incrementally
  - Pre-computed rollups for common queries
  - Aggregate consistency validated

### AC5: Data Quality Checks
- **Given** ETL pipelines process data
- **When** data quality rules are evaluated
- **Then** anomalies are detected and handled:
  - Null checks on required fields
  - Referential integrity validation
  - Business rule validation (negative quantities)
  - Alerts raised for quality issues

## Technical Specification

### CDC Configuration

```typescript
// src/analytics/etl/cdc/debezium.config.ts
export interface DebeziumConnectorConfig {
  name: string;
  config: {
    'connector.class': string;
    'database.hostname': string;
    'database.port': string;
    'database.user': string;
    'database.password': string;
    'database.dbname': string;
    'database.server.name': string;
    'table.include.list': string;
    'plugin.name': string;
    'publication.name': string;
    'slot.name': string;
    'transforms': string;
    'transforms.route.type': string;
    'transforms.route.topic.replacement': string;
    'key.converter': string;
    'value.converter': string;
    'key.converter.schemas.enable': string;
    'value.converter.schemas.enable': string;
  };
}

export const createDebeziumConfig = (env: string): DebeziumConnectorConfig => ({
  name: `orion-cdc-${env}`,
  config: {
    'connector.class': 'io.debezium.connector.postgresql.PostgresConnector',
    'database.hostname': process.env.DB_HOST,
    'database.port': process.env.DB_PORT || '5432',
    'database.user': process.env.DB_CDC_USER,
    'database.password': process.env.DB_CDC_PASSWORD,
    'database.dbname': process.env.DB_NAME,
    'database.server.name': `orion-${env}`,
    'table.include.list': [
      'public.trades',
      'public.orders',
      'public.positions',
      'public.clients',
      'public.instruments',
      'public.liquidity_providers',
    ].join(','),
    'plugin.name': 'pgoutput',
    'publication.name': 'orion_cdc_publication',
    'slot.name': 'orion_cdc_slot',
    'transforms': 'route',
    'transforms.route.type': 'org.apache.kafka.connect.transforms.RegexRouter',
    'transforms.route.topic.replacement': 'cdc.$1',
    'key.converter': 'org.apache.kafka.connect.json.JsonConverter',
    'value.converter': 'org.apache.kafka.connect.json.JsonConverter',
    'key.converter.schemas.enable': 'true',
    'value.converter.schemas.enable': 'true',
  },
});
```

### Streaming ETL Service

```typescript
// src/analytics/etl/services/streaming-etl.service.ts
import { Injectable, Logger, OnModuleInit, OnModuleDestroy } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, DataSource } from 'typeorm';
import { Kafka, Consumer, EachMessagePayload } from 'kafkajs';
import { FactTradeEntity } from '../../entities/fact-trade.entity';
import { FactQuoteEntity } from '../../entities/fact-quote.entity';
import { FactOrderEntity } from '../../entities/fact-order.entity';
import { DimensionLookupService } from './dimension-lookup.service';
import { DataQualityService } from './data-quality.service';
import { ETLMetricsService } from './etl-metrics.service';

interface CDCPayload {
  before: Record<string, any> | null;
  after: Record<string, any> | null;
  source: {
    version: string;
    connector: string;
    name: string;
    ts_ms: number;
    snapshot: string;
    db: string;
    schema: string;
    table: string;
    txId: number;
    lsn: number;
  };
  op: 'c' | 'u' | 'd' | 'r'; // create, update, delete, read (snapshot)
  ts_ms: number;
}

@Injectable()
export class StreamingEtlService implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(StreamingEtlService.name);
  private kafka: Kafka;
  private consumer: Consumer;
  private isRunning = false;

  private readonly topicHandlers = new Map<string, (payload: CDCPayload) => Promise<void>>();

  constructor(
    private readonly dataSource: DataSource,
    @InjectRepository(FactTradeEntity)
    private readonly factTradeRepo: Repository<FactTradeEntity>,
    @InjectRepository(FactQuoteEntity)
    private readonly factQuoteRepo: Repository<FactQuoteEntity>,
    @InjectRepository(FactOrderEntity)
    private readonly factOrderRepo: Repository<FactOrderEntity>,
    private readonly dimensionLookup: DimensionLookupService,
    private readonly dataQuality: DataQualityService,
    private readonly metrics: ETLMetricsService,
  ) {
    this.kafka = new Kafka({
      clientId: 'analytics-etl',
      brokers: process.env.KAFKA_BROKERS?.split(',') || ['localhost:9092'],
    });

    this.consumer = this.kafka.consumer({
      groupId: 'analytics-etl-group',
      sessionTimeout: 30000,
      heartbeatInterval: 3000,
    });

    // Register topic handlers
    this.topicHandlers.set('cdc.public.trades', this.handleTradeChange.bind(this));
    this.topicHandlers.set('cdc.public.orders', this.handleOrderChange.bind(this));
    this.topicHandlers.set('trade.executed', this.handleTradeEvent.bind(this));
    this.topicHandlers.set('quote.received', this.handleQuoteEvent.bind(this));
  }

  async onModuleInit(): Promise<void> {
    await this.start();
  }

  async onModuleDestroy(): Promise<void> {
    await this.stop();
  }

  async start(): Promise<void> {
    if (this.isRunning) return;

    this.logger.log('Starting streaming ETL service');

    await this.consumer.connect();
    await this.consumer.subscribe({
      topics: Array.from(this.topicHandlers.keys()),
      fromBeginning: false,
    });

    this.isRunning = true;

    await this.consumer.run({
      eachMessage: async (payload: EachMessagePayload) => {
        await this.processMessage(payload);
      },
    });

    this.logger.log('Streaming ETL service started');
  }

  async stop(): Promise<void> {
    if (!this.isRunning) return;

    this.logger.log('Stopping streaming ETL service');
    this.isRunning = false;
    await this.consumer.disconnect();
    this.logger.log('Streaming ETL service stopped');
  }

  private async processMessage(payload: EachMessagePayload): Promise<void> {
    const { topic, partition, message } = payload;
    const startTime = Date.now();

    try {
      const handler = this.topicHandlers.get(topic);
      if (!handler) {
        this.logger.warn(`No handler for topic: ${topic}`);
        return;
      }

      const value = JSON.parse(message.value?.toString() || '{}');
      await handler(value);

      this.metrics.recordProcessedMessage(topic, Date.now() - startTime);
    } catch (error) {
      this.logger.error(`Error processing message from ${topic}:${partition}`, error);
      this.metrics.recordFailedMessage(topic);
      
      // Dead letter queue for failed messages
      await this.sendToDeadLetterQueue(topic, message, error);
    }
  }

  private async handleTradeChange(payload: CDCPayload): Promise<void> {
    if (payload.op === 'd') {
      // Handle delete - soft delete in fact table
      this.logger.warn(`Trade deleted: ${payload.before?.id}`);
      return;
    }

    const trade = payload.after;
    if (!trade) return;

    // Validate data quality
    const qualityResult = await this.dataQuality.validateTrade(trade);
    if (!qualityResult.isValid) {
      this.logger.warn(`Trade failed quality check: ${qualityResult.errors.join(', ')}`);
      this.metrics.recordQualityFailure('trade', qualityResult.errors);
      return;
    }

    // Transform to fact record
    const factTrade = await this.transformToFactTrade(trade);
    
    // Upsert with idempotency
    await this.factTradeRepo.upsert(factTrade, ['tradeReference']);
    this.logger.debug(`Loaded trade fact: ${trade.trade_reference}`);
  }

  private async handleOrderChange(payload: CDCPayload): Promise<void> {
    if (payload.op === 'd') return;

    const order = payload.after;
    if (!order || order.status !== 'FILLED') return; // Only load completed orders

    const qualityResult = await this.dataQuality.validateOrder(order);
    if (!qualityResult.isValid) {
      this.metrics.recordQualityFailure('order', qualityResult.errors);
      return;
    }

    const factOrder = await this.transformToFactOrder(order);
    await this.factOrderRepo.upsert(factOrder, ['orderId']);
  }

  private async handleTradeEvent(event: any): Promise<void> {
    // Real-time trade event (complementary to CDC)
    const trade = event.payload;
    
    const factTrade = await this.transformToFactTrade({
      id: trade.tradeId,
      tenant_id: trade.tenantId,
      trade_date: trade.executionTime,
      settlement_date: trade.settlementDate,
      client_id: trade.clientId,
      instrument_id: trade.instrumentId,
      lp_id: trade.lpId,
      trade_reference: trade.tradeReference,
      order_id: trade.orderId,
      side: trade.side,
      order_type: trade.orderType,
      quantity: trade.quantity,
      price: trade.price,
      fees: trade.fees || 0,
      currency: trade.currency,
      execution_venue: trade.venue,
      fill_latency_ms: trade.latencyMs || 0,
    });

    await this.factTradeRepo.upsert(factTrade, ['tradeReference']);
  }

  private async handleQuoteEvent(event: any): Promise<void> {
    const quote = event.payload;
    
    // Sample quotes (don't store every tick)
    if (!this.shouldSampleQuote(quote)) return;

    const factQuote: Partial<FactQuoteEntity> = {
      tenantId: quote.tenantId,
      quoteTimestamp: new Date(quote.timestamp),
      quoteDate: new Date(quote.timestamp),
      quoteDateKey: this.toDateKey(new Date(quote.timestamp)),
      instrumentId: quote.instrumentId,
      lpId: quote.lpId,
      bidPrice: quote.bid,
      askPrice: quote.ask,
      bidSize: quote.bidSize,
      askSize: quote.askSize,
      midPrice: (quote.bid + quote.ask) / 2,
      spread: quote.ask - quote.bid,
      spreadBps: ((quote.ask - quote.bid) / quote.bid) * 10000,
      isStale: quote.isStale || false,
      latencyMs: quote.latencyMs || 0,
    };

    await this.factQuoteRepo.save(factQuote);
  }

  private async transformToFactTrade(trade: any): Promise<Partial<FactTradeEntity>> {
    const tradeDate = new Date(trade.trade_date);
    const notionalValue = trade.quantity * trade.price;
    const netAmount = trade.side === 'BUY' 
      ? notionalValue + (trade.fees || 0)
      : notionalValue - (trade.fees || 0);

    return {
      tenantId: trade.tenant_id,
      tradeDate,
      settlementDate: new Date(trade.settlement_date),
      tradeDateKey: this.toDateKey(tradeDate),
      clientId: trade.client_id,
      instrumentId: trade.instrument_id,
      lpId: trade.lp_id,
      tradeReference: trade.trade_reference,
      orderId: trade.order_id,
      side: trade.side,
      orderType: trade.order_type,
      quantity: trade.quantity,
      price: trade.price,
      notionalValue,
      fees: trade.fees || 0,
      netAmount,
      currency: trade.currency,
      executionVenue: trade.execution_venue,
      fillLatencyMs: trade.fill_latency_ms || 0,
      fillCount: trade.fill_count || 1,
      isPartialFill: trade.is_partial_fill || false,
    };
  }

  private async transformToFactOrder(order: any): Promise<Partial<FactOrderEntity>> {
    const orderDate = new Date(order.created_at);
    const fillRate = order.requested_quantity > 0 
      ? order.filled_quantity / order.requested_quantity 
      : 0;

    return {
      tenantId: order.tenant_id,
      orderDate,
      orderDateKey: this.toDateKey(orderDate),
      clientId: order.client_id,
      instrumentId: order.instrument_id,
      orderId: order.id,
      orderType: order.order_type,
      side: order.side,
      finalStatus: order.status,
      requestedQuantity: order.requested_quantity,
      filledQuantity: order.filled_quantity,
      fillRate,
      limitPrice: order.limit_price,
      avgFillPrice: order.avg_fill_price,
      fillCount: order.fill_count || 0,
      totalLatencyMs: order.total_latency_ms || 0,
      routingLatencyMs: order.routing_latency_ms || 0,
      wasRejected: order.status === 'REJECTED',
      wasCancelled: order.status === 'CANCELLED',
    };
  }

  private toDateKey(date: Date): number {
    return date.getFullYear() * 10000 + (date.getMonth() + 1) * 100 + date.getDate();
  }

  private shouldSampleQuote(quote: any): boolean {
    // Sample 1 in 10 quotes for storage efficiency
    // Always store if spread changed significantly
    return Math.random() < 0.1 || quote.spreadChanged;
  }

  private async sendToDeadLetterQueue(
    topic: string,
    message: any,
    error: Error,
  ): Promise<void> {
    const producer = this.kafka.producer();
    await producer.connect();

    try {
      await producer.send({
        topic: `${topic}.dlq`,
        messages: [{
          key: message.key,
          value: JSON.stringify({
            originalMessage: message.value?.toString(),
            error: error.message,
            stack: error.stack,
            timestamp: new Date().toISOString(),
          }),
        }],
      });
    } finally {
      await producer.disconnect();
    }
  }
}
```

### Dimension Synchronization Service

```typescript
// src/analytics/etl/services/dimension-sync.service.ts
import { Injectable, Logger } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, DataSource } from 'typeorm';
import { Cron, CronExpression } from '@nestjs/schedule';
import { DimClientEntity } from '../../entities/dim-client.entity';
import { DimInstrumentEntity } from '../../entities/dim-instrument.entity';
import { DimLpEntity } from '../../entities/dim-lp.entity';

interface DimensionChange<T> {
  entity: T;
  changeType: 'INSERT' | 'UPDATE_SCD1' | 'UPDATE_SCD2';
  changedFields?: string[];
}

@Injectable()
export class DimensionSyncService {
  private readonly logger = new Logger(DimensionSyncService.name);

  // Fields that trigger SCD Type 2 (historical tracking)
  private readonly scd2Fields = {
    client: ['segment', 'region', 'status', 'client_type'],
    instrument: ['asset_class', 'instrument_type'],
    lp: ['tier', 'lp_type', 'is_active'],
  };

  constructor(
    private readonly dataSource: DataSource,
    @InjectRepository(DimClientEntity)
    private readonly dimClientRepo: Repository<DimClientEntity>,
    @InjectRepository(DimInstrumentEntity)
    private readonly dimInstrumentRepo: Repository<DimInstrumentEntity>,
    @InjectRepository(DimLpEntity)
    private readonly dimLpRepo: Repository<DimLpEntity>,
  ) {}

  @Cron(CronExpression.EVERY_5_MINUTES)
  async syncAllDimensions(): Promise<void> {
    this.logger.log('Starting dimension synchronization');

    await Promise.all([
      this.syncClients(),
      this.syncInstruments(),
      this.syncLiquidityProviders(),
    ]);

    this.logger.log('Dimension synchronization completed');
  }

  async syncClients(): Promise<void> {
    const sourceClients = await this.dataSource.query(`
      SELECT 
        c.id as client_id,
        c.tenant_id,
        c.client_code,
        c.name as client_name,
        c.client_type,
        c.region,
        c.segment,
        c.industry,
        c.onboard_date,
        c.status,
        c.updated_at
      FROM clients c
      WHERE c.updated_at > NOW() - INTERVAL '10 minutes'
         OR NOT EXISTS (
           SELECT 1 FROM dim_clients dc 
           WHERE dc.client_id = c.id AND dc.is_current = true
         )
    `);

    for (const source of sourceClients) {
      await this.syncClientDimension(source);
    }

    this.logger.log(`Synced ${sourceClients.length} clients`);
  }

  private async syncClientDimension(source: any): Promise<void> {
    const current = await this.dimClientRepo.findOne({
      where: { clientId: source.client_id, isCurrent: true },
    });

    if (!current) {
      // New client - insert
      const newDim = this.dimClientRepo.create({
        tenantId: source.tenant_id,
        clientId: source.client_id,
        clientCode: source.client_code,
        clientName: source.client_name,
        clientType: source.client_type,
        region: source.region,
        segment: source.segment,
        industry: source.industry,
        onboardDate: source.onboard_date,
        status: source.status,
        validFrom: new Date(),
        isCurrent: true,
        version: 1,
      });
      await this.dimClientRepo.save(newDim);
      return;
    }

    // Check for changes
    const scd2Changed = this.checkScd2Changes(current, source, 'client');
    const scd1Changed = this.checkScd1Changes(current, source);

    if (scd2Changed.length > 0) {
      // SCD Type 2 - close current record and create new version
      await this.dataSource.transaction(async (manager) => {
        // Close current record
        current.validTo = new Date();
        current.isCurrent = false;
        await manager.save(current);

        // Create new version
        const newVersion = this.dimClientRepo.create({
          ...current,
          id: undefined,
          clientName: source.client_name,
          clientType: source.client_type,
          region: source.region,
          segment: source.segment,
          industry: source.industry,
          status: source.status,
          validFrom: new Date(),
          validTo: null,
          isCurrent: true,
          version: current.version + 1,
        });
        await manager.save(newVersion);
      });

      this.logger.debug(`SCD2 update for client ${source.client_id}: ${scd2Changed.join(', ')}`);
    } else if (scd1Changed.length > 0) {
      // SCD Type 1 - update in place
      current.clientName = source.client_name;
      current.industry = source.industry;
      await this.dimClientRepo.save(current);

      this.logger.debug(`SCD1 update for client ${source.client_id}: ${scd1Changed.join(', ')}`);
    }
  }

  async syncInstruments(): Promise<void> {
    const sourceInstruments = await this.dataSource.query(`
      SELECT 
        i.id as instrument_id,
        i.symbol,
        i.name,
        i.asset_class,
        i.instrument_type,
        i.base_currency,
        i.quote_currency,
        i.exchange,
        i.price_precision,
        i.quantity_precision,
        i.updated_at
      FROM instruments i
      WHERE i.updated_at > NOW() - INTERVAL '10 minutes'
         OR NOT EXISTS (
           SELECT 1 FROM dim_instruments di 
           WHERE di.instrument_id = i.id AND di.is_current = true
         )
    `);

    for (const source of sourceInstruments) {
      await this.syncInstrumentDimension(source);
    }

    this.logger.log(`Synced ${sourceInstruments.length} instruments`);
  }

  private async syncInstrumentDimension(source: any): Promise<void> {
    const current = await this.dimInstrumentRepo.findOne({
      where: { instrumentId: source.instrument_id, isCurrent: true },
    });

    if (!current) {
      const newDim = this.dimInstrumentRepo.create({
        instrumentId: source.instrument_id,
        symbol: source.symbol,
        name: source.name,
        assetClass: source.asset_class,
        instrumentType: source.instrument_type,
        baseCurrency: source.base_currency,
        quoteCurrency: source.quote_currency,
        exchange: source.exchange,
        pricePrecision: source.price_precision,
        quantityPrecision: source.quantity_precision,
        validFrom: new Date(),
        isCurrent: true,
        version: 1,
      });
      await this.dimInstrumentRepo.save(newDim);
      return;
    }

    const scd2Changed = this.checkScd2Changes(current, source, 'instrument');
    
    if (scd2Changed.length > 0) {
      await this.dataSource.transaction(async (manager) => {
        current.validTo = new Date();
        current.isCurrent = false;
        await manager.save(current);

        const newVersion = this.dimInstrumentRepo.create({
          ...current,
          id: undefined,
          symbol: source.symbol,
          name: source.name,
          assetClass: source.asset_class,
          instrumentType: source.instrument_type,
          baseCurrency: source.base_currency,
          quoteCurrency: source.quote_currency,
          validFrom: new Date(),
          validTo: null,
          isCurrent: true,
          version: current.version + 1,
        });
        await manager.save(newVersion);
      });
    }
  }

  async syncLiquidityProviders(): Promise<void> {
    const sourceLps = await this.dataSource.query(`
      SELECT 
        lp.id as lp_id,
        lp.code as lp_code,
        lp.name as lp_name,
        lp.type as lp_type,
        lp.region,
        lp.tier,
        lp.is_active,
        lp.updated_at
      FROM liquidity_providers lp
      WHERE lp.updated_at > NOW() - INTERVAL '10 minutes'
         OR NOT EXISTS (
           SELECT 1 FROM dim_liquidity_providers dlp 
           WHERE dlp.lp_id = lp.id AND dlp.is_current = true
         )
    `);

    for (const source of sourceLps) {
      await this.syncLpDimension(source);
    }

    this.logger.log(`Synced ${sourceLps.length} liquidity providers`);
  }

  private async syncLpDimension(source: any): Promise<void> {
    const current = await this.dimLpRepo.findOne({
      where: { lpId: source.lp_id, isCurrent: true },
    });

    if (!current) {
      const newDim = this.dimLpRepo.create({
        lpId: source.lp_id,
        lpCode: source.lp_code,
        lpName: source.lp_name,
        lpType: source.lp_type,
        region: source.region,
        tier: source.tier,
        isActive: source.is_active,
        validFrom: new Date(),
        isCurrent: true,
      });
      await this.dimLpRepo.save(newDim);
      return;
    }

    const scd2Changed = this.checkScd2Changes(current, source, 'lp');
    
    if (scd2Changed.length > 0) {
      await this.dataSource.transaction(async (manager) => {
        current.validTo = new Date();
        current.isCurrent = false;
        await manager.save(current);

        const newVersion = this.dimLpRepo.create({
          ...current,
          id: undefined,
          lpName: source.lp_name,
          lpType: source.lp_type,
          region: source.region,
          tier: source.tier,
          isActive: source.is_active,
          validFrom: new Date(),
          validTo: null,
          isCurrent: true,
        });
        await manager.save(newVersion);
      });
    }
  }

  private checkScd2Changes(
    current: any,
    source: any,
    entityType: 'client' | 'instrument' | 'lp',
  ): string[] {
    const scd2Fields = this.scd2Fields[entityType];
    const changed: string[] = [];

    for (const field of scd2Fields) {
      const currentField = this.toCamelCase(field);
      if (current[currentField] !== source[field]) {
        changed.push(field);
      }
    }

    return changed;
  }

  private checkScd1Changes(current: any, source: any): string[] {
    const changed: string[] = [];
    
    // Non-historical fields
    if (current.clientName !== source.client_name) changed.push('client_name');
    if (current.industry !== source.industry) changed.push('industry');

    return changed;
  }

  private toCamelCase(str: string): string {
    return str.replace(/_([a-z])/g, (_, letter) => letter.toUpperCase());
  }
}
```

### Aggregation Job Service

```typescript
// src/analytics/etl/services/aggregation-job.service.ts
import { Injectable, Logger } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, DataSource } from 'typeorm';
import { Cron, CronExpression } from '@nestjs/schedule';
import { AggDailyTradeSummaryEntity } from '../../entities/agg-daily-trade-summary.entity';
import { AggClientMetricsEntity } from '../../entities/agg-client-metrics.entity';
import { AggLpPerformanceEntity } from '../../entities/agg-lp-performance.entity';

@Injectable()
export class AggregationJobService {
  private readonly logger = new Logger(AggregationJobService.name);

  constructor(
    private readonly dataSource: DataSource,
    @InjectRepository(AggDailyTradeSummaryEntity)
    private readonly aggDailySummaryRepo: Repository<AggDailyTradeSummaryEntity>,
    @InjectRepository(AggClientMetricsEntity)
    private readonly aggClientMetricsRepo: Repository<AggClientMetricsEntity>,
    @InjectRepository(AggLpPerformanceEntity)
    private readonly aggLpPerfRepo: Repository<AggLpPerformanceEntity>,
  ) {}

  // Run every 15 minutes for intraday updates
  @Cron('0 */15 * * * *')
  async runIntradayAggregation(): Promise<void> {
    const today = new Date();
    today.setHours(0, 0, 0, 0);

    this.logger.log('Running intraday aggregation');
    await this.aggregateDailyTradeSummary(today);
    this.logger.log('Intraday aggregation completed');
  }

  // Run at 1 AM for full daily aggregation
  @Cron('0 0 1 * * *')
  async runDailyAggregation(): Promise<void> {
    const yesterday = new Date();
    yesterday.setDate(yesterday.getDate() - 1);
    yesterday.setHours(0, 0, 0, 0);

    this.logger.log('Running daily aggregation');
    
    await Promise.all([
      this.aggregateDailyTradeSummary(yesterday, true),
      this.aggregateClientMetrics(yesterday, 'DAILY'),
      this.aggregateLpPerformance(yesterday, 'DAILY'),
    ]);

    this.logger.log('Daily aggregation completed');
  }

  // Run Monday at 2 AM for weekly metrics
  @Cron('0 0 2 * * 1')
  async runWeeklyAggregation(): Promise<void> {
    const weekStart = new Date();
    weekStart.setDate(weekStart.getDate() - 7);

    this.logger.log('Running weekly aggregation');
    
    await Promise.all([
      this.aggregateClientMetrics(weekStart, 'WEEKLY'),
      this.aggregateLpPerformance(weekStart, 'WEEKLY'),
    ]);

    this.logger.log('Weekly aggregation completed');
  }

  // Run 1st of month at 3 AM for monthly metrics
  @Cron('0 0 3 1 * *')
  async runMonthlyAggregation(): Promise<void> {
    const monthStart = new Date();
    monthStart.setMonth(monthStart.getMonth() - 1);
    monthStart.setDate(1);

    this.logger.log('Running monthly aggregation');
    
    await Promise.all([
      this.aggregateClientMetrics(monthStart, 'MONTHLY'),
      this.aggregateLpPerformance(monthStart, 'MONTHLY'),
    ]);

    this.logger.log('Monthly aggregation completed');
  }

  async aggregateDailyTradeSummary(date: Date, isFinal = false): Promise<void> {
    const dateStr = date.toISOString().split('T')[0];
    const dateKey = date.getFullYear() * 10000 + (date.getMonth() + 1) * 100 + date.getDate();

    // Multiple aggregation levels
    const aggregationLevels = [
      { level: 'TENANT', groupBy: 'tenant_id', columns: 'tenant_id, NULL as client_id, NULL as instrument_id, NULL as lp_id' },
      { level: 'CLIENT', groupBy: 'tenant_id, client_id', columns: 'tenant_id, client_id, NULL as instrument_id, NULL as lp_id' },
      { level: 'INSTRUMENT', groupBy: 'tenant_id, instrument_id', columns: 'tenant_id, NULL as client_id, instrument_id, NULL as lp_id' },
      { level: 'LP', groupBy: 'tenant_id, lp_id', columns: 'tenant_id, NULL as client_id, NULL as instrument_id, lp_id' },
      { level: 'CLIENT_INSTRUMENT', groupBy: 'tenant_id, client_id, instrument_id', columns: 'tenant_id, client_id, instrument_id, NULL as lp_id' },
    ];

    for (const agg of aggregationLevels) {
      await this.dataSource.query(`
        INSERT INTO agg_daily_trade_summary (
          id, tenant_id, trade_date, trade_date_key, client_id, instrument_id, lp_id,
          aggregation_level, trade_count, buy_count, sell_count,
          total_volume, buy_volume, sell_volume, total_notional, total_fees,
          avg_price, vwap_price, min_price, max_price, avg_latency_ms, max_latency_ms,
          created_at, updated_at
        )
        SELECT 
          gen_random_uuid(),
          ${agg.columns},
          '${dateStr}'::DATE,
          ${dateKey},
          '${agg.level}',
          COUNT(*),
          SUM(CASE WHEN side = 'BUY' THEN 1 ELSE 0 END),
          SUM(CASE WHEN side = 'SELL' THEN 1 ELSE 0 END),
          SUM(quantity),
          SUM(CASE WHEN side = 'BUY' THEN quantity ELSE 0 END),
          SUM(CASE WHEN side = 'SELL' THEN quantity ELSE 0 END),
          SUM(notional_value),
          SUM(fees),
          AVG(price),
          SUM(quantity * price) / NULLIF(SUM(quantity), 0),
          MIN(price),
          MAX(price),
          AVG(fill_latency_ms),
          MAX(fill_latency_ms),
          NOW(),
          NOW()
        FROM fact_trades
        WHERE trade_date = '${dateStr}'::DATE
        GROUP BY ${agg.groupBy}
        ON CONFLICT (tenant_id, trade_date, aggregation_level, 
          COALESCE(client_id, '00000000-0000-0000-0000-000000000000'),
          COALESCE(instrument_id, '00000000-0000-0000-0000-000000000000'),
          COALESCE(lp_id, '00000000-0000-0000-0000-000000000000'))
        DO UPDATE SET
          trade_count = EXCLUDED.trade_count,
          buy_count = EXCLUDED.buy_count,
          sell_count = EXCLUDED.sell_count,
          total_volume = EXCLUDED.total_volume,
          buy_volume = EXCLUDED.buy_volume,
          sell_volume = EXCLUDED.sell_volume,
          total_notional = EXCLUDED.total_notional,
          total_fees = EXCLUDED.total_fees,
          avg_price = EXCLUDED.avg_price,
          vwap_price = EXCLUDED.vwap_price,
          min_price = EXCLUDED.min_price,
          max_price = EXCLUDED.max_price,
          avg_latency_ms = EXCLUDED.avg_latency_ms,
          max_latency_ms = EXCLUDED.max_latency_ms,
          updated_at = NOW()
      `);
    }

    this.logger.debug(`Aggregated daily trade summary for ${dateStr}`);
  }

  async aggregateClientMetrics(periodStart: Date, periodType: string): Promise<void> {
    const periodEnd = this.calculatePeriodEnd(periodStart, periodType);
    
    await this.dataSource.query(`
      INSERT INTO agg_client_metrics (
        id, tenant_id, metric_date, client_id, period_type,
        trading_days, total_trades, unique_instruments,
        total_volume, total_notional, avg_trade_size, total_fees,
        realized_pnl, avg_utilization, max_utilization, breach_count,
        risk_score, engagement_score, created_at, updated_at
      )
      SELECT
        gen_random_uuid(),
        t.tenant_id,
        $1::DATE,
        t.client_id,
        $4,
        COUNT(DISTINCT t.trade_date),
        COUNT(*),
        COUNT(DISTINCT t.instrument_id),
        SUM(t.quantity),
        SUM(t.notional_value),
        AVG(t.notional_value),
        SUM(t.fees),
        COALESCE(p.realized_pnl, 0),
        COALESCE(AVG(r.utilization_pct), 0),
        COALESCE(MAX(r.utilization_pct), 0),
        COALESCE(SUM(r.soft_breach_count + r.hard_breach_count), 0),
        AVG(r.risk_score),
        -- Engagement score formula
        LEAST(1.0, (
          COUNT(DISTINCT t.trade_date)::DECIMAL / 
          EXTRACT(DAYS FROM ($2::DATE - $1::DATE))::DECIMAL
        ) * 0.4 + 
        LEAST(COUNT(*), 100)::DECIMAL / 100 * 0.3 +
        LEAST(COUNT(DISTINCT t.instrument_id), 20)::DECIMAL / 20 * 0.3),
        NOW(),
        NOW()
      FROM fact_trades t
      LEFT JOIN (
        SELECT client_id, SUM(realized_pnl) as realized_pnl
        FROM positions 
        GROUP BY client_id
      ) p ON t.client_id = p.client_id
      LEFT JOIN fact_risk_snapshots r 
        ON t.tenant_id = r.tenant_id 
        AND t.client_id = r.client_id 
        AND r.snapshot_date BETWEEN $1 AND $2
      WHERE t.trade_date BETWEEN $1 AND $2
      GROUP BY t.tenant_id, t.client_id, p.realized_pnl
      ON CONFLICT (tenant_id, client_id, metric_date, period_type)
      DO UPDATE SET
        trading_days = EXCLUDED.trading_days,
        total_trades = EXCLUDED.total_trades,
        unique_instruments = EXCLUDED.unique_instruments,
        total_volume = EXCLUDED.total_volume,
        total_notional = EXCLUDED.total_notional,
        avg_trade_size = EXCLUDED.avg_trade_size,
        total_fees = EXCLUDED.total_fees,
        realized_pnl = EXCLUDED.realized_pnl,
        avg_utilization = EXCLUDED.avg_utilization,
        max_utilization = EXCLUDED.max_utilization,
        breach_count = EXCLUDED.breach_count,
        risk_score = EXCLUDED.risk_score,
        engagement_score = EXCLUDED.engagement_score,
        updated_at = NOW()
    `, [periodStart, periodEnd, periodType, periodType]);

    this.logger.debug(`Aggregated client metrics for ${periodType} starting ${periodStart.toISOString()}`);
  }

  async aggregateLpPerformance(periodStart: Date, periodType: string): Promise<void> {
    const periodEnd = this.calculatePeriodEnd(periodStart, periodType);

    await this.dataSource.query(`
      INSERT INTO agg_lp_performance (
        id, tenant_id, metric_date, lp_id, instrument_id, period_type,
        quote_count, avg_spread, avg_spread_bps, quote_availability_pct, avg_latency_ms,
        trade_count, total_notional, fill_rate, rejection_count,
        overall_rank, performance_score, created_at, updated_at
      )
      SELECT
        gen_random_uuid(),
        COALESCE(q.tenant_id, t.tenant_id),
        $1::DATE,
        COALESCE(q.lp_id, t.lp_id),
        NULL, -- Overall LP performance (not per instrument)
        $3,
        COALESCE(q.quote_count, 0),
        COALESCE(q.avg_spread, 0),
        COALESCE(q.avg_spread_bps, 0),
        COALESCE(q.availability_pct, 0),
        COALESCE(q.avg_latency_ms, 0),
        COALESCE(t.trade_count, 0),
        COALESCE(t.total_notional, 0),
        CASE 
          WHEN COALESCE(t.request_count, 0) > 0 
          THEN t.trade_count::DECIMAL / t.request_count 
          ELSE 0 
        END,
        COALESCE(t.rejection_count, 0),
        NULL, -- Rank calculated separately
        -- Performance score formula
        (COALESCE(q.availability_pct, 0) * 0.25 +
         (1 - LEAST(COALESCE(q.avg_spread_bps, 100), 100) / 100) * 0.35 +
         (1 - LEAST(COALESCE(q.avg_latency_ms, 1000), 1000) / 1000) * 0.20 +
         COALESCE(t.fill_rate, 0) * 0.20),
        NOW(),
        NOW()
      FROM (
        SELECT
          tenant_id, lp_id,
          COUNT(*) as quote_count,
          AVG(spread) as avg_spread,
          AVG(spread_bps) as avg_spread_bps,
          AVG(CASE WHEN NOT is_stale THEN 1.0 ELSE 0.0 END) * 100 as availability_pct,
          AVG(latency_ms) as avg_latency_ms
        FROM fact_quotes
        WHERE quote_date BETWEEN $1 AND $2
        GROUP BY tenant_id, lp_id
      ) q
      FULL OUTER JOIN (
        SELECT
          tenant_id, lp_id,
          COUNT(*) as trade_count,
          SUM(notional_value) as total_notional,
          COUNT(*) as request_count,
          0 as rejection_count,
          1.0 as fill_rate
        FROM fact_trades
        WHERE trade_date BETWEEN $1 AND $2 AND lp_id IS NOT NULL
        GROUP BY tenant_id, lp_id
      ) t ON q.tenant_id = t.tenant_id AND q.lp_id = t.lp_id
      ON CONFLICT (tenant_id, lp_id, metric_date, period_type)
        WHERE instrument_id IS NULL
      DO UPDATE SET
        quote_count = EXCLUDED.quote_count,
        avg_spread = EXCLUDED.avg_spread,
        avg_spread_bps = EXCLUDED.avg_spread_bps,
        quote_availability_pct = EXCLUDED.quote_availability_pct,
        avg_latency_ms = EXCLUDED.avg_latency_ms,
        trade_count = EXCLUDED.trade_count,
        total_notional = EXCLUDED.total_notional,
        fill_rate = EXCLUDED.fill_rate,
        rejection_count = EXCLUDED.rejection_count,
        performance_score = EXCLUDED.performance_score,
        updated_at = NOW()
    `, [periodStart, periodEnd, periodType]);

    // Calculate ranks
    await this.calculateLpRanks(periodStart, periodType);

    this.logger.debug(`Aggregated LP performance for ${periodType} starting ${periodStart.toISOString()}`);
  }

  private async calculateLpRanks(periodStart: Date, periodType: string): Promise<void> {
    await this.dataSource.query(`
      WITH ranked AS (
        SELECT 
          id,
          ROW_NUMBER() OVER (
            PARTITION BY tenant_id 
            ORDER BY performance_score DESC NULLS LAST
          ) as rank
        FROM agg_lp_performance
        WHERE metric_date = $1 AND period_type = $2
      )
      UPDATE agg_lp_performance a
      SET overall_rank = r.rank
      FROM ranked r
      WHERE a.id = r.id
    `, [periodStart, periodType]);
  }

  private calculatePeriodEnd(start: Date, periodType: string): Date {
    const end = new Date(start);
    
    switch (periodType) {
      case 'DAILY':
        end.setDate(end.getDate() + 1);
        break;
      case 'WEEKLY':
        end.setDate(end.getDate() + 7);
        break;
      case 'MONTHLY':
        end.setMonth(end.getMonth() + 1);
        break;
      case 'QUARTERLY':
        end.setMonth(end.getMonth() + 3);
        break;
      case 'YEARLY':
        end.setFullYear(end.getFullYear() + 1);
        break;
    }
    
    end.setDate(end.getDate() - 1); // Last day of period
    return end;
  }
}
```

### Data Quality Service

```typescript
// src/analytics/etl/services/data-quality.service.ts
import { Injectable, Logger } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';

interface ValidationResult {
  isValid: boolean;
  errors: string[];
  warnings: string[];
}

interface DataQualityRule {
  name: string;
  validate: (record: any) => boolean;
  errorMessage: string;
  severity: 'ERROR' | 'WARNING';
}

@Injectable()
export class DataQualityService {
  private readonly logger = new Logger(DataQualityService.name);

  private readonly tradeRules: DataQualityRule[] = [
    {
      name: 'tenant_required',
      validate: (r) => !!r.tenant_id,
      errorMessage: 'tenant_id is required',
      severity: 'ERROR',
    },
    {
      name: 'client_required',
      validate: (r) => !!r.client_id,
      errorMessage: 'client_id is required',
      severity: 'ERROR',
    },
    {
      name: 'instrument_required',
      validate: (r) => !!r.instrument_id,
      errorMessage: 'instrument_id is required',
      severity: 'ERROR',
    },
    {
      name: 'positive_quantity',
      validate: (r) => r.quantity > 0,
      errorMessage: 'quantity must be positive',
      severity: 'ERROR',
    },
    {
      name: 'positive_price',
      validate: (r) => r.price > 0,
      errorMessage: 'price must be positive',
      severity: 'ERROR',
    },
    {
      name: 'valid_side',
      validate: (r) => ['BUY', 'SELL'].includes(r.side),
      errorMessage: 'side must be BUY or SELL',
      severity: 'ERROR',
    },
    {
      name: 'reasonable_price',
      validate: (r) => r.price < 1000000000, // 1 billion
      errorMessage: 'price seems unreasonably high',
      severity: 'WARNING',
    },
    {
      name: 'fees_non_negative',
      validate: (r) => (r.fees || 0) >= 0,
      errorMessage: 'fees should be non-negative',
      severity: 'WARNING',
    },
  ];

  private readonly orderRules: DataQualityRule[] = [
    {
      name: 'tenant_required',
      validate: (r) => !!r.tenant_id,
      errorMessage: 'tenant_id is required',
      severity: 'ERROR',
    },
    {
      name: 'client_required',
      validate: (r) => !!r.client_id,
      errorMessage: 'client_id is required',
      severity: 'ERROR',
    },
    {
      name: 'positive_requested_quantity',
      validate: (r) => r.requested_quantity > 0,
      errorMessage: 'requested_quantity must be positive',
      severity: 'ERROR',
    },
    {
      name: 'valid_status',
      validate: (r) => ['PENDING', 'PARTIAL', 'FILLED', 'CANCELLED', 'REJECTED'].includes(r.status),
      errorMessage: 'invalid order status',
      severity: 'ERROR',
    },
    {
      name: 'filled_not_exceed_requested',
      validate: (r) => r.filled_quantity <= r.requested_quantity,
      errorMessage: 'filled quantity exceeds requested',
      severity: 'WARNING',
    },
  ];

  async validateTrade(trade: any): Promise<ValidationResult> {
    return this.validate(trade, this.tradeRules);
  }

  async validateOrder(order: any): Promise<ValidationResult> {
    return this.validate(order, this.orderRules);
  }

  private validate(record: any, rules: DataQualityRule[]): ValidationResult {
    const errors: string[] = [];
    const warnings: string[] = [];

    for (const rule of rules) {
      try {
        if (!rule.validate(record)) {
          if (rule.severity === 'ERROR') {
            errors.push(`[${rule.name}] ${rule.errorMessage}`);
          } else {
            warnings.push(`[${rule.name}] ${rule.errorMessage}`);
          }
        }
      } catch (e) {
        errors.push(`[${rule.name}] Validation error: ${e.message}`);
      }
    }

    return {
      isValid: errors.length === 0,
      errors,
      warnings,
    };
  }
}
```

## Database Schema

```sql
-- ETL Control Tables

-- Track ETL job executions
CREATE TABLE etl_job_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_name VARCHAR(100) NOT NULL,
    job_type VARCHAR(50) NOT NULL, -- 'CDC', 'DIMENSION_SYNC', 'AGGREGATION'
    status VARCHAR(20) NOT NULL, -- 'RUNNING', 'COMPLETED', 'FAILED'
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    records_processed INTEGER DEFAULT 0,
    records_failed INTEGER DEFAULT 0,
    error_message TEXT,
    metadata JSONB
);

CREATE INDEX idx_etl_job_runs_name ON etl_job_runs(job_name, started_at);

-- Track data quality issues
CREATE TABLE etl_data_quality_issues (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_run_id UUID REFERENCES etl_job_runs(id),
    source_table VARCHAR(100) NOT NULL,
    record_id VARCHAR(100),
    rule_name VARCHAR(100) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    error_message TEXT NOT NULL,
    record_data JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_etl_dq_issues_job ON etl_data_quality_issues(job_run_id);
CREATE INDEX idx_etl_dq_issues_date ON etl_data_quality_issues(created_at);

-- CDC watermarks for resumable processing
CREATE TABLE etl_cdc_watermarks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_table VARCHAR(100) UNIQUE NOT NULL,
    last_processed_lsn VARCHAR(100),
    last_processed_timestamp TIMESTAMPTZ,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
```

## Definition of Done

- [ ] CDC connectors configured for all source tables
- [ ] Streaming ETL consumers processing all event types
- [ ] Dimension sync service with SCD Type 2 support
- [ ] Aggregation jobs for daily, weekly, monthly rollups
- [ ] Data quality rules implemented and enforced
- [ ] Dead letter queue for failed messages
- [ ] ETL monitoring and metrics
- [ ] Unit tests for transformation logic
- [ ] Integration tests for end-to-end ETL
- [ ] Documentation for ETL operations

## Dependencies

- Debezium for CDC
- Kafka for event streaming
- PostgreSQL with TimescaleDB
- NestJS scheduler for cron jobs

## Test Cases

### Unit Tests
```typescript
describe('DataQualityService', () => {
  it('should reject trade with missing tenant', async () => {
    const trade = { client_id: 'c1', quantity: 100, price: 10 };
    const result = await service.validateTrade(trade);
    expect(result.isValid).toBe(false);
    expect(result.errors).toContain('[tenant_required] tenant_id is required');
  });

  it('should warn on negative fees', async () => {
    const trade = validTrade({ fees: -10 });
    const result = await service.validateTrade(trade);
    expect(result.warnings.length).toBeGreaterThan(0);
  });
});

describe('DimensionSyncService', () => {
  it('should create SCD2 record on segment change', async () => {
    const client = await createClient({ segment: 'TIER_2' });
    await updateClientSegment(client.id, 'TIER_1');
    await service.syncClients();
    
    const versions = await dimClientRepo.find({
      where: { clientId: client.id },
      order: { version: 'ASC' },
    });
    
    expect(versions.length).toBe(2);
    expect(versions[0].isCurrent).toBe(false);
    expect(versions[1].isCurrent).toBe(true);
    expect(versions[1].segment).toBe('TIER_1');
  });
});
```

### Integration Tests
```typescript
describe('ETL Pipeline Integration', () => {
  it('should process trade event end-to-end', async () => {
    // Publish trade event
    await kafkaProducer.send({
      topic: 'trade.executed',
      messages: [{ value: JSON.stringify(tradeEvent) }],
    });

    // Wait for processing
    await sleep(5000);

    // Verify fact record
    const fact = await factTradeRepo.findOne({
      where: { tradeReference: tradeEvent.tradeReference },
    });
    
    expect(fact).toBeDefined();
    expect(fact.notionalValue).toBe(tradeEvent.quantity * tradeEvent.price);
  });
});
```
