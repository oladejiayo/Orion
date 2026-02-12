# US-13-02: Order Blotter

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-13-02 |
| **Epic** | Epic 13: Workstation UI |
| **Title** | Order Blotter |
| **Priority** | Critical |
| **Story Points** | 13 |
| **Status** | Ready for Development |

## User Story

**As a** trader  
**I want** a real-time order blotter with advanced grid features  
**So that** I can monitor and manage all my orders efficiently

## Acceptance Criteria

### AC1: Real-Time Order Display
- **Given** the order blotter is open
- **When** orders are created, updated, or cancelled
- **Then** the blotter:
  - Updates in real-time via WebSocket
  - Highlights changed rows briefly
  - Maintains scroll position during updates
  - Shows accurate order counts

### AC2: Column Customization
- **Given** the order blotter grid
- **When** I customize columns
- **Then** I can:
  - Show/hide columns via context menu
  - Reorder columns via drag-and-drop
  - Resize columns
  - Pin columns left/right
  - Persist column configuration

### AC3: Filtering and Sorting
- **Given** orders in the blotter
- **When** I apply filters
- **Then** I can:
  - Filter by any column
  - Apply multiple filters simultaneously
  - Use quick filters (Today, Open Orders, etc.)
  - Sort by clicking column headers
  - Clear all filters with one click

### AC4: Order Actions
- **Given** an order row in the blotter
- **When** I interact with it
- **Then** I can:
  - Double-click to open order ticket for amendment
  - Right-click for context menu actions
  - Cancel order from context menu
  - View order history/audit trail
  - Clone order to new ticket

### AC5: Export Functionality
- **Given** filtered order data
- **When** I export
- **Then** I can:
  - Export to Excel with current filters
  - Export to CSV
  - Copy selected rows to clipboard
  - Print blotter view

## Technical Specification

### Order Blotter Component

```typescript
// src/features/blotter/components/OrderBlotter.tsx
import React, { useCallback, useMemo, useRef, useEffect } from 'react';
import { AgGridReact } from 'ag-grid-react';
import { 
  ColDef, 
  GridReadyEvent,
  GetRowIdParams,
  GridApi,
  RowClassRules,
  CellClassRules,
  ValueFormatterParams,
  FilterChangedEvent,
  SortChangedEvent,
  ColumnMovedEvent,
} from 'ag-grid-community';
import 'ag-grid-enterprise';
import 'ag-grid-community/styles/ag-grid.css';
import 'ag-grid-community/styles/ag-theme-alpine.css';

import { useOrders } from '../hooks/useOrders';
import { useOrderActions } from '../hooks/useOrderActions';
import { useGridConfig } from '../hooks/useGridConfig';
import { usePanelLink } from '../../../shared/hooks/usePanelLink';
import { Order, OrderStatus, OrderSide } from '../types/order.types';
import { BlotterToolbar } from './BlotterToolbar';
import { OrderContextMenu } from './OrderContextMenu';
import { formatCurrency, formatQuantity, formatDateTime } from '../../../shared/utils/formatters';

interface OrderBlotterProps {
  panelId: string;
  defaultFilter?: 'all' | 'open' | 'today' | 'filled';
}

export const OrderBlotter: React.FC<OrderBlotterProps> = ({ 
  panelId, 
  defaultFilter = 'open' 
}) => {
  const gridRef = useRef<AgGridReact<Order>>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  
  const { linkedInstrumentId, updateLinkedInstrument } = usePanelLink(panelId);
  
  const { 
    orders, 
    isLoading, 
    connectionStatus,
    totalCount,
  } = useOrders({ 
    filter: defaultFilter,
    instrumentId: linkedInstrumentId,
  });

  const { 
    cancelOrder, 
    amendOrder, 
    cloneOrder,
    viewOrderHistory,
  } = useOrderActions();

  const { 
    columnState, 
    saveColumnState, 
    loadColumnState,
  } = useGridConfig('order-blotter');

  // Context menu state
  const [contextMenu, setContextMenu] = React.useState<{
    x: number;
    y: number;
    order: Order;
  } | null>(null);

  // Column definitions
  const columnDefs = useMemo<ColDef<Order>[]>(() => [
    {
      headerName: '',
      field: 'status',
      width: 40,
      pinned: 'left',
      cellRenderer: 'statusIndicator',
      filter: false,
      sortable: false,
    },
    {
      headerName: 'Order ID',
      field: 'orderId',
      width: 120,
      pinned: 'left',
      cellRenderer: 'orderIdCell',
    },
    {
      headerName: 'Client',
      field: 'clientName',
      width: 150,
      filter: 'agTextColumnFilter',
    },
    {
      headerName: 'Instrument',
      field: 'instrumentSymbol',
      width: 100,
      filter: 'agSetColumnFilter',
    },
    {
      headerName: 'Side',
      field: 'side',
      width: 70,
      cellClass: (params) => params.value === 'BUY' ? 'side-buy' : 'side-sell',
      filter: 'agSetColumnFilter',
    },
    {
      headerName: 'Type',
      field: 'orderType',
      width: 80,
      filter: 'agSetColumnFilter',
    },
    {
      headerName: 'Quantity',
      field: 'quantity',
      width: 100,
      type: 'numericColumn',
      valueFormatter: (params) => formatQuantity(params.value),
      filter: 'agNumberColumnFilter',
    },
    {
      headerName: 'Filled',
      field: 'filledQuantity',
      width: 100,
      type: 'numericColumn',
      valueFormatter: (params) => formatQuantity(params.value),
      cellClass: (params) => {
        if (params.data?.filledQuantity === params.data?.quantity) return 'fully-filled';
        if (params.data?.filledQuantity > 0) return 'partially-filled';
        return '';
      },
    },
    {
      headerName: 'Price',
      field: 'price',
      width: 100,
      type: 'numericColumn',
      valueFormatter: (params) => params.value ? formatCurrency(params.value, params.data?.currency) : 'MKT',
    },
    {
      headerName: 'Avg Fill',
      field: 'averageFillPrice',
      width: 100,
      type: 'numericColumn',
      valueFormatter: (params) => params.value ? formatCurrency(params.value, params.data?.currency) : '-',
    },
    {
      headerName: 'Status',
      field: 'status',
      width: 100,
      filter: 'agSetColumnFilter',
      cellRenderer: 'statusBadge',
    },
    {
      headerName: 'Time',
      field: 'createdAt',
      width: 160,
      valueFormatter: (params) => formatDateTime(params.value),
      filter: 'agDateColumnFilter',
      sort: 'desc',
    },
    {
      headerName: 'Updated',
      field: 'updatedAt',
      width: 160,
      valueFormatter: (params) => formatDateTime(params.value),
      hide: true,
    },
    {
      headerName: 'TIF',
      field: 'timeInForce',
      width: 70,
      filter: 'agSetColumnFilter',
    },
    {
      headerName: 'LP',
      field: 'liquidityProvider',
      width: 100,
      filter: 'agSetColumnFilter',
      hide: true,
    },
    {
      headerName: 'Notes',
      field: 'notes',
      width: 200,
      hide: true,
    },
  ], []);

  // Default column properties
  const defaultColDef = useMemo<ColDef>(() => ({
    sortable: true,
    filter: true,
    resizable: true,
    floatingFilter: true,
    menuTabs: ['filterMenuTab', 'generalMenuTab', 'columnsMenuTab'],
  }), []);

  // Row class rules for status highlighting
  const rowClassRules = useMemo<RowClassRules<Order>>(() => ({
    'row-cancelled': (params) => params.data?.status === 'CANCELLED',
    'row-rejected': (params) => params.data?.status === 'REJECTED',
    'row-filled': (params) => params.data?.status === 'FILLED',
    'row-updated': (params) => {
      // Highlight recently updated rows
      const updatedAt = new Date(params.data?.updatedAt || 0);
      return Date.now() - updatedAt.getTime() < 3000;
    },
  }), []);

  // Get row ID for delta updates
  const getRowId = useCallback((params: GetRowIdParams<Order>) => {
    return params.data.orderId;
  }, []);

  // Handle grid ready
  const onGridReady = useCallback((params: GridReadyEvent) => {
    // Load saved column state
    if (columnState) {
      params.columnApi.applyColumnState({
        state: columnState,
        applyOrder: true,
      });
    }
  }, [columnState]);

  // Handle column changes
  const onColumnChanged = useCallback((event: ColumnMovedEvent) => {
    const state = event.columnApi?.getColumnState();
    if (state) {
      saveColumnState(state);
    }
  }, [saveColumnState]);

  // Handle row double-click
  const onRowDoubleClicked = useCallback((event: any) => {
    const order = event.data as Order;
    if (order.status === 'OPEN' || order.status === 'PARTIALLY_FILLED') {
      amendOrder(order);
    } else {
      cloneOrder(order);
    }
  }, [amendOrder, cloneOrder]);

  // Handle context menu
  const onCellContextMenu = useCallback((event: any) => {
    event.event.preventDefault();
    setContextMenu({
      x: event.event.clientX,
      y: event.event.clientY,
      order: event.data,
    });
  }, []);

  // Handle instrument selection for linking
  const onCellClicked = useCallback((event: any) => {
    if (event.column.getColId() === 'instrumentSymbol') {
      updateLinkedInstrument(event.data.instrumentId);
    }
  }, [updateLinkedInstrument]);

  // Close context menu on click outside
  useEffect(() => {
    const handleClick = () => setContextMenu(null);
    document.addEventListener('click', handleClick);
    return () => document.removeEventListener('click', handleClick);
  }, []);

  // Custom components
  const components = useMemo(() => ({
    statusIndicator: StatusIndicatorCell,
    statusBadge: StatusBadgeCell,
    orderIdCell: OrderIdCell,
  }), []);

  return (
    <div className="order-blotter" ref={containerRef}>
      <BlotterToolbar
        gridRef={gridRef}
        totalCount={totalCount}
        connectionStatus={connectionStatus}
        onQuickFilter={(filter) => {
          // Apply quick filter
        }}
      />

      <div className="ag-theme-alpine-dark blotter-grid">
        <AgGridReact<Order>
          ref={gridRef}
          rowData={orders}
          columnDefs={columnDefs}
          defaultColDef={defaultColDef}
          rowClassRules={rowClassRules}
          getRowId={getRowId}
          onGridReady={onGridReady}
          onColumnMoved={onColumnChanged}
          onColumnResized={onColumnChanged}
          onColumnVisible={onColumnChanged}
          onRowDoubleClicked={onRowDoubleClicked}
          onCellContextMenu={onCellContextMenu}
          onCellClicked={onCellClicked}
          animateRows={true}
          enableRangeSelection={true}
          enableRangeHandle={true}
          suppressRowClickSelection={true}
          rowSelection="multiple"
          components={components}
          loading={isLoading}
          overlayLoadingTemplate='<span class="loading-overlay">Loading orders...</span>'
          overlayNoRowsTemplate='<span class="no-rows-overlay">No orders to display</span>'
        />
      </div>

      {contextMenu && (
        <OrderContextMenu
          x={contextMenu.x}
          y={contextMenu.y}
          order={contextMenu.order}
          onCancel={() => {
            cancelOrder(contextMenu.order.orderId);
            setContextMenu(null);
          }}
          onAmend={() => {
            amendOrder(contextMenu.order);
            setContextMenu(null);
          }}
          onClone={() => {
            cloneOrder(contextMenu.order);
            setContextMenu(null);
          }}
          onViewHistory={() => {
            viewOrderHistory(contextMenu.order.orderId);
            setContextMenu(null);
          }}
          onClose={() => setContextMenu(null)}
        />
      )}
    </div>
  );
};
```

### Orders Hook with WebSocket

```typescript
// src/features/blotter/hooks/useOrders.ts
import { useEffect, useCallback, useMemo } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { orderApi } from '../services/order.api';
import { useWebSocket } from '../../../shared/hooks/useWebSocket';
import { Order } from '../types/order.types';

interface UseOrdersOptions {
  filter?: 'all' | 'open' | 'today' | 'filled';
  instrumentId?: string | null;
  clientId?: string;
}

interface UseOrdersResult {
  orders: Order[];
  isLoading: boolean;
  error: Error | null;
  connectionStatus: 'connected' | 'disconnected' | 'connecting';
  totalCount: number;
  refetch: () => void;
}

export function useOrders(options: UseOrdersOptions = {}): UseOrdersResult {
  const queryClient = useQueryClient();
  const { filter = 'open', instrumentId, clientId } = options;

  // Build query key
  const queryKey = useMemo(() => 
    ['orders', { filter, instrumentId, clientId }],
    [filter, instrumentId, clientId]
  );

  // Initial fetch
  const { 
    data, 
    isLoading, 
    error,
    refetch,
  } = useQuery({
    queryKey,
    queryFn: () => orderApi.getOrders({ filter, instrumentId, clientId }),
    staleTime: 30000, // 30 seconds
    refetchOnWindowFocus: false,
  });

  // WebSocket subscription
  const { connectionStatus, subscribe } = useWebSocket();

  // Handle real-time updates
  const handleOrderUpdate = useCallback((update: {
    type: 'INSERT' | 'UPDATE' | 'DELETE';
    order: Order;
  }) => {
    queryClient.setQueryData<Order[]>(queryKey, (oldData = []) => {
      switch (update.type) {
        case 'INSERT':
          // Check if order matches current filter
          if (!matchesFilter(update.order, options)) {
            return oldData;
          }
          return [update.order, ...oldData];

        case 'UPDATE':
          return oldData.map(order => 
            order.orderId === update.order.orderId 
              ? { ...order, ...update.order }
              : order
          );

        case 'DELETE':
          return oldData.filter(order => 
            order.orderId !== update.order.orderId
          );

        default:
          return oldData;
      }
    });
  }, [queryClient, queryKey, options]);

  // Subscribe to order events
  useEffect(() => {
    const unsubscribe = subscribe('orders', handleOrderUpdate);
    return () => unsubscribe();
  }, [subscribe, handleOrderUpdate]);

  return {
    orders: data?.orders || [],
    isLoading,
    error: error as Error | null,
    connectionStatus,
    totalCount: data?.totalCount || 0,
    refetch,
  };
}

function matchesFilter(order: Order, options: UseOrdersOptions): boolean {
  const { filter, instrumentId, clientId } = options;

  if (instrumentId && order.instrumentId !== instrumentId) {
    return false;
  }

  if (clientId && order.clientId !== clientId) {
    return false;
  }

  switch (filter) {
    case 'open':
      return ['NEW', 'OPEN', 'PARTIALLY_FILLED'].includes(order.status);
    case 'today':
      const today = new Date().toDateString();
      return new Date(order.createdAt).toDateString() === today;
    case 'filled':
      return order.status === 'FILLED';
    case 'all':
    default:
      return true;
  }
}
```

### Order Actions Hook

```typescript
// src/features/blotter/hooks/useOrderActions.ts
import { useCallback } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { orderApi } from '../services/order.api';
import { Order } from '../types/order.types';
import { useOrderTicketStore } from '../../order-ticket/stores/order-ticket.store';
import { useNotification } from '../../../shared/hooks/useNotification';
import { useDialog } from '../../../shared/hooks/useDialog';

export function useOrderActions() {
  const queryClient = useQueryClient();
  const { openTicket } = useOrderTicketStore();
  const { notify } = useNotification();
  const { confirm } = useDialog();

  // Cancel order mutation
  const cancelMutation = useMutation({
    mutationFn: (orderId: string) => orderApi.cancelOrder(orderId),
    onSuccess: (_, orderId) => {
      queryClient.invalidateQueries({ queryKey: ['orders'] });
      notify({
        type: 'success',
        title: 'Order Cancelled',
        message: `Order ${orderId} has been cancelled`,
      });
    },
    onError: (error: Error, orderId) => {
      notify({
        type: 'error',
        title: 'Cancel Failed',
        message: error.message,
      });
    },
  });

  // Cancel order with confirmation
  const cancelOrder = useCallback(async (orderId: string) => {
    const confirmed = await confirm({
      title: 'Cancel Order',
      message: `Are you sure you want to cancel order ${orderId}?`,
      confirmText: 'Cancel Order',
      cancelText: 'Keep Order',
      variant: 'danger',
    });

    if (confirmed) {
      cancelMutation.mutate(orderId);
    }
  }, [confirm, cancelMutation]);

  // Open order ticket for amendment
  const amendOrder = useCallback((order: Order) => {
    if (!['NEW', 'OPEN', 'PARTIALLY_FILLED'].includes(order.status)) {
      notify({
        type: 'warning',
        title: 'Cannot Amend',
        message: 'Only open orders can be amended',
      });
      return;
    }

    openTicket({
      mode: 'amend',
      orderId: order.orderId,
      instrumentId: order.instrumentId,
      side: order.side,
      quantity: order.quantity - order.filledQuantity, // Remaining quantity
      price: order.price,
      orderType: order.orderType,
      timeInForce: order.timeInForce,
    });
  }, [openTicket, notify]);

  // Clone order to new ticket
  const cloneOrder = useCallback((order: Order) => {
    openTicket({
      mode: 'new',
      instrumentId: order.instrumentId,
      side: order.side,
      quantity: order.quantity,
      price: order.price,
      orderType: order.orderType,
      timeInForce: order.timeInForce,
      clientId: order.clientId,
    });
  }, [openTicket]);

  // View order history
  const viewOrderHistory = useCallback((orderId: string) => {
    // Open order history dialog
    // Implementation depends on dialog system
  }, []);

  return {
    cancelOrder,
    amendOrder,
    cloneOrder,
    viewOrderHistory,
    isCancelling: cancelMutation.isPending,
  };
}
```

### Blotter Toolbar Component

```typescript
// src/features/blotter/components/BlotterToolbar.tsx
import React from 'react';
import { RefObject } from 'react';
import { AgGridReact } from 'ag-grid-react';
import { 
  DownloadIcon, 
  ReloadIcon, 
  MagnifyingGlassIcon,
  Cross2Icon,
} from '@radix-ui/react-icons';
import { Order } from '../types/order.types';
import { exportToExcel, exportToCsv } from '../../../shared/utils/export';

interface BlotterToolbarProps {
  gridRef: RefObject<AgGridReact<Order>>;
  totalCount: number;
  connectionStatus: 'connected' | 'disconnected' | 'connecting';
  onQuickFilter: (filter: string) => void;
}

export const BlotterToolbar: React.FC<BlotterToolbarProps> = ({
  gridRef,
  totalCount,
  connectionStatus,
  onQuickFilter,
}) => {
  const [searchText, setSearchText] = React.useState('');

  const handleExportExcel = () => {
    const api = gridRef.current?.api;
    if (!api) return;

    const data = api.getDataAsCsv({
      allColumns: true,
      skipColumnGroupHeaders: true,
    });
    
    exportToExcel(data, 'orders.xlsx');
  };

  const handleExportCsv = () => {
    gridRef.current?.api?.exportDataAsCsv({
      fileName: 'orders.csv',
    });
  };

  const handleSearch = (text: string) => {
    setSearchText(text);
    gridRef.current?.api?.setQuickFilter(text);
  };

  const handleClearFilters = () => {
    gridRef.current?.api?.setFilterModel(null);
    setSearchText('');
    gridRef.current?.api?.setQuickFilter('');
  };

  const handleRefresh = () => {
    // Trigger refetch
  };

  return (
    <div className="blotter-toolbar">
      <div className="toolbar-left">
        <div className="quick-filters">
          <button onClick={() => onQuickFilter('open')} className="active">
            Open
          </button>
          <button onClick={() => onQuickFilter('today')}>
            Today
          </button>
          <button onClick={() => onQuickFilter('filled')}>
            Filled
          </button>
          <button onClick={() => onQuickFilter('all')}>
            All
          </button>
        </div>

        <div className="search-box">
          <MagnifyingGlassIcon className="search-icon" />
          <input
            type="text"
            value={searchText}
            onChange={(e) => handleSearch(e.target.value)}
            placeholder="Quick search..."
          />
          {searchText && (
            <button onClick={() => handleSearch('')} className="clear-btn">
              <Cross2Icon />
            </button>
          )}
        </div>
      </div>

      <div className="toolbar-center">
        <span className="order-count">
          {totalCount} orders
        </span>
        <span className={`connection-status ${connectionStatus}`}>
          <span className="status-dot" />
          {connectionStatus}
        </span>
      </div>

      <div className="toolbar-right">
        <button onClick={handleClearFilters} title="Clear Filters">
          Clear Filters
        </button>
        <button onClick={handleRefresh} title="Refresh">
          <ReloadIcon />
        </button>
        <div className="export-dropdown">
          <button title="Export">
            <DownloadIcon />
            Export
          </button>
          <div className="dropdown-menu">
            <button onClick={handleExportExcel}>Export to Excel</button>
            <button onClick={handleExportCsv}>Export to CSV</button>
          </div>
        </div>
      </div>
    </div>
  );
};
```

### Order Context Menu

```typescript
// src/features/blotter/components/OrderContextMenu.tsx
import React from 'react';
import * as ContextMenu from '@radix-ui/react-context-menu';
import { Order } from '../types/order.types';

interface OrderContextMenuProps {
  x: number;
  y: number;
  order: Order;
  onCancel: () => void;
  onAmend: () => void;
  onClone: () => void;
  onViewHistory: () => void;
  onClose: () => void;
}

export const OrderContextMenu: React.FC<OrderContextMenuProps> = ({
  x,
  y,
  order,
  onCancel,
  onAmend,
  onClone,
  onViewHistory,
  onClose,
}) => {
  const canCancel = ['NEW', 'OPEN', 'PARTIALLY_FILLED'].includes(order.status);
  const canAmend = ['NEW', 'OPEN', 'PARTIALLY_FILLED'].includes(order.status);

  return (
    <div
      className="order-context-menu"
      style={{ left: x, top: y }}
    >
      <div className="context-menu-header">
        Order: {order.orderId}
      </div>
      
      <div className="context-menu-items">
        <button onClick={onAmend} disabled={!canAmend}>
          {canAmend ? 'Amend Order' : 'View Order'}
        </button>
        
        <button onClick={onClone}>
          Clone Order
        </button>
        
        <div className="separator" />
        
        <button onClick={onViewHistory}>
          View History
        </button>
        
        <div className="separator" />
        
        <button 
          onClick={onCancel} 
          disabled={!canCancel}
          className="danger"
        >
          Cancel Order
        </button>
      </div>
    </div>
  );
};
```

### CSS Styles

```css
/* src/features/blotter/styles/order-blotter.css */

.order-blotter {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--bg-primary);
}

.blotter-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: var(--bg-secondary);
  border-bottom: 1px solid var(--border-color);
  gap: 16px;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.quick-filters {
  display: flex;
  gap: 4px;
}

.quick-filters button {
  padding: 4px 12px;
  background: transparent;
  border: 1px solid var(--border-color);
  border-radius: 4px;
  font-size: 12px;
  cursor: pointer;
}

.quick-filters button:hover {
  background: var(--bg-tertiary);
}

.quick-filters button.active {
  background: var(--accent-color);
  border-color: var(--accent-color);
  color: white;
}

.search-box {
  display: flex;
  align-items: center;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 4px;
  padding: 4px 8px;
}

.search-box input {
  border: none;
  background: transparent;
  outline: none;
  width: 200px;
  font-size: 13px;
}

.blotter-grid {
  flex: 1;
  overflow: hidden;
}

/* Status colors */
.side-buy {
  color: var(--success-color) !important;
}

.side-sell {
  color: var(--error-color) !important;
}

.row-filled {
  background-color: rgba(76, 175, 80, 0.1) !important;
}

.row-cancelled {
  background-color: rgba(158, 158, 158, 0.1) !important;
  color: var(--text-secondary) !important;
}

.row-rejected {
  background-color: rgba(244, 67, 54, 0.1) !important;
}

.row-updated {
  animation: row-highlight 3s ease-out;
}

@keyframes row-highlight {
  0% {
    background-color: rgba(255, 235, 59, 0.3);
  }
  100% {
    background-color: transparent;
  }
}

.fully-filled {
  color: var(--success-color);
}

.partially-filled {
  color: var(--warning-color);
}

/* Context menu */
.order-context-menu {
  position: fixed;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  box-shadow: var(--shadow-lg);
  min-width: 180px;
  z-index: 1000;
}

.context-menu-header {
  padding: 8px 12px;
  font-size: 12px;
  color: var(--text-secondary);
  border-bottom: 1px solid var(--border-color);
}

.context-menu-items button {
  display: block;
  width: 100%;
  padding: 8px 12px;
  text-align: left;
  background: transparent;
  border: none;
  cursor: pointer;
  font-size: 13px;
}

.context-menu-items button:hover {
  background: var(--bg-secondary);
}

.context-menu-items button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.context-menu-items button.danger {
  color: var(--error-color);
}

.separator {
  height: 1px;
  background: var(--border-color);
  margin: 4px 0;
}

/* Connection status */
.connection-status {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.connection-status.connected .status-dot {
  background: var(--success-color);
}

.connection-status.disconnected .status-dot {
  background: var(--error-color);
}

.connection-status.connecting .status-dot {
  background: var(--warning-color);
  animation: pulse 1s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}
```

## Definition of Done

- [ ] AG Grid integration with virtual scrolling
- [ ] Real-time WebSocket updates working
- [ ] Row highlighting on updates
- [ ] Column customization and persistence
- [ ] Quick filters (Open, Today, Filled, All)
- [ ] Search/filter functionality
- [ ] Context menu actions
- [ ] Double-click to amend
- [ ] Excel/CSV export
- [ ] Unit tests for hooks
- [ ] Integration tests for blotter

## Test Cases

### Unit Tests
```typescript
describe('useOrders', () => {
  it('should fetch orders with filter', async () => {
    const { result } = renderHook(() => useOrders({ filter: 'open' }));
    
    await waitFor(() => {
      expect(result.current.isLoading).toBe(false);
    });
    
    expect(result.current.orders).toHaveLength(mockOrders.length);
  });

  it('should handle WebSocket updates', async () => {
    const { result } = renderHook(() => useOrders());
    
    act(() => {
      emitWebSocketEvent('orders', {
        type: 'UPDATE',
        order: { orderId: '123', status: 'FILLED' },
      });
    });
    
    expect(result.current.orders.find(o => o.orderId === '123')?.status).toBe('FILLED');
  });
});

describe('useOrderActions', () => {
  it('should cancel order after confirmation', async () => {
    const { result } = renderHook(() => useOrderActions());
    
    mockConfirm.mockResolvedValue(true);
    
    await act(async () => {
      await result.current.cancelOrder('order-123');
    });
    
    expect(orderApi.cancelOrder).toHaveBeenCalledWith('order-123');
  });
});
```
