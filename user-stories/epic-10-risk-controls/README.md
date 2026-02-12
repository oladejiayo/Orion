# Epic 10 - Risk & Controls

## Overview

This epic implements comprehensive risk management and trading controls including pre-trade checks, position limits, exposure monitoring, and kill switch capabilities for the Orion platform.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Risk Engine                                  │
├─────────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                 │
│  │ Pre-Trade   │  │  Real-Time  │  │   Limit     │                 │
│  │   Checks    │  │  Monitor    │  │  Manager    │                 │
│  └─────────────┘  └─────────────┘  └─────────────┘                 │
│         │                │                │                         │
│  ┌──────┴────────────────┴────────────────┴─────┐                  │
│  │              Risk Cache (Redis)               │                  │
│  └───────────────────────────────────────────────┘                  │
│         │                │                │                         │
│  ┌──────┴──────┐  ┌──────┴──────┐  ┌──────┴──────┐                 │
│  │  Position   │  │  Exposure   │  │   Order     │                 │
│  │   Limits    │  │   Limits    │  │  Controls   │                 │
│  └─────────────┘  └─────────────┘  └─────────────┘                 │
├─────────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                 │
│  │ Kill Switch │  │   Alert     │  │  Circuit    │                 │
│  │  Manager    │  │  Engine     │  │  Breakers   │                 │
│  └─────────────┘  └─────────────┘  └─────────────┘                 │
└─────────────────────────────────────────────────────────────────────┘
```

## Risk Hierarchy

```
Tenant (Platform-wide)
  └── Client Group (Desk/Team)
        └── Client (Individual/Account)
              └── Instrument (Per-Symbol)
```

## Risk Limit Types

| Limit Type | Level | Description |
|------------|-------|-------------|
| **Position Limit** | Client/Instrument | Max position size |
| **Gross Exposure** | Client/Group | Total exposure across positions |
| **Net Exposure** | Client/Group | Net long/short exposure |
| **Daily Loss** | Client/Group | Maximum daily P&L loss |
| **Order Size** | Client/Instrument | Max single order size |
| **Order Rate** | Client | Orders per time window |
| **Turnover** | Client | Daily trading volume |

## Data Model

```typescript
interface RiskLimit {
  id: string;
  tenantId: string;
  entityType: 'tenant' | 'client_group' | 'client' | 'instrument';
  entityId: string;
  limitType: RiskLimitType;
  limitValue: number;
  warningThreshold: number;  // % of limit
  currency?: string;
  instrumentId?: string;
  isActive: boolean;
  effectiveFrom: Date;
  effectiveTo?: Date;
}

interface RiskCheck {
  orderId: string;
  checkType: string;
  status: 'passed' | 'failed' | 'warning';
  currentValue: number;
  limitValue: number;
  message?: string;
  checkedAt: Date;
}

interface RiskAlert {
  id: string;
  tenantId: string;
  entityType: string;
  entityId: string;
  alertType: string;
  severity: 'info' | 'warning' | 'critical';
  message: string;
  currentValue: number;
  thresholdValue: number;
  acknowledgedBy?: string;
  acknowledgedAt?: Date;
  createdAt: Date;
}
```

## User Stories

| ID | Title | Priority | Points |
|----|-------|----------|--------|
| US-10-01 | Risk Limit Configuration | P0 | 5 |
| US-10-02 | Pre-Trade Risk Checks | P0 | 8 |
| US-10-03 | Real-Time Exposure Monitor | P0 | 8 |
| US-10-04 | Position Limit Management | P0 | 5 |
| US-10-05 | Kill Switch Implementation | P0 | 5 |
| US-10-06 | Risk Alert Engine | P1 | 5 |
| US-10-07 | Risk Reporting API | P1 | 5 |

## Performance Requirements

- Pre-trade check latency: < 5ms
- Real-time exposure update: < 100ms
- Kill switch activation: < 1 second
- Risk cache refresh: every 100ms
- Alert generation: < 500ms

## Integration Points

- **Order Service**: Pre-trade validation
- **Position Service**: Exposure calculation
- **Trade Service**: P&L updates
- **Client Service**: Client risk profiles
- **Alert Service**: Risk notifications

## Success Metrics

- 100% pre-trade check coverage
- < 5ms average check latency
- Zero false positive kill switches
- Real-time exposure accuracy within 0.1%
