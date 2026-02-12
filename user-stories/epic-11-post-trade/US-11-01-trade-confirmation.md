# User Story: US-11-01 - Trade Confirmation Service

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-11-01 |
| **Epic** | Epic 11 - Post-Trade Services |
| **Title** | Trade Confirmation Service |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |
| **PRD Reference** | FR-PostTrade-01, NFR-PostTrade-01 |

## User Story

**As a** trading operations manager  
**I want** automatic trade confirmations generated when orders are filled  
**So that** clients and counterparties receive timely confirmation of executed trades

## Description

Implement trade confirmation service that captures fills from the OMS, enriches trade data, generates confirmation documents, and distributes them via multiple channels (API, email, SWIFT).

## Acceptance Criteria

- [ ] Capture fills from Kafka events
- [ ] Create trade records with full audit trail
- [ ] Generate confirmation documents (PDF, JSON)
- [ ] Support multiple delivery channels
- [ ] Handle partial fills aggregation
- [ ] Maintain confirmation status tracking
- [ ] Support amendment and cancellation

## Technical Details

### Trade Entity

```typescript
// services/post-trade-service/src/entities/trade.entity.ts
import { Entity, Column, CreateDateColumn, UpdateDateColumn, Index } from 'typeorm';

export enum TradeStatus {
  PENDING_CONFIRMATION = 'pending_confirmation',
  CONFIRMED = 'confirmed',
  AMENDED = 'amended',
  CANCELLED = 'cancelled',
  SETTLED = 'settled',
}

export enum TradeType {
  OUTRIGHT = 'outright',
  SWAP = 'swap',
  FORWARD = 'forward',
  NDF = 'ndf',
  OPTION = 'option',
}

@Entity('trades')
@Index(['tenantId', 'tradeDate'])
@Index(['tenantId', 'clientId', 'status'])
@Index(['tenantId', 'orderId'])
export class TradeEntity {
  @Column('uuid', { primaryKey: true, default: () => 'gen_random_uuid()' })
  id: string;

  @Column('uuid')
  tenantId: string;

  @Column('varchar', { length: 50, unique: true })
  tradeReference: string;

  @Column('uuid')
  orderId: string;

  @Column('uuid')
  clientId: string;

  @Column('uuid')
  instrumentId: string;

  @Column('varchar', { length: 10 })
  side: string; // buy, sell

  @Column('decimal', { precision: 20, scale: 8 })
  quantity: number;

  @Column('decimal', { precision: 20, scale: 8 })
  price: number;

  @Column('varchar', { length: 3 })
  currency: string;

  @Column('decimal', { precision: 20, scale: 4 })
  notionalValue: number;

  @Column('uuid')
  lpId: string;

  @Column('varchar', { length: 100 })
  executionVenue: string;

  @Column('timestamp with time zone')
  executedAt: Date;

  @Column('date')
  tradeDate: Date;

  @Column('date')
  valueDate: Date;

  @Column('date')
  settlementDate: Date;

  @Column('varchar', { length: 20 })
  tradeType: TradeType;

  @Column('varchar', { length: 30, default: TradeStatus.PENDING_CONFIRMATION })
  status: TradeStatus;

  @Column('jsonb', { nullable: true })
  fees: {
    type: string;
    amount: number;
    currency: string;
  }[];

  @Column('jsonb', { nullable: true })
  regulatoryIds: {
    uti?: string;
    usi?: string;
    lei?: string;
    reportingCounterpartyId?: string;
  };

  @Column('jsonb', { nullable: true })
  counterparty: {
    name: string;
    lei?: string;
    accountId?: string;
  };

  @Column('varchar', { length: 100, nullable: true })
  allocatedToId: string;

  @Column('uuid', { nullable: true })
  originalTradeId: string; // For amendments

  @Column('text', { nullable: true })
  amendmentReason: string;

  @Column('jsonb', { nullable: true })
  metadata: any;

  @CreateDateColumn()
  createdAt: Date;

  @UpdateDateColumn()
  updatedAt: Date;
}
```

### Trade Confirmation Entity

```typescript
// services/post-trade-service/src/entities/trade-confirmation.entity.ts
import { Entity, Column, CreateDateColumn } from 'typeorm';

export enum ConfirmationStatus {
  PENDING = 'pending',
  SENT = 'sent',
  DELIVERED = 'delivered',
  ACKNOWLEDGED = 'acknowledged',
  FAILED = 'failed',
}

export enum DeliveryChannel {
  API = 'api',
  EMAIL = 'email',
  SWIFT = 'swift',
  FIX = 'fix',
}

@Entity('trade_confirmations')
export class TradeConfirmationEntity {
  @Column('uuid', { primaryKey: true, default: () => 'gen_random_uuid()' })
  id: string;

  @Column('uuid')
  tenantId: string;

  @Column('uuid')
  tradeId: string;

  @Column('varchar', { length: 50 })
  confirmationReference: string;

  @Column('varchar', { length: 20 })
  channel: DeliveryChannel;

  @Column('varchar', { length: 20, default: ConfirmationStatus.PENDING })
  status: ConfirmationStatus;

  @Column('varchar', { length: 200, nullable: true })
  recipient: string;

  @Column('text', { nullable: true })
  documentContent: string;

  @Column('varchar', { length: 500, nullable: true })
  documentUrl: string;

  @Column('timestamp with time zone', { nullable: true })
  sentAt: Date;

  @Column('timestamp with time zone', { nullable: true })
  deliveredAt: Date;

  @Column('timestamp with time zone', { nullable: true })
  acknowledgedAt: Date;

  @Column('varchar', { length: 500, nullable: true })
  errorMessage: string;

  @Column('int', { default: 0 })
  retryCount: number;

  @CreateDateColumn()
  createdAt: Date;
}
```

### Trade Confirmation Service

```typescript
// services/post-trade-service/src/confirmations/trade-confirmation.service.ts
import { Injectable } from '@nestjs/common';
import { InjectRepository, InjectEntityManager } from '@nestjs/typeorm';
import { Repository, EntityManager } from 'typeorm';
import { logger, metrics } from '@orion/observability';
import { EventPublisher } from '@orion/events';
import { TradeEntity, TradeStatus } from '../entities/trade.entity';
import {
  TradeConfirmationEntity,
  ConfirmationStatus,
  DeliveryChannel,
} from '../entities/trade-confirmation.entity';
import { DocumentGeneratorService } from './document-generator.service';
import { EmailService } from '@orion/notifications';

export interface CreateTradeDto {
  tenantId: string;
  orderId: string;
  fillId: string;
  clientId: string;
  instrumentId: string;
  side: string;
  quantity: number;
  price: number;
  currency: string;
  lpId: string;
  executionVenue: string;
  executedAt: Date;
  tradeType: string;
  fees?: { type: string; amount: number; currency: string }[];
  metadata?: any;
}

@Injectable()
export class TradeConfirmationService {
  constructor(
    @InjectRepository(TradeEntity)
    private readonly tradeRepo: Repository<TradeEntity>,
    @InjectRepository(TradeConfirmationEntity)
    private readonly confirmationRepo: Repository<TradeConfirmationEntity>,
    @InjectEntityManager()
    private readonly entityManager: EntityManager,
    private readonly documentGenerator: DocumentGeneratorService,
    private readonly emailService: EmailService,
    private readonly eventPublisher: EventPublisher,
  ) {}

  /**
   * Create trade from fill event
   */
  async createTrade(dto: CreateTradeDto): Promise<TradeEntity> {
    const startTime = Date.now();

    // Generate trade reference
    const tradeReference = await this.generateTradeReference(dto.tenantId);

    // Calculate value dates
    const { tradeDate, valueDate, settlementDate } = this.calculateDates(dto);

    // Calculate notional
    const notionalValue = dto.quantity * dto.price;

    // Generate regulatory IDs
    const regulatoryIds = await this.generateRegulatoryIds(dto);

    const trade = this.tradeRepo.create({
      ...dto,
      tradeReference,
      tradeDate,
      valueDate,
      settlementDate,
      notionalValue,
      regulatoryIds,
      status: TradeStatus.PENDING_CONFIRMATION,
    });

    await this.entityManager.transaction(async manager => {
      await manager.save(trade);

      // Create outbox event
      await manager.query(
        `INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload)
         VALUES ($1, $2, $3, $4)`,
        ['Trade', trade.id, 'trade.created', JSON.stringify(trade)],
      );
    });

    // Generate and send confirmation asynchronously
    this.generateAndSendConfirmation(trade);

    metrics.histogram('trade.creation_latency_ms', Date.now() - startTime);
    logger.info('Trade created', { tradeId: trade.id, reference: trade.tradeReference });

    return trade;
  }

  /**
   * Amend trade
   */
  async amendTrade(
    tradeId: string,
    amendments: Partial<CreateTradeDto>,
    reason: string,
    userId: string,
  ): Promise<TradeEntity> {
    const original = await this.tradeRepo.findOne({ where: { id: tradeId } });
    if (!original) {
      throw new Error('Trade not found');
    }

    if (original.status === TradeStatus.SETTLED) {
      throw new Error('Cannot amend settled trade');
    }

    // Cancel original
    original.status = TradeStatus.AMENDED;
    await this.tradeRepo.save(original);

    // Create amended trade
    const amended = this.tradeRepo.create({
      ...original,
      ...amendments,
      id: undefined,
      tradeReference: await this.generateTradeReference(original.tenantId, 'AMD'),
      originalTradeId: original.id,
      amendmentReason: reason,
      status: TradeStatus.PENDING_CONFIRMATION,
      notionalValue: (amendments.quantity || original.quantity) * (amendments.price || original.price),
      metadata: {
        ...original.metadata,
        amendedBy: userId,
        amendedAt: new Date(),
      },
    });

    await this.tradeRepo.save(amended);

    // Send amended confirmation
    await this.generateAndSendConfirmation(amended, true);

    logger.info('Trade amended', {
      originalId: tradeId,
      amendedId: amended.id,
      reason,
    });

    return amended;
  }

  /**
   * Cancel trade
   */
  async cancelTrade(
    tradeId: string,
    reason: string,
    userId: string,
  ): Promise<TradeEntity> {
    const trade = await this.tradeRepo.findOne({ where: { id: tradeId } });
    if (!trade) {
      throw new Error('Trade not found');
    }

    if (trade.status === TradeStatus.SETTLED) {
      throw new Error('Cannot cancel settled trade');
    }

    trade.status = TradeStatus.CANCELLED;
    trade.metadata = {
      ...trade.metadata,
      cancelledBy: userId,
      cancelledAt: new Date(),
      cancellationReason: reason,
    };

    await this.tradeRepo.save(trade);

    // Send cancellation confirmation
    await this.sendCancellationConfirmation(trade, reason);

    logger.info('Trade cancelled', { tradeId, reason });

    return trade;
  }

  /**
   * Aggregate partial fills into single trade
   */
  async aggregatePartialFills(
    orderId: string,
    fills: CreateTradeDto[],
  ): Promise<TradeEntity> {
    if (fills.length === 0) {
      throw new Error('No fills to aggregate');
    }

    const firstFill = fills[0];

    // Calculate weighted average price
    let totalQuantity = 0;
    let totalValue = 0;

    for (const fill of fills) {
      totalQuantity += fill.quantity;
      totalValue += fill.quantity * fill.price;
    }

    const averagePrice = totalValue / totalQuantity;

    // Aggregate fees
    const aggregatedFees = this.aggregateFees(fills);

    return this.createTrade({
      ...firstFill,
      quantity: totalQuantity,
      price: averagePrice,
      fees: aggregatedFees,
      metadata: {
        ...firstFill.metadata,
        aggregatedFromFills: fills.length,
        fillDetails: fills.map(f => ({
          fillId: f.fillId,
          quantity: f.quantity,
          price: f.price,
          executedAt: f.executedAt,
        })),
      },
    });
  }

  /**
   * Get trade by ID
   */
  async getTradeById(tenantId: string, tradeId: string): Promise<TradeEntity> {
    return this.tradeRepo.findOne({
      where: { id: tradeId, tenantId },
    });
  }

  /**
   * Get trades for client
   */
  async getClientTrades(
    tenantId: string,
    clientId: string,
    options: {
      startDate?: Date;
      endDate?: Date;
      status?: TradeStatus;
      limit?: number;
      offset?: number;
    } = {},
  ): Promise<{ trades: TradeEntity[]; total: number }> {
    const query = this.tradeRepo
      .createQueryBuilder('trade')
      .where('trade.tenantId = :tenantId', { tenantId })
      .andWhere('trade.clientId = :clientId', { clientId });

    if (options.startDate) {
      query.andWhere('trade.tradeDate >= :startDate', { startDate: options.startDate });
    }
    if (options.endDate) {
      query.andWhere('trade.tradeDate <= :endDate', { endDate: options.endDate });
    }
    if (options.status) {
      query.andWhere('trade.status = :status', { status: options.status });
    }

    const total = await query.getCount();

    query
      .orderBy('trade.executedAt', 'DESC')
      .limit(options.limit || 50)
      .offset(options.offset || 0);

    const trades = await query.getMany();

    return { trades, total };
  }

  /**
   * Generate and send confirmation
   */
  private async generateAndSendConfirmation(
    trade: TradeEntity,
    isAmendment: boolean = false,
  ): Promise<void> {
    try {
      // Get client preferences for delivery channels
      const channels = await this.getClientDeliveryPreferences(trade.tenantId, trade.clientId);

      for (const channel of channels) {
        const confirmation = this.confirmationRepo.create({
          tenantId: trade.tenantId,
          tradeId: trade.id,
          confirmationReference: `${trade.tradeReference}-${channel.toUpperCase()}`,
          channel,
          status: ConfirmationStatus.PENDING,
        });

        await this.confirmationRepo.save(confirmation);

        // Generate document
        const document = await this.documentGenerator.generateTradeConfirmation(
          trade,
          channel,
          isAmendment,
        );

        confirmation.documentContent = document.content;
        confirmation.documentUrl = document.url;

        // Send based on channel
        await this.sendConfirmation(confirmation, trade);
      }

      // Update trade status
      trade.status = TradeStatus.CONFIRMED;
      await this.tradeRepo.save(trade);
    } catch (error) {
      logger.error('Failed to generate confirmation', {
        tradeId: trade.id,
        error: error.message,
      });
    }
  }

  private async sendConfirmation(
    confirmation: TradeConfirmationEntity,
    trade: TradeEntity,
  ): Promise<void> {
    try {
      switch (confirmation.channel) {
        case DeliveryChannel.EMAIL:
          await this.sendEmailConfirmation(confirmation, trade);
          break;
        case DeliveryChannel.API:
          await this.publishApiConfirmation(confirmation, trade);
          break;
        case DeliveryChannel.SWIFT:
          await this.sendSwiftConfirmation(confirmation, trade);
          break;
      }

      confirmation.status = ConfirmationStatus.SENT;
      confirmation.sentAt = new Date();
      await this.confirmationRepo.save(confirmation);
    } catch (error) {
      confirmation.status = ConfirmationStatus.FAILED;
      confirmation.errorMessage = error.message;
      confirmation.retryCount += 1;
      await this.confirmationRepo.save(confirmation);

      throw error;
    }
  }

  private async sendEmailConfirmation(
    confirmation: TradeConfirmationEntity,
    trade: TradeEntity,
  ): Promise<void> {
    const clientEmail = await this.getClientEmail(trade.tenantId, trade.clientId);

    await this.emailService.send({
      to: [clientEmail],
      subject: `Trade Confirmation - ${trade.tradeReference}`,
      template: 'trade-confirmation',
      data: {
        trade,
        confirmationReference: confirmation.confirmationReference,
      },
      attachments: [
        {
          filename: `${trade.tradeReference}.pdf`,
          url: confirmation.documentUrl,
        },
      ],
    });

    confirmation.recipient = clientEmail;
  }

  private async publishApiConfirmation(
    confirmation: TradeConfirmationEntity,
    trade: TradeEntity,
  ): Promise<void> {
    await this.eventPublisher.publish({
      type: 'trade.confirmation.available',
      aggregateId: trade.id,
      aggregateType: 'Trade',
      payload: {
        trade,
        confirmationUrl: confirmation.documentUrl,
      },
      metadata: { tenantId: trade.tenantId },
    });
  }

  private async sendSwiftConfirmation(
    confirmation: TradeConfirmationEntity,
    trade: TradeEntity,
  ): Promise<void> {
    // Generate MT300/MT304 message
    const swiftMessage = await this.documentGenerator.generateSwiftMessage(trade);

    // Send via SWIFT network adapter
    // Implementation depends on SWIFT connectivity
  }

  private async sendCancellationConfirmation(
    trade: TradeEntity,
    reason: string,
  ): Promise<void> {
    const channels = await this.getClientDeliveryPreferences(trade.tenantId, trade.clientId);

    for (const channel of channels) {
      const document = await this.documentGenerator.generateCancellationConfirmation(
        trade,
        reason,
        channel,
      );

      // Send via appropriate channel
    }
  }

  private async generateTradeReference(
    tenantId: string,
    prefix: string = 'TRD',
  ): Promise<string> {
    const date = new Date().toISOString().slice(0, 10).replace(/-/g, '');
    const sequence = await this.getNextSequence(tenantId, date);
    return `${prefix}${date}${sequence.toString().padStart(6, '0')}`;
  }

  private async getNextSequence(tenantId: string, date: string): Promise<number> {
    const result = await this.entityManager.query(
      `INSERT INTO trade_sequences (tenant_id, sequence_date, current_value)
       VALUES ($1, $2, 1)
       ON CONFLICT (tenant_id, sequence_date)
       DO UPDATE SET current_value = trade_sequences.current_value + 1
       RETURNING current_value`,
      [tenantId, date],
    );
    return result[0].current_value;
  }

  private calculateDates(dto: CreateTradeDto): {
    tradeDate: Date;
    valueDate: Date;
    settlementDate: Date;
  } {
    const tradeDate = new Date(dto.executedAt);
    tradeDate.setHours(0, 0, 0, 0);

    // T+2 settlement by default
    const settlementDate = new Date(tradeDate);
    settlementDate.setDate(settlementDate.getDate() + 2);

    return {
      tradeDate,
      valueDate: settlementDate,
      settlementDate,
    };
  }

  private async generateRegulatoryIds(dto: CreateTradeDto): Promise<{
    uti: string;
    lei?: string;
  }> {
    // Generate Unique Transaction Identifier (UTI)
    const uti = `${dto.tenantId.slice(0, 10)}${Date.now()}${Math.random().toString(36).slice(2, 8)}`;

    return { uti };
  }

  private aggregateFees(fills: CreateTradeDto[]): { type: string; amount: number; currency: string }[] {
    const feeMap = new Map<string, { amount: number; currency: string }>();

    for (const fill of fills) {
      if (fill.fees) {
        for (const fee of fill.fees) {
          const key = `${fee.type}-${fee.currency}`;
          const existing = feeMap.get(key) || { amount: 0, currency: fee.currency };
          existing.amount += fee.amount;
          feeMap.set(key, existing);
        }
      }
    }

    return Array.from(feeMap.entries()).map(([key, value]) => ({
      type: key.split('-')[0],
      amount: value.amount,
      currency: value.currency,
    }));
  }

  private async getClientDeliveryPreferences(
    tenantId: string,
    clientId: string,
  ): Promise<DeliveryChannel[]> {
    // Get from client settings - default to API and Email
    return [DeliveryChannel.API, DeliveryChannel.EMAIL];
  }

  private async getClientEmail(tenantId: string, clientId: string): Promise<string> {
    // Get from client master data
    return 'client@example.com';
  }
}
```

### Fill Event Consumer

```typescript
// services/post-trade-service/src/consumers/fill-event.consumer.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { Kafka, Consumer, EachMessagePayload } from 'kafkajs';
import { logger, metrics } from '@orion/observability';
import { TradeConfirmationService } from '../confirmations/trade-confirmation.service';

@Injectable()
export class FillEventConsumer implements OnModuleInit {
  private consumer: Consumer;
  private fillBuffer: Map<string, any[]> = new Map();
  private flushTimeout: NodeJS.Timeout;

  constructor(
    private readonly tradeService: TradeConfirmationService,
  ) {
    const kafka = new Kafka({
      clientId: 'post-trade-service',
      brokers: process.env.KAFKA_BROKERS.split(','),
    });

    this.consumer = kafka.consumer({ groupId: 'post-trade-fills' });
  }

  async onModuleInit() {
    await this.consumer.connect();
    await this.consumer.subscribe({
      topics: ['order.filled', 'order.partially_filled'],
      fromBeginning: false,
    });

    await this.consumer.run({
      eachMessage: this.handleMessage.bind(this),
    });

    // Start flush timer for partial fills
    this.flushTimeout = setInterval(() => this.flushPartialFills(), 5000);
  }

  private async handleMessage({ topic, message }: EachMessagePayload): Promise<void> {
    const startTime = Date.now();

    try {
      const event = JSON.parse(message.value.toString());

      if (event.orderStatus === 'filled') {
        // Complete fill - create trade immediately
        await this.createTradeFromFill(event);
      } else if (event.orderStatus === 'partially_filled') {
        // Partial fill - buffer for aggregation
        this.bufferPartialFill(event);
      }

      metrics.histogram('fill.processing_latency_ms', Date.now() - startTime);
    } catch (error) {
      logger.error('Failed to process fill event', {
        topic,
        error: error.message,
      });
    }
  }

  private async createTradeFromFill(event: any): Promise<void> {
    await this.tradeService.createTrade({
      tenantId: event.tenantId,
      orderId: event.orderId,
      fillId: event.fillId,
      clientId: event.clientId,
      instrumentId: event.instrumentId,
      side: event.side,
      quantity: event.filledQuantity,
      price: event.fillPrice,
      currency: event.currency,
      lpId: event.lpId,
      executionVenue: event.executionVenue,
      executedAt: new Date(event.timestamp),
      tradeType: event.orderType,
      metadata: event.metadata,
    });
  }

  private bufferPartialFill(event: any): void {
    const key = event.orderId;
    const existing = this.fillBuffer.get(key) || [];
    existing.push(event);
    this.fillBuffer.set(key, existing);
  }

  private async flushPartialFills(): Promise<void> {
    const now = Date.now();

    for (const [orderId, fills] of this.fillBuffer.entries()) {
      // Check if oldest fill is > 5 seconds old
      const oldest = Math.min(...fills.map(f => new Date(f.timestamp).getTime()));

      if (now - oldest > 5000) {
        try {
          await this.tradeService.aggregatePartialFills(orderId, fills.map(f => ({
            tenantId: f.tenantId,
            orderId: f.orderId,
            fillId: f.fillId,
            clientId: f.clientId,
            instrumentId: f.instrumentId,
            side: f.side,
            quantity: f.filledQuantity,
            price: f.fillPrice,
            currency: f.currency,
            lpId: f.lpId,
            executionVenue: f.executionVenue,
            executedAt: new Date(f.timestamp),
            tradeType: f.orderType,
            metadata: f.metadata,
          })));

          this.fillBuffer.delete(orderId);
        } catch (error) {
          logger.error('Failed to aggregate partial fills', { orderId, error });
        }
      }
    }
  }
}
```

## Database Schema

```sql
-- Trades table
CREATE TABLE trades (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    trade_reference VARCHAR(50) UNIQUE NOT NULL,
    order_id UUID NOT NULL,
    client_id UUID NOT NULL,
    instrument_id UUID NOT NULL,
    side VARCHAR(10) NOT NULL,
    quantity DECIMAL(20, 8) NOT NULL,
    price DECIMAL(20, 8) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    notional_value DECIMAL(20, 4) NOT NULL,
    lp_id UUID NOT NULL,
    execution_venue VARCHAR(100) NOT NULL,
    executed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    trade_date DATE NOT NULL,
    value_date DATE NOT NULL,
    settlement_date DATE NOT NULL,
    trade_type VARCHAR(20) NOT NULL,
    status VARCHAR(30) DEFAULT 'pending_confirmation',
    fees JSONB,
    regulatory_ids JSONB,
    counterparty JSONB,
    allocated_to_id VARCHAR(100),
    original_trade_id UUID,
    amendment_reason TEXT,
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_trades_tenant_date ON trades(tenant_id, trade_date);
CREATE INDEX idx_trades_tenant_client ON trades(tenant_id, client_id, status);
CREATE INDEX idx_trades_order ON trades(tenant_id, order_id);

-- Trade confirmations table
CREATE TABLE trade_confirmations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    trade_id UUID NOT NULL REFERENCES trades(id),
    confirmation_reference VARCHAR(50) NOT NULL,
    channel VARCHAR(20) NOT NULL,
    status VARCHAR(20) DEFAULT 'pending',
    recipient VARCHAR(200),
    document_content TEXT,
    document_url VARCHAR(500),
    sent_at TIMESTAMP WITH TIME ZONE,
    delivered_at TIMESTAMP WITH TIME ZONE,
    acknowledged_at TIMESTAMP WITH TIME ZONE,
    error_message VARCHAR(500),
    retry_count INT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Trade sequences for reference generation
CREATE TABLE trade_sequences (
    tenant_id UUID NOT NULL,
    sequence_date DATE NOT NULL,
    current_value INT DEFAULT 0,
    PRIMARY KEY (tenant_id, sequence_date)
);
```

## Definition of Done

- [ ] Fill event consumption
- [ ] Trade creation with reference
- [ ] Partial fill aggregation
- [ ] Confirmation generation (PDF, JSON)
- [ ] Multi-channel delivery
- [ ] Amendment support
- [ ] Cancellation support
- [ ] Unit and integration tests

## Dependencies

- **US-09-06**: Fill Processing (source events)
- **External**: Document generation service

## Test Cases

```typescript
describe('TradeConfirmationService', () => {
  it('should create trade from fill event', async () => {
    const trade = await tradeService.createTrade({
      tenantId: 'tenant-1',
      orderId: 'order-1',
      fillId: 'fill-1',
      clientId: 'client-1',
      instrumentId: 'EUR/USD',
      side: 'buy',
      quantity: 100000,
      price: 1.0850,
      currency: 'USD',
      lpId: 'lp-1',
      executionVenue: 'EBS',
      executedAt: new Date(),
      tradeType: 'outright',
    });

    expect(trade.tradeReference).toMatch(/^TRD\d{8}\d{6}$/);
    expect(trade.notionalValue).toBe(108500);
    expect(trade.regulatoryIds.uti).toBeDefined();
  });

  it('should aggregate partial fills', async () => {
    const fills = [
      { fillId: 'f1', quantity: 50000, price: 1.0850 },
      { fillId: 'f2', quantity: 30000, price: 1.0852 },
      { fillId: 'f3', quantity: 20000, price: 1.0848 },
    ];

    const trade = await tradeService.aggregatePartialFills('order-1', fills);

    expect(trade.quantity).toBe(100000);
    expect(trade.metadata.aggregatedFromFills).toBe(3);
  });

  it('should amend trade and link to original', async () => {
    const original = await createTrade();
    const amended = await tradeService.amendTrade(
      original.id,
      { price: 1.0855 },
      'Price correction',
      'user-1',
    );

    expect(amended.originalTradeId).toBe(original.id);
    expect(amended.price).toBe(1.0855);
    expect(original.status).toBe(TradeStatus.AMENDED);
  });
});
```
