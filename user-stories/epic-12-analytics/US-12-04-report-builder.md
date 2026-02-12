# US-12-04: Report Builder Service

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-12-04 |
| **Epic** | Epic 12: Analytics & Data Products |
| **Title** | Report Builder Service |
| **Priority** | High |
| **Story Points** | 8 |
| **Status** | Ready for Development |

## User Story

**As a** business user  
**I want** a self-service report builder to create and schedule custom reports  
**So that** I can generate recurring reports without developer assistance

## Acceptance Criteria

### AC1: Report Definition Management
- **Given** a user with report creation permissions
- **When** they create a new report definition
- **Then** the system:
  - Stores report configuration (dimensions, measures, filters)
  - Validates query structure and permissions
  - Supports template-based report creation
  - Allows report versioning and duplication

### AC2: Multi-Format Output
- **Given** a report definition with output format specified
- **When** the report is generated
- **Then** the output is rendered in the requested format:
  - PDF with proper formatting and charts
  - Excel with multiple sheets and formulas
  - CSV for data export
  - JSON for API consumption

### AC3: Report Scheduling
- **Given** a report with a schedule defined
- **When** the scheduled time arrives
- **Then** the system:
  - Executes the report query
  - Generates output in specified format
  - Delivers to configured recipients (email, S3, SFTP)
  - Logs execution history and metrics

### AC4: Parameterized Reports
- **Given** a report definition with parameters
- **When** a user runs the report
- **Then** parameter handling works:
  - Prompt for required parameters at runtime
  - Support default parameter values
  - Allow parameter cascading (client → accounts)
  - Validate parameter values

### AC5: Report Access Control
- **Given** a report created by a user
- **When** other users attempt to access it
- **Then** permissions are enforced:
  - Private reports visible only to creator
  - Shared reports accessible to specified users/roles
  - Public reports available to all tenant users
  - Admin can manage all reports

## Technical Specification

### Report Definition Entities

```typescript
// src/analytics/reports/entities/report-definition.entity.ts
import { Entity, PrimaryGeneratedColumn, Column, Index, CreateDateColumn, UpdateDateColumn } from 'typeorm';

export enum ReportType {
  TRADE_ACTIVITY = 'TRADE_ACTIVITY',
  RISK_SUMMARY = 'RISK_SUMMARY',
  CLIENT_ANALYTICS = 'CLIENT_ANALYTICS',
  LP_PERFORMANCE = 'LP_PERFORMANCE',
  MARKET_DATA = 'MARKET_DATA',
  PNL_REPORT = 'PNL_REPORT',
  REGULATORY = 'REGULATORY',
  CUSTOM = 'CUSTOM',
}

export enum OutputFormat {
  PDF = 'PDF',
  EXCEL = 'EXCEL',
  CSV = 'CSV',
  JSON = 'JSON',
}

export enum ReportVisibility {
  PRIVATE = 'PRIVATE',
  SHARED = 'SHARED',
  PUBLIC = 'PUBLIC',
}

@Entity('report_definitions')
@Index(['tenantId', 'createdBy'])
@Index(['tenantId', 'visibility'])
export class ReportDefinitionEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ type: 'uuid' })
  tenantId: string;

  @Column({ type: 'varchar', length: 255 })
  name: string;

  @Column({ type: 'text', nullable: true })
  description: string;

  @Column({ type: 'varchar', length: 50 })
  reportType: ReportType;

  @Column({ type: 'jsonb' })
  queryDefinition: ReportQueryDefinition;

  @Column({ type: 'jsonb', nullable: true })
  visualizations: ReportVisualization[];

  @Column({ type: 'jsonb', nullable: true })
  parameters: ReportParameter[];

  @Column({ type: 'varchar', length: 20, default: OutputFormat.PDF })
  defaultFormat: OutputFormat;

  @Column({ type: 'varchar', length: 20, default: ReportVisibility.PRIVATE })
  visibility: ReportVisibility;

  @Column({ type: 'uuid', array: true, default: '{}' })
  sharedWithUsers: string[];

  @Column({ type: 'varchar', array: true, default: '{}' })
  sharedWithRoles: string[];

  @Column({ type: 'jsonb', nullable: true })
  schedule: ReportSchedule | null;

  @Column({ type: 'varchar', array: true, default: '{}' })
  recipients: string[];

  @Column({ type: 'jsonb', nullable: true })
  deliveryConfig: DeliveryConfig;

  @Column({ type: 'uuid' })
  createdBy: string;

  @Column({ type: 'boolean', default: true })
  isActive: boolean;

  @Column({ type: 'integer', default: 1 })
  version: number;

  @CreateDateColumn()
  createdAt: Date;

  @UpdateDateColumn()
  updatedAt: Date;
}

// Supporting types
interface ReportQueryDefinition {
  dimensions: {
    name: string;
    label: string;
    source: string;
    hierarchy?: string;
  }[];
  measures: {
    name: string;
    label: string;
    aggregation: string;
    format?: string;
  }[];
  filters: {
    dimension: string;
    operator: string;
    value?: any;
    parameter?: string; // Reference to parameter
  }[];
  sorting?: {
    column: string;
    direction: 'ASC' | 'DESC';
  }[];
  limit?: number;
}

interface ReportVisualization {
  id: string;
  type: 'TABLE' | 'BAR_CHART' | 'LINE_CHART' | 'PIE_CHART' | 'AREA_CHART' | 'KPI';
  title: string;
  config: Record<string, any>;
  position: { page: number; order: number };
}

interface ReportParameter {
  id: string;
  name: string;
  label: string;
  type: 'STRING' | 'NUMBER' | 'DATE' | 'DATE_RANGE' | 'SELECT' | 'MULTI_SELECT';
  required: boolean;
  defaultValue?: any;
  options?: { value: any; label: string }[];
  dynamicOptions?: {
    source: string; // Query or endpoint
    valueField: string;
    labelField: string;
    dependsOn?: string;
  };
  validation?: {
    min?: number;
    max?: number;
    pattern?: string;
  };
}

interface ReportSchedule {
  frequency: 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'QUARTERLY';
  dayOfWeek?: number; // 0-6 for weekly
  dayOfMonth?: number; // 1-31 for monthly
  time: string; // HH:mm
  timezone: string;
  enabled: boolean;
  nextRunAt?: Date;
}

interface DeliveryConfig {
  email?: {
    enabled: boolean;
    subject?: string;
    body?: string;
    attachReport: boolean;
  };
  s3?: {
    enabled: boolean;
    bucket: string;
    prefix: string;
    filenamePattern: string;
  };
  sftp?: {
    enabled: boolean;
    host: string;
    port: number;
    username: string;
    path: string;
  };
}

// src/analytics/reports/entities/report-execution.entity.ts
@Entity('report_executions')
@Index(['reportId', 'startedAt'])
@Index(['tenantId', 'startedAt'])
export class ReportExecutionEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ type: 'uuid' })
  tenantId: string;

  @Column({ type: 'uuid' })
  reportId: string;

  @Column({ type: 'varchar', length: 20 })
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

  @Column({ type: 'varchar', length: 20 })
  triggerType: 'MANUAL' | 'SCHEDULED' | 'API';

  @Column({ type: 'uuid', nullable: true })
  triggeredBy: string;

  @Column({ type: 'jsonb', nullable: true })
  parameterValues: Record<string, any>;

  @Column({ type: 'varchar', length: 20 })
  outputFormat: OutputFormat;

  @Column({ type: 'timestamp with time zone' })
  startedAt: Date;

  @Column({ type: 'timestamp with time zone', nullable: true })
  completedAt: Date;

  @Column({ type: 'integer', nullable: true })
  executionTimeMs: number;

  @Column({ type: 'integer', nullable: true })
  rowCount: number;

  @Column({ type: 'varchar', length: 500, nullable: true })
  outputPath: string;

  @Column({ type: 'integer', nullable: true })
  outputSizeBytes: number;

  @Column({ type: 'text', nullable: true })
  errorMessage: string;

  @Column({ type: 'jsonb', nullable: true })
  deliveryStatus: Record<string, { status: string; timestamp: Date; error?: string }>;

  @CreateDateColumn()
  createdAt: Date;
}
```

### Report Builder Service

```typescript
// src/analytics/reports/services/report-builder.service.ts
import { Injectable, Logger, NotFoundException, ForbiddenException } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { v4 as uuidv4 } from 'uuid';
import { ReportDefinitionEntity, ReportType, OutputFormat, ReportVisibility } from '../entities/report-definition.entity';
import { ReportExecutionEntity } from '../entities/report-execution.entity';
import { OlapQueryEngineService } from '../../query-engine/services/olap-query-engine.service';
import { ReportRendererService } from './report-renderer.service';
import { ReportDeliveryService } from './report-delivery.service';

export interface CreateReportDto {
  name: string;
  description?: string;
  reportType: ReportType;
  queryDefinition: any;
  visualizations?: any[];
  parameters?: any[];
  defaultFormat?: OutputFormat;
  visibility?: ReportVisibility;
  sharedWithUsers?: string[];
  sharedWithRoles?: string[];
  schedule?: any;
  recipients?: string[];
  deliveryConfig?: any;
}

export interface RunReportDto {
  parameterValues?: Record<string, any>;
  outputFormat?: OutputFormat;
  async?: boolean;
}

@Injectable()
export class ReportBuilderService {
  private readonly logger = new Logger(ReportBuilderService.name);

  constructor(
    @InjectRepository(ReportDefinitionEntity)
    private readonly reportRepo: Repository<ReportDefinitionEntity>,
    @InjectRepository(ReportExecutionEntity)
    private readonly executionRepo: Repository<ReportExecutionEntity>,
    private readonly queryEngine: OlapQueryEngineService,
    private readonly renderer: ReportRendererService,
    private readonly delivery: ReportDeliveryService,
  ) {}

  async createReport(
    tenantId: string,
    userId: string,
    dto: CreateReportDto,
  ): Promise<ReportDefinitionEntity> {
    // Validate query definition
    await this.validateQueryDefinition(tenantId, dto.queryDefinition);

    const report = this.reportRepo.create({
      tenantId,
      name: dto.name,
      description: dto.description,
      reportType: dto.reportType,
      queryDefinition: dto.queryDefinition,
      visualizations: dto.visualizations || [],
      parameters: dto.parameters || [],
      defaultFormat: dto.defaultFormat || OutputFormat.PDF,
      visibility: dto.visibility || ReportVisibility.PRIVATE,
      sharedWithUsers: dto.sharedWithUsers || [],
      sharedWithRoles: dto.sharedWithRoles || [],
      schedule: dto.schedule,
      recipients: dto.recipients || [],
      deliveryConfig: dto.deliveryConfig,
      createdBy: userId,
      isActive: true,
      version: 1,
    });

    const saved = await this.reportRepo.save(report);

    if (dto.schedule?.enabled) {
      await this.scheduleReport(saved);
    }

    this.logger.log(`Created report ${saved.id}: ${saved.name}`);
    return saved;
  }

  async updateReport(
    tenantId: string,
    userId: string,
    reportId: string,
    dto: Partial<CreateReportDto>,
  ): Promise<ReportDefinitionEntity> {
    const report = await this.getReportWithAccess(tenantId, userId, reportId, 'WRITE');

    if (dto.queryDefinition) {
      await this.validateQueryDefinition(tenantId, dto.queryDefinition);
    }

    Object.assign(report, dto);
    report.version += 1;
    report.updatedAt = new Date();

    const updated = await this.reportRepo.save(report);

    if (dto.schedule !== undefined) {
      if (dto.schedule?.enabled) {
        await this.scheduleReport(updated);
      } else {
        await this.unscheduleReport(updated.id);
      }
    }

    this.logger.log(`Updated report ${reportId}`);
    return updated;
  }

  async runReport(
    tenantId: string,
    userId: string,
    reportId: string,
    dto: RunReportDto = {},
  ): Promise<ReportExecutionEntity> {
    const report = await this.getReportWithAccess(tenantId, userId, reportId, 'READ');

    // Validate and fill parameters
    const parameterValues = await this.resolveParameters(report, dto.parameterValues);

    const execution = await this.executionRepo.save({
      tenantId,
      reportId,
      status: 'PENDING',
      triggerType: 'MANUAL',
      triggeredBy: userId,
      parameterValues,
      outputFormat: dto.outputFormat || report.defaultFormat,
      startedAt: new Date(),
    });

    if (dto.async) {
      // Queue for async execution
      this.executeReportAsync(execution.id);
      return execution;
    } else {
      return this.executeReport(execution);
    }
  }

  private async executeReport(execution: ReportExecutionEntity): Promise<ReportExecutionEntity> {
    try {
      execution.status = 'RUNNING';
      await this.executionRepo.save(execution);

      const report = await this.reportRepo.findOne({ where: { id: execution.reportId } });
      if (!report) throw new Error('Report not found');

      // Build query from definition
      const query = this.buildQueryFromDefinition(
        report,
        execution.tenantId,
        execution.parameterValues,
      );

      // Execute query
      const result = await this.queryEngine.execute(query);

      // Render to requested format
      const output = await this.renderer.render(
        report,
        result.data,
        execution.outputFormat,
      );

      // Save output
      const outputPath = await this.saveOutput(execution.id, output, execution.outputFormat);

      // Update execution record
      execution.status = 'COMPLETED';
      execution.completedAt = new Date();
      execution.executionTimeMs = Date.now() - execution.startedAt.getTime();
      execution.rowCount = result.data.length;
      execution.outputPath = outputPath;
      execution.outputSizeBytes = output.length;

      await this.executionRepo.save(execution);

      // Deliver if recipients configured
      if (report.recipients?.length > 0 && report.deliveryConfig) {
        await this.deliverReport(execution, report, outputPath);
      }

      this.logger.log(`Report ${execution.reportId} executed successfully in ${execution.executionTimeMs}ms`);
      return execution;

    } catch (error) {
      execution.status = 'FAILED';
      execution.completedAt = new Date();
      execution.executionTimeMs = Date.now() - execution.startedAt.getTime();
      execution.errorMessage = error.message;
      await this.executionRepo.save(execution);

      this.logger.error(`Report ${execution.reportId} failed: ${error.message}`);
      throw error;
    }
  }

  private async executeReportAsync(executionId: string): Promise<void> {
    // In production, this would be queued to a job processor
    setImmediate(async () => {
      try {
        const execution = await this.executionRepo.findOne({ where: { id: executionId } });
        if (execution) {
          await this.executeReport(execution);
        }
      } catch (error) {
        this.logger.error(`Async report execution failed: ${error.message}`);
      }
    });
  }

  private buildQueryFromDefinition(
    report: ReportDefinitionEntity,
    tenantId: string,
    parameterValues: Record<string, any>,
  ): any {
    const def = report.queryDefinition;

    // Replace parameter references with values
    const filters = def.filters.map((f: any) => {
      if (f.parameter) {
        return {
          ...f,
          value: parameterValues[f.parameter],
        };
      }
      return f;
    });

    return {
      tenantId,
      dimensions: def.dimensions.map((d: any) => ({
        name: d.name,
        table: this.getTableForDimension(d.source),
        column: d.name,
        alias: d.label,
        hierarchy: d.hierarchy ? { level: d.hierarchy, dateColumn: d.name } : undefined,
      })),
      measures: def.measures.map((m: any) => ({
        name: m.name,
        aggregation: m.aggregation,
        table: 'fact_trades', // Default, could be derived
        column: m.name,
        alias: m.label,
      })),
      filters,
      orderBy: def.sorting,
      limit: def.limit,
    };
  }

  private getTableForDimension(source: string): string {
    const sourceMap: Record<string, string> = {
      'client': 'dim_clients',
      'instrument': 'dim_instruments',
      'lp': 'dim_liquidity_providers',
      'time': 'dim_time',
      'trade': 'fact_trades',
    };
    return sourceMap[source] || 'fact_trades';
  }

  async getReports(
    tenantId: string,
    userId: string,
    userRoles: string[],
    options: { type?: ReportType; visibility?: ReportVisibility; limit?: number; offset?: number },
  ): Promise<{ reports: ReportDefinitionEntity[]; total: number }> {
    const query = this.reportRepo.createQueryBuilder('r')
      .where('r.tenantId = :tenantId', { tenantId })
      .andWhere('r.isActive = true')
      .andWhere(
        `(r.visibility = 'PUBLIC' 
          OR r.createdBy = :userId 
          OR :userId = ANY(r.sharedWithUsers)
          OR r.sharedWithRoles && :userRoles)`,
        { userId, userRoles },
      );

    if (options.type) {
      query.andWhere('r.reportType = :type', { type: options.type });
    }

    if (options.visibility) {
      query.andWhere('r.visibility = :visibility', { visibility: options.visibility });
    }

    const [reports, total] = await query
      .orderBy('r.updatedAt', 'DESC')
      .limit(options.limit || 50)
      .offset(options.offset || 0)
      .getManyAndCount();

    return { reports, total };
  }

  async getReportWithAccess(
    tenantId: string,
    userId: string,
    reportId: string,
    accessType: 'READ' | 'WRITE',
  ): Promise<ReportDefinitionEntity> {
    const report = await this.reportRepo.findOne({
      where: { id: reportId, tenantId },
    });

    if (!report) {
      throw new NotFoundException('Report not found');
    }

    // Check access
    const hasAccess = 
      report.createdBy === userId ||
      report.visibility === ReportVisibility.PUBLIC ||
      report.sharedWithUsers.includes(userId);

    if (!hasAccess) {
      throw new ForbiddenException('Access denied to report');
    }

    // Write access requires ownership
    if (accessType === 'WRITE' && report.createdBy !== userId) {
      throw new ForbiddenException('Only report owner can modify');
    }

    return report;
  }

  async getExecutionHistory(
    tenantId: string,
    reportId: string,
    limit: number = 20,
  ): Promise<ReportExecutionEntity[]> {
    return this.executionRepo.find({
      where: { tenantId, reportId },
      order: { startedAt: 'DESC' },
      take: limit,
    });
  }

  async getExecutionOutput(
    tenantId: string,
    executionId: string,
  ): Promise<{ path: string; format: OutputFormat }> {
    const execution = await this.executionRepo.findOne({
      where: { id: executionId, tenantId },
    });

    if (!execution || execution.status !== 'COMPLETED') {
      throw new NotFoundException('Execution not found or not completed');
    }

    return {
      path: execution.outputPath,
      format: execution.outputFormat as OutputFormat,
    };
  }

  private async validateQueryDefinition(tenantId: string, definition: any): Promise<void> {
    // Validate structure
    if (!definition.dimensions && !definition.measures) {
      throw new Error('Query must have at least one dimension or measure');
    }

    // Validate dimensions exist
    // Validate measures are valid aggregations
    // Check user has access to referenced data
  }

  private async resolveParameters(
    report: ReportDefinitionEntity,
    providedValues: Record<string, any> = {},
  ): Promise<Record<string, any>> {
    const resolved: Record<string, any> = {};

    for (const param of report.parameters || []) {
      if (providedValues[param.name] !== undefined) {
        resolved[param.name] = providedValues[param.name];
      } else if (param.defaultValue !== undefined) {
        resolved[param.name] = param.defaultValue;
      } else if (param.required) {
        throw new Error(`Required parameter '${param.name}' not provided`);
      }

      // Validate value
      if (param.validation && resolved[param.name] !== undefined) {
        this.validateParameterValue(param, resolved[param.name]);
      }
    }

    return resolved;
  }

  private validateParameterValue(param: any, value: any): void {
    const { validation } = param;

    if (validation.min !== undefined && value < validation.min) {
      throw new Error(`Parameter '${param.name}' must be >= ${validation.min}`);
    }

    if (validation.max !== undefined && value > validation.max) {
      throw new Error(`Parameter '${param.name}' must be <= ${validation.max}`);
    }

    if (validation.pattern) {
      const regex = new RegExp(validation.pattern);
      if (!regex.test(value)) {
        throw new Error(`Parameter '${param.name}' does not match required pattern`);
      }
    }
  }

  private async saveOutput(executionId: string, data: Buffer, format: OutputFormat): Promise<string> {
    // In production, save to S3 or similar storage
    const extension = format.toLowerCase();
    const filename = `reports/${executionId}.${extension}`;
    
    // Mock save - would use AWS S3 SDK in production
    this.logger.debug(`Saving report output to ${filename}`);
    
    return filename;
  }

  private async deliverReport(
    execution: ReportExecutionEntity,
    report: ReportDefinitionEntity,
    outputPath: string,
  ): Promise<void> {
    const deliveryStatus: Record<string, any> = {};

    if (report.deliveryConfig?.email?.enabled) {
      try {
        await this.delivery.sendEmail(
          report.recipients,
          report.deliveryConfig.email,
          outputPath,
          execution.outputFormat as OutputFormat,
        );
        deliveryStatus['email'] = { status: 'SENT', timestamp: new Date() };
      } catch (error) {
        deliveryStatus['email'] = { status: 'FAILED', timestamp: new Date(), error: error.message };
      }
    }

    if (report.deliveryConfig?.s3?.enabled) {
      try {
        await this.delivery.uploadToS3(
          outputPath,
          report.deliveryConfig.s3,
          execution,
        );
        deliveryStatus['s3'] = { status: 'UPLOADED', timestamp: new Date() };
      } catch (error) {
        deliveryStatus['s3'] = { status: 'FAILED', timestamp: new Date(), error: error.message };
      }
    }

    execution.deliveryStatus = deliveryStatus;
    await this.executionRepo.save(execution);
  }

  private async scheduleReport(report: ReportDefinitionEntity): Promise<void> {
    // Calculate next run time and register with scheduler
    const nextRun = this.calculateNextRunTime(report.schedule!);
    report.schedule!.nextRunAt = nextRun;
    await this.reportRepo.save(report);
    this.logger.log(`Scheduled report ${report.id} for ${nextRun.toISOString()}`);
  }

  private async unscheduleReport(reportId: string): Promise<void> {
    this.logger.log(`Unscheduled report ${reportId}`);
  }

  private calculateNextRunTime(schedule: any): Date {
    const now = new Date();
    const [hours, minutes] = schedule.time.split(':').map(Number);
    const next = new Date(now);
    next.setHours(hours, minutes, 0, 0);

    switch (schedule.frequency) {
      case 'DAILY':
        if (next <= now) next.setDate(next.getDate() + 1);
        break;
      case 'WEEKLY':
        next.setDate(next.getDate() + ((schedule.dayOfWeek - now.getDay() + 7) % 7 || 7));
        break;
      case 'MONTHLY':
        next.setDate(schedule.dayOfMonth);
        if (next <= now) next.setMonth(next.getMonth() + 1);
        break;
    }

    return next;
  }
}
```

### Report Renderer Service

```typescript
// src/analytics/reports/services/report-renderer.service.ts
import { Injectable, Logger } from '@nestjs/common';
import * as PDFDocument from 'pdfkit';
import * as ExcelJS from 'exceljs';
import { ReportDefinitionEntity, OutputFormat } from '../entities/report-definition.entity';

@Injectable()
export class ReportRendererService {
  private readonly logger = new Logger(ReportRendererService.name);

  async render(
    report: ReportDefinitionEntity,
    data: Record<string, any>[],
    format: OutputFormat,
  ): Promise<Buffer> {
    switch (format) {
      case OutputFormat.PDF:
        return this.renderPdf(report, data);
      case OutputFormat.EXCEL:
        return this.renderExcel(report, data);
      case OutputFormat.CSV:
        return this.renderCsv(report, data);
      case OutputFormat.JSON:
        return this.renderJson(report, data);
      default:
        throw new Error(`Unsupported format: ${format}`);
    }
  }

  private async renderPdf(report: ReportDefinitionEntity, data: Record<string, any>[]): Promise<Buffer> {
    return new Promise((resolve, reject) => {
      const chunks: Buffer[] = [];
      const doc = new PDFDocument({ margin: 50 });

      doc.on('data', (chunk) => chunks.push(chunk));
      doc.on('end', () => resolve(Buffer.concat(chunks)));
      doc.on('error', reject);

      // Header
      doc.fontSize(20).text(report.name, { align: 'center' });
      doc.moveDown();
      
      if (report.description) {
        doc.fontSize(10).text(report.description, { align: 'center' });
        doc.moveDown();
      }

      doc.fontSize(8).text(`Generated: ${new Date().toISOString()}`, { align: 'right' });
      doc.moveDown(2);

      // Render visualizations
      for (const viz of report.visualizations || []) {
        this.renderVisualization(doc, viz, data);
        doc.moveDown(2);
      }

      // Data table
      this.renderPdfTable(doc, report.queryDefinition, data);

      doc.end();
    });
  }

  private renderVisualization(doc: typeof PDFDocument, viz: any, data: any[]): void {
    doc.fontSize(14).text(viz.title);
    doc.moveDown(0.5);

    switch (viz.type) {
      case 'KPI':
        this.renderKpi(doc, viz, data);
        break;
      case 'TABLE':
        // Already rendered below
        break;
      // Chart rendering would require additional libraries
    }
  }

  private renderKpi(doc: typeof PDFDocument, viz: any, data: any[]): void {
    const value = data[0]?.[viz.config.measure] || 0;
    const formatted = this.formatValue(value, viz.config.format);
    
    doc.fontSize(24).text(formatted, { align: 'center' });
    if (viz.config.label) {
      doc.fontSize(10).text(viz.config.label, { align: 'center' });
    }
  }

  private renderPdfTable(doc: typeof PDFDocument, queryDef: any, data: Record<string, any>[]): void {
    if (data.length === 0) {
      doc.text('No data available');
      return;
    }

    const columns = [
      ...queryDef.dimensions.map((d: any) => d.label || d.name),
      ...queryDef.measures.map((m: any) => m.label || m.name),
    ];
    const keys = [
      ...queryDef.dimensions.map((d: any) => d.label || d.name),
      ...queryDef.measures.map((m: any) => m.label || m.name),
    ];

    const columnWidth = (doc.page.width - 100) / columns.length;
    const startX = 50;
    let y = doc.y;

    // Header row
    doc.fontSize(10).font('Helvetica-Bold');
    columns.forEach((col, i) => {
      doc.text(col, startX + i * columnWidth, y, { width: columnWidth, align: 'left' });
    });
    
    y += 20;
    doc.moveTo(startX, y).lineTo(doc.page.width - 50, y).stroke();
    y += 10;

    // Data rows
    doc.font('Helvetica');
    for (const row of data.slice(0, 100)) { // Limit rows in PDF
      if (y > doc.page.height - 100) {
        doc.addPage();
        y = 50;
      }

      keys.forEach((key, i) => {
        const value = this.formatValue(row[key]);
        doc.text(value, startX + i * columnWidth, y, { width: columnWidth, align: 'left' });
      });
      y += 15;
    }

    if (data.length > 100) {
      doc.moveDown();
      doc.text(`... and ${data.length - 100} more rows`, { align: 'center' });
    }
  }

  private async renderExcel(report: ReportDefinitionEntity, data: Record<string, any>[]): Promise<Buffer> {
    const workbook = new ExcelJS.Workbook();
    workbook.creator = 'Orion Analytics';
    workbook.created = new Date();

    // Summary sheet
    const summarySheet = workbook.addWorksheet('Summary');
    summarySheet.addRow(['Report', report.name]);
    summarySheet.addRow(['Description', report.description || '']);
    summarySheet.addRow(['Generated', new Date().toISOString()]);
    summarySheet.addRow(['Row Count', data.length]);

    // Data sheet
    const dataSheet = workbook.addWorksheet('Data');

    if (data.length > 0) {
      // Headers
      const headers = Object.keys(data[0]);
      const headerRow = dataSheet.addRow(headers);
      headerRow.font = { bold: true };
      headerRow.fill = {
        type: 'pattern',
        pattern: 'solid',
        fgColor: { argb: 'FF4472C4' },
      };
      headerRow.font = { color: { argb: 'FFFFFFFF' }, bold: true };

      // Data rows
      for (const row of data) {
        dataSheet.addRow(Object.values(row));
      }

      // Auto-fit columns
      dataSheet.columns.forEach((column) => {
        let maxLength = 10;
        column.eachCell?.({ includeEmpty: true }, (cell) => {
          const cellLength = cell.value?.toString().length || 0;
          maxLength = Math.max(maxLength, cellLength);
        });
        column.width = Math.min(maxLength + 2, 50);
      });

      // Add filters
      dataSheet.autoFilter = {
        from: { row: 1, column: 1 },
        to: { row: 1, column: headers.length },
      };

      // Freeze header row
      dataSheet.views = [{ state: 'frozen', ySplit: 1 }];
    }

    return workbook.xlsx.writeBuffer() as Promise<Buffer>;
  }

  private async renderCsv(report: ReportDefinitionEntity, data: Record<string, any>[]): Promise<Buffer> {
    if (data.length === 0) {
      return Buffer.from('');
    }

    const headers = Object.keys(data[0]);
    const lines: string[] = [];

    // Header row
    lines.push(headers.map(h => this.escapeCsv(h)).join(','));

    // Data rows
    for (const row of data) {
      const values = headers.map(h => this.escapeCsv(row[h]));
      lines.push(values.join(','));
    }

    return Buffer.from(lines.join('\n'), 'utf-8');
  }

  private escapeCsv(value: any): string {
    if (value === null || value === undefined) return '';
    const str = String(value);
    if (str.includes(',') || str.includes('"') || str.includes('\n')) {
      return `"${str.replace(/"/g, '""')}"`;
    }
    return str;
  }

  private async renderJson(report: ReportDefinitionEntity, data: Record<string, any>[]): Promise<Buffer> {
    const output = {
      report: {
        id: report.id,
        name: report.name,
        description: report.description,
        generatedAt: new Date().toISOString(),
      },
      metadata: {
        rowCount: data.length,
        columns: data.length > 0 ? Object.keys(data[0]) : [],
      },
      data,
    };

    return Buffer.from(JSON.stringify(output, null, 2), 'utf-8');
  }

  private formatValue(value: any, format?: string): string {
    if (value === null || value === undefined) return '';
    
    if (typeof value === 'number') {
      switch (format) {
        case 'currency':
          return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(value);
        case 'percent':
          return new Intl.NumberFormat('en-US', { style: 'percent', minimumFractionDigits: 2 }).format(value / 100);
        case 'number':
          return new Intl.NumberFormat('en-US').format(value);
        default:
          return value.toLocaleString();
      }
    }

    if (value instanceof Date) {
      return value.toISOString().split('T')[0];
    }

    return String(value);
  }
}
```

## Database Schema

```sql
-- Report definitions
CREATE TABLE report_definitions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    report_type VARCHAR(50) NOT NULL,
    query_definition JSONB NOT NULL,
    visualizations JSONB,
    parameters JSONB,
    default_format VARCHAR(20) DEFAULT 'PDF',
    visibility VARCHAR(20) DEFAULT 'PRIVATE',
    shared_with_users UUID[] DEFAULT '{}',
    shared_with_roles VARCHAR[] DEFAULT '{}',
    schedule JSONB,
    recipients VARCHAR[] DEFAULT '{}',
    delivery_config JSONB,
    created_by UUID NOT NULL,
    is_active BOOLEAN DEFAULT TRUE,
    version INTEGER DEFAULT 1,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_report_def_tenant ON report_definitions(tenant_id, created_by);
CREATE INDEX idx_report_def_visibility ON report_definitions(tenant_id, visibility);
CREATE INDEX idx_report_def_type ON report_definitions(tenant_id, report_type);

-- Report executions
CREATE TABLE report_executions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    report_id UUID NOT NULL REFERENCES report_definitions(id),
    status VARCHAR(20) NOT NULL,
    trigger_type VARCHAR(20) NOT NULL,
    triggered_by UUID,
    parameter_values JSONB,
    output_format VARCHAR(20) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    execution_time_ms INTEGER,
    row_count INTEGER,
    output_path VARCHAR(500),
    output_size_bytes INTEGER,
    error_message TEXT,
    delivery_status JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_report_exec_report ON report_executions(report_id, started_at);
CREATE INDEX idx_report_exec_tenant ON report_executions(tenant_id, started_at);
CREATE INDEX idx_report_exec_status ON report_executions(status) WHERE status IN ('PENDING', 'RUNNING');

-- Report templates
CREATE TABLE report_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    report_type VARCHAR(50) NOT NULL,
    template_definition JSONB NOT NULL,
    is_system BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
```

## Definition of Done

- [ ] Report CRUD operations working
- [ ] PDF rendering with tables and KPIs
- [ ] Excel export with formatting
- [ ] CSV export working
- [ ] JSON export working
- [ ] Parameter handling and validation
- [ ] Report scheduling functional
- [ ] Email delivery working
- [ ] S3 upload working
- [ ] Access control enforced
- [ ] Unit tests for renderer
- [ ] Integration tests for full workflow
- [ ] API documentation complete

## Test Cases

### Unit Tests
```typescript
describe('ReportRendererService', () => {
  it('should render PDF with data', async () => {
    const report = createTestReport();
    const data = [{ client: 'ABC', trades: 100, volume: 1000000 }];

    const pdf = await renderer.render(report, data, OutputFormat.PDF);

    expect(pdf).toBeInstanceOf(Buffer);
    expect(pdf.length).toBeGreaterThan(0);
  });

  it('should render Excel with multiple sheets', async () => {
    const report = createTestReport();
    const data = generateTestData(100);

    const excel = await renderer.render(report, data, OutputFormat.EXCEL);

    expect(excel).toBeInstanceOf(Buffer);
    // Parse and verify structure
  });

  it('should escape CSV special characters', async () => {
    const data = [{ name: 'Test, Inc.', value: 'Has "quotes"' }];
    const csv = await renderer.render(createTestReport(), data, OutputFormat.CSV);
    
    const csvString = csv.toString();
    expect(csvString).toContain('"Test, Inc."');
    expect(csvString).toContain('"Has ""quotes"""');
  });
});

describe('ReportBuilderService', () => {
  it('should validate required parameters', async () => {
    const report = createTestReport({
      parameters: [{ name: 'client', required: true }],
    });

    await expect(
      service.runReport(tenantId, userId, report.id, {})
    ).rejects.toThrow('Required parameter');
  });

  it('should enforce visibility permissions', async () => {
    const privateReport = await createPrivateReport(user1);

    await expect(
      service.getReportWithAccess(tenantId, user2, privateReport.id, 'READ')
    ).rejects.toThrow('Access denied');
  });
});
```
