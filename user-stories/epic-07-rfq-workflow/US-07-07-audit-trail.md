# User Story: US-07-07 - RFQ Audit Trail

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-07-07 |
| **Epic** | Epic 07 - RFQ Workflow |
| **Title** | RFQ Audit Trail |
| **Priority** | P1 - High |
| **Story Points** | 5 |
| **PRD Reference** | FR-RFQ-07, NFR-Audit-01 |

## User Story

**As a** compliance officer  
**I want** a complete audit trail of all RFQ activities  
**So that** I can demonstrate regulatory compliance and investigate trading activities

## Description

Implement comprehensive RFQ audit logging capturing all state changes, user actions, quote interactions, and system events with immutable storage and efficient querying.

## Acceptance Criteria

- [ ] Immutable audit log for all RFQ events
- [ ] Complete timeline reconstruction
- [ ] User action tracking with context
- [ ] Quote pricing history
- [ ] System event logging
- [ ] Query API for compliance review
- [ ] Export capability for regulatory reports

## Technical Details

### Audit Log Schema

```sql
-- migrations/20240122_create_rfq_audit_log.sql

CREATE TYPE audit_event_category AS ENUM (
    'lifecycle',      -- RFQ state changes
    'quote',          -- Quote arrivals, updates, expirations
    'execution',      -- Selection, execution, fills
    'user_action',    -- Manual actions by traders
    'system',         -- Timeouts, validations, distributions
    'error'           -- Failures, rejections
);

CREATE TABLE rfq_audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Event identification
    event_id VARCHAR(100) NOT NULL UNIQUE,
    sequence_number BIGSERIAL,
    
    -- RFQ reference
    rfq_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    
    -- Event details
    category audit_event_category NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    event_timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    -- Actor
    actor_type VARCHAR(20) NOT NULL,  -- 'user', 'system', 'lp'
    actor_id VARCHAR(100),
    actor_name VARCHAR(200),
    
    -- State change
    previous_state JSONB,
    new_state JSONB,
    
    -- Event payload
    payload JSONB NOT NULL DEFAULT '{}',
    
    -- Context
    correlation_id VARCHAR(100),
    session_id VARCHAR(100),
    ip_address INET,
    user_agent TEXT,
    
    -- Immutability
    checksum VARCHAR(64) NOT NULL,  -- SHA-256 of event content
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    -- Indexes for querying
    CONSTRAINT audit_log_immutable CHECK (created_at IS NOT NULL)
);

-- Partition by month for performance
CREATE INDEX idx_audit_rfq_timestamp ON rfq_audit_log(rfq_id, event_timestamp DESC);
CREATE INDEX idx_audit_tenant_timestamp ON rfq_audit_log(tenant_id, event_timestamp DESC);
CREATE INDEX idx_audit_category ON rfq_audit_log(category, event_timestamp DESC);
CREATE INDEX idx_audit_event_type ON rfq_audit_log(event_type, event_timestamp DESC);
CREATE INDEX idx_audit_actor ON rfq_audit_log(actor_id, event_timestamp DESC);
CREATE INDEX idx_audit_sequence ON rfq_audit_log(rfq_id, sequence_number);

-- Create partitions
CREATE TABLE rfq_audit_log_y2024m01 PARTITION OF rfq_audit_log
    FOR VALUES FROM ('2024-01-01') TO ('2024-02-01');
-- ... additional partitions
```

### Audit Event Entity

```typescript
// services/rfq-service/src/audit/entities/audit-event.entity.ts
import { Entity, Column, PrimaryGeneratedColumn, Index, CreateDateColumn } from 'typeorm';

export type AuditEventCategory = 
  | 'lifecycle' | 'quote' | 'execution' | 'user_action' | 'system' | 'error';

export type ActorType = 'user' | 'system' | 'lp';

@Entity('rfq_audit_log')
export class AuditEventEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ name: 'event_id', unique: true })
  eventId: string;

  @Column({ name: 'sequence_number', type: 'bigint' })
  sequenceNumber: number;

  @Column('uuid', { name: 'rfq_id' })
  @Index()
  rfqId: string;

  @Column('uuid', { name: 'tenant_id' })
  @Index()
  tenantId: string;

  @Column({ type: 'enum', enum: ['lifecycle', 'quote', 'execution', 'user_action', 'system', 'error'] })
  category: AuditEventCategory;

  @Column({ name: 'event_type' })
  eventType: string;

  @Column({ name: 'event_timestamp', type: 'timestamptz' })
  @Index()
  eventTimestamp: Date;

  @Column({ name: 'actor_type' })
  actorType: ActorType;

  @Column({ name: 'actor_id', nullable: true })
  actorId?: string;

  @Column({ name: 'actor_name', nullable: true })
  actorName?: string;

  @Column('jsonb', { name: 'previous_state', nullable: true })
  previousState?: any;

  @Column('jsonb', { name: 'new_state', nullable: true })
  newState?: any;

  @Column('jsonb', { default: {} })
  payload: any;

  @Column({ name: 'correlation_id', nullable: true })
  correlationId?: string;

  @Column({ name: 'session_id', nullable: true })
  sessionId?: string;

  @Column({ name: 'ip_address', type: 'inet', nullable: true })
  ipAddress?: string;

  @Column({ name: 'user_agent', nullable: true })
  userAgent?: string;

  @Column()
  checksum: string;

  @CreateDateColumn({ name: 'created_at' })
  createdAt: Date;
}
```

### Audit Service

```typescript
// services/rfq-service/src/audit/audit.service.ts
import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { createHash } from 'crypto';
import { v4 as uuidv4 } from 'uuid';
import { logger } from '@orion/observability';
import { AuditEventEntity, AuditEventCategory, ActorType } from './entities/audit-event.entity';
import { RequestContext } from '@orion/core';

export interface AuditEntry {
  rfqId: string;
  tenantId: string;
  category: AuditEventCategory;
  eventType: string;
  actorType: ActorType;
  actorId?: string;
  actorName?: string;
  previousState?: any;
  newState?: any;
  payload?: any;
}

@Injectable()
export class AuditService {
  constructor(
    @InjectRepository(AuditEventEntity)
    private readonly auditRepository: Repository<AuditEventEntity>,
  ) {}

  async log(entry: AuditEntry): Promise<AuditEventEntity> {
    const context = RequestContext.current();
    const eventId = uuidv4();
    const eventTimestamp = new Date();

    const event = this.auditRepository.create({
      eventId,
      rfqId: entry.rfqId,
      tenantId: entry.tenantId,
      category: entry.category,
      eventType: entry.eventType,
      eventTimestamp,
      actorType: entry.actorType,
      actorId: entry.actorId,
      actorName: entry.actorName,
      previousState: entry.previousState,
      newState: entry.newState,
      payload: entry.payload || {},
      correlationId: context?.correlationId,
      sessionId: context?.sessionId,
      ipAddress: context?.ipAddress,
      userAgent: context?.userAgent,
      checksum: this.calculateChecksum(entry, eventTimestamp),
    });

    const saved = await this.auditRepository.save(event);
    
    logger.debug('Audit event logged', { 
      eventId, 
      rfqId: entry.rfqId, 
      eventType: entry.eventType 
    });

    return saved;
  }

  async logBatch(entries: AuditEntry[]): Promise<void> {
    const events = entries.map(entry => {
      const eventId = uuidv4();
      const eventTimestamp = new Date();
      const context = RequestContext.current();

      return this.auditRepository.create({
        eventId,
        rfqId: entry.rfqId,
        tenantId: entry.tenantId,
        category: entry.category,
        eventType: entry.eventType,
        eventTimestamp,
        actorType: entry.actorType,
        actorId: entry.actorId,
        actorName: entry.actorName,
        previousState: entry.previousState,
        newState: entry.newState,
        payload: entry.payload || {},
        correlationId: context?.correlationId,
        checksum: this.calculateChecksum(entry, eventTimestamp),
      });
    });

    await this.auditRepository.save(events);
  }

  private calculateChecksum(entry: AuditEntry, timestamp: Date): string {
    const content = JSON.stringify({
      rfqId: entry.rfqId,
      eventType: entry.eventType,
      timestamp: timestamp.toISOString(),
      previousState: entry.previousState,
      newState: entry.newState,
      payload: entry.payload,
    });

    return createHash('sha256').update(content).digest('hex');
  }
}
```

### RFQ Audit Interceptor

```typescript
// services/rfq-service/src/audit/rfq-audit.interceptor.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { OnEvent } from '@nestjs/event-emitter';
import { AuditService } from './audit.service';
import { RfqService } from '../rfq.service';

@Injectable()
export class RfqAuditInterceptor {
  constructor(
    private readonly auditService: AuditService,
    private readonly rfqService: RfqService,
  ) {}

  @OnEvent('rfq.created')
  async handleRfqCreated(event: {
    rfqId: string;
    tenantId: string;
    traderId: string;
    symbol: string;
    side: string;
    quantity: number;
  }) {
    await this.auditService.log({
      rfqId: event.rfqId,
      tenantId: event.tenantId,
      category: 'lifecycle',
      eventType: 'rfq.created',
      actorType: 'user',
      actorId: event.traderId,
      newState: { status: 'pending' },
      payload: {
        symbol: event.symbol,
        side: event.side,
        quantity: event.quantity,
      },
    });
  }

  @OnEvent('rfq.validated')
  async handleRfqValidated(event: { rfqId: string }) {
    const rfq = await this.rfqService.getRfq(event.rfqId);
    
    await this.auditService.log({
      rfqId: event.rfqId,
      tenantId: rfq.tenantId,
      category: 'lifecycle',
      eventType: 'rfq.validated',
      actorType: 'system',
      previousState: { status: 'pending' },
      newState: { status: 'validated' },
    });
  }

  @OnEvent('rfq.distributed')
  async handleRfqDistributed(event: { rfqId: string; lpCount: number }) {
    const rfq = await this.rfqService.getRfq(event.rfqId);
    
    await this.auditService.log({
      rfqId: event.rfqId,
      tenantId: rfq.tenantId,
      category: 'lifecycle',
      eventType: 'rfq.distributed',
      actorType: 'system',
      previousState: { status: 'validated' },
      newState: { status: 'distributed' },
      payload: { lpCount: event.lpCount },
    });
  }

  @OnEvent('quote.received')
  async handleQuoteReceived(event: {
    rfqId: string;
    quoteId: string;
    lpId: string;
    bidPrice?: number;
    askPrice?: number;
  }) {
    const rfq = await this.rfqService.getRfq(event.rfqId);
    
    await this.auditService.log({
      rfqId: event.rfqId,
      tenantId: rfq.tenantId,
      category: 'quote',
      eventType: 'quote.received',
      actorType: 'lp',
      actorId: event.lpId,
      payload: {
        quoteId: event.quoteId,
        bidPrice: event.bidPrice,
        askPrice: event.askPrice,
      },
    });
  }

  @OnEvent('rfq.filled')
  async handleRfqFilled(event: {
    rfqId: string;
    tradeId: string;
    quoteId: string;
    lpId: string;
    price: number;
    quantity: number;
  }) {
    const rfq = await this.rfqService.getRfq(event.rfqId);
    
    await this.auditService.log({
      rfqId: event.rfqId,
      tenantId: rfq.tenantId,
      category: 'execution',
      eventType: 'rfq.filled',
      actorType: 'user',
      actorId: rfq.traderId,
      previousState: { status: 'executed' },
      newState: { status: 'filled', tradeId: event.tradeId },
      payload: {
        quoteId: event.quoteId,
        lpId: event.lpId,
        price: event.price,
        quantity: event.quantity,
      },
    });
  }

  @OnEvent('rfq.expired')
  async handleRfqExpired(event: { rfqId: string }) {
    const rfq = await this.rfqService.getRfq(event.rfqId);
    
    await this.auditService.log({
      rfqId: event.rfqId,
      tenantId: rfq.tenantId,
      category: 'lifecycle',
      eventType: 'rfq.expired',
      actorType: 'system',
      previousState: { status: rfq.status },
      newState: { status: 'expired' },
    });
  }

  @OnEvent('rfq.cancelled')
  async handleRfqCancelled(event: { 
    rfqId: string; 
    reason: string; 
    cancelledBy: string 
  }) {
    const rfq = await this.rfqService.getRfq(event.rfqId);
    
    await this.auditService.log({
      rfqId: event.rfqId,
      tenantId: rfq.tenantId,
      category: 'user_action',
      eventType: 'rfq.cancelled',
      actorType: 'user',
      actorId: event.cancelledBy,
      previousState: { status: rfq.status },
      newState: { status: 'cancelled' },
      payload: { reason: event.reason },
    });
  }
}
```

### Audit Query Service

```typescript
// services/rfq-service/src/audit/audit-query.service.ts
import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, Between, In } from 'typeorm';
import { AuditEventEntity, AuditEventCategory } from './entities/audit-event.entity';

export interface AuditQueryParams {
  rfqId?: string;
  tenantId?: string;
  category?: AuditEventCategory | AuditEventCategory[];
  eventTypes?: string[];
  actorId?: string;
  startDate?: Date;
  endDate?: Date;
  limit?: number;
  offset?: number;
}

export interface AuditTimeline {
  rfqId: string;
  events: AuditEventEntity[];
  summary: {
    createdAt: Date;
    completedAt?: Date;
    finalStatus: string;
    quoteCount: number;
    executedLp?: string;
    executedPrice?: number;
  };
}

@Injectable()
export class AuditQueryService {
  constructor(
    @InjectRepository(AuditEventEntity)
    private readonly auditRepository: Repository<AuditEventEntity>,
  ) {}

  async queryEvents(params: AuditQueryParams): Promise<{
    events: AuditEventEntity[];
    total: number;
  }> {
    const query = this.auditRepository.createQueryBuilder('audit');

    if (params.rfqId) {
      query.andWhere('audit.rfq_id = :rfqId', { rfqId: params.rfqId });
    }

    if (params.tenantId) {
      query.andWhere('audit.tenant_id = :tenantId', { tenantId: params.tenantId });
    }

    if (params.category) {
      const categories = Array.isArray(params.category) ? params.category : [params.category];
      query.andWhere('audit.category IN (:...categories)', { categories });
    }

    if (params.eventTypes) {
      query.andWhere('audit.event_type IN (:...eventTypes)', { eventTypes: params.eventTypes });
    }

    if (params.actorId) {
      query.andWhere('audit.actor_id = :actorId', { actorId: params.actorId });
    }

    if (params.startDate) {
      query.andWhere('audit.event_timestamp >= :startDate', { startDate: params.startDate });
    }

    if (params.endDate) {
      query.andWhere('audit.event_timestamp <= :endDate', { endDate: params.endDate });
    }

    query.orderBy('audit.event_timestamp', 'DESC');

    const total = await query.getCount();

    if (params.offset) {
      query.skip(params.offset);
    }

    query.take(params.limit || 100);

    const events = await query.getMany();

    return { events, total };
  }

  async getRfqTimeline(rfqId: string): Promise<AuditTimeline> {
    const events = await this.auditRepository.find({
      where: { rfqId },
      order: { sequenceNumber: 'ASC' },
    });

    const createdEvent = events.find(e => e.eventType === 'rfq.created');
    const completedEvent = events.find(e => 
      ['rfq.filled', 'rfq.expired', 'rfq.cancelled', 'rfq.rejected'].includes(e.eventType)
    );
    const filledEvent = events.find(e => e.eventType === 'rfq.filled');
    const quoteEvents = events.filter(e => e.eventType === 'quote.received');

    return {
      rfqId,
      events,
      summary: {
        createdAt: createdEvent?.eventTimestamp || events[0]?.eventTimestamp,
        completedAt: completedEvent?.eventTimestamp,
        finalStatus: completedEvent?.newState?.status || events[events.length - 1]?.newState?.status,
        quoteCount: quoteEvents.length,
        executedLp: filledEvent?.payload?.lpId,
        executedPrice: filledEvent?.payload?.price,
      },
    };
  }

  async exportToCSV(params: AuditQueryParams): Promise<string> {
    const { events } = await this.queryEvents({ ...params, limit: 10000 });

    const headers = [
      'Event ID', 'RFQ ID', 'Timestamp', 'Category', 'Event Type',
      'Actor Type', 'Actor ID', 'Previous State', 'New State', 'Payload',
    ].join(',');

    const rows = events.map(e => [
      e.eventId,
      e.rfqId,
      e.eventTimestamp.toISOString(),
      e.category,
      e.eventType,
      e.actorType,
      e.actorId || '',
      JSON.stringify(e.previousState || {}),
      JSON.stringify(e.newState || {}),
      JSON.stringify(e.payload),
    ].map(v => `"${String(v).replace(/"/g, '""')}"`).join(','));

    return [headers, ...rows].join('\n');
  }

  async verifyIntegrity(rfqId: string): Promise<{
    valid: boolean;
    issues: string[];
  }> {
    const events = await this.auditRepository.find({
      where: { rfqId },
      order: { sequenceNumber: 'ASC' },
    });

    const issues: string[] = [];

    // Verify sequence continuity
    for (let i = 1; i < events.length; i++) {
      if (events[i].sequenceNumber !== events[i - 1].sequenceNumber + 1n) {
        issues.push(`Sequence gap between ${events[i - 1].eventId} and ${events[i].eventId}`);
      }
    }

    // Verify checksums (would require recalculating)
    // Additional integrity checks...

    return {
      valid: issues.length === 0,
      issues,
    };
  }
}
```

### Audit Controller

```typescript
// services/rfq-service/src/audit/audit.controller.ts
import { Controller, Get, Param, Query, UseGuards, Res } from '@nestjs/common';
import { Response } from 'express';
import { JwtAuthGuard, RequirePermissions, Permission } from '@orion/security';
import { AuditQueryService, AuditQueryParams } from './audit-query.service';

@Controller('audit')
@UseGuards(JwtAuthGuard)
export class AuditController {
  constructor(private readonly auditQueryService: AuditQueryService) {}

  @Get('rfq/:rfqId')
  @RequirePermissions(Permission.AUDIT_VIEW)
  async getRfqAudit(@Param('rfqId') rfqId: string) {
    return this.auditQueryService.getRfqTimeline(rfqId);
  }

  @Get('events')
  @RequirePermissions(Permission.AUDIT_VIEW)
  async queryEvents(@Query() params: AuditQueryParams) {
    return this.auditQueryService.queryEvents(params);
  }

  @Get('export')
  @RequirePermissions(Permission.AUDIT_EXPORT)
  async exportAudit(
    @Query() params: AuditQueryParams,
    @Res() res: Response,
  ) {
    const csv = await this.auditQueryService.exportToCSV(params);
    
    res.setHeader('Content-Type', 'text/csv');
    res.setHeader('Content-Disposition', `attachment; filename=rfq-audit-${Date.now()}.csv`);
    res.send(csv);
  }

  @Get('verify/:rfqId')
  @RequirePermissions(Permission.AUDIT_ADMIN)
  async verifyIntegrity(@Param('rfqId') rfqId: string) {
    return this.auditQueryService.verifyIntegrity(rfqId);
  }
}
```

## Definition of Done

- [ ] Audit log schema with partitioning
- [ ] Event capture for all RFQ lifecycle events
- [ ] Immutable storage with checksums
- [ ] Timeline reconstruction API
- [ ] CSV export for compliance
- [ ] Integrity verification

## Dependencies

- **US-07-01** through **US-07-06**: All RFQ events

## Test Cases

```typescript
describe('AuditService', () => {
  it('should log RFQ creation event', async () => {
    const rfq = await createRfq();
    
    const events = await auditQueryService.queryEvents({ rfqId: rfq.id });
    expect(events.events.some(e => e.eventType === 'rfq.created')).toBe(true);
  });

  it('should maintain event sequence', async () => {
    const rfq = await createAndFillRfq();
    
    const timeline = await auditQueryService.getRfqTimeline(rfq.id);
    
    const sequences = timeline.events.map(e => e.sequenceNumber);
    expect(sequences).toEqual([...sequences].sort((a, b) => Number(a - b)));
  });

  it('should generate valid checksum', async () => {
    const rfq = await createRfq();
    const timeline = await auditQueryService.getRfqTimeline(rfq.id);
    
    const result = await auditQueryService.verifyIntegrity(rfq.id);
    expect(result.valid).toBe(true);
  });
});
```
