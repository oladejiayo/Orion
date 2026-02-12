# US-13-05: Market Data Grid

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-13-05 |
| **Epic** | Epic 13: Workstation UI |
| **Title** | Market Data Grid |
| **Priority** | High |
| **Story Points** | 8 |
| **Status** | Ready for Development |

## User Story

**As a** trader  
**I want** a real-time market data grid with streaming prices  
**So that** I can monitor instrument prices and market conditions

## Acceptance Criteria

### AC1: Real-Time Price Streaming
- **Given** instruments in the market data grid
- **When** prices update from the server
- **Then** the grid shows:
  - Live bid/ask prices
  - Price change indicators (tick up/down colors)
  - Last traded price
  - Daily high/low
  - Volume

### AC2: Instrument Management
- **Given** the market data grid
- **When** I manage instruments
- **Then** I can:
  - Add instruments via search
  - Remove instruments
  - Reorder instruments via drag
  - Save instrument list to workspace

### AC3: Watchlist Support
- **Given** saved watchlists
- **When** I use the grid
- **Then** I can:
  - Create and name watchlists
  - Switch between watchlists
  - Share watchlists with team

### AC4: Price Alerts
- **Given** an instrument in the grid
- **When** I set a price alert
- **Then**:
  - Alert triggers at specified price
  - Visual and audio notification
  - Alert can be recurring or one-time

### AC5: Click Trading
- **Given** an instrument row
- **When** I click bid/ask price
- **Then** the order ticket opens pre-filled

## Technical Specification

### Market Data Grid Component

```typescript
// src/features/market-data/components/MarketDataGrid.tsx
import React, { useMemo, useCallback, useRef, useEffect } from 'react';
import { AgGridReact } from 'ag-grid-react';
import { ColDef, CellClassParams, GetRowIdParams } from 'ag-grid-community';
import { useMarketDataStore } from '../stores/market-data.store';
import { useMarketDataSubscription } from '../hooks/useMarketDataSubscription';
import { usePanelLink } from '../../../shared/hooks/usePanelLink';
import { useOrderTicketStore } from '../../order-ticket/stores/order-ticket.store';
import { Quote } from '../types/market-data.types';
import { MarketDataToolbar } from './MarketDataToolbar';
import { PriceCellRenderer } from './PriceCellRenderer';
import { SparklineCellRenderer } from './SparklineCellRenderer';

interface MarketDataGridProps {
  panelId: string;
}

export const MarketDataGrid: React.FC<MarketDataGridProps> = ({ panelId }) => {
  const gridRef = useRef<AgGridReact<Quote>>(null);
  const { updateLinkedInstrument } = usePanelLink(panelId);
  const { openTicket } = useOrderTicketStore();

  const {
    activeWatchlistId,
    instruments,
    quotes,
    addInstrument,
    removeInstrument,
  } = useMarketDataStore();

  // Subscribe to real-time quotes
  const instrumentIds = useMemo(() => instruments.map(i => i.id), [instruments]);
  useMarketDataSubscription(instrumentIds);

  // Merge instruments with quotes
  const rowData = useMemo(() => {
    return instruments.map(inst => ({
      ...inst,
      ...quotes.get(inst.id),
    }));
  }, [instruments, quotes]);

  const columnDefs = useMemo<ColDef[]>(() => [
    {
      headerName: '',
      field: 'symbol',
      width: 40,
      cellRenderer: 'removeButton',
      cellRendererParams: {
        onRemove: (instrumentId: string) => removeInstrument(instrumentId),
      },
    },
    {
      headerName: 'Symbol',
      field: 'symbol',
      width: 80,
      pinned: 'left',
      cellClass: 'symbol-cell',
    },
    {
      headerName: 'Bid',
      field: 'bid',
      width: 90,
      cellRenderer: 'priceCell',
      cellRendererParams: {
        type: 'bid',
        onClick: (data: Quote) => handleBidClick(data),
      },
      cellClass: 'clickable-price bid-cell',
    },
    {
      headerName: 'Ask',
      field: 'ask',
      width: 90,
      cellRenderer: 'priceCell',
      cellRendererParams: {
        type: 'ask',
        onClick: (data: Quote) => handleAskClick(data),
      },
      cellClass: 'clickable-price ask-cell',
    },
    {
      headerName: 'Spread',
      field: 'spread',
      width: 60,
      valueGetter: (params) => {
        if (!params.data?.bid || !params.data?.ask) return null;
        return ((params.data.ask - params.data.bid) * 10000).toFixed(1);
      },
      cellClass: 'spread-cell',
    },
    {
      headerName: 'Last',
      field: 'last',
      width: 90,
      cellRenderer: 'priceCell',
      cellRendererParams: { type: 'last' },
    },
    {
      headerName: 'Chg',
      field: 'change',
      width: 70,
      valueFormatter: (params) => 
        params.value ? `${params.value >= 0 ? '+' : ''}${params.value.toFixed(4)}` : '-',
      cellClass: (params: CellClassParams) => {
        if (!params.value) return '';
        return params.value >= 0 ? 'change-positive' : 'change-negative';
      },
    },
    {
      headerName: '%',
      field: 'changePercent',
      width: 60,
      valueFormatter: (params) =>
        params.value ? `${params.value >= 0 ? '+' : ''}${params.value.toFixed(2)}%` : '-',
      cellClass: (params: CellClassParams) => {
        if (!params.value) return '';
        return params.value >= 0 ? 'change-positive' : 'change-negative';
      },
    },
    {
      headerName: 'High',
      field: 'high',
      width: 80,
      valueFormatter: (params) => params.value?.toFixed(5) || '-',
    },
    {
      headerName: 'Low',
      field: 'low',
      width: 80,
      valueFormatter: (params) => params.value?.toFixed(5) || '-',
    },
    {
      headerName: 'Volume',
      field: 'volume',
      width: 100,
      valueFormatter: (params) => 
        params.value ? params.value.toLocaleString() : '-',
    },
    {
      headerName: 'Trend',
      field: 'sparkline',
      width: 100,
      cellRenderer: 'sparklineCell',
    },
    {
      headerName: 'Time',
      field: 'timestamp',
      width: 80,
      valueFormatter: (params) => 
        params.value ? new Date(params.value).toLocaleTimeString() : '-',
    },
  ], [removeInstrument]);

  const defaultColDef = useMemo<ColDef>(() => ({
    sortable: true,
    resizable: true,
  }), []);

  const components = useMemo(() => ({
    priceCell: PriceCellRenderer,
    sparklineCell: SparklineCellRenderer,
    removeButton: RemoveButtonRenderer,
  }), []);

  const getRowId = useCallback((params: GetRowIdParams) => {
    return params.data.id;
  }, []);

  // Handle bid click - open sell order
  const handleBidClick = useCallback((data: Quote) => {
    openTicket({
      instrumentId: data.id,
      instrumentSymbol: data.symbol,
      side: 'SELL',
      price: data.bid,
    });
  }, [openTicket]);

  // Handle ask click - open buy order
  const handleAskClick = useCallback((data: Quote) => {
    openTicket({
      instrumentId: data.id,
      instrumentSymbol: data.symbol,
      side: 'BUY',
      price: data.ask,
    });
  }, [openTicket]);

  // Handle row selection for panel linking
  const onRowClicked = useCallback((event: any) => {
    updateLinkedInstrument(event.data.id);
  }, [updateLinkedInstrument]);

  // Flash cells on price update
  useEffect(() => {
    const api = gridRef.current?.api;
    if (!api) return;

    // Subscribe to quote changes and flash cells
    const unsubscribe = useMarketDataStore.subscribe(
      (state) => state.lastUpdatedInstrument,
      (instrumentId) => {
        if (instrumentId) {
          const rowNode = api.getRowNode(instrumentId);
          if (rowNode) {
            api.flashCells({
              rowNodes: [rowNode],
              columns: ['bid', 'ask', 'last'],
            });
          }
        }
      }
    );

    return unsubscribe;
  }, []);

  return (
    <div className="market-data-grid">
      <MarketDataToolbar
        panelId={panelId}
        onAddInstrument={addInstrument}
      />

      <div className="ag-theme-alpine-dark grid-container">
        <AgGridReact
          ref={gridRef}
          rowData={rowData}
          columnDefs={columnDefs}
          defaultColDef={defaultColDef}
          getRowId={getRowId}
          onRowClicked={onRowClicked}
          components={components}
          animateRows={false}
          enableCellChangeFlash={true}
          rowDragManaged={true}
          rowDragEntireRow={true}
          suppressMoveWhenRowDragging={true}
        />
      </div>
    </div>
  );
};

// Remove button cell renderer
const RemoveButtonRenderer: React.FC<any> = ({ data, onRemove }) => {
  return (
    <button
      className="remove-instrument-btn"
      onClick={(e) => {
        e.stopPropagation();
        onRemove(data.id);
      }}
      title="Remove"
    >
      ×
    </button>
  );
};
```

### Price Cell Renderer

```typescript
// src/features/market-data/components/PriceCellRenderer.tsx
import React, { useEffect, useState, useRef } from 'react';

interface PriceCellRendererProps {
  value: number;
  data: any;
  type: 'bid' | 'ask' | 'last';
  onClick?: (data: any) => void;
}

export const PriceCellRenderer: React.FC<PriceCellRendererProps> = ({
  value,
  data,
  type,
  onClick,
}) => {
  const [flash, setFlash] = useState<'up' | 'down' | null>(null);
  const prevValueRef = useRef<number | null>(null);

  useEffect(() => {
    if (prevValueRef.current !== null && value !== prevValueRef.current) {
      setFlash(value > prevValueRef.current ? 'up' : 'down');
      const timer = setTimeout(() => setFlash(null), 300);
      return () => clearTimeout(timer);
    }
    prevValueRef.current = value;
  }, [value]);

  if (!value) return <span className="price-empty">-</span>;

  const formattedPrice = formatPrice(value, data?.decimals || 5);
  const [main, pip, frac] = splitPrice(formattedPrice);

  return (
    <span
      className={`price-cell ${type} ${flash ? `flash-${flash}` : ''} ${onClick ? 'clickable' : ''}`}
      onClick={() => onClick?.(data)}
    >
      <span className="price-main">{main}</span>
      <span className="price-pip">{pip}</span>
      <span className="price-frac">{frac}</span>
    </span>
  );
};

function formatPrice(value: number, decimals: number): string {
  return value.toFixed(decimals);
}

function splitPrice(price: string): [string, string, string] {
  // Split price for emphasis (e.g., 1.12|34|5)
  const parts = price.split('.');
  if (parts.length === 1) return [parts[0], '', ''];
  
  const decimal = parts[1];
  if (decimal.length <= 2) return [parts[0] + '.', decimal, ''];
  
  return [
    parts[0] + '.' + decimal.slice(0, -2),
    decimal.slice(-2, -1),
    decimal.slice(-1),
  ];
}
```

### Market Data Store

```typescript
// src/features/market-data/stores/market-data.store.ts
import { create } from 'zustand';
import { persist } from 'zustand/middleware';

interface Instrument {
  id: string;
  symbol: string;
  name: string;
  decimals: number;
}

interface Quote {
  id: string;
  symbol: string;
  bid: number;
  ask: number;
  last: number;
  high: number;
  low: number;
  change: number;
  changePercent: number;
  volume: number;
  timestamp: Date;
  sparkline?: number[];
}

interface Watchlist {
  id: string;
  name: string;
  instrumentIds: string[];
  isDefault: boolean;
}

interface MarketDataState {
  // Watchlists
  watchlists: Watchlist[];
  activeWatchlistId: string | null;
  
  // Instruments in active watchlist
  instruments: Instrument[];
  
  // Live quotes
  quotes: Map<string, Quote>;
  lastUpdatedInstrument: string | null;

  // Actions
  addInstrument: (instrument: Instrument) => void;
  removeInstrument: (instrumentId: string) => void;
  reorderInstruments: (fromIndex: number, toIndex: number) => void;
  updateQuote: (quote: Quote) => void;
  updateQuotes: (quotes: Quote[]) => void;
  
  // Watchlist actions
  createWatchlist: (name: string) => void;
  deleteWatchlist: (watchlistId: string) => void;
  switchWatchlist: (watchlistId: string) => void;
  renameWatchlist: (watchlistId: string, name: string) => void;
}

export const useMarketDataStore = create<MarketDataState>()(
  persist(
    (set, get) => ({
      watchlists: [
        { id: 'default', name: 'Default', instrumentIds: [], isDefault: true },
      ],
      activeWatchlistId: 'default',
      instruments: [],
      quotes: new Map(),
      lastUpdatedInstrument: null,

      addInstrument: (instrument) => {
        set((state) => {
          // Check if already exists
          if (state.instruments.some(i => i.id === instrument.id)) {
            return state;
          }

          const newInstruments = [...state.instruments, instrument];
          
          // Update watchlist
          const watchlists = state.watchlists.map(w => {
            if (w.id === state.activeWatchlistId) {
              return {
                ...w,
                instrumentIds: [...w.instrumentIds, instrument.id],
              };
            }
            return w;
          });

          return {
            instruments: newInstruments,
            watchlists,
          };
        });
      },

      removeInstrument: (instrumentId) => {
        set((state) => {
          const newInstruments = state.instruments.filter(i => i.id !== instrumentId);
          
          const watchlists = state.watchlists.map(w => {
            if (w.id === state.activeWatchlistId) {
              return {
                ...w,
                instrumentIds: w.instrumentIds.filter(id => id !== instrumentId),
              };
            }
            return w;
          });

          return {
            instruments: newInstruments,
            watchlists,
          };
        });
      },

      reorderInstruments: (fromIndex, toIndex) => {
        set((state) => {
          const newInstruments = [...state.instruments];
          const [removed] = newInstruments.splice(fromIndex, 1);
          newInstruments.splice(toIndex, 0, removed);
          return { instruments: newInstruments };
        });
      },

      updateQuote: (quote) => {
        set((state) => {
          const newQuotes = new Map(state.quotes);
          const existing = newQuotes.get(quote.id);
          
          // Add to sparkline history
          const sparkline = existing?.sparkline || [];
          const newSparkline = [...sparkline.slice(-29), quote.last];
          
          newQuotes.set(quote.id, { ...quote, sparkline: newSparkline });
          
          return {
            quotes: newQuotes,
            lastUpdatedInstrument: quote.id,
          };
        });
      },

      updateQuotes: (quotes) => {
        set((state) => {
          const newQuotes = new Map(state.quotes);
          
          for (const quote of quotes) {
            const existing = newQuotes.get(quote.id);
            const sparkline = existing?.sparkline || [];
            const newSparkline = [...sparkline.slice(-29), quote.last];
            newQuotes.set(quote.id, { ...quote, sparkline: newSparkline });
          }
          
          return { quotes: newQuotes };
        });
      },

      createWatchlist: (name) => {
        const id = crypto.randomUUID();
        set((state) => ({
          watchlists: [
            ...state.watchlists,
            { id, name, instrumentIds: [], isDefault: false },
          ],
          activeWatchlistId: id,
          instruments: [],
        }));
      },

      deleteWatchlist: (watchlistId) => {
        set((state) => {
          const watchlist = state.watchlists.find(w => w.id === watchlistId);
          if (watchlist?.isDefault) return state;

          const newWatchlists = state.watchlists.filter(w => w.id !== watchlistId);
          const newActiveId = state.activeWatchlistId === watchlistId
            ? newWatchlists.find(w => w.isDefault)?.id || newWatchlists[0]?.id
            : state.activeWatchlistId;

          return {
            watchlists: newWatchlists,
            activeWatchlistId: newActiveId,
          };
        });
      },

      switchWatchlist: (watchlistId) => {
        set((state) => {
          const watchlist = state.watchlists.find(w => w.id === watchlistId);
          if (!watchlist) return state;

          // Load instruments for this watchlist
          // In production, would fetch instrument details from API
          return {
            activeWatchlistId: watchlistId,
            instruments: [], // Would load from API
          };
        });
      },

      renameWatchlist: (watchlistId, name) => {
        set((state) => ({
          watchlists: state.watchlists.map(w =>
            w.id === watchlistId ? { ...w, name } : w
          ),
        }));
      },
    }),
    {
      name: 'orion-market-data',
      partialize: (state) => ({
        watchlists: state.watchlists,
        activeWatchlistId: state.activeWatchlistId,
      }),
    }
  )
);
```

### Market Data WebSocket Subscription

```typescript
// src/features/market-data/hooks/useMarketDataSubscription.ts
import { useEffect, useRef } from 'react';
import { useWebSocket } from '../../../shared/hooks/useWebSocket';
import { useMarketDataStore } from '../stores/market-data.store';

export function useMarketDataSubscription(instrumentIds: string[]) {
  const { subscribe, unsubscribe, send } = useWebSocket();
  const { updateQuotes } = useMarketDataStore();
  const subscriptionRef = useRef<string | null>(null);

  useEffect(() => {
    if (instrumentIds.length === 0) return;

    // Subscribe to market data channel
    const handleQuoteUpdate = (data: any) => {
      if (data.type === 'QUOTE_UPDATE') {
        updateQuotes(data.quotes);
      }
    };

    subscriptionRef.current = subscribe('market-data', handleQuoteUpdate);

    // Send subscription request
    send('market-data', {
      action: 'SUBSCRIBE',
      instrumentIds,
    });

    return () => {
      // Unsubscribe on cleanup
      if (subscriptionRef.current) {
        send('market-data', {
          action: 'UNSUBSCRIBE',
          instrumentIds,
        });
        unsubscribe(subscriptionRef.current);
      }
    };
  }, [instrumentIds.join(',')]); // Re-subscribe when instruments change
}
```

### CSS Styles

```css
/* src/features/market-data/styles/market-data-grid.css */

.market-data-grid {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.grid-container {
  flex: 1;
  overflow: hidden;
}

/* Price cell styling */
.price-cell {
  display: inline-flex;
  align-items: baseline;
  font-family: var(--font-mono);
  font-size: 13px;
}

.price-cell.clickable {
  cursor: pointer;
}

.price-cell.clickable:hover {
  text-decoration: underline;
}

.price-main {
  color: var(--text-secondary);
}

.price-pip {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
}

.price-frac {
  font-size: 10px;
  color: var(--text-secondary);
  vertical-align: super;
}

/* Bid/Ask colors */
.bid-cell .price-pip {
  color: var(--error-color);
}

.ask-cell .price-pip {
  color: var(--success-color);
}

/* Flash animation */
.price-cell.flash-up {
  animation: flash-up 0.3s ease-out;
}

.price-cell.flash-down {
  animation: flash-down 0.3s ease-out;
}

@keyframes flash-up {
  0% { background-color: rgba(76, 175, 80, 0.5); }
  100% { background-color: transparent; }
}

@keyframes flash-down {
  0% { background-color: rgba(244, 67, 54, 0.5); }
  100% { background-color: transparent; }
}

/* Change columns */
.change-positive {
  color: var(--success-color) !important;
}

.change-negative {
  color: var(--error-color) !important;
}

/* Symbol cell */
.symbol-cell {
  font-weight: 600;
}

/* Spread cell */
.spread-cell {
  color: var(--text-secondary);
  font-size: 11px;
}

/* Remove button */
.remove-instrument-btn {
  width: 20px;
  height: 20px;
  border: none;
  background: transparent;
  color: var(--text-tertiary);
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s;
}

.ag-row:hover .remove-instrument-btn {
  opacity: 1;
}

.remove-instrument-btn:hover {
  color: var(--error-color);
}

/* Sparkline */
.sparkline-container {
  height: 24px;
  width: 100%;
}

.sparkline-line {
  stroke: var(--accent-color);
  stroke-width: 1.5;
  fill: none;
}
```

## Definition of Done

- [ ] Real-time price streaming via WebSocket
- [ ] Price tick animations (flash on update)
- [ ] Click-to-trade (bid/ask opens order ticket)
- [ ] Instrument add/remove
- [ ] Watchlist management
- [ ] Row drag reordering
- [ ] Column persistence
- [ ] Sparkline trend display
- [ ] Unit tests for store
- [ ] Integration tests for WebSocket

## Test Cases

```typescript
describe('MarketDataGrid', () => {
  it('should display live quotes', async () => {
    render(<MarketDataGrid panelId="test" />);
    
    act(() => {
      emitWebSocketEvent('market-data', {
        type: 'QUOTE_UPDATE',
        quotes: [{ id: 'EURUSD', bid: 1.1234, ask: 1.1236 }],
      });
    });

    expect(screen.getByText('1.1234')).toBeInTheDocument();
  });

  it('should open order ticket on price click', async () => {
    render(<MarketDataGrid panelId="test" />);
    
    const askPrice = screen.getByText('1.1236');
    fireEvent.click(askPrice);
    
    expect(useOrderTicketStore.getState().isOpen).toBe(true);
    expect(useOrderTicketStore.getState().data.side).toBe('BUY');
  });
});
```
