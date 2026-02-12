# User Story: US-04-01 - Instrument Management

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-04-01 |
| **Epic** | Epic 04 - Reference Data Management |
| **Title** | Instrument Management |
| **Priority** | P0 - Critical |
| **Story Points** | 8 |
| **PRD Reference** | FR-REF-01, FR-REF-02 |

## User Story

**As a** trading operations manager  
**I want** to manage tradeable instruments  
**So that** traders can execute RFQs and orders on properly defined instruments

## Description

Implement comprehensive instrument management supporting multiple asset classes (FX, Crypto, Equities, Fixed Income). Each instrument has asset-class-specific attributes, validation rules, and lifecycle states.

## Acceptance Criteria

- [ ] Create, update, deactivate instruments via API
- [ ] Support FX, Crypto, Equity, Fixed Income asset classes
- [ ] Validate symbol uniqueness per tenant
- [ ] Enforce asset-class-specific required fields
- [ ] Publish `instrument.created/updated/deactivated` events
- [ ] gRPC API for internal service lookups
- [ ] REST API for admin management

## Technical Details

### Database Schema

```sql
-- Migration: 010_create_instruments.sql
CREATE TYPE asset_class AS ENUM ('fx', 'crypto', 'equity', 'fixed_income', 'commodity');
CREATE TYPE instrument_status AS ENUM ('active', 'suspended', 'deactivated');

CREATE TABLE instruments (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  
  -- Core identification
  symbol VARCHAR(50) NOT NULL,
  name VARCHAR(255) NOT NULL,
  description TEXT,
  asset_class asset_class NOT NULL,
  status instrument_status NOT NULL DEFAULT 'active',
  
  -- Trading attributes
  base_currency VARCHAR(10),
  quote_currency VARCHAR(10),
  tick_size DECIMAL(20, 10) NOT NULL,
  lot_size DECIMAL(20, 10) DEFAULT 1,
  min_quantity DECIMAL(20, 10) DEFAULT 0,
  max_quantity DECIMAL(20, 10),
  price_precision INT NOT NULL DEFAULT 2,
  quantity_precision INT NOT NULL DEFAULT 2,
  
  -- Market identifiers
  isin VARCHAR(12),
  cusip VARCHAR(9),
  sedol VARCHAR(7),
  bloomberg_ticker VARCHAR(50),
  reuters_ric VARCHAR(50),
  exchange VARCHAR(50),
  
  -- Asset-class specific (JSONB for flexibility)
  attributes JSONB NOT NULL DEFAULT '{}',
  
  -- Metadata
  tags VARCHAR(50)[],
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_by UUID,
  updated_by UUID,
  
  CONSTRAINT instruments_tenant_symbol_unique UNIQUE (tenant_id, symbol)
);

-- Enable RLS
ALTER TABLE instruments ENABLE ROW LEVEL SECURITY;
ALTER TABLE instruments FORCE ROW LEVEL SECURITY;

CREATE POLICY instruments_tenant_isolation ON instruments
  FOR ALL USING (tenant_id = current_tenant_id() OR is_admin_context())
  WITH CHECK (tenant_id = current_tenant_id() OR is_admin_context());

-- Indexes
CREATE INDEX idx_instruments_symbol ON instruments(tenant_id, symbol);
CREATE INDEX idx_instruments_asset_class ON instruments(tenant_id, asset_class);
CREATE INDEX idx_instruments_status ON instruments(tenant_id, status);
CREATE INDEX idx_instruments_tags ON instruments USING GIN(tags);
```

### Domain Model

```typescript
// services/reference-data-service/src/domain/instrument.entity.ts
import { z } from 'zod';

export const AssetClass = z.enum(['fx', 'crypto', 'equity', 'fixed_income', 'commodity']);
export type AssetClass = z.infer<typeof AssetClass>;

export const InstrumentStatus = z.enum(['active', 'suspended', 'deactivated']);
export type InstrumentStatus = z.infer<typeof InstrumentStatus>;

// Asset-class-specific attributes
export const FxAttributesSchema = z.object({
  settlementDays: z.number().default(2),
  quoteBasis: z.enum(['european', 'american']).default('european'),
  ndfCurrency: z.string().optional(),
  isNdf: z.boolean().default(false),
});

export const CryptoAttributesSchema = z.object({
  network: z.string().optional(),
  contractAddress: z.string().optional(),
  decimals: z.number().default(18),
});

export const EquityAttributesSchema = z.object({
  exchange: z.string(),
  sector: z.string().optional(),
  industry: z.string().optional(),
  marketCap: z.enum(['large', 'mid', 'small']).optional(),
  dividendYield: z.number().optional(),
});

export const FixedIncomeAttributesSchema = z.object({
  maturityDate: z.string(),
  couponRate: z.number(),
  couponFrequency: z.enum(['annual', 'semi-annual', 'quarterly', 'monthly']),
  issuer: z.string(),
  creditRating: z.string().optional(),
  faceValue: z.number().default(1000),
});

export const InstrumentSchema = z.object({
  id: z.string().uuid(),
  tenantId: z.string().uuid(),
  symbol: z.string().min(1).max(50),
  name: z.string().min(1).max(255),
  description: z.string().optional(),
  assetClass: AssetClass,
  status: InstrumentStatus,
  baseCurrency: z.string().length(3).optional(),
  quoteCurrency: z.string().length(3).optional(),
  tickSize: z.number().positive(),
  lotSize: z.number().positive().default(1),
  minQuantity: z.number().nonnegative().default(0),
  maxQuantity: z.number().positive().optional(),
  pricePrecision: z.number().int().min(0).max(10).default(2),
  quantityPrecision: z.number().int().min(0).max(10).default(2),
  isin: z.string().length(12).optional(),
  cusip: z.string().length(9).optional(),
  sedol: z.string().length(7).optional(),
  bloombergTicker: z.string().optional(),
  reutersRic: z.string().optional(),
  exchange: z.string().optional(),
  attributes: z.record(z.unknown()).default({}),
  tags: z.array(z.string()).default([]),
  createdAt: z.date(),
  updatedAt: z.date(),
  createdBy: z.string().uuid().optional(),
  updatedBy: z.string().uuid().optional(),
});

export type Instrument = z.infer<typeof InstrumentSchema>;
```

### Service Implementation

```typescript
// services/reference-data-service/src/application/instrument.service.ts
import { Injectable, NotFoundException, ConflictException } from '@nestjs/common';
import { InstrumentRepository } from '../infrastructure/instrument.repository';
import { EventBus } from '@orion/event-model';
import { AuditService } from '@orion/observability';
import { 
  Instrument, 
  AssetClass,
  FxAttributesSchema,
  CryptoAttributesSchema,
  EquityAttributesSchema,
  FixedIncomeAttributesSchema,
} from '../domain/instrument.entity';
import { CreateInstrumentDto, UpdateInstrumentDto } from './instrument.dto';
import { getCurrentTenant } from '@orion/security';

@Injectable()
export class InstrumentService {
  constructor(
    private readonly repo: InstrumentRepository,
    private readonly eventBus: EventBus,
    private readonly audit: AuditService,
  ) {}

  async createInstrument(dto: CreateInstrumentDto): Promise<Instrument> {
    const { tenantId, userId } = getCurrentTenant()!;
    
    // Check for duplicate symbol
    const existing = await this.repo.findBySymbol(dto.symbol);
    if (existing) {
      throw new ConflictException(`Instrument with symbol '${dto.symbol}' already exists`);
    }

    // Validate asset-class-specific attributes
    this.validateAssetClassAttributes(dto.assetClass, dto.attributes);

    // Validate currency pair for FX
    if (dto.assetClass === 'fx' && (!dto.baseCurrency || !dto.quoteCurrency)) {
      throw new BadRequestException('FX instruments require baseCurrency and quoteCurrency');
    }

    const instrument = await this.repo.create({
      ...dto,
      tenantId,
      status: 'active',
      createdBy: userId,
    });

    await this.eventBus.publish({
      eventType: 'instrument.created',
      aggregateType: 'instrument',
      aggregateId: instrument.id,
      payload: {
        instrumentId: instrument.id,
        symbol: instrument.symbol,
        assetClass: instrument.assetClass,
      },
      metadata: { tenantId, userId, correlationId: crypto.randomUUID() },
    });

    await this.audit.log({
      action: 'CREATE',
      entityType: 'INSTRUMENT',
      entityId: instrument.id,
      newValue: instrument,
    });

    return instrument;
  }

  async updateInstrument(id: string, dto: UpdateInstrumentDto): Promise<Instrument> {
    const { tenantId, userId } = getCurrentTenant()!;
    
    const existing = await this.repo.findById(id);
    if (!existing) {
      throw new NotFoundException(`Instrument ${id} not found`);
    }

    // Don't allow changing asset class
    if (dto.assetClass && dto.assetClass !== existing.assetClass) {
      throw new BadRequestException('Cannot change instrument asset class');
    }

    // Validate attributes if provided
    if (dto.attributes) {
      this.validateAssetClassAttributes(existing.assetClass, dto.attributes);
    }

    const updated = await this.repo.update(id, {
      ...dto,
      updatedBy: userId,
    });

    await this.eventBus.publish({
      eventType: 'instrument.updated',
      aggregateType: 'instrument',
      aggregateId: id,
      payload: {
        instrumentId: id,
        symbol: updated.symbol,
        changes: dto,
      },
      metadata: { tenantId, userId, correlationId: crypto.randomUUID() },
    });

    await this.audit.log({
      action: 'UPDATE',
      entityType: 'INSTRUMENT',
      entityId: id,
      oldValue: existing,
      newValue: updated,
    });

    return updated;
  }

  async deactivateInstrument(id: string): Promise<Instrument> {
    const { tenantId, userId } = getCurrentTenant()!;
    
    const existing = await this.repo.findById(id);
    if (!existing) {
      throw new NotFoundException(`Instrument ${id} not found`);
    }

    if (existing.status === 'deactivated') {
      throw new ConflictException('Instrument already deactivated');
    }

    const updated = await this.repo.update(id, {
      status: 'deactivated',
      updatedBy: userId,
    });

    await this.eventBus.publish({
      eventType: 'instrument.deactivated',
      aggregateType: 'instrument',
      aggregateId: id,
      payload: { instrumentId: id, symbol: updated.symbol },
      metadata: { tenantId, userId, correlationId: crypto.randomUUID() },
    });

    return updated;
  }

  async getInstrument(id: string): Promise<Instrument> {
    const instrument = await this.repo.findById(id);
    if (!instrument) {
      throw new NotFoundException(`Instrument ${id} not found`);
    }
    return instrument;
  }

  async getInstrumentBySymbol(symbol: string): Promise<Instrument> {
    const instrument = await this.repo.findBySymbol(symbol);
    if (!instrument) {
      throw new NotFoundException(`Instrument with symbol '${symbol}' not found`);
    }
    return instrument;
  }

  async listInstruments(filters: {
    assetClass?: AssetClass;
    status?: string;
    tags?: string[];
    search?: string;
    page?: number;
    limit?: number;
  }): Promise<{ items: Instrument[]; total: number }> {
    return this.repo.findAll(filters);
  }

  private validateAssetClassAttributes(assetClass: AssetClass, attributes: unknown): void {
    const schemaMap = {
      fx: FxAttributesSchema,
      crypto: CryptoAttributesSchema,
      equity: EquityAttributesSchema,
      fixed_income: FixedIncomeAttributesSchema,
      commodity: z.object({}),
    };

    const schema = schemaMap[assetClass];
    if (schema) {
      schema.parse(attributes);
    }
  }
}
```

### gRPC Service Definition

```protobuf
// proto/reference_data.proto
syntax = "proto3";

package orion.referencedata.v1;

service InstrumentService {
  rpc GetInstrument(GetInstrumentRequest) returns (Instrument);
  rpc GetInstrumentBySymbol(GetInstrumentBySymbolRequest) returns (Instrument);
  rpc ListInstruments(ListInstrumentsRequest) returns (ListInstrumentsResponse);
  rpc CreateInstrument(CreateInstrumentRequest) returns (Instrument);
  rpc UpdateInstrument(UpdateInstrumentRequest) returns (Instrument);
}

message Instrument {
  string id = 1;
  string symbol = 2;
  string name = 3;
  string asset_class = 4;
  string status = 5;
  string base_currency = 6;
  string quote_currency = 7;
  string tick_size = 8;
  string lot_size = 9;
  string min_quantity = 10;
  string max_quantity = 11;
  int32 price_precision = 12;
  int32 quantity_precision = 13;
  map<string, string> attributes = 14;
  repeated string tags = 15;
  google.protobuf.Timestamp created_at = 16;
  google.protobuf.Timestamp updated_at = 17;
}

message GetInstrumentRequest {
  string id = 1;
}

message GetInstrumentBySymbolRequest {
  string symbol = 1;
}

message ListInstrumentsRequest {
  string asset_class = 1;
  string status = 2;
  repeated string tags = 3;
  string search = 4;
  int32 page = 5;
  int32 limit = 6;
}

message ListInstrumentsResponse {
  repeated Instrument items = 1;
  int32 total = 2;
}
```

### REST Controller

```typescript
// services/reference-data-service/src/api/instrument.controller.ts
import { Controller, Get, Post, Put, Delete, Body, Param, Query, UseGuards } from '@nestjs/common';
import { ApiTags, ApiOperation, ApiBearerAuth } from '@nestjs/swagger';
import { InstrumentService } from '../application/instrument.service';
import { CreateInstrumentDto, UpdateInstrumentDto, InstrumentQueryDto } from './instrument.dto';
import { JwtAuthGuard, Permissions } from '@orion/security';

@ApiTags('Instruments')
@Controller('instruments')
@UseGuards(JwtAuthGuard)
@ApiBearerAuth()
export class InstrumentController {
  constructor(private readonly instrumentService: InstrumentService) {}

  @Post()
  @Permissions('instrument:create')
  @ApiOperation({ summary: 'Create a new instrument' })
  async createInstrument(@Body() dto: CreateInstrumentDto) {
    return this.instrumentService.createInstrument(dto);
  }

  @Get()
  @Permissions('instrument:read')
  @ApiOperation({ summary: 'List instruments with filtering' })
  async listInstruments(@Query() query: InstrumentQueryDto) {
    return this.instrumentService.listInstruments(query);
  }

  @Get(':id')
  @Permissions('instrument:read')
  @ApiOperation({ summary: 'Get instrument by ID' })
  async getInstrument(@Param('id') id: string) {
    return this.instrumentService.getInstrument(id);
  }

  @Get('symbol/:symbol')
  @Permissions('instrument:read')
  @ApiOperation({ summary: 'Get instrument by symbol' })
  async getInstrumentBySymbol(@Param('symbol') symbol: string) {
    return this.instrumentService.getInstrumentBySymbol(symbol);
  }

  @Put(':id')
  @Permissions('instrument:update')
  @ApiOperation({ summary: 'Update instrument' })
  async updateInstrument(
    @Param('id') id: string,
    @Body() dto: UpdateInstrumentDto,
  ) {
    return this.instrumentService.updateInstrument(id, dto);
  }

  @Delete(':id')
  @Permissions('instrument:delete')
  @ApiOperation({ summary: 'Deactivate instrument' })
  async deactivateInstrument(@Param('id') id: string) {
    return this.instrumentService.deactivateInstrument(id);
  }
}
```

## Implementation Steps

1. **Create database schema**
   - Define enums for asset class and status
   - Create instruments table with RLS
   - Add indexes for queries

2. **Build domain model**
   - Define Zod schemas
   - Create asset-class-specific validation
   - Implement entity types

3. **Implement service layer**
   - CRUD operations
   - Event publishing
   - Audit logging

4. **Create gRPC service**
   - Define proto file
   - Implement server handlers
   - Generate client stubs

5. **Build REST API**
   - Controller with Swagger docs
   - DTO validation
   - Permission guards

6. **Write tests**
   - Unit tests for validation
   - Integration tests for CRUD
   - gRPC client tests

## Definition of Done

- [ ] Database schema deployed
- [ ] All asset classes supported
- [ ] Validation enforced
- [ ] Events published
- [ ] gRPC and REST APIs work
- [ ] Permissions checked
- [ ] Tests achieve 80% coverage

## Dependencies

- **US-03-02**: Row-Level Security
- **US-01-06**: Protobuf Definitions

## Test Cases

```typescript
describe('InstrumentService', () => {
  describe('createInstrument', () => {
    it('should create FX instrument with currencies', async () => {
      const instrument = await service.createInstrument({
        symbol: 'EUR/USD',
        name: 'Euro/US Dollar',
        assetClass: 'fx',
        baseCurrency: 'EUR',
        quoteCurrency: 'USD',
        tickSize: 0.00001,
        pricePrecision: 5,
      });
      
      expect(instrument.id).toBeDefined();
      expect(instrument.status).toBe('active');
    });

    it('should reject FX without currencies', async () => {
      await expect(service.createInstrument({
        symbol: 'EUR/USD',
        name: 'Euro/US Dollar',
        assetClass: 'fx',
        tickSize: 0.00001,
      })).rejects.toThrow(BadRequestException);
    });

    it('should reject duplicate symbol', async () => {
      await service.createInstrument({
        symbol: 'BTCUSD',
        name: 'Bitcoin/USD',
        assetClass: 'crypto',
        tickSize: 0.01,
      });

      await expect(service.createInstrument({
        symbol: 'BTCUSD',
        name: 'Another BTC',
        assetClass: 'crypto',
        tickSize: 0.01,
      })).rejects.toThrow(ConflictException);
    });
  });

  describe('validateAssetClassAttributes', () => {
    it('should validate fixed income attributes', () => {
      expect(() => service.createInstrument({
        symbol: 'BOND-001',
        name: 'Test Bond',
        assetClass: 'fixed_income',
        tickSize: 0.01,
        attributes: {
          maturityDate: '2030-01-01',
          couponRate: 5.5,
          couponFrequency: 'semi-annual',
          issuer: 'Test Corp',
        },
      })).not.toThrow();
    });
  });
});
```
