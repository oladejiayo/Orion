# User Story: US-11-06 - Reconciliation Service

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-11-06 |
| **Epic** | Epic 11 - Post-Trade Services |
| **Title** | Reconciliation Service |
| **Priority** | P1 - High |
| **Story Points** | 5 |
| **PRD Reference** | FR-PostTrade-06, NFR-PostTrade-06 |

## User Story

**As an** operations manager  
**I want** automated reconciliation between internal records and external sources  
**So that** discrepancies are identified and resolved quickly

## Description

Implement reconciliation service for positions, trades, and cash balances against LPs, custodians, and prime brokers with break detection, exception management, and audit trail.

## Acceptance Criteria

- [ ] Position reconciliation with custodians
- [ ] Trade reconciliation with LPs
- [ ] Cash balance reconciliation
- [ ] Break detection and categorization
- [ ] Exception workflow management
- [ ] Auto-matching logic
- [ ] Break aging reports
- [ ] Reconciliation audit trail

## Technical Details

### Reconciliation Entity

```typescript
// services/post-trade-service/src/entities/reconciliation.entity.ts
import { Entity, Column, CreateDateColumn, UpdateDateColumn, Index } from 'typeorm';

export enum ReconciliationType {
  POSITION = 'position',
  TRADE = 'trade',
  CASH = 'cash',
}

export enum ReconciliationStatus {
  PENDING = 'pending',
  MATCHED = 'matched',
  BREAK = 'break',
  RESOLVED = 'resolved',
  AGED = 'aged',
}

export enum BreakType {
  QUANTITY = 'quantity',
  PRICE = 'price',
  MISSING_INTERNAL = 'missing_internal',
  MISSING_EXTERNAL = 'missing_external',
  SETTLEMENT_DATE = 'settlement_date',
  OTHER = 'other',
}

@Entity('reconciliations')
@Index(['tenantId', 'reconcType', 'reconcDate'])
@Index(['tenantId', 'status'])
export class ReconciliationEntity {
  @Column('uuid', { primaryKey: true, default: () => 'gen_random_uuid()' })
  id: string;

  @Column('uuid')
  tenantId: string;

  @Column('varchar', { length: 20 })
  reconcType: ReconciliationType;

  @Column('date')
  reconcDate: Date;

  @Column('varchar', { length: 100 })
  externalSource: string;

  @Column('varchar', { length: 100 })
  matchKey: string;

  @Column('varchar', { length: 20 })
  status: ReconciliationStatus;

  @Column('varchar', { length: 50, nullable: true })
  breakType: BreakType;

  @Column('jsonb')
  internalRecord: any;

  @Column('jsonb', { nullable: true })
  externalRecord: any;

  @Column('jsonb', { nullable: true })
  differences: {
    field: string;
    internal: any;
    external: any;
    variance: number;
  }[];

  @Column('decimal', { precision: 20, scale: 4, nullable: true })
  varianceAmount: number;

  @Column('varchar', { length: 3, nullable: true })
  varianceCurrency: string;

  @Column('varchar', { length: 100, nullable: true })
  resolvedBy: string;

  @Column('timestamp with time zone', { nullable: true })
  resolvedAt: Date;

  @Column('varchar', { length: 500, nullable: true })
  resolutionNote: string;

  @Column('int', { default: 0 })
  ageInDays: number;

  @CreateDateColumn()
  createdAt: Date;

  @UpdateDateColumn()
  updatedAt: Date;
}
```

### Reconciliation Service

```typescript
// services/post-trade-service/src/reconciliation/reconciliation.service.ts
import { Injectable } from '@nestjs/common';
import { InjectRepository, InjectEntityManager } from '@nestjs/typeorm';
import { Repository, EntityManager, Between, Not, In } from 'typeorm';
import { Cron, CronExpression } from '@nestjs/schedule';
import { logger, metrics } from '@orion/observability';
import { EventPublisher } from '@orion/events';
import { PositionEntity } from '../entities/position.entity';
import { TradeEntity } from '../entities/trade.entity';
import {
  ReconciliationEntity,
  ReconciliationType,
  ReconciliationStatus,
  BreakType,
} from '../entities/reconciliation.entity';

export interface ExternalPositionRecord {
  accountId: string;
  instrumentId: string;
  symbol: string;
  quantity: number;
  settlementDate: Date;
  source: string;
}

export interface ExternalTradeRecord {
  tradeReference: string;
  clientReference: string;
  instrumentId: string;
  side: string;
  quantity: number;
  price: number;
  tradeDate: Date;
  settlementDate: Date;
  source: string;
}

export interface ReconciliationResult {
  matched: number;
  breaks: number;
  missingInternal: number;
  missingExternal: number;
  totalVariance: number;
}

@Injectable()
export class ReconciliationService {
  private readonly tolerances = {
    quantity: 0.0001, // 0.01%
    price: 0.0001,
    amount: 0.01, // $0.01
  };

  constructor(
    @InjectRepository(PositionEntity)
    private readonly positionRepo: Repository<PositionEntity>,
    @InjectRepository(TradeEntity)
    private readonly tradeRepo: Repository<TradeEntity>,
    @InjectRepository(ReconciliationEntity)
    private readonly reconcRepo: Repository<ReconciliationEntity>,
    @InjectEntityManager()
    private readonly entityManager: EntityManager,
    private readonly eventPublisher: EventPublisher,
  ) {}

  /**
   * Run position reconciliation
   */
  async reconcilePositions(
    tenantId: string,
    externalSource: string,
    externalPositions: ExternalPositionRecord[],
    reconcDate: Date,
  ): Promise<ReconciliationResult> {
    const startTime = Date.now();

    // Get internal positions
    const internalPositions = await this.positionRepo.find({
      where: { tenantId },
    });

    // Create maps for matching
    const internalMap = new Map<string, PositionEntity>();
    for (const pos of internalPositions) {
      const key = this.getPositionMatchKey(pos.clientId, pos.instrumentId);
      internalMap.set(key, pos);
    }

    const externalMap = new Map<string, ExternalPositionRecord>();
    for (const pos of externalPositions) {
      const key = this.getPositionMatchKey(pos.accountId, pos.instrumentId);
      externalMap.set(key, pos);
    }

    let matched = 0;
    let breaks = 0;
    let missingInternal = 0;
    let missingExternal = 0;
    let totalVariance = 0;

    // Match internal to external
    for (const [key, internal] of internalMap) {
      const external = externalMap.get(key);

      if (!external) {
        // Missing in external source
        missingExternal++;
        await this.createReconciliation({
          tenantId,
          reconcType: ReconciliationType.POSITION,
          reconcDate,
          externalSource,
          matchKey: key,
          status: ReconciliationStatus.BREAK,
          breakType: BreakType.MISSING_EXTERNAL,
          internalRecord: this.positionToRecord(internal),
          varianceAmount: Number(internal.marketValue),
          varianceCurrency: internal.currency,
        });
      } else {
        // Compare quantities
        const qtyDiff = Math.abs(Number(internal.quantity) - external.quantity);
        const qtyVariance = qtyDiff / Math.abs(Number(internal.quantity));

        if (qtyVariance > this.tolerances.quantity) {
          breaks++;
          totalVariance += qtyDiff * Number(internal.markPrice);

          await this.createReconciliation({
            tenantId,
            reconcType: ReconciliationType.POSITION,
            reconcDate,
            externalSource,
            matchKey: key,
            status: ReconciliationStatus.BREAK,
            breakType: BreakType.QUANTITY,
            internalRecord: this.positionToRecord(internal),
            externalRecord: external,
            differences: [{
              field: 'quantity',
              internal: Number(internal.quantity),
              external: external.quantity,
              variance: qtyDiff,
            }],
            varianceAmount: qtyDiff * Number(internal.markPrice),
            varianceCurrency: internal.currency,
          });
        } else {
          matched++;
          await this.createReconciliation({
            tenantId,
            reconcType: ReconciliationType.POSITION,
            reconcDate,
            externalSource,
            matchKey: key,
            status: ReconciliationStatus.MATCHED,
            internalRecord: this.positionToRecord(internal),
            externalRecord: external,
          });
        }
      }
    }

    // Check for external records missing internally
    for (const [key, external] of externalMap) {
      if (!internalMap.has(key)) {
        missingInternal++;
        await this.createReconciliation({
          tenantId,
          reconcType: ReconciliationType.POSITION,
          reconcDate,
          externalSource,
          matchKey: key,
          status: ReconciliationStatus.BREAK,
          breakType: BreakType.MISSING_INTERNAL,
          internalRecord: null,
          externalRecord: external,
        });
      }
    }

    const result = { matched, breaks, missingInternal, missingExternal, totalVariance };

    logger.info('Position reconciliation completed', {
      tenantId,
      externalSource,
      ...result,
      durationMs: Date.now() - startTime,
    });

    metrics.gauge('reconciliation.position.matched', matched, { tenantId, source: externalSource });
    metrics.gauge('reconciliation.position.breaks', breaks, { tenantId, source: externalSource });

    return result;
  }

  /**
   * Run trade reconciliation
   */
  async reconcileTrades(
    tenantId: string,
    externalSource: string,
    externalTrades: ExternalTradeRecord[],
    reconcDate: Date,
  ): Promise<ReconciliationResult> {
    const startTime = Date.now();

    // Get internal trades for the date
    const internalTrades = await this.tradeRepo.find({
      where: { tenantId, tradeDate: reconcDate },
    });

    // Create maps
    const internalMap = new Map<string, TradeEntity>();
    for (const trade of internalTrades) {
      internalMap.set(trade.tradeReference, trade);
    }

    const externalMap = new Map<string, ExternalTradeRecord>();
    for (const trade of externalTrades) {
      externalMap.set(trade.tradeReference, trade);
    }

    let matched = 0;
    let breaks = 0;
    let missingInternal = 0;
    let missingExternal = 0;
    let totalVariance = 0;

    // Match internal to external
    for (const [ref, internal] of internalMap) {
      const external = externalMap.get(ref) || this.findByClientReference(externalMap, internal);

      if (!external) {
        missingExternal++;
        await this.createReconciliation({
          tenantId,
          reconcType: ReconciliationType.TRADE,
          reconcDate,
          externalSource,
          matchKey: ref,
          status: ReconciliationStatus.BREAK,
          breakType: BreakType.MISSING_EXTERNAL,
          internalRecord: this.tradeToRecord(internal),
          varianceAmount: Number(internal.notionalValue),
          varianceCurrency: internal.currency,
        });
      } else {
        const differences = this.compareTradeRecords(internal, external);

        if (differences.length > 0) {
          breaks++;
          const variance = differences.reduce((sum, d) => sum + (d.variance || 0), 0);
          totalVariance += variance;

          await this.createReconciliation({
            tenantId,
            reconcType: ReconciliationType.TRADE,
            reconcDate,
            externalSource,
            matchKey: ref,
            status: ReconciliationStatus.BREAK,
            breakType: this.determineBreakType(differences),
            internalRecord: this.tradeToRecord(internal),
            externalRecord: external,
            differences,
            varianceAmount: variance,
            varianceCurrency: internal.currency,
          });
        } else {
          matched++;
          await this.createReconciliation({
            tenantId,
            reconcType: ReconciliationType.TRADE,
            reconcDate,
            externalSource,
            matchKey: ref,
            status: ReconciliationStatus.MATCHED,
            internalRecord: this.tradeToRecord(internal),
            externalRecord: external,
          });
        }
      }
    }

    // Check for missing internal
    for (const [ref, external] of externalMap) {
      if (!internalMap.has(ref)) {
        missingInternal++;
        await this.createReconciliation({
          tenantId,
          reconcType: ReconciliationType.TRADE,
          reconcDate,
          externalSource,
          matchKey: ref,
          status: ReconciliationStatus.BREAK,
          breakType: BreakType.MISSING_INTERNAL,
          externalRecord: external,
        });
      }
    }

    return { matched, breaks, missingInternal, missingExternal, totalVariance };
  }

  /**
   * Resolve break
   */
  async resolveBreak(
    reconcId: string,
    userId: string,
    resolution: {
      action: 'accept_internal' | 'accept_external' | 'adjust' | 'write_off';
      note: string;
      adjustment?: any;
    },
  ): Promise<ReconciliationEntity> {
    const reconc = await this.reconcRepo.findOne({
      where: { id: reconcId, status: ReconciliationStatus.BREAK },
    });

    if (!reconc) {
      throw new Error('Break not found');
    }

    reconc.status = ReconciliationStatus.RESOLVED;
    reconc.resolvedBy = userId;
    reconc.resolvedAt = new Date();
    reconc.resolutionNote = `${resolution.action}: ${resolution.note}`;

    await this.reconcRepo.save(reconc);

    // Apply adjustment if needed
    if (resolution.action === 'adjust' && resolution.adjustment) {
      await this.applyAdjustment(reconc, resolution.adjustment);
    }

    await this.eventPublisher.publish({
      type: 'reconciliation.break.resolved',
      aggregateId: reconc.id,
      aggregateType: 'Reconciliation',
      payload: { reconc, resolution },
      metadata: { tenantId: reconc.tenantId },
    });

    logger.info('Break resolved', { reconcId, action: resolution.action, userId });

    return reconc;
  }

  /**
   * Get breaks summary
   */
  async getBreaksSummary(tenantId: string): Promise<{
    total: number;
    byType: Record<string, number>;
    bySource: Record<string, number>;
    byAge: { range: string; count: number }[];
    totalVariance: number;
  }> {
    const breaks = await this.reconcRepo.find({
      where: { tenantId, status: ReconciliationStatus.BREAK },
    });

    const summary = {
      total: breaks.length,
      byType: {} as Record<string, number>,
      bySource: {} as Record<string, number>,
      byAge: [
        { range: '0-1 days', count: 0 },
        { range: '2-7 days', count: 0 },
        { range: '8-30 days', count: 0 },
        { range: '30+ days', count: 0 },
      ],
      totalVariance: 0,
    };

    for (const brk of breaks) {
      summary.byType[brk.breakType] = (summary.byType[brk.breakType] || 0) + 1;
      summary.bySource[brk.externalSource] = (summary.bySource[brk.externalSource] || 0) + 1;
      summary.totalVariance += Number(brk.varianceAmount) || 0;

      if (brk.ageInDays <= 1) summary.byAge[0].count++;
      else if (brk.ageInDays <= 7) summary.byAge[1].count++;
      else if (brk.ageInDays <= 30) summary.byAge[2].count++;
      else summary.byAge[3].count++;
    }

    return summary;
  }

  /**
   * Update break ages
   */
  @Cron(CronExpression.EVERY_DAY_AT_1AM)
  async updateBreakAges(): Promise<void> {
    await this.reconcRepo
      .createQueryBuilder()
      .update()
      .set({ ageInDays: () => 'age_in_days + 1' })
      .where('status = :status', { status: ReconciliationStatus.BREAK })
      .execute();

    // Mark old breaks as aged
    await this.reconcRepo
      .createQueryBuilder()
      .update()
      .set({ status: ReconciliationStatus.AGED })
      .where('status = :status', { status: ReconciliationStatus.BREAK })
      .andWhere('age_in_days > 30')
      .execute();

    logger.info('Break ages updated');
  }

  /**
   * EOD reconciliation job
   */
  @Cron(CronExpression.EVERY_DAY_AT_8PM)
  async runEodReconciliation(): Promise<void> {
    logger.info('Starting EOD reconciliation');

    // Get all tenants
    const tenants = await this.entityManager.query(
      `SELECT DISTINCT tenant_id FROM positions`,
    );

    for (const { tenant_id } of tenants) {
      // Get external data from adapters and reconcile
      // Implementation depends on external source integrations
    }
  }

  private async createReconciliation(data: Partial<ReconciliationEntity>): Promise<void> {
    const reconc = this.reconcRepo.create(data);
    await this.reconcRepo.save(reconc);
  }

  private getPositionMatchKey(clientId: string, instrumentId: string): string {
    return `${clientId}:${instrumentId}`;
  }

  private positionToRecord(position: PositionEntity): any {
    return {
      clientId: position.clientId,
      instrumentId: position.instrumentId,
      quantity: Number(position.quantity),
      averageCost: Number(position.averageCost),
      marketValue: Number(position.marketValue),
    };
  }

  private tradeToRecord(trade: TradeEntity): any {
    return {
      tradeReference: trade.tradeReference,
      instrumentId: trade.instrumentId,
      side: trade.side,
      quantity: Number(trade.quantity),
      price: Number(trade.price),
      tradeDate: trade.tradeDate,
      settlementDate: trade.settlementDate,
    };
  }

  private findByClientReference(
    map: Map<string, ExternalTradeRecord>,
    internal: TradeEntity,
  ): ExternalTradeRecord | undefined {
    for (const [_, external] of map) {
      if (external.clientReference === internal.tradeReference) {
        return external;
      }
    }
    return undefined;
  }

  private compareTradeRecords(internal: TradeEntity, external: ExternalTradeRecord): any[] {
    const differences: any[] = [];

    if (Math.abs(Number(internal.quantity) - external.quantity) / Number(internal.quantity) > this.tolerances.quantity) {
      differences.push({
        field: 'quantity',
        internal: Number(internal.quantity),
        external: external.quantity,
        variance: Math.abs(Number(internal.quantity) - external.quantity),
      });
    }

    if (Math.abs(Number(internal.price) - external.price) / Number(internal.price) > this.tolerances.price) {
      differences.push({
        field: 'price',
        internal: Number(internal.price),
        external: external.price,
        variance: Math.abs(Number(internal.price) - external.price),
      });
    }

    return differences;
  }

  private determineBreakType(differences: any[]): BreakType {
    if (differences.some(d => d.field === 'quantity')) return BreakType.QUANTITY;
    if (differences.some(d => d.field === 'price')) return BreakType.PRICE;
    return BreakType.OTHER;
  }

  private async applyAdjustment(reconc: ReconciliationEntity, adjustment: any): Promise<void> {
    // Apply position or trade adjustment based on reconciliation type
  }
}
```

## Database Schema

```sql
-- Reconciliations table
CREATE TABLE reconciliations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    reconc_type VARCHAR(20) NOT NULL,
    reconc_date DATE NOT NULL,
    external_source VARCHAR(100) NOT NULL,
    match_key VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    break_type VARCHAR(50),
    internal_record JSONB,
    external_record JSONB,
    differences JSONB,
    variance_amount DECIMAL(20, 4),
    variance_currency VARCHAR(3),
    resolved_by VARCHAR(100),
    resolved_at TIMESTAMP WITH TIME ZONE,
    resolution_note VARCHAR(500),
    age_in_days INT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_reconc_tenant_type ON reconciliations(tenant_id, reconc_type, reconc_date);
CREATE INDEX idx_reconc_status ON reconciliations(tenant_id, status);
CREATE INDEX idx_reconc_break ON reconciliations(tenant_id, status, break_type) WHERE status = 'break';
```

## Definition of Done

- [ ] Position reconciliation
- [ ] Trade reconciliation
- [ ] Break detection
- [ ] Break resolution workflow
- [ ] Break aging
- [ ] Summary reports
- [ ] EOD batch job

## Test Cases

```typescript
describe('ReconciliationService', () => {
  it('should detect quantity breaks', async () => {
    await createPosition({ quantity: 100000 });

    const result = await reconcService.reconcilePositions(
      'tenant-1', 'custodian',
      [{ accountId: 'client-1', instrumentId: 'EUR/USD', quantity: 99000, ... }],
      new Date()
    );

    expect(result.breaks).toBe(1);
    expect(result.matched).toBe(0);
  });

  it('should detect missing external records', async () => {
    await createPosition({ quantity: 100000 });

    const result = await reconcService.reconcilePositions(
      'tenant-1', 'custodian', [], new Date()
    );

    expect(result.missingExternal).toBe(1);
  });

  it('should resolve break', async () => {
    const reconc = await createBreak();

    const resolved = await reconcService.resolveBreak(
      reconc.id, 'user-1',
      { action: 'accept_internal', note: 'External data error' }
    );

    expect(resolved.status).toBe(ReconciliationStatus.RESOLVED);
  });
});
```
