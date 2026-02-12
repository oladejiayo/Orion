# US-13-07: Real-Time Charts

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-13-07 |
| **Epic** | Epic 13: Workstation UI |
| **Title** | Real-Time Charts |
| **Priority** | High |
| **Story Points** | 8 |
| **Status** | Ready for Development |

## User Story

**As a** trader  
**I want** interactive real-time price charts  
**So that** I can analyze market trends and make informed trading decisions

## Acceptance Criteria

### AC1: Chart Display
- **Given** the chart widget is open
- **When** an instrument is selected
- **Then** I see:
  - Real-time candlestick/line chart
  - Price axis with auto-scaling
  - Time axis with zoom/pan
  - Current price indicator

### AC2: Chart Types
- **Given** the chart toolbar
- **When** I change chart type
- **Then** I can switch between:
  - Candlestick
  - Line
  - Area
  - Bar (OHLC)

### AC3: Time Intervals
- **Given** the chart time selector
- **When** I change interval
- **Then** I can view:
  - 1M, 5M, 15M, 30M, 1H
  - 4H, 1D, 1W, 1MO
  - Auto-adjust to visible range

### AC4: Technical Indicators
- **Given** the indicators menu
- **When** I add indicators
- **Then** I can overlay:
  - Moving Averages (SMA, EMA)
  - Bollinger Bands
  - RSI, MACD
  - Volume

### AC5: Drawing Tools
- **Given** the drawing toolbar
- **When** I use drawing tools
- **Then** I can add:
  - Trend lines
  - Horizontal lines
  - Fibonacci retracements
  - Text annotations

## Technical Specification

### Chart Widget Component

```typescript
// src/features/charts/components/ChartWidget.tsx
import React, { useEffect, useRef, useCallback, useState } from 'react';
import {
  createChart,
  IChartApi,
  ISeriesApi,
  CandlestickData,
  LineData,
  Time,
  CrosshairMode,
} from 'lightweight-charts';
import { usePanelLink } from '../../../shared/hooks/usePanelLink';
import { useChartData } from '../hooks/useChartData';
import { useChartSettings } from '../hooks/useChartSettings';
import { ChartToolbar } from './ChartToolbar';
import { IndicatorPanel } from './IndicatorPanel';
import { DrawingToolbar } from './DrawingToolbar';
import { ChartType, TimeInterval, Indicator } from '../types/chart.types';

interface ChartWidgetProps {
  panelId: string;
}

export const ChartWidget: React.FC<ChartWidgetProps> = ({ panelId }) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const mainSeriesRef = useRef<ISeriesApi<'Candlestick'> | ISeriesApi<'Line'> | null>(null);
  const indicatorSeriesRef = useRef<Map<string, ISeriesApi<any>>>(new Map());

  const { linkedInstrumentId, updateLinkedInstrument } = usePanelLink(panelId);
  
  const [chartType, setChartType] = useState<ChartType>('candlestick');
  const [timeInterval, setTimeInterval] = useState<TimeInterval>('1H');
  const [activeIndicators, setActiveIndicators] = useState<Indicator[]>([]);
  const [isDrawingMode, setDrawingMode] = useState(false);

  const { settings, updateSettings } = useChartSettings(panelId);

  const {
    data,
    isLoading,
    subscribe,
    unsubscribe,
  } = useChartData({
    instrumentId: linkedInstrumentId,
    interval: timeInterval,
  });

  // Initialize chart
  useEffect(() => {
    if (!containerRef.current) return;

    const chart = createChart(containerRef.current, {
      layout: {
        background: { color: '#1a1a2e' },
        textColor: '#d1d4dc',
      },
      grid: {
        vertLines: { color: '#2B2B43' },
        horzLines: { color: '#2B2B43' },
      },
      crosshair: {
        mode: CrosshairMode.Normal,
        vertLine: {
          color: '#758696',
          width: 1,
          style: 3,
        },
        horzLine: {
          color: '#758696',
          width: 1,
          style: 3,
        },
      },
      rightPriceScale: {
        borderColor: '#2B2B43',
        scaleMargins: {
          top: 0.1,
          bottom: 0.2,
        },
      },
      timeScale: {
        borderColor: '#2B2B43',
        timeVisible: true,
        secondsVisible: false,
      },
      handleScroll: {
        mouseWheel: true,
        pressedMouseMove: true,
      },
      handleScale: {
        axisPressedMouseMove: true,
        mouseWheel: true,
        pinch: true,
      },
    });

    chartRef.current = chart;

    // Handle resize
    const resizeObserver = new ResizeObserver((entries) => {
      if (entries.length === 0 || !containerRef.current) return;
      const { width, height } = entries[0].contentRect;
      chart.resize(width, height);
    });

    resizeObserver.observe(containerRef.current);

    return () => {
      resizeObserver.disconnect();
      chart.remove();
      chartRef.current = null;
    };
  }, []);

  // Update series based on chart type
  useEffect(() => {
    if (!chartRef.current) return;

    // Remove existing main series
    if (mainSeriesRef.current) {
      chartRef.current.removeSeries(mainSeriesRef.current);
    }

    // Create new series based on type
    let series: ISeriesApi<any>;

    switch (chartType) {
      case 'candlestick':
        series = chartRef.current.addCandlestickSeries({
          upColor: '#26a69a',
          downColor: '#ef5350',
          borderVisible: false,
          wickUpColor: '#26a69a',
          wickDownColor: '#ef5350',
        });
        break;

      case 'line':
        series = chartRef.current.addLineSeries({
          color: '#2962FF',
          lineWidth: 2,
        });
        break;

      case 'area':
        series = chartRef.current.addAreaSeries({
          topColor: 'rgba(41, 98, 255, 0.3)',
          bottomColor: 'rgba(41, 98, 255, 0.0)',
          lineColor: '#2962FF',
          lineWidth: 2,
        });
        break;

      case 'bar':
        series = chartRef.current.addBarSeries({
          upColor: '#26a69a',
          downColor: '#ef5350',
        });
        break;

      default:
        series = chartRef.current.addCandlestickSeries();
    }

    mainSeriesRef.current = series;
  }, [chartType]);

  // Update data
  useEffect(() => {
    if (!mainSeriesRef.current || !data.length) return;

    if (chartType === 'candlestick' || chartType === 'bar') {
      mainSeriesRef.current.setData(data as CandlestickData<Time>[]);
    } else {
      // Convert to line data
      const lineData: LineData<Time>[] = data.map(d => ({
        time: d.time,
        value: d.close,
      }));
      mainSeriesRef.current.setData(lineData);
    }

    // Fit content
    chartRef.current?.timeScale().fitContent();
  }, [data, chartType]);

  // Real-time updates
  useEffect(() => {
    if (!linkedInstrumentId) return;

    const handleRealtimeUpdate = (update: CandlestickData<Time>) => {
      if (mainSeriesRef.current) {
        mainSeriesRef.current.update(update);
      }
    };

    subscribe(handleRealtimeUpdate);

    return () => {
      unsubscribe();
    };
  }, [linkedInstrumentId, subscribe, unsubscribe]);

  // Add/remove indicators
  const handleAddIndicator = useCallback((indicator: Indicator) => {
    if (!chartRef.current) return;

    let series: ISeriesApi<any>;

    switch (indicator.type) {
      case 'SMA':
      case 'EMA':
        series = chartRef.current.addLineSeries({
          color: indicator.color || '#FF6D00',
          lineWidth: 1,
          priceLineVisible: false,
        });
        break;

      case 'BOLLINGER':
        // Add upper, middle, lower bands
        const upper = chartRef.current.addLineSeries({
          color: '#2196F3',
          lineWidth: 1,
        });
        const lower = chartRef.current.addLineSeries({
          color: '#2196F3',
          lineWidth: 1,
        });
        indicatorSeriesRef.current.set(`${indicator.id}-upper`, upper);
        indicatorSeriesRef.current.set(`${indicator.id}-lower`, lower);
        series = chartRef.current.addLineSeries({
          color: '#2196F3',
          lineWidth: 1,
        });
        break;

      case 'RSI':
      case 'MACD':
        // These go on separate panes - would need histogram series
        series = chartRef.current.addLineSeries({
          color: indicator.color || '#9C27B0',
          lineWidth: 1,
          pane: 1,
        });
        break;

      case 'VOLUME':
        series = chartRef.current.addHistogramSeries({
          color: '#26a69a',
          priceFormat: {
            type: 'volume',
          },
          priceScaleId: 'volume',
        });
        chartRef.current.priceScale('volume').applyOptions({
          scaleMargins: {
            top: 0.8,
            bottom: 0,
          },
        });
        break;

      default:
        return;
    }

    indicatorSeriesRef.current.set(indicator.id, series);
    setActiveIndicators(prev => [...prev, indicator]);

    // Calculate and set indicator data
    calculateIndicatorData(indicator, data);
  }, [data]);

  const handleRemoveIndicator = useCallback((indicatorId: string) => {
    const series = indicatorSeriesRef.current.get(indicatorId);
    if (series && chartRef.current) {
      chartRef.current.removeSeries(series);
      indicatorSeriesRef.current.delete(indicatorId);
    }
    setActiveIndicators(prev => prev.filter(i => i.id !== indicatorId));
  }, []);

  const calculateIndicatorData = useCallback((
    indicator: Indicator,
    candles: CandlestickData<Time>[]
  ) => {
    const series = indicatorSeriesRef.current.get(indicator.id);
    if (!series) return;

    const closes = candles.map(c => c.close);
    let indicatorData: LineData<Time>[];

    switch (indicator.type) {
      case 'SMA':
        indicatorData = calculateSMA(candles, indicator.params.period || 20);
        break;
      case 'EMA':
        indicatorData = calculateEMA(candles, indicator.params.period || 20);
        break;
      case 'VOLUME':
        const volumeData = candles.map(c => ({
          time: c.time,
          value: (c as any).volume || 0,
          color: c.close >= c.open ? '#26a69a' : '#ef5350',
        }));
        series.setData(volumeData);
        return;
      default:
        return;
    }

    series.setData(indicatorData);
  }, []);

  return (
    <div className="chart-widget">
      <ChartToolbar
        instrumentId={linkedInstrumentId}
        chartType={chartType}
        timeInterval={timeInterval}
        onChartTypeChange={setChartType}
        onTimeIntervalChange={setTimeInterval}
        onDrawingModeToggle={() => setDrawingMode(!isDrawingMode)}
        isDrawingMode={isDrawingMode}
      />

      <div className="chart-container" ref={containerRef} />

      <IndicatorPanel
        activeIndicators={activeIndicators}
        onAddIndicator={handleAddIndicator}
        onRemoveIndicator={handleRemoveIndicator}
      />

      {isDrawingMode && (
        <DrawingToolbar
          chart={chartRef.current}
          onClose={() => setDrawingMode(false)}
        />
      )}
    </div>
  );
};

// Indicator calculations
function calculateSMA(
  candles: CandlestickData<Time>[],
  period: number
): LineData<Time>[] {
  const result: LineData<Time>[] = [];
  
  for (let i = period - 1; i < candles.length; i++) {
    let sum = 0;
    for (let j = 0; j < period; j++) {
      sum += candles[i - j].close;
    }
    result.push({
      time: candles[i].time,
      value: sum / period,
    });
  }
  
  return result;
}

function calculateEMA(
  candles: CandlestickData<Time>[],
  period: number
): LineData<Time>[] {
  const result: LineData<Time>[] = [];
  const multiplier = 2 / (period + 1);
  
  // Start with SMA
  let sum = 0;
  for (let i = 0; i < period; i++) {
    sum += candles[i].close;
  }
  let ema = sum / period;
  result.push({ time: candles[period - 1].time, value: ema });
  
  // Calculate EMA
  for (let i = period; i < candles.length; i++) {
    ema = (candles[i].close - ema) * multiplier + ema;
    result.push({ time: candles[i].time, value: ema });
  }
  
  return result;
}
```

### Chart Toolbar

```typescript
// src/features/charts/components/ChartToolbar.tsx
import React from 'react';
import * as ToggleGroup from '@radix-ui/react-toggle-group';
import { ChartType, TimeInterval } from '../types/chart.types';

interface ChartToolbarProps {
  instrumentId: string | null;
  chartType: ChartType;
  timeInterval: TimeInterval;
  onChartTypeChange: (type: ChartType) => void;
  onTimeIntervalChange: (interval: TimeInterval) => void;
  onDrawingModeToggle: () => void;
  isDrawingMode: boolean;
}

const TIME_INTERVALS: { value: TimeInterval; label: string }[] = [
  { value: '1M', label: '1m' },
  { value: '5M', label: '5m' },
  { value: '15M', label: '15m' },
  { value: '30M', label: '30m' },
  { value: '1H', label: '1H' },
  { value: '4H', label: '4H' },
  { value: '1D', label: '1D' },
  { value: '1W', label: '1W' },
];

export const ChartToolbar: React.FC<ChartToolbarProps> = ({
  instrumentId,
  chartType,
  timeInterval,
  onChartTypeChange,
  onTimeIntervalChange,
  onDrawingModeToggle,
  isDrawingMode,
}) => {
  return (
    <div className="chart-toolbar">
      <div className="toolbar-left">
        <span className="instrument-symbol">
          {instrumentId || 'Select Instrument'}
        </span>
      </div>

      <div className="toolbar-center">
        {/* Time Interval Selector */}
        <ToggleGroup.Root
          type="single"
          value={timeInterval}
          onValueChange={(value) => value && onTimeIntervalChange(value as TimeInterval)}
          className="toggle-group"
        >
          {TIME_INTERVALS.map((interval) => (
            <ToggleGroup.Item
              key={interval.value}
              value={interval.value}
              className="toggle-item"
            >
              {interval.label}
            </ToggleGroup.Item>
          ))}
        </ToggleGroup.Root>
      </div>

      <div className="toolbar-right">
        {/* Chart Type Selector */}
        <ToggleGroup.Root
          type="single"
          value={chartType}
          onValueChange={(value) => value && onChartTypeChange(value as ChartType)}
          className="toggle-group"
        >
          <ToggleGroup.Item value="candlestick" className="toggle-item" title="Candlestick">
            📊
          </ToggleGroup.Item>
          <ToggleGroup.Item value="line" className="toggle-item" title="Line">
            📈
          </ToggleGroup.Item>
          <ToggleGroup.Item value="area" className="toggle-item" title="Area">
            📉
          </ToggleGroup.Item>
        </ToggleGroup.Root>

        <button
          className={`toolbar-btn ${isDrawingMode ? 'active' : ''}`}
          onClick={onDrawingModeToggle}
          title="Drawing Tools"
        >
          ✏️
        </button>

        <button className="toolbar-btn" title="Indicators">
          📐
        </button>

        <button className="toolbar-btn" title="Settings">
          ⚙️
        </button>
      </div>
    </div>
  );
};
```

### Chart Data Hook

```typescript
// src/features/charts/hooks/useChartData.ts
import { useState, useEffect, useCallback, useRef } from 'react';
import { useQuery } from '@tanstack/react-query';
import { CandlestickData, Time } from 'lightweight-charts';
import { chartApi } from '../services/chart.api';
import { useWebSocket } from '../../../shared/hooks/useWebSocket';
import { TimeInterval } from '../types/chart.types';

interface UseChartDataOptions {
  instrumentId: string | null;
  interval: TimeInterval;
}

export function useChartData({ instrumentId, interval }: UseChartDataOptions) {
  const callbackRef = useRef<((data: CandlestickData<Time>) => void) | null>(null);
  const { subscribe: wsSubscribe, send } = useWebSocket();

  const queryKey = ['chart-data', instrumentId, interval];

  const { data, isLoading, error, refetch } = useQuery({
    queryKey,
    queryFn: () => chartApi.getCandles(instrumentId!, interval),
    enabled: !!instrumentId,
    staleTime: 60000, // 1 minute
  });

  // Subscribe to real-time candle updates
  const subscribe = useCallback((callback: (data: CandlestickData<Time>) => void) => {
    callbackRef.current = callback;

    if (!instrumentId) return;

    // Subscribe to candle updates
    send('charts', {
      action: 'SUBSCRIBE',
      instrumentId,
      interval,
    });
  }, [instrumentId, interval, send]);

  const unsubscribe = useCallback(() => {
    callbackRef.current = null;

    if (!instrumentId) return;

    send('charts', {
      action: 'UNSUBSCRIBE',
      instrumentId,
      interval,
    });
  }, [instrumentId, interval, send]);

  // Handle WebSocket updates
  useEffect(() => {
    const handleUpdate = (message: any) => {
      if (
        message.type === 'CANDLE_UPDATE' &&
        message.instrumentId === instrumentId &&
        message.interval === interval
      ) {
        const candle: CandlestickData<Time> = {
          time: message.time as Time,
          open: message.open,
          high: message.high,
          low: message.low,
          close: message.close,
        };

        callbackRef.current?.(candle);
      }
    };

    const unsub = wsSubscribe('charts', handleUpdate);
    return () => unsub();
  }, [instrumentId, interval, wsSubscribe]);

  return {
    data: data || [],
    isLoading,
    error: error as Error | null,
    refetch,
    subscribe,
    unsubscribe,
  };
}
```

### CSS Styles

```css
/* src/features/charts/styles/chart-widget.css */

.chart-widget {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #1a1a2e;
}

.chart-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: #16162a;
  border-bottom: 1px solid #2B2B43;
}

.toolbar-left,
.toolbar-center,
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.instrument-symbol {
  font-size: 16px;
  font-weight: 600;
  color: #fff;
}

/* Toggle group */
.toggle-group {
  display: flex;
  background: #2B2B43;
  border-radius: 4px;
  padding: 2px;
}

.toggle-item {
  padding: 4px 8px;
  background: transparent;
  border: none;
  border-radius: 3px;
  font-size: 12px;
  color: #758696;
  cursor: pointer;
  transition: all 0.15s;
}

.toggle-item:hover {
  color: #d1d4dc;
}

.toggle-item[data-state="on"] {
  background: #2962FF;
  color: #fff;
}

/* Toolbar buttons */
.toolbar-btn {
  padding: 6px 10px;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
}

.toolbar-btn:hover {
  background: #2B2B43;
}

.toolbar-btn.active {
  background: #2962FF;
  border-color: #2962FF;
}

/* Chart container */
.chart-container {
  flex: 1;
  min-height: 0;
}

/* Indicator panel */
.indicator-panel {
  display: flex;
  gap: 8px;
  padding: 8px;
  background: #16162a;
  border-top: 1px solid #2B2B43;
  overflow-x: auto;
}

.indicator-chip {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  background: #2B2B43;
  border-radius: 4px;
  font-size: 12px;
  color: #d1d4dc;
}

.indicator-chip .remove-btn {
  background: none;
  border: none;
  color: #758696;
  cursor: pointer;
  padding: 0 4px;
}

.indicator-chip .remove-btn:hover {
  color: #ef5350;
}
```

## Definition of Done

- [ ] TradingView Lightweight Charts integration
- [ ] Candlestick, line, area chart types
- [ ] Time interval switching
- [ ] Real-time candle updates via WebSocket
- [ ] Moving Average indicators (SMA, EMA)
- [ ] Volume histogram
- [ ] Drawing tools (trend lines)
- [ ] Panel linking for instrument sync
- [ ] Chart settings persistence
- [ ] Unit tests for indicator calculations
- [ ] Integration tests

## Test Cases

```typescript
describe('ChartWidget', () => {
  it('should render chart with data', async () => {
    render(<ChartWidget panelId="test" />);
    
    await waitFor(() => {
      expect(containerRef.current?.querySelector('canvas')).toBeInTheDocument();
    });
  });

  it('should update on real-time candle', async () => {
    const { result } = renderHook(() => useChartData({
      instrumentId: 'EURUSD',
      interval: '1H',
    }));

    const mockCallback = jest.fn();
    result.current.subscribe(mockCallback);

    act(() => {
      emitWebSocketEvent('charts', {
        type: 'CANDLE_UPDATE',
        instrumentId: 'EURUSD',
        interval: '1H',
        time: 1234567890,
        open: 1.12,
        high: 1.13,
        low: 1.11,
        close: 1.125,
      });
    });

    expect(mockCallback).toHaveBeenCalled();
  });
});

describe('Indicator Calculations', () => {
  it('should calculate SMA correctly', () => {
    const candles = generateMockCandles(30);
    const sma = calculateSMA(candles, 20);
    
    expect(sma.length).toBe(11); // 30 - 20 + 1
  });
});
```
