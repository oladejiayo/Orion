# US-13-04: Order Ticket

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-13-04 |
| **Epic** | Epic 13: Workstation UI |
| **Title** | Order Ticket |
| **Priority** | Critical |
| **Story Points** | 13 |
| **Status** | Ready for Development |

## User Story

**As a** trader  
**I want** a comprehensive order entry ticket  
**So that** I can quickly and accurately submit orders with full validation

## Acceptance Criteria

### AC1: Instrument Selection
- **Given** the order ticket is open
- **When** I search for an instrument
- **Then** I can:
  - Type to search by symbol or name
  - See autocomplete suggestions
  - View instrument details (spread, last price)
  - Select from recent instruments

### AC2: Order Entry
- **Given** an instrument is selected
- **When** I enter order details
- **Then** I can specify:
  - Buy/Sell side with clear visual distinction
  - Quantity with validation
  - Order type (Market, Limit, Stop, etc.)
  - Price (for limit orders)
  - Time in Force (GTC, IOC, FOK, DAY)
  - Client account

### AC3: Real-Time Validation
- **Given** order details are entered
- **When** validation runs
- **Then** I see:
  - Real-time validation messages
  - Quantity vs available limits
  - Price reasonability checks
  - Credit utilization warnings
  - Estimated notional value

### AC4: Order Submission
- **Given** a valid order
- **When** I submit
- **Then**:
  - Order is sent to backend
  - Confirmation is displayed
  - Ticket can be reset or closed
  - Quick re-submit for similar order

### AC5: Order Amendment
- **Given** an existing order to amend
- **When** I open it in the ticket
- **Then** I can:
  - Modify quantity and price
  - See original vs amended values
  - Submit amendment
  - Cancel and revert changes

## Technical Specification

### Order Ticket Store

```typescript
// src/features/order-ticket/stores/order-ticket.store.ts
import { create } from 'zustand';
import { subscribeWithSelector } from 'zustand/middleware';

export interface OrderTicketData {
  mode: 'new' | 'amend';
  orderId?: string;
  instrumentId?: string;
  instrumentSymbol?: string;
  side: 'BUY' | 'SELL';
  orderType: 'MARKET' | 'LIMIT' | 'STOP' | 'STOP_LIMIT';
  quantity: number | '';
  price: number | '';
  stopPrice: number | '';
  timeInForce: 'GTC' | 'IOC' | 'FOK' | 'DAY';
  clientId?: string;
  accountId?: string;
  notes?: string;
}

interface ValidationError {
  field: string;
  message: string;
  severity: 'error' | 'warning';
}

interface OrderTicketState {
  isOpen: boolean;
  data: OrderTicketData;
  originalData: OrderTicketData | null;
  validationErrors: ValidationError[];
  isSubmitting: boolean;
  lastSubmittedOrderId: string | null;
  recentInstruments: { id: string; symbol: string }[];

  // Actions
  openTicket: (data?: Partial<OrderTicketData>) => void;
  closeTicket: () => void;
  resetTicket: () => void;
  updateField: <K extends keyof OrderTicketData>(field: K, value: OrderTicketData[K]) => void;
  setInstrument: (instrumentId: string, symbol: string) => void;
  toggleSide: () => void;
  setValidationErrors: (errors: ValidationError[]) => void;
  submitOrder: () => Promise<void>;
}

const defaultData: OrderTicketData = {
  mode: 'new',
  side: 'BUY',
  orderType: 'LIMIT',
  quantity: '',
  price: '',
  stopPrice: '',
  timeInForce: 'DAY',
};

export const useOrderTicketStore = create<OrderTicketState>()(
  subscribeWithSelector((set, get) => ({
    isOpen: false,
    data: { ...defaultData },
    originalData: null,
    validationErrors: [],
    isSubmitting: false,
    lastSubmittedOrderId: null,
    recentInstruments: [],

    openTicket: (data) => {
      const newData = { ...defaultData, ...data };
      set({
        isOpen: true,
        data: newData,
        originalData: data?.mode === 'amend' ? newData : null,
        validationErrors: [],
      });
    },

    closeTicket: () => {
      set({
        isOpen: false,
        data: { ...defaultData },
        originalData: null,
        validationErrors: [],
      });
    },

    resetTicket: () => {
      const { data } = get();
      set({
        data: {
          ...defaultData,
          instrumentId: data.instrumentId,
          instrumentSymbol: data.instrumentSymbol,
          clientId: data.clientId,
        },
        validationErrors: [],
      });
    },

    updateField: (field, value) => {
      set((state) => ({
        data: { ...state.data, [field]: value },
      }));
    },

    setInstrument: (instrumentId, symbol) => {
      set((state) => {
        // Add to recent instruments
        const recent = [
          { id: instrumentId, symbol },
          ...state.recentInstruments.filter(i => i.id !== instrumentId),
        ].slice(0, 10);

        return {
          data: { ...state.data, instrumentId, instrumentSymbol: symbol },
          recentInstruments: recent,
        };
      });
    },

    toggleSide: () => {
      set((state) => ({
        data: {
          ...state.data,
          side: state.data.side === 'BUY' ? 'SELL' : 'BUY',
        },
      }));
    },

    setValidationErrors: (errors) => {
      set({ validationErrors: errors });
    },

    submitOrder: async () => {
      const { data, originalData } = get();
      set({ isSubmitting: true });

      try {
        let orderId: string;

        if (data.mode === 'amend' && data.orderId) {
          const response = await orderApi.amendOrder(data.orderId, {
            quantity: Number(data.quantity),
            price: data.price ? Number(data.price) : undefined,
          });
          orderId = response.orderId;
        } else {
          const response = await orderApi.createOrder({
            instrumentId: data.instrumentId!,
            side: data.side,
            orderType: data.orderType,
            quantity: Number(data.quantity),
            price: data.price ? Number(data.price) : undefined,
            stopPrice: data.stopPrice ? Number(data.stopPrice) : undefined,
            timeInForce: data.timeInForce,
            clientId: data.clientId!,
            accountId: data.accountId,
            notes: data.notes,
          });
          orderId = response.orderId;
        }

        set({
          isSubmitting: false,
          lastSubmittedOrderId: orderId,
        });

        return orderId;
      } catch (error) {
        set({ isSubmitting: false });
        throw error;
      }
    },
  }))
);
```

### Order Ticket Component

```typescript
// src/features/order-ticket/components/OrderTicket.tsx
import React, { useEffect, useCallback } from 'react';
import { useForm, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useOrderTicketStore } from '../stores/order-ticket.store';
import { useInstrumentSearch } from '../hooks/useInstrumentSearch';
import { useOrderValidation } from '../hooks/useOrderValidation';
import { useMarketData } from '../../market-data/hooks/useMarketData';
import { InstrumentSearch } from './InstrumentSearch';
import { SideToggle } from './SideToggle';
import { QuantityInput } from './QuantityInput';
import { PriceInput } from './PriceInput';
import { OrderTypeSelect } from './OrderTypeSelect';
import { TimeInForceSelect } from './TimeInForceSelect';
import { ClientAccountSelect } from './ClientAccountSelect';
import { OrderSummary } from './OrderSummary';
import { ValidationMessages } from './ValidationMessages';

const orderSchema = z.object({
  instrumentId: z.string().min(1, 'Instrument is required'),
  side: z.enum(['BUY', 'SELL']),
  orderType: z.enum(['MARKET', 'LIMIT', 'STOP', 'STOP_LIMIT']),
  quantity: z.number().positive('Quantity must be positive'),
  price: z.number().optional(),
  stopPrice: z.number().optional(),
  timeInForce: z.enum(['GTC', 'IOC', 'FOK', 'DAY']),
  clientId: z.string().min(1, 'Client is required'),
}).refine((data) => {
  if (data.orderType === 'LIMIT' && !data.price) {
    return false;
  }
  return true;
}, {
  message: 'Price is required for limit orders',
  path: ['price'],
});

interface OrderTicketProps {
  panelId: string;
}

export const OrderTicket: React.FC<OrderTicketProps> = ({ panelId }) => {
  const {
    isOpen,
    data,
    originalData,
    validationErrors,
    isSubmitting,
    updateField,
    setInstrument,
    toggleSide,
    submitOrder,
    resetTicket,
    closeTicket,
  } = useOrderTicketStore();

  const { validate } = useOrderValidation();
  
  // Get real-time market data for selected instrument
  const { quote, isLoading: quoteLoading } = useMarketData(data.instrumentId);

  // Form handling
  const {
    control,
    handleSubmit,
    formState: { errors },
    setValue,
    watch,
    reset,
  } = useForm({
    resolver: zodResolver(orderSchema),
    defaultValues: data,
  });

  // Sync store with form
  useEffect(() => {
    reset(data);
  }, [data, reset]);

  // Real-time validation
  useEffect(() => {
    const subscription = watch((value) => {
      validate(value as any).then((errors) => {
        useOrderTicketStore.getState().setValidationErrors(errors);
      });
    });
    return () => subscription.unsubscribe();
  }, [watch, validate]);

  const onSubmit = useCallback(async () => {
    try {
      await submitOrder();
      // Show success notification
    } catch (error) {
      // Show error notification
    }
  }, [submitOrder]);

  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    // Ctrl+Enter to submit
    if (e.ctrlKey && e.key === 'Enter') {
      handleSubmit(onSubmit)();
    }
    // Escape to close
    if (e.key === 'Escape') {
      closeTicket();
    }
    // F2 to toggle side
    if (e.key === 'F2') {
      toggleSide();
    }
  }, [handleSubmit, onSubmit, closeTicket, toggleSide]);

  const isAmendMode = data.mode === 'amend';
  const hasChanges = isAmendMode && (
    data.quantity !== originalData?.quantity ||
    data.price !== originalData?.price
  );

  return (
    <div className="order-ticket" onKeyDown={handleKeyDown}>
      <div className="ticket-header">
        <h2>{isAmendMode ? 'Amend Order' : 'New Order'}</h2>
        {isAmendMode && (
          <span className="order-id">Order: {data.orderId}</span>
        )}
      </div>

      <form onSubmit={handleSubmit(onSubmit)} className="ticket-form">
        {/* Instrument Selection */}
        <div className="form-section">
          <InstrumentSearch
            value={data.instrumentId}
            onChange={(id, symbol) => setInstrument(id, symbol)}
            disabled={isAmendMode}
          />
          
          {quote && (
            <div className="market-data-preview">
              <span className="bid">{quote.bid}</span>
              <span className="spread">{quote.spread}</span>
              <span className="ask">{quote.ask}</span>
            </div>
          )}
        </div>

        {/* Side Toggle */}
        <div className="form-section">
          <SideToggle
            value={data.side}
            onChange={(side) => updateField('side', side)}
            bidPrice={quote?.bid}
            askPrice={quote?.ask}
          />
        </div>

        {/* Quantity */}
        <div className="form-section">
          <label>Quantity</label>
          <QuantityInput
            value={data.quantity}
            onChange={(qty) => updateField('quantity', qty)}
            instrumentId={data.instrumentId}
            error={errors.quantity?.message}
          />
          {isAmendMode && originalData && (
            <span className="original-value">
              Original: {originalData.quantity}
            </span>
          )}
        </div>

        {/* Order Type */}
        <div className="form-section">
          <label>Order Type</label>
          <OrderTypeSelect
            value={data.orderType}
            onChange={(type) => updateField('orderType', type)}
            disabled={isAmendMode}
          />
        </div>

        {/* Price (for Limit orders) */}
        {(data.orderType === 'LIMIT' || data.orderType === 'STOP_LIMIT') && (
          <div className="form-section">
            <label>Limit Price</label>
            <PriceInput
              value={data.price}
              onChange={(price) => updateField('price', price)}
              instrumentId={data.instrumentId}
              side={data.side}
              referencePrice={data.side === 'BUY' ? quote?.ask : quote?.bid}
              error={errors.price?.message}
            />
            {isAmendMode && originalData && (
              <span className="original-value">
                Original: {originalData.price}
              </span>
            )}
          </div>
        )}

        {/* Stop Price */}
        {(data.orderType === 'STOP' || data.orderType === 'STOP_LIMIT') && (
          <div className="form-section">
            <label>Stop Price</label>
            <PriceInput
              value={data.stopPrice}
              onChange={(price) => updateField('stopPrice', price)}
              instrumentId={data.instrumentId}
              side={data.side}
              error={errors.stopPrice?.message}
            />
          </div>
        )}

        {/* Time in Force */}
        <div className="form-section">
          <label>Time in Force</label>
          <TimeInForceSelect
            value={data.timeInForce}
            onChange={(tif) => updateField('timeInForce', tif)}
            disabled={isAmendMode}
          />
        </div>

        {/* Client/Account */}
        <div className="form-section">
          <label>Client / Account</label>
          <ClientAccountSelect
            clientId={data.clientId}
            accountId={data.accountId}
            onClientChange={(id) => updateField('clientId', id)}
            onAccountChange={(id) => updateField('accountId', id)}
            disabled={isAmendMode}
          />
        </div>

        {/* Notes */}
        <div className="form-section">
          <label>Notes (Optional)</label>
          <textarea
            value={data.notes || ''}
            onChange={(e) => updateField('notes', e.target.value)}
            placeholder="Order notes..."
            rows={2}
          />
        </div>

        {/* Validation Messages */}
        <ValidationMessages errors={validationErrors} />

        {/* Order Summary */}
        <OrderSummary
          data={data}
          quote={quote}
        />

        {/* Actions */}
        <div className="ticket-actions">
          <button
            type="button"
            onClick={resetTicket}
            className="btn-secondary"
          >
            Reset
          </button>
          
          <button
            type="submit"
            disabled={isSubmitting || validationErrors.some(e => e.severity === 'error')}
            className={`btn-primary ${data.side === 'BUY' ? 'btn-buy' : 'btn-sell'}`}
          >
            {isSubmitting ? (
              'Submitting...'
            ) : isAmendMode ? (
              hasChanges ? 'Amend Order' : 'No Changes'
            ) : (
              `${data.side} ${data.instrumentSymbol || 'Select Instrument'}`
            )}
          </button>
        </div>

        <div className="keyboard-hints">
          <span>Ctrl+Enter: Submit</span>
          <span>F2: Toggle Side</span>
          <span>Esc: Close</span>
        </div>
      </form>
    </div>
  );
};
```

### Side Toggle Component

```typescript
// src/features/order-ticket/components/SideToggle.tsx
import React from 'react';

interface SideToggleProps {
  value: 'BUY' | 'SELL';
  onChange: (side: 'BUY' | 'SELL') => void;
  bidPrice?: number;
  askPrice?: number;
}

export const SideToggle: React.FC<SideToggleProps> = ({
  value,
  onChange,
  bidPrice,
  askPrice,
}) => {
  return (
    <div className="side-toggle">
      <button
        type="button"
        className={`side-btn buy ${value === 'BUY' ? 'active' : ''}`}
        onClick={() => onChange('BUY')}
      >
        <span className="side-label">BUY</span>
        {askPrice && <span className="side-price">{askPrice.toFixed(5)}</span>}
      </button>
      
      <button
        type="button"
        className={`side-btn sell ${value === 'SELL' ? 'active' : ''}`}
        onClick={() => onChange('SELL')}
      >
        <span className="side-label">SELL</span>
        {bidPrice && <span className="side-price">{bidPrice.toFixed(5)}</span>}
      </button>
    </div>
  );
};
```

### Order Validation Hook

```typescript
// src/features/order-ticket/hooks/useOrderValidation.ts
import { useCallback } from 'react';
import { orderApi } from '../services/order.api';
import { OrderTicketData } from '../stores/order-ticket.store';

interface ValidationError {
  field: string;
  message: string;
  severity: 'error' | 'warning';
}

export function useOrderValidation() {
  const validate = useCallback(async (data: OrderTicketData): Promise<ValidationError[]> => {
    const errors: ValidationError[] = [];

    // Client-side validation
    if (!data.instrumentId) {
      errors.push({
        field: 'instrumentId',
        message: 'Please select an instrument',
        severity: 'error',
      });
    }

    if (!data.quantity || data.quantity <= 0) {
      errors.push({
        field: 'quantity',
        message: 'Quantity must be greater than 0',
        severity: 'error',
      });
    }

    if (data.orderType === 'LIMIT' && (!data.price || data.price <= 0)) {
      errors.push({
        field: 'price',
        message: 'Price is required for limit orders',
        severity: 'error',
      });
    }

    if (!data.clientId) {
      errors.push({
        field: 'clientId',
        message: 'Please select a client',
        severity: 'error',
      });
    }

    // Skip server validation if basic validation fails
    if (errors.some(e => e.severity === 'error')) {
      return errors;
    }

    // Server-side validation
    try {
      const serverValidation = await orderApi.validateOrder({
        instrumentId: data.instrumentId!,
        side: data.side,
        quantity: Number(data.quantity),
        price: data.price ? Number(data.price) : undefined,
        clientId: data.clientId!,
      });

      // Add server validation results
      for (const warning of serverValidation.warnings || []) {
        errors.push({
          field: warning.field,
          message: warning.message,
          severity: 'warning',
        });
      }

      for (const error of serverValidation.errors || []) {
        errors.push({
          field: error.field,
          message: error.message,
          severity: 'error',
        });
      }
    } catch (error) {
      // Don't block on validation API failure
      console.error('Validation API error:', error);
    }

    return errors;
  }, []);

  return { validate };
}
```

### CSS Styles

```css
/* src/features/order-ticket/styles/order-ticket.css */

.order-ticket {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--bg-primary);
  padding: 16px;
  overflow-y: auto;
}

.ticket-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border-color);
}

.ticket-header h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

.order-id {
  font-size: 12px;
  color: var(--text-secondary);
}

.ticket-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.form-section {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-section label {
  font-size: 12px;
  font-weight: 500;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

/* Side Toggle */
.side-toggle {
  display: flex;
  gap: 8px;
}

.side-btn {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 12px;
  border: 2px solid var(--border-color);
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
  transition: all 0.15s ease;
}

.side-btn.buy {
  border-color: var(--success-color);
  opacity: 0.5;
}

.side-btn.buy.active {
  background: var(--success-color);
  opacity: 1;
}

.side-btn.buy:hover {
  opacity: 0.8;
}

.side-btn.sell {
  border-color: var(--error-color);
  opacity: 0.5;
}

.side-btn.sell.active {
  background: var(--error-color);
  opacity: 1;
}

.side-btn.sell:hover {
  opacity: 0.8;
}

.side-label {
  font-size: 16px;
  font-weight: 700;
}

.side-price {
  font-size: 14px;
  font-family: var(--font-mono);
  margin-top: 4px;
}

.side-btn.active .side-label,
.side-btn.active .side-price {
  color: white;
}

/* Market Data Preview */
.market-data-preview {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 8px;
  padding: 8px;
  background: var(--bg-secondary);
  border-radius: 4px;
  font-family: var(--font-mono);
  font-size: 14px;
}

.market-data-preview .bid {
  color: var(--error-color);
}

.market-data-preview .ask {
  color: var(--success-color);
}

.market-data-preview .spread {
  color: var(--text-secondary);
  font-size: 12px;
}

/* Original value indicator */
.original-value {
  font-size: 11px;
  color: var(--text-secondary);
  font-style: italic;
}

/* Actions */
.ticket-actions {
  display: flex;
  gap: 12px;
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--border-color);
}

.ticket-actions button {
  flex: 1;
  padding: 12px 16px;
  border: none;
  border-radius: 6px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-secondary {
  background: var(--bg-tertiary);
  color: var(--text-primary);
}

.btn-primary {
  color: white;
}

.btn-primary.btn-buy {
  background: var(--success-color);
}

.btn-primary.btn-sell {
  background: var(--error-color);
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* Keyboard hints */
.keyboard-hints {
  display: flex;
  justify-content: center;
  gap: 16px;
  margin-top: 12px;
  font-size: 11px;
  color: var(--text-tertiary);
}

.keyboard-hints span {
  display: flex;
  align-items: center;
  gap: 4px;
}
```

## Definition of Done

- [ ] Instrument search with autocomplete
- [ ] Side toggle with live prices
- [ ] Quantity input with validation
- [ ] Order type selection
- [ ] Price input for limit orders
- [ ] Time in Force selection
- [ ] Client/Account selection
- [ ] Real-time validation
- [ ] Order submission
- [ ] Amendment mode working
- [ ] Keyboard shortcuts
- [ ] Unit tests for store
- [ ] Integration tests for submission

## Test Cases

```typescript
describe('OrderTicket', () => {
  it('should validate required fields', async () => {
    render(<OrderTicket panelId="test" />);
    
    fireEvent.click(screen.getByText('BUY'));
    
    await waitFor(() => {
      expect(screen.getByText('Please select an instrument')).toBeInTheDocument();
    });
  });

  it('should submit valid order', async () => {
    render(<OrderTicket panelId="test" />);
    
    // Fill form
    await selectInstrument('EURUSD');
    await enterQuantity(100000);
    await selectClient('Client A');
    
    fireEvent.click(screen.getByText('BUY EURUSD'));
    
    await waitFor(() => {
      expect(orderApi.createOrder).toHaveBeenCalled();
    });
  });

  it('should handle amendment mode', async () => {
    useOrderTicketStore.getState().openTicket({
      mode: 'amend',
      orderId: 'ORD-123',
      quantity: 100000,
      price: 1.1234,
    });

    render(<OrderTicket panelId="test" />);
    
    expect(screen.getByText('Amend Order')).toBeInTheDocument();
    expect(screen.getByDisplayValue('100000')).toBeInTheDocument();
  });
});
```
