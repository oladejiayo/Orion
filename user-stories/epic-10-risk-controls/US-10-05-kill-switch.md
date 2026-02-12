# User Story: US-10-05 - Kill Switch Implementation

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-10-05 |
| **Epic** | Epic 10 - Risk & Controls |
| **Title** | Kill Switch Implementation |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |
| **PRD Reference** | FR-Risk-05, NFR-Risk-02 |

## User Story

**As a** risk manager  
**I want** to immediately halt trading for a client, instrument, or platform  
**So that** I can prevent further losses in emergency situations

## Description

Implement kill switch functionality supporting multiple scopes (client, instrument, LP, tenant-wide), automatic triggers based on risk thresholds, manual activation with audit trail, and sub-second activation time.

## Acceptance Criteria

- [ ] Client-level kill switch
- [ ] Instrument-level kill switch
- [ ] LP-level kill switch
- [ ] Tenant-wide kill switch
- [ ] Automatic trigger on limit breach
- [ ] Manual activation via API
- [ ] Cancel all open orders on activation
- [ ] Sub-second activation time
- [ ] Full audit trail
- [ ] Graceful reactivation

## Technical Details

### Kill Switch Entity

```typescript
// services/risk-service/src/entities/kill-switch.entity.ts
import { Entity, Column, CreateDateColumn, UpdateDateColumn, Index } from 'typeorm';

export enum KillSwitchScope {
  CLIENT = 'client',
  INSTRUMENT = 'instrument',
  LP = 'lp',
  TENANT = 'tenant',
}

export enum KillSwitchTrigger {
  MANUAL = 'manual',
  LOSS_LIMIT = 'loss_limit',
  EXPOSURE_BREACH = 'exposure_breach',
  POSITION_BREACH = 'position_breach',
  SYSTEM_ERROR = 'system_error',
  EXTERNAL = 'external',
}

@Entity('kill_switches')
@Index(['tenantId', 'scope', 'targetId', 'isActive'])
export class KillSwitchEntity {
  @Column('uuid', { primaryKey: true, default: () => 'gen_random_uuid()' })
  id: string;

  @Column('uuid')
  tenantId: string;

  @Column('varchar', { length: 20 })
  scope: KillSwitchScope;

  @Column('varchar', { length: 100 })
  targetId: string;

  @Column('varchar', { length: 50 })
  trigger: KillSwitchTrigger;

  @Column('boolean', { default: true })
  isActive: boolean;

  @Column('varchar', { length: 100 })
  activatedBy: string;

  @Column('varchar', { length: 500 })
  reason: string;

  @Column('timestamp with time zone')
  activatedAt: Date;

  @Column('varchar', { length: 100, nullable: true })
  deactivatedBy: string;

  @Column('timestamp with time zone', { nullable: true })
  deactivatedAt: Date;

  @Column('varchar', { length: 500, nullable: true })
  deactivationReason: string;

  @Column('int', { default: 0 })
  ordersCancelled: number;

  @Column('jsonb', { nullable: true })
  metadata: any;

  @CreateDateColumn()
  createdAt: Date;

  @UpdateDateColumn()
  updatedAt: Date;
}
```

### Kill Switch Service

```typescript
// services/risk-service/src/killswitch/kill-switch.service.ts
import { Injectable, BadRequestException } from '@nestjs/common';
import { InjectRepository, InjectEntityManager } from '@nestjs/typeorm';
import { Repository, EntityManager } from 'typeorm';
import { Redis } from 'ioredis';
import { InjectRedis } from '@nestjs-modules/ioredis';
import { logger, metrics } from '@orion/observability';
import { KillSwitchEntity, KillSwitchScope, KillSwitchTrigger } from '../entities/kill-switch.entity';
import { OrderCancellationService } from '@orion/order-service';
import { EventPublisher } from '@orion/events';

export interface ActivateKillSwitchDto {
  scope: KillSwitchScope;
  targetId: string;
  trigger: KillSwitchTrigger;
  reason: string;
  cancelOpenOrders?: boolean;
  metadata?: any;
}

export interface KillSwitchStatus {
  isActive: boolean;
  scope?: KillSwitchScope;
  targetId?: string;
  activatedAt?: Date;
  reason?: string;
}

@Injectable()
export class KillSwitchService {
  private readonly cachePrefix = 'killswitch:';

  constructor(
    @InjectRepository(KillSwitchEntity)
    private readonly killSwitchRepo: Repository<KillSwitchEntity>,
    @InjectEntityManager()
    private readonly entityManager: EntityManager,
    @InjectRedis() private readonly redis: Redis,
    private readonly orderCancellation: OrderCancellationService,
    private readonly eventPublisher: EventPublisher,
  ) {}

  /**
   * Activate kill switch
   */
  async activate(
    tenantId: string,
    dto: ActivateKillSwitchDto,
    userId: string,
  ): Promise<KillSwitchEntity> {
    const startTime = Date.now();

    return this.entityManager.transaction(async (manager) => {
      // Check for existing active kill switch
      const existing = await manager.findOne(KillSwitchEntity, {
        where: {
          tenantId,
          scope: dto.scope,
          targetId: dto.targetId,
          isActive: true,
        },
      });

      if (existing) {
        logger.info('Kill switch already active', {
          scope: dto.scope,
          targetId: dto.targetId,
        });
        return existing;
      }

      // Create kill switch record
      const killSwitch = manager.create(KillSwitchEntity, {
        tenantId,
        scope: dto.scope,
        targetId: dto.targetId,
        trigger: dto.trigger,
        activatedBy: userId,
        reason: dto.reason,
        activatedAt: new Date(),
        metadata: dto.metadata,
      });

      await manager.save(killSwitch);

      // Update Redis cache for fast checks
      await this.setCacheStatus(tenantId, dto.scope, dto.targetId, true);

      // Cancel open orders if requested
      let ordersCancelled = 0;
      if (dto.cancelOpenOrders !== false) {
        ordersCancelled = await this.cancelOpenOrders(
          tenantId,
          dto.scope,
          dto.targetId,
        );
        killSwitch.ordersCancelled = ordersCancelled;
        await manager.save(killSwitch);
      }

      // Publish event
      await this.publishKillSwitchEvent(killSwitch, 'activated');

      const activationTime = Date.now() - startTime;
      metrics.timing('killswitch.activation_time', activationTime);
      metrics.increment('killswitch.activated', {
        scope: dto.scope,
        trigger: dto.trigger,
      });

      logger.warn('Kill switch activated', {
        id: killSwitch.id,
        scope: dto.scope,
        targetId: dto.targetId,
        trigger: dto.trigger,
        reason: dto.reason,
        ordersCancelled,
        activationTimeMs: activationTime,
      });

      return killSwitch;
    });
  }

  /**
   * Deactivate kill switch
   */
  async deactivate(
    killSwitchId: string,
    userId: string,
    reason: string,
  ): Promise<KillSwitchEntity> {
    const killSwitch = await this.killSwitchRepo.findOne({
      where: { id: killSwitchId, isActive: true },
    });

    if (!killSwitch) {
      throw new BadRequestException('Active kill switch not found');
    }

    killSwitch.isActive = false;
    killSwitch.deactivatedBy = userId;
    killSwitch.deactivatedAt = new Date();
    killSwitch.deactivationReason = reason;

    await this.killSwitchRepo.save(killSwitch);

    // Clear Redis cache
    await this.setCacheStatus(
      killSwitch.tenantId,
      killSwitch.scope as KillSwitchScope,
      killSwitch.targetId,
      false,
    );

    // Publish event
    await this.publishKillSwitchEvent(killSwitch, 'deactivated');

    logger.info('Kill switch deactivated', {
      id: killSwitchId,
      deactivatedBy: userId,
      reason,
    });

    return killSwitch;
  }

  /**
   * Check if trading is blocked (fast path via Redis)
   */
  async isTradingBlocked(
    tenantId: string,
    clientId?: string,
    instrumentId?: string,
    lpId?: string,
  ): Promise<KillSwitchStatus> {
    // Check tenant-wide kill switch
    const tenantBlocked = await this.checkCache(tenantId, KillSwitchScope.TENANT, tenantId);
    if (tenantBlocked) {
      return { isActive: true, scope: KillSwitchScope.TENANT, targetId: tenantId };
    }

    // Check client kill switch
    if (clientId) {
      const clientBlocked = await this.checkCache(tenantId, KillSwitchScope.CLIENT, clientId);
      if (clientBlocked) {
        return { isActive: true, scope: KillSwitchScope.CLIENT, targetId: clientId };
      }
    }

    // Check instrument kill switch
    if (instrumentId) {
      const instrumentBlocked = await this.checkCache(tenantId, KillSwitchScope.INSTRUMENT, instrumentId);
      if (instrumentBlocked) {
        return { isActive: true, scope: KillSwitchScope.INSTRUMENT, targetId: instrumentId };
      }
    }

    // Check LP kill switch
    if (lpId) {
      const lpBlocked = await this.checkCache(tenantId, KillSwitchScope.LP, lpId);
      if (lpBlocked) {
        return { isActive: true, scope: KillSwitchScope.LP, targetId: lpId };
      }
    }

    return { isActive: false };
  }

  /**
   * Get active kill switches
   */
  async getActiveKillSwitches(tenantId: string): Promise<KillSwitchEntity[]> {
    return this.killSwitchRepo.find({
      where: { tenantId, isActive: true },
      order: { activatedAt: 'DESC' },
    });
  }

  /**
   * Get kill switch history
   */
  async getHistory(
    tenantId: string,
    options: {
      scope?: KillSwitchScope;
      targetId?: string;
      fromDate?: Date;
      toDate?: Date;
      limit?: number;
    },
  ): Promise<KillSwitchEntity[]> {
    const query = this.killSwitchRepo.createQueryBuilder('ks')
      .where('ks.tenantId = :tenantId', { tenantId });

    if (options.scope) {
      query.andWhere('ks.scope = :scope', { scope: options.scope });
    }
    if (options.targetId) {
      query.andWhere('ks.targetId = :targetId', { targetId: options.targetId });
    }
    if (options.fromDate) {
      query.andWhere('ks.activatedAt >= :fromDate', { fromDate: options.fromDate });
    }
    if (options.toDate) {
      query.andWhere('ks.activatedAt <= :toDate', { toDate: options.toDate });
    }

    return query
      .orderBy('ks.activatedAt', 'DESC')
      .take(options.limit || 100)
      .getMany();
  }

  /**
   * Auto-trigger kill switch based on risk breach
   */
  async autoTrigger(
    tenantId: string,
    trigger: KillSwitchTrigger,
    scope: KillSwitchScope,
    targetId: string,
    breachDetails: any,
  ): Promise<KillSwitchEntity> {
    return this.activate(
      tenantId,
      {
        scope,
        targetId,
        trigger,
        reason: `Automatic trigger: ${trigger}`,
        cancelOpenOrders: true,
        metadata: { breachDetails },
      },
      'SYSTEM',
    );
  }

  /**
   * Cancel all open orders for kill switch scope
   */
  private async cancelOpenOrders(
    tenantId: string,
    scope: KillSwitchScope,
    targetId: string,
  ): Promise<number> {
    try {
      let result;

      switch (scope) {
        case KillSwitchScope.CLIENT:
          result = await this.orderCancellation.cancelAllForClient(
            targetId,
            'SYSTEM',
            `Kill switch activated`,
          );
          break;

        case KillSwitchScope.INSTRUMENT:
          result = await this.orderCancellation.cancelAllForInstrument(
            targetId,
            'SYSTEM',
            `Kill switch activated`,
          );
          break;

        case KillSwitchScope.TENANT:
          result = await this.orderCancellation.cancelAllForTenant(
            tenantId,
            'SYSTEM',
            `Tenant-wide kill switch activated`,
          );
          break;

        case KillSwitchScope.LP:
          result = await this.orderCancellation.cancelAllForLp(
            targetId,
            'SYSTEM',
            `LP kill switch activated`,
          );
          break;

        default:
          return 0;
      }

      return result.cancelled.length;
    } catch (error) {
      logger.error('Failed to cancel orders during kill switch', { error });
      return 0;
    }
  }

  private async setCacheStatus(
    tenantId: string,
    scope: KillSwitchScope,
    targetId: string,
    isActive: boolean,
  ): Promise<void> {
    const key = `${this.cachePrefix}${tenantId}:${scope}:${targetId}`;

    if (isActive) {
      await this.redis.setex(key, 86400, '1'); // 24 hour TTL
    } else {
      await this.redis.del(key);
    }
  }

  private async checkCache(
    tenantId: string,
    scope: KillSwitchScope,
    targetId: string,
  ): Promise<boolean> {
    const key = `${this.cachePrefix}${tenantId}:${scope}:${targetId}`;
    const value = await this.redis.get(key);
    return value === '1';
  }

  private async publishKillSwitchEvent(
    killSwitch: KillSwitchEntity,
    action: 'activated' | 'deactivated',
  ): Promise<void> {
    await this.eventPublisher.publish({
      type: `killswitch.${action}`,
      aggregateId: killSwitch.id,
      aggregateType: 'KillSwitch',
      payload: {
        id: killSwitch.id,
        scope: killSwitch.scope,
        targetId: killSwitch.targetId,
        trigger: killSwitch.trigger,
        reason: action === 'activated' ? killSwitch.reason : killSwitch.deactivationReason,
        ordersCancelled: killSwitch.ordersCancelled,
      },
      metadata: { tenantId: killSwitch.tenantId },
    });
  }
}
```

### Kill Switch Controller

```typescript
// services/risk-service/src/controllers/kill-switch.controller.ts
import { Controller, Post, Delete, Get, Body, Param, Query, UseGuards } from '@nestjs/common';
import { ApiTags, ApiOperation, ApiBearerAuth } from '@nestjs/swagger';
import { TenantGuard, TenantId, UserId, RolesGuard, Roles } from '@orion/auth';
import { KillSwitchService, ActivateKillSwitchDto } from '../killswitch/kill-switch.service';
import { KillSwitchScope } from '../entities/kill-switch.entity';

@ApiTags('Kill Switch')
@ApiBearerAuth()
@Controller('api/v1/risk/killswitch')
@UseGuards(TenantGuard, RolesGuard)
export class KillSwitchController {
  constructor(private readonly killSwitchService: KillSwitchService) {}

  @Post()
  @Roles('risk_manager', 'admin')
  @ApiOperation({ summary: 'Activate kill switch' })
  async activate(
    @Body() dto: ActivateKillSwitchDto,
    @TenantId() tenantId: string,
    @UserId() userId: string,
  ) {
    return this.killSwitchService.activate(tenantId, dto, userId);
  }

  @Delete(':id')
  @Roles('risk_manager', 'admin')
  @ApiOperation({ summary: 'Deactivate kill switch' })
  async deactivate(
    @Param('id') id: string,
    @Body('reason') reason: string,
    @UserId() userId: string,
  ) {
    return this.killSwitchService.deactivate(id, userId, reason);
  }

  @Get('check')
  @ApiOperation({ summary: 'Check if trading is blocked' })
  async checkStatus(
    @TenantId() tenantId: string,
    @Query('clientId') clientId?: string,
    @Query('instrumentId') instrumentId?: string,
    @Query('lpId') lpId?: string,
  ) {
    return this.killSwitchService.isTradingBlocked(tenantId, clientId, instrumentId, lpId);
  }

  @Get('active')
  @ApiOperation({ summary: 'Get active kill switches' })
  async getActive(@TenantId() tenantId: string) {
    return this.killSwitchService.getActiveKillSwitches(tenantId);
  }

  @Get('history')
  @ApiOperation({ summary: 'Get kill switch history' })
  async getHistory(
    @TenantId() tenantId: string,
    @Query('scope') scope?: KillSwitchScope,
    @Query('targetId') targetId?: string,
  ) {
    return this.killSwitchService.getHistory(tenantId, { scope, targetId });
  }
}
```

## Database Schema

```sql
-- Kill switches table
CREATE TABLE kill_switches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    scope VARCHAR(20) NOT NULL,
    target_id VARCHAR(100) NOT NULL,
    trigger VARCHAR(50) NOT NULL,
    is_active BOOLEAN DEFAULT true,
    activated_by VARCHAR(100) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    activated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    deactivated_by VARCHAR(100),
    deactivated_at TIMESTAMP WITH TIME ZONE,
    deactivation_reason VARCHAR(500),
    orders_cancelled INT DEFAULT 0,
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_kill_switches_active ON kill_switches(tenant_id, scope, target_id, is_active);
CREATE INDEX idx_kill_switches_history ON kill_switches(tenant_id, activated_at DESC);
```

## Definition of Done

- [ ] Client kill switch
- [ ] Instrument kill switch
- [ ] LP kill switch
- [ ] Tenant-wide kill switch
- [ ] Automatic triggers
- [ ] Order cancellation on activation
- [ ] < 1 second activation
- [ ] Full audit trail
- [ ] Manual deactivation

## Dependencies

- **US-09-07**: Order Cancellation Service
- **US-10-03**: Exposure Monitor (for auto-triggers)

## Test Cases

```typescript
describe('KillSwitchService', () => {
  it('should activate kill switch within 1 second', async () => {
    const startTime = Date.now();

    await killSwitchService.activate('tenant-1', {
      scope: KillSwitchScope.CLIENT,
      targetId: 'client-1',
      trigger: KillSwitchTrigger.MANUAL,
      reason: 'Test activation',
    }, 'user-1');

    const activationTime = Date.now() - startTime;
    expect(activationTime).toBeLessThan(1000);
  });

  it('should block trading when kill switch is active', async () => {
    await killSwitchService.activate('tenant-1', {
      scope: KillSwitchScope.CLIENT,
      targetId: 'client-1',
      trigger: KillSwitchTrigger.MANUAL,
      reason: 'Test',
    }, 'user-1');

    const status = await killSwitchService.isTradingBlocked(
      'tenant-1', 'client-1'
    );

    expect(status.isActive).toBe(true);
    expect(status.scope).toBe(KillSwitchScope.CLIENT);
  });

  it('should cancel all open orders on activation', async () => {
    await createOpenOrders('client-1', 5);

    const ks = await killSwitchService.activate('tenant-1', {
      scope: KillSwitchScope.CLIENT,
      targetId: 'client-1',
      trigger: KillSwitchTrigger.LOSS_LIMIT,
      reason: 'Daily loss limit exceeded',
      cancelOpenOrders: true,
    }, 'SYSTEM');

    expect(ks.ordersCancelled).toBe(5);
  });

  it('should allow trading after deactivation', async () => {
    const ks = await activateKillSwitch('client-1');

    await killSwitchService.deactivate(ks.id, 'user-1', 'Issue resolved');

    const status = await killSwitchService.isTradingBlocked(
      'tenant-1', 'client-1'
    );

    expect(status.isActive).toBe(false);
  });

  it('should check Redis cache for fast lookups', async () => {
    await activateKillSwitch('client-1');

    const cacheValue = await redis.get('killswitch:tenant-1:client:client-1');
    expect(cacheValue).toBe('1');
  });
});
```
