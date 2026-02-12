# User Story: US-08-01 - Trade Entity and Repository

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-08-01 |
| **Epic** | Epic 08 - Trade Execution |
| **Title** | Trade Entity and Repository |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |
| **PRD Reference** | FR-Trade-01 |

## User Story

**As a** platform developer  
**I want** a comprehensive trade data model and repository  
**So that** trades can be stored, retrieved, and managed efficiently

## Description

Implement the core trade entity, database schema, and repository pattern with support for complex queries, versioning, and multi-tenant isolation.

## Acceptance Criteria

- [ ] Trade entity with all required fields
- [ ] Database schema with proper indexes
- [ ] Repository with CRUD operations
- [ ] Optimistic locking for concurrent updates
- [ ] Multi-tenant RLS policies
- [ ] Query support for common access patterns
- [ ] Trade reference number generation

## Technical Details

### Database Schema

```sql
-- migrations/20240124_create_trade_tables.sql

CREATE TYPE trade_status AS ENUM (
    'pending',
    'validated',
    'rejected',
    'booked',
    'amended',
    'allocated',
    'settling',
    'settled',
    'cancelled'
);

CREATE TYPE trade_side AS ENUM ('buy', 'sell');

CREATE TYPE trade_source AS ENUM ('rfq', 'order', 'manual', 'import');

-- Main trades table
CREATE TABLE trades (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    
    -- Reference
    trade_ref VARCHAR(50) NOT NULL,
    external_ref VARCHAR(100),
    
    -- Source
    source_type trade_source NOT NULL,
    source_id UUID,
    
    -- Counterparties
    client_id UUID NOT NULL,
    client_name VARCHAR(200) NOT NULL,
    counterparty_id UUID NOT NULL,
    counterparty_name VARCHAR(200) NOT NULL,
    
    -- Instrument
    instrument_id UUID NOT NULL REFERENCES instruments(id),
    symbol VARCHAR(50) NOT NULL,
    asset_class VARCHAR(20) NOT NULL,
    
    -- Economics
    side trade_side NOT NULL,
    quantity NUMERIC(20, 8) NOT NULL,
    price NUMERIC(20, 10) NOT NULL,
    notional_amount NUMERIC(20, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    settlement_currency VARCHAR(3) NOT NULL,
    
    -- Fees
    commission NUMERIC(20, 4) DEFAULT 0,
    commission_currency VARCHAR(3),
    total_fees NUMERIC(20, 4) DEFAULT 0,
    
    -- Dates
    trade_date DATE NOT NULL,
    settlement_date DATE NOT NULL,
    value_date DATE NOT NULL,
    execution_time TIMESTAMPTZ NOT NULL,
    
    -- State
    status trade_status NOT NULL DEFAULT 'pending',
    rejection_reason TEXT,
    cancellation_reason TEXT,
    
    -- Execution
    trader_id UUID NOT NULL,
    trader_name VARCHAR(200) NOT NULL,
    execution_venue VARCHAR(100),
    
    -- Versioning
    version INTEGER NOT NULL DEFAULT 1,
    previous_version_id UUID,
    
    -- Metadata
    tags JSONB DEFAULT '[]',
    custom_fields JSONB DEFAULT '{}',
    
    -- Audit
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID NOT NULL,
    updated_by UUID,
    
    UNIQUE(tenant_id, trade_ref)
);

-- Trade fees
CREATE TABLE trade_fees (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trade_id UUID NOT NULL REFERENCES trades(id) ON DELETE CASCADE,
    fee_type VARCHAR(50) NOT NULL,
    amount NUMERIC(20, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Trade allocations
CREATE TABLE trade_allocations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trade_id UUID NOT NULL REFERENCES trades(id) ON DELETE CASCADE,
    account_id UUID NOT NULL,
    account_name VARCHAR(200) NOT NULL,
    quantity NUMERIC(20, 8) NOT NULL,
    percentage NUMERIC(5, 2) NOT NULL,
    notional_amount NUMERIC(20, 2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Trade history for amendments
CREATE TABLE trade_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trade_id UUID NOT NULL REFERENCES trades(id),
    version INTEGER NOT NULL,
    changes JSONB NOT NULL,
    changed_by UUID NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reason TEXT
);

-- Indexes
CREATE INDEX idx_trades_tenant_ref ON trades(tenant_id, trade_ref);
CREATE INDEX idx_trades_tenant_status ON trades(tenant_id, status);
CREATE INDEX idx_trades_trade_date ON trades(tenant_id, trade_date DESC);
CREATE INDEX idx_trades_client ON trades(client_id, trade_date DESC);
CREATE INDEX idx_trades_instrument ON trades(instrument_id, trade_date DESC);
CREATE INDEX idx_trades_source ON trades(source_type, source_id);
CREATE INDEX idx_trades_trader ON trades(trader_id, trade_date DESC);
CREATE INDEX idx_trades_settlement ON trades(settlement_date) WHERE status IN ('booked', 'settling');
CREATE INDEX idx_trade_allocations_trade ON trade_allocations(trade_id);
CREATE INDEX idx_trade_fees_trade ON trade_fees(trade_id);

-- Row Level Security
ALTER TABLE trades ENABLE ROW LEVEL SECURITY;
ALTER TABLE trade_fees ENABLE ROW LEVEL SECURITY;
ALTER TABLE trade_allocations ENABLE ROW LEVEL SECURITY;
ALTER TABLE trade_history ENABLE ROW LEVEL SECURITY;

CREATE POLICY trades_tenant_isolation ON trades
    USING (tenant_id = current_setting('app.current_tenant_id')::uuid);

CREATE POLICY trade_fees_tenant_isolation ON trade_fees
    USING (trade_id IN (SELECT id FROM trades WHERE tenant_id = current_setting('app.current_tenant_id')::uuid));

CREATE POLICY trade_allocations_tenant_isolation ON trade_allocations
    USING (trade_id IN (SELECT id FROM trades WHERE tenant_id = current_setting('app.current_tenant_id')::uuid));

CREATE POLICY trade_history_tenant_isolation ON trade_history
    USING (trade_id IN (SELECT id FROM trades WHERE tenant_id = current_setting('app.current_tenant_id')::uuid));

-- Trade reference sequence per tenant
CREATE TABLE trade_ref_sequences (
    tenant_id UUID PRIMARY KEY REFERENCES tenants(id),
    current_value BIGINT NOT NULL DEFAULT 0,
    prefix VARCHAR(10) NOT NULL DEFAULT 'TRD',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Function to generate trade reference
CREATE OR REPLACE FUNCTION generate_trade_ref(p_tenant_id UUID)
RETURNS VARCHAR(50) AS $$
DECLARE
    v_seq BIGINT;
    v_prefix VARCHAR(10);
    v_date_part VARCHAR(8);
BEGIN
    -- Get and increment sequence
    INSERT INTO trade_ref_sequences (tenant_id, current_value, prefix)
    VALUES (p_tenant_id, 1, 'TRD')
    ON CONFLICT (tenant_id) DO UPDATE
    SET current_value = trade_ref_sequences.current_value + 1,
        updated_at = NOW()
    RETURNING current_value, prefix INTO v_seq, v_prefix;
    
    -- Format: TRD-20240124-000001
    v_date_part := TO_CHAR(NOW(), 'YYYYMMDD');
    RETURN v_prefix || '-' || v_date_part || '-' || LPAD(v_seq::TEXT, 6, '0');
END;
$$ LANGUAGE plpgsql;
```

### Trade Entity

```typescript
// services/trade-service/src/entities/trade.entity.ts
import { 
  Entity, Column, PrimaryGeneratedColumn, CreateDateColumn, 
  UpdateDateColumn, VersionColumn, OneToMany, Index 
} from 'typeorm';
import { TradeFeeEntity } from './trade-fee.entity';
import { TradeAllocationEntity } from './trade-allocation.entity';

export type TradeStatus = 
  | 'pending' | 'validated' | 'rejected' | 'booked' 
  | 'amended' | 'allocated' | 'settling' | 'settled' | 'cancelled';

export type TradeSide = 'buy' | 'sell';
export type TradeSource = 'rfq' | 'order' | 'manual' | 'import';

@Entity('trades')
@Index(['tenantId', 'tradeRef'], { unique: true })
@Index(['tenantId', 'status'])
@Index(['tenantId', 'tradeDate'])
export class TradeEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column('uuid', { name: 'tenant_id' })
  tenantId: string;

  @Column({ name: 'trade_ref' })
  tradeRef: string;

  @Column({ name: 'external_ref', nullable: true })
  externalRef?: string;

  // Source
  @Column({ name: 'source_type', type: 'enum', enum: ['rfq', 'order', 'manual', 'import'] })
  sourceType: TradeSource;

  @Column('uuid', { name: 'source_id', nullable: true })
  sourceId?: string;

  // Counterparties
  @Column('uuid', { name: 'client_id' })
  clientId: string;

  @Column({ name: 'client_name' })
  clientName: string;

  @Column('uuid', { name: 'counterparty_id' })
  counterpartyId: string;

  @Column({ name: 'counterparty_name' })
  counterpartyName: string;

  // Instrument
  @Column('uuid', { name: 'instrument_id' })
  instrumentId: string;

  @Column()
  symbol: string;

  @Column({ name: 'asset_class' })
  assetClass: string;

  // Economics
  @Column({ type: 'enum', enum: ['buy', 'sell'] })
  side: TradeSide;

  @Column('decimal', { precision: 20, scale: 8 })
  quantity: number;

  @Column('decimal', { precision: 20, scale: 10 })
  price: number;

  @Column('decimal', { name: 'notional_amount', precision: 20, scale: 2 })
  notionalAmount: number;

  @Column({ length: 3 })
  currency: string;

  @Column({ name: 'settlement_currency', length: 3 })
  settlementCurrency: string;

  // Fees
  @Column('decimal', { precision: 20, scale: 4, default: 0 })
  commission: number;

  @Column({ name: 'commission_currency', length: 3, nullable: true })
  commissionCurrency?: string;

  @Column('decimal', { name: 'total_fees', precision: 20, scale: 4, default: 0 })
  totalFees: number;

  // Dates
  @Column({ name: 'trade_date', type: 'date' })
  tradeDate: Date;

  @Column({ name: 'settlement_date', type: 'date' })
  settlementDate: Date;

  @Column({ name: 'value_date', type: 'date' })
  valueDate: Date;

  @Column({ name: 'execution_time', type: 'timestamptz' })
  executionTime: Date;

  // State
  @Column({ 
    type: 'enum', 
    enum: ['pending', 'validated', 'rejected', 'booked', 'amended', 'allocated', 'settling', 'settled', 'cancelled'],
    default: 'pending' 
  })
  status: TradeStatus;

  @Column({ name: 'rejection_reason', nullable: true })
  rejectionReason?: string;

  @Column({ name: 'cancellation_reason', nullable: true })
  cancellationReason?: string;

  // Execution
  @Column('uuid', { name: 'trader_id' })
  traderId: string;

  @Column({ name: 'trader_name' })
  traderName: string;

  @Column({ name: 'execution_venue', nullable: true })
  executionVenue?: string;

  // Version
  @VersionColumn()
  version: number;

  @Column('uuid', { name: 'previous_version_id', nullable: true })
  previousVersionId?: string;

  // Metadata
  @Column('jsonb', { default: [] })
  tags: string[];

  @Column('jsonb', { name: 'custom_fields', default: {} })
  customFields: Record<string, any>;

  // Audit
  @CreateDateColumn({ name: 'created_at' })
  createdAt: Date;

  @UpdateDateColumn({ name: 'updated_at' })
  updatedAt: Date;

  @Column('uuid', { name: 'created_by' })
  createdBy: string;

  @Column('uuid', { name: 'updated_by', nullable: true })
  updatedBy?: string;

  // Relations
  @OneToMany(() => TradeFeeEntity, fee => fee.trade, { cascade: true })
  fees: TradeFeeEntity[];

  @OneToMany(() => TradeAllocationEntity, allocation => allocation.trade, { cascade: true })
  allocations: TradeAllocationEntity[];
}
```

### Trade Fee Entity

```typescript
// services/trade-service/src/entities/trade-fee.entity.ts
import { Entity, Column, PrimaryGeneratedColumn, ManyToOne, JoinColumn, CreateDateColumn } from 'typeorm';
import { TradeEntity } from './trade.entity';

@Entity('trade_fees')
export class TradeFeeEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column('uuid', { name: 'trade_id' })
  tradeId: string;

  @Column({ name: 'fee_type' })
  feeType: string;

  @Column('decimal', { precision: 20, scale: 4 })
  amount: number;

  @Column({ length: 3 })
  currency: string;

  @Column({ nullable: true })
  description?: string;

  @CreateDateColumn({ name: 'created_at' })
  createdAt: Date;

  @ManyToOne(() => TradeEntity, trade => trade.fees)
  @JoinColumn({ name: 'trade_id' })
  trade: TradeEntity;
}
```

### Trade Repository

```typescript
// services/trade-service/src/repositories/trade.repository.ts
import { Injectable } from '@nestjs/common';
import { InjectRepository, InjectDataSource } from '@nestjs/typeorm';
import { Repository, DataSource, FindOptionsWhere, Between, In } from 'typeorm';
import { TradeEntity, TradeStatus } from '../entities/trade.entity';
import { getCurrentTenant } from '@orion/security';

export interface TradeQueryOptions {
  status?: TradeStatus | TradeStatus[];
  clientId?: string;
  traderId?: string;
  instrumentId?: string;
  symbol?: string;
  assetClass?: string;
  tradeDateFrom?: Date;
  tradeDateTo?: Date;
  settlementDateFrom?: Date;
  settlementDateTo?: Date;
  sourceType?: string;
  limit?: number;
  offset?: number;
  orderBy?: string;
  orderDir?: 'ASC' | 'DESC';
}

@Injectable()
export class TradeRepository {
  constructor(
    @InjectRepository(TradeEntity)
    private readonly repository: Repository<TradeEntity>,
    @InjectDataSource()
    private readonly dataSource: DataSource,
  ) {}

  async create(trade: Partial<TradeEntity>): Promise<TradeEntity> {
    const tenant = getCurrentTenant();
    
    // Generate trade reference
    const tradeRef = await this.generateTradeRef(tenant.tenantId);
    
    const entity = this.repository.create({
      ...trade,
      tenantId: tenant.tenantId,
      tradeRef,
    });

    return this.repository.save(entity);
  }

  async findById(id: string): Promise<TradeEntity | null> {
    return this.repository.findOne({
      where: { id },
      relations: ['fees', 'allocations'],
    });
  }

  async findByTradeRef(tradeRef: string): Promise<TradeEntity | null> {
    return this.repository.findOne({
      where: { tradeRef },
      relations: ['fees', 'allocations'],
    });
  }

  async findBySourceId(sourceType: string, sourceId: string): Promise<TradeEntity | null> {
    return this.repository.findOne({
      where: { sourceType: sourceType as any, sourceId },
      relations: ['fees', 'allocations'],
    });
  }

  async query(options: TradeQueryOptions): Promise<{
    trades: TradeEntity[];
    total: number;
  }> {
    const query = this.repository.createQueryBuilder('trade')
      .leftJoinAndSelect('trade.fees', 'fees')
      .leftJoinAndSelect('trade.allocations', 'allocations');

    if (options.status) {
      const statuses = Array.isArray(options.status) ? options.status : [options.status];
      query.andWhere('trade.status IN (:...statuses)', { statuses });
    }

    if (options.clientId) {
      query.andWhere('trade.client_id = :clientId', { clientId: options.clientId });
    }

    if (options.traderId) {
      query.andWhere('trade.trader_id = :traderId', { traderId: options.traderId });
    }

    if (options.instrumentId) {
      query.andWhere('trade.instrument_id = :instrumentId', { instrumentId: options.instrumentId });
    }

    if (options.symbol) {
      query.andWhere('trade.symbol = :symbol', { symbol: options.symbol });
    }

    if (options.assetClass) {
      query.andWhere('trade.asset_class = :assetClass', { assetClass: options.assetClass });
    }

    if (options.tradeDateFrom) {
      query.andWhere('trade.trade_date >= :tradeDateFrom', { tradeDateFrom: options.tradeDateFrom });
    }

    if (options.tradeDateTo) {
      query.andWhere('trade.trade_date <= :tradeDateTo', { tradeDateTo: options.tradeDateTo });
    }

    if (options.settlementDateFrom) {
      query.andWhere('trade.settlement_date >= :settlementDateFrom', { settlementDateFrom: options.settlementDateFrom });
    }

    if (options.settlementDateTo) {
      query.andWhere('trade.settlement_date <= :settlementDateTo', { settlementDateTo: options.settlementDateTo });
    }

    if (options.sourceType) {
      query.andWhere('trade.source_type = :sourceType', { sourceType: options.sourceType });
    }

    // Ordering
    const orderBy = options.orderBy || 'trade.created_at';
    const orderDir = options.orderDir || 'DESC';
    query.orderBy(orderBy, orderDir);

    // Get total count
    const total = await query.getCount();

    // Pagination
    if (options.offset) {
      query.skip(options.offset);
    }
    query.take(options.limit || 50);

    const trades = await query.getMany();

    return { trades, total };
  }

  async update(
    id: string, 
    updates: Partial<TradeEntity>,
    expectedVersion?: number,
  ): Promise<TradeEntity> {
    const queryRunner = this.dataSource.createQueryRunner();
    await queryRunner.connect();
    await queryRunner.startTransaction();

    try {
      const trade = await queryRunner.manager.findOne(TradeEntity, {
        where: { id },
        lock: { mode: 'pessimistic_write' },
      });

      if (!trade) {
        throw new Error(`Trade ${id} not found`);
      }

      // Optimistic locking check
      if (expectedVersion !== undefined && trade.version !== expectedVersion) {
        throw new Error(`Optimistic lock failure: expected version ${expectedVersion}, found ${trade.version}`);
      }

      // Apply updates
      Object.assign(trade, updates);
      trade.updatedAt = new Date();

      const saved = await queryRunner.manager.save(trade);
      await queryRunner.commitTransaction();

      return saved;
    } catch (error) {
      await queryRunner.rollbackTransaction();
      throw error;
    } finally {
      await queryRunner.release();
    }
  }

  async updateStatus(
    id: string,
    status: TradeStatus,
    reason?: string,
  ): Promise<TradeEntity> {
    const updates: Partial<TradeEntity> = { status };
    
    if (status === 'rejected' && reason) {
      updates.rejectionReason = reason;
    }
    if (status === 'cancelled' && reason) {
      updates.cancellationReason = reason;
    }

    return this.update(id, updates);
  }

  async getTradesForSettlement(settlementDate: Date): Promise<TradeEntity[]> {
    return this.repository.find({
      where: {
        settlementDate,
        status: In(['booked', 'allocated']),
      },
      relations: ['fees', 'allocations'],
    });
  }

  async getTradesByDateRange(
    startDate: Date,
    endDate: Date,
  ): Promise<TradeEntity[]> {
    return this.repository.find({
      where: {
        tradeDate: Between(startDate, endDate),
      },
      relations: ['fees', 'allocations'],
      order: { tradeDate: 'DESC', createdAt: 'DESC' },
    });
  }

  private async generateTradeRef(tenantId: string): Promise<string> {
    const result = await this.dataSource.query(
      'SELECT generate_trade_ref($1) as trade_ref',
      [tenantId],
    );
    return result[0].trade_ref;
  }
}
```

### Trade DTOs

```typescript
// services/trade-service/src/dto/trade.dto.ts
import { IsUUID, IsEnum, IsNumber, IsString, IsOptional, IsDateString, IsArray, ValidateNested } from 'class-validator';
import { Type } from 'class-transformer';

export class TradeFeeDto {
  @IsString()
  feeType: string;

  @IsNumber()
  amount: number;

  @IsString()
  currency: string;

  @IsOptional()
  @IsString()
  description?: string;
}

export class CreateTradeDto {
  @IsEnum(['rfq', 'order', 'manual', 'import'])
  sourceType: 'rfq' | 'order' | 'manual' | 'import';

  @IsOptional()
  @IsUUID()
  sourceId?: string;

  @IsOptional()
  @IsString()
  externalRef?: string;

  @IsUUID()
  clientId: string;

  @IsUUID()
  counterpartyId: string;

  @IsUUID()
  instrumentId: string;

  @IsEnum(['buy', 'sell'])
  side: 'buy' | 'sell';

  @IsNumber()
  quantity: number;

  @IsNumber()
  price: number;

  @IsString()
  currency: string;

  @IsOptional()
  @IsString()
  settlementCurrency?: string;

  @IsDateString()
  tradeDate: string;

  @IsDateString()
  settlementDate: string;

  @IsOptional()
  @IsDateString()
  executionTime?: string;

  @IsOptional()
  @IsNumber()
  commission?: number;

  @IsOptional()
  @IsString()
  commissionCurrency?: string;

  @IsOptional()
  @IsArray()
  @ValidateNested({ each: true })
  @Type(() => TradeFeeDto)
  fees?: TradeFeeDto[];

  @IsOptional()
  @IsString()
  executionVenue?: string;
}

export class TradeResponseDto {
  id: string;
  tradeRef: string;
  symbol: string;
  side: string;
  quantity: number;
  price: number;
  notionalAmount: number;
  status: string;
  tradeDate: Date;
  settlementDate: Date;
  counterpartyName: string;
  createdAt: Date;
}
```

## Definition of Done

- [ ] Trade entity with all fields
- [ ] Database schema deployed
- [ ] Repository with CRUD operations
- [ ] Trade reference generation
- [ ] Optimistic locking working
- [ ] RLS policies applied
- [ ] Query interface complete

## Dependencies

- **US-03-02**: Tenant Context for RLS
- **US-04-01**: Instrument Reference Data

## Test Cases

```typescript
describe('TradeRepository', () => {
  it('should create trade with generated reference', async () => {
    const trade = await tradeRepository.create({
      sourceType: 'manual',
      clientId: 'client-1',
      counterpartyId: 'lp-1',
      instrumentId: 'eur-usd',
      side: 'buy',
      quantity: 1000000,
      price: 1.0850,
      currency: 'EUR',
      tradeDate: new Date(),
      settlementDate: addDays(new Date(), 2),
      traderId: 'trader-1',
    });

    expect(trade.tradeRef).toMatch(/^TRD-\d{8}-\d{6}$/);
    expect(trade.status).toBe('pending');
  });

  it('should enforce optimistic locking', async () => {
    const trade = await createTrade();
    
    await tradeRepository.update(trade.id, { status: 'validated' }, trade.version);
    
    await expect(
      tradeRepository.update(trade.id, { status: 'booked' }, trade.version)
    ).rejects.toThrow('Optimistic lock failure');
  });

  it('should query trades by date range', async () => {
    await createTradesForDateRange();
    
    const { trades, total } = await tradeRepository.query({
      tradeDateFrom: new Date('2024-01-01'),
      tradeDateTo: new Date('2024-01-31'),
    });

    expect(trades.length).toBeGreaterThan(0);
    expect(total).toBeGreaterThan(0);
  });
});
```
