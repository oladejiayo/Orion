# User Story: US-10-06 - Risk Alert Engine

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-10-06 |
| **Epic** | Epic 10 - Risk & Controls |
| **Title** | Risk Alert Engine |
| **Priority** | P1 - High |
| **Story Points** | 5 |
| **PRD Reference** | FR-Risk-06, NFR-Risk-02 |

## User Story

**As a** risk manager  
**I want** to receive real-time alerts when risk thresholds are approached or breached  
**So that** I can take proactive action before situations escalate

## Description

Implement risk alert engine with configurable alert rules, multiple notification channels (WebSocket, email, webhook), alert acknowledgment workflow, and alert aggregation to prevent alert fatigue.

## Acceptance Criteria

- [ ] Configurable alert rules
- [ ] Multiple severity levels
- [ ] WebSocket real-time alerts
- [ ] Email notifications
- [ ] Webhook integrations
- [ ] Alert acknowledgment
- [ ] Alert aggregation/deduplication
- [ ] Alert escalation
- [ ] Alert history and analytics

## Technical Details

### Risk Alert Entity

```typescript
// services/risk-service/src/entities/risk-alert.entity.ts
import { Entity, Column, CreateDateColumn, Index } from 'typeorm';

export enum AlertSeverity {
  INFO = 'info',
  WARNING = 'warning',
  CRITICAL = 'critical',
  EMERGENCY = 'emergency',
}

export enum AlertStatus {
  ACTIVE = 'active',
  ACKNOWLEDGED = 'acknowledged',
  RESOLVED = 'resolved',
  ESCALATED = 'escalated',
}

@Entity('risk_alerts')
@Index(['tenantId', 'status', 'createdAt'])
@Index(['tenantId', 'entityType', 'entityId'])
export class RiskAlertEntity {
  @Column('uuid', { primaryKey: true, default: () => 'gen_random_uuid()' })
  id: string;

  @Column('uuid')
  tenantId: string;

  @Column('varchar', { length: 30 })
  entityType: string; // client, instrument, lp, tenant

  @Column('varchar', { length: 100 })
  entityId: string;

  @Column('varchar', { length: 50 })
  alertType: string;

  @Column('varchar', { length: 20 })
  severity: AlertSeverity;

  @Column('varchar', { length: 20, default: AlertStatus.ACTIVE })
  status: AlertStatus;

  @Column('varchar', { length: 500 })
  message: string;

  @Column('decimal', { precision: 20, scale: 4 })
  currentValue: number;

  @Column('decimal', { precision: 20, scale: 4 })
  thresholdValue: number;

  @Column('decimal', { precision: 5, scale: 2 })
  utilizationPercent: number;

  @Column('varchar', { length: 100, nullable: true })
  acknowledgedBy: string;

  @Column('timestamp with time zone', { nullable: true })
  acknowledgedAt: Date;

  @Column('varchar', { length: 500, nullable: true })
  acknowledgmentNote: string;

  @Column('varchar', { length: 100, nullable: true })
  resolvedBy: string;

  @Column('timestamp with time zone', { nullable: true })
  resolvedAt: Date;

  @Column('uuid', { nullable: true })
  escalatedToId: string;

  @Column('timestamp with time zone', { nullable: true })
  escalatedAt: Date;

  @Column('int', { default: 1 })
  occurrenceCount: number;

  @Column('timestamp with time zone', { nullable: true })
  lastOccurrence: Date;

  @Column('jsonb', { nullable: true })
  metadata: any;

  @CreateDateColumn()
  createdAt: Date;
}
```

### Alert Rule Entity

```typescript
// services/risk-service/src/entities/alert-rule.entity.ts
import { Entity, Column, CreateDateColumn, UpdateDateColumn } from 'typeorm';

@Entity('alert_rules')
export class AlertRuleEntity {
  @Column('uuid', { primaryKey: true, default: () => 'gen_random_uuid()' })
  id: string;

  @Column('uuid')
  tenantId: string;

  @Column('varchar', { length: 100 })
  name: string;

  @Column('varchar', { length: 30 })
  riskLimitType: string;

  @Column('decimal', { precision: 5, scale: 2 })
  thresholdPercent: number;

  @Column('varchar', { length: 20 })
  severity: string;

  @Column('boolean', { default: true })
  enabled: boolean;

  @Column('jsonb')
  notifications: {
    websocket: boolean;
    email: boolean;
    webhook: boolean;
    recipients?: string[];
    webhookUrl?: string;
  };

  @Column('int', { default: 60 })
  cooldownSeconds: number; // Prevent alert spam

  @Column('boolean', { default: true })
  autoResolve: boolean; // Auto-resolve when condition clears

  @Column('jsonb', { nullable: true })
  escalationConfig: {
    enabled: boolean;
    afterMinutes: number;
    escalateTo: string[];
  };

  @CreateDateColumn()
  createdAt: Date;

  @UpdateDateColumn()
  updatedAt: Date;
}
```

### Risk Alert Service

```typescript
// services/risk-service/src/alerts/risk-alert.service.ts
import { Injectable } from '@nestjs/common';
import { InjectRepository, InjectEntityManager } from '@nestjs/typeorm';
import { Repository, EntityManager, MoreThan, In } from 'typeorm';
import { Redis } from 'ioredis';
import { InjectRedis } from '@nestjs-modules/ioredis';
import { logger, metrics } from '@orion/observability';
import { RiskAlertEntity, AlertSeverity, AlertStatus } from '../entities/risk-alert.entity';
import { AlertRuleEntity } from '../entities/alert-rule.entity';
import { EmailService } from '@orion/notifications';
import { WebhookService } from '@orion/integrations';
import { EventPublisher } from '@orion/events';

export interface CreateAlertDto {
  tenantId: string;
  entityType: string;
  entityId: string;
  alertType: string;
  severity: AlertSeverity;
  message: string;
  currentValue: number;
  thresholdValue: number;
  metadata?: any;
}

@Injectable()
export class RiskAlertService {
  private readonly cooldownPrefix = 'alert:cooldown:';

  constructor(
    @InjectRepository(RiskAlertEntity)
    private readonly alertRepo: Repository<RiskAlertEntity>,
    @InjectRepository(AlertRuleEntity)
    private readonly ruleRepo: Repository<AlertRuleEntity>,
    @InjectEntityManager()
    private readonly entityManager: EntityManager,
    @InjectRedis() private readonly redis: Redis,
    private readonly emailService: EmailService,
    private readonly webhookService: WebhookService,
    private readonly eventPublisher: EventPublisher,
  ) {}

  /**
   * Create or update a risk alert
   */
  async createAlert(dto: CreateAlertDto): Promise<RiskAlertEntity | null> {
    // Check cooldown to prevent alert spam
    const cooldownKey = `${this.cooldownPrefix}${dto.tenantId}:${dto.entityType}:${dto.entityId}:${dto.alertType}`;
    const inCooldown = await this.redis.get(cooldownKey);

    if (inCooldown) {
      // Update occurrence count on existing alert
      await this.incrementOccurrence(dto);
      return null;
    }

    // Check for existing active alert (deduplication)
    const existing = await this.alertRepo.findOne({
      where: {
        tenantId: dto.tenantId,
        entityType: dto.entityType,
        entityId: dto.entityId,
        alertType: dto.alertType,
        status: In([AlertStatus.ACTIVE, AlertStatus.ACKNOWLEDGED]),
      },
    });

    if (existing) {
      // Update existing alert
      existing.currentValue = dto.currentValue;
      existing.utilizationPercent = (dto.currentValue / dto.thresholdValue) * 100;
      existing.occurrenceCount += 1;
      existing.lastOccurrence = new Date();

      // Escalate severity if needed
      if (this.shouldEscalateSeverity(existing.severity, dto.severity)) {
        existing.severity = dto.severity;
      }

      await this.alertRepo.save(existing);
      await this.sendNotifications(existing);

      return existing;
    }

    // Create new alert
    const alert = this.alertRepo.create({
      ...dto,
      utilizationPercent: (dto.currentValue / dto.thresholdValue) * 100,
      lastOccurrence: new Date(),
    });

    await this.alertRepo.save(alert);

    // Set cooldown
    const rule = await this.getAlertRule(dto.tenantId, dto.alertType);
    const cooldownSeconds = rule?.cooldownSeconds || 60;
    await this.redis.setex(cooldownKey, cooldownSeconds, '1');

    // Send notifications
    await this.sendNotifications(alert);

    // Publish event
    await this.publishAlertEvent(alert, 'created');

    logger.info('Risk alert created', {
      alertId: alert.id,
      type: alert.alertType,
      severity: alert.severity,
      entityType: alert.entityType,
      entityId: alert.entityId,
    });

    metrics.increment('risk.alert.created', {
      type: alert.alertType,
      severity: alert.severity,
    });

    return alert;
  }

  /**
   * Acknowledge alert
   */
  async acknowledgeAlert(
    alertId: string,
    userId: string,
    note?: string,
  ): Promise<RiskAlertEntity> {
    const alert = await this.alertRepo.findOne({
      where: { id: alertId, status: AlertStatus.ACTIVE },
    });

    if (!alert) {
      throw new Error('Active alert not found');
    }

    alert.status = AlertStatus.ACKNOWLEDGED;
    alert.acknowledgedBy = userId;
    alert.acknowledgedAt = new Date();
    alert.acknowledgmentNote = note;

    await this.alertRepo.save(alert);
    await this.publishAlertEvent(alert, 'acknowledged');

    logger.info('Alert acknowledged', { alertId, userId });

    return alert;
  }

  /**
   * Resolve alert
   */
  async resolveAlert(
    alertId: string,
    userId: string,
    auto: boolean = false,
  ): Promise<RiskAlertEntity> {
    const alert = await this.alertRepo.findOne({
      where: { id: alertId, status: In([AlertStatus.ACTIVE, AlertStatus.ACKNOWLEDGED]) },
    });

    if (!alert) {
      throw new Error('Alert not found');
    }

    alert.status = AlertStatus.RESOLVED;
    alert.resolvedBy = auto ? 'SYSTEM' : userId;
    alert.resolvedAt = new Date();

    await this.alertRepo.save(alert);
    await this.publishAlertEvent(alert, 'resolved');

    logger.info('Alert resolved', { alertId, auto });

    return alert;
  }

  /**
   * Auto-resolve alerts when condition clears
   */
  async autoResolveAlerts(
    tenantId: string,
    entityType: string,
    entityId: string,
    alertType: string,
    currentValue: number,
    thresholdValue: number,
  ): Promise<void> {
    // Check if condition is now below threshold
    const utilizationPercent = (currentValue / thresholdValue) * 100;

    // Get rule to check auto-resolve settings
    const rule = await this.getAlertRule(tenantId, alertType);
    if (!rule?.autoResolve) return;

    // Only auto-resolve if significantly below threshold
    if (utilizationPercent < 70) {
      const activeAlerts = await this.alertRepo.find({
        where: {
          tenantId,
          entityType,
          entityId,
          alertType,
          status: In([AlertStatus.ACTIVE, AlertStatus.ACKNOWLEDGED]),
        },
      });

      for (const alert of activeAlerts) {
        await this.resolveAlert(alert.id, 'SYSTEM', true);
      }
    }
  }

  /**
   * Escalate unacknowledged alerts
   */
  async escalateStaleAlerts(): Promise<void> {
    const rules = await this.ruleRepo.find({
      where: { enabled: true },
    });

    for (const rule of rules) {
      if (!rule.escalationConfig?.enabled) continue;

      const escalationTime = new Date(
        Date.now() - rule.escalationConfig.afterMinutes * 60 * 1000,
      );

      const staleAlerts = await this.alertRepo.find({
        where: {
          alertType: rule.riskLimitType,
          status: AlertStatus.ACTIVE,
          createdAt: MoreThan(escalationTime),
          escalatedAt: null,
        },
      });

      for (const alert of staleAlerts) {
        await this.escalateAlert(alert, rule.escalationConfig.escalateTo);
      }
    }
  }

  /**
   * Get active alerts for tenant
   */
  async getActiveAlerts(
    tenantId: string,
    filters?: {
      severity?: AlertSeverity;
      entityType?: string;
      entityId?: string;
    },
  ): Promise<RiskAlertEntity[]> {
    const where: any = {
      tenantId,
      status: In([AlertStatus.ACTIVE, AlertStatus.ACKNOWLEDGED]),
    };

    if (filters?.severity) where.severity = filters.severity;
    if (filters?.entityType) where.entityType = filters.entityType;
    if (filters?.entityId) where.entityId = filters.entityId;

    return this.alertRepo.find({
      where,
      order: { severity: 'DESC', createdAt: 'DESC' },
    });
  }

  /**
   * Get alert summary statistics
   */
  async getAlertSummary(tenantId: string): Promise<{
    active: number;
    acknowledged: number;
    bySeverity: Record<string, number>;
    byType: Record<string, number>;
  }> {
    const activeAlerts = await this.alertRepo.find({
      where: {
        tenantId,
        status: In([AlertStatus.ACTIVE, AlertStatus.ACKNOWLEDGED]),
      },
    });

    const summary = {
      active: activeAlerts.filter(a => a.status === AlertStatus.ACTIVE).length,
      acknowledged: activeAlerts.filter(a => a.status === AlertStatus.ACKNOWLEDGED).length,
      bySeverity: {} as Record<string, number>,
      byType: {} as Record<string, number>,
    };

    for (const alert of activeAlerts) {
      summary.bySeverity[alert.severity] = (summary.bySeverity[alert.severity] || 0) + 1;
      summary.byType[alert.alertType] = (summary.byType[alert.alertType] || 0) + 1;
    }

    return summary;
  }

  private async incrementOccurrence(dto: CreateAlertDto): Promise<void> {
    await this.alertRepo
      .createQueryBuilder()
      .update()
      .set({
        occurrenceCount: () => 'occurrence_count + 1',
        lastOccurrence: new Date(),
        currentValue: dto.currentValue,
      })
      .where({
        tenantId: dto.tenantId,
        entityType: dto.entityType,
        entityId: dto.entityId,
        alertType: dto.alertType,
        status: In([AlertStatus.ACTIVE, AlertStatus.ACKNOWLEDGED]),
      })
      .execute();
  }

  private async getAlertRule(tenantId: string, alertType: string): Promise<AlertRuleEntity | null> {
    return this.ruleRepo.findOne({
      where: { tenantId, riskLimitType: alertType, enabled: true },
    });
  }

  private shouldEscalateSeverity(current: AlertSeverity, new_: AlertSeverity): boolean {
    const order = [AlertSeverity.INFO, AlertSeverity.WARNING, AlertSeverity.CRITICAL, AlertSeverity.EMERGENCY];
    return order.indexOf(new_) > order.indexOf(current);
  }

  private async sendNotifications(alert: RiskAlertEntity): Promise<void> {
    const rule = await this.getAlertRule(alert.tenantId, alert.alertType);
    if (!rule) return;

    // WebSocket notification (always sent for real-time UI)
    if (rule.notifications.websocket !== false) {
      await this.redis.publish('risk:alert:created', JSON.stringify(alert));
    }

    // Email notification
    if (rule.notifications.email && rule.notifications.recipients?.length) {
      await this.sendEmailNotification(alert, rule.notifications.recipients);
    }

    // Webhook notification
    if (rule.notifications.webhook && rule.notifications.webhookUrl) {
      await this.sendWebhookNotification(alert, rule.notifications.webhookUrl);
    }
  }

  private async sendEmailNotification(alert: RiskAlertEntity, recipients: string[]): Promise<void> {
    try {
      await this.emailService.send({
        to: recipients,
        subject: `[${alert.severity.toUpperCase()}] Risk Alert: ${alert.alertType}`,
        template: 'risk-alert',
        data: {
          alertType: alert.alertType,
          severity: alert.severity,
          message: alert.message,
          entityType: alert.entityType,
          entityId: alert.entityId,
          currentValue: alert.currentValue,
          thresholdValue: alert.thresholdValue,
          timestamp: alert.createdAt,
        },
      });
    } catch (error) {
      logger.error('Failed to send alert email', { alertId: alert.id, error });
    }
  }

  private async sendWebhookNotification(alert: RiskAlertEntity, url: string): Promise<void> {
    try {
      await this.webhookService.post(url, {
        event: 'risk.alert',
        data: alert,
      });
    } catch (error) {
      logger.error('Failed to send alert webhook', { alertId: alert.id, error });
    }
  }

  private async escalateAlert(alert: RiskAlertEntity, escalateTo: string[]): Promise<void> {
    alert.status = AlertStatus.ESCALATED;
    alert.escalatedAt = new Date();
    await this.alertRepo.save(alert);

    // Send escalation notifications
    await this.emailService.send({
      to: escalateTo,
      subject: `[ESCALATION] Risk Alert: ${alert.alertType}`,
      template: 'risk-alert-escalation',
      data: {
        ...alert,
        escalationReason: 'Alert not acknowledged within configured timeframe',
      },
    });

    logger.warn('Alert escalated', { alertId: alert.id, escalateTo });
  }

  private async publishAlertEvent(alert: RiskAlertEntity, action: string): Promise<void> {
    await this.eventPublisher.publish({
      type: `risk.alert.${action}`,
      aggregateId: alert.id,
      aggregateType: 'RiskAlert',
      payload: alert,
      metadata: { tenantId: alert.tenantId },
    });
  }
}
```

## Database Schema

```sql
-- Risk alerts table
CREATE TABLE risk_alerts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    entity_type VARCHAR(30) NOT NULL,
    entity_id VARCHAR(100) NOT NULL,
    alert_type VARCHAR(50) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    status VARCHAR(20) DEFAULT 'active',
    message VARCHAR(500) NOT NULL,
    current_value DECIMAL(20, 4) NOT NULL,
    threshold_value DECIMAL(20, 4) NOT NULL,
    utilization_percent DECIMAL(5, 2) NOT NULL,
    acknowledged_by VARCHAR(100),
    acknowledged_at TIMESTAMP WITH TIME ZONE,
    acknowledgment_note VARCHAR(500),
    resolved_by VARCHAR(100),
    resolved_at TIMESTAMP WITH TIME ZONE,
    escalated_to_id UUID,
    escalated_at TIMESTAMP WITH TIME ZONE,
    occurrence_count INT DEFAULT 1,
    last_occurrence TIMESTAMP WITH TIME ZONE,
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_risk_alerts_active ON risk_alerts(tenant_id, status, created_at DESC);
CREATE INDEX idx_risk_alerts_entity ON risk_alerts(tenant_id, entity_type, entity_id);

-- Alert rules table
CREATE TABLE alert_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    risk_limit_type VARCHAR(30) NOT NULL,
    threshold_percent DECIMAL(5, 2) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    enabled BOOLEAN DEFAULT true,
    notifications JSONB NOT NULL,
    cooldown_seconds INT DEFAULT 60,
    auto_resolve BOOLEAN DEFAULT true,
    escalation_config JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
```

## Definition of Done

- [ ] Alert creation with deduplication
- [ ] Multiple severity levels
- [ ] WebSocket notifications
- [ ] Email notifications
- [ ] Webhook integration
- [ ] Acknowledgment workflow
- [ ] Auto-resolution
- [ ] Escalation handling
- [ ] Alert analytics

## Dependencies

- **US-10-03**: Exposure Monitor (triggers alerts)
- **External**: Email service, Webhook service

## Test Cases

```typescript
describe('RiskAlertService', () => {
  it('should create alert and send notifications', async () => {
    await alertService.createAlert({
      tenantId: 'tenant-1',
      entityType: 'client',
      entityId: 'client-1',
      alertType: 'gross_exposure_warning',
      severity: AlertSeverity.WARNING,
      message: 'Gross exposure at 85% of limit',
      currentValue: 850000,
      thresholdValue: 1000000,
    });

    expect(redis.publish).toHaveBeenCalledWith('risk:alert:created', expect.any(String));
  });

  it('should deduplicate alerts within cooldown', async () => {
    await alertService.createAlert(createAlertDto());
    await alertService.createAlert(createAlertDto());

    const alerts = await alertRepo.find({});
    expect(alerts.length).toBe(1);
    expect(alerts[0].occurrenceCount).toBe(2);
  });

  it('should acknowledge alert', async () => {
    const alert = await createAlert();

    await alertService.acknowledgeAlert(alert.id, 'user-1', 'Investigating');

    const updated = await alertRepo.findOne(alert.id);
    expect(updated.status).toBe(AlertStatus.ACKNOWLEDGED);
    expect(updated.acknowledgedBy).toBe('user-1');
  });

  it('should auto-resolve when condition clears', async () => {
    await createAlert({ currentValue: 950000, thresholdValue: 1000000 });

    await alertService.autoResolveAlerts(
      'tenant-1', 'client', 'client-1', 'gross_exposure',
      600000, 1000000 // Now at 60%
    );

    const alerts = await alertRepo.find({ status: AlertStatus.ACTIVE });
    expect(alerts.length).toBe(0);
  });
});
```
