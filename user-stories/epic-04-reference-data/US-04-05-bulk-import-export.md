# User Story: US-04-05 - Bulk Import/Export

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-04-05 |
| **Epic** | Epic 04 - Reference Data Management |
| **Title** | Bulk Import/Export |
| **Priority** | P1 - High |
| **Story Points** | 3 |

## User Story

**As a** operations manager  
**I want** to import and export reference data in bulk  
**So that** I can efficiently onboard new data and migrate between environments

## Description

Implement bulk import/export capabilities for instruments and counterparties using CSV and JSON formats. Support validation, error reporting, and dry-run mode.

## Acceptance Criteria

- [ ] Import instruments from CSV/JSON
- [ ] Export instruments to CSV/JSON
- [ ] Validation with detailed error report
- [ ] Dry-run mode (validate without saving)
- [ ] Progress tracking for large imports
- [ ] Async processing for large files

## Technical Details

### Import Service

```typescript
// services/reference-data-service/src/application/bulk-import.service.ts
import { Injectable } from '@nestjs/common';
import { InstrumentService } from './instrument.service';
import { CreateInstrumentDto } from './instrument.dto';
import { parse } from 'csv-parse/sync';
import { logger } from '@orion/observability';

export interface ImportResult {
  total: number;
  successful: number;
  failed: number;
  errors: Array<{ row: number; field?: string; message: string }>;
  created: string[];
}

@Injectable()
export class BulkImportService {
  constructor(private readonly instrumentService: InstrumentService) {}

  async importInstruments(
    data: string | Buffer,
    format: 'csv' | 'json',
    options: { dryRun?: boolean; skipDuplicates?: boolean } = {},
  ): Promise<ImportResult> {
    const records = format === 'csv' 
      ? this.parseCsv(data.toString())
      : JSON.parse(data.toString());

    const result: ImportResult = {
      total: records.length,
      successful: 0,
      failed: 0,
      errors: [],
      created: [],
    };

    for (let i = 0; i < records.length; i++) {
      const row = i + 1;
      const record = records[i];

      try {
        const dto = this.mapToInstrumentDto(record);
        
        if (!options.dryRun) {
          const instrument = await this.instrumentService.createInstrument(dto);
          result.created.push(instrument.id);
        }
        result.successful++;
      } catch (error) {
        if (options.skipDuplicates && (error as Error).message.includes('already exists')) {
          result.successful++;
          continue;
        }

        result.failed++;
        result.errors.push({
          row,
          message: (error as Error).message,
        });
      }
    }

    logger.info('Bulk import completed', {
      total: result.total,
      successful: result.successful,
      failed: result.failed,
    });

    return result;
  }

  async exportInstruments(
    format: 'csv' | 'json',
    filters?: { assetClass?: string; status?: string },
  ): Promise<string> {
    const { items } = await this.instrumentService.listInstruments({
      ...filters,
      limit: 100000,
    });

    if (format === 'json') {
      return JSON.stringify(items, null, 2);
    }

    return this.toCsv(items);
  }

  private parseCsv(data: string): Record<string, string>[] {
    return parse(data, {
      columns: true,
      skip_empty_lines: true,
      trim: true,
    });
  }

  private mapToInstrumentDto(record: Record<string, string>): CreateInstrumentDto {
    return {
      symbol: record.symbol,
      name: record.name,
      assetClass: record.asset_class as any,
      baseCurrency: record.base_currency,
      quoteCurrency: record.quote_currency,
      tickSize: parseFloat(record.tick_size),
      pricePrecision: parseInt(record.price_precision || '2'),
      quantityPrecision: parseInt(record.quantity_precision || '2'),
      isin: record.isin || undefined,
      exchange: record.exchange || undefined,
      tags: record.tags ? record.tags.split(',') : [],
    };
  }

  private toCsv(items: any[]): string {
    if (items.length === 0) return '';

    const headers = ['symbol', 'name', 'asset_class', 'base_currency', 'quote_currency', 
                     'tick_size', 'price_precision', 'status', 'isin', 'exchange', 'tags'];
    
    const rows = items.map(item => headers.map(h => {
      const key = h.replace(/_([a-z])/g, (_, c) => c.toUpperCase());
      const value = item[key];
      if (Array.isArray(value)) return value.join(',');
      return value ?? '';
    }).join(','));

    return [headers.join(','), ...rows].join('\n');
  }
}
```

### API Endpoints

```typescript
// services/reference-data-service/src/api/bulk.controller.ts
import { Controller, Post, Get, Body, Query, UseInterceptors, UploadedFile, Res } from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import { ApiTags, ApiOperation, ApiConsumes } from '@nestjs/swagger';
import { BulkImportService, ImportResult } from '../application/bulk-import.service';
import { Response } from 'express';

@ApiTags('Bulk Operations')
@Controller('bulk')
export class BulkController {
  constructor(private readonly bulkService: BulkImportService) {}

  @Post('instruments/import')
  @ApiOperation({ summary: 'Import instruments from file' })
  @ApiConsumes('multipart/form-data')
  @UseInterceptors(FileInterceptor('file'))
  async importInstruments(
    @UploadedFile() file: Express.Multer.File,
    @Query('format') format: 'csv' | 'json' = 'csv',
    @Query('dryRun') dryRun?: boolean,
    @Query('skipDuplicates') skipDuplicates?: boolean,
  ): Promise<ImportResult> {
    return this.bulkService.importInstruments(
      file.buffer,
      format,
      { dryRun, skipDuplicates },
    );
  }

  @Get('instruments/export')
  @ApiOperation({ summary: 'Export instruments to file' })
  async exportInstruments(
    @Query('format') format: 'csv' | 'json' = 'csv',
    @Query('assetClass') assetClass?: string,
    @Query('status') status?: string,
    @Res() res: Response,
  ): Promise<void> {
    const data = await this.bulkService.exportInstruments(format, { assetClass, status });
    
    const contentType = format === 'json' ? 'application/json' : 'text/csv';
    const extension = format === 'json' ? 'json' : 'csv';
    
    res.setHeader('Content-Type', contentType);
    res.setHeader('Content-Disposition', `attachment; filename=instruments.${extension}`);
    res.send(data);
  }

  @Get('instruments/template')
  @ApiOperation({ summary: 'Download import template' })
  async downloadTemplate(@Res() res: Response): Promise<void> {
    const template = `symbol,name,asset_class,base_currency,quote_currency,tick_size,price_precision,quantity_precision,isin,exchange,tags
EUR/USD,Euro/US Dollar,fx,EUR,USD,0.00001,5,2,,,
BTCUSD,Bitcoin/USD,crypto,BTC,USD,0.01,2,8,,,
AAPL,Apple Inc.,equity,,,0.01,2,0,US0378331005,NASDAQ,tech`;

    res.setHeader('Content-Type', 'text/csv');
    res.setHeader('Content-Disposition', 'attachment; filename=instrument_template.csv');
    res.send(template);
  }
}
```

## Definition of Done

- [ ] CSV import working
- [ ] JSON import working
- [ ] Export in both formats
- [ ] Validation errors reported
- [ ] Dry-run mode works
- [ ] Template downloadable
- [ ] Tests cover edge cases

## Dependencies

- **US-04-01**: Instrument Management

## Test Cases

```typescript
describe('BulkImportService', () => {
  it('should import valid CSV', async () => {
    const csv = `symbol,name,asset_class,tick_size
BTCUSD,Bitcoin,crypto,0.01
ETHUSD,Ethereum,crypto,0.01`;

    const result = await service.importInstruments(csv, 'csv');
    
    expect(result.successful).toBe(2);
    expect(result.failed).toBe(0);
  });

  it('should report row-level errors', async () => {
    const csv = `symbol,name,asset_class,tick_size
BTCUSD,Bitcoin,crypto,0.01
INVALID,,crypto,`;

    const result = await service.importInstruments(csv, 'csv');
    
    expect(result.successful).toBe(1);
    expect(result.failed).toBe(1);
    expect(result.errors[0].row).toBe(2);
  });

  it('should not create records in dry-run mode', async () => {
    const csv = `symbol,name,asset_class,tick_size
TEST,Test,crypto,0.01`;

    await service.importInstruments(csv, 'csv', { dryRun: true });
    
    const exists = await instrumentRepo.findBySymbol('TEST');
    expect(exists).toBeNull();
  });
});
```
