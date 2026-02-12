# User Story: US-08-02 - Trade Capture Service

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-08-02 |
| **Epic** | Epic 08 - Trade Execution |
| **Title** | Trade Capture Service |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |
| **PRD Reference** | FR-Trade-02 |

## User Story

**As a** trader  
**I want** trades to be captured from multiple sources  
**So that** all executions are recorded accurately in the system

## Description

Implement trade capture from RFQ fills, order fills, manual entry, and external imports with unified processing pipeline and idempotency.

## Acceptance Criteria

- [ ] Capture trades from RFQ fills
- [ ] Capture trades from order fills
- [ ] Manual trade entry API
- [ ] Bulk import capability
- [ ] Idempotent processing (no duplicates)
- [ ] Trade validation on capture
- [ ] Event published on successful capture

## Technical Details

### Trade Capture Service

```typescript
// services/trade-service/src/capture/trade-capture.service.ts
import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { logger, metrics } from '@orion/observability';
import { transactionalOutbox } from '@orion/event-model';
import { getCurrentTenant, getCurrentUser } from '@orion/security';
import { TradeEntity, TradeSource } from '../entities/trade.entity';
import { TradeRepository } from '../repositories/trade.repository';
import { TradeValidationService } from '../validation/trade-validation.service';
import { TradeEnrichmentService } from '../enrichment/trade-enrichment.service';
import { CreateTradeDto, TradeResponseDto } from '../dto/trade.dto';

export interface CaptureResult {
  success: boolean;
  trade?: TradeEntity;
  errors?: string[];
}

export interface RfqTradeInput {
  rfqId: string;
  quoteId: string;
  lpId: string;
  symbol: string;
  side: 'buy' | 'sell';
  quantity: number;
  price: number;
  settlementDate: Date;
  lpConfirmationId?: string;
}

export interface OrderTradeInput {
  orderId: string;
  executionId: string;
  symbol: string;
  side: 'buy' | 'sell';
  quantity: number;
  price: number;
  venue: string;
  venueOrderId?: string;
}

@Injectable()
export class TradeCaptureService {
  constructor(
    private readonly tradeRepository: TradeRepository,
    private readonly validationService: TradeValidationService,
    private readonly enrichmentService: TradeEnrichmentService,
    @InjectRepository(TradeEntity)
    private readonly entityRepository: Repository<TradeEntity>,
  ) {}

  async captureFromRfq(input: RfqTradeInput, userId: string): Promise<CaptureResult> {
    const startTime = Date.now();

    // Check for duplicate (idempotency)
    const existing = await this.tradeRepository.findBySourceId('rfq', input.rfqId);
    if (existing) {
      logger.info('Duplicate RFQ trade detected', { rfqId: input.rfqId, tradeId: existing.id });
      return { success: true, trade: existing };
    }

    try {
      // Enrich trade data
      const enriched = await this.enrichmentService.enrichFromRfq(input);

      // Create trade
      const trade = await this.createTrade({
        sourceType: 'rfq',
        sourceId: input.rfqId,
        externalRef: input.lpConfirmationId,
        ...enriched,
      }, userId);

      metrics.timing('trade.capture.rfq', Date.now() - startTime);
      metrics.increment('trade.captured', { source: 'rfq' });

      return { success: true, trade };
    } catch (error) {
      logger.error('RFQ trade capture failed', { rfqId: input.rfqId, error });
      return { success: false, errors: [error.message] };
    }
  }

  async captureFromOrder(input: OrderTradeInput, userId: string): Promise<CaptureResult> {
    const startTime = Date.now();

    // Check for duplicate using execution ID
    const existing = await this.tradeRepository.findBySourceId('order', input.executionId);
    if (existing) {
      logger.info('Duplicate order trade detected', { executionId: input.executionId });
      return { success: true, trade: existing };
    }

    try {
      const enriched = await this.enrichmentService.enrichFromOrder(input);

      const trade = await this.createTrade({
        sourceType: 'order',
        sourceId: input.executionId,
        externalRef: input.venueOrderId,
        executionVenue: input.venue,
        ...enriched,
      }, userId);

      metrics.timing('trade.capture.order', Date.now() - startTime);
      metrics.increment('trade.captured', { source: 'order' });

      return { success: true, trade };
    } catch (error) {
      logger.error('Order trade capture failed', { executionId: input.executionId, error });
      return { success: false, errors: [error.message] };
    }
  }

  async captureManual(dto: CreateTradeDto, userId: string): Promise<CaptureResult> {
    const startTime = Date.now();

    try {
      // Validate manual trade input
      const validation = await this.validationService.validateCreateDto(dto);
      if (!validation.valid) {
        return { success: false, errors: validation.errors };
      }

      // Enrich
      const enriched = await this.enrichmentService.enrichManualTrade(dto);

      const trade = await this.createTrade({
        sourceType: 'manual',
        executionTime: dto.executionTime ? new Date(dto.executionTime) : new Date(),
        ...enriched,
      }, userId);

      metrics.timing('trade.capture.manual', Date.now() - startTime);
      metrics.increment('trade.captured', { source: 'manual' });

      return { success: true, trade };
    } catch (error) {
      logger.error('Manual trade capture failed', { error });
      return { success: false, errors: [error.message] };
    }
  }

  async bulkImport(
    trades: CreateTradeDto[], 
    userId: string,
  ): Promise<{
    successful: TradeEntity[];
    failed: { index: number; errors: string[] }[];
  }> {
    const results = {
      successful: [] as TradeEntity[],
      failed: [] as { index: number; errors: string[] }[],
    };

    for (let i = 0; i < trades.length; i++) {
      const dto = trades[i];
      
      try {
        const result = await this.captureFromImport(dto, userId);
        
        if (result.success && result.trade) {
          results.successful.push(result.trade);
        } else {
          results.failed.push({ index: i, errors: result.errors || ['Unknown error'] });
        }
      } catch (error) {
        results.failed.push({ index: i, errors: [error.message] });
      }
    }

    logger.info('Bulk import completed', {
      total: trades.length,
      successful: results.successful.length,
      failed: results.failed.length,
    });

    metrics.increment('trade.import.batch', { 
      successful: results.successful.length.toString(),
      failed: results.failed.length.toString(),
    });

    return results;
  }

  private async captureFromImport(dto: CreateTradeDto, userId: string): Promise<CaptureResult> {
    // Check for duplicate by external ref
    if (dto.externalRef) {
      const existing = await this.entityRepository.findOne({
        where: { externalRef: dto.externalRef, sourceType: 'import' as any },
      });
      if (existing) {
        return { success: true, trade: existing };
      }
    }

    const validation = await this.validationService.validateCreateDto(dto);
    if (!validation.valid) {
      return { success: false, errors: validation.errors };
    }

    const enriched = await this.enrichmentService.enrichManualTrade(dto);

    const trade = await this.createTrade({
      sourceType: 'import',
      ...enriched,
    }, userId);

    return { success: true, trade };
  }

  private async createTrade(
    data: Partial<TradeEntity>,
    userId: string,
  ): Promise<TradeEntity> {
    const tenant = getCurrentTenant();
    const user = getCurrentUser();

    // Calculate notional
    const notionalAmount = Number(data.quantity) * Number(data.price);

    // Calculate total fees
    const totalFees = (data.fees || []).reduce((sum, fee) => sum + Number(fee.amount), 0);

    const trade = await transactionalOutbox(
      this.entityRepository.manager,
      async (manager) => {
        const entity = this.entityRepository.create({
          ...data,
          tenantId: tenant.tenantId,
          notionalAmount,
          totalFees,
          status: 'pending',
          traderId: userId,
          traderName: user?.name || 'Unknown',
          createdBy: userId,
        });

        // Generate trade ref
        const result = await manager.query(
          'SELECT generate_trade_ref($1) as trade_ref',
          [tenant.tenantId],
        );
        entity.tradeRef = result[0].trade_ref;

        return manager.save(entity);
      },
      {
        topic: 'orion.events.trade',
        eventType: 'trade.captured',
        aggregateType: 'trade',
        aggregateId: data.sourceId || 'new',
        payload: {
          tradeRef: data.tradeRef,
          sourceType: data.sourceType,
          symbol: data.symbol,
          side: data.side,
          quantity: data.quantity,
          price: data.price,
        },
      },
    );

    logger.info('Trade captured', {
      tradeId: trade.id,
      tradeRef: trade.tradeRef,
      sourceType: trade.sourceType,
    });

    return trade;
  }
}
```

### Trade Capture Event Handler

```typescript
// services/trade-service/src/capture/capture-event.handler.ts
import { Injectable } from '@nestjs/common';
import { OnEvent } from '@orion/event-model';
import { logger } from '@orion/observability';
import { TradeCaptureService } from './trade-capture.service';

@Injectable()
export class CaptureEventHandler {
  constructor(private readonly captureService: TradeCaptureService) {}

  @OnEvent('rfq.filled')
  async handleRfqFilled(event: {
    rfqId: string;
    quoteId: string;
    lpId: string;
    symbol: string;
    side: 'buy' | 'sell';
    quantity: number;
    price: number;
    settlementDate: string;
    traderId: string;
    lpConfirmationId?: string;
  }): Promise<void> {
    logger.info('Processing RFQ fill for trade capture', { rfqId: event.rfqId });

    const result = await this.captureService.captureFromRfq({
      rfqId: event.rfqId,
      quoteId: event.quoteId,
      lpId: event.lpId,
      symbol: event.symbol,
      side: event.side,
      quantity: event.quantity,
      price: event.price,
      settlementDate: new Date(event.settlementDate),
      lpConfirmationId: event.lpConfirmationId,
    }, event.traderId);

    if (!result.success) {
      logger.error('Failed to capture trade from RFQ', {
        rfqId: event.rfqId,
        errors: result.errors,
      });
    }
  }

  @OnEvent('order.filled')
  async handleOrderFilled(event: {
    orderId: string;
    executionId: string;
    symbol: string;
    side: 'buy' | 'sell';
    quantity: number;
    price: number;
    venue: string;
    venueOrderId?: string;
    traderId: string;
  }): Promise<void> {
    logger.info('Processing order fill for trade capture', { orderId: event.orderId });

    const result = await this.captureService.captureFromOrder({
      orderId: event.orderId,
      executionId: event.executionId,
      symbol: event.symbol,
      side: event.side,
      quantity: event.quantity,
      price: event.price,
      venue: event.venue,
      venueOrderId: event.venueOrderId,
    }, event.traderId);

    if (!result.success) {
      logger.error('Failed to capture trade from order', {
        orderId: event.orderId,
        errors: result.errors,
      });
    }
  }
}
```

### Trade Capture Controller

```typescript
// services/trade-service/src/capture/capture.controller.ts
import { 
  Controller, Post, Body, UseGuards, 
  HttpException, HttpStatus, UploadedFile, UseInterceptors 
} from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import { JwtAuthGuard, CurrentUser, User, RequirePermissions, Permission } from '@orion/security';
import { TradeCaptureService } from './trade-capture.service';
import { CreateTradeDto } from '../dto/trade.dto';
import { TradeImportParser } from './trade-import.parser';

@Controller('trades')
@UseGuards(JwtAuthGuard)
export class CaptureController {
  constructor(
    private readonly captureService: TradeCaptureService,
    private readonly importParser: TradeImportParser,
  ) {}

  @Post()
  @RequirePermissions(Permission.TRADE_CREATE)
  async createTrade(
    @Body() dto: CreateTradeDto,
    @CurrentUser() user: User,
  ) {
    const result = await this.captureService.captureManual(dto, user.id);
    
    if (!result.success) {
      throw new HttpException(
        { errors: result.errors },
        HttpStatus.BAD_REQUEST,
      );
    }

    return result.trade;
  }

  @Post('import')
  @RequirePermissions(Permission.TRADE_IMPORT)
  @UseInterceptors(FileInterceptor('file'))
  async importTrades(
    @UploadedFile() file: Express.Multer.File,
    @CurrentUser() user: User,
  ) {
    // Parse file (CSV or Excel)
    const trades = await this.importParser.parse(file);
    
    // Bulk import
    const results = await this.captureService.bulkImport(trades, user.id);

    return {
      total: trades.length,
      successful: results.successful.length,
      failed: results.failed,
    };
  }

  @Post('bulk')
  @RequirePermissions(Permission.TRADE_CREATE)
  async createBulkTrades(
    @Body() trades: CreateTradeDto[],
    @CurrentUser() user: User,
  ) {
    const results = await this.captureService.bulkImport(trades, user.id);

    return {
      total: trades.length,
      successful: results.successful.length,
      failed: results.failed,
    };
  }
}
```

### Trade Import Parser

```typescript
// services/trade-service/src/capture/trade-import.parser.ts
import { Injectable } from '@nestjs/common';
import * as XLSX from 'xlsx';
import { parse as csvParse } from 'csv-parse/sync';
import { CreateTradeDto } from '../dto/trade.dto';

@Injectable()
export class TradeImportParser {
  async parse(file: Express.Multer.File): Promise<CreateTradeDto[]> {
    const extension = file.originalname.split('.').pop()?.toLowerCase();

    switch (extension) {
      case 'csv':
        return this.parseCSV(file.buffer);
      case 'xlsx':
      case 'xls':
        return this.parseExcel(file.buffer);
      default:
        throw new Error(`Unsupported file format: ${extension}`);
    }
  }

  private parseCSV(buffer: Buffer): CreateTradeDto[] {
    const records = csvParse(buffer, {
      columns: true,
      skip_empty_lines: true,
      trim: true,
    });

    return records.map(this.mapRecord);
  }

  private parseExcel(buffer: Buffer): CreateTradeDto[] {
    const workbook = XLSX.read(buffer, { type: 'buffer' });
    const sheetName = workbook.SheetNames[0];
    const sheet = workbook.Sheets[sheetName];
    const records = XLSX.utils.sheet_to_json(sheet);

    return records.map(this.mapRecord);
  }

  private mapRecord(record: any): CreateTradeDto {
    return {
      sourceType: 'import',
      externalRef: record.external_ref || record.externalRef,
      clientId: record.client_id || record.clientId,
      counterpartyId: record.counterparty_id || record.counterpartyId,
      instrumentId: record.instrument_id || record.instrumentId,
      side: (record.side || '').toLowerCase() as 'buy' | 'sell',
      quantity: parseFloat(record.quantity),
      price: parseFloat(record.price),
      currency: record.currency || 'USD',
      settlementCurrency: record.settlement_currency || record.currency || 'USD',
      tradeDate: record.trade_date || record.tradeDate,
      settlementDate: record.settlement_date || record.settlementDate,
      executionTime: record.execution_time || record.executionTime,
      commission: record.commission ? parseFloat(record.commission) : undefined,
      commissionCurrency: record.commission_currency,
      executionVenue: record.execution_venue || record.venue,
    };
  }
}
```

## Definition of Done

- [ ] RFQ fill trade capture
- [ ] Order fill trade capture
- [ ] Manual trade entry API
- [ ] Bulk import (CSV, Excel)
- [ ] Idempotency checks
- [ ] Event publishing
- [ ] Integration tests

## Dependencies

- **US-08-01**: Trade Entity and Repository
- **US-07-04**: Quote Execution (RFQ fills)

## Test Cases

```typescript
describe('TradeCaptureService', () => {
  it('should capture trade from RFQ fill', async () => {
    const result = await captureService.captureFromRfq({
      rfqId: 'rfq-1',
      quoteId: 'quote-1',
      lpId: 'lp-1',
      symbol: 'EUR/USD',
      side: 'buy',
      quantity: 1000000,
      price: 1.0850,
      settlementDate: new Date(),
    }, 'trader-1');

    expect(result.success).toBe(true);
    expect(result.trade?.sourceType).toBe('rfq');
    expect(result.trade?.sourceId).toBe('rfq-1');
  });

  it('should prevent duplicate RFQ trades', async () => {
    const input = {
      rfqId: 'rfq-1',
      quoteId: 'quote-1',
      lpId: 'lp-1',
      symbol: 'EUR/USD',
      side: 'buy' as const,
      quantity: 1000000,
      price: 1.0850,
      settlementDate: new Date(),
    };

    const first = await captureService.captureFromRfq(input, 'trader-1');
    const second = await captureService.captureFromRfq(input, 'trader-1');

    expect(first.trade?.id).toBe(second.trade?.id);
  });

  it('should bulk import trades', async () => {
    const trades = [
      createTradeDto({ externalRef: 'ext-1' }),
      createTradeDto({ externalRef: 'ext-2' }),
      createTradeDto({ externalRef: 'ext-3' }),
    ];

    const results = await captureService.bulkImport(trades, 'trader-1');
    
    expect(results.successful.length).toBe(3);
    expect(results.failed.length).toBe(0);
  });
});
```
