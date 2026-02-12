# User Story: US-07-03 - Quote Collection and Ranking

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-07-03 |
| **Epic** | Epic 07 - RFQ Workflow |
| **Title** | Quote Collection and Ranking |
| **Priority** | P0 - Critical |
| **Story Points** | 8 |
| **PRD Reference** | FR-RFQ-03 |

## User Story

**As a** trader  
**I want** to receive and compare quotes from multiple liquidity providers  
**So that** I can execute at the best available price

## Description

Implement quote collection from LP gateways, quote validation, best price ranking, and composite quote aggregation with real-time streaming to traders.

## Acceptance Criteria

- [ ] Quote reception from all gateway types
- [ ] Quote validation (price, quantity, expiry)
- [ ] Best price ranking algorithm
- [ ] Quote status tracking
- [ ] Real-time quote streaming to traders
- [ ] RFQ status updated to QUOTED

## Technical Details

### Quote Database Schema

```sql
-- migrations/20240121_create_quotes_table.sql

CREATE TYPE quote_status AS ENUM (
    'pending',
    'valid',
    'expired',
    'selected',
    'executed',
    'rejected',
    'withdrawn'
);

CREATE TABLE quotes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rfq_id UUID NOT NULL REFERENCES rfqs(id),
    lp_id UUID NOT NULL REFERENCES liquidity_providers(id),
    
    -- LP Reference
    lp_quote_id VARCHAR(100),
    
    -- Pricing
    bid_price NUMERIC(20, 8),
    ask_price NUMERIC(20, 8),
    mid_price NUMERIC(20, 8),
    spread NUMERIC(20, 8),
    
    -- For two-way quotes
    bid_quantity NUMERIC(20, 8),
    ask_quantity NUMERIC(20, 8),
    
    -- Validity
    valid_until TIMESTAMPTZ NOT NULL,
    
    -- Status
    status quote_status NOT NULL DEFAULT 'pending',
    
    -- Ranking
    ranking INTEGER,
    is_best_bid BOOLEAN DEFAULT FALSE,
    is_best_ask BOOLEAN DEFAULT FALSE,
    
    -- Timing
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    validated_at TIMESTAMPTZ,
    
    -- Audit
    raw_message JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_quotes_rfq ON quotes(rfq_id, status);
CREATE INDEX idx_quotes_lp ON quotes(lp_id, received_at DESC);
CREATE INDEX idx_quotes_rfq_ranking ON quotes(rfq_id, ranking) WHERE status = 'valid';
```

### Quote Entity

```typescript
// services/rfq-service/src/entities/quote.entity.ts
import { Entity, Column, PrimaryGeneratedColumn, CreateDateColumn, ManyToOne, JoinColumn } from 'typeorm';
import { RfqEntity } from './rfq.entity';

export type QuoteStatus = 
  | 'pending' | 'valid' | 'expired' | 'selected' 
  | 'executed' | 'rejected' | 'withdrawn';

@Entity('quotes')
export class QuoteEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column('uuid', { name: 'rfq_id' })
  rfqId: string;

  @ManyToOne(() => RfqEntity)
  @JoinColumn({ name: 'rfq_id' })
  rfq: RfqEntity;

  @Column('uuid', { name: 'lp_id' })
  lpId: string;

  @Column({ name: 'lp_quote_id', nullable: true })
  lpQuoteId?: string;

  @Column('decimal', { name: 'bid_price', precision: 20, scale: 8, nullable: true })
  bidPrice?: number;

  @Column('decimal', { name: 'ask_price', precision: 20, scale: 8, nullable: true })
  askPrice?: number;

  @Column('decimal', { name: 'mid_price', precision: 20, scale: 8, nullable: true })
  midPrice?: number;

  @Column('decimal', { precision: 20, scale: 8, nullable: true })
  spread?: number;

  @Column('decimal', { name: 'bid_quantity', precision: 20, scale: 8, nullable: true })
  bidQuantity?: number;

  @Column('decimal', { name: 'ask_quantity', precision: 20, scale: 8, nullable: true })
  askQuantity?: number;

  @Column({ name: 'valid_until', type: 'timestamptz' })
  validUntil: Date;

  @Column({ type: 'enum', enum: ['pending', 'valid', 'expired', 'selected', 'executed', 'rejected', 'withdrawn'], default: 'pending' })
  status: QuoteStatus;

  @Column({ nullable: true })
  ranking?: number;

  @Column({ name: 'is_best_bid', default: false })
  isBestBid: boolean;

  @Column({ name: 'is_best_ask', default: false })
  isBestAsk: boolean;

  @Column({ name: 'received_at', type: 'timestamptz' })
  receivedAt: Date;

  @Column({ name: 'validated_at', type: 'timestamptz', nullable: true })
  validatedAt?: Date;

  @Column('jsonb', { name: 'raw_message', nullable: true })
  rawMessage?: any;

  @CreateDateColumn({ name: 'created_at' })
  createdAt: Date;
}
```

### Quote Collection Service

```typescript
// services/rfq-service/src/quote/quote-collection.service.ts
import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { logger, metrics } from '@orion/observability';
import { EventEmitter2 } from '@nestjs/event-emitter';
import { transactionalOutbox } from '@orion/event-model';
import { QuoteEntity, QuoteStatus } from '../entities/quote.entity';
import { RfqEntity } from '../entities/rfq.entity';
import { QuoteValidationService } from './quote-validation.service';

export interface IncomingQuote {
  rfqId: string;
  lpId: string;
  lpQuoteId?: string;
  bidPrice?: number;
  askPrice?: number;
  bidQuantity?: number;
  askQuantity?: number;
  validUntil: Date;
  rawMessage?: any;
}

@Injectable()
export class QuoteCollectionService {
  constructor(
    @InjectRepository(QuoteEntity)
    private readonly quoteRepository: Repository<QuoteEntity>,
    @InjectRepository(RfqEntity)
    private readonly rfqRepository: Repository<RfqEntity>,
    private readonly validationService: QuoteValidationService,
    private readonly eventEmitter: EventEmitter2,
  ) {}

  async receiveQuote(incoming: IncomingQuote): Promise<QuoteEntity | null> {
    const receivedAt = new Date();
    
    // Get RFQ
    const rfq = await this.rfqRepository.findOne({ 
      where: { id: incoming.rfqId } 
    });

    if (!rfq) {
      logger.warn('Quote received for unknown RFQ', { rfqId: incoming.rfqId });
      return null;
    }

    // Check RFQ is in valid state
    if (!['distributed', 'quoted'].includes(rfq.status)) {
      logger.warn('Quote received for RFQ in invalid state', { 
        rfqId: incoming.rfqId, 
        status: rfq.status 
      });
      return null;
    }

    // Check RFQ not expired
    if (rfq.expiresAt < receivedAt) {
      logger.warn('Quote received for expired RFQ', { rfqId: incoming.rfqId });
      return null;
    }

    // Check for duplicate
    const existingQuote = await this.quoteRepository.findOne({
      where: { rfqId: incoming.rfqId, lpId: incoming.lpId },
    });

    if (existingQuote) {
      // Update existing quote
      return this.updateQuote(existingQuote, incoming, receivedAt);
    }

    // Create new quote
    const quote = this.quoteRepository.create({
      rfqId: incoming.rfqId,
      lpId: incoming.lpId,
      lpQuoteId: incoming.lpQuoteId,
      bidPrice: incoming.bidPrice,
      askPrice: incoming.askPrice,
      bidQuantity: incoming.bidQuantity,
      askQuantity: incoming.askQuantity,
      validUntil: incoming.validUntil,
      midPrice: this.calculateMidPrice(incoming.bidPrice, incoming.askPrice),
      spread: this.calculateSpread(incoming.bidPrice, incoming.askPrice),
      status: 'pending',
      receivedAt,
      rawMessage: incoming.rawMessage,
    });

    // Validate quote
    const validation = await this.validationService.validate(quote, rfq);
    
    if (validation.valid) {
      quote.status = 'valid';
      quote.validatedAt = new Date();
    } else {
      quote.status = 'rejected';
      logger.warn('Quote validation failed', { 
        quoteId: quote.id, 
        errors: validation.errors 
      });
    }

    const savedQuote = await this.quoteRepository.save(quote);

    // If first valid quote, update RFQ status
    if (quote.status === 'valid' && rfq.status === 'distributed') {
      await this.updateRfqToQuoted(rfq.id);
    }

    // Re-rank all quotes
    if (quote.status === 'valid') {
      await this.rankQuotes(rfq.id, rfq.side);
    }

    // Emit quote received event
    this.eventEmitter.emit('quote.received', {
      rfqId: rfq.id,
      quoteId: savedQuote.id,
      lpId: savedQuote.lpId,
      bidPrice: savedQuote.bidPrice,
      askPrice: savedQuote.askPrice,
      status: savedQuote.status,
    });

    metrics.increment('quote.received', { 
      status: savedQuote.status,
      lpId: savedQuote.lpId,
    });

    return savedQuote;
  }

  private async updateQuote(
    existing: QuoteEntity, 
    incoming: IncomingQuote,
    receivedAt: Date,
  ): Promise<QuoteEntity> {
    existing.bidPrice = incoming.bidPrice;
    existing.askPrice = incoming.askPrice;
    existing.bidQuantity = incoming.bidQuantity;
    existing.askQuantity = incoming.askQuantity;
    existing.validUntil = incoming.validUntil;
    existing.midPrice = this.calculateMidPrice(incoming.bidPrice, incoming.askPrice);
    existing.spread = this.calculateSpread(incoming.bidPrice, incoming.askPrice);
    existing.receivedAt = receivedAt;
    existing.rawMessage = incoming.rawMessage;

    return this.quoteRepository.save(existing);
  }

  async rankQuotes(rfqId: string, rfqSide: string): Promise<void> {
    const quotes = await this.quoteRepository.find({
      where: { rfqId, status: 'valid' },
    });

    if (quotes.length === 0) return;

    // Reset best flags
    quotes.forEach(q => {
      q.isBestBid = false;
      q.isBestAsk = false;
    });

    // Sort based on RFQ side
    if (rfqSide === 'buy') {
      // For buying, lower ask is better
      quotes.sort((a, b) => (a.askPrice || Infinity) - (b.askPrice || Infinity));
    } else if (rfqSide === 'sell') {
      // For selling, higher bid is better
      quotes.sort((a, b) => (b.bidPrice || 0) - (a.bidPrice || 0));
    } else {
      // Two-way: rank by spread
      quotes.sort((a, b) => (a.spread || Infinity) - (b.spread || Infinity));
    }

    // Assign rankings
    quotes.forEach((quote, index) => {
      quote.ranking = index + 1;
    });

    // Mark best bid/ask
    const bestBid = [...quotes].sort((a, b) => (b.bidPrice || 0) - (a.bidPrice || 0))[0];
    const bestAsk = [...quotes].sort((a, b) => (a.askPrice || Infinity) - (b.askPrice || Infinity))[0];

    if (bestBid?.bidPrice) bestBid.isBestBid = true;
    if (bestAsk?.askPrice) bestAsk.isBestAsk = true;

    await this.quoteRepository.save(quotes);

    // Emit ranking update
    this.eventEmitter.emit('quotes.ranked', {
      rfqId,
      rankings: quotes.map(q => ({
        quoteId: q.id,
        lpId: q.lpId,
        ranking: q.ranking,
        bidPrice: q.bidPrice,
        askPrice: q.askPrice,
        isBestBid: q.isBestBid,
        isBestAsk: q.isBestAsk,
      })),
    });
  }

  async getQuotesForRfq(rfqId: string): Promise<QuoteEntity[]> {
    return this.quoteRepository.find({
      where: { rfqId },
      order: { ranking: 'ASC' },
    });
  }

  async getValidQuotesForRfq(rfqId: string): Promise<QuoteEntity[]> {
    const now = new Date();
    
    return this.quoteRepository
      .createQueryBuilder('quote')
      .where('quote.rfq_id = :rfqId', { rfqId })
      .andWhere('quote.status = :status', { status: 'valid' })
      .andWhere('quote.valid_until > :now', { now })
      .orderBy('quote.ranking', 'ASC')
      .getMany();
  }

  async getBestQuote(rfqId: string, side: string): Promise<QuoteEntity | null> {
    const quotes = await this.getValidQuotesForRfq(rfqId);
    return quotes[0] || null;
  }

  async getCompositeQuote(rfqId: string): Promise<CompositeQuote> {
    const quotes = await this.getValidQuotesForRfq(rfqId);

    if (quotes.length === 0) {
      return {
        rfqId,
        quoteCount: 0,
        bestBid: null,
        bestAsk: null,
        bestSpread: null,
        averageBid: null,
        averageAsk: null,
      };
    }

    const bids = quotes.filter(q => q.bidPrice).map(q => Number(q.bidPrice));
    const asks = quotes.filter(q => q.askPrice).map(q => Number(q.askPrice));

    return {
      rfqId,
      quoteCount: quotes.length,
      bestBid: bids.length > 0 ? Math.max(...bids) : null,
      bestAsk: asks.length > 0 ? Math.min(...asks) : null,
      bestSpread: this.calculateSpread(
        bids.length > 0 ? Math.max(...bids) : undefined,
        asks.length > 0 ? Math.min(...asks) : undefined,
      ),
      averageBid: bids.length > 0 ? bids.reduce((a, b) => a + b, 0) / bids.length : null,
      averageAsk: asks.length > 0 ? asks.reduce((a, b) => a + b, 0) / asks.length : null,
    };
  }

  private async updateRfqToQuoted(rfqId: string): Promise<void> {
    await transactionalOutbox(
      this.rfqRepository.manager,
      async (manager) => {
        await manager.update(RfqEntity, rfqId, {
          status: 'quoted',
          updatedAt: new Date(),
        });
      },
      {
        topic: 'orion.events.rfq',
        eventType: 'rfq.quoted',
        aggregateType: 'rfq',
        aggregateId: rfqId,
        payload: { rfqId },
      },
    );
  }

  private calculateMidPrice(bid?: number, ask?: number): number | undefined {
    if (bid !== undefined && ask !== undefined) {
      return (Number(bid) + Number(ask)) / 2;
    }
    return bid || ask;
  }

  private calculateSpread(bid?: number, ask?: number): number | undefined {
    if (bid !== undefined && ask !== undefined) {
      return Number(ask) - Number(bid);
    }
    return undefined;
  }
}

interface CompositeQuote {
  rfqId: string;
  quoteCount: number;
  bestBid: number | null;
  bestAsk: number | null;
  bestSpread: number | null;
  averageBid: number | null;
  averageAsk: number | null;
}
```

### Quote Validation Service

```typescript
// services/rfq-service/src/quote/quote-validation.service.ts
import { Injectable } from '@nestjs/common';
import { QuoteEntity } from '../entities/quote.entity';
import { RfqEntity } from '../entities/rfq.entity';

export interface QuoteValidationResult {
  valid: boolean;
  errors: string[];
  warnings: string[];
}

@Injectable()
export class QuoteValidationService {
  async validate(quote: QuoteEntity, rfq: RfqEntity): Promise<QuoteValidationResult> {
    const errors: string[] = [];
    const warnings: string[] = [];

    // 1. Validate quote has pricing
    if (rfq.side === 'buy' && !quote.askPrice) {
      errors.push('Ask price required for buy RFQ');
    }
    
    if (rfq.side === 'sell' && !quote.bidPrice) {
      errors.push('Bid price required for sell RFQ');
    }
    
    if (rfq.side === 'two_way' && (!quote.bidPrice || !quote.askPrice)) {
      errors.push('Both bid and ask prices required for two-way RFQ');
    }

    // 2. Validate price sanity (bid < ask)
    if (quote.bidPrice && quote.askPrice) {
      if (Number(quote.bidPrice) >= Number(quote.askPrice)) {
        errors.push('Bid price must be less than ask price');
      }
    }

    // 3. Validate quote expiry
    const now = new Date();
    if (quote.validUntil <= now) {
      errors.push('Quote has already expired');
    }

    if (quote.validUntil > rfq.expiresAt) {
      warnings.push('Quote validity extends beyond RFQ expiry');
    }

    // 4. Validate against reference price (if available)
    if (rfq.referencePrice) {
      const tolerance = 0.1; // 10% tolerance
      const refPrice = Number(rfq.referencePrice);

      if (quote.bidPrice) {
        const bidDeviation = Math.abs(Number(quote.bidPrice) - refPrice) / refPrice;
        if (bidDeviation > tolerance) {
          warnings.push(`Bid price deviates ${(bidDeviation * 100).toFixed(1)}% from reference`);
        }
      }

      if (quote.askPrice) {
        const askDeviation = Math.abs(Number(quote.askPrice) - refPrice) / refPrice;
        if (askDeviation > tolerance) {
          warnings.push(`Ask price deviates ${(askDeviation * 100).toFixed(1)}% from reference`);
        }
      }
    }

    // 5. Validate quantity coverage
    if (quote.bidQuantity && Number(quote.bidQuantity) < Number(rfq.quantity)) {
      warnings.push('Bid quantity is less than requested');
    }
    
    if (quote.askQuantity && Number(quote.askQuantity) < Number(rfq.quantity)) {
      warnings.push('Ask quantity is less than requested');
    }

    return {
      valid: errors.length === 0,
      errors,
      warnings,
    };
  }
}
```

### Quote Stream Handler (FIX)

```typescript
// services/rfq-service/src/quote/fix-quote-handler.ts
import { Injectable } from '@nestjs/common';
import { logger } from '@orion/observability';
import { QuoteCollectionService, IncomingQuote } from './quote-collection.service';

@Injectable()
export class FixQuoteHandler {
  constructor(private readonly quoteCollectionService: QuoteCollectionService) {}

  async handleFixQuote(lpId: string, fields: Record<string, string>): Promise<void> {
    try {
      const incoming: IncomingQuote = {
        rfqId: fields['131'],         // QuoteReqID
        lpId,
        lpQuoteId: fields['117'],      // QuoteID
        bidPrice: fields['132'] ? parseFloat(fields['132']) : undefined,   // BidPx
        askPrice: fields['133'] ? parseFloat(fields['133']) : undefined,   // OfferPx
        bidQuantity: fields['134'] ? parseFloat(fields['134']) : undefined, // BidSize
        askQuantity: fields['135'] ? parseFloat(fields['135']) : undefined, // OfferSize
        validUntil: this.parseFixTimestamp(fields['126'] || fields['62']),  // ExpireTime or TransactTime
        rawMessage: fields,
      };

      await this.quoteCollectionService.receiveQuote(incoming);
    } catch (error) {
      logger.error('Failed to process FIX quote', { lpId, error });
    }
  }

  private parseFixTimestamp(timestamp: string): Date {
    // FIX timestamp format: YYYYMMDD-HH:MM:SS.sss
    if (!timestamp) {
      return new Date(Date.now() + 30000); // Default 30s validity
    }
    
    const year = parseInt(timestamp.slice(0, 4));
    const month = parseInt(timestamp.slice(4, 6)) - 1;
    const day = parseInt(timestamp.slice(6, 8));
    const time = timestamp.slice(9);
    const [hours, minutes, seconds] = time.split(':');
    
    return new Date(year, month, day, 
      parseInt(hours), parseInt(minutes), parseFloat(seconds));
  }
}
```

### Quote Expiry Watcher

```typescript
// services/rfq-service/src/quote/quote-expiry.service.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { Cron, CronExpression } from '@nestjs/schedule';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, LessThan, In } from 'typeorm';
import { logger } from '@orion/observability';
import { QuoteEntity } from '../entities/quote.entity';
import { EventEmitter2 } from '@nestjs/event-emitter';

@Injectable()
export class QuoteExpiryService {
  constructor(
    @InjectRepository(QuoteEntity)
    private readonly quoteRepository: Repository<QuoteEntity>,
    private readonly eventEmitter: EventEmitter2,
  ) {}

  @Cron(CronExpression.EVERY_SECOND)
  async expireQuotes(): Promise<void> {
    const now = new Date();
    
    const expiredQuotes = await this.quoteRepository.find({
      where: {
        status: In(['pending', 'valid']),
        validUntil: LessThan(now),
      },
    });

    if (expiredQuotes.length === 0) return;

    // Group by RFQ for re-ranking
    const rfqIds = new Set(expiredQuotes.map(q => q.rfqId));

    await this.quoteRepository.update(
      { id: In(expiredQuotes.map(q => q.id)) },
      { status: 'expired' },
    );

    logger.info('Expired quotes', { count: expiredQuotes.length });

    // Emit expiry events and trigger re-ranking
    for (const quote of expiredQuotes) {
      this.eventEmitter.emit('quote.expired', {
        quoteId: quote.id,
        rfqId: quote.rfqId,
        lpId: quote.lpId,
      });
    }

    // Re-rank affected RFQs
    for (const rfqId of rfqIds) {
      this.eventEmitter.emit('quotes.rerank', { rfqId });
    }
  }
}
```

### Quote Controller

```typescript
// services/rfq-service/src/quote/quote.controller.ts
import { Controller, Get, Param, UseGuards } from '@nestjs/common';
import { JwtAuthGuard } from '@orion/security';
import { QuoteCollectionService } from './quote-collection.service';

@Controller('rfqs/:rfqId/quotes')
@UseGuards(JwtAuthGuard)
export class QuoteController {
  constructor(private readonly quoteCollectionService: QuoteCollectionService) {}

  @Get()
  async getQuotes(@Param('rfqId') rfqId: string) {
    return this.quoteCollectionService.getQuotesForRfq(rfqId);
  }

  @Get('valid')
  async getValidQuotes(@Param('rfqId') rfqId: string) {
    return this.quoteCollectionService.getValidQuotesForRfq(rfqId);
  }

  @Get('best')
  async getBestQuote(@Param('rfqId') rfqId: string) {
    const rfq = await this.rfqService.getRfq(rfqId);
    return this.quoteCollectionService.getBestQuote(rfqId, rfq.side);
  }

  @Get('composite')
  async getCompositeQuote(@Param('rfqId') rfqId: string) {
    return this.quoteCollectionService.getCompositeQuote(rfqId);
  }
}
```

## Definition of Done

- [ ] Quote entity and schema created
- [ ] Quote reception from FIX, REST, WS
- [ ] Quote validation rules
- [ ] Best price ranking
- [ ] Composite quote calculation
- [ ] Quote expiry handling
- [ ] Real-time events

## Dependencies

- **US-07-02**: RFQ Distribution to LPs
- **US-06-04**: WebSocket Distribution

## Test Cases

```typescript
describe('QuoteCollectionService', () => {
  it('should receive and validate quote', async () => {
    const rfq = await createDistributedRfq({ side: 'buy' });
    
    const quote = await quoteCollectionService.receiveQuote({
      rfqId: rfq.id,
      lpId: 'lp-1',
      askPrice: 1.0850,
      validUntil: new Date(Date.now() + 30000),
    });

    expect(quote.status).toBe('valid');
  });

  it('should rank quotes by best price', async () => {
    const rfq = await createDistributedRfq({ side: 'buy' });
    
    await receiveQuote(rfq.id, 'lp-1', { askPrice: 1.0850 });
    await receiveQuote(rfq.id, 'lp-2', { askPrice: 1.0845 });  // Best
    await receiveQuote(rfq.id, 'lp-3', { askPrice: 1.0855 });

    const quotes = await quoteCollectionService.getValidQuotesForRfq(rfq.id);
    
    expect(quotes[0].lpId).toBe('lp-2');
    expect(quotes[0].ranking).toBe(1);
    expect(quotes[0].isBestAsk).toBe(true);
  });

  it('should reject quote with invalid pricing', async () => {
    const rfq = await createDistributedRfq({ side: 'two_way' });
    
    const quote = await quoteCollectionService.receiveQuote({
      rfqId: rfq.id,
      lpId: 'lp-1',
      bidPrice: 1.0850,
      askPrice: 1.0840,  // Ask < Bid (invalid)
      validUntil: new Date(Date.now() + 30000),
    });

    expect(quote.status).toBe('rejected');
  });
});
```
