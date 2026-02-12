# User Story: US-03-04 - Tenant Configuration Service

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-03-04 |
| **Epic** | Epic 03 - Multi-Tenancy |
| **Title** | Tenant Configuration Service |
| **Priority** | P1 - High |
| **Story Points** | 5 |
| **PRD Reference** | FR-TENANT-04 |

## User Story

**As a** tenant administrator  
**I want** to configure tenant-specific settings  
**So that** my organization's preferences are applied platform-wide

## Description

Implement a configuration service that stores and retrieves tenant-specific settings. This enables customization of features, limits, trading preferences, and UI branding per tenant without code changes.

## Acceptance Criteria

- [ ] Configuration stored in database with caching
- [ ] Hierarchical config: defaults → tenant overrides
- [ ] Hot-reload without service restart
- [ ] Config changes audited
- [ ] Feature flags per tenant
- [ ] API for admin to update settings

## Technical Details

### Configuration Schema

```typescript
// services/tenant-service/src/domain/tenant-config.types.ts
import { z } from 'zod';

export const TenantConfigSchema = z.object({
  // Feature Flags
  features: z.object({
    rfq: z.object({
      enabled: z.boolean().default(true),
      maxQuoteValidity: z.number().default(60), // seconds
      autoRejectTimeout: z.number().default(300), // seconds
      allowPartialFills: z.boolean().default(true),
    }).default({}),
    oms: z.object({
      enabled: z.boolean().default(false),
      orderTypes: z.array(z.enum(['market', 'limit', 'stop', 'stop_limit'])).default(['market', 'limit']),
      maxOrderSize: z.number().optional(),
    }).default({}),
    analytics: z.object({
      enabled: z.boolean().default(false),
      retentionDays: z.number().default(90),
      exportFormats: z.array(z.string()).default(['csv', 'xlsx']),
    }).default({}),
    marketData: z.object({
      streamingEnabled: z.boolean().default(true),
      snapshotOnly: z.boolean().default(false),
      maxSubscriptions: z.number().default(1000),
    }).default({}),
  }).default({}),

  // Trading Limits
  limits: z.object({
    maxDailyVolume: z.number().optional(),
    maxSingleTradeSize: z.number().optional(),
    maxOpenRfqs: z.number().default(100),
    maxPendingOrders: z.number().default(500),
    rateLimits: z.object({
      rfqPerMinute: z.number().default(60),
      ordersPerMinute: z.number().default(120),
      apiCallsPerMinute: z.number().default(1000),
    }).default({}),
  }).default({}),

  // Preferences
  preferences: z.object({
    timezone: z.string().default('UTC'),
    locale: z.string().default('en-US'),
    defaultCurrency: z.string().default('USD'),
    dateFormat: z.string().default('YYYY-MM-DD'),
    numberFormat: z.object({
      decimalSeparator: z.string().default('.'),
      thousandsSeparator: z.string().default(','),
      decimalPlaces: z.number().default(2),
    }).default({}),
  }).default({}),

  // Branding
  branding: z.object({
    primaryColor: z.string().default('#1976d2'),
    logoUrl: z.string().optional(),
    faviconUrl: z.string().optional(),
    companyName: z.string().optional(),
  }).default({}),

  // Notifications
  notifications: z.object({
    email: z.object({
      tradeConfirmations: z.boolean().default(true),
      dailySummary: z.boolean().default(false),
      alertsEnabled: z.boolean().default(true),
    }).default({}),
    webhook: z.object({
      enabled: z.boolean().default(false),
      url: z.string().optional(),
      events: z.array(z.string()).default([]),
    }).default({}),
  }).default({}),

  // Compliance
  compliance: z.object({
    requireApprovalAbove: z.number().optional(),
    mandatoryFields: z.array(z.string()).default([]),
    auditRetentionYears: z.number().default(7),
  }).default({}),
});

export type TenantConfig = z.infer<typeof TenantConfigSchema>;
```

### Configuration Service

```typescript
// services/tenant-service/src/application/config.service.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { Redis } from 'ioredis';
import { TenantConfigSchema, TenantConfig } from '../domain/tenant-config.types';
import { TenantRepository } from '../infrastructure/tenant.repository';
import { EventBus } from '@orion/event-model';
import { logger } from '@orion/observability';
import deepmerge from 'deepmerge';

const DEFAULT_CONFIG = TenantConfigSchema.parse({});
const CACHE_TTL = 300; // 5 minutes
const CACHE_PREFIX = 'tenant:config:';

@Injectable()
export class TenantConfigService implements OnModuleInit {
  private localCache = new Map<string, { config: TenantConfig; expiry: number }>();

  constructor(
    private readonly tenantRepo: TenantRepository,
    private readonly redis: Redis,
    private readonly eventBus: EventBus,
  ) {}

  async onModuleInit() {
    // Subscribe to config change events for cache invalidation
    await this.eventBus.subscribe('tenant.config.updated', async (event) => {
      await this.invalidateCache(event.payload.tenantId);
    });
  }

  /**
   * Get tenant configuration with caching
   */
  async getConfig(tenantId: string): Promise<TenantConfig> {
    // Check local cache first
    const localCached = this.localCache.get(tenantId);
    if (localCached && localCached.expiry > Date.now()) {
      return localCached.config;
    }

    // Check Redis cache
    const redisCached = await this.redis.get(`${CACHE_PREFIX}${tenantId}`);
    if (redisCached) {
      const config = TenantConfigSchema.parse(JSON.parse(redisCached));
      this.setLocalCache(tenantId, config);
      return config;
    }

    // Load from database
    const tenant = await this.tenantRepo.findById(tenantId);
    if (!tenant) {
      logger.warn('Tenant not found, returning defaults', { tenantId });
      return DEFAULT_CONFIG;
    }

    // Merge defaults with tenant overrides
    const config = this.mergeWithDefaults(tenant.settings as Partial<TenantConfig>);
    
    // Cache the result
    await this.cacheConfig(tenantId, config);
    
    return config;
  }

  /**
   * Get specific config value with type safety
   */
  async getConfigValue<K extends keyof TenantConfig>(
    tenantId: string,
    key: K,
  ): Promise<TenantConfig[K]> {
    const config = await this.getConfig(tenantId);
    return config[key];
  }

  /**
   * Check if a feature is enabled for tenant
   */
  async isFeatureEnabled(
    tenantId: string,
    feature: 'rfq' | 'oms' | 'analytics' | 'marketData',
  ): Promise<boolean> {
    const config = await this.getConfig(tenantId);
    return config.features[feature]?.enabled ?? false;
  }

  /**
   * Update tenant configuration
   */
  async updateConfig(
    tenantId: string,
    updates: Partial<TenantConfig>,
    updatedBy: string,
  ): Promise<TenantConfig> {
    const current = await this.getConfig(tenantId);
    const merged = deepmerge(current, updates) as TenantConfig;
    
    // Validate the merged config
    const validated = TenantConfigSchema.parse(merged);

    // Store in database
    await this.tenantRepo.update(tenantId, {
      settings: validated,
      updatedBy,
    });

    // Invalidate caches
    await this.invalidateCache(tenantId);

    // Publish event
    await this.eventBus.publish({
      eventType: 'tenant.config.updated',
      aggregateType: 'tenant',
      aggregateId: tenantId,
      payload: {
        tenantId,
        changes: updates,
        updatedBy,
      },
      metadata: {
        tenantId,
        userId: updatedBy,
        correlationId: crypto.randomUUID(),
      },
    });

    return validated;
  }

  /**
   * Reset config to defaults
   */
  async resetConfig(tenantId: string, updatedBy: string): Promise<TenantConfig> {
    await this.tenantRepo.update(tenantId, {
      settings: {},
      updatedBy,
    });

    await this.invalidateCache(tenantId);

    await this.eventBus.publish({
      eventType: 'tenant.config.reset',
      aggregateType: 'tenant',
      aggregateId: tenantId,
      payload: { tenantId, updatedBy },
      metadata: { tenantId, userId: updatedBy, correlationId: crypto.randomUUID() },
    });

    return DEFAULT_CONFIG;
  }

  private mergeWithDefaults(overrides: Partial<TenantConfig>): TenantConfig {
    return TenantConfigSchema.parse(deepmerge(DEFAULT_CONFIG, overrides || {}));
  }

  private async cacheConfig(tenantId: string, config: TenantConfig): Promise<void> {
    const serialized = JSON.stringify(config);
    await this.redis.setex(`${CACHE_PREFIX}${tenantId}`, CACHE_TTL, serialized);
    this.setLocalCache(tenantId, config);
  }

  private setLocalCache(tenantId: string, config: TenantConfig): void {
    this.localCache.set(tenantId, {
      config,
      expiry: Date.now() + (CACHE_TTL * 1000),
    });
  }

  private async invalidateCache(tenantId: string): Promise<void> {
    await this.redis.del(`${CACHE_PREFIX}${tenantId}`);
    this.localCache.delete(tenantId);
    logger.info('Config cache invalidated', { tenantId });
  }
}
```

### API Endpoints

```typescript
// services/tenant-service/src/api/config.controller.ts
import { Controller, Get, Put, Delete, Body, Param, UseGuards } from '@nestjs/common';
import { ApiTags, ApiOperation, ApiBearerAuth } from '@nestjs/swagger';
import { TenantConfigService } from '../application/config.service';
import { TenantConfig } from '../domain/tenant-config.types';
import { TenantAdminGuard } from '@orion/security';
import { CurrentUser, User, CurrentTenant } from '@orion/security';

@ApiTags('Tenant Configuration')
@Controller('tenants/:tenantId/config')
@UseGuards(TenantAdminGuard)
@ApiBearerAuth()
export class TenantConfigController {
  constructor(private readonly configService: TenantConfigService) {}

  @Get()
  @ApiOperation({ summary: 'Get tenant configuration' })
  async getConfig(
    @Param('tenantId') tenantId: string,
    @CurrentTenant('tenantId') currentTenant: string,
  ): Promise<TenantConfig> {
    // Verify access (tenant admin can only see their own config)
    if (tenantId !== currentTenant) {
      throw new ForbiddenException('Cannot access other tenant configuration');
    }
    return this.configService.getConfig(tenantId);
  }

  @Get(':section')
  @ApiOperation({ summary: 'Get specific config section' })
  async getConfigSection(
    @Param('tenantId') tenantId: string,
    @Param('section') section: keyof TenantConfig,
  ): Promise<unknown> {
    return this.configService.getConfigValue(tenantId, section);
  }

  @Put()
  @ApiOperation({ summary: 'Update tenant configuration' })
  async updateConfig(
    @Param('tenantId') tenantId: string,
    @Body() updates: Partial<TenantConfig>,
    @CurrentUser() user: User,
  ): Promise<TenantConfig> {
    return this.configService.updateConfig(tenantId, updates, user.id);
  }

  @Put(':section')
  @ApiOperation({ summary: 'Update specific config section' })
  async updateConfigSection(
    @Param('tenantId') tenantId: string,
    @Param('section') section: keyof TenantConfig,
    @Body() updates: unknown,
    @CurrentUser() user: User,
  ): Promise<TenantConfig> {
    return this.configService.updateConfig(
      tenantId,
      { [section]: updates } as Partial<TenantConfig>,
      user.id,
    );
  }

  @Delete()
  @ApiOperation({ summary: 'Reset configuration to defaults' })
  async resetConfig(
    @Param('tenantId') tenantId: string,
    @CurrentUser() user: User,
  ): Promise<TenantConfig> {
    return this.configService.resetConfig(tenantId, user.id);
  }
}
```

### Feature Flag Helper

```typescript
// libs/security/src/features/feature-flag.service.ts
import { Injectable } from '@nestjs/common';
import { TenantConfigService } from '../../../services/tenant-service/src/application/config.service';
import { getCurrentTenant } from '../tenant-context/tenant-context';

@Injectable()
export class FeatureFlagService {
  constructor(private readonly configService: TenantConfigService) {}

  async isEnabled(feature: string): Promise<boolean> {
    const tenant = getCurrentTenant();
    if (!tenant) {
      return false;
    }

    const [category, subFeature] = feature.split('.');
    
    switch (category) {
      case 'rfq':
        return this.configService.isFeatureEnabled(tenant.tenantId, 'rfq');
      case 'oms':
        return this.configService.isFeatureEnabled(tenant.tenantId, 'oms');
      case 'analytics':
        return this.configService.isFeatureEnabled(tenant.tenantId, 'analytics');
      default:
        return false;
    }
  }

  async requireFeature(feature: string): Promise<void> {
    const enabled = await this.isEnabled(feature);
    if (!enabled) {
      throw new ForbiddenException(`Feature '${feature}' is not enabled for your organization`);
    }
  }
}

// Decorator for controller methods
export function RequireFeature(feature: string) {
  return applyDecorators(
    UseGuards(FeatureGuard),
    SetMetadata('required_feature', feature),
  );
}
```

## Implementation Steps

1. **Define config schema**
   - Create Zod schema with defaults
   - Document all settings
   - Version schema for migrations

2. **Build config service**
   - Implement caching layers
   - Add merge logic
   - Handle invalidation

3. **Create API endpoints**
   - Full config CRUD
   - Section-specific updates
   - Reset functionality

4. **Add feature flags**
   - Helper service
   - Guard decorator
   - Runtime checks

5. **Integration with services**
   - Inject config where needed
   - Use feature flags
   - React to changes

## Definition of Done

- [ ] Config schema validated with Zod
- [ ] Two-level caching (local + Redis)
- [ ] Hot-reload on config changes
- [ ] API endpoints functional
- [ ] Feature flags implemented
- [ ] Config changes audited
- [ ] Tests verify caching

## Dependencies

- **US-03-01**: Tenant Registration (tenants table)
- **US-01-02**: Docker Compose (Redis)

## Test Cases

```typescript
describe('TenantConfigService', () => {
  it('should return defaults for missing config', async () => {
    const config = await service.getConfig('new-tenant');
    expect(config.features.rfq.enabled).toBe(true);
    expect(config.preferences.timezone).toBe('UTC');
  });

  it('should merge overrides with defaults', async () => {
    await tenantRepo.update('tenant-1', {
      settings: { features: { oms: { enabled: true } } },
    });
    
    const config = await service.getConfig('tenant-1');
    expect(config.features.oms.enabled).toBe(true);
    expect(config.features.rfq.enabled).toBe(true); // default preserved
  });

  it('should cache config in Redis', async () => {
    await service.getConfig('tenant-1');
    
    const cached = await redis.get('tenant:config:tenant-1');
    expect(cached).toBeTruthy();
  });

  it('should invalidate cache on update', async () => {
    await service.getConfig('tenant-1');
    await service.updateConfig('tenant-1', { preferences: { timezone: 'America/New_York' } }, 'admin');
    
    const cached = await redis.get('tenant:config:tenant-1');
    expect(cached).toBeNull();
  });
});
```
