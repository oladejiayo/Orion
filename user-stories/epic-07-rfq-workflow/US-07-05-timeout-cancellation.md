# User Story: US-07-05 - RFQ Timeout and Cancellation

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-07-05 |
| **Epic** | Epic 07 - RFQ Workflow |
| **Title** | RFQ Timeout and Cancellation |
| **Priority** | P1 - High |
| **Story Points** | 5 |
| **PRD Reference** | FR-RFQ-05 |

## User Story

**As a** trader  
**I want** RFQs to automatically expire after their timeout and have the ability to cancel them manually  
**So that** resources are released and I can manage my trading workflow efficiently

## Description

Implement automatic RFQ expiration with scheduled checks, manual cancellation API, LP cancellation notifications, and proper cleanup of related resources.

## Acceptance Criteria

- [ ] Automatic RFQ expiration at timeout
- [ ] Manual cancellation API
- [ ] Cancel notifications sent to LPs
- [ ] Quotes marked as expired/withdrawn
- [ ] RFQ status updated to EXPIRED/CANCELLED
- [ ] Audit trail for all state changes

## Technical Details

### RFQ Expiry Scheduler

```typescript
// services/rfq-service/src/expiry/rfq-expiry.scheduler.ts
import { Injectable, OnModuleInit, OnModuleDestroy } from '@nestjs/common';
import { Cron, CronExpression } from '@nestjs/schedule';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, In, LessThan } from 'typeorm';
import { logger, metrics } from '@orion/observability';
import { transactionalOutbox } from '@orion/event-model';
import { RfqEntity } from '../entities/rfq.entity';
import { QuoteEntity } from '../entities/quote.entity';
import { DistributionService } from '../distribution/distribution.service';

@Injectable()
export class RfqExpiryScheduler implements OnModuleInit, OnModuleDestroy {
  private isRunning = false;
  private processingBatch = false;

  constructor(
    @InjectRepository(RfqEntity)
    private readonly rfqRepository: Repository<RfqEntity>,
    @InjectRepository(QuoteEntity)
    private readonly quoteRepository: Repository<QuoteEntity>,
    private readonly distributionService: DistributionService,
  ) {}

  onModuleInit() {
    this.isRunning = true;
    logger.info('RFQ expiry scheduler started');
  }

  onModuleDestroy() {
    this.isRunning = false;
    logger.info('RFQ expiry scheduler stopped');
  }

  @Cron(CronExpression.EVERY_SECOND)
  async checkExpiredRfqs(): Promise<void> {
    if (!this.isRunning || this.processingBatch) return;
    
    this.processingBatch = true;
    
    try {
      const now = new Date();
      
      // Find RFQs that have expired
      const expiredRfqs = await this.rfqRepository.find({
        where: {
          status: In(['pending', 'validated', 'distributed', 'quoted']),
          expiresAt: LessThan(now),
        },
        take: 100,  // Process in batches
      });

      if (expiredRfqs.length === 0) return;

      logger.info('Processing expired RFQs', { count: expiredRfqs.length });

      for (const rfq of expiredRfqs) {
        await this.expireRfq(rfq);
      }

      metrics.increment('rfq.expired.batch', { count: expiredRfqs.length.toString() });
    } catch (error) {
      logger.error('Error processing expired RFQs', { error });
    } finally {
      this.processingBatch = false;
    }
  }

  async expireRfq(rfq: RfqEntity): Promise<void> {
    try {
      // Expire all associated quotes
      await this.quoteRepository.update(
        { rfqId: rfq.id, status: In(['pending', 'valid']) },
        { status: 'expired' },
      );

      // Update RFQ status
      await transactionalOutbox(
        this.rfqRepository.manager,
        async (manager) => {
          await manager.update(RfqEntity, rfq.id, {
            status: 'expired',
            updatedAt: new Date(),
          });
        },
        {
          topic: 'orion.events.rfq',
          eventType: 'rfq.expired',
          aggregateType: 'rfq',
          aggregateId: rfq.id,
          payload: {
            rfqId: rfq.id,
            tenantId: rfq.tenantId,
            symbol: rfq.symbol,
            expiredAt: new Date(),
          },
        },
      );

      // Notify LPs of cancellation
      await this.distributionService.cancelRfqWithLPs(rfq.id);

      logger.info('RFQ expired', { rfqId: rfq.id, symbol: rfq.symbol });
      metrics.increment('rfq.expired', { symbol: rfq.symbol });

    } catch (error) {
      logger.error('Failed to expire RFQ', { rfqId: rfq.id, error });
    }
  }
}
```

### Cancellation Service

```typescript
// services/rfq-service/src/cancellation/cancellation.service.ts
import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, In } from 'typeorm';
import { logger, metrics } from '@orion/observability';
import { transactionalOutbox } from '@orion/event-model';
import { RfqEntity } from '../entities/rfq.entity';
import { QuoteEntity } from '../entities/quote.entity';
import { DistributionService } from '../distribution/distribution.service';

export class CancellationError extends Error {
  constructor(
    public readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = 'CancellationError';
  }
}

export interface CancellationResult {
  rfqId: string;
  previousStatus: string;
  cancelledAt: Date;
  quotesWithdrawn: number;
  lpNotifications: number;
}

@Injectable()
export class CancellationService {
  constructor(
    @InjectRepository(RfqEntity)
    private readonly rfqRepository: Repository<RfqEntity>,
    @InjectRepository(QuoteEntity)
    private readonly quoteRepository: Repository<QuoteEntity>,
    private readonly distributionService: DistributionService,
  ) {}

  async cancelRfq(
    rfqId: string,
    reason: string,
    cancelledBy: string,
  ): Promise<CancellationResult> {
    const rfq = await this.rfqRepository.findOne({ where: { id: rfqId } });

    if (!rfq) {
      throw new CancellationError('RFQ_NOT_FOUND', `RFQ ${rfqId} not found`);
    }

    // Validate RFQ can be cancelled
    const cancellableStates = ['pending', 'validated', 'distributed', 'quoted'];
    if (!cancellableStates.includes(rfq.status)) {
      throw new CancellationError(
        'RFQ_NOT_CANCELLABLE',
        `RFQ in state ${rfq.status} cannot be cancelled`,
      );
    }

    const previousStatus = rfq.status;
    const cancelledAt = new Date();

    // Withdraw all active quotes
    const withdrawResult = await this.quoteRepository.update(
      { rfqId, status: In(['pending', 'valid']) },
      { status: 'withdrawn' },
    );

    // Update RFQ status
    await transactionalOutbox(
      this.rfqRepository.manager,
      async (manager) => {
        await manager.update(RfqEntity, rfqId, {
          status: 'cancelled',
          rejectionReason: reason,
          updatedAt: cancelledAt,
        });
      },
      {
        topic: 'orion.events.rfq',
        eventType: 'rfq.cancelled',
        aggregateType: 'rfq',
        aggregateId: rfqId,
        payload: {
          rfqId,
          tenantId: rfq.tenantId,
          symbol: rfq.symbol,
          reason,
          cancelledBy,
          cancelledAt,
          previousStatus,
        },
      },
    );

    // Send cancellation to LPs
    const lpNotifications = await this.distributionService.cancelRfqWithLPs(rfqId);

    logger.info('RFQ cancelled', {
      rfqId,
      reason,
      cancelledBy,
      quotesWithdrawn: withdrawResult.affected,
    });

    metrics.increment('rfq.cancelled', {
      reason: reason.slice(0, 50),
      previousStatus,
    });

    return {
      rfqId,
      previousStatus,
      cancelledAt,
      quotesWithdrawn: withdrawResult.affected || 0,
      lpNotifications,
    };
  }

  async bulkCancel(
    rfqIds: string[],
    reason: string,
    cancelledBy: string,
  ): Promise<CancellationResult[]> {
    const results: CancellationResult[] = [];

    for (const rfqId of rfqIds) {
      try {
        const result = await this.cancelRfq(rfqId, reason, cancelledBy);
        results.push(result);
      } catch (error) {
        logger.warn('Failed to cancel RFQ in bulk', { rfqId, error });
      }
    }

    return results;
  }
}
```

### LP Cancellation Notification

```typescript
// services/rfq-service/src/distribution/distribution.service.ts (addition)

async cancelRfqWithLPs(rfqId: string): Promise<number> {
  const distributions = await this.distributionRepository.find({
    where: {
      rfqId,
      deliveryStatus: 'delivered',
    },
  });

  if (distributions.length === 0) return 0;

  let notified = 0;

  // Send cancellation to each LP
  const cancelPromises = distributions.map(async (distribution) => {
    const lp = await this.lpRepository.findOne({ 
      where: { id: distribution.lpId } 
    });
    
    if (!lp) return;

    const gateway = this.gateways.get(lp.protocol);
    if (!gateway) return;

    try {
      await gateway.cancelRfq(lp.id, rfqId);
      notified++;
      
      logger.debug('LP notified of cancellation', { 
        lpId: lp.id, 
        rfqId 
      });
    } catch (error) {
      logger.warn('Failed to notify LP of cancellation', { 
        lpId: lp.id, 
        rfqId, 
        error 
      });
    }
  });

  await Promise.allSettled(cancelPromises);

  return notified;
}
```

### Cancellation Controller

```typescript
// services/rfq-service/src/cancellation/cancellation.controller.ts
import { Controller, Post, Param, Body, UseGuards, Delete, HttpCode, HttpStatus } from '@nestjs/common';
import { JwtAuthGuard, CurrentUser, User, RequirePermissions, Permission } from '@orion/security';
import { CancellationService, CancellationError } from './cancellation.service';

export class CancelRfqDto {
  reason: string;
}

export class BulkCancelDto {
  rfqIds: string[];
  reason: string;
}

@Controller('rfqs')
@UseGuards(JwtAuthGuard)
export class CancellationController {
  constructor(private readonly cancellationService: CancellationService) {}

  @Delete(':id')
  @HttpCode(HttpStatus.OK)
  @RequirePermissions(Permission.RFQ_CANCEL)
  async cancelRfq(
    @Param('id') id: string,
    @Body() dto: CancelRfqDto,
    @CurrentUser() user: User,
  ) {
    return this.cancellationService.cancelRfq(id, dto.reason, user.id);
  }

  @Post('bulk-cancel')
  @RequirePermissions(Permission.RFQ_CANCEL)
  async bulkCancel(
    @Body() dto: BulkCancelDto,
    @CurrentUser() user: User,
  ) {
    return this.cancellationService.bulkCancel(dto.rfqIds, dto.reason, user.id);
  }
}
```

### Timeout Extension API

```typescript
// services/rfq-service/src/timeout/timeout-extension.service.ts
import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { logger } from '@orion/observability';
import { transactionalOutbox } from '@orion/event-model';
import { RfqEntity } from '../entities/rfq.entity';

@Injectable()
export class TimeoutExtensionService {
  private readonly MAX_EXTENSION_SECONDS = 120;
  private readonly MAX_TOTAL_TIMEOUT = 300;

  constructor(
    @InjectRepository(RfqEntity)
    private readonly rfqRepository: Repository<RfqEntity>,
  ) {}

  async extendTimeout(
    rfqId: string,
    additionalSeconds: number,
    extendedBy: string,
  ): Promise<{ newExpiresAt: Date }> {
    const rfq = await this.rfqRepository.findOne({ where: { id: rfqId } });

    if (!rfq) {
      throw new Error(`RFQ ${rfqId} not found`);
    }

    // Validate RFQ is in extendable state
    const extendableStates = ['distributed', 'quoted'];
    if (!extendableStates.includes(rfq.status)) {
      throw new Error(`RFQ in state ${rfq.status} cannot be extended`);
    }

    // Validate extension limits
    if (additionalSeconds > this.MAX_EXTENSION_SECONDS) {
      throw new Error(`Extension cannot exceed ${this.MAX_EXTENSION_SECONDS} seconds`);
    }

    const totalTimeout = rfq.timeoutSeconds + additionalSeconds;
    if (totalTimeout > this.MAX_TOTAL_TIMEOUT) {
      throw new Error(`Total timeout cannot exceed ${this.MAX_TOTAL_TIMEOUT} seconds`);
    }

    // Calculate new expiry
    const now = new Date();
    const originalExpiry = rfq.expiresAt;
    const baseTime = originalExpiry > now ? originalExpiry : now;
    const newExpiresAt = new Date(baseTime.getTime() + additionalSeconds * 1000);

    await transactionalOutbox(
      this.rfqRepository.manager,
      async (manager) => {
        await manager.update(RfqEntity, rfqId, {
          expiresAt: newExpiresAt,
          timeoutSeconds: totalTimeout,
          updatedAt: new Date(),
        });
      },
      {
        topic: 'orion.events.rfq',
        eventType: 'rfq.extended',
        aggregateType: 'rfq',
        aggregateId: rfqId,
        payload: {
          rfqId,
          originalExpiresAt: originalExpiry,
          newExpiresAt,
          additionalSeconds,
          extendedBy,
        },
      },
    );

    logger.info('RFQ timeout extended', {
      rfqId,
      additionalSeconds,
      newExpiresAt,
    });

    return { newExpiresAt };
  }
}
```

### Timeout Extension Controller

```typescript
// services/rfq-service/src/timeout/timeout.controller.ts
import { Controller, Post, Param, Body, UseGuards } from '@nestjs/common';
import { JwtAuthGuard, CurrentUser, User } from '@orion/security';
import { TimeoutExtensionService } from './timeout-extension.service';

class ExtendTimeoutDto {
  additionalSeconds: number;
}

@Controller('rfqs/:id/timeout')
@UseGuards(JwtAuthGuard)
export class TimeoutController {
  constructor(private readonly timeoutService: TimeoutExtensionService) {}

  @Post('extend')
  async extendTimeout(
    @Param('id') id: string,
    @Body() dto: ExtendTimeoutDto,
    @CurrentUser() user: User,
  ) {
    return this.timeoutService.extendTimeout(id, dto.additionalSeconds, user.id);
  }
}
```

## Definition of Done

- [ ] Automatic expiry scheduler running
- [ ] Manual cancellation API
- [ ] LP cancellation notifications
- [ ] Quote withdrawal on cancel
- [ ] Timeout extension feature
- [ ] Events published for all state changes

## Dependencies

- **US-07-02**: LP Distribution
- **US-07-03**: Quote Collection

## Test Cases

```typescript
describe('RfqExpiryScheduler', () => {
  it('should expire RFQ after timeout', async () => {
    const rfq = await createRfq({ timeoutSeconds: 1 });
    await waitMs(1500);
    await expiryScheduler.checkExpiredRfqs();
    
    const updated = await getRfq(rfq.id);
    expect(updated.status).toBe('expired');
  });
});

describe('CancellationService', () => {
  it('should cancel RFQ and withdraw quotes', async () => {
    const rfq = await createQuotedRfq();
    await createQuotes(rfq.id, 3);
    
    const result = await cancellationService.cancelRfq(
      rfq.id, 
      'User requested', 
      'trader-1'
    );
    
    expect(result.quotesWithdrawn).toBe(3);
    
    const updated = await getRfq(rfq.id);
    expect(updated.status).toBe('cancelled');
  });

  it('should not cancel executed RFQ', async () => {
    const rfq = await createExecutedRfq();
    
    await expect(
      cancellationService.cancelRfq(rfq.id, 'Reason', 'trader-1')
    ).rejects.toThrow(CancellationError);
  });
});

describe('TimeoutExtensionService', () => {
  it('should extend RFQ timeout', async () => {
    const rfq = await createDistributedRfq();
    const originalExpiry = rfq.expiresAt;
    
    const result = await timeoutService.extendTimeout(rfq.id, 30, 'trader-1');
    
    expect(result.newExpiresAt.getTime())
      .toBeGreaterThan(originalExpiry.getTime());
  });
});
```
