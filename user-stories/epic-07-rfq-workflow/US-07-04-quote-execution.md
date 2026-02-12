# User Story: US-07-04 - Quote Selection and Execution

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-07-04 |
| **Epic** | Epic 07 - RFQ Workflow |
| **Title** | Quote Selection and Execution |
| **Priority** | P0 - Critical |
| **Story Points** | 8 |
| **PRD Reference** | FR-RFQ-04 |

## User Story

**As a** trader  
**I want** to select a quote and execute the trade  
**So that** I can complete my trading objective at the chosen price

## Description

Implement quote selection flow with validation, execution request to LP, trade creation, and state transitions for both successful and failed executions.

## Acceptance Criteria

- [ ] Quote selection with validation
- [ ] Optimistic locking to prevent race conditions
- [ ] Execution request sent to winning LP
- [ ] Execution confirmation handling
- [ ] Trade record creation on success
- [ ] Rollback on execution failure
- [ ] RFQ status updated to EXECUTED/FILLED

## Technical Details

### Execution State Machine

```
┌────────────────────────────────────────────────────────────────────────┐
│                        QUOTE EXECUTION FLOW                            │
├────────────────────────────────────────────────────────────────────────┤
│                                                                        │
│   [VALID QUOTE]                                                        │
│        │                                                               │
│        ▼                                                               │
│   ┌─────────┐                                                          │
│   │ SELECT  │ ── trader clicks execute ──▶ Validate quote still valid  │
│   └────┬────┘                                                          │
│        │                                                               │
│        ▼                                                               │
│   ┌─────────┐                                                          │
│   │ LOCK    │ ── optimistic lock quote ──▶ Prevent concurrent select   │
│   └────┬────┘                                                          │
│        │                                                               │
│        ▼                                                               │
│   ┌─────────┐                                                          │
│   │ EXECUTE │ ── send to LP gateway ────▶ Wait for confirmation        │
│   └────┬────┘                                                          │
│        │                                                               │
│   ┌────┴────────────────┐                                              │
│   │                     │                                              │
│   ▼                     ▼                                              │
│ SUCCESS              FAILURE                                           │
│   │                     │                                              │
│   ▼                     ▼                                              │
│ Create Trade       Unlock Quote                                        │
│ RFQ → FILLED       RFQ → Retry/Expire                                  │
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘
```

### Execution DTOs

```typescript
// services/rfq-service/src/dto/execute-quote.dto.ts
import { IsUUID, IsNumber, IsOptional, IsString, Min } from 'class-validator';

export class ExecuteQuoteDto {
  @IsUUID()
  quoteId: string;

  @IsOptional()
  @IsNumber()
  @Min(0)
  overrideQuantity?: number;

  @IsOptional()
  @IsString()
  executionNote?: string;
}

export class ExecutionResultDto {
  success: boolean;
  tradeId?: string;
  executionPrice: number;
  executionQuantity: number;
  executionTime: Date;
  lpConfirmationId?: string;
  rejectionReason?: string;
}
```

### Quote Selection Service

```typescript
// services/rfq-service/src/execution/quote-selection.service.ts
import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, DataSource } from 'typeorm';
import { logger, metrics } from '@orion/observability';
import { transactionalOutbox } from '@orion/event-model';
import { QuoteEntity } from '../entities/quote.entity';
import { RfqEntity } from '../entities/rfq.entity';
import { ExecuteQuoteDto, ExecutionResultDto } from '../dto/execute-quote.dto';
import { LpExecutionGateway } from '../gateway/lp-execution.gateway';
import { TradeService } from '../../trade/trade.service';

export class QuoteSelectionError extends Error {
  constructor(
    public readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = 'QuoteSelectionError';
  }
}

@Injectable()
export class QuoteSelectionService {
  constructor(
    @InjectRepository(QuoteEntity)
    private readonly quoteRepository: Repository<QuoteEntity>,
    @InjectRepository(RfqEntity)
    private readonly rfqRepository: Repository<RfqEntity>,
    private readonly dataSource: DataSource,
    private readonly executionGateway: LpExecutionGateway,
    private readonly tradeService: TradeService,
  ) {}

  async selectAndExecute(
    rfqId: string,
    dto: ExecuteQuoteDto,
    userId: string,
  ): Promise<ExecutionResultDto> {
    const startTime = Date.now();

    // Use transaction with optimistic locking
    const queryRunner = this.dataSource.createQueryRunner();
    await queryRunner.connect();
    await queryRunner.startTransaction('SERIALIZABLE');

    try {
      // 1. Lock and validate quote
      const quote = await queryRunner.manager.findOne(QuoteEntity, {
        where: { id: dto.quoteId },
        lock: { mode: 'pessimistic_write' },
      });

      if (!quote) {
        throw new QuoteSelectionError('QUOTE_NOT_FOUND', 'Quote not found');
      }

      // 2. Validate quote state
      this.validateQuoteForExecution(quote);

      // 3. Get and validate RFQ
      const rfq = await queryRunner.manager.findOne(RfqEntity, {
        where: { id: quote.rfqId },
        lock: { mode: 'pessimistic_write' },
      });

      if (!rfq) {
        throw new QuoteSelectionError('RFQ_NOT_FOUND', 'RFQ not found');
      }

      this.validateRfqForExecution(rfq);

      // 4. Mark quote as selected (prevent other selections)
      await queryRunner.manager.update(QuoteEntity, dto.quoteId, {
        status: 'selected',
      });

      // 5. Update RFQ status
      await queryRunner.manager.update(RfqEntity, rfqId, {
        status: 'executed',
        selectedQuoteId: dto.quoteId,
        updatedAt: new Date(),
      });

      // 6. Mark other quotes as not selected
      await queryRunner.manager
        .createQueryBuilder()
        .update(QuoteEntity)
        .set({ status: 'rejected' })
        .where('rfq_id = :rfqId', { rfqId })
        .andWhere('id != :quoteId', { quoteId: dto.quoteId })
        .andWhere('status IN (:...statuses)', { statuses: ['valid', 'pending'] })
        .execute();

      // Commit the selection
      await queryRunner.commitTransaction();

      // 7. Send execution to LP (outside transaction)
      const executionResult = await this.executeWithLP(
        rfq,
        quote,
        dto.overrideQuantity || Number(rfq.quantity),
        userId,
      );

      // 8. Handle execution result
      if (executionResult.success) {
        await this.handleSuccessfulExecution(rfq, quote, executionResult, userId);
        metrics.increment('rfq.executed', { status: 'success' });
      } else {
        await this.handleFailedExecution(rfq, quote, executionResult.rejectionReason);
        metrics.increment('rfq.executed', { status: 'failed' });
      }

      metrics.timing('rfq.execution_time', Date.now() - startTime);
      
      return executionResult;

    } catch (error) {
      await queryRunner.rollbackTransaction();
      logger.error('Quote selection failed', { rfqId, quoteId: dto.quoteId, error });
      throw error;
    } finally {
      await queryRunner.release();
    }
  }

  private validateQuoteForExecution(quote: QuoteEntity): void {
    const now = new Date();

    if (quote.status !== 'valid') {
      throw new QuoteSelectionError(
        'QUOTE_NOT_VALID',
        `Quote is in state ${quote.status}, expected valid`,
      );
    }

    if (quote.validUntil < now) {
      throw new QuoteSelectionError(
        'QUOTE_EXPIRED',
        'Quote has expired',
      );
    }
  }

  private validateRfqForExecution(rfq: RfqEntity): void {
    const now = new Date();

    if (!['quoted', 'distributed'].includes(rfq.status)) {
      throw new QuoteSelectionError(
        'RFQ_NOT_EXECUTABLE',
        `RFQ is in state ${rfq.status}, expected quoted`,
      );
    }

    if (rfq.expiresAt < now) {
      throw new QuoteSelectionError(
        'RFQ_EXPIRED',
        'RFQ has expired',
      );
    }
  }

  private async executeWithLP(
    rfq: RfqEntity,
    quote: QuoteEntity,
    quantity: number,
    userId: string,
  ): Promise<ExecutionResultDto> {
    const executionPrice = rfq.side === 'buy' 
      ? quote.askPrice 
      : quote.bidPrice;

    try {
      const lpResponse = await this.executionGateway.execute({
        rfqId: rfq.id,
        quoteId: quote.id,
        lpId: quote.lpId,
        lpQuoteId: quote.lpQuoteId,
        symbol: rfq.symbol,
        side: rfq.side,
        quantity,
        price: Number(executionPrice),
        settlementDate: rfq.settlementDate!,
      });

      if (lpResponse.status === 'filled') {
        return {
          success: true,
          executionPrice: lpResponse.fillPrice || Number(executionPrice),
          executionQuantity: lpResponse.fillQuantity || quantity,
          executionTime: lpResponse.executionTime || new Date(),
          lpConfirmationId: lpResponse.confirmationId,
        };
      } else {
        return {
          success: false,
          executionPrice: Number(executionPrice),
          executionQuantity: quantity,
          executionTime: new Date(),
          rejectionReason: lpResponse.rejectionReason || 'Execution failed',
        };
      }
    } catch (error) {
      logger.error('LP execution request failed', { rfqId: rfq.id, error });
      return {
        success: false,
        executionPrice: Number(executionPrice),
        executionQuantity: quantity,
        executionTime: new Date(),
        rejectionReason: error.message,
      };
    }
  }

  private async handleSuccessfulExecution(
    rfq: RfqEntity,
    quote: QuoteEntity,
    result: ExecutionResultDto,
    userId: string,
  ): Promise<void> {
    // Create trade
    const trade = await this.tradeService.createFromRfq({
      rfqId: rfq.id,
      quoteId: quote.id,
      tenantId: rfq.tenantId,
      clientId: rfq.clientId,
      traderId: rfq.traderId,
      instrumentId: rfq.instrumentId,
      symbol: rfq.symbol,
      side: rfq.side === 'two_way' ? 'buy' : rfq.side, // Resolve two-way
      quantity: result.executionQuantity,
      price: result.executionPrice,
      lpId: quote.lpId,
      lpConfirmationId: result.lpConfirmationId,
      settlementDate: rfq.settlementDate,
      executedBy: userId,
    });

    // Update RFQ to filled
    await transactionalOutbox(
      this.rfqRepository.manager,
      async (manager) => {
        await manager.update(RfqEntity, rfq.id, {
          status: 'filled',
          executedTradeId: trade.id,
          updatedAt: new Date(),
        });

        await manager.update(QuoteEntity, quote.id, {
          status: 'executed',
        });
      },
      {
        topic: 'orion.events.rfq',
        eventType: 'rfq.filled',
        aggregateType: 'rfq',
        aggregateId: rfq.id,
        payload: {
          rfqId: rfq.id,
          tradeId: trade.id,
          quoteId: quote.id,
          lpId: quote.lpId,
          price: result.executionPrice,
          quantity: result.executionQuantity,
        },
      },
    );

    logger.info('RFQ filled', {
      rfqId: rfq.id,
      tradeId: trade.id,
      price: result.executionPrice,
    });
  }

  private async handleFailedExecution(
    rfq: RfqEntity,
    quote: QuoteEntity,
    reason?: string,
  ): Promise<void> {
    // Rollback quote status to valid (if not expired)
    const now = new Date();
    const newQuoteStatus = quote.validUntil > now ? 'valid' : 'expired';

    // Determine RFQ status
    const validQuotes = await this.quoteRepository.count({
      where: { rfqId: rfq.id, status: 'valid' },
    });

    const newRfqStatus = validQuotes > 0 ? 'quoted' : 'distributed';

    await transactionalOutbox(
      this.rfqRepository.manager,
      async (manager) => {
        await manager.update(RfqEntity, rfq.id, {
          status: newRfqStatus,
          selectedQuoteId: null,
          updatedAt: new Date(),
        });

        await manager.update(QuoteEntity, quote.id, {
          status: newQuoteStatus,
        });
      },
      {
        topic: 'orion.events.rfq',
        eventType: 'rfq.execution_failed',
        aggregateType: 'rfq',
        aggregateId: rfq.id,
        payload: {
          rfqId: rfq.id,
          quoteId: quote.id,
          lpId: quote.lpId,
          reason,
        },
      },
    );

    logger.warn('RFQ execution failed', { rfqId: rfq.id, reason });
  }
}
```

### LP Execution Gateway

```typescript
// services/rfq-service/src/gateway/lp-execution.gateway.ts
import { Injectable } from '@nestjs/common';
import { logger, metrics } from '@orion/observability';
import { FixGateway } from './fix-gateway';
import { RestGateway } from './rest-gateway';

export interface ExecutionRequest {
  rfqId: string;
  quoteId: string;
  lpId: string;
  lpQuoteId?: string;
  symbol: string;
  side: string;
  quantity: number;
  price: number;
  settlementDate: Date;
}

export interface ExecutionResponse {
  status: 'filled' | 'rejected' | 'pending';
  confirmationId?: string;
  fillPrice?: number;
  fillQuantity?: number;
  executionTime?: Date;
  rejectionReason?: string;
}

@Injectable()
export class LpExecutionGateway {
  private lpProtocols: Map<string, string> = new Map();

  constructor(
    private readonly fixGateway: FixGateway,
    private readonly restGateway: RestGateway,
  ) {}

  setLpProtocol(lpId: string, protocol: string): void {
    this.lpProtocols.set(lpId, protocol);
  }

  async execute(request: ExecutionRequest): Promise<ExecutionResponse> {
    const protocol = this.lpProtocols.get(request.lpId) || 'rest';
    const startTime = Date.now();

    try {
      let response: ExecutionResponse;

      switch (protocol) {
        case 'fix':
          response = await this.executeViaFix(request);
          break;
        case 'rest':
          response = await this.executeViaRest(request);
          break;
        default:
          throw new Error(`Unknown protocol: ${protocol}`);
      }

      metrics.timing('lp.execution_time', Date.now() - startTime, {
        lpId: request.lpId,
        protocol,
        status: response.status,
      });

      return response;
    } catch (error) {
      metrics.increment('lp.execution_error', { lpId: request.lpId, protocol });
      throw error;
    }
  }

  private async executeViaFix(request: ExecutionRequest): Promise<ExecutionResponse> {
    // Send FIX Order Single (D) message
    const fixMessage = this.buildNewOrderSingle(request);
    
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        reject(new Error('FIX execution timeout'));
      }, 10000);

      // Set up response handler
      this.fixGateway.onExecutionReport(request.quoteId, (report) => {
        clearTimeout(timeout);
        
        if (report.ordStatus === '2') { // Filled
          resolve({
            status: 'filled',
            confirmationId: report.execId,
            fillPrice: parseFloat(report.lastPx),
            fillQuantity: parseFloat(report.lastQty),
            executionTime: new Date(),
          });
        } else if (report.ordStatus === '8') { // Rejected
          resolve({
            status: 'rejected',
            rejectionReason: report.text,
          });
        }
      });

      // Send order
      this.fixGateway.sendRawMessage(request.lpId, fixMessage);
    });
  }

  private buildNewOrderSingle(request: ExecutionRequest): string {
    // FIX 4.4 New Order Single (D)
    const side = request.side === 'buy' ? '1' : '2';
    const ordType = '2'; // Limit
    
    return [
      '35=D',                           // MsgType = New Order Single
      `11=${request.quoteId}`,          // ClOrdID
      `117=${request.lpQuoteId}`,       // QuoteID
      `55=${request.symbol}`,           // Symbol
      `54=${side}`,                     // Side
      `60=${this.formatTimestamp()}`,   // TransactTime
      `38=${request.quantity}`,         // OrderQty
      `40=${ordType}`,                  // OrdType = Limit
      `44=${request.price}`,            // Price
      `59=4`,                           // TimeInForce = FOK
      `64=${this.formatDate(request.settlementDate)}`, // SettlDate
    ].join('\x01');
  }

  private async executeViaRest(request: ExecutionRequest): Promise<ExecutionResponse> {
    const response = await this.restGateway.post(request.lpId, '/api/v1/execute', {
      rfqId: request.rfqId,
      quoteId: request.lpQuoteId,
      symbol: request.symbol,
      side: request.side,
      quantity: request.quantity,
      price: request.price,
      settlementDate: request.settlementDate.toISOString(),
    });

    return {
      status: response.data.status === 'filled' ? 'filled' : 'rejected',
      confirmationId: response.data.confirmationId,
      fillPrice: response.data.fillPrice,
      fillQuantity: response.data.fillQuantity,
      executionTime: response.data.executionTime 
        ? new Date(response.data.executionTime) 
        : new Date(),
      rejectionReason: response.data.rejectionReason,
    };
  }

  private formatTimestamp(): string {
    return new Date().toISOString().replace(/[-:]/g, '').replace('T', '-').slice(0, 17);
  }

  private formatDate(date: Date): string {
    return date.toISOString().slice(0, 10).replace(/-/g, '');
  }
}
```

### Execution Controller

```typescript
// services/rfq-service/src/execution/execution.controller.ts
import { Controller, Post, Param, Body, UseGuards, HttpException, HttpStatus } from '@nestjs/common';
import { JwtAuthGuard, CurrentUser, User, Permission, RequirePermissions } from '@orion/security';
import { QuoteSelectionService, QuoteSelectionError } from './quote-selection.service';
import { ExecuteQuoteDto, ExecutionResultDto } from '../dto/execute-quote.dto';

@Controller('rfqs/:rfqId')
@UseGuards(JwtAuthGuard)
export class ExecutionController {
  constructor(private readonly quoteSelectionService: QuoteSelectionService) {}

  @Post('execute')
  @RequirePermissions(Permission.RFQ_EXECUTE)
  async executeQuote(
    @Param('rfqId') rfqId: string,
    @Body() dto: ExecuteQuoteDto,
    @CurrentUser() user: User,
  ): Promise<ExecutionResultDto> {
    try {
      return await this.quoteSelectionService.selectAndExecute(rfqId, dto, user.id);
    } catch (error) {
      if (error instanceof QuoteSelectionError) {
        throw new HttpException(
          { code: error.code, message: error.message },
          this.mapErrorCode(error.code),
        );
      }
      throw error;
    }
  }

  private mapErrorCode(code: string): HttpStatus {
    switch (code) {
      case 'QUOTE_NOT_FOUND':
      case 'RFQ_NOT_FOUND':
        return HttpStatus.NOT_FOUND;
      case 'QUOTE_EXPIRED':
      case 'RFQ_EXPIRED':
        return HttpStatus.GONE;
      case 'QUOTE_NOT_VALID':
      case 'RFQ_NOT_EXECUTABLE':
        return HttpStatus.CONFLICT;
      default:
        return HttpStatus.BAD_REQUEST;
    }
  }
}
```

## Definition of Done

- [ ] Quote selection with optimistic locking
- [ ] LP execution gateway (FIX, REST)
- [ ] Trade creation on success
- [ ] State rollback on failure
- [ ] Concurrent selection prevention
- [ ] Integration tests

## Dependencies

- **US-07-03**: Quote Collection and Ranking
- **US-08-01**: Trade Entity (placeholder)

## Test Cases

```typescript
describe('QuoteSelectionService', () => {
  it('should execute valid quote', async () => {
    const rfq = await createQuotedRfq();
    const quote = await createValidQuote(rfq.id, 'lp-1', { askPrice: 1.0850 });

    const result = await quoteSelectionService.selectAndExecute(
      rfq.id,
      { quoteId: quote.id },
      'trader-1',
    );

    expect(result.success).toBe(true);
    expect(result.tradeId).toBeDefined();
  });

  it('should reject expired quote', async () => {
    const rfq = await createQuotedRfq();
    const quote = await createExpiredQuote(rfq.id, 'lp-1');

    await expect(
      quoteSelectionService.selectAndExecute(rfq.id, { quoteId: quote.id }, 'trader-1')
    ).rejects.toThrow(QuoteSelectionError);
  });

  it('should prevent concurrent selection', async () => {
    const rfq = await createQuotedRfq();
    const quote = await createValidQuote(rfq.id, 'lp-1', { askPrice: 1.0850 });

    // Simulate concurrent execution
    const results = await Promise.allSettled([
      quoteSelectionService.selectAndExecute(rfq.id, { quoteId: quote.id }, 'trader-1'),
      quoteSelectionService.selectAndExecute(rfq.id, { quoteId: quote.id }, 'trader-2'),
    ]);

    const successes = results.filter(r => r.status === 'fulfilled');
    expect(successes.length).toBe(1);
  });
});
```
