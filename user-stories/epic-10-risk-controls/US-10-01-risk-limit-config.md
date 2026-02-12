# User Story: US-10-01 - Risk Limit Configuration

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-10-01 |
| **Epic** | Epic 10 - Risk & Controls |
| **Title** | Risk Limit Configuration |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |
| **PRD Reference** | FR-Risk-01, NFR-Risk-01 |

## User Story

**As a** risk manager  
**I want** to configure risk limits at various levels  
**So that** trading activity is controlled within defined boundaries

## Description

Implement risk limit configuration with hierarchical inheritance (tenant → client group → client → instrument), supporting multiple limit types, warning thresholds, and effective date management.

## Acceptance Criteria

- [ ] Configure limits at tenant/group/client/instrument levels
- [ ] Support all limit types (position, exposure, loss, order)
- [ ] Hierarchical limit inheritance
- [ ] Warning thresholds configuration
- [ ] Effective date management
- [ ] Limit override capability
- [ ] Audit trail for limit changes
- [ ] Redis cache synchronization

## Technical Details

### Risk Limit Entity

```typescript
// services/risk-service/src/entities/risk-limit.entity.ts
import { Entity, Column, CreateDateColumn, UpdateDateColumn, Index } from 'typeorm';

export enum RiskLimitType {
  POSITION_LIMIT = 'position_limit',
  GROSS_EXPOSURE = 'gross_exposure',
  NET_EXPOSURE = 'net_exposure',
  DAILY_LOSS = 'daily_loss',
  MAX_ORDER_SIZE = 'max_order_size',
  ORDER_RATE = 'order_rate',
  DAILY_TURNOVER = 'daily_turnover',
  OPEN_ORDERS = 'open_orders',
}

export enum RiskEntityType {
  TENANT = 'tenant',
  CLIENT_GROUP = 'client_group',
  CLIENT = 'client',
  INSTRUMENT = 'instrument',
}

@Entity('risk_limits')
@Index(['tenantId', 'entityType', 'entityId', 'limitType'], { unique: true })
@Index(['tenantId', 'entityType', 'isActive'])
export class RiskLimitEntity {
  @Column('uuid', { primaryKey: true, default: () => 'gen_random_uuid()' })
  id: string;

  @Column('uuid')
  tenantId: string;

  @Column('varchar', { length: 30 })
  entityType: RiskEntityType;

  @Column('uuid')
  entityId: string;

  @Column('varchar', { length: 30 })
  limitType: RiskLimitType;

  @Column('decimal', { precision: 20, scale: 4 })
  limitValue: number;

  @Column('decimal', { precision: 5, scale: 2, default: 80 })
  warningThreshold: number; // Percentage

  @Column('decimal', { precision: 5, scale: 2, default: 95 })
  criticalThreshold: number; // Percentage

  @Column('varchar', { length: 3, nullable: true })
  currency: string;

  @Column('varchar', { length: 50, nullable: true })
  instrumentId: string;

  @Column('boolean', { default: true })
  isActive: boolean;

  @Column('boolean', { default: false })
  isOverride: boolean; // Overrides parent limit

  @Column('timestamp with time zone', { default: () => 'CURRENT_TIMESTAMP' })
  effectiveFrom: Date;

  @Column('timestamp with time zone', { nullable: true })
  effectiveTo: Date;

  @Column('varchar', { length: 100 })
  createdBy: string;

  @Column('varchar', { length: 100, nullable: true })
  updatedBy: string;

  @Column('jsonb', { nullable: true })
  metadata: any;

  @CreateDateColumn()
  createdAt: Date;

  @UpdateDateColumn()
  updatedAt: Date;
}
```

### Risk Limit Audit Entity

```typescript
// services/risk-service/src/entities/risk-limit-audit.entity.ts
import { Entity, Column, CreateDateColumn } from 'typeorm';

@Entity('risk_limit_audit')
export class RiskLimitAuditEntity {
  @Column('uuid', { primaryKey: true, default: () => 'gen_random_uuid()' })
  id: string;

  @Column('uuid')
  limitId: string;

  @Column('uuid')
  tenantId: string;

  @Column('varchar', { length: 20 })
  action: 'created' | 'updated' | 'deleted' | 'activated' | 'deactivated';

  @Column('jsonb', { nullable: true })
  previousValues: any;

  @Column('jsonb', { nullable: true })
  newValues: any;

  @Column('varchar', { length: 100 })
  changedBy: string;

  @Column('varchar', { length: 500, nullable: true })
  reason: string;

  @CreateDateColumn()
  changedAt: Date;
}
```

### Risk Limit Service

```typescript
// services/risk-service/src/limits/risk-limit.service.ts
import { Injectable, BadRequestException, NotFoundException } from '@nestjs/common';
import { InjectRepository, InjectEntityManager } from '@nestjs/typeorm';
import { Repository, EntityManager, In } from 'typeorm';
import { Redis } from 'ioredis';
import { InjectRedis } from '@nestjs-modules/ioredis';
import { logger, metrics } from '@orion/observability';
import { RiskLimitEntity, RiskLimitType, RiskEntityType } from '../entities/risk-limit.entity';
import { RiskLimitAuditEntity } from '../entities/risk-limit-audit.entity';
import { CreateRiskLimitDto, UpdateRiskLimitDto } from '../dto/risk-limit.dto';

@Injectable()
export class RiskLimitService {
  private readonly cachePrefix = 'risk:limit:';
  private readonly cacheTTL = 300; // 5 minutes

  constructor(
    @InjectRepository(RiskLimitEntity)
    private readonly limitRepo: Repository<RiskLimitEntity>,
    @InjectRepository(RiskLimitAuditEntity)
    private readonly auditRepo: Repository<RiskLimitAuditEntity>,
    @InjectEntityManager()
    private readonly entityManager: EntityManager,
    @InjectRedis() private readonly redis: Redis,
  ) {}

  /**
   * Create a new risk limit
   */
  async createLimit(dto: CreateRiskLimitDto, userId: string): Promise<RiskLimitEntity> {
    return this.entityManager.transaction(async (manager) => {
      // Check for duplicate
      const existing = await manager.findOne(RiskLimitEntity, {
        where: {
          tenantId: dto.tenantId,
          entityType: dto.entityType,
          entityId: dto.entityId,
          limitType: dto.limitType,
          isActive: true,
        },
      });

      if (existing) {
        throw new BadRequestException(
          `Active limit already exists for ${dto.limitType} on ${dto.entityType}:${dto.entityId}`,
        );
      }

      // Validate limit value
      this.validateLimitValue(dto.limitType, dto.limitValue);

      // Create limit
      const limit = manager.create(RiskLimitEntity, {
        ...dto,
        createdBy: userId,
      });
      await manager.save(limit);

      // Create audit record
      await this.createAuditRecord(manager, limit, 'created', null, limit, userId);

      // Update cache
      await this.updateLimitCache(limit);

      logger.info('Risk limit created', {
        limitId: limit.id,
        entityType: dto.entityType,
        entityId: dto.entityId,
        limitType: dto.limitType,
        limitValue: dto.limitValue,
      });

      metrics.increment('risk.limit.created');

      return limit;
    });
  }

  /**
   * Update an existing risk limit
   */
  async updateLimit(
    limitId: string,
    dto: UpdateRiskLimitDto,
    userId: string,
    reason?: string,
  ): Promise<RiskLimitEntity> {
    return this.entityManager.transaction(async (manager) => {
      const limit = await manager.findOne(RiskLimitEntity, {
        where: { id: limitId },
        lock: { mode: 'pessimistic_write' },
      });

      if (!limit) {
        throw new NotFoundException(`Risk limit not found: ${limitId}`);
      }

      const previousValues = { ...limit };

      // Update fields
      if (dto.limitValue !== undefined) {
        this.validateLimitValue(limit.limitType as RiskLimitType, dto.limitValue);
        limit.limitValue = dto.limitValue;
      }
      if (dto.warningThreshold !== undefined) {
        limit.warningThreshold = dto.warningThreshold;
      }
      if (dto.criticalThreshold !== undefined) {
        limit.criticalThreshold = dto.criticalThreshold;
      }
      if (dto.isActive !== undefined) {
        limit.isActive = dto.isActive;
      }
      if (dto.effectiveTo !== undefined) {
        limit.effectiveTo = dto.effectiveTo;
      }

      limit.updatedBy = userId;
      await manager.save(limit);

      // Create audit record
      await this.createAuditRecord(
        manager,
        limit,
        'updated',
        previousValues,
        limit,
        userId,
        reason,
      );

      // Update cache
      await this.updateLimitCache(limit);

      logger.info('Risk limit updated', {
        limitId: limit.id,
        changes: dto,
      });

      metrics.increment('risk.limit.updated');

      return limit;
    });
  }

  /**
   * Get effective limit for entity (with inheritance)
   */
  async getEffectiveLimit(
    tenantId: string,
    entityType: RiskEntityType,
    entityId: string,
    limitType: RiskLimitType,
    instrumentId?: string,
  ): Promise<RiskLimitEntity | null> {
    // Try cache first
    const cacheKey = this.buildCacheKey(tenantId, entityType, entityId, limitType, instrumentId);
    const cached = await this.redis.get(cacheKey);
    if (cached) {
      return JSON.parse(cached);
    }

    // Build hierarchy for lookup
    const hierarchy = await this.buildEntityHierarchy(tenantId, entityType, entityId);

    // Search from most specific to most general
    for (const level of hierarchy) {
      const limit = await this.limitRepo.findOne({
        where: {
          tenantId,
          entityType: level.type,
          entityId: level.id,
          limitType,
          isActive: true,
          instrumentId: instrumentId || null,
        },
        order: { effectiveFrom: 'DESC' },
      });

      if (limit && this.isEffective(limit)) {
        // Cache result
        await this.redis.setex(cacheKey, this.cacheTTL, JSON.stringify(limit));
        return limit;
      }
    }

    // Check tenant-level default
    const tenantLimit = await this.limitRepo.findOne({
      where: {
        tenantId,
        entityType: RiskEntityType.TENANT,
        entityId: tenantId,
        limitType,
        isActive: true,
      },
    });

    if (tenantLimit && this.isEffective(tenantLimit)) {
      await this.redis.setex(cacheKey, this.cacheTTL, JSON.stringify(tenantLimit));
      return tenantLimit;
    }

    return null;
  }

  /**
   * Get all limits for an entity
   */
  async getLimitsForEntity(
    tenantId: string,
    entityType: RiskEntityType,
    entityId: string,
  ): Promise<RiskLimitEntity[]> {
    return this.limitRepo.find({
      where: {
        tenantId,
        entityType,
        entityId,
        isActive: true,
      },
      order: { limitType: 'ASC' },
    });
  }

  /**
   * Bulk update limits (for mass changes)
   */
  async bulkUpdateLimits(
    tenantId: string,
    limitType: RiskLimitType,
    multiplier: number,
    userId: string,
    reason: string,
  ): Promise<{ updated: number }> {
    const limits = await this.limitRepo.find({
      where: {
        tenantId,
        limitType,
        isActive: true,
      },
    });

    let updated = 0;
    for (const limit of limits) {
      await this.updateLimit(
        limit.id,
        { limitValue: Number(limit.limitValue) * multiplier },
        userId,
        reason,
      );
      updated++;
    }

    logger.info('Bulk limit update completed', {
      tenantId,
      limitType,
      multiplier,
      updated,
    });

    return { updated };
  }

  /**
   * Build entity hierarchy for inheritance lookup
   */
  private async buildEntityHierarchy(
    tenantId: string,
    entityType: RiskEntityType,
    entityId: string,
  ): Promise<{ type: RiskEntityType; id: string }[]> {
    const hierarchy: { type: RiskEntityType; id: string }[] = [];

    // Add current entity
    hierarchy.push({ type: entityType, id: entityId });

    if (entityType === RiskEntityType.CLIENT) {
      // Get client's group
      const clientGroup = await this.getClientGroup(entityId);
      if (clientGroup) {
        hierarchy.push({ type: RiskEntityType.CLIENT_GROUP, id: clientGroup.id });
      }
    }

    // Tenant is always at the top
    hierarchy.push({ type: RiskEntityType.TENANT, id: tenantId });

    return hierarchy;
  }

  private async getClientGroup(clientId: string): Promise<{ id: string } | null> {
    // Would call client service to get group
    return null;
  }

  private isEffective(limit: RiskLimitEntity): boolean {
    const now = new Date();
    if (limit.effectiveFrom > now) return false;
    if (limit.effectiveTo && limit.effectiveTo < now) return false;
    return true;
  }

  private validateLimitValue(limitType: RiskLimitType, value: number): void {
    if (value < 0) {
      throw new BadRequestException('Limit value cannot be negative');
    }

    // Type-specific validation
    switch (limitType) {
      case RiskLimitType.ORDER_RATE:
        if (value > 10000) {
          throw new BadRequestException('Order rate limit cannot exceed 10000/min');
        }
        break;
    }
  }

  private buildCacheKey(
    tenantId: string,
    entityType: string,
    entityId: string,
    limitType: string,
    instrumentId?: string,
  ): string {
    const parts = [this.cachePrefix, tenantId, entityType, entityId, limitType];
    if (instrumentId) parts.push(instrumentId);
    return parts.join(':');
  }

  private async updateLimitCache(limit: RiskLimitEntity): Promise<void> {
    const cacheKey = this.buildCacheKey(
      limit.tenantId,
      limit.entityType,
      limit.entityId,
      limit.limitType,
      limit.instrumentId,
    );

    if (limit.isActive && this.isEffective(limit)) {
      await this.redis.setex(cacheKey, this.cacheTTL, JSON.stringify(limit));
    } else {
      await this.redis.del(cacheKey);
    }

    // Publish cache invalidation event
    await this.redis.publish('risk:limit:changed', JSON.stringify({
      limitId: limit.id,
      entityType: limit.entityType,
      entityId: limit.entityId,
      limitType: limit.limitType,
    }));
  }

  private async createAuditRecord(
    manager: EntityManager,
    limit: RiskLimitEntity,
    action: string,
    previousValues: any,
    newValues: any,
    userId: string,
    reason?: string,
  ): Promise<void> {
    const audit = manager.create(RiskLimitAuditEntity, {
      limitId: limit.id,
      tenantId: limit.tenantId,
      action,
      previousValues,
      newValues,
      changedBy: userId,
      reason,
    });
    await manager.save(audit);
  }
}
```

### Risk Limit DTOs

```typescript
// services/risk-service/src/dto/risk-limit.dto.ts
import { IsUUID, IsString, IsNumber, IsEnum, IsOptional, IsBoolean, Min, Max } from 'class-validator';
import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { RiskLimitType, RiskEntityType } from '../entities/risk-limit.entity';

export class CreateRiskLimitDto {
  @ApiProperty()
  @IsUUID()
  tenantId: string;

  @ApiProperty({ enum: RiskEntityType })
  @IsEnum(RiskEntityType)
  entityType: RiskEntityType;

  @ApiProperty()
  @IsUUID()
  entityId: string;

  @ApiProperty({ enum: RiskLimitType })
  @IsEnum(RiskLimitType)
  limitType: RiskLimitType;

  @ApiProperty()
  @IsNumber()
  @Min(0)
  limitValue: number;

  @ApiPropertyOptional({ default: 80 })
  @IsOptional()
  @IsNumber()
  @Min(0)
  @Max(100)
  warningThreshold?: number;

  @ApiPropertyOptional({ default: 95 })
  @IsOptional()
  @IsNumber()
  @Min(0)
  @Max(100)
  criticalThreshold?: number;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  currency?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  instrumentId?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsBoolean()
  isOverride?: boolean;
}

export class UpdateRiskLimitDto {
  @ApiPropertyOptional()
  @IsOptional()
  @IsNumber()
  @Min(0)
  limitValue?: number;

  @ApiPropertyOptional()
  @IsOptional()
  @IsNumber()
  @Min(0)
  @Max(100)
  warningThreshold?: number;

  @ApiPropertyOptional()
  @IsOptional()
  @IsNumber()
  @Min(0)
  @Max(100)
  criticalThreshold?: number;

  @ApiPropertyOptional()
  @IsOptional()
  @IsBoolean()
  isActive?: boolean;

  @ApiPropertyOptional()
  @IsOptional()
  effectiveTo?: Date;
}
```

## Database Schema

```sql
-- Risk limits table
CREATE TABLE risk_limits (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    entity_type VARCHAR(30) NOT NULL,
    entity_id UUID NOT NULL,
    limit_type VARCHAR(30) NOT NULL,
    limit_value DECIMAL(20, 4) NOT NULL,
    warning_threshold DECIMAL(5, 2) DEFAULT 80,
    critical_threshold DECIMAL(5, 2) DEFAULT 95,
    currency VARCHAR(3),
    instrument_id VARCHAR(50),
    is_active BOOLEAN DEFAULT true,
    is_override BOOLEAN DEFAULT false,
    effective_from TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    effective_to TIMESTAMP WITH TIME ZONE,
    created_by VARCHAR(100) NOT NULL,
    updated_by VARCHAR(100),
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(tenant_id, entity_type, entity_id, limit_type)
);

CREATE INDEX idx_risk_limits_entity ON risk_limits(tenant_id, entity_type, entity_id);
CREATE INDEX idx_risk_limits_type ON risk_limits(tenant_id, limit_type, is_active);

-- Risk limit audit table
CREATE TABLE risk_limit_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    limit_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    action VARCHAR(20) NOT NULL,
    previous_values JSONB,
    new_values JSONB,
    changed_by VARCHAR(100) NOT NULL,
    reason VARCHAR(500),
    changed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_risk_limit_audit_limit ON risk_limit_audit(limit_id, changed_at DESC);
```

## Definition of Done

- [ ] Limit entity and repository
- [ ] CRUD operations for limits
- [ ] Hierarchical inheritance
- [ ] Warning/critical thresholds
- [ ] Effective date management
- [ ] Redis cache synchronization
- [ ] Audit trail
- [ ] Bulk update capability

## Dependencies

- **US-06-01**: Client Entity (for hierarchy)

## Test Cases

```typescript
describe('RiskLimitService', () => {
  it('should create a new risk limit', async () => {
    const dto = {
      tenantId: 'tenant-1',
      entityType: RiskEntityType.CLIENT,
      entityId: 'client-1',
      limitType: RiskLimitType.POSITION_LIMIT,
      limitValue: 1000000,
    };

    const limit = await limitService.createLimit(dto, 'user-1');

    expect(limit.id).toBeDefined();
    expect(limit.limitValue).toBe(1000000);
  });

  it('should inherit limit from parent entity', async () => {
    // Set tenant limit
    await limitService.createLimit({
      tenantId: 'tenant-1',
      entityType: RiskEntityType.TENANT,
      entityId: 'tenant-1',
      limitType: RiskLimitType.GROSS_EXPOSURE,
      limitValue: 50000000,
    }, 'user-1');

    // Client has no specific limit, should inherit
    const limit = await limitService.getEffectiveLimit(
      'tenant-1',
      RiskEntityType.CLIENT,
      'client-1',
      RiskLimitType.GROSS_EXPOSURE,
    );

    expect(limit.limitValue).toBe(50000000);
  });

  it('should override parent limit when specified', async () => {
    // Client-specific override
    await limitService.createLimit({
      tenantId: 'tenant-1',
      entityType: RiskEntityType.CLIENT,
      entityId: 'client-1',
      limitType: RiskLimitType.GROSS_EXPOSURE,
      limitValue: 10000000,
      isOverride: true,
    }, 'user-1');

    const limit = await limitService.getEffectiveLimit(
      'tenant-1',
      RiskEntityType.CLIENT,
      'client-1',
      RiskLimitType.GROSS_EXPOSURE,
    );

    expect(limit.limitValue).toBe(10000000);
  });

  it('should create audit record on update', async () => {
    const limit = await createLimit();

    await limitService.updateLimit(
      limit.id,
      { limitValue: 2000000 },
      'user-1',
      'Increased limit per client request',
    );

    const audits = await auditRepo.find({ where: { limitId: limit.id } });
    expect(audits.length).toBe(2); // create + update
    expect(audits[1].reason).toBe('Increased limit per client request');
  });
});
```
