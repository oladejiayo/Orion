# User Story: US-07-01 - RFQ Creation and Validation

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-07-01 |
| **Epic** | Epic 07 - RFQ Workflow |
| **Title** | RFQ Creation and Validation |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |
| **PRD Reference** | FR-RFQ-01 |

## User Story

**As a** trader  
**I want** to create an RFQ for a specific instrument and quantity  
**So that** I can receive competitive quotes from liquidity providers

## Description

Implement RFQ creation with comprehensive validation including instrument eligibility, quantity limits, settlement terms, and pre-trade risk checks.

## Acceptance Criteria

- [ ] RFQ creation API with required fields
- [ ] Instrument validation (exists, tradeable)
- [ ] Quantity validation (min/max, lot sizes)
- [ ] Settlement date validation
- [ ] Pre-trade risk checks integration
- [ ] RFQ stored with PENDING status
- [ ] RFQ creation event published

## Technical Details

### Database Schema

```sql
-- migrations/20240119_create_rfq_tables.sql

CREATE TYPE rfq_status AS ENUM (
    'pending',
    'validated',
    'distributed',
    'quoted',
    'executed',
    'filled',
    'expired',
    'cancelled',
    'rejected'
);

CREATE TYPE rfq_side AS ENUM ('buy', 'sell', 'two_way');

CREATE TABLE rfqs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    client_id UUID NOT NULL,
    trader_id UUID NOT NULL,
    
    -- Instrument
    instrument_id UUID NOT NULL REFERENCES instruments(id),
    symbol VARCHAR(50) NOT NULL,
    asset_class VARCHAR(20) NOT NULL,
    
    -- Request details
    side rfq_side NOT NULL,
    quantity NUMERIC(20, 8) NOT NULL,
    notional_currency VARCHAR(3),
    notional_amount NUMERIC(20, 2),
    
    -- Settlement
    settlement_date DATE,
    settlement_type VARCHAR(20),
    value_date DATE,
    
    -- Timing
    timeout_seconds INTEGER NOT NULL DEFAULT 30,
    expires_at TIMESTAMPTZ NOT NULL,
    
    -- State
    status rfq_status NOT NULL DEFAULT 'pending',
    rejection_reason TEXT,
    
    -- Selected execution
    selected_quote_id UUID,
    executed_trade_id UUID,
    
    -- Metadata
    reference_price NUMERIC(20, 8),
    notes TEXT,
    tags JSONB DEFAULT '[]',
    
    -- Audit
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID NOT NULL,
    
    -- RLS
    CONSTRAINT fk_tenant FOREIGN KEY (tenant_id) 
        REFERENCES tenants(id) ON DELETE RESTRICT
);

-- Indexes
CREATE INDEX idx_rfqs_tenant_status ON rfqs(tenant_id, status);
CREATE INDEX idx_rfqs_trader ON rfqs(trader_id, created_at DESC);
CREATE INDEX idx_rfqs_client ON rfqs(client_id, created_at DESC);
CREATE INDEX idx_rfqs_instrument ON rfqs(instrument_id, created_at DESC);
CREATE INDEX idx_rfqs_expires ON rfqs(expires_at) WHERE status IN ('pending', 'validated', 'distributed', 'quoted');

-- RLS Policy
ALTER TABLE rfqs ENABLE ROW LEVEL SECURITY;

CREATE POLICY rfqs_tenant_isolation ON rfqs
    USING (tenant_id = current_setting('app.current_tenant_id')::uuid);
```

### RFQ DTOs

```typescript
// services/rfq-service/src/dto/create-rfq.dto.ts
import { IsUUID, IsEnum, IsNumber, IsOptional, IsDateString, IsString, Min, Max } from 'class-validator';

export class CreateRfqDto {
  @IsUUID()
  instrumentId: string;

  @IsEnum(['buy', 'sell', 'two_way'])
  side: 'buy' | 'sell' | 'two_way';

  @IsNumber()
  @Min(0)
  quantity: number;

  @IsOptional()
  @IsString()
  notionalCurrency?: string;

  @IsOptional()
  @IsNumber()
  notionalAmount?: number;

  @IsOptional()
  @IsDateString()
  settlementDate?: string;

  @IsOptional()
  @IsEnum(['spot', 'forward', 'same_day', 'tom'])
  settlementType?: 'spot' | 'forward' | 'same_day' | 'tom';

  @IsOptional()
  @IsNumber()
  @Min(5)
  @Max(120)
  timeoutSeconds?: number;

  @IsOptional()
  @IsString()
  notes?: string;

  @IsOptional()
  @IsUUID()
  clientId?: string;  // For agency trades
}

export class RfqResponseDto {
  id: string;
  status: string;
  symbol: string;
  side: string;
  quantity: number;
  expiresAt: Date;
  createdAt: Date;
}
```

### RFQ Entity

```typescript
// services/rfq-service/src/entities/rfq.entity.ts
import { Entity, Column, PrimaryGeneratedColumn, CreateDateColumn, UpdateDateColumn, VersionColumn } from 'typeorm';

export type RfqStatus = 
  | 'pending' | 'validated' | 'distributed' | 'quoted' 
  | 'executed' | 'filled' | 'expired' | 'cancelled' | 'rejected';

export type RfqSide = 'buy' | 'sell' | 'two_way';

@Entity('rfqs')
export class RfqEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column('uuid', { name: 'tenant_id' })
  tenantId: string;

  @Column('uuid', { name: 'client_id' })
  clientId: string;

  @Column('uuid', { name: 'trader_id' })
  traderId: string;

  @Column('uuid', { name: 'instrument_id' })
  instrumentId: string;

  @Column()
  symbol: string;

  @Column({ name: 'asset_class' })
  assetClass: string;

  @Column({ type: 'enum', enum: ['buy', 'sell', 'two_way'] })
  side: RfqSide;

  @Column('decimal', { precision: 20, scale: 8 })
  quantity: number;

  @Column({ name: 'notional_currency', nullable: true })
  notionalCurrency?: string;

  @Column('decimal', { name: 'notional_amount', precision: 20, scale: 2, nullable: true })
  notionalAmount?: number;

  @Column({ name: 'settlement_date', type: 'date', nullable: true })
  settlementDate?: Date;

  @Column({ name: 'settlement_type', nullable: true })
  settlementType?: string;

  @Column({ name: 'value_date', type: 'date', nullable: true })
  valueDate?: Date;

  @Column({ name: 'timeout_seconds', default: 30 })
  timeoutSeconds: number;

  @Column({ name: 'expires_at', type: 'timestamptz' })
  expiresAt: Date;

  @Column({ type: 'enum', enum: ['pending', 'validated', 'distributed', 'quoted', 'executed', 'filled', 'expired', 'cancelled', 'rejected'], default: 'pending' })
  status: RfqStatus;

  @Column({ name: 'rejection_reason', nullable: true })
  rejectionReason?: string;

  @Column('uuid', { name: 'selected_quote_id', nullable: true })
  selectedQuoteId?: string;

  @Column('uuid', { name: 'executed_trade_id', nullable: true })
  executedTradeId?: string;

  @Column('decimal', { name: 'reference_price', precision: 20, scale: 8, nullable: true })
  referencePrice?: number;

  @Column({ nullable: true })
  notes?: string;

  @Column('jsonb', { default: [] })
  tags: string[];

  @VersionColumn()
  version: number;

  @CreateDateColumn({ name: 'created_at' })
  createdAt: Date;

  @UpdateDateColumn({ name: 'updated_at' })
  updatedAt: Date;

  @Column('uuid', { name: 'created_by' })
  createdBy: string;
}
```

### RFQ Validation Service

```typescript
// services/rfq-service/src/validation/rfq-validation.service.ts
import { Injectable } from '@nestjs/common';
import { logger } from '@orion/observability';
import { InstrumentService } from '@orion/reference-data';
import { RiskService } from '../risk/risk.service';
import { CreateRfqDto } from '../dto/create-rfq.dto';

export interface ValidationResult {
  valid: boolean;
  errors: ValidationError[];
  warnings: ValidationWarning[];
  enrichedData?: EnrichedRfqData;
}

export interface ValidationError {
  field: string;
  code: string;
  message: string;
}

export interface ValidationWarning {
  field: string;
  code: string;
  message: string;
}

export interface EnrichedRfqData {
  symbol: string;
  assetClass: string;
  pricePrecision: number;
  sizePrecision: number;
  minQuantity: number;
  maxQuantity: number;
  lotSize: number;
  referencePrice?: number;
  settlementDate: Date;
  valueDate: Date;
}

@Injectable()
export class RfqValidationService {
  constructor(
    private readonly instrumentService: InstrumentService,
    private readonly riskService: RiskService,
  ) {}

  async validate(
    dto: CreateRfqDto,
    tenantId: string,
    traderId: string,
  ): Promise<ValidationResult> {
    const errors: ValidationError[] = [];
    const warnings: ValidationWarning[] = [];

    // 1. Validate instrument
    const instrument = await this.instrumentService.getById(dto.instrumentId);
    
    if (!instrument) {
      errors.push({
        field: 'instrumentId',
        code: 'INSTRUMENT_NOT_FOUND',
        message: `Instrument ${dto.instrumentId} not found`,
      });
      return { valid: false, errors, warnings };
    }

    if (instrument.status !== 'active') {
      errors.push({
        field: 'instrumentId',
        code: 'INSTRUMENT_NOT_TRADEABLE',
        message: `Instrument ${instrument.symbol} is not tradeable (status: ${instrument.status})`,
      });
    }

    // 2. Validate quantity
    if (dto.quantity < instrument.minimumSize) {
      errors.push({
        field: 'quantity',
        code: 'QUANTITY_BELOW_MINIMUM',
        message: `Quantity ${dto.quantity} is below minimum ${instrument.minimumSize}`,
      });
    }

    if (instrument.maximumSize && dto.quantity > instrument.maximumSize) {
      errors.push({
        field: 'quantity',
        code: 'QUANTITY_ABOVE_MAXIMUM',
        message: `Quantity ${dto.quantity} exceeds maximum ${instrument.maximumSize}`,
      });
    }

    // Check lot size
    if (instrument.lotSize && dto.quantity % instrument.lotSize !== 0) {
      warnings.push({
        field: 'quantity',
        code: 'QUANTITY_NOT_LOT_SIZE',
        message: `Quantity ${dto.quantity} is not a multiple of lot size ${instrument.lotSize}`,
      });
    }

    // 3. Validate settlement
    const settlementDates = this.calculateSettlementDates(
      dto.settlementType || 'spot',
      instrument.assetClass,
      dto.settlementDate ? new Date(dto.settlementDate) : undefined,
    );

    if (settlementDates.settlementDate < new Date()) {
      errors.push({
        field: 'settlementDate',
        code: 'SETTLEMENT_DATE_PAST',
        message: 'Settlement date cannot be in the past',
      });
    }

    // 4. Pre-trade risk checks
    const riskResult = await this.riskService.preTradeCheck({
      tenantId,
      traderId,
      clientId: dto.clientId,
      instrumentId: dto.instrumentId,
      side: dto.side,
      quantity: dto.quantity,
      notionalAmount: dto.notionalAmount,
    });

    if (!riskResult.approved) {
      for (const breach of riskResult.breaches) {
        errors.push({
          field: 'risk',
          code: `RISK_${breach.type}`,
          message: breach.message,
        });
      }
    }

    for (const warning of riskResult.warnings || []) {
      warnings.push({
        field: 'risk',
        code: `RISK_WARNING_${warning.type}`,
        message: warning.message,
      });
    }

    // 5. Build enriched data
    const enrichedData: EnrichedRfqData = {
      symbol: instrument.symbol,
      assetClass: instrument.assetClass,
      pricePrecision: instrument.pricePrecision,
      sizePrecision: instrument.sizePrecision,
      minQuantity: instrument.minimumSize,
      maxQuantity: instrument.maximumSize || Infinity,
      lotSize: instrument.lotSize,
      referencePrice: await this.getReferencePrice(instrument.symbol),
      settlementDate: settlementDates.settlementDate,
      valueDate: settlementDates.valueDate,
    };

    return {
      valid: errors.length === 0,
      errors,
      warnings,
      enrichedData,
    };
  }

  private calculateSettlementDates(
    settlementType: string,
    assetClass: string,
    requestedDate?: Date,
  ): { settlementDate: Date; valueDate: Date } {
    const today = new Date();
    let settlementDate: Date;

    switch (settlementType) {
      case 'same_day':
        settlementDate = today;
        break;
      case 'tom':
        settlementDate = this.addBusinessDays(today, 1);
        break;
      case 'spot':
        // Spot is T+2 for FX, T+1 for crypto
        settlementDate = assetClass === 'crypto' 
          ? this.addBusinessDays(today, 1)
          : this.addBusinessDays(today, 2);
        break;
      case 'forward':
        settlementDate = requestedDate || this.addBusinessDays(today, 30);
        break;
      default:
        settlementDate = this.addBusinessDays(today, 2);
    }

    return {
      settlementDate,
      valueDate: settlementDate,
    };
  }

  private addBusinessDays(date: Date, days: number): Date {
    const result = new Date(date);
    let added = 0;
    
    while (added < days) {
      result.setDate(result.getDate() + 1);
      if (result.getDay() !== 0 && result.getDay() !== 6) {
        added++;
      }
    }
    
    return result;
  }

  private async getReferencePrice(symbol: string): Promise<number | undefined> {
    // Get from market data service
    try {
      const price = await this.instrumentService.getLatestPrice(symbol);
      return price?.midPrice;
    } catch {
      return undefined;
    }
  }
}
```

### RFQ Service

```typescript
// services/rfq-service/src/rfq.service.ts
import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { logger, metrics } from '@orion/observability';
import { EventBus, transactionalOutbox } from '@orion/event-model';
import { getCurrentTenant } from '@orion/security';
import { RfqEntity, RfqStatus } from './entities/rfq.entity';
import { CreateRfqDto, RfqResponseDto } from './dto/create-rfq.dto';
import { RfqValidationService } from './validation/rfq-validation.service';

@Injectable()
export class RfqService {
  constructor(
    @InjectRepository(RfqEntity)
    private readonly rfqRepository: Repository<RfqEntity>,
    private readonly validationService: RfqValidationService,
    private readonly eventBus: EventBus,
  ) {}

  async createRfq(dto: CreateRfqDto, userId: string): Promise<RfqResponseDto> {
    const tenant = getCurrentTenant();
    const startTime = Date.now();

    // Validate RFQ
    const validation = await this.validationService.validate(
      dto,
      tenant.tenantId,
      userId,
    );

    if (!validation.valid) {
      logger.warn('RFQ validation failed', {
        errors: validation.errors,
        userId,
      });
      
      throw new RfqValidationError(validation.errors);
    }

    // Calculate expiration
    const timeoutSeconds = dto.timeoutSeconds || 30;
    const expiresAt = new Date(Date.now() + timeoutSeconds * 1000);

    // Create RFQ entity
    const rfq = this.rfqRepository.create({
      tenantId: tenant.tenantId,
      clientId: dto.clientId || tenant.tenantId,  // Default to tenant for prop trades
      traderId: userId,
      instrumentId: dto.instrumentId,
      symbol: validation.enrichedData!.symbol,
      assetClass: validation.enrichedData!.assetClass,
      side: dto.side,
      quantity: dto.quantity,
      notionalCurrency: dto.notionalCurrency,
      notionalAmount: dto.notionalAmount,
      settlementDate: validation.enrichedData!.settlementDate,
      settlementType: dto.settlementType || 'spot',
      valueDate: validation.enrichedData!.valueDate,
      timeoutSeconds,
      expiresAt,
      status: 'pending',
      referencePrice: validation.enrichedData!.referencePrice,
      notes: dto.notes,
      createdBy: userId,
    });

    // Save with transactional outbox
    const savedRfq = await transactionalOutbox(
      this.rfqRepository.manager,
      async (manager) => {
        const saved = await manager.save(rfq);
        return saved;
      },
      {
        topic: 'orion.events.rfq',
        eventType: 'rfq.created',
        aggregateType: 'rfq',
        aggregateId: rfq.id,
        payload: {
          rfqId: rfq.id,
          tenantId: rfq.tenantId,
          traderId: rfq.traderId,
          symbol: rfq.symbol,
          side: rfq.side,
          quantity: rfq.quantity,
          timeoutSeconds: rfq.timeoutSeconds,
          expiresAt: rfq.expiresAt,
        },
      },
    );

    metrics.timing('rfq.creation_time', Date.now() - startTime);
    metrics.increment('rfq.created', { symbol: rfq.symbol, side: rfq.side });

    logger.info('RFQ created', {
      rfqId: savedRfq.id,
      symbol: savedRfq.symbol,
      quantity: savedRfq.quantity,
    });

    return {
      id: savedRfq.id,
      status: savedRfq.status,
      symbol: savedRfq.symbol,
      side: savedRfq.side,
      quantity: savedRfq.quantity,
      expiresAt: savedRfq.expiresAt,
      createdAt: savedRfq.createdAt,
    };
  }

  async validateRfq(rfqId: string): Promise<void> {
    const rfq = await this.rfqRepository.findOne({ where: { id: rfqId } });
    
    if (!rfq) {
      throw new RfqNotFoundError(rfqId);
    }

    if (rfq.status !== 'pending') {
      throw new InvalidRfqStateError(rfqId, rfq.status, 'pending');
    }

    await transactionalOutbox(
      this.rfqRepository.manager,
      async (manager) => {
        await manager.update(RfqEntity, rfqId, { 
          status: 'validated',
          updatedAt: new Date(),
        });
      },
      {
        topic: 'orion.events.rfq',
        eventType: 'rfq.validated',
        aggregateType: 'rfq',
        aggregateId: rfqId,
        payload: { rfqId },
      },
    );

    logger.info('RFQ validated', { rfqId });
  }

  async getRfq(rfqId: string): Promise<RfqEntity> {
    const rfq = await this.rfqRepository.findOne({ where: { id: rfqId } });
    
    if (!rfq) {
      throw new RfqNotFoundError(rfqId);
    }

    return rfq;
  }

  async getRfqsByTrader(traderId: string, limit: number = 50): Promise<RfqEntity[]> {
    return this.rfqRepository.find({
      where: { traderId },
      order: { createdAt: 'DESC' },
      take: limit,
    });
  }
}

// Custom errors
export class RfqValidationError extends Error {
  constructor(public readonly errors: ValidationError[]) {
    super('RFQ validation failed');
    this.name = 'RfqValidationError';
  }
}

export class RfqNotFoundError extends Error {
  constructor(rfqId: string) {
    super(`RFQ ${rfqId} not found`);
    this.name = 'RfqNotFoundError';
  }
}

export class InvalidRfqStateError extends Error {
  constructor(rfqId: string, currentState: string, expectedState: string) {
    super(`RFQ ${rfqId} is in state ${currentState}, expected ${expectedState}`);
    this.name = 'InvalidRfqStateError';
  }
}
```

### RFQ Controller

```typescript
// services/rfq-service/src/rfq.controller.ts
import { Controller, Post, Get, Param, Body, UseGuards, Query } from '@nestjs/common';
import { JwtAuthGuard, CurrentUser, User } from '@orion/security';
import { RfqService } from './rfq.service';
import { CreateRfqDto, RfqResponseDto } from './dto/create-rfq.dto';

@Controller('rfqs')
@UseGuards(JwtAuthGuard)
export class RfqController {
  constructor(private readonly rfqService: RfqService) {}

  @Post()
  async createRfq(
    @Body() dto: CreateRfqDto,
    @CurrentUser() user: User,
  ): Promise<RfqResponseDto> {
    return this.rfqService.createRfq(dto, user.id);
  }

  @Get(':id')
  async getRfq(@Param('id') id: string) {
    return this.rfqService.getRfq(id);
  }

  @Get()
  async getMyRfqs(
    @CurrentUser() user: User,
    @Query('limit') limit: number = 50,
  ) {
    return this.rfqService.getRfqsByTrader(user.id, limit);
  }
}
```

## Definition of Done

- [ ] RFQ creation API implemented
- [ ] Instrument validation working
- [ ] Quantity validation with lot sizes
- [ ] Settlement date calculation
- [ ] Pre-trade risk checks integrated
- [ ] RFQ events published
- [ ] Tests pass

## Dependencies

- **US-04-01**: Instrument Management
- **US-05-02**: Transactional Outbox Pattern
- **US-10-01**: Pre-trade Risk Checks (placeholder)

## Test Cases

```typescript
describe('RfqService', () => {
  it('should create valid RFQ', async () => {
    const rfq = await rfqService.createRfq({
      instrumentId: 'eur-usd-id',
      side: 'buy',
      quantity: 1000000,
      timeoutSeconds: 30,
    }, 'trader-1');

    expect(rfq.id).toBeDefined();
    expect(rfq.status).toBe('pending');
    expect(rfq.symbol).toBe('EUR/USD');
  });

  it('should reject invalid instrument', async () => {
    await expect(
      rfqService.createRfq({
        instrumentId: 'invalid-id',
        side: 'buy',
        quantity: 1000000,
      }, 'trader-1')
    ).rejects.toThrow(RfqValidationError);
  });

  it('should reject quantity below minimum', async () => {
    await expect(
      rfqService.createRfq({
        instrumentId: 'eur-usd-id',
        side: 'buy',
        quantity: 100,  // Below minimum
      }, 'trader-1')
    ).rejects.toThrow(RfqValidationError);
  });
});
```
