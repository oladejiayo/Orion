# User Story: US-10-07 - Risk Reporting API

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-10-07 |
| **Epic** | Epic 10 - Risk & Controls |
| **Title** | Risk Reporting API |
| **Priority** | P1 - High |
| **Story Points** | 5 |
| **PRD Reference** | FR-Risk-07, NFR-Risk-03 |

## User Story

**As a** risk manager  
**I want** comprehensive risk reports and APIs  
**So that** I can monitor overall risk exposure and compliance

## Description

Implement risk reporting API providing exposure snapshots, utilization reports, breach history, risk trends, and exportable reports for regulatory compliance and internal monitoring.

## Acceptance Criteria

- [ ] Real-time exposure snapshot API
- [ ] Utilization dashboard data
- [ ] Breach history reports
- [ ] Risk trend analysis
- [ ] Export to CSV/PDF
- [ ] Scheduled report generation
- [ ] Tenant-level aggregation
- [ ] Historical data retention

## Technical Details

### Risk Report DTOs

```typescript
// services/risk-service/src/dto/risk-report.dto.ts

export interface ExposureSnapshotDto {
  tenantId: string;
  timestamp: Date;
  clients: ClientExposureDto[];
  instruments: InstrumentExposureDto[];
  totals: TotalExposureDto;
}

export interface ClientExposureDto {
  clientId: string;
  clientName: string;
  grossExposure: number;
  netExposure: number;
  grossLimit: number;
  grossUtilization: number;
  netLimit: number;
  netUtilization: number;
  dailyPnL: number;
  dailyPnLLimit: number;
  dailyPnLUtilization: number;
  positionCount: number;
  openOrderCount: number;
  warningLevel: boolean;
  criticalLevel: boolean;
}

export interface InstrumentExposureDto {
  instrumentId: string;
  symbol: string;
  totalPosition: number;
  notionalValue: number;
  positionLimit: number;
  utilization: number;
  clientCount: number;
}

export interface TotalExposureDto {
  totalGrossExposure: number;
  totalNetExposure: number;
  totalDailyPnL: number;
  clientsAtWarning: number;
  clientsAtCritical: number;
  activeKillSwitches: number;
  activeAlerts: number;
}

export interface BreachHistoryDto {
  id: string;
  timestamp: Date;
  entityType: string;
  entityId: string;
  entityName: string;
  limitType: string;
  limitValue: number;
  breachValue: number;
  breachPercent: number;
  severity: string;
  resolvedAt?: Date;
  resolutionMethod?: string;
}

export interface RiskTrendDto {
  timestamp: Date;
  grossExposure: number;
  netExposure: number;
  dailyPnL: number;
  alertCount: number;
  breachCount: number;
}

export interface UtilizationReportDto {
  reportDate: Date;
  tenantId: string;
  limitUtilizations: LimitUtilizationDto[];
  topUtilized: TopUtilizedDto[];
  trends: {
    avgUtilization7d: number;
    avgUtilization30d: number;
    utilizationChange: number;
  };
}

export interface LimitUtilizationDto {
  limitType: string;
  entityCount: number;
  avgUtilization: number;
  maxUtilization: number;
  atWarningCount: number;
  atCriticalCount: number;
}

export interface TopUtilizedDto {
  entityType: string;
  entityId: string;
  entityName: string;
  limitType: string;
  utilization: number;
}
```

### Risk Reporting Service

```typescript
// services/risk-service/src/reporting/risk-reporting.service.ts
import { Injectable } from '@nestjs/common';
import { InjectRepository, InjectEntityManager } from '@nestjs/typeorm';
import { Repository, EntityManager, Between, LessThan, MoreThan } from 'typeorm';
import { Redis } from 'ioredis';
import { InjectRedis } from '@nestjs-modules/ioredis';
import { logger, metrics } from '@orion/observability';
import { RiskLimitEntity } from '../entities/risk-limit.entity';
import { RiskAlertEntity, AlertSeverity, AlertStatus } from '../entities/risk-alert.entity';
import { KillSwitchEntity } from '../entities/kill-switch.entity';
import { RiskCacheService } from '../cache/risk-cache.service';
import {
  ExposureSnapshotDto,
  ClientExposureDto,
  BreachHistoryDto,
  RiskTrendDto,
  UtilizationReportDto,
} from '../dto/risk-report.dto';

@Injectable()
export class RiskReportingService {
  constructor(
    @InjectRepository(RiskLimitEntity)
    private readonly limitRepo: Repository<RiskLimitEntity>,
    @InjectRepository(RiskAlertEntity)
    private readonly alertRepo: Repository<RiskAlertEntity>,
    @InjectRepository(KillSwitchEntity)
    private readonly killSwitchRepo: Repository<KillSwitchEntity>,
    @InjectEntityManager()
    private readonly entityManager: EntityManager,
    @InjectRedis() private readonly redis: Redis,
    private readonly cacheService: RiskCacheService,
  ) {}

  /**
   * Get real-time exposure snapshot
   */
  async getExposureSnapshot(tenantId: string): Promise<ExposureSnapshotDto> {
    const startTime = Date.now();

    // Get all client exposures from cache
    const clientKeys = await this.redis.keys(`exposure:${tenantId}:*`);
    const clientExposures: ClientExposureDto[] = [];

    for (const key of clientKeys) {
      const cached = await this.redis.hgetall(key);
      if (cached && cached.clientId) {
        const limits = await this.getClientLimits(tenantId, cached.clientId);

        clientExposures.push({
          clientId: cached.clientId,
          clientName: cached.clientName || cached.clientId,
          grossExposure: parseFloat(cached.grossExposure) || 0,
          netExposure: parseFloat(cached.netExposure) || 0,
          grossLimit: limits.grossExposure || 0,
          grossUtilization: this.calculateUtilization(
            parseFloat(cached.grossExposure) || 0,
            limits.grossExposure,
          ),
          netLimit: limits.netExposure || 0,
          netUtilization: this.calculateUtilization(
            parseFloat(cached.netExposure) || 0,
            limits.netExposure,
          ),
          dailyPnL: parseFloat(cached.dailyPnL) || 0,
          dailyPnLLimit: limits.dailyLoss || 0,
          dailyPnLUtilization: this.calculateUtilization(
            Math.abs(parseFloat(cached.dailyPnL) || 0),
            limits.dailyLoss,
          ),
          positionCount: parseInt(cached.positionCount) || 0,
          openOrderCount: parseInt(cached.openOrderCount) || 0,
          warningLevel: this.isAtWarningLevel(cached, limits),
          criticalLevel: this.isAtCriticalLevel(cached, limits),
        });
      }
    }

    // Get instrument exposures
    const instrumentExposures = await this.getInstrumentExposures(tenantId);

    // Get totals
    const totals = await this.calculateTotals(tenantId, clientExposures);

    metrics.histogram('risk.report.snapshot_latency_ms', Date.now() - startTime);

    return {
      tenantId,
      timestamp: new Date(),
      clients: clientExposures.sort((a, b) => b.grossUtilization - a.grossUtilization),
      instruments: instrumentExposures,
      totals,
    };
  }

  /**
   * Get breach history
   */
  async getBreachHistory(
    tenantId: string,
    options: {
      startDate?: Date;
      endDate?: Date;
      entityType?: string;
      limitType?: string;
      limit?: number;
      offset?: number;
    } = {},
  ): Promise<{ breaches: BreachHistoryDto[]; total: number }> {
    const query = this.alertRepo
      .createQueryBuilder('alert')
      .where('alert.tenantId = :tenantId', { tenantId })
      .andWhere('alert.severity IN (:...severities)', {
        severities: [AlertSeverity.CRITICAL, AlertSeverity.EMERGENCY],
      });

    if (options.startDate) {
      query.andWhere('alert.createdAt >= :startDate', { startDate: options.startDate });
    }
    if (options.endDate) {
      query.andWhere('alert.createdAt <= :endDate', { endDate: options.endDate });
    }
    if (options.entityType) {
      query.andWhere('alert.entityType = :entityType', { entityType: options.entityType });
    }
    if (options.limitType) {
      query.andWhere('alert.alertType = :limitType', { limitType: options.limitType });
    }

    const total = await query.getCount();

    query
      .orderBy('alert.createdAt', 'DESC')
      .limit(options.limit || 50)
      .offset(options.offset || 0);

    const alerts = await query.getMany();

    const breaches: BreachHistoryDto[] = alerts.map(alert => ({
      id: alert.id,
      timestamp: alert.createdAt,
      entityType: alert.entityType,
      entityId: alert.entityId,
      entityName: alert.metadata?.entityName || alert.entityId,
      limitType: alert.alertType,
      limitValue: Number(alert.thresholdValue),
      breachValue: Number(alert.currentValue),
      breachPercent: Number(alert.utilizationPercent),
      severity: alert.severity,
      resolvedAt: alert.resolvedAt,
      resolutionMethod: alert.resolvedBy === 'SYSTEM' ? 'auto' : 'manual',
    }));

    return { breaches, total };
  }

  /**
   * Get risk trends over time
   */
  async getRiskTrends(
    tenantId: string,
    options: {
      interval: 'hour' | 'day' | 'week';
      periods: number;
    },
  ): Promise<RiskTrendDto[]> {
    const intervalMap = {
      hour: 3600,
      day: 86400,
      week: 604800,
    };

    const intervalSeconds = intervalMap[options.interval];
    const trends: RiskTrendDto[] = [];

    // Get historical snapshots from TimescaleDB
    const query = `
      SELECT
        time_bucket($1, snapshot_time) AS bucket,
        AVG(gross_exposure) as avg_gross_exposure,
        AVG(net_exposure) as avg_net_exposure,
        AVG(daily_pnl) as avg_daily_pnl,
        SUM(alert_count) as total_alerts,
        SUM(breach_count) as total_breaches
      FROM risk_snapshots
      WHERE tenant_id = $2
        AND snapshot_time >= NOW() - INTERVAL '${options.periods} ${options.interval}s'
      GROUP BY bucket
      ORDER BY bucket ASC
    `;

    const results = await this.entityManager.query(query, [
      `${intervalSeconds} seconds`,
      tenantId,
    ]);

    for (const row of results) {
      trends.push({
        timestamp: row.bucket,
        grossExposure: parseFloat(row.avg_gross_exposure) || 0,
        netExposure: parseFloat(row.avg_net_exposure) || 0,
        dailyPnL: parseFloat(row.avg_daily_pnl) || 0,
        alertCount: parseInt(row.total_alerts) || 0,
        breachCount: parseInt(row.total_breaches) || 0,
      });
    }

    return trends;
  }

  /**
   * Get utilization report
   */
  async getUtilizationReport(tenantId: string): Promise<UtilizationReportDto> {
    const limits = await this.limitRepo.find({
      where: { tenantId, isActive: true },
    });

    const utilizationByType: Map<string, LimitUtilizationDto> = new Map();

    for (const limit of limits) {
      const currentValue = await this.getCurrentValueForLimit(tenantId, limit);
      const utilization = this.calculateUtilization(currentValue, Number(limit.limitValue));

      if (!utilizationByType.has(limit.limitType)) {
        utilizationByType.set(limit.limitType, {
          limitType: limit.limitType,
          entityCount: 0,
          avgUtilization: 0,
          maxUtilization: 0,
          atWarningCount: 0,
          atCriticalCount: 0,
        });
      }

      const typeUtil = utilizationByType.get(limit.limitType)!;
      typeUtil.entityCount += 1;
      typeUtil.avgUtilization += utilization;
      typeUtil.maxUtilization = Math.max(typeUtil.maxUtilization, utilization);

      if (utilization >= Number(limit.warningThreshold || 80)) {
        typeUtil.atWarningCount += 1;
      }
      if (utilization >= Number(limit.criticalThreshold || 95)) {
        typeUtil.atCriticalCount += 1;
      }
    }

    // Calculate averages
    const limitUtilizations: LimitUtilizationDto[] = [];
    for (const [type, util] of utilizationByType) {
      util.avgUtilization = util.avgUtilization / util.entityCount;
      limitUtilizations.push(util);
    }

    // Get top utilized entities
    const topUtilized = await this.getTopUtilizedEntities(tenantId);

    // Get historical trends
    const trends = await this.getUtilizationTrends(tenantId);

    return {
      reportDate: new Date(),
      tenantId,
      limitUtilizations,
      topUtilized,
      trends,
    };
  }

  /**
   * Generate scheduled report
   */
  async generateScheduledReport(
    tenantId: string,
    reportType: 'daily' | 'weekly' | 'monthly',
  ): Promise<{
    reportId: string;
    generatedAt: Date;
    downloadUrl: string;
  }> {
    const snapshot = await this.getExposureSnapshot(tenantId);
    const breaches = await this.getBreachHistory(tenantId, {
      startDate: this.getReportStartDate(reportType),
    });
    const utilization = await this.getUtilizationReport(tenantId);

    // Generate report document
    const reportId = `report-${Date.now()}`;
    const report = {
      id: reportId,
      type: reportType,
      tenantId,
      generatedAt: new Date(),
      snapshot,
      breaches: breaches.breaches,
      utilization,
    };

    // Store report in S3
    const downloadUrl = await this.storeReport(report);

    logger.info('Scheduled report generated', { reportId, reportType, tenantId });

    return {
      reportId,
      generatedAt: report.generatedAt,
      downloadUrl,
    };
  }

  /**
   * Export report to CSV
   */
  async exportToCSV(
    tenantId: string,
    reportType: 'exposure' | 'breaches' | 'utilization',
  ): Promise<string> {
    let data: any[];
    let headers: string[];

    switch (reportType) {
      case 'exposure':
        const snapshot = await this.getExposureSnapshot(tenantId);
        data = snapshot.clients;
        headers = [
          'Client ID', 'Client Name', 'Gross Exposure', 'Net Exposure',
          'Gross Utilization %', 'Net Utilization %', 'Daily PnL',
          'Position Count', 'Warning Level', 'Critical Level'
        ];
        break;

      case 'breaches':
        const breaches = await this.getBreachHistory(tenantId, { limit: 1000 });
        data = breaches.breaches;
        headers = [
          'Timestamp', 'Entity Type', 'Entity ID', 'Limit Type',
          'Limit Value', 'Breach Value', 'Breach %', 'Severity', 'Resolved At'
        ];
        break;

      case 'utilization':
        const utilization = await this.getUtilizationReport(tenantId);
        data = utilization.limitUtilizations;
        headers = [
          'Limit Type', 'Entity Count', 'Avg Utilization %',
          'Max Utilization %', 'At Warning Count', 'At Critical Count'
        ];
        break;
    }

    return this.convertToCSV(headers, data);
  }

  private async getClientLimits(tenantId: string, clientId: string): Promise<Record<string, number>> {
    const limits = await this.limitRepo.find({
      where: { tenantId, entityType: 'client', entityId: clientId, isActive: true },
    });

    const result: Record<string, number> = {};
    for (const limit of limits) {
      result[limit.limitType.replace('_', '')] = Number(limit.limitValue);
    }

    return result;
  }

  private async getInstrumentExposures(tenantId: string): Promise<InstrumentExposureDto[]> {
    // Query aggregated instrument positions
    const query = `
      SELECT
        p.instrument_id,
        i.symbol,
        SUM(p.quantity) as total_position,
        SUM(p.quantity * p.mark_price) as notional_value,
        COUNT(DISTINCT p.client_id) as client_count
      FROM positions p
      JOIN instruments i ON p.instrument_id = i.id
      WHERE p.tenant_id = $1
      GROUP BY p.instrument_id, i.symbol
    `;

    const results = await this.entityManager.query(query, [tenantId]);

    return results.map((row: any) => ({
      instrumentId: row.instrument_id,
      symbol: row.symbol,
      totalPosition: parseFloat(row.total_position) || 0,
      notionalValue: parseFloat(row.notional_value) || 0,
      positionLimit: 0, // Fetch from limits
      utilization: 0,
      clientCount: parseInt(row.client_count) || 0,
    }));
  }

  private async calculateTotals(
    tenantId: string,
    clientExposures: ClientExposureDto[],
  ): Promise<TotalExposureDto> {
    const activeAlerts = await this.alertRepo.count({
      where: { tenantId, status: AlertStatus.ACTIVE },
    });

    const activeKillSwitches = await this.killSwitchRepo.count({
      where: { tenantId, isActive: true },
    });

    return {
      totalGrossExposure: clientExposures.reduce((sum, c) => sum + c.grossExposure, 0),
      totalNetExposure: clientExposures.reduce((sum, c) => sum + c.netExposure, 0),
      totalDailyPnL: clientExposures.reduce((sum, c) => sum + c.dailyPnL, 0),
      clientsAtWarning: clientExposures.filter(c => c.warningLevel).length,
      clientsAtCritical: clientExposures.filter(c => c.criticalLevel).length,
      activeKillSwitches,
      activeAlerts,
    };
  }

  private calculateUtilization(current: number, limit: number): number {
    if (!limit || limit === 0) return 0;
    return Math.min(100, (Math.abs(current) / limit) * 100);
  }

  private isAtWarningLevel(cached: any, limits: Record<string, number>): boolean {
    return (
      this.calculateUtilization(parseFloat(cached.grossExposure) || 0, limits.grossExposure) >= 80 ||
      this.calculateUtilization(parseFloat(cached.netExposure) || 0, limits.netExposure) >= 80
    );
  }

  private isAtCriticalLevel(cached: any, limits: Record<string, number>): boolean {
    return (
      this.calculateUtilization(parseFloat(cached.grossExposure) || 0, limits.grossExposure) >= 95 ||
      this.calculateUtilization(parseFloat(cached.netExposure) || 0, limits.netExposure) >= 95
    );
  }

  private async getTopUtilizedEntities(tenantId: string): Promise<TopUtilizedDto[]> {
    // Implementation to get top 10 most utilized entities
    return [];
  }

  private async getUtilizationTrends(tenantId: string): Promise<{
    avgUtilization7d: number;
    avgUtilization30d: number;
    utilizationChange: number;
  }> {
    return { avgUtilization7d: 0, avgUtilization30d: 0, utilizationChange: 0 };
  }

  private async getCurrentValueForLimit(tenantId: string, limit: RiskLimitEntity): Promise<number> {
    const cacheKey = `exposure:${tenantId}:${limit.entityId}`;
    const cached = await this.redis.hgetall(cacheKey);

    switch (limit.limitType) {
      case 'gross_exposure':
        return parseFloat(cached.grossExposure) || 0;
      case 'net_exposure':
        return parseFloat(cached.netExposure) || 0;
      case 'daily_loss':
        return Math.abs(parseFloat(cached.dailyPnL) || 0);
      default:
        return 0;
    }
  }

  private getReportStartDate(reportType: 'daily' | 'weekly' | 'monthly'): Date {
    const now = new Date();
    switch (reportType) {
      case 'daily':
        return new Date(now.setDate(now.getDate() - 1));
      case 'weekly':
        return new Date(now.setDate(now.getDate() - 7));
      case 'monthly':
        return new Date(now.setMonth(now.getMonth() - 1));
    }
  }

  private async storeReport(report: any): Promise<string> {
    // Store in S3 and return signed URL
    return `https://reports.orion.com/${report.id}`;
  }

  private convertToCSV(headers: string[], data: any[]): string {
    const rows = [headers.join(',')];
    for (const item of data) {
      rows.push(Object.values(item).join(','));
    }
    return rows.join('\n');
  }
}
```

### Risk Reporting Controller

```typescript
// services/risk-service/src/controllers/risk-reporting.controller.ts
import {
  Controller,
  Get,
  Query,
  Param,
  Res,
  UseGuards,
  UseInterceptors,
} from '@nestjs/common';
import { Response } from 'express';
import { ApiTags, ApiOperation, ApiQuery } from '@nestjs/swagger';
import { JwtAuthGuard, TenantGuard } from '@orion/auth';
import { TenantId, CacheInterceptor } from '@orion/common';
import { RiskReportingService } from '../reporting/risk-reporting.service';

@ApiTags('Risk Reports')
@Controller('v1/risk/reports')
@UseGuards(JwtAuthGuard, TenantGuard)
export class RiskReportingController {
  constructor(private readonly reportingService: RiskReportingService) {}

  @Get('exposure-snapshot')
  @ApiOperation({ summary: 'Get real-time exposure snapshot' })
  @UseInterceptors(CacheInterceptor)
  async getExposureSnapshot(@TenantId() tenantId: string) {
    return this.reportingService.getExposureSnapshot(tenantId);
  }

  @Get('breach-history')
  @ApiOperation({ summary: 'Get breach history' })
  @ApiQuery({ name: 'startDate', required: false })
  @ApiQuery({ name: 'endDate', required: false })
  @ApiQuery({ name: 'entityType', required: false })
  @ApiQuery({ name: 'limit', required: false })
  @ApiQuery({ name: 'offset', required: false })
  async getBreachHistory(
    @TenantId() tenantId: string,
    @Query('startDate') startDate?: string,
    @Query('endDate') endDate?: string,
    @Query('entityType') entityType?: string,
    @Query('limit') limit?: number,
    @Query('offset') offset?: number,
  ) {
    return this.reportingService.getBreachHistory(tenantId, {
      startDate: startDate ? new Date(startDate) : undefined,
      endDate: endDate ? new Date(endDate) : undefined,
      entityType,
      limit,
      offset,
    });
  }

  @Get('trends')
  @ApiOperation({ summary: 'Get risk trends over time' })
  @ApiQuery({ name: 'interval', enum: ['hour', 'day', 'week'] })
  @ApiQuery({ name: 'periods', type: Number })
  async getRiskTrends(
    @TenantId() tenantId: string,
    @Query('interval') interval: 'hour' | 'day' | 'week' = 'day',
    @Query('periods') periods: number = 7,
  ) {
    return this.reportingService.getRiskTrends(tenantId, { interval, periods });
  }

  @Get('utilization')
  @ApiOperation({ summary: 'Get utilization report' })
  async getUtilizationReport(@TenantId() tenantId: string) {
    return this.reportingService.getUtilizationReport(tenantId);
  }

  @Get('export/:type')
  @ApiOperation({ summary: 'Export report to CSV' })
  async exportReport(
    @TenantId() tenantId: string,
    @Param('type') type: 'exposure' | 'breaches' | 'utilization',
    @Res() res: Response,
  ) {
    const csv = await this.reportingService.exportToCSV(tenantId, type);

    res.setHeader('Content-Type', 'text/csv');
    res.setHeader('Content-Disposition', `attachment; filename=${type}-report.csv`);
    res.send(csv);
  }

  @Get('scheduled/:type')
  @ApiOperation({ summary: 'Generate scheduled report' })
  async generateScheduledReport(
    @TenantId() tenantId: string,
    @Param('type') type: 'daily' | 'weekly' | 'monthly',
  ) {
    return this.reportingService.generateScheduledReport(tenantId, type);
  }
}
```

## Database Schema

```sql
-- Risk snapshots for trend analysis (TimescaleDB hypertable)
CREATE TABLE risk_snapshots (
    snapshot_time TIMESTAMP WITH TIME ZONE NOT NULL,
    tenant_id UUID NOT NULL,
    entity_type VARCHAR(30) NOT NULL,
    entity_id VARCHAR(100) NOT NULL,
    gross_exposure DECIMAL(20, 4),
    net_exposure DECIMAL(20, 4),
    daily_pnl DECIMAL(20, 4),
    alert_count INT DEFAULT 0,
    breach_count INT DEFAULT 0,
    metadata JSONB
);

SELECT create_hypertable('risk_snapshots', 'snapshot_time');

CREATE INDEX idx_risk_snapshots_tenant ON risk_snapshots(tenant_id, snapshot_time DESC);

-- Compression policy (compress data older than 7 days)
SELECT add_compression_policy('risk_snapshots', INTERVAL '7 days');

-- Retention policy (keep 1 year of data)
SELECT add_retention_policy('risk_snapshots', INTERVAL '365 days');

-- Continuous aggregate for daily summaries
CREATE MATERIALIZED VIEW risk_daily_summary
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 day', snapshot_time) AS bucket,
    tenant_id,
    AVG(gross_exposure) as avg_gross_exposure,
    MAX(gross_exposure) as max_gross_exposure,
    AVG(net_exposure) as avg_net_exposure,
    AVG(daily_pnl) as avg_daily_pnl,
    SUM(alert_count) as total_alerts,
    SUM(breach_count) as total_breaches
FROM risk_snapshots
GROUP BY bucket, tenant_id;

-- Refresh policy for continuous aggregate
SELECT add_continuous_aggregate_policy('risk_daily_summary',
    start_offset => INTERVAL '3 days',
    end_offset => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour');
```

## API Documentation

### Endpoints

```yaml
openapi: 3.0.0
paths:
  /v1/risk/reports/exposure-snapshot:
    get:
      summary: Get real-time exposure snapshot
      responses:
        200:
          description: Exposure snapshot with all clients
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ExposureSnapshot'

  /v1/risk/reports/breach-history:
    get:
      summary: Get breach history
      parameters:
        - name: startDate
          in: query
          schema:
            type: string
            format: date-time
        - name: endDate
          in: query
          schema:
            type: string
            format: date-time
        - name: entityType
          in: query
          schema:
            type: string
        - name: limit
          in: query
          schema:
            type: integer
            default: 50
      responses:
        200:
          description: List of breaches
          content:
            application/json:
              schema:
                type: object
                properties:
                  breaches:
                    type: array
                    items:
                      $ref: '#/components/schemas/BreachHistory'
                  total:
                    type: integer

  /v1/risk/reports/trends:
    get:
      summary: Get risk trends over time
      parameters:
        - name: interval
          in: query
          schema:
            type: string
            enum: [hour, day, week]
        - name: periods
          in: query
          schema:
            type: integer
      responses:
        200:
          description: Risk trend data
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/RiskTrend'

  /v1/risk/reports/export/{type}:
    get:
      summary: Export report to CSV
      parameters:
        - name: type
          in: path
          required: true
          schema:
            type: string
            enum: [exposure, breaches, utilization]
      responses:
        200:
          description: CSV file download
          content:
            text/csv:
              schema:
                type: string
```

## Definition of Done

- [ ] Exposure snapshot API
- [ ] Breach history with filtering
- [ ] Risk trends using TimescaleDB
- [ ] Utilization reports
- [ ] CSV/PDF export
- [ ] Scheduled report generation
- [ ] Report storage in S3
- [ ] API documentation

## Dependencies

- **US-10-03**: Exposure Monitor (data source)
- **US-10-06**: Alert Engine (breach data)
- **External**: TimescaleDB, S3

## Test Cases

```typescript
describe('RiskReportingService', () => {
  it('should get exposure snapshot with all clients', async () => {
    await setupTestExposures();

    const snapshot = await reportingService.getExposureSnapshot('tenant-1');

    expect(snapshot.clients.length).toBeGreaterThan(0);
    expect(snapshot.totals.totalGrossExposure).toBeGreaterThan(0);
    expect(snapshot.clients[0].grossUtilization).toBeDefined();
  });

  it('should get breach history with filters', async () => {
    await createTestBreaches();

    const { breaches, total } = await reportingService.getBreachHistory('tenant-1', {
      startDate: new Date('2024-01-01'),
      entityType: 'client',
      limit: 10,
    });

    expect(breaches.length).toBeLessThanOrEqual(10);
    expect(breaches.every(b => b.entityType === 'client')).toBe(true);
  });

  it('should export to CSV', async () => {
    const csv = await reportingService.exportToCSV('tenant-1', 'exposure');

    expect(csv).toContain('Client ID');
    expect(csv.split('\n').length).toBeGreaterThan(1);
  });

  it('should calculate utilization correctly', async () => {
    const report = await reportingService.getUtilizationReport('tenant-1');

    expect(report.limitUtilizations.length).toBeGreaterThan(0);
    expect(report.limitUtilizations[0].avgUtilization).toBeDefined();
  });
});
```
