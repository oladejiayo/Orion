# User Story: US-11-07 - Regulatory Reporting

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-11-07 |
| **Epic** | Epic 11 - Post-Trade Services |
| **Title** | Regulatory Reporting |
| **Priority** | P2 - Medium |
| **Story Points** | 8 |
| **PRD Reference** | FR-PostTrade-07, NFR-PostTrade-07 |

## User Story

**As a** compliance officer  
**I want** automated regulatory trade reporting  
**So that** we meet EMIR, MiFID II, and other regulatory requirements

## Description

Implement regulatory reporting service supporting EMIR trade reporting, MiFID II transaction reporting, and SFTR securities financing reporting with message generation, submission tracking, and exception handling.

## Acceptance Criteria

- [ ] EMIR trade reporting (TR submission)
- [ ] MiFID II transaction reporting (ARM)
- [ ] SFTR reporting capability
- [ ] Message format generation (ISO 20022)
- [ ] Submission tracking and status
- [ ] Amendment and cancellation
- [ ] Regulatory ID generation (UTI, LEI)
- [ ] Report history and audit

## Technical Details

### Regulatory Report Entity

```typescript
// services/post-trade-service/src/entities/regulatory-report.entity.ts
import { Entity, Column, CreateDateColumn, UpdateDateColumn, Index } from 'typeorm';

export enum ReportType {
  EMIR = 'emir',
  MIFID_II = 'mifid_ii',
  SFTR = 'sftr',
  DODD_FRANK = 'dodd_frank',
}

export enum ReportStatus {
  PENDING = 'pending',
  SUBMITTED = 'submitted',
  ACCEPTED = 'accepted',
  REJECTED = 'rejected',
  AMENDED = 'amended',
  CANCELLED = 'cancelled',
}

export enum ReportActionType {
  NEW = 'new',
  MODIFY = 'modify',
  CANCEL = 'cancel',
  ERROR = 'error',
  POSITION = 'position',
}

@Entity('regulatory_reports')
@Index(['tenantId', 'reportType', 'status'])
@Index(['tenantId', 'tradeId'])
@Index(['uti'], { unique: true, where: 'uti IS NOT NULL' })
export class RegulatoryReportEntity {
  @Column('uuid', { primaryKey: true, default: () => 'gen_random_uuid()' })
  id: string;

  @Column('uuid')
  tenantId: string;

  @Column('uuid')
  tradeId: string;

  @Column('varchar', { length: 20 })
  reportType: ReportType;

  @Column('varchar', { length: 20 })
  actionType: ReportActionType;

  @Column('varchar', { length: 20, default: ReportStatus.PENDING })
  status: ReportStatus;

  @Column('varchar', { length: 100, nullable: true })
  uti: string; // Unique Transaction Identifier

  @Column('varchar', { length: 20, nullable: true })
  lei: string; // Legal Entity Identifier

  @Column('varchar', { length: 100, nullable: true })
  tradeRepositoryId: string;

  @Column('varchar', { length: 100, nullable: true })
  submissionReference: string;

  @Column('text')
  reportPayload: string; // XML or JSON message

  @Column('varchar', { length: 20 })
  reportFormat: string; // iso20022, fpml, etc

  @Column('timestamp with time zone', { nullable: true })
  submittedAt: Date;

  @Column('timestamp with time zone', { nullable: true })
  acknowledgedAt: Date;

  @Column('varchar', { length: 500, nullable: true })
  rejectionReason: string;

  @Column('jsonb', { nullable: true })
  validationErrors: {
    code: string;
    field: string;
    message: string;
  }[];

  @Column('uuid', { nullable: true })
  originalReportId: string;

  @Column('int', { default: 0 })
  retryCount: number;

  @CreateDateColumn()
  createdAt: Date;

  @UpdateDateColumn()
  updatedAt: Date;
}
```

### Regulatory Reporting Service

```typescript
// services/post-trade-service/src/regulatory/regulatory-reporting.service.ts
import { Injectable } from '@nestjs/common';
import { InjectRepository, InjectEntityManager } from '@nestjs/typeorm';
import { Repository, EntityManager, In } from 'typeorm';
import { Cron, CronExpression } from '@nestjs/schedule';
import { logger, metrics } from '@orion/observability';
import { EventPublisher } from '@orion/events';
import { TradeEntity } from '../entities/trade.entity';
import {
  RegulatoryReportEntity,
  ReportType,
  ReportStatus,
  ReportActionType,
} from '../entities/regulatory-report.entity';
import { EmirMessageGenerator } from './generators/emir-message.generator';
import { MifidMessageGenerator } from './generators/mifid-message.generator';
import { TradeRepositoryAdapter } from './adapters/trade-repository.adapter';

export interface ReportingConfig {
  tenantId: string;
  lei: string;
  reportingCounterpartyId: string;
  tradeRepositories: {
    emir?: string;
    mifid?: string;
  };
  enabled: {
    emir: boolean;
    mifid: boolean;
    sftr: boolean;
  };
}

@Injectable()
export class RegulatoryReportingService {
  constructor(
    @InjectRepository(TradeEntity)
    private readonly tradeRepo: Repository<TradeEntity>,
    @InjectRepository(RegulatoryReportEntity)
    private readonly reportRepo: Repository<RegulatoryReportEntity>,
    @InjectEntityManager()
    private readonly entityManager: EntityManager,
    private readonly emirGenerator: EmirMessageGenerator,
    private readonly mifidGenerator: MifidMessageGenerator,
    private readonly trAdapter: TradeRepositoryAdapter,
    private readonly eventPublisher: EventPublisher,
  ) {}

  /**
   * Generate and submit EMIR report
   */
  async reportToEmir(trade: TradeEntity, actionType: ReportActionType): Promise<RegulatoryReportEntity> {
    const config = await this.getConfig(trade.tenantId);
    if (!config.enabled.emir) {
      throw new Error('EMIR reporting not enabled');
    }

    // Generate UTI if new trade
    const uti = actionType === ReportActionType.NEW
      ? await this.generateUTI(trade)
      : trade.regulatoryIds?.uti;

    // Generate EMIR message
    const message = await this.emirGenerator.generate(trade, {
      actionType,
      uti,
      lei: config.lei,
      reportingCounterpartyId: config.reportingCounterpartyId,
    });

    // Validate message
    const validationErrors = await this.validateEmirMessage(message);
    if (validationErrors.length > 0) {
      logger.error('EMIR validation failed', { tradeId: trade.id, errors: validationErrors });
    }

    // Create report record
    const report = this.reportRepo.create({
      tenantId: trade.tenantId,
      tradeId: trade.id,
      reportType: ReportType.EMIR,
      actionType,
      status: validationErrors.length > 0 ? ReportStatus.REJECTED : ReportStatus.PENDING,
      uti,
      lei: config.lei,
      tradeRepositoryId: config.tradeRepositories.emir,
      reportPayload: message.xml,
      reportFormat: 'iso20022',
      validationErrors: validationErrors.length > 0 ? validationErrors : null,
    });

    await this.reportRepo.save(report);

    // Submit to TR if valid
    if (validationErrors.length === 0) {
      await this.submitToTradeRepository(report);
    }

    // Update trade with regulatory IDs
    if (actionType === ReportActionType.NEW) {
      trade.regulatoryIds = { ...trade.regulatoryIds, uti };
      await this.tradeRepo.save(trade);
    }

    logger.info('EMIR report created', {
      reportId: report.id,
      tradeId: trade.id,
      actionType,
      status: report.status,
    });

    return report;
  }

  /**
   * Generate and submit MiFID II transaction report
   */
  async reportToMifid(trade: TradeEntity, actionType: ReportActionType): Promise<RegulatoryReportEntity> {
    const config = await this.getConfig(trade.tenantId);
    if (!config.enabled.mifid) {
      throw new Error('MiFID II reporting not enabled');
    }

    // Generate transaction reference
    const transactionRef = await this.generateTransactionReference(trade);

    // Generate MiFID II message
    const message = await this.mifidGenerator.generate(trade, {
      actionType,
      transactionReference: transactionRef,
      lei: config.lei,
    });

    const report = this.reportRepo.create({
      tenantId: trade.tenantId,
      tradeId: trade.id,
      reportType: ReportType.MIFID_II,
      actionType,
      status: ReportStatus.PENDING,
      lei: config.lei,
      submissionReference: transactionRef,
      reportPayload: message.xml,
      reportFormat: 'iso20022',
    });

    await this.reportRepo.save(report);
    await this.submitToArm(report);

    return report;
  }

  /**
   * Amend existing report
   */
  async amendReport(
    originalReportId: string,
    trade: TradeEntity,
  ): Promise<RegulatoryReportEntity> {
    const original = await this.reportRepo.findOne({
      where: { id: originalReportId },
    });

    if (!original) {
      throw new Error('Original report not found');
    }

    original.status = ReportStatus.AMENDED;
    await this.reportRepo.save(original);

    // Create amendment report
    if (original.reportType === ReportType.EMIR) {
      return this.reportToEmir(trade, ReportActionType.MODIFY);
    } else {
      return this.reportToMifid(trade, ReportActionType.MODIFY);
    }
  }

  /**
   * Cancel report
   */
  async cancelReport(
    originalReportId: string,
    trade: TradeEntity,
    reason: string,
  ): Promise<RegulatoryReportEntity> {
    const original = await this.reportRepo.findOne({
      where: { id: originalReportId },
    });

    if (!original) {
      throw new Error('Original report not found');
    }

    // Create cancellation report
    const cancellation = this.reportRepo.create({
      tenantId: trade.tenantId,
      tradeId: trade.id,
      reportType: original.reportType,
      actionType: ReportActionType.CANCEL,
      status: ReportStatus.PENDING,
      uti: original.uti,
      lei: original.lei,
      originalReportId: original.id,
      reportPayload: await this.generateCancellationMessage(original, reason),
      reportFormat: original.reportFormat,
    });

    await this.reportRepo.save(cancellation);
    await this.submitToTradeRepository(cancellation);

    original.status = ReportStatus.CANCELLED;
    await this.reportRepo.save(original);

    return cancellation;
  }

  /**
   * Process TR acknowledgment
   */
  async processAcknowledgment(
    submissionReference: string,
    status: 'accepted' | 'rejected',
    details?: { errors?: any[]; timestamp?: Date },
  ): Promise<void> {
    const report = await this.reportRepo.findOne({
      where: { submissionReference },
    });

    if (!report) {
      logger.warn('Acknowledgment for unknown report', { submissionReference });
      return;
    }

    if (status === 'accepted') {
      report.status = ReportStatus.ACCEPTED;
      report.acknowledgedAt = details?.timestamp || new Date();
    } else {
      report.status = ReportStatus.REJECTED;
      report.rejectionReason = details?.errors?.[0]?.message || 'Unknown rejection reason';
      report.validationErrors = details?.errors;
    }

    await this.reportRepo.save(report);

    await this.eventPublisher.publish({
      type: `regulatory.report.${status}`,
      aggregateId: report.id,
      aggregateType: 'RegulatoryReport',
      payload: report,
      metadata: { tenantId: report.tenantId },
    });

    logger.info('Regulatory report acknowledgment processed', {
      reportId: report.id,
      status,
    });
  }

  /**
   * Get reporting status for trade
   */
  async getTradeReportingStatus(tradeId: string): Promise<{
    emir?: RegulatoryReportEntity;
    mifid?: RegulatoryReportEntity;
    sftr?: RegulatoryReportEntity;
  }> {
    const reports = await this.reportRepo.find({
      where: { tradeId },
      order: { createdAt: 'DESC' },
    });

    const result: any = {};

    for (const report of reports) {
      if (!result[report.reportType]) {
        result[report.reportType] = report;
      }
    }

    return result;
  }

  /**
   * Retry failed reports
   */
  @Cron(CronExpression.EVERY_HOUR)
  async retryFailedReports(): Promise<void> {
    const failed = await this.reportRepo.find({
      where: {
        status: In([ReportStatus.PENDING, ReportStatus.REJECTED]),
        retryCount: { $lt: 3 } as any,
      },
    });

    for (const report of failed) {
      try {
        report.retryCount += 1;
        await this.reportRepo.save(report);

        if (report.reportType === ReportType.EMIR) {
          await this.submitToTradeRepository(report);
        } else {
          await this.submitToArm(report);
        }
      } catch (error) {
        logger.error('Failed to retry report', {
          reportId: report.id,
          error: error.message,
        });
      }
    }
  }

  /**
   * EOD reporting job
   */
  @Cron(CronExpression.EVERY_DAY_AT_9PM)
  async runEodReporting(): Promise<void> {
    logger.info('Starting EOD regulatory reporting');

    // Get trades that need reporting
    const unreportedTrades = await this.tradeRepo
      .createQueryBuilder('t')
      .leftJoin('regulatory_reports', 'r', 't.id = r.trade_id')
      .where('r.id IS NULL')
      .andWhere('t.status = :status', { status: 'confirmed' })
      .getMany();

    for (const trade of unreportedTrades) {
      const config = await this.getConfig(trade.tenantId);

      if (config.enabled.emir) {
        await this.reportToEmir(trade, ReportActionType.NEW);
      }

      if (config.enabled.mifid) {
        await this.reportToMifid(trade, ReportActionType.NEW);
      }
    }

    logger.info('EOD reporting completed', { tradesProcessed: unreportedTrades.length });
  }

  private async submitToTradeRepository(report: RegulatoryReportEntity): Promise<void> {
    try {
      const result = await this.trAdapter.submit(
        report.tradeRepositoryId,
        report.reportPayload,
      );

      report.submissionReference = result.reference;
      report.submittedAt = new Date();
      report.status = ReportStatus.SUBMITTED;
      await this.reportRepo.save(report);

      logger.info('Report submitted to TR', {
        reportId: report.id,
        reference: result.reference,
      });
    } catch (error) {
      report.rejectionReason = error.message;
      await this.reportRepo.save(report);
      throw error;
    }
  }

  private async submitToArm(report: RegulatoryReportEntity): Promise<void> {
    // Submit to Approved Reporting Mechanism for MiFID II
    // Similar to TR submission
  }

  private async generateUTI(trade: TradeEntity): Promise<string> {
    // UTI format: LEI prefix + unique suffix
    const config = await this.getConfig(trade.tenantId);
    const suffix = `${Date.now()}${Math.random().toString(36).slice(2, 10)}`.toUpperCase();
    return `${config.lei}${suffix}`;
  }

  private async generateTransactionReference(trade: TradeEntity): Promise<string> {
    return `TXN${trade.tenantId.slice(0, 8)}${Date.now()}`;
  }

  private async validateEmirMessage(message: any): Promise<any[]> {
    // Validate against EMIR schema
    return [];
  }

  private async generateCancellationMessage(
    original: RegulatoryReportEntity,
    reason: string,
  ): Promise<string> {
    // Generate cancellation message based on original format
    return '';
  }

  private async getConfig(tenantId: string): Promise<ReportingConfig> {
    // Get from tenant configuration
    return {
      tenantId,
      lei: 'LEI12345678901234567890',
      reportingCounterpartyId: 'COUNTERPARTY',
      tradeRepositories: { emir: 'DTCC', mifid: 'ARM' },
      enabled: { emir: true, mifid: true, sftr: false },
    };
  }
}
```

### EMIR Message Generator

```typescript
// services/post-trade-service/src/regulatory/generators/emir-message.generator.ts
import { Injectable } from '@nestjs/common';
import { TradeEntity } from '../../entities/trade.entity';

@Injectable()
export class EmirMessageGenerator {
  async generate(
    trade: TradeEntity,
    options: {
      actionType: string;
      uti: string;
      lei: string;
      reportingCounterpartyId: string;
    },
  ): Promise<{ xml: string; fields: any }> {
    const fields = this.mapTradeToEmirFields(trade, options);
    const xml = this.buildXml(fields);

    return { xml, fields };
  }

  private mapTradeToEmirFields(trade: TradeEntity, options: any): any {
    return {
      // Section 1 - Counterparty Data
      reportingCounterpartyId: options.reportingCounterpartyId,
      otherCounterpartyId: trade.counterparty?.lei || 'UNKNOWN',
      counterpartySide: trade.side === 'buy' ? 'B' : 'S',

      // Section 2 - Transaction Data
      uti: options.uti,
      executionTimestamp: trade.executedAt.toISOString(),
      effectiveDate: trade.tradeDate,
      maturityDate: trade.settlementDate,

      // Section 3 - Product Data
      productClassification: this.getProductClassification(trade),
      underlyingAsset: trade.instrumentId,

      // Section 4 - Transaction Details
      notionalAmount: trade.notionalValue,
      notionalCurrency: trade.currency,
      price: trade.price,
      priceNotation: 'MONE',
      quantity: trade.quantity,

      // Section 5 - Risk Mitigation
      confirmationType: 'NCNF',
      confirmationTimestamp: new Date().toISOString(),

      // Execution Venue
      executionVenue: trade.executionVenue,
    };
  }

  private getProductClassification(trade: TradeEntity): string {
    // Map to ISDA taxonomy
    const typeMap: Record<string, string> = {
      outright: 'FXSPOT',
      forward: 'FXFWD',
      swap: 'FXSWP',
      ndf: 'FXNDF',
      option: 'FXOPT',
    };
    return typeMap[trade.tradeType] || 'OTHER';
  }

  private buildXml(fields: any): string {
    // Build ISO 20022 auth.030 message
    return `<?xml version="1.0" encoding="UTF-8"?>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:auth.030.001.02">
  <DerivsTradRpt>
    <RptHdr>
      <RptgDtTm>${new Date().toISOString()}</RptgDtTm>
    </RptHdr>
    <TradData>
      <CtrPtySpcfcData>
        <RptgCtrPty>
          <LEI>${fields.reportingCounterpartyId}</LEI>
        </RptgCtrPty>
      </CtrPtySpcfcData>
      <CmonTradData>
        <TxId>
          <UnqTxIdr>${fields.uti}</UnqTxIdr>
        </TxId>
        <ExctnTmStmp>${fields.executionTimestamp}</ExctnTmStmp>
        <NtnlAmt>${fields.notionalAmount}</NtnlAmt>
        <NtnlCcy>${fields.notionalCurrency}</NtnlCcy>
        <Pric>${fields.price}</Pric>
      </CmonTradData>
    </TradData>
  </DerivsTradRpt>
</Document>`;
  }
}
```

## Database Schema

```sql
-- Regulatory reports table
CREATE TABLE regulatory_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    trade_id UUID NOT NULL REFERENCES trades(id),
    report_type VARCHAR(20) NOT NULL,
    action_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) DEFAULT 'pending',
    uti VARCHAR(100),
    lei VARCHAR(20),
    trade_repository_id VARCHAR(100),
    submission_reference VARCHAR(100),
    report_payload TEXT NOT NULL,
    report_format VARCHAR(20) NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE,
    acknowledged_at TIMESTAMP WITH TIME ZONE,
    rejection_reason VARCHAR(500),
    validation_errors JSONB,
    original_report_id UUID,
    retry_count INT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_reg_reports_tenant ON regulatory_reports(tenant_id, report_type, status);
CREATE INDEX idx_reg_reports_trade ON regulatory_reports(tenant_id, trade_id);
CREATE UNIQUE INDEX idx_reg_reports_uti ON regulatory_reports(uti) WHERE uti IS NOT NULL;

-- Regulatory configurations
CREATE TABLE regulatory_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL UNIQUE,
    lei VARCHAR(20) NOT NULL,
    reporting_counterparty_id VARCHAR(100) NOT NULL,
    trade_repositories JSONB NOT NULL,
    enabled_reports JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
```

## Definition of Done

- [ ] EMIR report generation
- [ ] MiFID II report generation
- [ ] TR/ARM submission
- [ ] UTI generation
- [ ] Status tracking
- [ ] Amendment handling
- [ ] Cancellation support
- [ ] EOD batch reporting

## Dependencies

- **US-11-01**: Trade Confirmation (trade data)
- **External**: Trade Repositories, ARMs

## Test Cases

```typescript
describe('RegulatoryReportingService', () => {
  it('should generate EMIR report with UTI', async () => {
    const trade = await createTrade();

    const report = await reportingService.reportToEmir(trade, ReportActionType.NEW);

    expect(report.uti).toBeDefined();
    expect(report.reportType).toBe(ReportType.EMIR);
    expect(report.reportPayload).toContain('UnqTxIdr');
  });

  it('should amend existing report', async () => {
    const original = await createReport();
    const trade = await getTrade(original.tradeId);

    const amended = await reportingService.amendReport(original.id, trade);

    expect(amended.actionType).toBe(ReportActionType.MODIFY);
    expect(amended.uti).toBe(original.uti);
  });

  it('should process TR acknowledgment', async () => {
    const report = await createSubmittedReport();

    await reportingService.processAcknowledgment(
      report.submissionReference, 'accepted', { timestamp: new Date() }
    );

    const updated = await reportRepo.findOne(report.id);
    expect(updated.status).toBe(ReportStatus.ACCEPTED);
  });
});
```
