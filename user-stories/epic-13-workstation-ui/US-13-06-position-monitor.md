# US-13-06: Position Monitor

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-13-06 |
| **Epic** | Epic 13: Workstation UI |
| **Title** | Position Monitor |
| **Priority** | High |
| **Story Points** | 8 |
| **Status** | Ready for Development |

## User Story

**As a** trader or risk manager  
**I want** a real-time position monitoring panel  
**So that** I can track open positions and P&L throughout the trading day

## Acceptance Criteria

### AC1: Position Display
- **Given** the position monitor is open
- **When** positions exist
- **Then** I see:
  - Position quantity (long/short)
  - Average cost
  - Current market value
  - Unrealized P&L
  - Realized P&L

### AC2: Real-Time P&L
- **Given** positions in the monitor
- **When** market prices update
- **Then**:
  - Unrealized P&L updates in real-time
  - Color coding shows profit/loss
  - Total P&L aggregates correctly

### AC3: Position Grouping
- **Given** multiple positions
- **When** I enable grouping
- **Then** I can group by:
  - Asset class
  - Currency
  - Client
  - Trading book

### AC4: Quick Actions
- **Given** a position row
- **When** I right-click
- **Then** I can:
  - Close position (create offsetting order)
  - Partial close
  - Add to position
  - View position history

### AC5: Risk Indicators
- **Given** positions with risk metrics
- **When** viewing the monitor
- **Then** I see:
  - Position size vs limits
  - Concentration warnings
  - Delta/Gamma exposure (for options)

## Technical Specification

### Position Monitor Component

```typescript
// src/features/positions/components/PositionMonitor.tsx
import React, { useMemo, useCallback, useRef } from 'react';
import { AgGridReact } from 'ag-grid-react';
import { ColDef, CellClassParams, GetRowIdParams } from 'ag-grid-community';
import { usePositions } from '../hooks/usePositions';
import { usePanelLink } from '../../../shared/hooks/usePanelLink';
import { useOrderTicketStore } from '../../order-ticket/stores/order-ticket.store';
import { Position } from '../types/position.types';
import { PositionToolbar } from './PositionToolbar';
import { PnlCellRenderer } from './PnlCellRenderer';
import { PositionContextMenu } from './PositionContextMenu';
import { formatCurrency, formatQuantity } from '../../../shared/utils/formatters';

interface PositionMonitorProps {
  panelId: string;
  showClosedPositions?: boolean;
}

export const PositionMonitor: React.FC<PositionMonitorProps> = ({
  panelId,
  showClosedPositions = false,
}) => {
  const gridRef = useRef<AgGridReact<Position>>(null);
  const { updateLinkedInstrument } = usePanelLink(panelId);
  const { openTicket } = useOrderTicketStore();

  const [contextMenu, setContextMenu] = React.useState<{
    x: number;
    y: number;
    position: Position;
  } | null>(null);

  const { 
    positions, 
    isLoading,
    totals,
    refetch,
  } = usePositions({ includeClosedToday: showClosedPositions });

  const columnDefs = useMemo<ColDef<Position>[]>(() => [
    {
      headerName: 'Instrument',
      field: 'instrumentSymbol',
      width: 100,
      pinned: 'left',
      enableRowGroup: true,
    },
    {
      headerName: 'Side',
      field: 'side',
      width: 60,
      valueGetter: (params) => params.data?.quantity > 0 ? 'LONG' : 'SHORT',
      cellClass: (params: CellClassParams) => 
        params.value === 'LONG' ? 'side-long' : 'side-short',
    },
    {
      headerName: 'Quantity',
      field: 'quantity',
      width: 100,
      type: 'numericColumn',
      valueFormatter: (params) => formatQuantity(Math.abs(params.value)),
      aggFunc: 'sum',
    },
    {
      headerName: 'Avg Cost',
      field: 'averageCost',
      width: 100,
      type: 'numericColumn',
      valueFormatter: (params) => 
        formatCurrency(params.value, params.data?.currency),
    },
    {
      headerName: 'Market Price',
      field: 'marketPrice',
      width: 100,
      type: 'numericColumn',
      valueFormatter: (params) => 
        formatCurrency(params.value, params.data?.currency),
      cellRenderer: 'pnlCell',
      cellRendererParams: { animated: true },
    },
    {
      headerName: 'Market Value',
      field: 'marketValue',
      width: 120,
      type: 'numericColumn',
      valueFormatter: (params) => 
        formatCurrency(params.value, params.data?.currency),
      aggFunc: 'sum',
    },
    {
      headerName: 'Unrealized P&L',
      field: 'unrealizedPnl',
      width: 120,
      type: 'numericColumn',
      cellRenderer: 'pnlCell',
      cellRendererParams: { showCurrency: true },
      aggFunc: 'sum',
    },
    {
      headerName: 'Realized P&L',
      field: 'realizedPnl',
      width: 120,
      type: 'numericColumn',
      cellRenderer: 'pnlCell',
      cellRendererParams: { showCurrency: true },
      aggFunc: 'sum',
    },
    {
      headerName: 'Total P&L',
      field: 'totalPnl',
      width: 120,
      type: 'numericColumn',
      valueGetter: (params) => 
        (params.data?.unrealizedPnl || 0) + (params.data?.realizedPnl || 0),
      cellRenderer: 'pnlCell',
      cellRendererParams: { showCurrency: true, bold: true },
      aggFunc: 'sum',
    },
    {
      headerName: 'P&L %',
      field: 'pnlPercent',
      width: 80,
      type: 'numericColumn',
      valueGetter: (params) => {
        if (!params.data?.costBasis) return 0;
        return ((params.data.unrealizedPnl / Math.abs(params.data.costBasis)) * 100);
      },
      valueFormatter: (params) => `${params.value >= 0 ? '+' : ''}${params.value.toFixed(2)}%`,
      cellClass: (params: CellClassParams) => 
        params.value >= 0 ? 'pnl-positive' : 'pnl-negative',
    },
    {
      headerName: 'Client',
      field: 'clientName',
      width: 120,
      enableRowGroup: true,
      hide: true,
    },
    {
      headerName: 'Book',
      field: 'tradingBook',
      width: 100,
      enableRowGroup: true,
      hide: true,
    },
  ], []);

  const defaultColDef = useMemo<ColDef>(() => ({
    sortable: true,
    filter: true,
    resizable: true,
  }), []);

  const components = useMemo(() => ({
    pnlCell: PnlCellRenderer,
  }), []);

  const getRowId = useCallback((params: GetRowIdParams<Position>) => {
    return params.data.positionId;
  }, []);

  const onRowClicked = useCallback((event: any) => {
    updateLinkedInstrument(event.data.instrumentId);
  }, [updateLinkedInstrument]);

  const onCellContextMenu = useCallback((event: any) => {
    event.event.preventDefault();
    setContextMenu({
      x: event.event.clientX,
      y: event.event.clientY,
      position: event.data,
    });
  }, []);

  const handleClosePosition = useCallback((position: Position) => {
    openTicket({
      mode: 'new',
      instrumentId: position.instrumentId,
      instrumentSymbol: position.instrumentSymbol,
      side: position.quantity > 0 ? 'SELL' : 'BUY',
      quantity: Math.abs(position.quantity),
      orderType: 'MARKET',
    });
    setContextMenu(null);
  }, [openTicket]);

  const handleAddToPosition = useCallback((position: Position) => {
    openTicket({
      mode: 'new',
      instrumentId: position.instrumentId,
      instrumentSymbol: position.instrumentSymbol,
      side: position.quantity > 0 ? 'BUY' : 'SELL',
    });
    setContextMenu(null);
  }, [openTicket]);

  // Status bar showing totals
  const statusBar = useMemo(() => ({
    statusPanels: [
      {
        statusPanel: 'agTotalRowCountComponent',
        align: 'left',
      },
      {
        statusPanel: 'agAggregationComponent',
        align: 'right',
      },
    ],
  }), []);

  return (
    <div className="position-monitor">
      <PositionToolbar
        totals={totals}
        onRefresh={refetch}
        onToggleClosedPositions={() => {}}
      />

      {/* P&L Summary Bar */}
      <div className="pnl-summary-bar">
        <div className="summary-item">
          <span className="label">Unrealized</span>
          <span className={`value ${totals.unrealizedPnl >= 0 ? 'positive' : 'negative'}`}>
            {formatCurrency(totals.unrealizedPnl, 'USD')}
          </span>
        </div>
        <div className="summary-item">
          <span className="label">Realized</span>
          <span className={`value ${totals.realizedPnl >= 0 ? 'positive' : 'negative'}`}>
            {formatCurrency(totals.realizedPnl, 'USD')}
          </span>
        </div>
        <div className="summary-item total">
          <span className="label">Total P&L</span>
          <span className={`value ${totals.totalPnl >= 0 ? 'positive' : 'negative'}`}>
            {formatCurrency(totals.totalPnl, 'USD')}
          </span>
        </div>
      </div>

      <div className="ag-theme-alpine-dark grid-container">
        <AgGridReact<Position>
          ref={gridRef}
          rowData={positions}
          columnDefs={columnDefs}
          defaultColDef={defaultColDef}
          getRowId={getRowId}
          onRowClicked={onRowClicked}
          onCellContextMenu={onCellContextMenu}
          components={components}
          animateRows={true}
          rowGroupPanelShow="onlyWhenGrouping"
          groupDefaultExpanded={1}
          statusBar={statusBar}
          loading={isLoading}
        />
      </div>

      {contextMenu && (
        <PositionContextMenu
          x={contextMenu.x}
          y={contextMenu.y}
          position={contextMenu.position}
          onClose={() => handleClosePosition(contextMenu.position)}
          onAdd={() => handleAddToPosition(contextMenu.position)}
          onViewHistory={() => {}}
          onDismiss={() => setContextMenu(null)}
        />
      )}
    </div>
  );
};
```

### P&L Cell Renderer

```typescript
// src/features/positions/components/PnlCellRenderer.tsx
import React, { useEffect, useState, useRef } from 'react';
import { formatCurrency } from '../../../shared/utils/formatters';

interface PnlCellRendererProps {
  value: number;
  data: any;
  showCurrency?: boolean;
  animated?: boolean;
  bold?: boolean;
}

export const PnlCellRenderer: React.FC<PnlCellRendererProps> = ({
  value,
  data,
  showCurrency = false,
  animated = false,
  bold = false,
}) => {
  const [flash, setFlash] = useState<'up' | 'down' | null>(null);
  const prevValueRef = useRef<number | null>(null);

  useEffect(() => {
    if (animated && prevValueRef.current !== null && value !== prevValueRef.current) {
      setFlash(value > prevValueRef.current ? 'up' : 'down');
      const timer = setTimeout(() => setFlash(null), 500);
      return () => clearTimeout(timer);
    }
    prevValueRef.current = value;
  }, [value, animated]);

  if (value === null || value === undefined) {
    return <span className="pnl-empty">-</span>;
  }

  const isPositive = value >= 0;
  const formattedValue = showCurrency 
    ? formatCurrency(value, data?.pnlCurrency || 'USD')
    : value.toFixed(5);

  return (
    <span
      className={`
        pnl-cell
        ${isPositive ? 'positive' : 'negative'}
        ${flash ? `flash-${flash}` : ''}
        ${bold ? 'bold' : ''}
      `}
    >
      {isPositive && showCurrency ? '+' : ''}
      {formattedValue}
    </span>
  );
};
```

### Positions Hook

```typescript
// src/features/positions/hooks/usePositions.ts
import { useEffect, useCallback, useMemo } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { positionApi } from '../services/position.api';
import { useWebSocket } from '../../../shared/hooks/useWebSocket';
import { Position } from '../types/position.types';

interface UsePositionsOptions {
  clientId?: string;
  instrumentId?: string;
  includeClosedToday?: boolean;
}

interface PositionTotals {
  unrealizedPnl: number;
  realizedPnl: number;
  totalPnl: number;
  marketValue: number;
  positionCount: number;
}

export function usePositions(options: UsePositionsOptions = {}) {
  const queryClient = useQueryClient();
  const { includeClosedToday = false } = options;

  const queryKey = useMemo(() => 
    ['positions', options],
    [options]
  );

  const { data, isLoading, error, refetch } = useQuery({
    queryKey,
    queryFn: () => positionApi.getPositions(options),
    staleTime: 10000, // 10 seconds
    refetchInterval: 30000, // Refetch every 30s as backup
  });

  const { subscribe } = useWebSocket();

  // Handle real-time position updates
  const handlePositionUpdate = useCallback((update: {
    type: 'UPDATE' | 'CLOSE';
    position: Partial<Position>;
  }) => {
    queryClient.setQueryData<Position[]>(queryKey, (oldData = []) => {
      if (update.type === 'CLOSE') {
        if (includeClosedToday) {
          return oldData.map(p =>
            p.positionId === update.position.positionId
              ? { ...p, ...update.position, isClosed: true }
              : p
          );
        }
        return oldData.filter(p => p.positionId !== update.position.positionId);
      }

      // UPDATE
      const exists = oldData.some(p => p.positionId === update.position.positionId);
      if (exists) {
        return oldData.map(p =>
          p.positionId === update.position.positionId
            ? { ...p, ...update.position }
            : p
        );
      }
      return [...oldData, update.position as Position];
    });
  }, [queryClient, queryKey, includeClosedToday]);

  // Handle market price updates for P&L recalculation
  const handlePriceUpdate = useCallback((update: {
    instrumentId: string;
    price: number;
  }) => {
    queryClient.setQueryData<Position[]>(queryKey, (oldData = []) => {
      return oldData.map(p => {
        if (p.instrumentId !== update.instrumentId) return p;

        const marketValue = p.quantity * update.price;
        const unrealizedPnl = marketValue - p.costBasis;

        return {
          ...p,
          marketPrice: update.price,
          marketValue,
          unrealizedPnl,
        };
      });
    });
  }, [queryClient, queryKey]);

  useEffect(() => {
    const unsubPosition = subscribe('positions', handlePositionUpdate);
    const unsubPrice = subscribe('market-data', (data: any) => {
      if (data.type === 'QUOTE_UPDATE') {
        for (const quote of data.quotes) {
          handlePriceUpdate({ instrumentId: quote.id, price: quote.last });
        }
      }
    });

    return () => {
      unsubPosition();
      unsubPrice();
    };
  }, [subscribe, handlePositionUpdate, handlePriceUpdate]);

  // Calculate totals
  const totals = useMemo<PositionTotals>(() => {
    const positions = data || [];
    return {
      unrealizedPnl: positions.reduce((sum, p) => sum + (p.unrealizedPnl || 0), 0),
      realizedPnl: positions.reduce((sum, p) => sum + (p.realizedPnl || 0), 0),
      totalPnl: positions.reduce((sum, p) => 
        sum + (p.unrealizedPnl || 0) + (p.realizedPnl || 0), 0),
      marketValue: positions.reduce((sum, p) => sum + Math.abs(p.marketValue || 0), 0),
      positionCount: positions.length,
    };
  }, [data]);

  return {
    positions: data || [],
    isLoading,
    error: error as Error | null,
    totals,
    refetch,
  };
}
```

### CSS Styles

```css
/* src/features/positions/styles/position-monitor.css */

.position-monitor {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.pnl-summary-bar {
  display: flex;
  justify-content: flex-end;
  gap: 24px;
  padding: 8px 16px;
  background: var(--bg-secondary);
  border-bottom: 1px solid var(--border-color);
}

.summary-item {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.summary-item .label {
  font-size: 10px;
  text-transform: uppercase;
  color: var(--text-tertiary);
  letter-spacing: 0.5px;
}

.summary-item .value {
  font-size: 16px;
  font-weight: 600;
  font-family: var(--font-mono);
}

.summary-item.total .value {
  font-size: 20px;
}

.summary-item .value.positive {
  color: var(--success-color);
}

.summary-item .value.negative {
  color: var(--error-color);
}

/* P&L cells */
.pnl-cell {
  font-family: var(--font-mono);
}

.pnl-cell.positive {
  color: var(--success-color);
}

.pnl-cell.negative {
  color: var(--error-color);
}

.pnl-cell.bold {
  font-weight: 700;
}

.pnl-cell.flash-up {
  animation: pnl-flash-up 0.5s ease-out;
}

.pnl-cell.flash-down {
  animation: pnl-flash-down 0.5s ease-out;
}

@keyframes pnl-flash-up {
  0% { background-color: rgba(76, 175, 80, 0.4); }
  100% { background-color: transparent; }
}

@keyframes pnl-flash-down {
  0% { background-color: rgba(244, 67, 54, 0.4); }
  100% { background-color: transparent; }
}

/* Side styling */
.side-long {
  color: var(--success-color) !important;
  font-weight: 600;
}

.side-short {
  color: var(--error-color) !important;
  font-weight: 600;
}

.grid-container {
  flex: 1;
  overflow: hidden;
}
```

## Definition of Done

- [ ] Position grid with real-time updates
- [ ] P&L calculation from live prices
- [ ] P&L summary bar with totals
- [ ] Row grouping by client/book
- [ ] Context menu for close/add actions
- [ ] Position history view
- [ ] Risk limit warnings
- [ ] Column persistence
- [ ] Unit tests for hooks
- [ ] Integration tests

## Test Cases

```typescript
describe('PositionMonitor', () => {
  it('should update P&L on price change', async () => {
    render(<PositionMonitor panelId="test" />);
    
    // Initial position
    await waitFor(() => {
      expect(screen.getByText('EURUSD')).toBeInTheDocument();
    });

    // Simulate price update
    act(() => {
      emitWebSocketEvent('market-data', {
        type: 'QUOTE_UPDATE',
        quotes: [{ id: 'EURUSD', last: 1.1300 }],
      });
    });

    // P&L should update
    await waitFor(() => {
      expect(screen.getByText(/\+.*USD/)).toBeInTheDocument();
    });
  });

  it('should calculate totals correctly', () => {
    const { result } = renderHook(() => usePositions());
    
    expect(result.current.totals.totalPnl).toBe(
      result.current.totals.unrealizedPnl + result.current.totals.realizedPnl
    );
  });
});
```
