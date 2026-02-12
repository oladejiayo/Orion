# US-13-03: Trade Blotter

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-13-03 |
| **Epic** | Epic 13: Workstation UI |
| **Title** | Trade Blotter |
| **Priority** | Critical |
| **Story Points** | 8 |
| **Status** | Ready for Development |

## User Story

**As a** trader or operations user  
**I want** a real-time trade blotter showing executed trades  
**So that** I can monitor trade activity and confirm executions

## Acceptance Criteria

### AC1: Trade Display
- **Given** the trade blotter is open
- **When** trades are executed
- **Then** the blotter shows:
  - All executed trades in real-time
  - Trade details (price, quantity, counterparty)
  - P&L per trade
  - Settlement information

### AC2: Trade Grouping
- **Given** multiple trades in the blotter
- **When** I enable grouping
- **Then** I can group by:
  - Instrument
  - Client
  - Trader
  - Execution venue
  - Date

### AC3: Aggregations
- **Given** trade data in the blotter
- **When** I view the footer
- **Then** I see aggregated values:
  - Total notional traded
  - Total P&L
  - Trade count
  - Average price (when grouped)

### AC4: Trade Details
- **Given** a trade in the blotter
- **When** I double-click
- **Then** I see a detailed view with:
  - Full trade information
  - Related order details
  - Allocation breakdown
  - Audit trail

## Technical Specification

### Trade Blotter Component

```typescript
// src/features/blotter/components/TradeBlotter.tsx
import React, { useCallback, useMemo, useRef } from 'react';
import { AgGridReact } from 'ag-grid-react';
import { 
  ColDef, 
  GridReadyEvent,
  GetRowIdParams,
  ValueGetterParams,
  IAggFuncParams,
} from 'ag-grid-community';
import 'ag-grid-enterprise';

import { useTrades } from '../hooks/useTrades';
import { usePanelLink } from '../../../shared/hooks/usePanelLink';
import { Trade } from '../types/trade.types';
import { TradeDetailDialog } from './TradeDetailDialog';
import { formatCurrency, formatQuantity, formatDateTime } from '../../../shared/utils/formatters';

interface TradeBlotterProps {
  panelId: string;
  showAllocations?: boolean;
}

export const TradeBlotter: React.FC<TradeBlotterProps> = ({
  panelId,
  showAllocations = false,
}) => {
  const gridRef = useRef<AgGridReact<Trade>>(null);
  const { linkedInstrumentId, updateLinkedInstrument } = usePanelLink(panelId);
  
  const [selectedTrade, setSelectedTrade] = React.useState<Trade | null>(null);
  
  const { 
    trades, 
    isLoading, 
    connectionStatus,
  } = useTrades({
    instrumentId: linkedInstrumentId,
  });

  const columnDefs = useMemo<ColDef<Trade>[]>(() => [
    {
      headerName: 'Trade ID',
      field: 'tradeId',
      width: 120,
      pinned: 'left',
    },
    {
      headerName: 'Time',
      field: 'executionTime',
      width: 150,
      valueFormatter: (params) => formatDateTime(params.value),
      sort: 'desc',
    },
    {
      headerName: 'Instrument',
      field: 'instrumentSymbol',
      width: 100,
      enableRowGroup: true,
      rowGroup: false,
    },
    {
      headerName: 'Client',
      field: 'clientName',
      width: 150,
      enableRowGroup: true,
    },
    {
      headerName: 'Side',
      field: 'side',
      width: 70,
      cellClass: (params) => params.value === 'BUY' ? 'side-buy' : 'side-sell',
    },
    {
      headerName: 'Quantity',
      field: 'quantity',
      width: 100,
      type: 'numericColumn',
      valueFormatter: (params) => formatQuantity(params.value),
      aggFunc: 'sum',
    },
    {
      headerName: 'Price',
      field: 'executionPrice',
      width: 100,
      type: 'numericColumn',
      valueFormatter: (params) => formatCurrency(params.value, params.data?.currency),
      aggFunc: 'avg',
    },
    {
      headerName: 'Notional',
      field: 'notionalValue',
      width: 120,
      type: 'numericColumn',
      valueFormatter: (params) => formatCurrency(params.value, params.data?.currency),
      aggFunc: 'sum',
    },
    {
      headerName: 'P&L',
      field: 'realizedPnl',
      width: 100,
      type: 'numericColumn',
      valueFormatter: (params) => formatCurrency(params.value, 'USD'),
      cellClass: (params) => {
        if (!params.value) return '';
        return params.value >= 0 ? 'pnl-positive' : 'pnl-negative';
      },
      aggFunc: 'sum',
    },
    {
      headerName: 'LP',
      field: 'liquidityProvider',
      width: 100,
      enableRowGroup: true,
    },
    {
      headerName: 'Venue',
      field: 'executionVenue',
      width: 100,
      enableRowGroup: true,
    },
    {
      headerName: 'Trader',
      field: 'traderName',
      width: 120,
      enableRowGroup: true,
    },
    {
      headerName: 'Settlement',
      field: 'settlementDate',
      width: 110,
      valueFormatter: (params) => params.value ? new Date(params.value).toLocaleDateString() : '-',
    },
    {
      headerName: 'Status',
      field: 'settlementStatus',
      width: 100,
      cellRenderer: 'settlementStatusBadge',
    },
  ], []);

  const defaultColDef = useMemo<ColDef>(() => ({
    sortable: true,
    filter: true,
    resizable: true,
    floatingFilter: true,
  }), []);

  const autoGroupColumnDef = useMemo<ColDef>(() => ({
    headerName: 'Group',
    minWidth: 200,
    cellRenderer: 'agGroupCellRenderer',
  }), []);

  const getRowId = useCallback((params: GetRowIdParams<Trade>) => {
    return params.data.tradeId;
  }, []);

  const onRowDoubleClicked = useCallback((event: any) => {
    setSelectedTrade(event.data);
  }, []);

  const statusBar = useMemo(() => ({
    statusPanels: [
      {
        statusPanel: 'agTotalAndFilteredRowCountComponent',
        align: 'left',
      },
      {
        statusPanel: 'agAggregationComponent',
        align: 'right',
      },
    ],
  }), []);

  return (
    <div className="trade-blotter">
      <div className="blotter-toolbar">
        <div className="toolbar-left">
          <span className="blotter-title">Trades</span>
          <span className={`connection-status ${connectionStatus}`}>
            <span className="status-dot" />
          </span>
        </div>
        <div className="toolbar-right">
          <button
            onClick={() => gridRef.current?.api?.expandAll()}
            title="Expand All"
          >
            Expand
          </button>
          <button
            onClick={() => gridRef.current?.api?.collapseAll()}
            title="Collapse All"
          >
            Collapse
          </button>
        </div>
      </div>

      <div className="ag-theme-alpine-dark blotter-grid">
        <AgGridReact<Trade>
          ref={gridRef}
          rowData={trades}
          columnDefs={columnDefs}
          defaultColDef={defaultColDef}
          autoGroupColumnDef={autoGroupColumnDef}
          getRowId={getRowId}
          onRowDoubleClicked={onRowDoubleClicked}
          animateRows={true}
          groupDefaultExpanded={0}
          enableRangeSelection={true}
          rowGroupPanelShow="always"
          sideBar={{
            toolPanels: ['columns', 'filters'],
          }}
          statusBar={statusBar}
          suppressAggFuncInHeader={true}
          loading={isLoading}
        />
      </div>

      {selectedTrade && (
        <TradeDetailDialog
          trade={selectedTrade}
          onClose={() => setSelectedTrade(null)}
        />
      )}
    </div>
  );
};
```

### Trade Detail Dialog

```typescript
// src/features/blotter/components/TradeDetailDialog.tsx
import React from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { Cross2Icon } from '@radix-ui/react-icons';
import { Trade } from '../types/trade.types';
import { useTradeAllocations } from '../hooks/useTradeAllocations';
import { formatCurrency, formatQuantity, formatDateTime } from '../../../shared/utils/formatters';

interface TradeDetailDialogProps {
  trade: Trade;
  onClose: () => void;
}

export const TradeDetailDialog: React.FC<TradeDetailDialogProps> = ({
  trade,
  onClose,
}) => {
  const { allocations, isLoading } = useTradeAllocations(trade.tradeId);

  return (
    <Dialog.Root open={true} onOpenChange={(open) => !open && onClose()}>
      <Dialog.Portal>
        <Dialog.Overlay className="dialog-overlay" />
        <Dialog.Content className="dialog-content trade-detail-dialog">
          <Dialog.Title className="dialog-title">
            Trade Details: {trade.tradeId}
          </Dialog.Title>

          <div className="trade-detail-content">
            <section className="detail-section">
              <h3>Trade Information</h3>
              <div className="detail-grid">
                <div className="detail-row">
                  <span className="label">Instrument</span>
                  <span className="value">{trade.instrumentSymbol}</span>
                </div>
                <div className="detail-row">
                  <span className="label">Side</span>
                  <span className={`value side-${trade.side.toLowerCase()}`}>
                    {trade.side}
                  </span>
                </div>
                <div className="detail-row">
                  <span className="label">Quantity</span>
                  <span className="value">{formatQuantity(trade.quantity)}</span>
                </div>
                <div className="detail-row">
                  <span className="label">Price</span>
                  <span className="value">
                    {formatCurrency(trade.executionPrice, trade.currency)}
                  </span>
                </div>
                <div className="detail-row">
                  <span className="label">Notional</span>
                  <span className="value">
                    {formatCurrency(trade.notionalValue, trade.currency)}
                  </span>
                </div>
                <div className="detail-row">
                  <span className="label">Execution Time</span>
                  <span className="value">{formatDateTime(trade.executionTime)}</span>
                </div>
              </div>
            </section>

            <section className="detail-section">
              <h3>Counterparties</h3>
              <div className="detail-grid">
                <div className="detail-row">
                  <span className="label">Client</span>
                  <span className="value">{trade.clientName}</span>
                </div>
                <div className="detail-row">
                  <span className="label">Liquidity Provider</span>
                  <span className="value">{trade.liquidityProvider}</span>
                </div>
                <div className="detail-row">
                  <span className="label">Venue</span>
                  <span className="value">{trade.executionVenue}</span>
                </div>
                <div className="detail-row">
                  <span className="label">Trader</span>
                  <span className="value">{trade.traderName}</span>
                </div>
              </div>
            </section>

            <section className="detail-section">
              <h3>Settlement</h3>
              <div className="detail-grid">
                <div className="detail-row">
                  <span className="label">Settlement Date</span>
                  <span className="value">
                    {trade.settlementDate 
                      ? new Date(trade.settlementDate).toLocaleDateString() 
                      : 'Pending'}
                  </span>
                </div>
                <div className="detail-row">
                  <span className="label">Status</span>
                  <span className={`value status-${trade.settlementStatus.toLowerCase()}`}>
                    {trade.settlementStatus}
                  </span>
                </div>
              </div>
            </section>

            {allocations && allocations.length > 0 && (
              <section className="detail-section">
                <h3>Allocations</h3>
                <table className="allocations-table">
                  <thead>
                    <tr>
                      <th>Account</th>
                      <th>Quantity</th>
                      <th>Price</th>
                      <th>Notional</th>
                    </tr>
                  </thead>
                  <tbody>
                    {allocations.map((alloc) => (
                      <tr key={alloc.allocationId}>
                        <td>{alloc.accountName}</td>
                        <td>{formatQuantity(alloc.quantity)}</td>
                        <td>{formatCurrency(alloc.price, trade.currency)}</td>
                        <td>{formatCurrency(alloc.notional, trade.currency)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </section>
            )}

            <section className="detail-section">
              <h3>Related Order</h3>
              <div className="detail-row">
                <span className="label">Order ID</span>
                <span className="value clickable">{trade.orderId}</span>
              </div>
            </section>
          </div>

          <Dialog.Close asChild>
            <button className="dialog-close" aria-label="Close">
              <Cross2Icon />
            </button>
          </Dialog.Close>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
};
```

### Trades Hook

```typescript
// src/features/blotter/hooks/useTrades.ts
import { useEffect, useCallback, useMemo } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { tradeApi } from '../services/trade.api';
import { useWebSocket } from '../../../shared/hooks/useWebSocket';
import { Trade } from '../types/trade.types';

interface UseTradesOptions {
  instrumentId?: string | null;
  clientId?: string;
  dateFrom?: Date;
  dateTo?: Date;
}

export function useTrades(options: UseTradesOptions = {}) {
  const queryClient = useQueryClient();
  const { instrumentId, clientId, dateFrom, dateTo } = options;

  const queryKey = useMemo(() => 
    ['trades', { instrumentId, clientId, dateFrom, dateTo }],
    [instrumentId, clientId, dateFrom, dateTo]
  );

  const { data, isLoading, error, refetch } = useQuery({
    queryKey,
    queryFn: () => tradeApi.getTrades(options),
    staleTime: 30000,
  });

  const { connectionStatus, subscribe } = useWebSocket();

  const handleTradeUpdate = useCallback((update: {
    type: 'INSERT' | 'UPDATE';
    trade: Trade;
  }) => {
    queryClient.setQueryData<Trade[]>(queryKey, (oldData = []) => {
      if (update.type === 'INSERT') {
        return [update.trade, ...oldData];
      } else {
        return oldData.map(trade =>
          trade.tradeId === update.trade.tradeId
            ? { ...trade, ...update.trade }
            : trade
        );
      }
    });
  }, [queryClient, queryKey]);

  useEffect(() => {
    const unsubscribe = subscribe('trades', handleTradeUpdate);
    return () => unsubscribe();
  }, [subscribe, handleTradeUpdate]);

  return {
    trades: data?.trades || [],
    isLoading,
    error: error as Error | null,
    connectionStatus,
    totalCount: data?.totalCount || 0,
    aggregates: data?.aggregates,
    refetch,
  };
}
```

## Definition of Done

- [ ] Trade blotter with AG Grid
- [ ] Real-time WebSocket updates
- [ ] Row grouping by instrument/client/venue
- [ ] Aggregation in footer (sum, avg)
- [ ] Trade detail dialog
- [ ] Allocation breakdown display
- [ ] Column persistence
- [ ] Export functionality
- [ ] Unit tests for hooks
- [ ] Integration tests

## Test Cases

```typescript
describe('TradeBlotter', () => {
  it('should display trades with real-time updates', async () => {
    render(<TradeBlotter panelId="test" />);
    
    await waitFor(() => {
      expect(screen.getByText('TRD-001')).toBeInTheDocument();
    });

    // Simulate new trade
    act(() => {
      emitWebSocketEvent('trades', {
        type: 'INSERT',
        trade: { tradeId: 'TRD-002', instrumentSymbol: 'EURUSD' },
      });
    });

    expect(screen.getByText('TRD-002')).toBeInTheDocument();
  });

  it('should group trades by instrument', async () => {
    render(<TradeBlotter panelId="test" />);
    
    const groupButton = screen.getByText('Instrument');
    fireEvent.click(groupButton);
    
    expect(screen.getByText('EURUSD (3)')).toBeInTheDocument();
  });
});
```
