# User Story: US-10-04 - Position Limit Management

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-10-04 |
| **Epic** | Epic 10 - Risk & Controls |
| **Title** | Position Limit Management |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |
| **PRD Reference** | FR-Risk-04, NFR-Risk-01 |

## User Story

**As a** risk manager  
**I want** granular position limit controls per instrument and client  
**So that** concentration risk is managed across the platform

## Description

Implement position limit management supporting per-instrument limits, aggregate limits, currency-based limits, and automatic limit adjustments based on market conditions.

## Acceptance Criteria

- [ ] Per-instrument position limits
- [ ] Per-client aggregate limits
- [ ] Currency exposure limits
- [ ] Long/short separate limits
- [ ] Limit utilization tracking
- [ ] Temporary limit increases
- [ ] Limit templates for client tiers
- [ ] Automatic limit adjustments

## Technical Details

### Position Limit Entity Extension

```typescript
// services/risk-service/src/entities/position-limit.entity.ts
import { Entity, Column, CreateDateColumn, UpdateDateColumn, Index } from 'typeorm';

@Entity('position_limits')
@Index(['tenantId', 'clientId', 'instrumentId'], { unique: true })
export class PositionLimitEntity {
  @Column('uuid', { primaryKey: true, default: () => 'gen_random_uuid()' })
  id: string;

  @Column('uuid')
  tenantId: string;

  @Column('uuid')
  clientId: string;

  @Column('varchar', { length: 50, nullable: true })
  instrumentId: string; // null = all instruments

  @Column('varchar', { length: 3, nullable: true })
  currency: string; // For currency-based limits

  @Column('decimal', { precision: 20, scale: 4 })
  maxLongPosition: number;

  @Column('decimal', { precision: 20, scale: 4 })
  maxShortPosition: number;

  @Column('decimal', { precision: 20, scale: 4, nullable: true })
  maxGrossPosition: number; // Long + Short absolute

  @Column('decimal', { precision: 20, scale: 4, nullable: true })
  maxNetPosition: number; // Long - Short absolute

  @Column('decimal', { precision: 5, scale: 2, default: 80 })
  warningThreshold: number;

  @Column('decimal', { precision: 5, scale: 2, default: 95 })
  softLimitThreshold: number;

  @Column('boolean', { default: true })
  hardLimitEnabled: boolean;

  @Column('boolean', { default: false })
  isTemporary: boolean;

  @Column('timestamp with time zone', { nullable: true })
  temporaryExpiresAt: Date;

  @Column('uuid', { nullable: true })
  templateId: string;

  @Column('boolean', { default: true })
  isActive: boolean;

  @Column('jsonb', { nullable: true })
  metadata: any;

  @CreateDateColumn()
  createdAt: Date;

  @UpdateDateColumn()
  updatedAt: Date;
}
```

### Limit Template Entity

```typescript
// services/risk-service/src/entities/limit-template.entity.ts
import { Entity, Column, CreateDateColumn, UpdateDateColumn } from 'typeorm';

@Entity('limit_templates')
export class LimitTemplateEntity {
  @Column('uuid', { primaryKey: true, default: () => 'gen_random_uuid()' })
  id: string;

  @Column('uuid')
  tenantId: string;

  @Column('varchar', { length: 100 })
  name: string;

  @Column('varchar', { length: 50 })
  tier: string; // e.g., 'bronze', 'silver', 'gold', 'platinum'

  @Column('jsonb')
  limits: {
    defaultMaxLong: number;
    defaultMaxShort: number;
    defaultMaxGross: number;
    perInstrumentMultiplier: number;
    currencyLimits: Record<string, number>;
  };

  @Column('boolean', { default: true })
  isActive: boolean;

  @CreateDateColumn()
  createdAt: Date;

  @UpdateDateColumn()
  updatedAt: Date;
}
```

### Position Limit Service

```typescript
// services/risk-service/src/limits/position-limit.service.ts
import { Injectable, BadRequestException, NotFoundException } from '@nestjs/common';
import { InjectRepository, InjectEntityManager } from '@nestjs/typeorm';
import { Repository, EntityManager, IsNull } from 'typeorm';
import { Redis } from 'ioredis';
import { InjectRedis } from '@nestjs-modules/ioredis';
import { Cron, CronExpression } from '@nestjs/schedule';
import { logger, metrics } from '@orion/observability';
import { PositionLimitEntity } from '../entities/position-limit.entity';
import { LimitTemplateEntity } from '../entities/limit-template.entity';
import { RiskCacheService } from '../cache/risk-cache.service';

export interface PositionLimitCheck {
  instrumentId: string;
  side: 'long' | 'short';
  currentPosition: number;
  projectedPosition: number;
  limitValue: number;
  utilizationPercent: number;
  status: 'within' | 'warning' | 'soft_breach' | 'hard_breach';
  canTrade: boolean;
}

@Injectable()
export class PositionLimitService {
  private readonly cachePrefix = 'pos:limit:';

  constructor(
    @InjectRepository(PositionLimitEntity)
    private readonly limitRepo: Repository<PositionLimitEntity>,
    @InjectRepository(LimitTemplateEntity)
    private readonly templateRepo: Repository<LimitTemplateEntity>,
    @InjectEntityManager()
    private readonly entityManager: EntityManager,
    @InjectRedis() private readonly redis: Redis,
    private readonly riskCache: RiskCacheService,
  ) {}

  /**
   * Check position limit for an order
   */
  async checkPositionLimit(
    tenantId: string,
    clientId: string,
    instrumentId: string,
    side: 'buy' | 'sell',
    quantity: number,
  ): Promise<PositionLimitCheck> {
    // Get current position
    const currentPosition = await this.riskCache.getPosition(clientId, instrumentId);

    // Calculate projected position
    const projectedPosition = side === 'buy'
      ? currentPosition + quantity
      : currentPosition - quantity;

    // Get applicable limit
    const limit = await this.getEffectiveLimit(tenantId, clientId, instrumentId);

    if (!limit) {
      return {
        instrumentId,
        side: projectedPosition >= 0 ? 'long' : 'short',
        currentPosition,
        projectedPosition: Math.abs(projectedPosition),
        limitValue: Infinity,
        utilizationPercent: 0,
        status: 'within',
        canTrade: true,
      };
    }

    const isLong = projectedPosition >= 0;
    const absProjected = Math.abs(projectedPosition);
    const limitValue = isLong
      ? Number(limit.maxLongPosition)
      : Number(limit.maxShortPosition);

    const utilizationPercent = (absProjected / limitValue) * 100;

    let status: 'within' | 'warning' | 'soft_breach' | 'hard_breach';
    let canTrade = true;

    if (absProjected > limitValue) {
      status = 'hard_breach';
      canTrade = !limit.hardLimitEnabled;
    } else if (utilizationPercent >= Number(limit.softLimitThreshold)) {
      status = 'soft_breach';
      canTrade = true; // Soft breach allows trading with warning
    } else if (utilizationPercent >= Number(limit.warningThreshold)) {
      status = 'warning';
      canTrade = true;
    } else {
      status = 'within';
    }

    return {
      instrumentId,
      side: isLong ? 'long' : 'short',
      currentPosition,
      projectedPosition: absProjected,
      limitValue,
      utilizationPercent,
      status,
      canTrade,
    };
  }

  /**
   * Get effective limit for client/instrument
   */
  async getEffectiveLimit(
    tenantId: string,
    clientId: string,
    instrumentId: string,
  ): Promise<PositionLimitEntity | null> {
    // Try cache first
    const cacheKey = `${this.cachePrefix}${clientId}:${instrumentId}`;
    const cached = await this.redis.get(cacheKey);
    if (cached) {
      return JSON.parse(cached);
    }

    // Check for instrument-specific limit
    let limit = await this.limitRepo.findOne({
      where: {
        tenantId,
        clientId,
        instrumentId,
        isActive: true,
      },
    });

    // Fall back to general client limit
    if (!limit) {
      limit = await this.limitRepo.findOne({
        where: {
          tenantId,
          clientId,
          instrumentId: IsNull(),
          isActive: true,
        },
      });
    }

    // Check if temporary limit is expired
    if (limit?.isTemporary && limit.temporaryExpiresAt < new Date()) {
      limit = null;
    }

    // Cache result
    if (limit) {
      await this.redis.setex(cacheKey, 300, JSON.stringify(limit));
    }

    return limit;
  }

  /**
   * Create position limit
   */
  async createLimit(
    tenantId: string,
    clientId: string,
    instrumentId: string | null,
    limits: {
      maxLongPosition: number;
      maxShortPosition: number;
      maxGrossPosition?: number;
      maxNetPosition?: number;
    },
    userId: string,
  ): Promise<PositionLimitEntity> {
    const existing = await this.limitRepo.findOne({
      where: {
        tenantId,
        clientId,
        instrumentId: instrumentId || IsNull(),
        isActive: true,
      },
    });

    if (existing) {
      throw new BadRequestException('Position limit already exists');
    }

    const limit = this.limitRepo.create({
      tenantId,
      clientId,
      instrumentId,
      ...limits,
    });

    await this.limitRepo.save(limit);
    await this.invalidateCache(clientId, instrumentId);

    logger.info('Position limit created', {
      limitId: limit.id,
      clientId,
      instrumentId,
    });

    return limit;
  }

  /**
   * Grant temporary limit increase
   */
  async grantTemporaryIncrease(
    limitId: string,
    multiplier: number,
    expiresAt: Date,
    userId: string,
    reason: string,
  ): Promise<PositionLimitEntity> {
    return this.entityManager.transaction(async (manager) => {
      const originalLimit = await manager.findOne(PositionLimitEntity, {
        where: { id: limitId },
      });

      if (!originalLimit) {
        throw new NotFoundException(`Limit not found: ${limitId}`);
      }

      // Create temporary limit
      const tempLimit = manager.create(PositionLimitEntity, {
        tenantId: originalLimit.tenantId,
        clientId: originalLimit.clientId,
        instrumentId: originalLimit.instrumentId,
        maxLongPosition: Number(originalLimit.maxLongPosition) * multiplier,
        maxShortPosition: Number(originalLimit.maxShortPosition) * multiplier,
        maxGrossPosition: originalLimit.maxGrossPosition
          ? Number(originalLimit.maxGrossPosition) * multiplier
          : null,
        isTemporary: true,
        temporaryExpiresAt: expiresAt,
        metadata: {
          originalLimitId: limitId,
          multiplier,
          grantedBy: userId,
          reason,
        },
      });

      // Deactivate original
      originalLimit.isActive = false;
      await manager.save(originalLimit);

      await manager.save(tempLimit);
      await this.invalidateCache(tempLimit.clientId, tempLimit.instrumentId);

      logger.info('Temporary limit increase granted', {
        originalLimitId: limitId,
        tempLimitId: tempLimit.id,
        multiplier,
        expiresAt,
      });

      return tempLimit;
    });
  }

  /**
   * Apply template to client
   */
  async applyTemplate(
    templateId: string,
    clientId: string,
    tenantId: string,
  ): Promise<PositionLimitEntity[]> {
    const template = await this.templateRepo.findOne({
      where: { id: templateId, isActive: true },
    });

    if (!template) {
      throw new NotFoundException(`Template not found: ${templateId}`);
    }

    // Create default limit from template
    const defaultLimit = this.limitRepo.create({
      tenantId,
      clientId,
      maxLongPosition: template.limits.defaultMaxLong,
      maxShortPosition: template.limits.defaultMaxShort,
      maxGrossPosition: template.limits.defaultMaxGross,
      templateId: template.id,
    });

    await this.limitRepo.save(defaultLimit);

    logger.info('Template applied to client', {
      templateId,
      clientId,
      tier: template.tier,
    });

    return [defaultLimit];
  }

  /**
   * Get position utilization summary
   */
  async getUtilizationSummary(
    tenantId: string,
    clientId: string,
  ): Promise<{
    instruments: Array<{
      instrumentId: string;
      currentPosition: number;
      limitValue: number;
      utilizationPercent: number;
    }>;
    aggregate: {
      totalUtilization: number;
      highestUtilization: number;
      instrumentsAtWarning: number;
    };
  }> {
    // Get all limits for client
    const limits = await this.limitRepo.find({
      where: { tenantId, clientId, isActive: true },
    });

    const instruments: any[] = [];
    let highestUtilization = 0;
    let instrumentsAtWarning = 0;

    for (const limit of limits) {
      if (!limit.instrumentId) continue;

      const position = await this.riskCache.getPosition(clientId, limit.instrumentId);
      const isLong = position >= 0;
      const absPosition = Math.abs(position);
      const limitValue = isLong
        ? Number(limit.maxLongPosition)
        : Number(limit.maxShortPosition);

      const utilizationPercent = limitValue > 0
        ? (absPosition / limitValue) * 100
        : 0;

      instruments.push({
        instrumentId: limit.instrumentId,
        currentPosition: absPosition,
        limitValue,
        utilizationPercent,
      });

      highestUtilization = Math.max(highestUtilization, utilizationPercent);
      if (utilizationPercent >= Number(limit.warningThreshold)) {
        instrumentsAtWarning++;
      }
    }

    const totalUtilization = instruments.length > 0
      ? instruments.reduce((sum, i) => sum + i.utilizationPercent, 0) / instruments.length
      : 0;

    return {
      instruments,
      aggregate: {
        totalUtilization,
        highestUtilization,
        instrumentsAtWarning,
      },
    };
  }

  /**
   * Clean up expired temporary limits
   */
  @Cron(CronExpression.EVERY_5_MINUTES)
  async cleanupExpiredTemporaryLimits(): Promise<void> {
    const expired = await this.limitRepo.find({
      where: {
        isTemporary: true,
        isActive: true,
      },
    });

    for (const limit of expired) {
      if (limit.temporaryExpiresAt && limit.temporaryExpiresAt < new Date()) {
        // Reactivate original limit if exists
        if (limit.metadata?.originalLimitId) {
          const original = await this.limitRepo.findOne({
            where: { id: limit.metadata.originalLimitId },
          });
          if (original) {
            original.isActive = true;
            await this.limitRepo.save(original);
          }
        }

        limit.isActive = false;
        await this.limitRepo.save(limit);
        await this.invalidateCache(limit.clientId, limit.instrumentId);

        logger.info('Temporary limit expired', { limitId: limit.id });
      }
    }
  }

  private async invalidateCache(clientId: string, instrumentId?: string): Promise<void> {
    const pattern = instrumentId
      ? `${this.cachePrefix}${clientId}:${instrumentId}`
      : `${this.cachePrefix}${clientId}:*`;

    if (instrumentId) {
      await this.redis.del(pattern);
    } else {
      const keys = await this.redis.keys(pattern);
      if (keys.length > 0) {
        await this.redis.del(...keys);
      }
    }
  }
}
```

## Database Schema

```sql
-- Position limits table
CREATE TABLE position_limits (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    client_id UUID NOT NULL,
    instrument_id VARCHAR(50),
    currency VARCHAR(3),
    max_long_position DECIMAL(20, 4) NOT NULL,
    max_short_position DECIMAL(20, 4) NOT NULL,
    max_gross_position DECIMAL(20, 4),
    max_net_position DECIMAL(20, 4),
    warning_threshold DECIMAL(5, 2) DEFAULT 80,
    soft_limit_threshold DECIMAL(5, 2) DEFAULT 95,
    hard_limit_enabled BOOLEAN DEFAULT true,
    is_temporary BOOLEAN DEFAULT false,
    temporary_expires_at TIMESTAMP WITH TIME ZONE,
    template_id UUID,
    is_active BOOLEAN DEFAULT true,
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(tenant_id, client_id, instrument_id)
);

CREATE INDEX idx_position_limits_client ON position_limits(tenant_id, client_id, is_active);
CREATE INDEX idx_position_limits_temp ON position_limits(is_temporary, temporary_expires_at);

-- Limit templates table
CREATE TABLE limit_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    tier VARCHAR(50) NOT NULL,
    limits JSONB NOT NULL,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
```

## Definition of Done

- [ ] Per-instrument limits
- [ ] Aggregate limits
- [ ] Long/short separate limits
- [ ] Limit templates
- [ ] Temporary increases
- [ ] Utilization tracking
- [ ] Cache management
- [ ] Expiry handling

## Dependencies

- **US-10-01**: Risk Limit Configuration
- **US-07-02**: Position Service

## Test Cases

```typescript
describe('PositionLimitService', () => {
  it('should block order exceeding hard limit', async () => {
    await createLimit({ maxLongPosition: 1000000, hardLimitEnabled: true });
    await setPosition('client-1', 'EUR/USD', 900000);

    const check = await positionLimitService.checkPositionLimit(
      'tenant-1', 'client-1', 'EUR/USD', 'buy', 200000
    );

    expect(check.status).toBe('hard_breach');
    expect(check.canTrade).toBe(false);
  });

  it('should allow soft breach with warning', async () => {
    await createLimit({ maxLongPosition: 1000000, softLimitThreshold: 90 });
    await setPosition('client-1', 'EUR/USD', 800000);

    const check = await positionLimitService.checkPositionLimit(
      'tenant-1', 'client-1', 'EUR/USD', 'buy', 150000
    );

    expect(check.status).toBe('soft_breach');
    expect(check.canTrade).toBe(true);
  });

  it('should grant temporary limit increase', async () => {
    const limit = await createLimit({ maxLongPosition: 1000000 });

    const tempLimit = await positionLimitService.grantTemporaryIncrease(
      limit.id,
      2.0, // Double the limit
      new Date(Date.now() + 3600000), // 1 hour
      'user-1',
      'Client request for large trade'
    );

    expect(Number(tempLimit.maxLongPosition)).toBe(2000000);
    expect(tempLimit.isTemporary).toBe(true);
  });

  it('should revert to original limit after temporary expires', async () => {
    const limit = await createLimit({ maxLongPosition: 1000000 });
    await positionLimitService.grantTemporaryIncrease(
      limit.id, 2.0, new Date(Date.now() - 1000), 'user-1', 'test'
    );

    await positionLimitService.cleanupExpiredTemporaryLimits();

    const effective = await positionLimitService.getEffectiveLimit(
      'tenant-1', 'client-1', null
    );

    expect(Number(effective.maxLongPosition)).toBe(1000000);
  });
});
```
