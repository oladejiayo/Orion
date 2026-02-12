# Epic 12: Analytics & Data Products

## Overview

This epic implements comprehensive analytics and data products capabilities for the Orion platform, enabling real-time and historical analysis of trading activity, market data, risk metrics, and operational performance. The system provides self-service analytics, scheduled reporting, and data export capabilities for both internal users and clients.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           Analytics & Data Products                              │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐        │
│  │   Analytics  │  │    Report    │  │   Dashboard  │  │    Data      │        │
│  │   BFF        │  │   Builder    │  │   Service    │  │   Export     │        │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘        │
│         │                 │                 │                 │                 │
│  ┌──────┴─────────────────┴─────────────────┴─────────────────┴───────┐        │
│  │                     Analytics Query Engine                         │        │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                │        │
│  │  │   OLAP      │  │   Query     │  │   Cache     │                │        │
│  │  │   Cubes     │  │   Optimizer │  │   Manager   │                │        │
│  │  └─────────────┘  └─────────────┘  └─────────────┘                │        │
│  └────────────────────────────────────────────────────────────────────┘        │
│                                      │                                          │
│  ┌───────────────────────────────────┴───────────────────────────────┐         │
│  │                      Data Warehouse Layer                          │         │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐               │         │
│  │  │   Fact      │  │  Dimension  │  │  Aggregate  │               │         │
│  │  │   Tables    │  │   Tables    │  │   Tables    │               │         │
│  │  └─────────────┘  └─────────────┘  └─────────────┘               │         │
│  └───────────────────────────────────────────────────────────────────┘         │
│                                      │                                          │
│  ┌───────────────────────────────────┴───────────────────────────────┐         │
│  │                        ETL Pipeline                                │         │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐               │         │
│  │  │   CDC       │  │   Transform │  │   Load      │               │         │
│  │  │   Capture   │  │   Engine    │  │   Manager   │               │         │
│  │  └─────────────┘  └─────────────┘  └─────────────┘               │         │
│  └───────────────────────────────────────────────────────────────────┘         │
│                                                                                  │
│  ┌────────────────────────────────────────────────────────────────────┐        │
│  │                    Real-Time Analytics Stream                       │        │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                │        │
│  │  │   Kafka     │  │   Stream    │  │  WebSocket  │                │        │
│  │  │   Consumer  │  │   Processor │  │   Publisher │                │        │
│  │  └─────────────┘  └─────────────┘  └─────────────┘                │        │
│  └────────────────────────────────────────────────────────────────────┘        │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

## Data Model

### Core Analytics Entities

```typescript
// Fact Tables
interface FactTrade {
  id: string;
  tradeDate: Date;
  settlementDate: Date;
  tenantId: string;
  clientId: string;
  instrumentId: string;
  lpId: string;
  side: 'BUY' | 'SELL';
  quantity: number;
  price: number;
  notionalValue: number;
  fees: number;
  netAmount: number;
  currency: string;
  executionVenue: string;
  orderType: string;
  fillLatencyMs: number;
  createdAt: Date;
}

interface FactQuote {
  id: string;
  quoteDate: Date;
  tenantId: string;
  instrumentId: string;
  lpId: string;
  bidPrice: number;
  askPrice: number;
  bidSize: number;
  askSize: number;
  spread: number;
  spreadBps: number;
  isStale: boolean;
  latencyMs: number;
  createdAt: Date;
}

interface FactRiskSnapshot {
  id: string;
  snapshotDate: Date;
  tenantId: string;
  clientId: string;
  exposure: number;
  utilizationPct: number;
  varValue: number;
  var99Value: number;
  riskScore: number;
  breachCount: number;
  createdAt: Date;
}

// Dimension Tables
interface DimClient {
  id: string;
  clientCode: string;
  clientName: string;
  clientType: string;
  region: string;
  segment: string;
  onboardDate: Date;
  status: string;
  validFrom: Date;
  validTo: Date | null;
  isCurrent: boolean;
}

interface DimInstrument {
  id: string;
  symbol: string;
  name: string;
  assetClass: string;
  instrumentType: string;
  baseCurrency: string;
  quoteCurrency: string;
  exchange: string;
  validFrom: Date;
  validTo: Date | null;
  isCurrent: boolean;
}

interface DimTime {
  dateKey: number; // YYYYMMDD
  date: Date;
  year: number;
  quarter: number;
  month: number;
  week: number;
  dayOfWeek: number;
  dayOfMonth: number;
  dayOfYear: number;
  isWeekend: boolean;
  isHoliday: boolean;
  fiscalYear: number;
  fiscalQuarter: number;
}

// Aggregate Tables
interface AggDailyTradeSummary {
  id: string;
  tradeDate: Date;
  tenantId: string;
  clientId: string | null;
  instrumentId: string | null;
  lpId: string | null;
  tradeCount: number;
  buyCount: number;
  sellCount: number;
  totalVolume: number;
  totalNotional: number;
  totalFees: number;
  avgPrice: number;
  vwapPrice: number;
  avgLatencyMs: number;
  createdAt: Date;
  updatedAt: Date;
}

interface AggClientMetrics {
  id: string;
  metricDate: Date;
  tenantId: string;
  clientId: string;
  tradingDays: number;
  totalTrades: number;
  totalVolume: number;
  totalNotional: number;
  avgTradeSize: number;
  profitability: number;
  riskScore: number;
  retentionScore: number;
  createdAt: Date;
}

// Report Definitions
interface ReportDefinition {
  id: string;
  tenantId: string;
  name: string;
  description: string;
  reportType: 'TRADE_ACTIVITY' | 'RISK_SUMMARY' | 'CLIENT_ANALYTICS' | 'MARKET_DATA' | 'P&L' | 'CUSTOM';
  queryDefinition: ReportQuery;
  outputFormat: 'PDF' | 'EXCEL' | 'CSV' | 'JSON';
  schedule: ReportSchedule | null;
  parameters: ReportParameter[];
  recipients: string[];
  createdBy: string;
  createdAt: Date;
  updatedAt: Date;
}

interface ReportQuery {
  dimensions: string[];
  measures: string[];
  filters: QueryFilter[];
  sorting: QuerySort[];
  limit: number | null;
}

interface ReportSchedule {
  frequency: 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'QUARTERLY';
  dayOfWeek?: number; // 0-6
  dayOfMonth?: number; // 1-31
  time: string; // HH:mm
  timezone: string;
  enabled: boolean;
}

// Dashboard Configuration
interface DashboardDefinition {
  id: string;
  tenantId: string;
  name: string;
  description: string;
  isDefault: boolean;
  layout: DashboardLayout;
  widgets: DashboardWidget[];
  refreshInterval: number; // seconds
  createdBy: string;
  createdAt: Date;
  updatedAt: Date;
}

interface DashboardWidget {
  id: string;
  type: 'CHART' | 'TABLE' | 'KPI' | 'MAP' | 'GAUGE';
  title: string;
  position: { x: number; y: number; w: number; h: number };
  query: ReportQuery;
  visualization: VisualizationConfig;
  refreshInterval?: number;
}
```

## User Stories

| Story ID | Title | Priority | Points |
|----------|-------|----------|--------|
| US-12-01 | Data Warehouse Schema | High | 13 |
| US-12-02 | ETL Pipeline | High | 13 |
| US-12-03 | OLAP Query Engine | High | 8 |
| US-12-04 | Report Builder Service | High | 8 |
| US-12-05 | Dashboard API | Medium | 8 |
| US-12-06 | Data Export Service | Medium | 5 |
| US-12-07 | Analytics WebSocket | Medium | 5 |

## Technical Requirements

### Performance Targets
- Query response time < 2s for standard reports
- Dashboard load time < 1s
- ETL lag < 5 minutes for real-time facts
- Support for 1B+ fact records
- Concurrent query capacity: 100+ users

### Data Retention
- Fact tables: 7 years
- Aggregate tables: Indefinite
- Report outputs: 90 days
- Query cache: 15 minutes

### Security
- Row-level security on all queries
- Tenant data isolation
- Audit trail for all data access
- PII masking in exports

## Dependencies

- **Epic 09**: OMS for trade data
- **Epic 10**: Risk service for risk snapshots
- **Epic 11**: Post-trade for settlement data
- **Epic 05**: Market data for quote facts
- **Epic 04**: Reference data for dimensions

## Integration Events

### Consumed Events
- `trade.executed` - Trade fact capture
- `order.filled` - Order fill metrics
- `quote.received` - Quote fact capture
- `risk.snapshot.created` - Risk fact capture
- `position.updated` - Position metrics
- `settlement.completed` - Settlement metrics

### Published Events
- `report.generated` - Report completion notification
- `export.completed` - Data export ready
- `dashboard.updated` - Dashboard data refresh
- `alert.threshold.crossed` - Analytics-based alerts
