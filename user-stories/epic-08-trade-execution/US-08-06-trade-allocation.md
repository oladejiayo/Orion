# User Story: US-08-06 - Trade Allocation

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-08-06 |
| **Epic** | Epic 08 - Trade Execution |
| **Title** | Trade Allocation |
| **Priority** | P1 - High |
| **Story Points** | 5 |
| **PRD Reference** | FR-Trade-06, NFR-Compliance-02 |

## User Story

**As a** portfolio manager  
**I want** to allocate block trades to multiple accounts  
**So that** I can distribute fills across client portfolios fairly

## Description

Implement trade allocation system supporting percentage-based and quantity-based allocation, pro-rata distribution, allocation templates, and regulatory compliance (e.g., best execution allocation rules).

## Acceptance Criteria

- [ ] Percentage-based allocation
- [ ] Quantity-based allocation
- [ ] Pro-rata allocation algorithm
- [ ] Allocation templates per account group
- [ ] Partial allocation support
- [ ] Allocation validation
- [ ] Fairness audit trail

## Technical Details

### Allocation DTOs

```typescript
// services/trade-service/src/dto/trade-allocation.dto.ts
import { IsUUID, IsNumber, IsString, IsOptional, IsArray, ValidateNested, Min, Max } from 'class-validator';
import { Type } from 'class-transformer';

export class AllocationItem {
  @IsUUID()
  accountId: string;

  @IsOptional()
  @IsNumber()
  @Min(0)
  quantity?: number;

  @IsOptional()
  @IsNumber()
  @Min(0)
  @Max(100)
  percentage?: number;

  @IsOptional()
  @IsString()
  reason?: string;
}

export class CreateAllocationDto {
  @IsUUID()
  tradeId: string;

  @IsArray()
  @ValidateNested({ each: true })
  @Type(() => AllocationItem)
  allocations: AllocationItem[];

  @IsOptional()
  @IsString()
  allocationMethod?: 'percentage' | 'quantity' | 'pro_rata' | 'template';

  @IsOptional()
  @IsUUID()
  templateId?: string;

  @IsOptional()
  @IsString()
  notes?: string;
}

export class AllocationTemplateDto {
  @IsString()
  name: string;

  @IsString()
  description: string;

  @IsArray()
  @ValidateNested({ each: true })
  @Type(() => AllocationItem)
  defaultAllocations: AllocationItem[];

  @IsOptional()
  @IsArray()
  accountGroupIds?: string[];
}
```

### Trade Allocation Entity

```typescript
// services/trade-service/src/entities/trade-allocation.entity.ts
import { Entity, PrimaryGeneratedColumn, Column, CreateDateColumn, UpdateDateColumn, ManyToOne, JoinColumn, Index } from 'typeorm';
import { TradeEntity } from './trade.entity';

export enum AllocationStatus {
  PENDING = 'pending',
  CONFIRMED = 'confirmed',
  REJECTED = 'rejected',
  SETTLED = 'settled',
}

@Entity('trade_allocations')
@Index(['tradeId'])
@Index(['accountId'])
@Index(['status'])
export class TradeAllocationEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column('uuid')
  tradeId: string;

  @Column('uuid')
  accountId: string;

  @Column('varchar', { length: 255 })
  accountName: string;

  @Column('decimal', { precision: 20, scale: 8 })
  quantity: number;

  @Column('decimal', { precision: 10, scale: 4 })
  percentage: number;

  @Column('decimal', { precision: 20, scale: 8 })
  price: number;

  @Column('decimal', { precision: 20, scale: 2 })
  notionalAmount: number;

  @Column('decimal', { precision: 20, scale: 4, default: 0 })
  fees: number;

  @Column({
    type: 'enum',
    enum: AllocationStatus,
    default: AllocationStatus.PENDING,
  })
  status: AllocationStatus;

  @Column('varchar', { length: 50, nullable: true })
  allocationMethod: string;

  @Column('text', { nullable: true })
  reason: string;

  @Column('jsonb', { nullable: true })
  metadata: Record<string, any>;

  @Column('uuid', { nullable: true })
  allocatedBy: string;

  @CreateDateColumn()
  allocatedAt: Date;

  @Column('uuid', { nullable: true })
  confirmedBy: string;

  @Column('timestamptz', { nullable: true })
  confirmedAt: Date;

  @Column('int', { default: 1 })
  version: number;

  @UpdateDateColumn()
  updatedAt: Date;

  @ManyToOne(() => TradeEntity, trade => trade.allocations)
  @JoinColumn({ name: 'tradeId' })
  trade: TradeEntity;
}
```

### Allocation Template Entity

```typescript
// services/trade-service/src/entities/allocation-template.entity.ts
import { Entity, PrimaryGeneratedColumn, Column, CreateDateColumn, UpdateDateColumn, Index } from 'typeorm';

@Entity('allocation_templates')
@Index(['tenantId', 'name'])
export class AllocationTemplateEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column('uuid')
  tenantId: string;

  @Column('varchar', { length: 100 })
  name: string;

  @Column('text', { nullable: true })
  description: string;

  @Column('boolean', { default: true })
  isActive: boolean;

  @Column('jsonb')
  allocations: Array<{
    accountId: string;
    accountName: string;
    percentage: number;
    priority?: number;
  }>;

  @Column('jsonb', { nullable: true })
  accountGroupIds: string[];

  @Column('jsonb', { nullable: true })
  rules: {
    minimumAllocation?: number;
    roundingMode?: 'up' | 'down' | 'nearest';
    residualAccount?: string;
  };

  @Column('uuid', { nullable: true })
  createdBy: string;

  @CreateDateColumn()
  createdAt: Date;

  @UpdateDateColumn()
  updatedAt: Date;
}
```

### Allocation Service

```typescript
// services/trade-service/src/services/trade-allocation.service.ts
import { Injectable, BadRequestException } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, DataSource, In } from 'typeorm';
import { logger, metrics } from '@orion/observability';
import { EventPublisher } from '@orion/events';
import { TradeEntity, TradeStatus } from '../entities/trade.entity';
import { TradeAllocationEntity, AllocationStatus } from '../entities/trade-allocation.entity';
import { AllocationTemplateEntity } from '../entities/allocation-template.entity';
import { CreateAllocationDto, AllocationItem } from '../dto/trade-allocation.dto';
import { AccountService } from '@orion/reference-data';
import { getCurrentUser, getCurrentTenant } from '@orion/security';

interface AllocationResult {
  trade: TradeEntity;
  allocations: TradeAllocationEntity[];
  summary: {
    totalAllocated: number;
    remainingQuantity: number;
    accountCount: number;
  };
}

@Injectable()
export class TradeAllocationService {
  constructor(
    @InjectRepository(TradeEntity)
    private readonly tradeRepo: Repository<TradeEntity>,
    @InjectRepository(TradeAllocationEntity)
    private readonly allocationRepo: Repository<TradeAllocationEntity>,
    @InjectRepository(AllocationTemplateEntity)
    private readonly templateRepo: Repository<AllocationTemplateEntity>,
    private readonly dataSource: DataSource,
    private readonly accountService: AccountService,
    private readonly eventPublisher: EventPublisher,
  ) {}

  async allocateTrade(dto: CreateAllocationDto): Promise<AllocationResult> {
    const queryRunner = this.dataSource.createQueryRunner();
    await queryRunner.connect();
    await queryRunner.startTransaction();

    try {
      // Get trade with lock
      const trade = await queryRunner.manager.findOne(TradeEntity, {
        where: { id: dto.tradeId },
        lock: { mode: 'pessimistic_write' },
      });

      if (!trade) {
        throw new BadRequestException(`Trade ${dto.tradeId} not found`);
      }

      // Validate trade can be allocated
      this.validateAllocatable(trade);

      // Resolve allocations (from template or direct input)
      let allocations: AllocationItem[];
      if (dto.templateId) {
        allocations = await this.resolveFromTemplate(dto.templateId, trade);
      } else if (dto.allocationMethod === 'pro_rata') {
        allocations = await this.calculateProRata(dto.allocations, trade);
      } else {
        allocations = dto.allocations;
      }

      // Validate allocations
      await this.validateAllocations(allocations, trade);

      // Create allocation records
      const allocationEntities: TradeAllocationEntity[] = [];
      let totalAllocated = 0;

      for (const alloc of allocations) {
        const quantity = alloc.quantity || (trade.quantity * (alloc.percentage! / 100));
        const notional = quantity * Number(trade.price);

        // Get account details
        const account = await this.accountService.getById(alloc.accountId);

        const entity = new TradeAllocationEntity();
        entity.tradeId = trade.id;
        entity.accountId = alloc.accountId;
        entity.accountName = account?.name || 'Unknown';
        entity.quantity = quantity;
        entity.percentage = alloc.percentage || (quantity / trade.quantity) * 100;
        entity.price = Number(trade.price);
        entity.notionalAmount = notional;
        entity.fees = this.calculateAllocationFees(trade, quantity);
        entity.status = AllocationStatus.PENDING;
        entity.allocationMethod = dto.allocationMethod || 'manual';
        entity.reason = alloc.reason;
        entity.allocatedBy = getCurrentUser()?.id;
        entity.metadata = {
          tradeRef: trade.tradeRef,
          originalQuantity: trade.quantity,
        };

        allocationEntities.push(entity);
        totalAllocated += quantity;
      }

      // Save allocations
      await queryRunner.manager.save(allocationEntities);

      // Update trade status
      trade.status = TradeStatus.ALLOCATED;
      trade.allocatedAt = new Date();
      trade.allocatedBy = getCurrentUser()?.id;
      trade.version += 1;

      await queryRunner.manager.save(trade);

      await queryRunner.commitTransaction();

      // Publish events
      await this.eventPublisher.publish('trade.allocated', {
        tradeId: trade.id,
        tradeRef: trade.tradeRef,
        allocationCount: allocationEntities.length,
        totalAllocated,
        allocations: allocationEntities.map(a => ({
          id: a.id,
          accountId: a.accountId,
          quantity: a.quantity,
          percentage: a.percentage,
        })),
        timestamp: new Date().toISOString(),
      });

      for (const alloc of allocationEntities) {
        await this.eventPublisher.publish('trade.allocation.created', {
          allocationId: alloc.id,
          tradeId: trade.id,
          accountId: alloc.accountId,
          quantity: alloc.quantity,
          notional: alloc.notionalAmount,
          timestamp: new Date().toISOString(),
        });
      }

      metrics.increment('trade.allocations', { 
        method: dto.allocationMethod || 'manual',
        accountCount: allocationEntities.length.toString(),
      });

      return {
        trade,
        allocations: allocationEntities,
        summary: {
          totalAllocated,
          remainingQuantity: trade.quantity - totalAllocated,
          accountCount: allocationEntities.length,
        },
      };
    } catch (error) {
      await queryRunner.rollbackTransaction();
      logger.error('Trade allocation failed', { tradeId: dto.tradeId, error });
      throw error;
    } finally {
      await queryRunner.release();
    }
  }

  async confirmAllocation(allocationId: string): Promise<TradeAllocationEntity> {
    const allocation = await this.allocationRepo.findOne({
      where: { id: allocationId, status: AllocationStatus.PENDING },
    });

    if (!allocation) {
      throw new BadRequestException(`Allocation ${allocationId} not found or not pending`);
    }

    allocation.status = AllocationStatus.CONFIRMED;
    allocation.confirmedBy = getCurrentUser()?.id;
    allocation.confirmedAt = new Date();
    allocation.version += 1;

    await this.allocationRepo.save(allocation);

    await this.eventPublisher.publish('trade.allocation.confirmed', {
      allocationId: allocation.id,
      tradeId: allocation.tradeId,
      accountId: allocation.accountId,
      timestamp: new Date().toISOString(),
    });

    return allocation;
  }

  async getTradeAllocations(tradeId: string): Promise<TradeAllocationEntity[]> {
    return this.allocationRepo.find({
      where: { tradeId },
      order: { allocatedAt: 'ASC' },
    });
  }

  async getAccountAllocations(
    accountId: string,
    options?: { fromDate?: Date; toDate?: Date; status?: AllocationStatus },
  ): Promise<TradeAllocationEntity[]> {
    const qb = this.allocationRepo.createQueryBuilder('a')
      .where('a.accountId = :accountId', { accountId });

    if (options?.fromDate) {
      qb.andWhere('a.allocatedAt >= :fromDate', { fromDate: options.fromDate });
    }
    if (options?.toDate) {
      qb.andWhere('a.allocatedAt <= :toDate', { toDate: options.toDate });
    }
    if (options?.status) {
      qb.andWhere('a.status = :status', { status: options.status });
    }

    return qb.orderBy('a.allocatedAt', 'DESC').getMany();
  }

  // Template management
  async createTemplate(dto: any): Promise<AllocationTemplateEntity> {
    const template = new AllocationTemplateEntity();
    Object.assign(template, dto);
    template.tenantId = getCurrentTenant()?.tenantId;
    template.createdBy = getCurrentUser()?.id;

    return this.templateRepo.save(template);
  }

  async getTemplates(): Promise<AllocationTemplateEntity[]> {
    return this.templateRepo.find({
      where: { isActive: true },
      order: { name: 'ASC' },
    });
  }

  private validateAllocatable(trade: TradeEntity): void {
    const allocatableStatuses = [TradeStatus.BOOKED, TradeStatus.VALIDATED];

    if (!allocatableStatuses.includes(trade.status)) {
      throw new BadRequestException(
        `Trade in status ${trade.status} cannot be allocated`,
      );
    }
  }

  private async validateAllocations(allocations: AllocationItem[], trade: TradeEntity): Promise<void> {
    // Check total percentage/quantity
    let totalPercentage = 0;
    let totalQuantity = 0;

    for (const alloc of allocations) {
      if (alloc.percentage) {
        totalPercentage += alloc.percentage;
      }
      if (alloc.quantity) {
        totalQuantity += alloc.quantity;
      }

      // Validate account exists and is active
      const account = await this.accountService.getById(alloc.accountId);
      if (!account) {
        throw new BadRequestException(`Account ${alloc.accountId} not found`);
      }
      if (account.status !== 'active') {
        throw new BadRequestException(`Account ${alloc.accountId} is not active`);
      }
    }

    // Percentage must sum to 100 (with small tolerance)
    if (totalPercentage > 0 && Math.abs(totalPercentage - 100) > 0.01) {
      throw new BadRequestException(
        `Allocation percentages must sum to 100%, got ${totalPercentage}%`,
      );
    }

    // Quantity cannot exceed trade quantity
    if (totalQuantity > 0 && totalQuantity > trade.quantity) {
      throw new BadRequestException(
        `Allocation quantity ${totalQuantity} exceeds trade quantity ${trade.quantity}`,
      );
    }
  }

  private async resolveFromTemplate(
    templateId: string,
    trade: TradeEntity,
  ): Promise<AllocationItem[]> {
    const template = await this.templateRepo.findOne({ where: { id: templateId } });

    if (!template) {
      throw new BadRequestException(`Template ${templateId} not found`);
    }

    return template.allocations.map(a => ({
      accountId: a.accountId,
      percentage: a.percentage,
    }));
  }

  private async calculateProRata(
    accounts: AllocationItem[],
    trade: TradeEntity,
  ): Promise<AllocationItem[]> {
    // Get AUM or target allocation for each account
    const accountDetails = await Promise.all(
      accounts.map(a => this.accountService.getById(a.accountId)),
    );

    const totalAum = accountDetails.reduce(
      (sum, a) => sum + (a?.targetAllocation || 0),
      0,
    );

    if (totalAum === 0) {
      // Equal split if no AUM data
      const equalPercentage = 100 / accounts.length;
      return accounts.map(a => ({
        accountId: a.accountId,
        percentage: equalPercentage,
      }));
    }

    return accounts.map((a, idx) => {
      const account = accountDetails[idx];
      const percentage = ((account?.targetAllocation || 0) / totalAum) * 100;
      return {
        accountId: a.accountId,
        percentage,
      };
    });
  }

  private calculateAllocationFees(trade: TradeEntity, quantity: number): number {
    if (!trade.totalFees) return 0;

    // Pro-rata fee allocation
    const feeRatio = quantity / trade.quantity;
    return Number(trade.totalFees) * feeRatio;
  }
}
```

### Allocation Controller

```typescript
// services/trade-service/src/controllers/trade-allocation.controller.ts
import { Controller, Get, Post, Body, Param, Query, UseGuards } from '@nestjs/common';
import { ApiTags, ApiOperation, ApiQuery } from '@nestjs/swagger';
import { JwtAuthGuard, PermissionGuard, RequirePermission } from '@orion/security';
import { TradeAllocationService } from '../services/trade-allocation.service';
import { CreateAllocationDto } from '../dto/trade-allocation.dto';

@ApiTags('Trade Allocations')
@Controller('api/v1/trades')
@UseGuards(JwtAuthGuard, PermissionGuard)
export class TradeAllocationController {
  constructor(private readonly allocationService: TradeAllocationService) {}

  @Post(':id/allocate')
  @RequirePermission('trade:allocate')
  @ApiOperation({ summary: 'Allocate trade to accounts' })
  async allocateTrade(
    @Param('id') tradeId: string,
    @Body() dto: Omit<CreateAllocationDto, 'tradeId'>,
  ) {
    return this.allocationService.allocateTrade({ ...dto, tradeId });
  }

  @Get(':id/allocations')
  @RequirePermission('trade:read')
  @ApiOperation({ summary: 'Get trade allocations' })
  async getTradeAllocations(@Param('id') tradeId: string) {
    return this.allocationService.getTradeAllocations(tradeId);
  }

  @Post('allocations/:allocationId/confirm')
  @RequirePermission('trade:allocate:confirm')
  @ApiOperation({ summary: 'Confirm allocation' })
  async confirmAllocation(@Param('allocationId') allocationId: string) {
    return this.allocationService.confirmAllocation(allocationId);
  }

  @Get('accounts/:accountId/allocations')
  @RequirePermission('trade:read')
  @ApiOperation({ summary: 'Get allocations for account' })
  @ApiQuery({ name: 'fromDate', required: false })
  @ApiQuery({ name: 'toDate', required: false })
  @ApiQuery({ name: 'status', required: false })
  async getAccountAllocations(
    @Param('accountId') accountId: string,
    @Query('fromDate') fromDate?: string,
    @Query('toDate') toDate?: string,
    @Query('status') status?: string,
  ) {
    return this.allocationService.getAccountAllocations(accountId, {
      fromDate: fromDate ? new Date(fromDate) : undefined,
      toDate: toDate ? new Date(toDate) : undefined,
      status: status as any,
    });
  }
}
```

### Allocation SQL Schema

```sql
-- migrations/009_trade_allocations_schema.sql

CREATE TABLE trade_allocations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trade_id UUID NOT NULL REFERENCES trades(id),
    account_id UUID NOT NULL,
    account_name VARCHAR(255) NOT NULL,
    quantity DECIMAL(20, 8) NOT NULL,
    percentage DECIMAL(10, 4) NOT NULL,
    price DECIMAL(20, 8) NOT NULL,
    notional_amount DECIMAL(20, 2) NOT NULL,
    fees DECIMAL(20, 4) DEFAULT 0,
    status VARCHAR(20) DEFAULT 'pending',
    allocation_method VARCHAR(50),
    reason TEXT,
    metadata JSONB,
    allocated_by UUID,
    allocated_at TIMESTAMPTZ DEFAULT NOW(),
    confirmed_by UUID,
    confirmed_at TIMESTAMPTZ,
    version INT DEFAULT 1,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE allocation_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    is_active BOOLEAN DEFAULT true,
    allocations JSONB NOT NULL,
    account_group_ids JSONB,
    rules JSONB,
    created_by UUID,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    
    CONSTRAINT allocation_templates_tenant_name UNIQUE (tenant_id, name)
);

-- Indexes
CREATE INDEX idx_allocations_trade ON trade_allocations(trade_id);
CREATE INDEX idx_allocations_account ON trade_allocations(account_id);
CREATE INDEX idx_allocations_status ON trade_allocations(status);
CREATE INDEX idx_allocations_allocated_at ON trade_allocations(allocated_at);

-- RLS policies
ALTER TABLE trade_allocations ENABLE ROW LEVEL SECURITY;
ALTER TABLE allocation_templates ENABLE ROW LEVEL SECURITY;

CREATE POLICY allocations_tenant_policy ON trade_allocations
    FOR ALL
    USING (
        EXISTS (
            SELECT 1 FROM trades t 
            WHERE t.id = trade_allocations.trade_id 
            AND t.tenant_id = current_setting('app.tenant_id')::uuid
        )
    );

CREATE POLICY templates_tenant_policy ON allocation_templates
    FOR ALL
    USING (tenant_id = current_setting('app.tenant_id')::uuid);
```

## Definition of Done

- [ ] Allocation entity and repository
- [ ] Percentage-based allocation
- [ ] Quantity-based allocation
- [ ] Pro-rata algorithm
- [ ] Template support
- [ ] Allocation validation
- [ ] Controller endpoints

## Dependencies

- **US-08-01**: Trade Entity
- **US-04-03**: Account Service

## Test Cases

```typescript
describe('TradeAllocationService', () => {
  it('should allocate by percentage', async () => {
    const trade = await createTrade({ quantity: 1000000 });
    
    const result = await allocationService.allocateTrade({
      tradeId: trade.id,
      allocationMethod: 'percentage',
      allocations: [
        { accountId: 'acc-1', percentage: 60 },
        { accountId: 'acc-2', percentage: 40 },
      ],
    });

    expect(result.allocations.length).toBe(2);
    expect(result.allocations[0].quantity).toBe(600000);
    expect(result.allocations[1].quantity).toBe(400000);
  });

  it('should allocate by quantity', async () => {
    const trade = await createTrade({ quantity: 1000000 });
    
    const result = await allocationService.allocateTrade({
      tradeId: trade.id,
      allocationMethod: 'quantity',
      allocations: [
        { accountId: 'acc-1', quantity: 700000 },
        { accountId: 'acc-2', quantity: 300000 },
      ],
    });

    expect(result.summary.totalAllocated).toBe(1000000);
  });

  it('should reject if percentages dont sum to 100', async () => {
    const trade = await createTrade();
    
    await expect(
      allocationService.allocateTrade({
        tradeId: trade.id,
        allocations: [
          { accountId: 'acc-1', percentage: 50 },
          { accountId: 'acc-2', percentage: 40 },
        ],
      }),
    ).rejects.toThrow('must sum to 100%');
  });

  it('should use template for allocation', async () => {
    const template = await createTemplate({
      allocations: [
        { accountId: 'acc-1', percentage: 50 },
        { accountId: 'acc-2', percentage: 50 },
      ],
    });
    const trade = await createTrade();
    
    const result = await allocationService.allocateTrade({
      tradeId: trade.id,
      allocationMethod: 'template',
      templateId: template.id,
    });

    expect(result.allocations.length).toBe(2);
  });
});
```
