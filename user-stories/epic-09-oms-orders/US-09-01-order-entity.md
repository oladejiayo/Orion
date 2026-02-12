# User Story: US-09-01 - Order Entity and Repository

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-09-01 |
| **Epic** | Epic 09 - OMS Orders V1 |
| **Title** | Order Entity and Repository |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |
| **PRD Reference** | FR-Order-01, NFR-Performance-01 |

## User Story

**As a** platform developer  
**I want** a robust order entity and repository layer  
**So that** orders are stored reliably with full version history

## Description

Implement order entity with all fields, database schema with partitioning for high volume, repository pattern with optimistic locking, and order reference generation.

## Acceptance Criteria

- [ ] Order entity with all required fields
- [ ] OrderFill entity for execution records
- [ ] PostgreSQL schema with partitioning
- [ ] Unique order reference generation
- [ ] Optimistic locking for concurrency
- [ ] Query options for filtering
- [ ] RLS policies for multi-tenancy

## Technical Details

### Order SQL Schema

```sql
-- migrations/011_orders_schema.sql

-- Order status enum
CREATE TYPE order_status AS ENUM (
    'pending',
    'validated',
    'rejected',
    'held',
    'working',
    'partial_fill',
    'filled',
    'cancelled',
    'expired',
    'done_for_day'
);

-- Order type enum
CREATE TYPE order_type AS ENUM (
    'market',
    'limit',
    'stop',
    'stop_limit',
    'pegged',
    'trailing_stop'
);

-- Time in force enum
CREATE TYPE time_in_force AS ENUM (
    'gtc',      -- Good til cancelled
    'day',      -- Day order
    'ioc',      -- Immediate or cancel
    'fok',      -- Fill or kill
    'gtd'       -- Good til date
);

-- Orders table
CREATE TABLE orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    order_ref VARCHAR(30) NOT NULL,
    client_order_id VARCHAR(100),
    
    -- Parties
    client_id UUID NOT NULL,
    trader_id UUID,
    desk_id UUID,
    
    -- Instrument
    instrument_id UUID NOT NULL,
    symbol VARCHAR(50) NOT NULL,
    
    -- Order details
    side VARCHAR(4) NOT NULL CHECK (side IN ('buy', 'sell')),
    order_type order_type NOT NULL,
    time_in_force time_in_force NOT NULL DEFAULT 'day',
    
    -- Quantities
    quantity DECIMAL(20, 8) NOT NULL,
    filled_quantity DECIMAL(20, 8) DEFAULT 0,
    remaining_quantity DECIMAL(20, 8) NOT NULL,
    display_quantity DECIMAL(20, 8), -- For iceberg orders
    
    -- Prices
    price DECIMAL(20, 8),           -- Limit price
    stop_price DECIMAL(20, 8),      -- Stop trigger
    peg_offset DECIMAL(20, 8),      -- Peg offset
    trailing_amount DECIMAL(20, 8), -- Trailing stop amount
    
    -- Execution
    average_price DECIMAL(20, 8),
    total_notional DECIMAL(20, 2) DEFAULT 0,
    
    -- Status
    status order_status DEFAULT 'pending',
    status_reason TEXT,
    reject_code VARCHAR(50),
    
    -- Routing
    routing_strategy VARCHAR(20) DEFAULT 'best',
    routed_lps JSONB DEFAULT '[]',
    working_order_ids JSONB DEFAULT '[]',
    
    -- Timestamps
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    validated_at TIMESTAMPTZ,
    worked_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    
    -- Metadata
    metadata JSONB DEFAULT '{}',
    client_metadata JSONB DEFAULT '{}',
    
    -- Audit
    version INT DEFAULT 1,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    
    -- Constraints
    CONSTRAINT orders_order_ref_unique UNIQUE (tenant_id, order_ref),
    CONSTRAINT orders_client_order_unique UNIQUE (tenant_id, client_id, client_order_id)
) PARTITION BY RANGE (received_at);

-- Create monthly partitions
CREATE TABLE orders_2024_01 PARTITION OF orders
    FOR VALUES FROM ('2024-01-01') TO ('2024-02-01');
CREATE TABLE orders_2024_02 PARTITION OF orders
    FOR VALUES FROM ('2024-02-01') TO ('2024-03-01');
-- ... more partitions as needed

-- Indexes
CREATE INDEX idx_orders_tenant ON orders(tenant_id);
CREATE INDEX idx_orders_client ON orders(client_id);
CREATE INDEX idx_orders_instrument ON orders(instrument_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_received ON orders(received_at DESC);
CREATE INDEX idx_orders_working ON orders(status) WHERE status IN ('working', 'partial_fill');
CREATE INDEX idx_orders_client_order ON orders(client_order_id) WHERE client_order_id IS NOT NULL;

-- Order fills table
CREATE TABLE order_fills (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id),
    fill_ref VARCHAR(30) NOT NULL,
    
    -- Execution
    lp_id UUID NOT NULL,
    lp_order_id VARCHAR(100),
    lp_fill_id VARCHAR(100),
    
    quantity DECIMAL(20, 8) NOT NULL,
    price DECIMAL(20, 8) NOT NULL,
    notional DECIMAL(20, 2) NOT NULL,
    
    -- Fees
    commission DECIMAL(20, 4) DEFAULT 0,
    exchange_fee DECIMAL(20, 4) DEFAULT 0,
    
    -- Timestamps
    executed_at TIMESTAMPTZ NOT NULL,
    reported_at TIMESTAMPTZ DEFAULT NOW(),
    
    -- Trade link
    trade_id UUID,
    
    -- Audit
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    
    CONSTRAINT fills_unique UNIQUE (order_id, fill_ref)
);

-- Indexes
CREATE INDEX idx_fills_order ON order_fills(order_id);
CREATE INDEX idx_fills_trade ON order_fills(trade_id) WHERE trade_id IS NOT NULL;
CREATE INDEX idx_fills_lp ON order_fills(lp_id);
CREATE INDEX idx_fills_executed ON order_fills(executed_at DESC);

-- RLS policies
ALTER TABLE orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_fills ENABLE ROW LEVEL SECURITY;

CREATE POLICY orders_tenant_policy ON orders
    FOR ALL
    USING (tenant_id = current_setting('app.tenant_id')::uuid);

CREATE POLICY fills_tenant_policy ON order_fills
    FOR ALL
    USING (
        EXISTS (
            SELECT 1 FROM orders o 
            WHERE o.id = order_fills.order_id 
            AND o.tenant_id = current_setting('app.tenant_id')::uuid
        )
    );

-- Auto-update timestamp trigger
CREATE TRIGGER orders_updated_at
    BEFORE UPDATE ON orders
    FOR EACH ROW
    EXECUTE FUNCTION update_timestamp();

-- Order sequence for reference generation
CREATE SEQUENCE order_ref_seq START 100000;
```

### Order Entity

```typescript
// services/order-service/src/entities/order.entity.ts
import { Entity, PrimaryGeneratedColumn, Column, CreateDateColumn, UpdateDateColumn, OneToMany, Index, VersionColumn } from 'typeorm';
import { OrderFillEntity } from './order-fill.entity';

export enum OrderStatus {
  PENDING = 'pending',
  VALIDATED = 'validated',
  REJECTED = 'rejected',
  HELD = 'held',
  WORKING = 'working',
  PARTIAL_FILL = 'partial_fill',
  FILLED = 'filled',
  CANCELLED = 'cancelled',
  EXPIRED = 'expired',
  DONE_FOR_DAY = 'done_for_day',
}

export enum OrderType {
  MARKET = 'market',
  LIMIT = 'limit',
  STOP = 'stop',
  STOP_LIMIT = 'stop_limit',
  PEGGED = 'pegged',
  TRAILING_STOP = 'trailing_stop',
}

export enum TimeInForce {
  GTC = 'gtc',
  DAY = 'day',
  IOC = 'ioc',
  FOK = 'fok',
  GTD = 'gtd',
}

@Entity('orders')
@Index(['tenantId', 'orderRef'], { unique: true })
@Index(['tenantId', 'clientId', 'clientOrderId'], { unique: true })
@Index(['clientId'])
@Index(['instrumentId'])
@Index(['status'])
@Index(['receivedAt'])
export class OrderEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column('uuid')
  tenantId: string;

  @Column('varchar', { length: 30 })
  orderRef: string;

  @Column('varchar', { length: 100, nullable: true })
  clientOrderId?: string;

  // Parties
  @Column('uuid')
  clientId: string;

  @Column('uuid', { nullable: true })
  traderId?: string;

  @Column('uuid', { nullable: true })
  deskId?: string;

  // Instrument
  @Column('uuid')
  instrumentId: string;

  @Column('varchar', { length: 50 })
  symbol: string;

  // Order details
  @Column('varchar', { length: 4 })
  side: 'buy' | 'sell';

  @Column({ type: 'enum', enum: OrderType })
  orderType: OrderType;

  @Column({ type: 'enum', enum: TimeInForce, default: TimeInForce.DAY })
  timeInForce: TimeInForce;

  // Quantities
  @Column('decimal', { precision: 20, scale: 8 })
  quantity: number;

  @Column('decimal', { precision: 20, scale: 8, default: 0 })
  filledQuantity: number;

  @Column('decimal', { precision: 20, scale: 8 })
  remainingQuantity: number;

  @Column('decimal', { precision: 20, scale: 8, nullable: true })
  displayQuantity?: number; // Iceberg

  // Prices
  @Column('decimal', { precision: 20, scale: 8, nullable: true })
  price?: number;

  @Column('decimal', { precision: 20, scale: 8, nullable: true })
  stopPrice?: number;

  @Column('decimal', { precision: 20, scale: 8, nullable: true })
  pegOffset?: number;

  @Column('decimal', { precision: 20, scale: 8, nullable: true })
  trailingAmount?: number;

  // Execution
  @Column('decimal', { precision: 20, scale: 8, nullable: true })
  averagePrice?: number;

  @Column('decimal', { precision: 20, scale: 2, default: 0 })
  totalNotional: number;

  // Status
  @Column({ type: 'enum', enum: OrderStatus, default: OrderStatus.PENDING })
  status: OrderStatus;

  @Column('text', { nullable: true })
  statusReason?: string;

  @Column('varchar', { length: 50, nullable: true })
  rejectCode?: string;

  // Routing
  @Column('varchar', { length: 20, default: 'best' })
  routingStrategy: string;

  @Column('jsonb', { default: [] })
  routedLps: string[];

  @Column('jsonb', { default: [] })
  workingOrderIds: string[];

  // Timestamps
  @Column('timestamptz')
  receivedAt: Date;

  @Column('timestamptz', { nullable: true })
  validatedAt?: Date;

  @Column('timestamptz', { nullable: true })
  workedAt?: Date;

  @Column('timestamptz', { nullable: true })
  completedAt?: Date;

  @Column('timestamptz', { nullable: true })
  expiresAt?: Date;

  // Metadata
  @Column('jsonb', { default: {} })
  metadata: Record<string, any>;

  @Column('jsonb', { default: {} })
  clientMetadata: Record<string, any>;

  // Audit
  @VersionColumn()
  version: number;

  @CreateDateColumn()
  createdAt: Date;

  @UpdateDateColumn()
  updatedAt: Date;

  // Relations
  @OneToMany(() => OrderFillEntity, fill => fill.order)
  fills: OrderFillEntity[];

  // Computed
  get isFilled(): boolean {
    return this.remainingQuantity <= 0;
  }

  get fillPercentage(): number {
    return (this.filledQuantity / this.quantity) * 100;
  }

  get isWorking(): boolean {
    return [OrderStatus.WORKING, OrderStatus.PARTIAL_FILL].includes(this.status);
  }

  get isTerminal(): boolean {
    return [
      OrderStatus.FILLED,
      OrderStatus.CANCELLED,
      OrderStatus.REJECTED,
      OrderStatus.EXPIRED,
    ].includes(this.status);
  }
}
```

### Order Fill Entity

```typescript
// services/order-service/src/entities/order-fill.entity.ts
import { Entity, PrimaryGeneratedColumn, Column, CreateDateColumn, ManyToOne, JoinColumn, Index } from 'typeorm';
import { OrderEntity } from './order.entity';

@Entity('order_fills')
@Index(['orderId'])
@Index(['lpId'])
@Index(['executedAt'])
export class OrderFillEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column('uuid')
  orderId: string;

  @Column('varchar', { length: 30 })
  fillRef: string;

  // Execution
  @Column('uuid')
  lpId: string;

  @Column('varchar', { length: 100, nullable: true })
  lpOrderId?: string;

  @Column('varchar', { length: 100, nullable: true })
  lpFillId?: string;

  @Column('decimal', { precision: 20, scale: 8 })
  quantity: number;

  @Column('decimal', { precision: 20, scale: 8 })
  price: number;

  @Column('decimal', { precision: 20, scale: 2 })
  notional: number;

  // Fees
  @Column('decimal', { precision: 20, scale: 4, default: 0 })
  commission: number;

  @Column('decimal', { precision: 20, scale: 4, default: 0 })
  exchangeFee: number;

  // Timestamps
  @Column('timestamptz')
  executedAt: Date;

  @Column('timestamptz')
  reportedAt: Date;

  // Trade link
  @Column('uuid', { nullable: true })
  tradeId?: string;

  // Metadata
  @Column('jsonb', { default: {} })
  metadata: Record<string, any>;

  @CreateDateColumn()
  createdAt: Date;

  @ManyToOne(() => OrderEntity, order => order.fills)
  @JoinColumn({ name: 'orderId' })
  order: OrderEntity;
}
```

### Order Repository

```typescript
// services/order-service/src/repositories/order.repository.ts
import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, FindOptionsWhere, In, Between, LessThanOrEqual, MoreThanOrEqual, DataSource } from 'typeorm';
import { OrderEntity, OrderStatus } from '../entities/order.entity';
import { OrderFillEntity } from '../entities/order-fill.entity';
import { logger } from '@orion/observability';
import { getCurrentTenant } from '@orion/security';

export interface OrderQueryOptions {
  clientId?: string;
  instrumentId?: string;
  status?: OrderStatus | OrderStatus[];
  side?: 'buy' | 'sell';
  fromDate?: Date;
  toDate?: Date;
  limit?: number;
  offset?: number;
  includeTerminal?: boolean;
}

@Injectable()
export class OrderRepository {
  constructor(
    @InjectRepository(OrderEntity)
    private readonly orderRepo: Repository<OrderEntity>,
    @InjectRepository(OrderFillEntity)
    private readonly fillRepo: Repository<OrderFillEntity>,
    private readonly dataSource: DataSource,
  ) {}

  async findById(id: string, options?: { includeFills?: boolean }): Promise<OrderEntity | null> {
    return this.orderRepo.findOne({
      where: { id },
      relations: options?.includeFills ? ['fills'] : [],
    });
  }

  async findByRef(orderRef: string): Promise<OrderEntity | null> {
    return this.orderRepo.findOne({ where: { orderRef } });
  }

  async findByClientOrderId(
    clientId: string,
    clientOrderId: string,
  ): Promise<OrderEntity | null> {
    return this.orderRepo.findOne({
      where: { clientId, clientOrderId },
    });
  }

  async findMany(options: OrderQueryOptions): Promise<OrderEntity[]> {
    const qb = this.orderRepo.createQueryBuilder('o');

    if (options.clientId) {
      qb.andWhere('o.clientId = :clientId', { clientId: options.clientId });
    }

    if (options.instrumentId) {
      qb.andWhere('o.instrumentId = :instrumentId', { instrumentId: options.instrumentId });
    }

    if (options.status) {
      if (Array.isArray(options.status)) {
        qb.andWhere('o.status IN (:...statuses)', { statuses: options.status });
      } else {
        qb.andWhere('o.status = :status', { status: options.status });
      }
    }

    if (options.side) {
      qb.andWhere('o.side = :side', { side: options.side });
    }

    if (options.fromDate) {
      qb.andWhere('o.receivedAt >= :fromDate', { fromDate: options.fromDate });
    }

    if (options.toDate) {
      qb.andWhere('o.receivedAt <= :toDate', { toDate: options.toDate });
    }

    if (!options.includeTerminal) {
      qb.andWhere('o.status NOT IN (:...terminal)', {
        terminal: [OrderStatus.FILLED, OrderStatus.CANCELLED, OrderStatus.REJECTED, OrderStatus.EXPIRED],
      });
    }

    qb.orderBy('o.receivedAt', 'DESC');

    if (options.limit) {
      qb.take(options.limit);
    }
    if (options.offset) {
      qb.skip(options.offset);
    }

    return qb.getMany();
  }

  async findWorkingOrders(): Promise<OrderEntity[]> {
    return this.orderRepo.find({
      where: {
        status: In([OrderStatus.WORKING, OrderStatus.PARTIAL_FILL]),
      },
      order: { receivedAt: 'ASC' },
    });
  }

  async save(order: OrderEntity): Promise<OrderEntity> {
    return this.orderRepo.save(order);
  }

  async updateWithLock(
    id: string,
    updates: Partial<OrderEntity>,
    expectedVersion: number,
  ): Promise<OrderEntity> {
    const result = await this.orderRepo
      .createQueryBuilder()
      .update()
      .set({ ...updates, version: expectedVersion + 1 })
      .where('id = :id AND version = :version', { id, version: expectedVersion })
      .returning('*')
      .execute();

    if (result.affected === 0) {
      throw new Error(`Optimistic lock failed for order ${id}`);
    }

    return result.raw[0];
  }

  async saveFill(fill: OrderFillEntity): Promise<OrderFillEntity> {
    return this.fillRepo.save(fill);
  }

  async getOrderFills(orderId: string): Promise<OrderFillEntity[]> {
    return this.fillRepo.find({
      where: { orderId },
      order: { executedAt: 'ASC' },
    });
  }

  async generateOrderRef(): Promise<string> {
    const date = new Date();
    const dateStr = date.toISOString().slice(2, 10).replace(/-/g, '');
    
    const result = await this.dataSource.query(
      "SELECT nextval('order_ref_seq') as seq",
    );
    const seq = result[0].seq;
    
    return `ORD-${dateStr}-${seq}`;
  }

  async getOrderStats(options: { fromDate?: Date; toDate?: Date }): Promise<any> {
    const qb = this.orderRepo.createQueryBuilder('o')
      .select([
        'o.status',
        'COUNT(*) as count',
        'SUM(o.quantity) as totalQuantity',
        'SUM(o.filledQuantity) as totalFilled',
        'AVG(o.fillPercentage) as avgFillRate',
      ])
      .groupBy('o.status');

    if (options.fromDate) {
      qb.andWhere('o.receivedAt >= :fromDate', { fromDate: options.fromDate });
    }
    if (options.toDate) {
      qb.andWhere('o.receivedAt <= :toDate', { toDate: options.toDate });
    }

    return qb.getRawMany();
  }
}
```

### Order Reference Generation

```typescript
// services/order-service/src/utils/order-ref.util.ts

/**
 * Generate unique order reference
 * Format: ORD-YYMMDD-SEQUENCE
 * Example: ORD-240115-100001
 */
export async function generateOrderRef(dataSource: DataSource): Promise<string> {
  const date = new Date();
  const dateStr = date.toISOString().slice(2, 10).replace(/-/g, '');
  
  const result = await dataSource.query(
    "SELECT nextval('order_ref_seq') as seq",
  );
  const seq = result[0].seq;
  
  return `ORD-${dateStr}-${seq}`;
}

/**
 * Generate unique fill reference
 * Format: FIL-ORDERREF-SEQUENCE
 */
export function generateFillRef(orderRef: string, fillNumber: number): string {
  return `FIL-${orderRef.replace('ORD-', '')}-${fillNumber.toString().padStart(3, '0')}`;
}
```

## Definition of Done

- [ ] Order entity with all fields
- [ ] Order fill entity
- [ ] PostgreSQL schema with partitions
- [ ] Order repository with queries
- [ ] Optimistic locking support
- [ ] Reference generation
- [ ] RLS policies

## Dependencies

- **US-04-01**: Instrument reference data
- **US-04-02**: Client reference data

## Test Cases

```typescript
describe('OrderRepository', () => {
  it('should create order with unique reference', async () => {
    const orderRef = await orderRepo.generateOrderRef();
    
    const order = new OrderEntity();
    order.orderRef = orderRef;
    order.clientId = 'client-1';
    order.instrumentId = 'eurusd';
    order.side = 'buy';
    order.orderType = OrderType.LIMIT;
    order.quantity = 1000000;
    order.remainingQuantity = 1000000;
    order.price = 1.0850;

    const saved = await orderRepo.save(order);
    expect(saved.id).toBeDefined();
    expect(saved.orderRef).toMatch(/^ORD-\d{6}-\d+$/);
  });

  it('should find working orders', async () => {
    await createOrder({ status: OrderStatus.WORKING });
    await createOrder({ status: OrderStatus.PARTIAL_FILL });
    await createOrder({ status: OrderStatus.FILLED });

    const working = await orderRepo.findWorkingOrders();
    expect(working.length).toBe(2);
  });

  it('should enforce optimistic locking', async () => {
    const order = await createOrder();
    
    // Concurrent update attempt
    await expect(
      orderRepo.updateWithLock(order.id, { filledQuantity: 100 }, order.version - 1),
    ).rejects.toThrow('Optimistic lock failed');
  });
});
```
