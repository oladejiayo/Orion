# User Story: US-04-02 - Counterparty Management

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-04-02 |
| **Epic** | Epic 04 - Reference Data Management |
| **Title** | Counterparty Management |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |
| **PRD Reference** | FR-REF-03 |

## User Story

**As a** trading operations manager  
**I want** to manage counterparties (liquidity providers, clients, and internal accounts)  
**So that** trades can be properly attributed and settled

## Description

Implement counterparty management for all trading partners including liquidity providers (LPs), institutional clients, and internal trading desks. Each counterparty has specific attributes for settlement, credit limits, and connectivity.

## Acceptance Criteria

- [ ] Create, update, deactivate counterparties
- [ ] Support LP, Client, Internal types
- [ ] Manage LP connectivity settings
- [ ] Track credit limits and usage
- [ ] Publish counterparty events
- [ ] Counterparty-instrument entitlements

## Technical Details

### Database Schema

```sql
-- Migration: 011_create_counterparties.sql
CREATE TYPE counterparty_type AS ENUM ('liquidity_provider', 'client', 'internal');
CREATE TYPE counterparty_status AS ENUM ('active', 'suspended', 'deactivated');

CREATE TABLE counterparties (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  
  -- Identity
  name VARCHAR(255) NOT NULL,
  code VARCHAR(50) NOT NULL,
  legal_name VARCHAR(255),
  type counterparty_type NOT NULL,
  status counterparty_status NOT NULL DEFAULT 'active',
  
  -- Contact
  contact_email VARCHAR(255),
  contact_phone VARCHAR(50),
  address JSONB,
  
  -- Trading attributes
  credit_limit DECIMAL(20, 2),
  credit_used DECIMAL(20, 2) DEFAULT 0,
  default_settlement_days INT DEFAULT 2,
  
  -- LP-specific
  connectivity JSONB, -- { protocol: 'FIX', host: '', port: 0, credentials: {} }
  supported_instruments UUID[],
  markup_bps DECIMAL(10, 4) DEFAULT 0,
  priority INT DEFAULT 100, -- Lower = higher priority
  
  -- Regulatory
  lei VARCHAR(20), -- Legal Entity Identifier
  mifid_classification VARCHAR(50),
  
  -- Metadata
  tags VARCHAR(50)[],
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_by UUID,
  updated_by UUID,
  
  CONSTRAINT counterparties_tenant_code_unique UNIQUE (tenant_id, code)
);

-- Enable RLS
ALTER TABLE counterparties ENABLE ROW LEVEL SECURITY;
ALTER TABLE counterparties FORCE ROW LEVEL SECURITY;

CREATE POLICY counterparties_tenant_isolation ON counterparties
  FOR ALL USING (tenant_id = current_tenant_id() OR is_admin_context())
  WITH CHECK (tenant_id = current_tenant_id() OR is_admin_context());

-- Indexes
CREATE INDEX idx_counterparties_code ON counterparties(tenant_id, code);
CREATE INDEX idx_counterparties_type ON counterparties(tenant_id, type);
CREATE INDEX idx_counterparties_status ON counterparties(tenant_id, status);

-- Counterparty-Instrument entitlements
CREATE TABLE counterparty_instruments (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  counterparty_id UUID NOT NULL REFERENCES counterparties(id),
  instrument_id UUID NOT NULL REFERENCES instruments(id),
  enabled BOOLEAN NOT NULL DEFAULT true,
  min_quantity DECIMAL(20, 10),
  max_quantity DECIMAL(20, 10),
  markup_bps DECIMAL(10, 4), -- Override counterparty default
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  
  CONSTRAINT cp_instrument_unique UNIQUE (counterparty_id, instrument_id)
);

ALTER TABLE counterparty_instruments ENABLE ROW LEVEL SECURITY;
ALTER TABLE counterparty_instruments FORCE ROW LEVEL SECURITY;

CREATE POLICY cp_instruments_tenant_isolation ON counterparty_instruments
  FOR ALL USING (tenant_id = current_tenant_id() OR is_admin_context());
```

### Service Implementation

```typescript
// services/reference-data-service/src/application/counterparty.service.ts
import { Injectable, NotFoundException, ConflictException } from '@nestjs/common';
import { CounterpartyRepository } from '../infrastructure/counterparty.repository';
import { EventBus } from '@orion/event-model';
import { AuditService } from '@orion/observability';
import { getCurrentTenant } from '@orion/security';

export interface Counterparty {
  id: string;
  tenantId: string;
  name: string;
  code: string;
  legalName?: string;
  type: 'liquidity_provider' | 'client' | 'internal';
  status: 'active' | 'suspended' | 'deactivated';
  creditLimit?: number;
  creditUsed?: number;
  connectivity?: LPConnectivity;
  supportedInstruments?: string[];
  markupBps?: number;
  priority?: number;
  lei?: string;
  tags: string[];
  createdAt: Date;
  updatedAt: Date;
}

export interface LPConnectivity {
  protocol: 'FIX' | 'REST' | 'WEBSOCKET' | 'GRPC';
  host: string;
  port: number;
  credentials?: {
    username?: string;
    apiKey?: string;
    secretKey?: string;
  };
  heartbeatInterval?: number;
  reconnectAttempts?: number;
}

@Injectable()
export class CounterpartyService {
  constructor(
    private readonly repo: CounterpartyRepository,
    private readonly eventBus: EventBus,
    private readonly audit: AuditService,
  ) {}

  async createCounterparty(dto: CreateCounterpartyDto): Promise<Counterparty> {
    const { tenantId, userId } = getCurrentTenant()!;
    
    const existing = await this.repo.findByCode(dto.code);
    if (existing) {
      throw new ConflictException(`Counterparty with code '${dto.code}' already exists`);
    }

    const counterparty = await this.repo.create({
      ...dto,
      tenantId,
      status: 'active',
      creditUsed: 0,
      createdBy: userId,
    });

    await this.eventBus.publish({
      eventType: 'counterparty.created',
      aggregateType: 'counterparty',
      aggregateId: counterparty.id,
      payload: {
        counterpartyId: counterparty.id,
        code: counterparty.code,
        type: counterparty.type,
      },
      metadata: { tenantId, userId, correlationId: crypto.randomUUID() },
    });

    await this.audit.log({
      action: 'CREATE',
      entityType: 'COUNTERPARTY',
      entityId: counterparty.id,
      newValue: counterparty,
    });

    return counterparty;
  }

  async updateCounterparty(id: string, dto: UpdateCounterpartyDto): Promise<Counterparty> {
    const { tenantId, userId } = getCurrentTenant()!;
    
    const existing = await this.repo.findById(id);
    if (!existing) {
      throw new NotFoundException(`Counterparty ${id} not found`);
    }

    const updated = await this.repo.update(id, {
      ...dto,
      updatedBy: userId,
    });

    await this.eventBus.publish({
      eventType: 'counterparty.updated',
      aggregateType: 'counterparty',
      aggregateId: id,
      payload: { counterpartyId: id, changes: dto },
      metadata: { tenantId, userId, correlationId: crypto.randomUUID() },
    });

    await this.audit.log({
      action: 'UPDATE',
      entityType: 'COUNTERPARTY',
      entityId: id,
      oldValue: existing,
      newValue: updated,
    });

    return updated;
  }

  async getLiquidityProviders(): Promise<Counterparty[]> {
    return this.repo.findByType('liquidity_provider', { status: 'active' });
  }

  async getLPsForInstrument(instrumentId: string): Promise<Counterparty[]> {
    return this.repo.findLPsWithInstrument(instrumentId);
  }

  async updateCreditUsage(id: string, amount: number): Promise<Counterparty> {
    const counterparty = await this.repo.findById(id);
    if (!counterparty) {
      throw new NotFoundException(`Counterparty ${id} not found`);
    }

    const newCreditUsed = (counterparty.creditUsed || 0) + amount;
    
    if (counterparty.creditLimit && newCreditUsed > counterparty.creditLimit) {
      throw new ConflictException('Credit limit exceeded');
    }

    return this.repo.update(id, { creditUsed: newCreditUsed });
  }

  async setInstrumentEntitlement(
    counterpartyId: string,
    instrumentId: string,
    settings: {
      enabled: boolean;
      minQuantity?: number;
      maxQuantity?: number;
      markupBps?: number;
    },
  ): Promise<void> {
    const { tenantId } = getCurrentTenant()!;
    
    await this.repo.upsertInstrumentEntitlement({
      tenantId,
      counterpartyId,
      instrumentId,
      ...settings,
    });

    await this.eventBus.publish({
      eventType: 'counterparty.entitlement.updated',
      aggregateType: 'counterparty',
      aggregateId: counterpartyId,
      payload: { counterpartyId, instrumentId, settings },
      metadata: { tenantId, correlationId: crypto.randomUUID() },
    });
  }
}
```

### REST Controller

```typescript
// services/reference-data-service/src/api/counterparty.controller.ts
@ApiTags('Counterparties')
@Controller('counterparties')
@UseGuards(JwtAuthGuard)
@ApiBearerAuth()
export class CounterpartyController {
  constructor(private readonly counterpartyService: CounterpartyService) {}

  @Post()
  @Permissions('counterparty:create')
  @ApiOperation({ summary: 'Create a new counterparty' })
  async createCounterparty(@Body() dto: CreateCounterpartyDto) {
    return this.counterpartyService.createCounterparty(dto);
  }

  @Get()
  @Permissions('counterparty:read')
  @ApiOperation({ summary: 'List counterparties' })
  async listCounterparties(@Query() query: CounterpartyQueryDto) {
    return this.counterpartyService.listCounterparties(query);
  }

  @Get('lps')
  @Permissions('counterparty:read')
  @ApiOperation({ summary: 'List active liquidity providers' })
  async getLiquidityProviders() {
    return this.counterpartyService.getLiquidityProviders();
  }

  @Get('lps/instrument/:instrumentId')
  @Permissions('counterparty:read')
  @ApiOperation({ summary: 'Get LPs for specific instrument' })
  async getLPsForInstrument(@Param('instrumentId') instrumentId: string) {
    return this.counterpartyService.getLPsForInstrument(instrumentId);
  }

  @Get(':id')
  @Permissions('counterparty:read')
  async getCounterparty(@Param('id') id: string) {
    return this.counterpartyService.getCounterparty(id);
  }

  @Put(':id')
  @Permissions('counterparty:update')
  async updateCounterparty(
    @Param('id') id: string,
    @Body() dto: UpdateCounterpartyDto,
  ) {
    return this.counterpartyService.updateCounterparty(id, dto);
  }

  @Put(':id/instruments/:instrumentId')
  @Permissions('counterparty:update')
  @ApiOperation({ summary: 'Set instrument entitlement for counterparty' })
  async setInstrumentEntitlement(
    @Param('id') counterpartyId: string,
    @Param('instrumentId') instrumentId: string,
    @Body() dto: InstrumentEntitlementDto,
  ) {
    return this.counterpartyService.setInstrumentEntitlement(
      counterpartyId,
      instrumentId,
      dto,
    );
  }
}
```

## Definition of Done

- [ ] All counterparty types supported
- [ ] Credit limits enforced
- [ ] LP connectivity stored
- [ ] Instrument entitlements work
- [ ] Events published
- [ ] Tests verify credit logic

## Dependencies

- **US-04-01**: Instrument Management
- **US-03-02**: Row-Level Security

## Test Cases

```typescript
describe('CounterpartyService', () => {
  describe('createCounterparty', () => {
    it('should create LP with connectivity', async () => {
      const cp = await service.createCounterparty({
        name: 'Test LP',
        code: 'TEST_LP',
        type: 'liquidity_provider',
        connectivity: {
          protocol: 'FIX',
          host: 'fix.testlp.com',
          port: 9878,
        },
        creditLimit: 1000000,
      });
      
      expect(cp.type).toBe('liquidity_provider');
      expect(cp.connectivity.protocol).toBe('FIX');
    });
  });

  describe('updateCreditUsage', () => {
    it('should reject when credit limit exceeded', async () => {
      const cp = await createCounterparty({ creditLimit: 1000, creditUsed: 900 });
      
      await expect(
        service.updateCreditUsage(cp.id, 200)
      ).rejects.toThrow('Credit limit exceeded');
    });
  });
});
```
