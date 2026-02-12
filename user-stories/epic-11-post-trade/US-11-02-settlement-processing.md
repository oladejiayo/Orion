# User Story: US-11-02 - Settlement Processing

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-11-02 |
| **Epic** | Epic 11 - Post-Trade Services |
| **Title** | Settlement Processing |
| **Priority** | P1 - High |
| **Story Points** | 5 |
| **PRD Reference** | FR-PostTrade-02, NFR-PostTrade-02 |

## User Story

**As a** settlement operations manager  
**I want** automated settlement instruction generation and tracking  
**So that** trades settle accurately and on time

## Description

Implement settlement processing service that generates settlement instructions, tracks settlement status, manages failed settlements, and integrates with custodians and CCPs.

## Acceptance Criteria

- [ ] Auto-generate settlement instructions
- [ ] Support multiple settlement methods
- [ ] Track settlement lifecycle
- [ ] Handle settlement failures
- [ ] Netting for same-day settlements
- [ ] Integration with custodians
- [ ] Settlement calendar management
- [ ] Exception handling workflow

## Technical Details

### Settlement Instruction Entity

```typescript
// services/post-trade-service/src/entities/settlement-instruction.entity.ts
import { Entity, Column, CreateDateColumn, UpdateDateColumn, Index } from 'typeorm';

export enum SettlementStatus {
  PENDING = 'pending',
  MATCHED = 'matched',
  INSTRUCTED = 'instructed',
  IN_TRANSIT = 'in_transit',
  SETTLED = 'settled',
  FAILED = 'failed',
  CANCELLED = 'cancelled',
}

export enum SettlementMethod {
  DVP = 'dvp',          // Delivery vs Payment
  FOP = 'fop',          // Free of Payment
  PVP = 'pvp',          // Payment vs Payment
  GROSS = 'gross',
  NET = 'net',
}

@Entity('settlement_instructions')
@Index(['tenantId', 'settlementDate', 'status'])
@Index(['tenantId', 'tradeId'])
export class SettlementInstructionEntity {
  @Column('uuid', { primaryKey: true, default: () => 'gen_random_uuid()' })
  id: string;

  @Column('uuid')
  tenantId: string;

  @Column('varchar', { length: 50, unique: true })
  instructionReference: string;

  @Column('uuid')
  tradeId: string;

  @Column('uuid', { nullable: true })
  nettingSetId: string;

  @Column('varchar', { length: 20 })
  settlementMethod: SettlementMethod;

  @Column('varchar', { length: 30, default: SettlementStatus.PENDING })
  status: SettlementStatus;

  @Column('date')
  settlementDate: Date;

  @Column('varchar', { length: 3 })
  settlementCurrency: string;

  @Column('decimal', { precision: 20, scale: 4 })
  settlementAmount: number;

  @Column('varchar', { length: 10 })
  direction: string; // pay, receive

  @Column('jsonb')
  ourAccount: {
    custodian: string;
    accountNumber: string;
    bic: string;
    correspondentBic?: string;
  };

  @Column('jsonb')
  counterpartyAccount: {
    beneficiary: string;
    accountNumber: string;
    bic: string;
    correspondentBic?: string;
  };

  @Column('varchar', { length: 100, nullable: true })
  externalReference: string;

  @Column('timestamp with time zone', { nullable: true })
  matchedAt: Date;

  @Column('timestamp with time zone', { nullable: true })
  instructedAt: Date;

  @Column('timestamp with time zone', { nullable: true })
  settledAt: Date;

  @Column('varchar', { length: 500, nullable: true })
  failureReason: string;

  @Column('int', { default: 0 })
  retryCount: number;

  @Column('jsonb', { nullable: true })
  metadata: any;

  @CreateDateColumn()
  createdAt: Date;

  @UpdateDateColumn()
  updatedAt: Date;
}
```

### Netting Set Entity

```typescript
// services/post-trade-service/src/entities/netting-set.entity.ts
import { Entity, Column, CreateDateColumn } from 'typeorm';

@Entity('netting_sets')
export class NettingSetEntity {
  @Column('uuid', { primaryKey: true, default: () => 'gen_random_uuid()' })
  id: string;

  @Column('uuid')
  tenantId: string;

  @Column('varchar', { length: 50 })
  nettingReference: string;

  @Column('date')
  settlementDate: Date;

  @Column('varchar', { length: 3 })
  currency: string;

  @Column('uuid')
  counterpartyId: string;

  @Column('decimal', { precision: 20, scale: 4 })
  grossPayAmount: number;

  @Column('decimal', { precision: 20, scale: 4 })
  grossReceiveAmount: number;

  @Column('decimal', { precision: 20, scale: 4 })
  netAmount: number;

  @Column('varchar', { length: 10 })
  netDirection: string; // pay, receive

  @Column('int')
  tradeCount: number;

  @Column('varchar', { length: 20, default: 'pending' })
  status: string;

  @CreateDateColumn()
  createdAt: Date;
}
```

### Settlement Service

```typescript
// services/post-trade-service/src/settlement/settlement.service.ts
import { Injectable } from '@nestjs/common';
import { InjectRepository, InjectEntityManager } from '@nestjs/typeorm';
import { Repository, EntityManager, In, LessThanOrEqual } from 'typeorm';
import { Cron, CronExpression } from '@nestjs/schedule';
import { logger, metrics } from '@orion/observability';
import { EventPublisher } from '@orion/events';
import { TradeEntity, TradeStatus } from '../entities/trade.entity';
import {
  SettlementInstructionEntity,
  SettlementStatus,
  SettlementMethod,
} from '../entities/settlement-instruction.entity';
import { NettingSetEntity } from '../entities/netting-set.entity';
import { SettlementCalendarService } from './settlement-calendar.service';

export interface SettlementAccountConfig {
  custodian: string;
  accountNumber: string;
  bic: string;
  correspondentBic?: string;
}

@Injectable()
export class SettlementService {
  constructor(
    @InjectRepository(TradeEntity)
    private readonly tradeRepo: Repository<TradeEntity>,
    @InjectRepository(SettlementInstructionEntity)
    private readonly instructionRepo: Repository<SettlementInstructionEntity>,
    @InjectRepository(NettingSetEntity)
    private readonly nettingRepo: Repository<NettingSetEntity>,
    @InjectEntityManager()
    private readonly entityManager: EntityManager,
    private readonly calendarService: SettlementCalendarService,
    private readonly eventPublisher: EventPublisher,
  ) {}

  /**
   * Generate settlement instruction for trade
   */
  async generateSettlementInstruction(trade: TradeEntity): Promise<SettlementInstructionEntity> {
    const startTime = Date.now();

    // Validate settlement date
    const settlementDate = await this.calendarService.getValidSettlementDate(
      trade.currency,
      trade.settlementDate,
    );

    // Get settlement accounts
    const ourAccount = await this.getOurSettlementAccount(trade.tenantId, trade.currency);
    const counterpartyAccount = await this.getCounterpartyAccount(trade);

    // Determine settlement method
    const method = this.determineSettlementMethod(trade);

    // Calculate settlement amount
    const { amount, direction } = this.calculateSettlementAmount(trade);

    const instruction = this.instructionRepo.create({
      tenantId: trade.tenantId,
      instructionReference: await this.generateInstructionReference(trade.tenantId),
      tradeId: trade.id,
      settlementMethod: method,
      settlementDate,
      settlementCurrency: trade.currency,
      settlementAmount: amount,
      direction,
      ourAccount,
      counterpartyAccount,
      status: SettlementStatus.PENDING,
    });

    await this.instructionRepo.save(instruction);

    logger.info('Settlement instruction generated', {
      instructionId: instruction.id,
      tradeId: trade.id,
      amount,
      direction,
    });

    metrics.histogram('settlement.instruction_generation_ms', Date.now() - startTime);

    return instruction;
  }

  /**
   * Create netting set for same-day settlements
   */
  async createNettingSet(
    tenantId: string,
    settlementDate: Date,
    currency: string,
    counterpartyId: string,
  ): Promise<NettingSetEntity> {
    // Find all pending instructions for netting
    const instructions = await this.instructionRepo.find({
      where: {
        tenantId,
        settlementDate,
        settlementCurrency: currency,
        status: SettlementStatus.PENDING,
      },
    });

    // Filter by counterparty
    const eligibleInstructions = instructions.filter(
      i => i.counterpartyAccount.beneficiary === counterpartyId,
    );

    if (eligibleInstructions.length < 2) {
      return null; // Not enough for netting
    }

    // Calculate net amounts
    let grossPay = 0;
    let grossReceive = 0;

    for (const inst of eligibleInstructions) {
      if (inst.direction === 'pay') {
        grossPay += Number(inst.settlementAmount);
      } else {
        grossReceive += Number(inst.settlementAmount);
      }
    }

    const netAmount = Math.abs(grossReceive - grossPay);
    const netDirection = grossReceive >= grossPay ? 'receive' : 'pay';

    // Create netting set
    const nettingSet = this.nettingRepo.create({
      tenantId,
      nettingReference: await this.generateNettingReference(tenantId),
      settlementDate,
      currency,
      counterpartyId,
      grossPayAmount: grossPay,
      grossReceiveAmount: grossReceive,
      netAmount,
      netDirection,
      tradeCount: eligibleInstructions.length,
    });

    await this.entityManager.transaction(async manager => {
      await manager.save(nettingSet);

      // Update instructions with netting set ID
      for (const inst of eligibleInstructions) {
        inst.nettingSetId = nettingSet.id;
        inst.settlementMethod = SettlementMethod.NET;
        await manager.save(inst);
      }
    });

    logger.info('Netting set created', {
      nettingSetId: nettingSet.id,
      tradeCount: nettingSet.tradeCount,
      grossPay,
      grossReceive,
      netAmount,
      netDirection,
    });

    return nettingSet;
  }

  /**
   * Send settlement instruction to custodian
   */
  async sendToCustodian(instructionId: string): Promise<void> {
    const instruction = await this.instructionRepo.findOne({
      where: { id: instructionId },
    });

    if (!instruction) {
      throw new Error('Instruction not found');
    }

    try {
      // Generate SWIFT message (MT202/MT103)
      const swiftMessage = await this.generateSwiftMessage(instruction);

      // Send to custodian via adapter
      const externalRef = await this.custodianAdapter.send(
        instruction.ourAccount.custodian,
        swiftMessage,
      );

      instruction.status = SettlementStatus.INSTRUCTED;
      instruction.instructedAt = new Date();
      instruction.externalReference = externalRef;
      await this.instructionRepo.save(instruction);

      // Publish event
      await this.eventPublisher.publish({
        type: 'settlement.instructed',
        aggregateId: instruction.id,
        aggregateType: 'SettlementInstruction',
        payload: instruction,
        metadata: { tenantId: instruction.tenantId },
      });

      logger.info('Settlement instruction sent to custodian', {
        instructionId,
        externalRef,
      });
    } catch (error) {
      instruction.status = SettlementStatus.FAILED;
      instruction.failureReason = error.message;
      instruction.retryCount += 1;
      await this.instructionRepo.save(instruction);

      throw error;
    }
  }

  /**
   * Process settlement confirmation from custodian
   */
  async processSettlementConfirmation(
    externalReference: string,
    status: 'settled' | 'failed',
    details?: { reason?: string; timestamp?: Date },
  ): Promise<void> {
    const instruction = await this.instructionRepo.findOne({
      where: { externalReference },
    });

    if (!instruction) {
      logger.warn('Settlement confirmation for unknown reference', { externalReference });
      return;
    }

    if (status === 'settled') {
      instruction.status = SettlementStatus.SETTLED;
      instruction.settledAt = details?.timestamp || new Date();

      // Update trade status
      await this.tradeRepo.update(instruction.tradeId, {
        status: TradeStatus.SETTLED,
      });

      await this.eventPublisher.publish({
        type: 'settlement.completed',
        aggregateId: instruction.id,
        aggregateType: 'SettlementInstruction',
        payload: instruction,
        metadata: { tenantId: instruction.tenantId },
      });
    } else {
      instruction.status = SettlementStatus.FAILED;
      instruction.failureReason = details?.reason || 'Settlement failed';

      await this.eventPublisher.publish({
        type: 'settlement.failed',
        aggregateId: instruction.id,
        aggregateType: 'SettlementInstruction',
        payload: {
          instruction,
          reason: instruction.failureReason,
        },
        metadata: { tenantId: instruction.tenantId },
      });
    }

    await this.instructionRepo.save(instruction);

    logger.info('Settlement confirmation processed', {
      instructionId: instruction.id,
      status,
    });
  }

  /**
   * Retry failed settlement
   */
  async retryFailedSettlement(instructionId: string): Promise<void> {
    const instruction = await this.instructionRepo.findOne({
      where: { id: instructionId, status: SettlementStatus.FAILED },
    });

    if (!instruction) {
      throw new Error('Failed instruction not found');
    }

    if (instruction.retryCount >= 3) {
      throw new Error('Maximum retry attempts exceeded');
    }

    instruction.status = SettlementStatus.PENDING;
    instruction.failureReason = null;
    await this.instructionRepo.save(instruction);

    await this.sendToCustodian(instructionId);
  }

  /**
   * Get settlement status for trade
   */
  async getSettlementStatus(tradeId: string): Promise<{
    instruction: SettlementInstructionEntity;
    nettingSet?: NettingSetEntity;
  }> {
    const instruction = await this.instructionRepo.findOne({
      where: { tradeId },
    });

    let nettingSet: NettingSetEntity | undefined;
    if (instruction?.nettingSetId) {
      nettingSet = await this.nettingRepo.findOne({
        where: { id: instruction.nettingSetId },
      });
    }

    return { instruction, nettingSet };
  }

  /**
   * Daily netting job
   */
  @Cron(CronExpression.EVERY_DAY_AT_6AM)
  async runDailyNetting(): Promise<void> {
    logger.info('Starting daily netting process');

    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    tomorrow.setHours(0, 0, 0, 0);

    // Get all tenants with pending settlements
    const pendingInstructions = await this.instructionRepo.find({
      where: {
        settlementDate: tomorrow,
        status: SettlementStatus.PENDING,
      },
    });

    // Group by tenant, currency, counterparty
    const groups = new Map<string, SettlementInstructionEntity[]>();

    for (const inst of pendingInstructions) {
      const key = `${inst.tenantId}:${inst.settlementCurrency}:${inst.counterpartyAccount.beneficiary}`;
      const existing = groups.get(key) || [];
      existing.push(inst);
      groups.set(key, existing);
    }

    // Create netting sets for eligible groups
    for (const [key, instructions] of groups) {
      if (instructions.length >= 2) {
        const [tenantId, currency, counterpartyId] = key.split(':');
        await this.createNettingSet(tenantId, tomorrow, currency, counterpartyId);
      }
    }

    logger.info('Daily netting completed', { groupsProcessed: groups.size });
  }

  /**
   * Send settlement instructions for tomorrow
   */
  @Cron(CronExpression.EVERY_DAY_AT_7AM)
  async sendSettlementInstructions(): Promise<void> {
    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    tomorrow.setHours(0, 0, 0, 0);

    const instructions = await this.instructionRepo.find({
      where: {
        settlementDate: LessThanOrEqual(tomorrow),
        status: In([SettlementStatus.PENDING, SettlementStatus.MATCHED]),
      },
    });

    for (const instruction of instructions) {
      try {
        await this.sendToCustodian(instruction.id);
      } catch (error) {
        logger.error('Failed to send settlement instruction', {
          instructionId: instruction.id,
          error: error.message,
        });
      }
    }

    logger.info('Settlement instructions sent', { count: instructions.length });
  }

  private determineSettlementMethod(trade: TradeEntity): SettlementMethod {
    // FX trades typically use PVP
    if (trade.tradeType === 'outright' || trade.tradeType === 'swap') {
      return SettlementMethod.PVP;
    }
    return SettlementMethod.DVP;
  }

  private calculateSettlementAmount(trade: TradeEntity): { amount: number; direction: string } {
    const amount = Number(trade.notionalValue);
    const direction = trade.side === 'buy' ? 'pay' : 'receive';
    return { amount, direction };
  }

  private async getOurSettlementAccount(
    tenantId: string,
    currency: string,
  ): Promise<SettlementAccountConfig> {
    // Get from configuration
    return {
      custodian: 'CUSTODIAN',
      accountNumber: '123456789',
      bic: 'CUSTUSXX',
    };
  }

  private async getCounterpartyAccount(trade: TradeEntity): Promise<SettlementAccountConfig> {
    // Get from counterparty SSI (Standard Settlement Instructions)
    return {
      beneficiary: trade.counterparty?.name || 'Unknown',
      accountNumber: trade.counterparty?.accountId || '',
      bic: 'COUNTUSXX',
    };
  }

  private async generateInstructionReference(tenantId: string): Promise<string> {
    const date = new Date().toISOString().slice(0, 10).replace(/-/g, '');
    const random = Math.random().toString(36).slice(2, 8).toUpperCase();
    return `STL${date}${random}`;
  }

  private async generateNettingReference(tenantId: string): Promise<string> {
    const date = new Date().toISOString().slice(0, 10).replace(/-/g, '');
    const random = Math.random().toString(36).slice(2, 8).toUpperCase();
    return `NET${date}${random}`;
  }

  private async generateSwiftMessage(instruction: SettlementInstructionEntity): Promise<string> {
    // Generate MT202 or MT103 based on instruction type
    return '';
  }

  private custodianAdapter = {
    send: async (custodian: string, message: string): Promise<string> => {
      // Send via appropriate protocol
      return `EXT${Date.now()}`;
    },
  };
}
```

## Database Schema

```sql
-- Settlement instructions table
CREATE TABLE settlement_instructions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    instruction_reference VARCHAR(50) UNIQUE NOT NULL,
    trade_id UUID NOT NULL REFERENCES trades(id),
    netting_set_id UUID,
    settlement_method VARCHAR(20) NOT NULL,
    status VARCHAR(30) DEFAULT 'pending',
    settlement_date DATE NOT NULL,
    settlement_currency VARCHAR(3) NOT NULL,
    settlement_amount DECIMAL(20, 4) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    our_account JSONB NOT NULL,
    counterparty_account JSONB NOT NULL,
    external_reference VARCHAR(100),
    matched_at TIMESTAMP WITH TIME ZONE,
    instructed_at TIMESTAMP WITH TIME ZONE,
    settled_at TIMESTAMP WITH TIME ZONE,
    failure_reason VARCHAR(500),
    retry_count INT DEFAULT 0,
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_settlement_tenant_date ON settlement_instructions(tenant_id, settlement_date, status);
CREATE INDEX idx_settlement_trade ON settlement_instructions(tenant_id, trade_id);
CREATE INDEX idx_settlement_external_ref ON settlement_instructions(external_reference);

-- Netting sets table
CREATE TABLE netting_sets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    netting_reference VARCHAR(50) NOT NULL,
    settlement_date DATE NOT NULL,
    currency VARCHAR(3) NOT NULL,
    counterparty_id UUID NOT NULL,
    gross_pay_amount DECIMAL(20, 4) NOT NULL,
    gross_receive_amount DECIMAL(20, 4) NOT NULL,
    net_amount DECIMAL(20, 4) NOT NULL,
    net_direction VARCHAR(10) NOT NULL,
    trade_count INT NOT NULL,
    status VARCHAR(20) DEFAULT 'pending',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Settlement calendar
CREATE TABLE settlement_calendars (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    currency VARCHAR(3) NOT NULL,
    date DATE NOT NULL,
    is_business_day BOOLEAN NOT NULL,
    holiday_name VARCHAR(100),
    UNIQUE(currency, date)
);
```

## Definition of Done

- [ ] Settlement instruction generation
- [ ] Netting set creation
- [ ] Custodian integration
- [ ] Settlement status tracking
- [ ] Failure handling and retry
- [ ] Calendar management
- [ ] Daily batch processing

## Dependencies

- **US-11-01**: Trade Confirmation (source data)
- **External**: Custodian adapters, SWIFT network

## Test Cases

```typescript
describe('SettlementService', () => {
  it('should generate settlement instruction for trade', async () => {
    const trade = await createTrade({
      side: 'buy',
      notionalValue: 100000,
      currency: 'USD',
    });

    const instruction = await settlementService.generateSettlementInstruction(trade);

    expect(instruction.settlementAmount).toBe(100000);
    expect(instruction.direction).toBe('pay');
    expect(instruction.status).toBe(SettlementStatus.PENDING);
  });

  it('should create netting set for multiple trades', async () => {
    await createSettlementInstructions([
      { direction: 'pay', amount: 100000 },
      { direction: 'receive', amount: 60000 },
      { direction: 'pay', amount: 30000 },
    ]);

    const nettingSet = await settlementService.createNettingSet(
      'tenant-1', tomorrow, 'USD', 'counterparty-1'
    );

    expect(nettingSet.grossPayAmount).toBe(130000);
    expect(nettingSet.grossReceiveAmount).toBe(60000);
    expect(nettingSet.netAmount).toBe(70000);
    expect(nettingSet.netDirection).toBe('pay');
  });

  it('should process settlement confirmation', async () => {
    const instruction = await createInstruction({ externalReference: 'EXT123' });

    await settlementService.processSettlementConfirmation(
      'EXT123',
      'settled',
      { timestamp: new Date() }
    );

    const updated = await instructionRepo.findOne(instruction.id);
    expect(updated.status).toBe(SettlementStatus.SETTLED);
  });
});
```
