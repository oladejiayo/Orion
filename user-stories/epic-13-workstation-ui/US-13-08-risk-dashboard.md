# US-13-08: Risk Dashboard Widget

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-13-08 |
| **Epic** | Epic 13: Workstation UI |
| **Title** | Risk Dashboard Widget |
| **Priority** | Medium |
| **Story Points** | 5 |
| **Status** | Ready for Development |

## User Story

**As a** trader or risk manager  
**I want** a risk metrics dashboard widget  
**So that** I can monitor risk limits and exposure in real-time

## Acceptance Criteria

### AC1: Risk Metrics Display
- **Given** the risk dashboard widget
- **When** I view the dashboard
- **Then** I see:
  - Gross/Net exposure
  - VaR (Value at Risk)
  - P&L vs limits
  - Position concentration

### AC2: Limit Utilization
- **Given** configured risk limits
- **When** utilization changes
- **Then** I see:
  - Visual gauges (percentage bars)
  - Color coding (green/yellow/red)
  - Warning thresholds

### AC3: Breach Alerts
- **Given** risk limit breaches
- **When** a limit is exceeded
- **Then**:
  - Widget shows alert indicator
  - Notification triggered
  - Breach details displayed

### AC4: Drill-Down
- **Given** a risk metric
- **When** I click for details
- **Then** I see breakdown by:
  - Instrument
  - Asset class
  - Client

## Technical Specification

### Risk Dashboard Component

```typescript
// src/features/risk/components/RiskDashboard.tsx
import React, { useMemo } from 'react';
import { useRiskMetrics } from '../hooks/useRiskMetrics';
import { RiskGauge } from './RiskGauge';
import { RiskMetricCard } from './RiskMetricCard';
import { BreachAlert } from './BreachAlert';
import { RiskBreakdownModal } from './RiskBreakdownModal';
import { formatCurrency, formatPercent } from '../../../shared/utils/formatters';

interface RiskDashboardProps {
  panelId: string;
}

export const RiskDashboard: React.FC<RiskDashboardProps> = ({ panelId }) => {
  const {
    metrics,
    limits,
    breaches,
    isLoading,
    selectedBreakdown,
    setSelectedBreakdown,
  } = useRiskMetrics();

  const [showBreakdownModal, setShowBreakdownModal] = React.useState(false);
  const [breakdownMetric, setBreakdownMetric] = React.useState<string | null>(null);

  const handleMetricClick = (metricKey: string) => {
    setBreakdownMetric(metricKey);
    setShowBreakdownModal(true);
  };

  // Calculate utilization percentages
  const utilization = useMemo(() => ({
    grossExposure: (metrics.grossExposure / limits.grossExposure) * 100,
    netExposure: (Math.abs(metrics.netExposure) / limits.netExposure) * 100,
    var: (metrics.var / limits.var) * 100,
    dailyLoss: Math.abs(metrics.dailyPnl < 0 ? metrics.dailyPnl / limits.dailyLoss * 100 : 0),
    concentration: (metrics.maxConcentration / limits.concentration) * 100,
  }), [metrics, limits]);

  return (
    <div className="risk-dashboard">
      {/* Breach Alerts Banner */}
      {breaches.length > 0 && (
        <div className="breach-banner">
          {breaches.map((breach) => (
            <BreachAlert
              key={breach.id}
              breach={breach}
              onAcknowledge={() => {}}
            />
          ))}
        </div>
      )}

      {/* Key Metrics Row */}
      <div className="metrics-row">
        <RiskMetricCard
          label="Gross Exposure"
          value={formatCurrency(metrics.grossExposure, 'USD')}
          limit={formatCurrency(limits.grossExposure, 'USD')}
          utilization={utilization.grossExposure}
          onClick={() => handleMetricClick('grossExposure')}
        />
        <RiskMetricCard
          label="Net Exposure"
          value={formatCurrency(metrics.netExposure, 'USD')}
          limit={formatCurrency(limits.netExposure, 'USD')}
          utilization={utilization.netExposure}
          direction={metrics.netExposure >= 0 ? 'long' : 'short'}
          onClick={() => handleMetricClick('netExposure')}
        />
        <RiskMetricCard
          label="VaR (95%)"
          value={formatCurrency(metrics.var, 'USD')}
          limit={formatCurrency(limits.var, 'USD')}
          utilization={utilization.var}
          onClick={() => handleMetricClick('var')}
        />
        <RiskMetricCard
          label="Daily P&L"
          value={formatCurrency(metrics.dailyPnl, 'USD')}
          limit={formatCurrency(-limits.dailyLoss, 'USD')}
          utilization={utilization.dailyLoss}
          isPnl={true}
          onClick={() => handleMetricClick('dailyPnl')}
        />
      </div>

      {/* Utilization Gauges */}
      <div className="gauges-section">
        <h3 className="section-title">Limit Utilization</h3>
        <div className="gauges-grid">
          <RiskGauge
            label="Gross Exposure"
            value={utilization.grossExposure}
            thresholds={{ warning: 75, critical: 90 }}
          />
          <RiskGauge
            label="Net Exposure"
            value={utilization.netExposure}
            thresholds={{ warning: 70, critical: 85 }}
          />
          <RiskGauge
            label="VaR"
            value={utilization.var}
            thresholds={{ warning: 80, critical: 95 }}
          />
          <RiskGauge
            label="Daily Loss"
            value={utilization.dailyLoss}
            thresholds={{ warning: 50, critical: 80 }}
          />
          <RiskGauge
            label="Concentration"
            value={utilization.concentration}
            thresholds={{ warning: 60, critical: 80 }}
          />
        </div>
      </div>

      {/* Position Breakdown Summary */}
      <div className="breakdown-section">
        <h3 className="section-title">Exposure by Asset Class</h3>
        <div className="breakdown-bars">
          {metrics.exposureByAssetClass.map((item) => (
            <div key={item.assetClass} className="breakdown-item">
              <div className="breakdown-header">
                <span className="breakdown-label">{item.assetClass}</span>
                <span className="breakdown-value">
                  {formatCurrency(item.exposure, 'USD')}
                </span>
              </div>
              <div className="breakdown-bar">
                <div
                  className="breakdown-fill"
                  style={{
                    width: `${(item.exposure / metrics.grossExposure) * 100}%`,
                    backgroundColor: item.color,
                  }}
                />
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Breakdown Modal */}
      {showBreakdownModal && breakdownMetric && (
        <RiskBreakdownModal
          metricKey={breakdownMetric}
          onClose={() => setShowBreakdownModal(false)}
        />
      )}
    </div>
  );
};
```

### Risk Gauge Component

```typescript
// src/features/risk/components/RiskGauge.tsx
import React from 'react';

interface RiskGaugeProps {
  label: string;
  value: number; // Percentage 0-100+
  thresholds: {
    warning: number;
    critical: number;
  };
}

export const RiskGauge: React.FC<RiskGaugeProps> = ({
  label,
  value,
  thresholds,
}) => {
  const clampedValue = Math.min(value, 100);
  
  const getStatus = () => {
    if (value >= thresholds.critical) return 'critical';
    if (value >= thresholds.warning) return 'warning';
    return 'normal';
  };

  const status = getStatus();

  return (
    <div className={`risk-gauge ${status}`}>
      <div className="gauge-header">
        <span className="gauge-label">{label}</span>
        <span className="gauge-value">{value.toFixed(1)}%</span>
      </div>
      <div className="gauge-track">
        <div
          className="gauge-fill"
          style={{ width: `${clampedValue}%` }}
        />
        {/* Threshold markers */}
        <div
          className="gauge-marker warning"
          style={{ left: `${thresholds.warning}%` }}
        />
        <div
          className="gauge-marker critical"
          style={{ left: `${thresholds.critical}%` }}
        />
      </div>
      {value > 100 && (
        <div className="breach-indicator">
          ⚠️ BREACH ({(value - 100).toFixed(1)}% over)
        </div>
      )}
    </div>
  );
};
```

### Risk Metrics Hook

```typescript
// src/features/risk/hooks/useRiskMetrics.ts
import { useEffect, useCallback } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { riskApi } from '../services/risk.api';
import { useWebSocket } from '../../../shared/hooks/useWebSocket';
import { RiskMetrics, RiskLimits, RiskBreach } from '../types/risk.types';

interface UseRiskMetricsResult {
  metrics: RiskMetrics;
  limits: RiskLimits;
  breaches: RiskBreach[];
  isLoading: boolean;
  error: Error | null;
  selectedBreakdown: string | null;
  setSelectedBreakdown: (breakdown: string | null) => void;
}

const defaultMetrics: RiskMetrics = {
  grossExposure: 0,
  netExposure: 0,
  var: 0,
  dailyPnl: 0,
  maxConcentration: 0,
  exposureByAssetClass: [],
};

const defaultLimits: RiskLimits = {
  grossExposure: 10000000,
  netExposure: 5000000,
  var: 500000,
  dailyLoss: 100000,
  concentration: 25,
};

export function useRiskMetrics(): UseRiskMetricsResult {
  const queryClient = useQueryClient();
  const { subscribe } = useWebSocket();

  const { data: metricsData, isLoading: metricsLoading } = useQuery({
    queryKey: ['risk-metrics'],
    queryFn: () => riskApi.getMetrics(),
    refetchInterval: 30000, // Refresh every 30s
  });

  const { data: limitsData, isLoading: limitsLoading } = useQuery({
    queryKey: ['risk-limits'],
    queryFn: () => riskApi.getLimits(),
    staleTime: 300000, // 5 minutes
  });

  const { data: breachesData } = useQuery({
    queryKey: ['risk-breaches'],
    queryFn: () => riskApi.getActiveBreaches(),
    refetchInterval: 10000,
  });

  // Handle real-time risk updates
  const handleRiskUpdate = useCallback((update: Partial<RiskMetrics>) => {
    queryClient.setQueryData<RiskMetrics>(['risk-metrics'], (old) => ({
      ...defaultMetrics,
      ...old,
      ...update,
    }));
  }, [queryClient]);

  // Handle new breaches
  const handleBreachUpdate = useCallback((breach: RiskBreach) => {
    queryClient.setQueryData<RiskBreach[]>(['risk-breaches'], (old = []) => {
      const exists = old.some(b => b.id === breach.id);
      if (exists) {
        return old.map(b => b.id === breach.id ? breach : b);
      }
      return [...old, breach];
    });
  }, [queryClient]);

  useEffect(() => {
    const unsubMetrics = subscribe('risk', (data: any) => {
      if (data.type === 'RISK_UPDATE') {
        handleRiskUpdate(data.metrics);
      }
      if (data.type === 'BREACH') {
        handleBreachUpdate(data.breach);
      }
    });

    return () => unsubMetrics();
  }, [subscribe, handleRiskUpdate, handleBreachUpdate]);

  return {
    metrics: metricsData || defaultMetrics,
    limits: limitsData || defaultLimits,
    breaches: breachesData || [],
    isLoading: metricsLoading || limitsLoading,
    error: null,
    selectedBreakdown: null,
    setSelectedBreakdown: () => {},
  };
}
```

### CSS Styles

```css
/* src/features/risk/styles/risk-dashboard.css */

.risk-dashboard {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px;
  height: 100%;
  overflow-y: auto;
}

/* Breach Banner */
.breach-banner {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.breach-alert {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  background: rgba(244, 67, 54, 0.15);
  border: 1px solid var(--error-color);
  border-radius: 4px;
}

.breach-alert .icon {
  font-size: 20px;
}

.breach-alert .message {
  flex: 1;
  color: var(--error-color);
  font-weight: 500;
}

/* Metrics Row */
.metrics-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 12px;
}

.metric-card {
  padding: 16px;
  background: var(--bg-secondary);
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s;
}

.metric-card:hover {
  background: var(--bg-hover);
}

.metric-card .label {
  font-size: 12px;
  color: var(--text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.metric-card .value {
  font-size: 24px;
  font-weight: 600;
  font-family: var(--font-mono);
  margin-top: 4px;
}

.metric-card .value.positive {
  color: var(--success-color);
}

.metric-card .value.negative {
  color: var(--error-color);
}

.metric-card .limit {
  font-size: 11px;
  color: var(--text-tertiary);
  margin-top: 4px;
}

.metric-card .utilization-bar {
  height: 4px;
  background: var(--bg-tertiary);
  border-radius: 2px;
  margin-top: 8px;
  overflow: hidden;
}

.metric-card .utilization-fill {
  height: 100%;
  transition: width 0.3s;
}

.metric-card .utilization-fill.normal {
  background: var(--success-color);
}

.metric-card .utilization-fill.warning {
  background: var(--warning-color);
}

.metric-card .utilization-fill.critical {
  background: var(--error-color);
}

/* Gauges Section */
.section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-secondary);
  margin-bottom: 12px;
}

.gauges-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 16px;
}

.risk-gauge {
  padding: 12px;
  background: var(--bg-secondary);
  border-radius: 6px;
}

.gauge-header {
  display: flex;
  justify-content: space-between;
  margin-bottom: 8px;
}

.gauge-label {
  font-size: 12px;
  color: var(--text-secondary);
}

.gauge-value {
  font-size: 14px;
  font-weight: 600;
  font-family: var(--font-mono);
}

.risk-gauge.normal .gauge-value {
  color: var(--success-color);
}

.risk-gauge.warning .gauge-value {
  color: var(--warning-color);
}

.risk-gauge.critical .gauge-value {
  color: var(--error-color);
}

.gauge-track {
  position: relative;
  height: 8px;
  background: var(--bg-tertiary);
  border-radius: 4px;
  overflow: hidden;
}

.gauge-fill {
  height: 100%;
  border-radius: 4px;
  transition: width 0.3s;
}

.risk-gauge.normal .gauge-fill {
  background: var(--success-color);
}

.risk-gauge.warning .gauge-fill {
  background: var(--warning-color);
}

.risk-gauge.critical .gauge-fill {
  background: var(--error-color);
}

.gauge-marker {
  position: absolute;
  top: 0;
  width: 2px;
  height: 100%;
  background: var(--text-tertiary);
}

.breach-indicator {
  font-size: 11px;
  color: var(--error-color);
  font-weight: 600;
  margin-top: 6px;
  animation: pulse 1s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.6; }
}

/* Breakdown Section */
.breakdown-bars {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.breakdown-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.breakdown-header {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
}

.breakdown-label {
  color: var(--text-secondary);
}

.breakdown-value {
  font-family: var(--font-mono);
  color: var(--text-primary);
}

.breakdown-bar {
  height: 6px;
  background: var(--bg-tertiary);
  border-radius: 3px;
  overflow: hidden;
}

.breakdown-fill {
  height: 100%;
  border-radius: 3px;
  transition: width 0.3s;
}
```

## Definition of Done

- [ ] Risk metrics display (exposure, VaR, P&L)
- [ ] Limit utilization gauges
- [ ] Real-time metric updates via WebSocket
- [ ] Breach alerts with notifications
- [ ] Drill-down breakdown modal
- [ ] Color-coded thresholds
- [ ] Responsive layout
- [ ] Unit tests for calculations
- [ ] Integration tests

## Test Cases

```typescript
describe('RiskDashboard', () => {
  it('should display risk metrics', async () => {
    render(<RiskDashboard panelId="test" />);
    
    expect(screen.getByText('Gross Exposure')).toBeInTheDocument();
    expect(screen.getByText('VaR (95%)')).toBeInTheDocument();
  });

  it('should show breach alert when limit exceeded', async () => {
    mockRiskApi.getActiveBreaches.mockResolvedValue([
      { id: '1', type: 'GROSS_EXPOSURE', severity: 'critical' },
    ]);

    render(<RiskDashboard panelId="test" />);
    
    await waitFor(() => {
      expect(screen.getByText(/BREACH/)).toBeInTheDocument();
    });
  });

  it('should update metrics in real-time', () => {
    render(<RiskDashboard panelId="test" />);
    
    act(() => {
      emitWebSocketEvent('risk', {
        type: 'RISK_UPDATE',
        metrics: { grossExposure: 8000000 },
      });
    });

    expect(screen.getByText('$8,000,000')).toBeInTheDocument();
  });
});

describe('RiskGauge', () => {
  it('should show warning status at threshold', () => {
    render(
      <RiskGauge
        label="Test"
        value={75}
        thresholds={{ warning: 75, critical: 90 }}
      />
    );
    
    expect(screen.getByText('75.0%').closest('.risk-gauge')).toHaveClass('warning');
  });
});
```
