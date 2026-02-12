# User Story: US-03-01 - Tenant Registration Flow

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-03-01 |
| **Epic** | Epic 03 - Multi-Tenancy |
| **Title** | Tenant Registration Flow |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |
| **PRD Reference** | FR-TENANT-01 |

## User Story

**As a** platform administrator  
**I want** to register new tenant organizations  
**So that** institutional clients can be onboarded to the platform

## Description

Implement the tenant registration workflow that creates new tenant records, initializes their configuration, and sets up the necessary database artifacts. This includes creating the tenant record, default settings, and publishing events for downstream services.

## Acceptance Criteria

- [ ] Admin can create new tenant via API
- [ ] Tenant record created with UUID identifier
- [ ] Default configuration initialized
- [ ] `tenant.created` event published to Kafka
- [ ] Duplicate tenant names rejected
- [ ] Tenant status supports: `pending`, `active`, `suspended`, `terminated`
- [ ] API validates required fields

## Technical Details

### Database Schema

```sql
-- Migration: 002_create_tenants.sql
CREATE TABLE tenants (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(255) NOT NULL,
  slug VARCHAR(100) NOT NULL UNIQUE,
  display_name VARCHAR(255) NOT NULL,
  status tenant_status NOT NULL DEFAULT 'pending',
  settings JSONB NOT NULL DEFAULT '{}',
  contact_email VARCHAR(255) NOT NULL,
  contact_name VARCHAR(255),
  address JSONB,
  regulatory_info JSONB,
  onboarded_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_by UUID,
  updated_by UUID
);

CREATE TYPE tenant_status AS ENUM ('pending', 'active', 'suspended', 'terminated');

CREATE INDEX idx_tenants_slug ON tenants(slug);
CREATE INDEX idx_tenants_status ON tenants(status);

-- Audit trigger
CREATE TRIGGER tenants_updated_at
  BEFORE UPDATE ON tenants
  FOR EACH ROW EXECUTE FUNCTION update_updated_at();
```

### Tenant Service Implementation

```typescript
// services/tenant-service/src/domain/tenant.entity.ts
import { z } from 'zod';

export const TenantStatus = z.enum(['pending', 'active', 'suspended', 'terminated']);
export type TenantStatus = z.infer<typeof TenantStatus>;

export const TenantSettingsSchema = z.object({
  timezone: z.string().default('UTC'),
  locale: z.string().default('en-US'),
  currency: z.string().default('USD'),
  features: z.object({
    rfqEnabled: z.boolean().default(true),
    omsEnabled: z.boolean().default(false),
    analyticsEnabled: z.boolean().default(false),
  }).default({}),
  limits: z.object({
    maxUsers: z.number().default(100),
    maxInstruments: z.number().default(1000),
    maxRfqPerDay: z.number().default(10000),
  }).default({}),
  integrations: z.object({
    fixEnabled: z.boolean().default(false),
    apiEnabled: z.boolean().default(true),
  }).default({}),
});

export type TenantSettings = z.infer<typeof TenantSettingsSchema>;

export interface Tenant {
  id: string;
  name: string;
  slug: string;
  displayName: string;
  status: TenantStatus;
  settings: TenantSettings;
  contactEmail: string;
  contactName?: string;
  address?: {
    line1: string;
    line2?: string;
    city: string;
    state?: string;
    postalCode: string;
    country: string;
  };
  regulatoryInfo?: {
    legalEntityId?: string;
    mifidClassification?: string;
    jurisdictions: string[];
  };
  onboardedAt?: Date;
  createdAt: Date;
  updatedAt: Date;
  createdBy?: string;
  updatedBy?: string;
}
```

```typescript
// services/tenant-service/src/application/tenant.service.ts
import { Injectable } from '@nestjs/common';
import { EventBus } from '@orion/event-model';
import { TenantRepository } from '../infrastructure/tenant.repository';
import { CreateTenantDto, UpdateTenantDto } from './tenant.dto';
import { Tenant, TenantSettingsSchema } from '../domain/tenant.entity';
import { ConflictException, NotFoundException } from '@nestjs/common';

@Injectable()
export class TenantService {
  constructor(
    private readonly tenantRepo: TenantRepository,
    private readonly eventBus: EventBus,
  ) {}

  async createTenant(dto: CreateTenantDto, createdBy: string): Promise<Tenant> {
    // Check for duplicate slug
    const existing = await this.tenantRepo.findBySlug(dto.slug);
    if (existing) {
      throw new ConflictException(`Tenant with slug '${dto.slug}' already exists`);
    }

    // Generate slug from name if not provided
    const slug = dto.slug || this.generateSlug(dto.name);

    // Initialize default settings
    const settings = TenantSettingsSchema.parse(dto.settings || {});

    const tenant = await this.tenantRepo.create({
      name: dto.name,
      slug,
      displayName: dto.displayName || dto.name,
      status: 'pending',
      settings,
      contactEmail: dto.contactEmail,
      contactName: dto.contactName,
      address: dto.address,
      regulatoryInfo: dto.regulatoryInfo,
      createdBy,
    });

    // Publish event for downstream services
    await this.eventBus.publish({
      eventType: 'tenant.created',
      aggregateType: 'tenant',
      aggregateId: tenant.id,
      payload: {
        tenantId: tenant.id,
        name: tenant.name,
        slug: tenant.slug,
        status: tenant.status,
        settings: tenant.settings,
      },
      metadata: {
        tenantId: tenant.id,
        userId: createdBy,
        correlationId: crypto.randomUUID(),
      },
    });

    return tenant;
  }

  async activateTenant(tenantId: string, updatedBy: string): Promise<Tenant> {
    const tenant = await this.tenantRepo.findById(tenantId);
    if (!tenant) {
      throw new NotFoundException(`Tenant ${tenantId} not found`);
    }

    if (tenant.status !== 'pending') {
      throw new ConflictException(`Tenant must be in 'pending' status to activate`);
    }

    const updated = await this.tenantRepo.update(tenantId, {
      status: 'active',
      onboardedAt: new Date(),
      updatedBy,
    });

    await this.eventBus.publish({
      eventType: 'tenant.activated',
      aggregateType: 'tenant',
      aggregateId: tenantId,
      payload: {
        tenantId,
        activatedAt: updated.onboardedAt,
      },
      metadata: {
        tenantId,
        userId: updatedBy,
        correlationId: crypto.randomUUID(),
      },
    });

    return updated;
  }

  async suspendTenant(tenantId: string, reason: string, updatedBy: string): Promise<Tenant> {
    const tenant = await this.tenantRepo.findById(tenantId);
    if (!tenant) {
      throw new NotFoundException(`Tenant ${tenantId} not found`);
    }

    const updated = await this.tenantRepo.update(tenantId, {
      status: 'suspended',
      updatedBy,
    });

    await this.eventBus.publish({
      eventType: 'tenant.suspended',
      aggregateType: 'tenant',
      aggregateId: tenantId,
      payload: {
        tenantId,
        reason,
        suspendedAt: new Date(),
      },
      metadata: {
        tenantId,
        userId: updatedBy,
        correlationId: crypto.randomUUID(),
      },
    });

    return updated;
  }

  async getTenant(tenantId: string): Promise<Tenant> {
    const tenant = await this.tenantRepo.findById(tenantId);
    if (!tenant) {
      throw new NotFoundException(`Tenant ${tenantId} not found`);
    }
    return tenant;
  }

  async listTenants(status?: string): Promise<Tenant[]> {
    return this.tenantRepo.findAll({ status });
  }

  private generateSlug(name: string): string {
    return name
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-|-$/g, '')
      .substring(0, 100);
  }
}
```

### REST API Endpoints

```typescript
// services/tenant-service/src/api/tenant.controller.ts
import { Controller, Post, Put, Get, Body, Param, Query, UseGuards } from '@nestjs/common';
import { ApiTags, ApiOperation, ApiResponse, ApiBearerAuth } from '@nestjs/swagger';
import { TenantService } from '../application/tenant.service';
import { CreateTenantDto, UpdateTenantDto, TenantResponseDto } from './tenant.dto';
import { AdminGuard } from '@orion/security';
import { CurrentUser, User } from '@orion/security';

@ApiTags('Tenants')
@Controller('admin/tenants')
@UseGuards(AdminGuard)
@ApiBearerAuth()
export class TenantController {
  constructor(private readonly tenantService: TenantService) {}

  @Post()
  @ApiOperation({ summary: 'Create a new tenant' })
  @ApiResponse({ status: 201, type: TenantResponseDto })
  @ApiResponse({ status: 409, description: 'Tenant slug already exists' })
  async createTenant(
    @Body() dto: CreateTenantDto,
    @CurrentUser() user: User,
  ): Promise<TenantResponseDto> {
    const tenant = await this.tenantService.createTenant(dto, user.id);
    return TenantResponseDto.fromEntity(tenant);
  }

  @Put(':tenantId/activate')
  @ApiOperation({ summary: 'Activate a pending tenant' })
  @ApiResponse({ status: 200, type: TenantResponseDto })
  async activateTenant(
    @Param('tenantId') tenantId: string,
    @CurrentUser() user: User,
  ): Promise<TenantResponseDto> {
    const tenant = await this.tenantService.activateTenant(tenantId, user.id);
    return TenantResponseDto.fromEntity(tenant);
  }

  @Put(':tenantId/suspend')
  @ApiOperation({ summary: 'Suspend an active tenant' })
  @ApiResponse({ status: 200, type: TenantResponseDto })
  async suspendTenant(
    @Param('tenantId') tenantId: string,
    @Body('reason') reason: string,
    @CurrentUser() user: User,
  ): Promise<TenantResponseDto> {
    const tenant = await this.tenantService.suspendTenant(tenantId, reason, user.id);
    return TenantResponseDto.fromEntity(tenant);
  }

  @Get(':tenantId')
  @ApiOperation({ summary: 'Get tenant details' })
  @ApiResponse({ status: 200, type: TenantResponseDto })
  async getTenant(@Param('tenantId') tenantId: string): Promise<TenantResponseDto> {
    const tenant = await this.tenantService.getTenant(tenantId);
    return TenantResponseDto.fromEntity(tenant);
  }

  @Get()
  @ApiOperation({ summary: 'List all tenants' })
  @ApiResponse({ status: 200, type: [TenantResponseDto] })
  async listTenants(@Query('status') status?: string): Promise<TenantResponseDto[]> {
    const tenants = await this.tenantService.listTenants(status);
    return tenants.map(TenantResponseDto.fromEntity);
  }
}
```

### DTO Definitions

```typescript
// services/tenant-service/src/api/tenant.dto.ts
import { IsString, IsEmail, IsOptional, IsObject, MaxLength, Matches } from 'class-validator';
import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { Tenant, TenantSettings } from '../domain/tenant.entity';

export class CreateTenantDto {
  @ApiProperty({ description: 'Legal entity name' })
  @IsString()
  @MaxLength(255)
  name: string;

  @ApiPropertyOptional({ description: 'URL-friendly identifier (auto-generated if not provided)' })
  @IsOptional()
  @IsString()
  @MaxLength(100)
  @Matches(/^[a-z0-9-]+$/, { message: 'Slug must be lowercase alphanumeric with hyphens' })
  slug?: string;

  @ApiPropertyOptional({ description: 'Display name for UI' })
  @IsOptional()
  @IsString()
  @MaxLength(255)
  displayName?: string;

  @ApiProperty({ description: 'Primary contact email' })
  @IsEmail()
  contactEmail: string;

  @ApiPropertyOptional({ description: 'Primary contact name' })
  @IsOptional()
  @IsString()
  contactName?: string;

  @ApiPropertyOptional({ description: 'Tenant settings override' })
  @IsOptional()
  @IsObject()
  settings?: Partial<TenantSettings>;

  @ApiPropertyOptional({ description: 'Business address' })
  @IsOptional()
  @IsObject()
  address?: {
    line1: string;
    line2?: string;
    city: string;
    state?: string;
    postalCode: string;
    country: string;
  };

  @ApiPropertyOptional({ description: 'Regulatory information' })
  @IsOptional()
  @IsObject()
  regulatoryInfo?: {
    legalEntityId?: string;
    mifidClassification?: string;
    jurisdictions: string[];
  };
}

export class TenantResponseDto {
  @ApiProperty() id: string;
  @ApiProperty() name: string;
  @ApiProperty() slug: string;
  @ApiProperty() displayName: string;
  @ApiProperty() status: string;
  @ApiProperty() settings: TenantSettings;
  @ApiProperty() contactEmail: string;
  @ApiPropertyOptional() contactName?: string;
  @ApiPropertyOptional() onboardedAt?: Date;
  @ApiProperty() createdAt: Date;
  @ApiProperty() updatedAt: Date;

  static fromEntity(tenant: Tenant): TenantResponseDto {
    return {
      id: tenant.id,
      name: tenant.name,
      slug: tenant.slug,
      displayName: tenant.displayName,
      status: tenant.status,
      settings: tenant.settings,
      contactEmail: tenant.contactEmail,
      contactName: tenant.contactName,
      onboardedAt: tenant.onboardedAt,
      createdAt: tenant.createdAt,
      updatedAt: tenant.updatedAt,
    };
  }
}
```

### Event Schema

```typescript
// libs/event-model/src/events/tenant.events.ts
import { OrionEvent } from '../envelope';

export interface TenantCreatedPayload {
  tenantId: string;
  name: string;
  slug: string;
  status: string;
  settings: Record<string, unknown>;
}

export interface TenantActivatedPayload {
  tenantId: string;
  activatedAt: Date;
}

export interface TenantSuspendedPayload {
  tenantId: string;
  reason: string;
  suspendedAt: Date;
}

export type TenantCreatedEvent = OrionEvent<'tenant.created', TenantCreatedPayload>;
export type TenantActivatedEvent = OrionEvent<'tenant.activated', TenantActivatedPayload>;
export type TenantSuspendedEvent = OrionEvent<'tenant.suspended', TenantSuspendedPayload>;
```

## Implementation Steps

1. **Create database migration**
   - Define tenant_status enum
   - Create tenants table with all columns
   - Add indexes and triggers

2. **Implement domain entity**
   - Define Tenant interface
   - Create Zod schemas for validation
   - Define settings schema with defaults

3. **Build repository layer**
   - Implement CRUD operations
   - Add findBySlug method
   - Handle status transitions

4. **Create service layer**
   - Implement business logic
   - Add validation rules
   - Integrate event publishing

5. **Expose REST API**
   - Create controller with endpoints
   - Add Swagger documentation
   - Implement admin authorization

6. **Add event schemas**
   - Define event types
   - Add to event catalog
   - Document event contracts

## Definition of Done

- [ ] Database migration creates tenants table
- [ ] Tenant CRUD operations work via API
- [ ] Events published on state changes
- [ ] Duplicate slug validation enforced
- [ ] Status transitions validated
- [ ] Admin-only access enforced
- [ ] API documented with Swagger
- [ ] Unit tests cover service logic
- [ ] Integration tests verify database operations

## Dependencies

- **US-01-03**: Shared Event Model Library
- **US-01-04**: Shared Security Library
- **US-01-10**: Database Migration Framework

## Test Cases

```typescript
describe('TenantService', () => {
  describe('createTenant', () => {
    it('should create tenant with generated UUID', async () => {
      const dto = {
        name: 'Acme Trading',
        contactEmail: 'admin@acme.com',
      };
      const tenant = await service.createTenant(dto, 'admin-user-id');
      
      expect(tenant.id).toMatch(/^[0-9a-f-]{36}$/);
      expect(tenant.slug).toBe('acme-trading');
      expect(tenant.status).toBe('pending');
    });

    it('should reject duplicate slug', async () => {
      await service.createTenant({ name: 'Test', slug: 'test-co', contactEmail: 'a@b.com' }, 'admin');
      
      await expect(
        service.createTenant({ name: 'Test 2', slug: 'test-co', contactEmail: 'b@c.com' }, 'admin')
      ).rejects.toThrow(ConflictException);
    });

    it('should publish tenant.created event', async () => {
      const dto = { name: 'New Tenant', contactEmail: 'new@tenant.com' };
      await service.createTenant(dto, 'admin');
      
      expect(eventBus.publish).toHaveBeenCalledWith(
        expect.objectContaining({
          eventType: 'tenant.created',
          aggregateType: 'tenant',
        })
      );
    });
  });

  describe('activateTenant', () => {
    it('should transition pending tenant to active', async () => {
      const tenant = await createPendingTenant();
      const activated = await service.activateTenant(tenant.id, 'admin');
      
      expect(activated.status).toBe('active');
      expect(activated.onboardedAt).toBeInstanceOf(Date);
    });

    it('should reject activation of non-pending tenant', async () => {
      const tenant = await createActiveTenant();
      
      await expect(
        service.activateTenant(tenant.id, 'admin')
      ).rejects.toThrow(ConflictException);
    });
  });
});
```

## Notes

- Tenant registration is admin-only; users cannot self-register tenants
- Consider implementing tenant provisioning workflow for complex setups
- Settings can be extended per tenant without schema changes
- Regulatory info structure may need jurisdiction-specific fields
