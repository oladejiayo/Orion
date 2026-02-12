# User Story: US-07-08 - RFQ Analytics and Reporting

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-07-08 |
| **Epic** | Epic 07 - RFQ Workflow |
| **Title** | RFQ Analytics and Reporting |
| **Priority** | P2 - Medium |
| **Story Points** | 5 |
| **PRD Reference** | FR-RFQ-08, FR-Analytics-01 |

## User Story

**As a** trading desk manager  
**I want** analytics and reports on RFQ activities  
**So that** I can optimize LP relationships, measure execution quality, and improve trading performance

## Description

Implement RFQ analytics including fill rates, response times, LP performance metrics, price improvement analysis, and configurable reporting.

## Acceptance Criteria

- [ ] RFQ volume and fill rate metrics
- [ ] LP performance scorecards
- [ ] Quote response time analysis
- [ ] Price improvement/slippage tracking
- [ ] Customizable date range reports
- [ ] Dashboard API for visualizations
- [ ] Export to PDF/Excel

## Technical Details

### Analytics Materialized Views

```sql
-- migrations/20240123_create_rfq_analytics.sql

-- Daily RFQ summary
CREATE MATERIALIZED VIEW rfq_daily_summary AS
SELECT 
    tenant_id,
    DATE(created_at) AS trade_date,
    asset_class,
    symbol,
    COUNT(*) AS total_rfqs,
    COUNT(*) FILTER (WHERE status = 'filled') AS filled_rfqs,
    COUNT(*) FILTER (WHERE status = 'expired') AS expired_rfqs,
    COUNT(*) FILTER (WHERE status = 'cancelled') AS cancelled_rfqs,
    ROUND(100.0 * COUNT(*) FILTER (WHERE status = 'filled') / NULLIF(COUNT(*), 0), 2) AS fill_rate,
    AVG(EXTRACT(EPOCH FROM (updated_at - created_at))) FILTER (WHERE status = 'filled') AS avg_fill_time_seconds,
    SUM(quantity) AS total_volume,
    SUM(quantity) FILTER (WHERE status = 'filled') AS filled_volume
FROM rfqs
GROUP BY tenant_id, DATE(created_at), asset_class, symbol;

CREATE UNIQUE INDEX idx_rfq_daily_summary 
ON rfq_daily_summary(tenant_id, trade_date, asset_class, symbol);

-- LP performance metrics
CREATE MATERIALIZED VIEW lp_performance_metrics AS
SELECT 
    d.tenant_id,
    d.lp_id,
    lp.name AS lp_name,
    DATE(d.created_at) AS trade_date,
    COUNT(*) AS rfqs_received,
    COUNT(*) FILTER (WHERE d.delivery_status = 'delivered') AS rfqs_delivered,
    COUNT(q.id) AS quotes_provided,
    COUNT(q.id) FILTER (WHERE q.status = 'executed') AS quotes_won,
    ROUND(100.0 * COUNT(q.id) / NULLIF(COUNT(*) FILTER (WHERE d.delivery_status = 'delivered'), 0), 2) AS quote_rate,
    ROUND(100.0 * COUNT(q.id) FILTER (WHERE q.status = 'executed') / NULLIF(COUNT(q.id), 0), 2) AS win_rate,
    AVG(EXTRACT(EPOCH FROM (q.received_at - d.sent_at)) * 1000) AS avg_response_time_ms,
    AVG(q.spread) AS avg_spread
FROM rfq_distributions d
JOIN liquidity_providers lp ON d.lp_id = lp.id
LEFT JOIN quotes q ON d.rfq_id = q.rfq_id AND d.lp_id = q.lp_id
GROUP BY d.tenant_id, d.lp_id, lp.name, DATE(d.created_at);

CREATE UNIQUE INDEX idx_lp_performance 
ON lp_performance_metrics(tenant_id, lp_id, trade_date);

-- Refresh views
CREATE OR REPLACE FUNCTION refresh_rfq_analytics()
RETURNS void AS $$
BEGIN
    REFRESH MATERIALIZED VIEW CONCURRENTLY rfq_daily_summary;
    REFRESH MATERIALIZED VIEW CONCURRENTLY lp_performance_metrics;
END;
$$ LANGUAGE plpgsql;

-- Schedule refresh every 5 minutes
-- (Configured via external scheduler like pg_cron)
```

### Analytics DTOs

```typescript
// services/rfq-service/src/analytics/dto/analytics.dto.ts

export class DateRangeDto {
  startDate: string;
  endDate: string;
}

export class RfqSummaryDto {
  tradeDate: string;
  assetClass: string;
  symbol: string;
  totalRfqs: number;
  filledRfqs: number;
  expiredRfqs: number;
  cancelledRfqs: number;
  fillRate: number;
  avgFillTimeSeconds: number;
  totalVolume: number;
  filledVolume: number;
}

export class LpPerformanceDto {
  lpId: string;
  lpName: string;
  rfqsReceived: number;
  rfqsDelivered: number;
  quotesProvided: number;
  quotesWon: number;
  quoteRate: number;
  winRate: number;
  avgResponseTimeMs: number;
  avgSpread: number;
}

export class PriceAnalysisDto {
  rfqId: string;
  symbol: string;
  side: string;
  referencePrice: number;
  executedPrice: number;
  priceImprovement: number;
  priceImprovementBps: number;
  bestQuotePrice: number;
  worstQuotePrice: number;
  quoteSpread: number;
}

export class DashboardMetricsDto {
  period: {
    start: Date;
    end: Date;
  };
  summary: {
    totalRfqs: number;
    filledRfqs: number;
    fillRate: number;
    avgFillTime: number;
    totalVolume: number;
  };
  byAssetClass: {
    assetClass: string;
    rfqCount: number;
    fillRate: number;
  }[];
  topLPs: LpPerformanceDto[];
  recentActivity: {
    time: string;
    rfqCount: number;
    fillRate: number;
  }[];
}
```

### Analytics Service

```typescript
// services/rfq-service/src/analytics/analytics.service.ts
import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, Between } from 'typeorm';
import { InjectDataSource } from '@nestjs/typeorm';
import { DataSource } from 'typeorm';
import { getCurrentTenant } from '@orion/security';
import { 
  RfqSummaryDto, 
  LpPerformanceDto, 
  PriceAnalysisDto,
  DashboardMetricsDto,
  DateRangeDto,
} from './dto/analytics.dto';
import { RfqEntity } from '../entities/rfq.entity';
import { QuoteEntity } from '../entities/quote.entity';

@Injectable()
export class AnalyticsService {
  constructor(
    @InjectDataSource()
    private readonly dataSource: DataSource,
    @InjectRepository(RfqEntity)
    private readonly rfqRepository: Repository<RfqEntity>,
    @InjectRepository(QuoteEntity)
    private readonly quoteRepository: Repository<QuoteEntity>,
  ) {}

  async getDailySummary(dateRange: DateRangeDto): Promise<RfqSummaryDto[]> {
    const tenant = getCurrentTenant();
    
    const result = await this.dataSource.query(`
      SELECT 
        trade_date::text,
        asset_class,
        symbol,
        total_rfqs,
        filled_rfqs,
        expired_rfqs,
        cancelled_rfqs,
        fill_rate,
        avg_fill_time_seconds,
        total_volume,
        filled_volume
      FROM rfq_daily_summary
      WHERE tenant_id = $1
        AND trade_date BETWEEN $2 AND $3
      ORDER BY trade_date DESC, total_rfqs DESC
    `, [tenant.tenantId, dateRange.startDate, dateRange.endDate]);

    return result.map(row => ({
      tradeDate: row.trade_date,
      assetClass: row.asset_class,
      symbol: row.symbol,
      totalRfqs: parseInt(row.total_rfqs),
      filledRfqs: parseInt(row.filled_rfqs),
      expiredRfqs: parseInt(row.expired_rfqs),
      cancelledRfqs: parseInt(row.cancelled_rfqs),
      fillRate: parseFloat(row.fill_rate),
      avgFillTimeSeconds: parseFloat(row.avg_fill_time_seconds),
      totalVolume: parseFloat(row.total_volume),
      filledVolume: parseFloat(row.filled_volume),
    }));
  }

  async getLpPerformance(dateRange: DateRangeDto): Promise<LpPerformanceDto[]> {
    const tenant = getCurrentTenant();
    
    const result = await this.dataSource.query(`
      SELECT 
        lp_id,
        lp_name,
        SUM(rfqs_received) AS rfqs_received,
        SUM(rfqs_delivered) AS rfqs_delivered,
        SUM(quotes_provided) AS quotes_provided,
        SUM(quotes_won) AS quotes_won,
        ROUND(100.0 * SUM(quotes_provided) / NULLIF(SUM(rfqs_delivered), 0), 2) AS quote_rate,
        ROUND(100.0 * SUM(quotes_won) / NULLIF(SUM(quotes_provided), 0), 2) AS win_rate,
        AVG(avg_response_time_ms) AS avg_response_time_ms,
        AVG(avg_spread) AS avg_spread
      FROM lp_performance_metrics
      WHERE tenant_id = $1
        AND trade_date BETWEEN $2 AND $3
      GROUP BY lp_id, lp_name
      ORDER BY quotes_won DESC
    `, [tenant.tenantId, dateRange.startDate, dateRange.endDate]);

    return result.map(row => ({
      lpId: row.lp_id,
      lpName: row.lp_name,
      rfqsReceived: parseInt(row.rfqs_received),
      rfqsDelivered: parseInt(row.rfqs_delivered),
      quotesProvided: parseInt(row.quotes_provided),
      quotesWon: parseInt(row.quotes_won),
      quoteRate: parseFloat(row.quote_rate) || 0,
      winRate: parseFloat(row.win_rate) || 0,
      avgResponseTimeMs: parseFloat(row.avg_response_time_ms),
      avgSpread: parseFloat(row.avg_spread),
    }));
  }

  async getPriceAnalysis(dateRange: DateRangeDto): Promise<PriceAnalysisDto[]> {
    const tenant = getCurrentTenant();
    
    const result = await this.dataSource.query(`
      SELECT 
        r.id AS rfq_id,
        r.symbol,
        r.side,
        r.reference_price,
        CASE r.side 
          WHEN 'buy' THEN q.ask_price 
          ELSE q.bid_price 
        END AS executed_price,
        CASE r.side
          WHEN 'buy' THEN r.reference_price - q.ask_price
          ELSE q.bid_price - r.reference_price
        END AS price_improvement,
        CASE r.side
          WHEN 'buy' THEN ROUND(10000 * (r.reference_price - q.ask_price) / r.reference_price, 2)
          ELSE ROUND(10000 * (q.bid_price - r.reference_price) / r.reference_price, 2)
        END AS price_improvement_bps,
        best_quotes.best_price AS best_quote_price,
        worst_quotes.worst_price AS worst_quote_price,
        best_quotes.best_price - worst_quotes.worst_price AS quote_spread
      FROM rfqs r
      JOIN quotes q ON r.selected_quote_id = q.id
      LEFT JOIN LATERAL (
        SELECT CASE r.side WHEN 'buy' THEN MIN(ask_price) ELSE MAX(bid_price) END AS best_price
        FROM quotes WHERE rfq_id = r.id AND status IN ('valid', 'executed')
      ) best_quotes ON true
      LEFT JOIN LATERAL (
        SELECT CASE r.side WHEN 'buy' THEN MAX(ask_price) ELSE MIN(bid_price) END AS worst_price
        FROM quotes WHERE rfq_id = r.id AND status IN ('valid', 'executed')
      ) worst_quotes ON true
      WHERE r.tenant_id = $1
        AND r.status = 'filled'
        AND r.created_at BETWEEN $2 AND $3
        AND r.reference_price IS NOT NULL
      ORDER BY r.created_at DESC
      LIMIT 1000
    `, [tenant.tenantId, dateRange.startDate, dateRange.endDate]);

    return result.map(row => ({
      rfqId: row.rfq_id,
      symbol: row.symbol,
      side: row.side,
      referencePrice: parseFloat(row.reference_price),
      executedPrice: parseFloat(row.executed_price),
      priceImprovement: parseFloat(row.price_improvement),
      priceImprovementBps: parseFloat(row.price_improvement_bps),
      bestQuotePrice: parseFloat(row.best_quote_price),
      worstQuotePrice: parseFloat(row.worst_quote_price),
      quoteSpread: parseFloat(row.quote_spread),
    }));
  }

  async getDashboardMetrics(): Promise<DashboardMetricsDto> {
    const tenant = getCurrentTenant();
    const now = new Date();
    const startOfDay = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    const startOfWeek = new Date(startOfDay);
    startOfWeek.setDate(startOfWeek.getDate() - 7);

    // Summary metrics
    const summary = await this.dataSource.query(`
      SELECT 
        COUNT(*) AS total_rfqs,
        COUNT(*) FILTER (WHERE status = 'filled') AS filled_rfqs,
        ROUND(100.0 * COUNT(*) FILTER (WHERE status = 'filled') / NULLIF(COUNT(*), 0), 2) AS fill_rate,
        AVG(EXTRACT(EPOCH FROM (updated_at - created_at))) FILTER (WHERE status = 'filled') AS avg_fill_time,
        SUM(quantity) AS total_volume
      FROM rfqs
      WHERE tenant_id = $1
        AND created_at >= $2
    `, [tenant.tenantId, startOfWeek]);

    // By asset class
    const byAssetClass = await this.dataSource.query(`
      SELECT 
        asset_class,
        COUNT(*) AS rfq_count,
        ROUND(100.0 * COUNT(*) FILTER (WHERE status = 'filled') / NULLIF(COUNT(*), 0), 2) AS fill_rate
      FROM rfqs
      WHERE tenant_id = $1
        AND created_at >= $2
      GROUP BY asset_class
      ORDER BY rfq_count DESC
    `, [tenant.tenantId, startOfWeek]);

    // Top LPs
    const topLPs = await this.getLpPerformance({
      startDate: startOfWeek.toISOString().split('T')[0],
      endDate: now.toISOString().split('T')[0],
    });

    // Hourly activity
    const recentActivity = await this.dataSource.query(`
      SELECT 
        DATE_TRUNC('hour', created_at) AS time,
        COUNT(*) AS rfq_count,
        ROUND(100.0 * COUNT(*) FILTER (WHERE status = 'filled') / NULLIF(COUNT(*), 0), 2) AS fill_rate
      FROM rfqs
      WHERE tenant_id = $1
        AND created_at >= $2
      GROUP BY DATE_TRUNC('hour', created_at)
      ORDER BY time DESC
      LIMIT 24
    `, [tenant.tenantId, startOfDay]);

    return {
      period: {
        start: startOfWeek,
        end: now,
      },
      summary: {
        totalRfqs: parseInt(summary[0]?.total_rfqs) || 0,
        filledRfqs: parseInt(summary[0]?.filled_rfqs) || 0,
        fillRate: parseFloat(summary[0]?.fill_rate) || 0,
        avgFillTime: parseFloat(summary[0]?.avg_fill_time) || 0,
        totalVolume: parseFloat(summary[0]?.total_volume) || 0,
      },
      byAssetClass: byAssetClass.map(row => ({
        assetClass: row.asset_class,
        rfqCount: parseInt(row.rfq_count),
        fillRate: parseFloat(row.fill_rate) || 0,
      })),
      topLPs: topLPs.slice(0, 5),
      recentActivity: recentActivity.map(row => ({
        time: row.time,
        rfqCount: parseInt(row.rfq_count),
        fillRate: parseFloat(row.fill_rate) || 0,
      })),
    };
  }

  async refreshAnalytics(): Promise<void> {
    await this.dataSource.query('SELECT refresh_rfq_analytics()');
  }
}
```

### Report Generation Service

```typescript
// services/rfq-service/src/analytics/report-generation.service.ts
import { Injectable } from '@nestjs/common';
import { AnalyticsService } from './analytics.service';
import { DateRangeDto } from './dto/analytics.dto';
import * as ExcelJS from 'exceljs';

export interface ReportConfig {
  dateRange: DateRangeDto;
  sections: ('summary' | 'lpPerformance' | 'priceAnalysis')[];
  format: 'xlsx' | 'pdf' | 'csv';
}

@Injectable()
export class ReportGenerationService {
  constructor(private readonly analyticsService: AnalyticsService) {}

  async generateReport(config: ReportConfig): Promise<Buffer> {
    switch (config.format) {
      case 'xlsx':
        return this.generateExcelReport(config);
      case 'csv':
        return this.generateCsvReport(config);
      case 'pdf':
        return this.generatePdfReport(config);
      default:
        throw new Error(`Unsupported format: ${config.format}`);
    }
  }

  private async generateExcelReport(config: ReportConfig): Promise<Buffer> {
    const workbook = new ExcelJS.Workbook();
    workbook.creator = 'Orion Platform';
    workbook.created = new Date();

    if (config.sections.includes('summary')) {
      const summary = await this.analyticsService.getDailySummary(config.dateRange);
      const sheet = workbook.addWorksheet('Daily Summary');
      
      sheet.columns = [
        { header: 'Date', key: 'tradeDate', width: 12 },
        { header: 'Asset Class', key: 'assetClass', width: 12 },
        { header: 'Symbol', key: 'symbol', width: 10 },
        { header: 'Total RFQs', key: 'totalRfqs', width: 10 },
        { header: 'Filled', key: 'filledRfqs', width: 10 },
        { header: 'Fill Rate %', key: 'fillRate', width: 12 },
        { header: 'Avg Fill Time (s)', key: 'avgFillTimeSeconds', width: 15 },
        { header: 'Total Volume', key: 'totalVolume', width: 15 },
      ];

      sheet.addRows(summary);
      this.styleWorksheet(sheet);
    }

    if (config.sections.includes('lpPerformance')) {
      const lpData = await this.analyticsService.getLpPerformance(config.dateRange);
      const sheet = workbook.addWorksheet('LP Performance');
      
      sheet.columns = [
        { header: 'LP Name', key: 'lpName', width: 20 },
        { header: 'RFQs Received', key: 'rfqsReceived', width: 15 },
        { header: 'Quotes Provided', key: 'quotesProvided', width: 15 },
        { header: 'Quotes Won', key: 'quotesWon', width: 12 },
        { header: 'Quote Rate %', key: 'quoteRate', width: 12 },
        { header: 'Win Rate %', key: 'winRate', width: 12 },
        { header: 'Avg Response (ms)', key: 'avgResponseTimeMs', width: 15 },
        { header: 'Avg Spread', key: 'avgSpread', width: 12 },
      ];

      sheet.addRows(lpData);
      this.styleWorksheet(sheet);
    }

    if (config.sections.includes('priceAnalysis')) {
      const priceData = await this.analyticsService.getPriceAnalysis(config.dateRange);
      const sheet = workbook.addWorksheet('Price Analysis');
      
      sheet.columns = [
        { header: 'RFQ ID', key: 'rfqId', width: 36 },
        { header: 'Symbol', key: 'symbol', width: 10 },
        { header: 'Side', key: 'side', width: 8 },
        { header: 'Reference Price', key: 'referencePrice', width: 15 },
        { header: 'Executed Price', key: 'executedPrice', width: 15 },
        { header: 'Improvement (bps)', key: 'priceImprovementBps', width: 15 },
        { header: 'Best Quote', key: 'bestQuotePrice', width: 12 },
        { header: 'Quote Spread', key: 'quoteSpread', width: 12 },
      ];

      sheet.addRows(priceData);
      this.styleWorksheet(sheet);
    }

    return workbook.xlsx.writeBuffer() as Promise<Buffer>;
  }

  private styleWorksheet(sheet: ExcelJS.Worksheet): void {
    // Style header row
    sheet.getRow(1).font = { bold: true };
    sheet.getRow(1).fill = {
      type: 'pattern',
      pattern: 'solid',
      fgColor: { argb: 'FF4472C4' },
    };
    sheet.getRow(1).font = { color: { argb: 'FFFFFFFF' }, bold: true };

    // Add borders
    sheet.eachRow((row, rowNum) => {
      row.eachCell((cell) => {
        cell.border = {
          top: { style: 'thin' },
          left: { style: 'thin' },
          bottom: { style: 'thin' },
          right: { style: 'thin' },
        };
      });
    });

    // Freeze header row
    sheet.views = [{ state: 'frozen', ySplit: 1 }];
  }

  private async generateCsvReport(config: ReportConfig): Promise<Buffer> {
    const sections: string[] = [];

    if (config.sections.includes('summary')) {
      const summary = await this.analyticsService.getDailySummary(config.dateRange);
      const headers = Object.keys(summary[0] || {}).join(',');
      const rows = summary.map(row => Object.values(row).join(','));
      sections.push(`Daily Summary\n${headers}\n${rows.join('\n')}`);
    }

    if (config.sections.includes('lpPerformance')) {
      const lpData = await this.analyticsService.getLpPerformance(config.dateRange);
      const headers = Object.keys(lpData[0] || {}).join(',');
      const rows = lpData.map(row => Object.values(row).join(','));
      sections.push(`LP Performance\n${headers}\n${rows.join('\n')}`);
    }

    return Buffer.from(sections.join('\n\n'));
  }

  private async generatePdfReport(config: ReportConfig): Promise<Buffer> {
    // Would use a PDF library like pdfkit or puppeteer
    throw new Error('PDF generation not implemented');
  }
}
```

### Analytics Controller

```typescript
// services/rfq-service/src/analytics/analytics.controller.ts
import { Controller, Get, Post, Query, Body, Res, UseGuards } from '@nestjs/common';
import { Response } from 'express';
import { JwtAuthGuard, RequirePermissions, Permission } from '@orion/security';
import { AnalyticsService } from './analytics.service';
import { ReportGenerationService, ReportConfig } from './report-generation.service';
import { DateRangeDto } from './dto/analytics.dto';

@Controller('analytics/rfq')
@UseGuards(JwtAuthGuard)
export class AnalyticsController {
  constructor(
    private readonly analyticsService: AnalyticsService,
    private readonly reportService: ReportGenerationService,
  ) {}

  @Get('dashboard')
  @RequirePermissions(Permission.ANALYTICS_VIEW)
  async getDashboard() {
    return this.analyticsService.getDashboardMetrics();
  }

  @Get('daily-summary')
  @RequirePermissions(Permission.ANALYTICS_VIEW)
  async getDailySummary(@Query() dateRange: DateRangeDto) {
    return this.analyticsService.getDailySummary(dateRange);
  }

  @Get('lp-performance')
  @RequirePermissions(Permission.ANALYTICS_VIEW)
  async getLpPerformance(@Query() dateRange: DateRangeDto) {
    return this.analyticsService.getLpPerformance(dateRange);
  }

  @Get('price-analysis')
  @RequirePermissions(Permission.ANALYTICS_VIEW)
  async getPriceAnalysis(@Query() dateRange: DateRangeDto) {
    return this.analyticsService.getPriceAnalysis(dateRange);
  }

  @Post('report')
  @RequirePermissions(Permission.ANALYTICS_EXPORT)
  async generateReport(
    @Body() config: ReportConfig,
    @Res() res: Response,
  ) {
    const buffer = await this.reportService.generateReport(config);
    
    const contentType = config.format === 'xlsx' 
      ? 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
      : config.format === 'pdf'
        ? 'application/pdf'
        : 'text/csv';
    
    res.setHeader('Content-Type', contentType);
    res.setHeader('Content-Disposition', `attachment; filename=rfq-report.${config.format}`);
    res.send(buffer);
  }

  @Post('refresh')
  @RequirePermissions(Permission.ANALYTICS_ADMIN)
  async refreshAnalytics() {
    await this.analyticsService.refreshAnalytics();
    return { success: true };
  }
}
```

## Definition of Done

- [ ] Materialized views for analytics
- [ ] Daily summary API
- [ ] LP performance metrics
- [ ] Price analysis calculations
- [ ] Dashboard endpoint
- [ ] Excel/CSV report generation
- [ ] Automatic view refresh

## Dependencies

- **US-07-01** through **US-07-07**: All RFQ data
- **US-12-01**: Analytics Infrastructure (placeholder)

## Test Cases

```typescript
describe('AnalyticsService', () => {
  it('should calculate fill rate correctly', async () => {
    await createRfqs([
      { status: 'filled' },
      { status: 'filled' },
      { status: 'expired' },
    ]);
    
    await analyticsService.refreshAnalytics();
    
    const summary = await analyticsService.getDailySummary({
      startDate: '2024-01-01',
      endDate: '2024-01-31',
    });

    expect(summary[0].fillRate).toBeCloseTo(66.67, 1);
  });

  it('should calculate LP win rate', async () => {
    const lp1 = await createLP('LP1');
    await createFilledRfqWithQuote(lp1.id, 3);  // 3 wins
    await createQuote(lp1.id, { status: 'valid' }); // 1 loss

    await analyticsService.refreshAnalytics();
    
    const performance = await analyticsService.getLpPerformance({
      startDate: '2024-01-01',
      endDate: '2024-01-31',
    });

    const lp1Stats = performance.find(p => p.lpId === lp1.id);
    expect(lp1Stats?.winRate).toBe(75);
  });

  it('should generate Excel report', async () => {
    const buffer = await reportService.generateReport({
      dateRange: { startDate: '2024-01-01', endDate: '2024-01-31' },
      sections: ['summary', 'lpPerformance'],
      format: 'xlsx',
    });

    expect(buffer).toBeInstanceOf(Buffer);
    expect(buffer.length).toBeGreaterThan(0);
  });
});
```
