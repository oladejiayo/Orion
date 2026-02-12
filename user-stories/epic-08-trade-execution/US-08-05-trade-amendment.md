# User Story: US-08-05 - Trade Amendment and Cancellation

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-08-05 |
| **Epic** | Epic 08 - Trade Execution |
| **Title** | Trade Amendment and Cancellation |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |
| **PRD Reference** | FR-Trade-05, NFR-Audit-01 |

## User Story

**As a** trader or operations user  
**I want** to amend or cancel trades  
**So that** I can correct errors and handle trade breaks

## Description

Implement trade amendment and cancellation with full version history, approval workflows for significant changes, and immutable audit trail.

## Acceptance Criteria

- [ ] Amendment creates new version
- [ ] Original trade preserved
- [ ] Amendment reason captured
- [ ] Approval workflow for significant changes
- [ ] Cancellation with reason
- [ ] Cascading updates to downstream systems
- [ ] Full audit trail

## Technical Details

### Amendment DTOs

```typescript
// services/trade-service/src/dto/trade-amendment.dto.ts
import { IsString, IsOptional, IsNumber, IsUUID, ValidateNested } from 'class-validator';
import { Type } from 'class-transformer';

export class AmendableFields {
  @IsOptional()
  @IsNumber()
  quantity?: number;

  @IsOptional()
  @IsNumber()
  price?: number;

  @IsOptional()
  @IsString()
  settlementDate?: string;

  @IsOptional()
  @IsString()
  counterpartyId?: string;

  @IsOptional()
  @IsString()
  clientReference?: string;

  @IsOptional()
  metadata?: Record<string, any>;
}

export class TradeAmendmentDto {
  @IsUUID()
  tradeId: string;

  @ValidateNested()
  @Type(() => AmendableFields)
  changes: AmendableFields;

  @IsString()
  reason: string;

  @IsOptional()
  @IsString()
  category?: 'correction' | 'late_booking' | 'client_request' | 'other';

  @IsOptional()
  @IsString()
  supportingDocumentId?: string;
}

export class TradeCancellationDto {
  @IsUUID()
  tradeId: string;

  @IsString()
  reason: string;

  @IsOptional()
  @IsString()
  category?: 'error' | 'duplicate' | 'client_request' | 'counterparty_request' | 'regulatory' | 'other';

  @IsOptional()
  @IsString()
  supportingDocumentId?: string;
}
```

### Trade Amendment Service

```typescript
// services/trade-service/src/services/trade-amendment.service.ts
import { Injectable, BadRequestException, ForbiddenException } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, DataSource } from 'typeorm';
import { logger, metrics } from '@orion/observability';
import { EventPublisher } from '@orion/events';
import { TradeEntity, TradeStatus } from '../entities/trade.entity';
import { TradeHistoryEntity } from '../entities/trade-history.entity';
import { TradeAmendmentDto, TradeCancellationDto } from '../dto/trade-amendment.dto';
import { TradeValidationService } from '../validation/trade-validation.service';
import { ApprovalService } from '@orion/workflow';
import { getCurrentUser, getCurrentTenant } from '@orion/security';

interface AmendmentResult {
  trade: TradeEntity;
  previousVersion: number;
  newVersion: number;
  changes: Record<string, { from: any; to: any }>;
  requiresApproval: boolean;
  approvalId?: string;
}

@Injectable()
export class TradeAmendmentService {
  constructor(
    @InjectRepository(TradeEntity)
    private readonly tradeRepo: Repository<TradeEntity>,
    @InjectRepository(TradeHistoryEntity)
    private readonly historyRepo: Repository<TradeHistoryEntity>,
    private readonly dataSource: DataSource,
    private readonly validationService: TradeValidationService,
    private readonly approvalService: ApprovalService,
    private readonly eventPublisher: EventPublisher,
  ) {}

  async amendTrade(dto: TradeAmendmentDto): Promise<AmendmentResult> {
    const queryRunner = this.dataSource.createQueryRunner();
    await queryRunner.connect();
    await queryRunner.startTransaction();

    try {
      // Get current trade with lock
      const trade = await queryRunner.manager.findOne(TradeEntity, {
        where: { id: dto.tradeId },
        lock: { mode: 'pessimistic_write' },
      });

      if (!trade) {
        throw new BadRequestException(`Trade ${dto.tradeId} not found`);
      }

      // Validate trade can be amended
      this.validateAmendable(trade);

      // Check permissions
      await this.checkAmendmentPermissions(trade, dto.changes);

      // Determine if approval required
      const requiresApproval = this.requiresApproval(trade, dto.changes);

      // Calculate changes
      const changes = this.calculateChanges(trade, dto.changes);

      // Save history before amendment
      await this.saveHistory(queryRunner.manager, trade, 'amendment', dto.reason);

      // Apply changes
      const previousVersion = trade.version;
      Object.assign(trade, dto.changes);
      trade.version = previousVersion + 1;
      trade.amendedAt = new Date();
      trade.amendedBy = getCurrentUser()?.id;
      trade.amendmentReason = dto.reason;
      trade.amendmentCategory = dto.category || 'other';

      if (requiresApproval) {
        trade.status = TradeStatus.PENDING_APPROVAL;
        trade.pendingChanges = dto.changes;
      } else {
        trade.status = TradeStatus.AMENDED;
      }

      // Re-validate amended trade
      const validation = await this.validationService.validateTrade(trade);
      if (!validation.valid) {
        throw new BadRequestException(
          `Amendment validation failed: ${validation.errors.map(e => e.message).join(', ')}`,
        );
      }

      // Save amended trade
      await queryRunner.manager.save(trade);

      // Create approval request if needed
      let approvalId: string | undefined;
      if (requiresApproval) {
        approvalId = await this.createApprovalRequest(trade, dto, changes);
      }

      await queryRunner.commitTransaction();

      // Publish event
      await this.eventPublisher.publish('trade.amended', {
        tradeId: trade.id,
        tradeRef: trade.tradeRef,
        previousVersion,
        newVersion: trade.version,
        changes,
        reason: dto.reason,
        requiresApproval,
        approvalId,
        amendedBy: trade.amendedBy,
        timestamp: new Date().toISOString(),
      });

      metrics.increment('trade.amendments', { 
        category: dto.category || 'other',
        requiresApproval: requiresApproval.toString(),
      });

      return {
        trade,
        previousVersion,
        newVersion: trade.version,
        changes,
        requiresApproval,
        approvalId,
      };
    } catch (error) {
      await queryRunner.rollbackTransaction();
      logger.error('Trade amendment failed', { tradeId: dto.tradeId, error });
      throw error;
    } finally {
      await queryRunner.release();
    }
  }

  async cancelTrade(dto: TradeCancellationDto): Promise<TradeEntity> {
    const queryRunner = this.dataSource.createQueryRunner();
    await queryRunner.connect();
    await queryRunner.startTransaction();

    try {
      // Get current trade with lock
      const trade = await queryRunner.manager.findOne(TradeEntity, {
        where: { id: dto.tradeId },
        lock: { mode: 'pessimistic_write' },
      });

      if (!trade) {
        throw new BadRequestException(`Trade ${dto.tradeId} not found`);
      }

      // Validate trade can be cancelled
      this.validateCancellable(trade);

      // Check permissions
      await this.checkCancellationPermissions(trade);

      // Save history before cancellation
      await this.saveHistory(queryRunner.manager, trade, 'cancellation', dto.reason);

      // Update trade status
      const previousStatus = trade.status;
      trade.status = TradeStatus.CANCELLED;
      trade.cancelledAt = new Date();
      trade.cancelledBy = getCurrentUser()?.id;
      trade.cancellationReason = dto.reason;
      trade.cancellationCategory = dto.category || 'other';
      trade.version += 1;

      await queryRunner.manager.save(trade);

      await queryRunner.commitTransaction();

      // Publish event
      await this.eventPublisher.publish('trade.cancelled', {
        tradeId: trade.id,
        tradeRef: trade.tradeRef,
        previousStatus,
        reason: dto.reason,
        category: dto.category,
        cancelledBy: trade.cancelledBy,
        timestamp: new Date().toISOString(),
      });

      metrics.increment('trade.cancellations', { 
        category: dto.category || 'other',
        previousStatus,
      });

      return trade;
    } catch (error) {
      await queryRunner.rollbackTransaction();
      logger.error('Trade cancellation failed', { tradeId: dto.tradeId, error });
      throw error;
    } finally {
      await queryRunner.release();
    }
  }

  async approveAmendment(tradeId: string, approvalId: string): Promise<TradeEntity> {
    const queryRunner = this.dataSource.createQueryRunner();
    await queryRunner.connect();
    await queryRunner.startTransaction();

    try {
      const trade = await queryRunner.manager.findOne(TradeEntity, {
        where: { id: tradeId, status: TradeStatus.PENDING_APPROVAL },
        lock: { mode: 'pessimistic_write' },
      });

      if (!trade) {
        throw new BadRequestException(`Trade ${tradeId} not pending approval`);
      }

      // Apply pending changes
      if (trade.pendingChanges) {
        Object.assign(trade, trade.pendingChanges);
        trade.pendingChanges = null;
      }

      trade.status = TradeStatus.AMENDED;
      trade.approvedAt = new Date();
      trade.approvedBy = getCurrentUser()?.id;

      await queryRunner.manager.save(trade);
      await queryRunner.commitTransaction();

      // Publish event
      await this.eventPublisher.publish('trade.amendment.approved', {
        tradeId: trade.id,
        approvalId,
        approvedBy: trade.approvedBy,
        timestamp: new Date().toISOString(),
      });

      return trade;
    } catch (error) {
      await queryRunner.rollbackTransaction();
      throw error;
    } finally {
      await queryRunner.release();
    }
  }

  async rejectAmendment(tradeId: string, approvalId: string, reason: string): Promise<TradeEntity> {
    const trade = await this.tradeRepo.findOne({
      where: { id: tradeId, status: TradeStatus.PENDING_APPROVAL },
    });

    if (!trade) {
      throw new BadRequestException(`Trade ${tradeId} not pending approval`);
    }

    // Revert to previous status
    trade.status = TradeStatus.BOOKED;
    trade.pendingChanges = null;
    trade.rejectedAt = new Date();
    trade.rejectedBy = getCurrentUser()?.id;
    trade.rejectionReason = reason;

    await this.tradeRepo.save(trade);

    // Publish event
    await this.eventPublisher.publish('trade.amendment.rejected', {
      tradeId: trade.id,
      approvalId,
      rejectedBy: trade.rejectedBy,
      reason,
      timestamp: new Date().toISOString(),
    });

    return trade;
  }

  private validateAmendable(trade: TradeEntity): void {
    const nonAmendableStatuses = [
      TradeStatus.SETTLED,
      TradeStatus.CANCELLED,
      TradeStatus.PENDING_APPROVAL,
    ];

    if (nonAmendableStatuses.includes(trade.status)) {
      throw new BadRequestException(
        `Trade in status ${trade.status} cannot be amended`,
      );
    }
  }

  private validateCancellable(trade: TradeEntity): void {
    const nonCancellableStatuses = [
      TradeStatus.SETTLED,
      TradeStatus.CANCELLED,
      TradeStatus.SETTLING,
    ];

    if (nonCancellableStatuses.includes(trade.status)) {
      throw new BadRequestException(
        `Trade in status ${trade.status} cannot be cancelled`,
      );
    }
  }

  private async checkAmendmentPermissions(trade: TradeEntity, changes: any): Promise<void> {
    const user = getCurrentUser();
    if (!user) throw new ForbiddenException('Not authenticated');

    // Check if user has amendment permission
    if (!user.permissions.includes('trade:amend')) {
      throw new ForbiddenException('No permission to amend trades');
    }

    // Price/quantity changes require elevated permission
    if ((changes.price || changes.quantity) && !user.permissions.includes('trade:amend:significant')) {
      throw new ForbiddenException('No permission for significant amendments');
    }
  }

  private async checkCancellationPermissions(trade: TradeEntity): Promise<void> {
    const user = getCurrentUser();
    if (!user) throw new ForbiddenException('Not authenticated');

    if (!user.permissions.includes('trade:cancel')) {
      throw new ForbiddenException('No permission to cancel trades');
    }
  }

  private requiresApproval(trade: TradeEntity, changes: any): boolean {
    const tenant = getCurrentTenant();
    const threshold = tenant?.config?.amendmentApprovalThreshold || 0;

    // Significant changes require approval
    if (changes.price) {
      const priceChange = Math.abs((changes.price - trade.price) / trade.price);
      if (priceChange > 0.01) return true; // >1% price change
    }

    if (changes.quantity) {
      const qtyChange = Math.abs((changes.quantity - trade.quantity) / trade.quantity);
      if (qtyChange > 0.1) return true; // >10% quantity change
    }

    // Notional threshold
    if (Number(trade.notionalAmount) > threshold) return true;

    return false;
  }

  private calculateChanges(trade: TradeEntity, changes: any): Record<string, { from: any; to: any }> {
    const result: Record<string, { from: any; to: any }> = {};

    for (const [key, newValue] of Object.entries(changes)) {
      if (newValue !== undefined && (trade as any)[key] !== newValue) {
        result[key] = {
          from: (trade as any)[key],
          to: newValue,
        };
      }
    }

    return result;
  }

  private async saveHistory(
    manager: any,
    trade: TradeEntity,
    action: string,
    reason: string,
  ): Promise<void> {
    const history = new TradeHistoryEntity();
    history.tradeId = trade.id;
    history.version = trade.version;
    history.action = action;
    history.snapshot = JSON.parse(JSON.stringify(trade));
    history.reason = reason;
    history.performedBy = getCurrentUser()?.id;
    history.performedAt = new Date();

    await manager.save(history);
  }

  private async createApprovalRequest(
    trade: TradeEntity,
    dto: TradeAmendmentDto,
    changes: Record<string, any>,
  ): Promise<string> {
    return this.approvalService.createRequest({
      type: 'trade_amendment',
      entityId: trade.id,
      entityType: 'trade',
      requestedBy: getCurrentUser()?.id,
      details: {
        tradeRef: trade.tradeRef,
        changes,
        reason: dto.reason,
        notionalAmount: trade.notionalAmount,
      },
      approvers: await this.getApprovers(trade),
    });
  }

  private async getApprovers(trade: TradeEntity): Promise<string[]> {
    // Get approvers based on trade attributes
    // This would typically query a role-based system
    return ['supervisor', 'compliance'];
  }
}
```

### Trade Amendment Controller

```typescript
// services/trade-service/src/controllers/trade-amendment.controller.ts
import { Controller, Post, Body, Param, UseGuards, HttpCode } from '@nestjs/common';
import { ApiTags, ApiOperation, ApiResponse } from '@nestjs/swagger';
import { JwtAuthGuard, PermissionGuard, RequirePermission } from '@orion/security';
import { TradeAmendmentService } from '../services/trade-amendment.service';
import { TradeAmendmentDto, TradeCancellationDto } from '../dto/trade-amendment.dto';

@ApiTags('Trade Amendments')
@Controller('api/v1/trades')
@UseGuards(JwtAuthGuard, PermissionGuard)
export class TradeAmendmentController {
  constructor(private readonly amendmentService: TradeAmendmentService) {}

  @Post(':id/amend')
  @HttpCode(200)
  @RequirePermission('trade:amend')
  @ApiOperation({ summary: 'Amend a trade' })
  @ApiResponse({ status: 200, description: 'Trade amended' })
  async amendTrade(
    @Param('id') tradeId: string,
    @Body() dto: Omit<TradeAmendmentDto, 'tradeId'>,
  ) {
    return this.amendmentService.amendTrade({ ...dto, tradeId });
  }

  @Post(':id/cancel')
  @HttpCode(200)
  @RequirePermission('trade:cancel')
  @ApiOperation({ summary: 'Cancel a trade' })
  @ApiResponse({ status: 200, description: 'Trade cancelled' })
  async cancelTrade(
    @Param('id') tradeId: string,
    @Body() dto: Omit<TradeCancellationDto, 'tradeId'>,
  ) {
    return this.amendmentService.cancelTrade({ ...dto, tradeId });
  }

  @Post(':id/amendments/:approvalId/approve')
  @HttpCode(200)
  @RequirePermission('trade:amend:approve')
  @ApiOperation({ summary: 'Approve trade amendment' })
  async approveAmendment(
    @Param('id') tradeId: string,
    @Param('approvalId') approvalId: string,
  ) {
    return this.amendmentService.approveAmendment(tradeId, approvalId);
  }

  @Post(':id/amendments/:approvalId/reject')
  @HttpCode(200)
  @RequirePermission('trade:amend:approve')
  @ApiOperation({ summary: 'Reject trade amendment' })
  async rejectAmendment(
    @Param('id') tradeId: string,
    @Param('approvalId') approvalId: string,
    @Body('reason') reason: string,
  ) {
    return this.amendmentService.rejectAmendment(tradeId, approvalId, reason);
  }
}
```

### Trade History Entity

```typescript
// services/trade-service/src/entities/trade-history.entity.ts
import { Entity, PrimaryGeneratedColumn, Column, CreateDateColumn, Index, ManyToOne, JoinColumn } from 'typeorm';
import { TradeEntity } from './trade.entity';

@Entity('trade_history')
@Index(['tradeId', 'version'])
@Index(['performedAt'])
export class TradeHistoryEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column('uuid')
  tradeId: string;

  @Column('int')
  version: number;

  @Column('varchar', { length: 50 })
  action: string; // 'created' | 'amendment' | 'cancellation' | 'status_change'

  @Column('jsonb')
  snapshot: Record<string, any>; // Full trade state at this version

  @Column('text', { nullable: true })
  reason: string;

  @Column('uuid', { nullable: true })
  performedBy: string;

  @CreateDateColumn()
  performedAt: Date;

  @Column('varchar', { length: 64, nullable: true })
  checksum: string; // SHA-256 of snapshot for integrity

  @ManyToOne(() => TradeEntity)
  @JoinColumn({ name: 'tradeId' })
  trade: TradeEntity;
}
```

### Trade History SQL Schema

```sql
-- migrations/008_trade_history_schema.sql

CREATE TABLE trade_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trade_id UUID NOT NULL REFERENCES trades(id),
    version INT NOT NULL,
    action VARCHAR(50) NOT NULL,
    snapshot JSONB NOT NULL,
    reason TEXT,
    performed_by UUID,
    performed_at TIMESTAMPTZ DEFAULT NOW(),
    checksum VARCHAR(64),
    
    CONSTRAINT trade_history_version_unique UNIQUE (trade_id, version)
);

-- Indexes
CREATE INDEX idx_trade_history_trade ON trade_history(trade_id);
CREATE INDEX idx_trade_history_performed_at ON trade_history(performed_at);
CREATE INDEX idx_trade_history_action ON trade_history(action);

-- Append-only trigger (prevent updates/deletes)
CREATE OR REPLACE FUNCTION prevent_history_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Trade history is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trade_history_immutable
BEFORE UPDATE OR DELETE ON trade_history
FOR EACH ROW EXECUTE FUNCTION prevent_history_modification();

-- RLS policy
ALTER TABLE trade_history ENABLE ROW LEVEL SECURITY;

CREATE POLICY trade_history_tenant_policy ON trade_history
    FOR ALL
    USING (
        EXISTS (
            SELECT 1 FROM trades t 
            WHERE t.id = trade_history.trade_id 
            AND t.tenant_id = current_setting('app.tenant_id')::uuid
        )
    );
```

## Definition of Done

- [ ] Amendment service with version control
- [ ] Cancellation service
- [ ] Approval workflow integration
- [ ] History entity and storage
- [ ] Controller endpoints
- [ ] Permission checks
- [ ] Event publishing

## Dependencies

- **US-08-01**: Trade Entity
- **US-08-03**: Trade Validation

## Test Cases

```typescript
describe('TradeAmendmentService', () => {
  it('should create new version on amendment', async () => {
    const trade = await createTrade({ price: 1.0850 });
    
    const result = await amendmentService.amendTrade({
      tradeId: trade.id,
      changes: { price: 1.0855 },
      reason: 'Price correction',
    });

    expect(result.newVersion).toBe(trade.version + 1);
    expect(result.trade.price).toBe(1.0855);
  });

  it('should preserve history on amendment', async () => {
    const trade = await createTrade();
    
    await amendmentService.amendTrade({
      tradeId: trade.id,
      changes: { quantity: 2000000 },
      reason: 'Quantity correction',
    });

    const history = await historyRepo.find({ where: { tradeId: trade.id } });
    expect(history.length).toBe(1);
    expect(history[0].snapshot.quantity).toBe(trade.quantity);
  });

  it('should require approval for significant changes', async () => {
    const trade = await createTrade({ price: 1.0850 });
    
    const result = await amendmentService.amendTrade({
      tradeId: trade.id,
      changes: { price: 1.1000 }, // >1% change
      reason: 'Major correction',
    });

    expect(result.requiresApproval).toBe(true);
    expect(result.trade.status).toBe(TradeStatus.PENDING_APPROVAL);
  });

  it('should cancel trade with reason', async () => {
    const trade = await createTrade();
    
    const cancelled = await amendmentService.cancelTrade({
      tradeId: trade.id,
      reason: 'Duplicate trade',
      category: 'duplicate',
    });

    expect(cancelled.status).toBe(TradeStatus.CANCELLED);
    expect(cancelled.cancellationReason).toBe('Duplicate trade');
  });
});
```
