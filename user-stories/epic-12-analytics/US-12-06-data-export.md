# US-12-06: Data Export Service

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-12-06 |
| **Epic** | Epic 12: Analytics & Data Products |
| **Title** | Data Export Service |
| **Priority** | Medium |
| **Story Points** | 5 |
| **Status** | Ready for Development |

## User Story

**As a** business user  
**I want** to export large datasets from the analytics platform  
**So that** I can perform offline analysis or integrate with external systems

## Acceptance Criteria

### AC1: Export Request Management
- **Given** a user with export permissions
- **When** they submit an export request
- **Then** the system:
  - Validates query and permissions
  - Creates export job with unique ID
  - Queues for asynchronous processing
  - Returns job ID for status tracking

### AC2: Large Dataset Handling
- **Given** an export request for millions of records
- **When** the export is processed
- **Then** large datasets are handled:
  - Streaming export (no full dataset in memory)
  - Progress tracking and estimation
  - Chunked file creation for very large exports
  - Compression for efficient storage

### AC3: Multiple Output Formats
- **Given** an export request with format specification
- **When** the export completes
- **Then** the output is in the correct format:
  - CSV with configurable delimiters
  - Excel (single or multi-sheet)
  - JSON (streaming NDJSON for large)
  - Parquet for analytics tools

### AC4: Export Delivery
- **Given** a completed export
- **When** delivery is requested
- **Then** the file is delivered:
  - Direct download link (time-limited)
  - S3 bucket upload
  - SFTP transfer
  - Email notification with link

### AC5: Export Quotas and Limits
- **Given** export requests from users
- **When** quotas are checked
- **Then** limits are enforced:
  - Daily export row limits per user
  - Concurrent export job limits
  - File size limits
  - Retention period for exports

## Technical Specification

### Export Entities

```typescript
// src/analytics/export/entities/export-job.entity.ts
import { Entity, PrimaryGeneratedColumn, Column, Index, CreateDateColumn, UpdateDateColumn } from 'typeorm';

export enum ExportFormat {
  CSV = 'CSV',
  EXCEL = 'EXCEL',
  JSON = 'JSON',
  NDJSON = 'NDJSON',
  PARQUET = 'PARQUET',
}

export enum ExportStatus {
  PENDING = 'PENDING',
  QUEUED = 'QUEUED',
  PROCESSING = 'PROCESSING',
  COMPLETED = 'COMPLETED',
  FAILED = 'FAILED',
  CANCELLED = 'CANCELLED',
  EXPIRED = 'EXPIRED',
}

export enum DeliveryMethod {
  DOWNLOAD = 'DOWNLOAD',
  S3 = 'S3',
  SFTP = 'SFTP',
  EMAIL = 'EMAIL',
}

@Entity('export_jobs')
@Index(['tenantId', 'requestedBy'])
@Index(['tenantId', 'status'])
export class ExportJobEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ type: 'uuid' })
  tenantId: string;

  @Column({ type: 'varchar', length: 255 })
  name: string;

  @Column({ type: 'text', nullable: true })
  description: string;

  @Column({ type: 'jsonb' })
  queryDefinition: ExportQuery;

  @Column({ type: 'varchar', length: 20 })
  format: ExportFormat;

  @Column({ type: 'jsonb', nullable: true })
  formatOptions: FormatOptions;

  @Column({ type: 'varchar', length: 20, default: ExportStatus.PENDING })
  status: ExportStatus;

  @Column({ type: 'jsonb', nullable: true })
  delivery: DeliveryConfig;

  @Column({ type: 'uuid' })
  requestedBy: string;

  @Column({ type: 'timestamp with time zone' })
  requestedAt: Date;

  @Column({ type: 'timestamp with time zone', nullable: true })
  startedAt: Date;

  @Column({ type: 'timestamp with time zone', nullable: true })
  completedAt: Date;

  // Progress tracking
  @Column({ type: 'integer', default: 0 })
  processedRows: number;

  @Column({ type: 'integer', nullable: true })
  totalRows: number;

  @Column({ type: 'decimal', precision: 5, scale: 2, default: 0 })
  progressPercent: number;

  // Output information
  @Column({ type: 'varchar', length: 500, nullable: true })
  outputPath: string;

  @Column({ type: 'bigint', nullable: true })
  fileSizeBytes: number;

  @Column({ type: 'integer', nullable: true })
  fileCount: number;

  @Column({ type: 'varchar', length: 500, nullable: true })
  downloadUrl: string;

  @Column({ type: 'timestamp with time zone', nullable: true })
  expiresAt: Date;

  @Column({ type: 'text', nullable: true })
  errorMessage: string;

  @CreateDateColumn()
  createdAt: Date;

  @UpdateDateColumn()
  updatedAt: Date;
}

interface ExportQuery {
  source: 'fact_trades' | 'fact_quotes' | 'fact_orders' | 'custom';
  columns: { name: string; alias?: string; format?: string }[];
  filters: { column: string; operator: string; value: any }[];
  orderBy?: { column: string; direction: 'ASC' | 'DESC' }[];
  dateRange?: { start: Date; end: Date };
}

interface FormatOptions {
  csv?: {
    delimiter: string;
    includeHeaders: boolean;
    quoteStrings: boolean;
    dateFormat: string;
    numberFormat?: string;
  };
  excel?: {
    sheetName: string;
    includeHeaders: boolean;
    freezeHeaders: boolean;
    autoFilter: boolean;
  };
  json?: {
    pretty: boolean;
    dateFormat: 'ISO' | 'EPOCH' | 'CUSTOM';
  };
  parquet?: {
    compression: 'SNAPPY' | 'GZIP' | 'NONE';
    rowGroupSize: number;
  };
}

interface DeliveryConfig {
  method: DeliveryMethod;
  s3?: {
    bucket: string;
    key: string;
    region?: string;
  };
  sftp?: {
    host: string;
    port: number;
    username: string;
    path: string;
  };
  email?: {
    recipients: string[];
    subject?: string;
    includeLink: boolean;
    attachFile: boolean;
    maxAttachmentSize: number;
  };
}
```

### Export Service

```typescript
// src/analytics/export/services/export.service.ts
import { Injectable, Logger, BadRequestException, NotFoundException } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, DataSource } from 'typeorm';
import { InjectQueue } from '@nestjs/bull';
import { Queue } from 'bull';
import { v4 as uuidv4 } from 'uuid';
import * as fs from 'fs';
import * as path from 'path';
import { ExportJobEntity, ExportFormat, ExportStatus, DeliveryMethod } from '../entities/export-job.entity';
import { ExportQuotaService } from './export-quota.service';
import { S3Service } from '../../../common/services/s3.service';

export interface CreateExportDto {
  name: string;
  description?: string;
  queryDefinition: any;
  format: ExportFormat;
  formatOptions?: any;
  delivery?: any;
}

@Injectable()
export class ExportService {
  private readonly logger = new Logger(ExportService.name);
  private readonly tempDir = process.env.EXPORT_TEMP_DIR || '/tmp/exports';
  private readonly maxRowsPerFile = 1000000; // 1M rows per file

  constructor(
    private readonly dataSource: DataSource,
    @InjectRepository(ExportJobEntity)
    private readonly exportJobRepo: Repository<ExportJobEntity>,
    @InjectQueue('exports')
    private readonly exportQueue: Queue,
    private readonly quotaService: ExportQuotaService,
    private readonly s3Service: S3Service,
  ) {
    // Ensure temp directory exists
    if (!fs.existsSync(this.tempDir)) {
      fs.mkdirSync(this.tempDir, { recursive: true });
    }
  }

  async createExport(
    tenantId: string,
    userId: string,
    dto: CreateExportDto,
  ): Promise<ExportJobEntity> {
    // Check quotas
    await this.quotaService.checkQuota(tenantId, userId);

    // Estimate row count
    const estimatedRows = await this.estimateRowCount(tenantId, dto.queryDefinition);
    
    // Validate size limits
    const maxRows = await this.quotaService.getMaxRowsForUser(tenantId, userId);
    if (estimatedRows > maxRows) {
      throw new BadRequestException(
        `Export exceeds maximum row limit (${estimatedRows} > ${maxRows})`,
      );
    }

    // Create export job
    const job = this.exportJobRepo.create({
      tenantId,
      name: dto.name,
      description: dto.description,
      queryDefinition: dto.queryDefinition,
      format: dto.format,
      formatOptions: dto.formatOptions || this.getDefaultFormatOptions(dto.format),
      status: ExportStatus.PENDING,
      delivery: dto.delivery || { method: DeliveryMethod.DOWNLOAD },
      requestedBy: userId,
      requestedAt: new Date(),
      totalRows: estimatedRows,
    });

    const saved = await this.exportJobRepo.save(job);

    // Queue for processing
    await this.exportQueue.add('process-export', {
      jobId: saved.id,
    }, {
      attempts: 3,
      backoff: {
        type: 'exponential',
        delay: 5000,
      },
    });

    saved.status = ExportStatus.QUEUED;
    await this.exportJobRepo.save(saved);

    this.logger.log(`Created export job ${saved.id} for ${estimatedRows} rows`);
    return saved;
  }

  async processExport(jobId: string): Promise<void> {
    const job = await this.exportJobRepo.findOne({ where: { id: jobId } });
    if (!job) {
      throw new NotFoundException('Export job not found');
    }

    try {
      job.status = ExportStatus.PROCESSING;
      job.startedAt = new Date();
      await this.exportJobRepo.save(job);

      // Execute streaming export
      const result = await this.executeExport(job);

      // Upload to storage
      const outputPath = await this.uploadExport(job, result.files);

      // Generate download URL
      const downloadUrl = await this.generateDownloadUrl(job, outputPath);

      // Update job
      job.status = ExportStatus.COMPLETED;
      job.completedAt = new Date();
      job.outputPath = outputPath;
      job.fileSizeBytes = result.totalSize;
      job.fileCount = result.files.length;
      job.processedRows = result.rowCount;
      job.progressPercent = 100;
      job.downloadUrl = downloadUrl;
      job.expiresAt = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000); // 7 days
      await this.exportJobRepo.save(job);

      // Deliver
      await this.deliverExport(job);

      // Update quota usage
      await this.quotaService.recordUsage(job.tenantId, job.requestedBy, result.rowCount);

      // Cleanup temp files
      await this.cleanupTempFiles(result.files);

      this.logger.log(`Export ${jobId} completed: ${result.rowCount} rows`);

    } catch (error) {
      job.status = ExportStatus.FAILED;
      job.completedAt = new Date();
      job.errorMessage = error.message;
      await this.exportJobRepo.save(job);
      throw error;
    }
  }

  private async executeExport(job: ExportJobEntity): Promise<{
    files: string[];
    rowCount: number;
    totalSize: number;
  }> {
    const files: string[] = [];
    let rowCount = 0;
    let totalSize = 0;
    let fileIndex = 0;

    // Build query
    const { sql, parameters } = this.buildExportQuery(job);

    // Stream query results
    const queryRunner = this.dataSource.createQueryRunner();
    await queryRunner.connect();

    try {
      // Set tenant context
      await queryRunner.query(`SET app.tenant_id = $1`, [job.tenantId]);

      const stream = await queryRunner.stream(sql, parameters);
      let currentWriter = await this.createFileWriter(job, fileIndex);
      let currentRowCount = 0;

      for await (const row of stream) {
        // Write row
        await this.writeRow(currentWriter, row, job.format, job.formatOptions);
        rowCount++;
        currentRowCount++;

        // Update progress periodically
        if (rowCount % 10000 === 0) {
          job.processedRows = rowCount;
          job.progressPercent = job.totalRows 
            ? Math.min(99, (rowCount / job.totalRows) * 100) 
            : 0;
          await this.exportJobRepo.save(job);
        }

        // Check if need to split file
        if (currentRowCount >= this.maxRowsPerFile) {
          await this.finalizeFile(currentWriter, job.format);
          files.push(currentWriter.path);
          totalSize += fs.statSync(currentWriter.path).size;

          fileIndex++;
          currentWriter = await this.createFileWriter(job, fileIndex);
          currentRowCount = 0;
        }
      }

      // Finalize last file
      await this.finalizeFile(currentWriter, job.format);
      files.push(currentWriter.path);
      totalSize += fs.statSync(currentWriter.path).size;

      return { files, rowCount, totalSize };

    } finally {
      await queryRunner.release();
    }
  }

  private buildExportQuery(job: ExportJobEntity): { sql: string; parameters: any[] } {
    const query = job.queryDefinition;
    const columns = query.columns.map(c => 
      c.alias ? `${c.name} AS "${c.alias}"` : c.name
    ).join(', ');

    let sql = `SELECT ${columns} FROM ${query.source}`;
    const parameters: any[] = [];
    let paramIndex = 1;

    // Add tenant filter
    sql += ` WHERE tenant_id = $${paramIndex++}`;
    parameters.push(job.tenantId);

    // Add date range filter
    if (query.dateRange) {
      sql += ` AND trade_date BETWEEN $${paramIndex++} AND $${paramIndex++}`;
      parameters.push(query.dateRange.start, query.dateRange.end);
    }

    // Add other filters
    for (const filter of query.filters) {
      sql += ` AND ${filter.column} ${filter.operator} $${paramIndex++}`;
      parameters.push(filter.value);
    }

    // Add ordering
    if (query.orderBy?.length > 0) {
      const orderClauses = query.orderBy.map(o => `${o.column} ${o.direction}`);
      sql += ` ORDER BY ${orderClauses.join(', ')}`;
    }

    return { sql, parameters };
  }

  private async createFileWriter(job: ExportJobEntity, fileIndex: number): Promise<any> {
    const extension = this.getFileExtension(job.format);
    const filename = fileIndex > 0 
      ? `${job.id}_${fileIndex}.${extension}`
      : `${job.id}.${extension}`;
    const filepath = path.join(this.tempDir, filename);

    const writer: any = {
      path: filepath,
      stream: fs.createWriteStream(filepath),
      headerWritten: false,
    };

    // Write headers for CSV
    if (job.format === ExportFormat.CSV && job.formatOptions?.csv?.includeHeaders !== false) {
      writer.headerWritten = false; // Will write on first row
    }

    return writer;
  }

  private async writeRow(
    writer: any,
    row: Record<string, any>,
    format: ExportFormat,
    options: any,
  ): Promise<void> {
    switch (format) {
      case ExportFormat.CSV:
        await this.writeCsvRow(writer, row, options?.csv);
        break;
      case ExportFormat.JSON:
      case ExportFormat.NDJSON:
        await this.writeJsonRow(writer, row, options?.json);
        break;
      // Excel and Parquet would need different handling
    }
  }

  private async writeCsvRow(writer: any, row: Record<string, any>, options: any): Promise<void> {
    const delimiter = options?.delimiter || ',';
    const columns = Object.keys(row);

    // Write header on first row
    if (!writer.headerWritten && options?.includeHeaders !== false) {
      writer.stream.write(columns.join(delimiter) + '\n');
      writer.headerWritten = true;
    }

    const values = columns.map(col => {
      const value = row[col];
      if (value === null || value === undefined) return '';
      
      let str = String(value);
      
      // Quote if contains delimiter, quote, or newline
      if (str.includes(delimiter) || str.includes('"') || str.includes('\n')) {
        str = `"${str.replace(/"/g, '""')}"`;
      }
      
      return str;
    });

    writer.stream.write(values.join(delimiter) + '\n');
  }

  private async writeJsonRow(writer: any, row: Record<string, any>, options: any): Promise<void> {
    const json = JSON.stringify(row);
    writer.stream.write(json + '\n');
  }

  private async finalizeFile(writer: any, format: ExportFormat): Promise<void> {
    return new Promise((resolve, reject) => {
      writer.stream.end((err: any) => {
        if (err) reject(err);
        else resolve();
      });
    });
  }

  private getFileExtension(format: ExportFormat): string {
    switch (format) {
      case ExportFormat.CSV: return 'csv';
      case ExportFormat.EXCEL: return 'xlsx';
      case ExportFormat.JSON: return 'json';
      case ExportFormat.NDJSON: return 'ndjson';
      case ExportFormat.PARQUET: return 'parquet';
      default: return 'dat';
    }
  }

  private getDefaultFormatOptions(format: ExportFormat): any {
    switch (format) {
      case ExportFormat.CSV:
        return {
          csv: {
            delimiter: ',',
            includeHeaders: true,
            quoteStrings: true,
            dateFormat: 'ISO',
          },
        };
      case ExportFormat.EXCEL:
        return {
          excel: {
            sheetName: 'Data',
            includeHeaders: true,
            freezeHeaders: true,
            autoFilter: true,
          },
        };
      case ExportFormat.JSON:
      case ExportFormat.NDJSON:
        return {
          json: {
            pretty: false,
            dateFormat: 'ISO',
          },
        };
      default:
        return {};
    }
  }

  private async estimateRowCount(tenantId: string, query: any): Promise<number> {
    let sql = `SELECT COUNT(*) as count FROM ${query.source} WHERE tenant_id = $1`;
    const params = [tenantId];

    if (query.dateRange) {
      sql += ` AND trade_date BETWEEN $2 AND $3`;
      params.push(query.dateRange.start, query.dateRange.end);
    }

    const result = await this.dataSource.query(sql, params);
    return parseInt(result[0].count, 10);
  }

  private async uploadExport(job: ExportJobEntity, files: string[]): Promise<string> {
    // If multiple files, create a zip
    if (files.length > 1) {
      // Zip files and upload
      // For simplicity, we'll just upload the first file
    }

    const key = `exports/${job.tenantId}/${job.id}/${path.basename(files[0])}`;
    await this.s3Service.uploadFile(files[0], key);
    
    return key;
  }

  private async generateDownloadUrl(job: ExportJobEntity, s3Key: string): Promise<string> {
    return this.s3Service.getSignedUrl(s3Key, 7 * 24 * 60 * 60); // 7 days
  }

  private async deliverExport(job: ExportJobEntity): Promise<void> {
    const delivery = job.delivery;

    switch (delivery.method) {
      case DeliveryMethod.EMAIL:
        await this.deliverByEmail(job);
        break;
      case DeliveryMethod.S3:
        await this.deliverToS3(job, delivery.s3);
        break;
      case DeliveryMethod.SFTP:
        await this.deliverBySftp(job, delivery.sftp);
        break;
      case DeliveryMethod.DOWNLOAD:
        // Already available via downloadUrl
        break;
    }
  }

  private async deliverByEmail(job: ExportJobEntity): Promise<void> {
    const config = job.delivery.email;
    // Send email with download link
    this.logger.log(`Sending export notification to ${config.recipients.join(', ')}`);
  }

  private async deliverToS3(job: ExportJobEntity, config: any): Promise<void> {
    // Copy to client's S3 bucket
    const sourceKey = job.outputPath;
    const destKey = `${config.key}/${path.basename(job.outputPath)}`;
    
    await this.s3Service.copyToExternalBucket(sourceKey, config.bucket, destKey);
    this.logger.log(`Delivered export to s3://${config.bucket}/${destKey}`);
  }

  private async deliverBySftp(job: ExportJobEntity, config: any): Promise<void> {
    // SFTP delivery implementation
    this.logger.log(`SFTP delivery to ${config.host}:${config.path}`);
  }

  private async cleanupTempFiles(files: string[]): Promise<void> {
    for (const file of files) {
      try {
        fs.unlinkSync(file);
      } catch (e) {
        this.logger.warn(`Failed to cleanup temp file: ${file}`);
      }
    }
  }

  async getExportStatus(tenantId: string, jobId: string): Promise<ExportJobEntity> {
    const job = await this.exportJobRepo.findOne({
      where: { id: jobId, tenantId },
    });

    if (!job) {
      throw new NotFoundException('Export job not found');
    }

    return job;
  }

  async listExports(
    tenantId: string,
    userId: string,
    options: { status?: ExportStatus; limit?: number; offset?: number } = {},
  ): Promise<{ exports: ExportJobEntity[]; total: number }> {
    const query = this.exportJobRepo.createQueryBuilder('e')
      .where('e.tenantId = :tenantId', { tenantId })
      .andWhere('e.requestedBy = :userId', { userId });

    if (options.status) {
      query.andWhere('e.status = :status', { status: options.status });
    }

    const [exports, total] = await query
      .orderBy('e.requestedAt', 'DESC')
      .limit(options.limit || 20)
      .offset(options.offset || 0)
      .getManyAndCount();

    return { exports, total };
  }

  async cancelExport(tenantId: string, userId: string, jobId: string): Promise<void> {
    const job = await this.exportJobRepo.findOne({
      where: { id: jobId, tenantId, requestedBy: userId },
    });

    if (!job) {
      throw new NotFoundException('Export job not found');
    }

    if (job.status === ExportStatus.COMPLETED || job.status === ExportStatus.FAILED) {
      throw new BadRequestException('Cannot cancel completed or failed export');
    }

    job.status = ExportStatus.CANCELLED;
    await this.exportJobRepo.save(job);

    // Remove from queue if pending
    // This would require access to the Bull queue job
    
    this.logger.log(`Cancelled export ${jobId}`);
  }
}
```

### Export Quota Service

```typescript
// src/analytics/export/services/export-quota.service.ts
import { Injectable, Logger, BadRequestException } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { ExportJobEntity, ExportStatus } from '../entities/export-job.entity';

interface QuotaConfig {
  maxRowsPerExport: number;
  maxRowsPerDay: number;
  maxConcurrentExports: number;
  maxExportsPerDay: number;
}

@Injectable()
export class ExportQuotaService {
  private readonly logger = new Logger(ExportQuotaService.name);

  private readonly defaultQuotas: QuotaConfig = {
    maxRowsPerExport: 10000000, // 10M rows
    maxRowsPerDay: 50000000, // 50M rows
    maxConcurrentExports: 3,
    maxExportsPerDay: 20,
  };

  constructor(
    @InjectRepository(ExportJobEntity)
    private readonly exportJobRepo: Repository<ExportJobEntity>,
  ) {}

  async checkQuota(tenantId: string, userId: string): Promise<void> {
    const quotas = await this.getQuotasForUser(tenantId, userId);
    
    // Check concurrent exports
    const concurrent = await this.countConcurrentExports(userId);
    if (concurrent >= quotas.maxConcurrentExports) {
      throw new BadRequestException(
        `Maximum concurrent exports reached (${quotas.maxConcurrentExports})`,
      );
    }

    // Check daily export count
    const dailyCount = await this.countDailyExports(userId);
    if (dailyCount >= quotas.maxExportsPerDay) {
      throw new BadRequestException(
        `Daily export limit reached (${quotas.maxExportsPerDay})`,
      );
    }

    // Check daily row usage
    const dailyRows = await this.countDailyRows(userId);
    if (dailyRows >= quotas.maxRowsPerDay) {
      throw new BadRequestException(
        `Daily row export limit reached (${quotas.maxRowsPerDay.toLocaleString()})`,
      );
    }
  }

  async getMaxRowsForUser(tenantId: string, userId: string): Promise<number> {
    const quotas = await this.getQuotasForUser(tenantId, userId);
    const dailyUsed = await this.countDailyRows(userId);
    
    return Math.min(
      quotas.maxRowsPerExport,
      quotas.maxRowsPerDay - dailyUsed,
    );
  }

  async recordUsage(tenantId: string, userId: string, rows: number): Promise<void> {
    // Usage is automatically recorded via export job completion
    this.logger.debug(`Recorded export usage: ${userId} exported ${rows} rows`);
  }

  async getUsageSummary(tenantId: string, userId: string): Promise<{
    dailyRowsUsed: number;
    dailyRowsRemaining: number;
    dailyExportsUsed: number;
    dailyExportsRemaining: number;
    concurrentActive: number;
    concurrentLimit: number;
  }> {
    const quotas = await this.getQuotasForUser(tenantId, userId);
    const dailyRows = await this.countDailyRows(userId);
    const dailyExports = await this.countDailyExports(userId);
    const concurrent = await this.countConcurrentExports(userId);

    return {
      dailyRowsUsed: dailyRows,
      dailyRowsRemaining: Math.max(0, quotas.maxRowsPerDay - dailyRows),
      dailyExportsUsed: dailyExports,
      dailyExportsRemaining: Math.max(0, quotas.maxExportsPerDay - dailyExports),
      concurrentActive: concurrent,
      concurrentLimit: quotas.maxConcurrentExports,
    };
  }

  private async getQuotasForUser(tenantId: string, userId: string): Promise<QuotaConfig> {
    // In production, would fetch from user/tenant configuration
    return this.defaultQuotas;
  }

  private async countConcurrentExports(userId: string): Promise<number> {
    return this.exportJobRepo.count({
      where: {
        requestedBy: userId,
        status: ExportStatus.PROCESSING,
      },
    });
  }

  private async countDailyExports(userId: string): Promise<number> {
    const today = new Date();
    today.setHours(0, 0, 0, 0);

    return this.exportJobRepo
      .createQueryBuilder('e')
      .where('e.requestedBy = :userId', { userId })
      .andWhere('e.requestedAt >= :today', { today })
      .getCount();
  }

  private async countDailyRows(userId: string): Promise<number> {
    const today = new Date();
    today.setHours(0, 0, 0, 0);

    const result = await this.exportJobRepo
      .createQueryBuilder('e')
      .select('COALESCE(SUM(e.processedRows), 0)', 'total')
      .where('e.requestedBy = :userId', { userId })
      .andWhere('e.requestedAt >= :today', { today })
      .andWhere('e.status = :status', { status: ExportStatus.COMPLETED })
      .getRawOne();

    return parseInt(result.total, 10);
  }
}
```

### Export Controller

```typescript
// src/analytics/export/controllers/export.controller.ts
import {
  Controller,
  Get,
  Post,
  Delete,
  Body,
  Param,
  Query,
  UseGuards,
  Res,
} from '@nestjs/common';
import { Response } from 'express';
import { ApiTags, ApiOperation, ApiBearerAuth } from '@nestjs/swagger';
import { JwtAuthGuard } from '../../../auth/guards/jwt-auth.guard';
import { TenantContext } from '../../../common/decorators/tenant-context.decorator';
import { ExportService, CreateExportDto } from '../services/export.service';
import { ExportQuotaService } from '../services/export-quota.service';
import { ExportStatus } from '../entities/export-job.entity';

@ApiTags('Data Export')
@ApiBearerAuth()
@UseGuards(JwtAuthGuard)
@Controller('analytics/exports')
export class ExportController {
  constructor(
    private readonly exportService: ExportService,
    private readonly quotaService: ExportQuotaService,
  ) {}

  @Post()
  @ApiOperation({ summary: 'Create export job' })
  async createExport(
    @TenantContext() ctx: { tenantId: string; userId: string },
    @Body() dto: CreateExportDto,
  ) {
    return this.exportService.createExport(ctx.tenantId, ctx.userId, dto);
  }

  @Get()
  @ApiOperation({ summary: 'List exports' })
  async listExports(
    @TenantContext() ctx: { tenantId: string; userId: string },
    @Query('status') status?: ExportStatus,
    @Query('limit') limit?: number,
    @Query('offset') offset?: number,
  ) {
    return this.exportService.listExports(ctx.tenantId, ctx.userId, {
      status,
      limit,
      offset,
    });
  }

  @Get('quota')
  @ApiOperation({ summary: 'Get export quota usage' })
  async getQuotaUsage(
    @TenantContext() ctx: { tenantId: string; userId: string },
  ) {
    return this.quotaService.getUsageSummary(ctx.tenantId, ctx.userId);
  }

  @Get(':id')
  @ApiOperation({ summary: 'Get export status' })
  async getExportStatus(
    @TenantContext() ctx: { tenantId: string },
    @Param('id') jobId: string,
  ) {
    return this.exportService.getExportStatus(ctx.tenantId, jobId);
  }

  @Get(':id/download')
  @ApiOperation({ summary: 'Download export file' })
  async downloadExport(
    @TenantContext() ctx: { tenantId: string },
    @Param('id') jobId: string,
    @Res() res: Response,
  ) {
    const job = await this.exportService.getExportStatus(ctx.tenantId, jobId);
    
    if (job.status !== ExportStatus.COMPLETED || !job.downloadUrl) {
      res.status(404).json({ error: 'Export not available' });
      return;
    }

    if (job.expiresAt && new Date() > job.expiresAt) {
      res.status(410).json({ error: 'Export expired' });
      return;
    }

    res.redirect(job.downloadUrl);
  }

  @Delete(':id')
  @ApiOperation({ summary: 'Cancel export job' })
  async cancelExport(
    @TenantContext() ctx: { tenantId: string; userId: string },
    @Param('id') jobId: string,
  ) {
    await this.exportService.cancelExport(ctx.tenantId, ctx.userId, jobId);
    return { success: true };
  }
}
```

## Database Schema

```sql
-- Export jobs
CREATE TABLE export_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    query_definition JSONB NOT NULL,
    format VARCHAR(20) NOT NULL,
    format_options JSONB,
    status VARCHAR(20) DEFAULT 'PENDING',
    delivery JSONB,
    requested_by UUID NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    processed_rows INTEGER DEFAULT 0,
    total_rows INTEGER,
    progress_percent DECIMAL(5, 2) DEFAULT 0,
    output_path VARCHAR(500),
    file_size_bytes BIGINT,
    file_count INTEGER,
    download_url VARCHAR(500),
    expires_at TIMESTAMPTZ,
    error_message TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_export_jobs_tenant ON export_jobs(tenant_id, requested_by);
CREATE INDEX idx_export_jobs_status ON export_jobs(tenant_id, status);
CREATE INDEX idx_export_jobs_date ON export_jobs(requested_at);

-- Cleanup expired exports
CREATE OR REPLACE FUNCTION cleanup_expired_exports()
RETURNS void AS $$
BEGIN
    UPDATE export_jobs 
    SET status = 'EXPIRED'
    WHERE status = 'COMPLETED' 
      AND expires_at < NOW();
END;
$$ LANGUAGE plpgsql;
```

## Definition of Done

- [ ] Export job creation and queuing
- [ ] Streaming CSV export working
- [ ] JSON/NDJSON export working
- [ ] Progress tracking implemented
- [ ] File chunking for large exports
- [ ] S3 upload and signed URLs
- [ ] Email delivery working
- [ ] Quota enforcement working
- [ ] Export cancellation working
- [ ] Unit tests for export logic
- [ ] Integration tests for full workflow

## Test Cases

### Unit Tests
```typescript
describe('ExportService', () => {
  it('should estimate row count correctly', async () => {
    const query = {
      source: 'fact_trades',
      columns: [{ name: 'id' }],
      filters: [],
      dateRange: { start: new Date('2026-01-01'), end: new Date('2026-01-31') },
    };

    const estimate = await service.estimateRowCount(tenantId, query);
    expect(estimate).toBeGreaterThan(0);
  });

  it('should enforce quota limits', async () => {
    // Exhaust quota
    await createCompletedExports(25);

    await expect(
      service.createExport(tenantId, userId, exportDto)
    ).rejects.toThrow('Daily export limit');
  });
});

describe('ExportQuotaService', () => {
  it('should calculate remaining quota', async () => {
    const usage = await quotaService.getUsageSummary(tenantId, userId);
    
    expect(usage.dailyRowsRemaining).toBeGreaterThanOrEqual(0);
    expect(usage.dailyExportsRemaining).toBeGreaterThanOrEqual(0);
  });
});
```
