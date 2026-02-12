# US-14-05: System Configuration

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-14-05 |
| **Epic** | Epic 14: Admin Console |
| **Title** | System Configuration |
| **Priority** | Medium |
| **Story Points** | 5 |
| **Status** | Ready for Development |

## User Story

**As a** platform administrator  
**I want** to configure system-wide settings  
**So that** I can customize platform behavior without code changes

## Acceptance Criteria

### AC1: Feature Flags
- **Given** I have config management permission
- **When** I view feature flags
- **Then** I can:
  - See all available flags
  - Toggle feature on/off
  - Set tenant-specific overrides

### AC2: Rate Limiting
- **Given** the rate limit settings
- **When** I configure limits
- **Then** I can set:
  - API rate limits per tier
  - WebSocket connection limits
  - Order throttling rules

### AC3: Notification Settings
- **Given** system notification config
- **When** I configure notifications
- **Then** I can set:
  - Email templates
  - Alert thresholds
  - Notification channels

### AC4: Integration Settings
- **Given** third-party integrations
- **When** I view configurations
- **Then** I can:
  - Manage API keys
  - Configure webhooks
  - Set callback URLs

### AC5: Environment Config
- **Given** environment settings
- **When** I update config
- **Then**:
  - Changes are validated
  - Audit log is created
  - Changes can be rolled back

## Technical Specification

### Configuration Dashboard

```typescript
// src/features/config/components/ConfigDashboard.tsx
import React from 'react';
import * as Tabs from '@radix-ui/react-tabs';
import { FeatureFlagsConfig } from './FeatureFlagsConfig';
import { RateLimitConfig } from './RateLimitConfig';
import { NotificationConfig } from './NotificationConfig';
import { IntegrationConfig } from './IntegrationConfig';
import { EnvironmentConfig } from './EnvironmentConfig';

export const ConfigDashboard: React.FC = () => {
  return (
    <div className="config-dashboard">
      <div className="dashboard-header">
        <h1>System Configuration</h1>
        <p className="description">
          Manage platform-wide settings and feature flags
        </p>
      </div>

      <Tabs.Root defaultValue="features" className="config-tabs">
        <Tabs.List className="tabs-list">
          <Tabs.Trigger value="features" className="tab-trigger">
            Feature Flags
          </Tabs.Trigger>
          <Tabs.Trigger value="ratelimits" className="tab-trigger">
            Rate Limits
          </Tabs.Trigger>
          <Tabs.Trigger value="notifications" className="tab-trigger">
            Notifications
          </Tabs.Trigger>
          <Tabs.Trigger value="integrations" className="tab-trigger">
            Integrations
          </Tabs.Trigger>
          <Tabs.Trigger value="environment" className="tab-trigger">
            Environment
          </Tabs.Trigger>
        </Tabs.List>

        <Tabs.Content value="features" className="tab-content">
          <FeatureFlagsConfig />
        </Tabs.Content>

        <Tabs.Content value="ratelimits" className="tab-content">
          <RateLimitConfig />
        </Tabs.Content>

        <Tabs.Content value="notifications" className="tab-content">
          <NotificationConfig />
        </Tabs.Content>

        <Tabs.Content value="integrations" className="tab-content">
          <IntegrationConfig />
        </Tabs.Content>

        <Tabs.Content value="environment" className="tab-content">
          <EnvironmentConfig />
        </Tabs.Content>
      </Tabs.Root>
    </div>
  );
};
```

### Feature Flags Component

```typescript
// src/features/config/components/FeatureFlagsConfig.tsx
import React, { useState } from 'react';
import * as Switch from '@radix-ui/react-switch';
import { useFeatureFlags, useUpdateFeatureFlag } from '../hooks/useFeatureFlags';
import { FeatureFlag, FlagOverride } from '../types/config.types';
import { TenantOverrideDialog } from './TenantOverrideDialog';

export const FeatureFlagsConfig: React.FC = () => {
  const { data: flags, isLoading } = useFeatureFlags();
  const { mutate: updateFlag } = useUpdateFeatureFlag();
  const [selectedFlag, setSelectedFlag] = useState<FeatureFlag | null>(null);

  const handleToggle = (flag: FeatureFlag, enabled: boolean) => {
    updateFlag({ flagId: flag.id, enabled });
  };

  if (isLoading) return <div className="loading">Loading flags...</div>;

  const groupedFlags = groupByCategory(flags || []);

  return (
    <div className="feature-flags-config">
      <div className="config-section-header">
        <h2>Feature Flags</h2>
        <p>Enable or disable platform features globally or per tenant</p>
      </div>

      {Object.entries(groupedFlags).map(([category, categoryFlags]) => (
        <div key={category} className="flag-category">
          <h3 className="category-title">{category}</h3>
          <div className="flags-list">
            {categoryFlags.map((flag: FeatureFlag) => (
              <div key={flag.id} className="flag-item">
                <div className="flag-info">
                  <div className="flag-header">
                    <span className="flag-name">{flag.name}</span>
                    <span className={`flag-status ${flag.enabled ? 'enabled' : 'disabled'}`}>
                      {flag.enabled ? 'Enabled' : 'Disabled'}
                    </span>
                  </div>
                  <p className="flag-description">{flag.description}</p>
                  {flag.overrides && flag.overrides.length > 0 && (
                    <span className="override-count">
                      {flag.overrides.length} tenant override(s)
                    </span>
                  )}
                </div>

                <div className="flag-actions">
                  <Switch.Root
                    className="switch-root"
                    checked={flag.enabled}
                    onCheckedChange={(checked) => handleToggle(flag, checked)}
                  >
                    <Switch.Thumb className="switch-thumb" />
                  </Switch.Root>

                  <button
                    className="btn-icon"
                    onClick={() => setSelectedFlag(flag)}
                    title="Tenant overrides"
                  >
                    ⚙️
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>
      ))}

      {selectedFlag && (
        <TenantOverrideDialog
          flag={selectedFlag}
          onClose={() => setSelectedFlag(null)}
        />
      )}
    </div>
  );
};

function groupByCategory(flags: FeatureFlag[]): Record<string, FeatureFlag[]> {
  return flags.reduce((acc, flag) => {
    const category = flag.category || 'General';
    if (!acc[category]) acc[category] = [];
    acc[category].push(flag);
    return acc;
  }, {} as Record<string, FeatureFlag[]>);
}
```

### Rate Limit Configuration

```typescript
// src/features/config/components/RateLimitConfig.tsx
import React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useRateLimits, useUpdateRateLimits } from '../hooks/useRateLimits';
import { RateLimitTier } from '../types/config.types';

const rateLimitSchema = z.object({
  tiers: z.array(z.object({
    name: z.string(),
    apiRequestsPerMinute: z.number().min(0),
    apiRequestsPerHour: z.number().min(0),
    websocketConnections: z.number().min(0),
    ordersPerSecond: z.number().min(0),
    ordersPerMinute: z.number().min(0),
  })),
});

type RateLimitFormData = z.infer<typeof rateLimitSchema>;

export const RateLimitConfig: React.FC = () => {
  const { data: rateLimits, isLoading } = useRateLimits();
  const { mutate: updateRateLimits, isPending } = useUpdateRateLimits();

  const {
    register,
    handleSubmit,
    formState: { errors, isDirty },
    reset,
  } = useForm<RateLimitFormData>({
    resolver: zodResolver(rateLimitSchema),
    defaultValues: { tiers: rateLimits?.tiers || [] },
  });

  const onSubmit = (data: RateLimitFormData) => {
    updateRateLimits(data, {
      onSuccess: () => reset(data),
    });
  };

  if (isLoading) return <div className="loading">Loading rate limits...</div>;

  return (
    <div className="rate-limit-config">
      <div className="config-section-header">
        <h2>Rate Limits</h2>
        <p>Configure API and order throttling by subscription tier</p>
      </div>

      <form onSubmit={handleSubmit(onSubmit)}>
        <div className="rate-limit-table">
          <div className="table-header">
            <span className="col-tier">Tier</span>
            <span className="col-api">API/min</span>
            <span className="col-api">API/hour</span>
            <span className="col-ws">WS Conns</span>
            <span className="col-order">Orders/sec</span>
            <span className="col-order">Orders/min</span>
          </div>

          {rateLimits?.tiers.map((tier: RateLimitTier, index: number) => (
            <div key={tier.name} className="table-row">
              <span className="col-tier">
                <span className={`tier-badge ${tier.name.toLowerCase()}`}>
                  {tier.name}
                </span>
              </span>
              <div className="col-api">
                <input
                  type="number"
                  {...register(`tiers.${index}.apiRequestsPerMinute`, { valueAsNumber: true })}
                />
              </div>
              <div className="col-api">
                <input
                  type="number"
                  {...register(`tiers.${index}.apiRequestsPerHour`, { valueAsNumber: true })}
                />
              </div>
              <div className="col-ws">
                <input
                  type="number"
                  {...register(`tiers.${index}.websocketConnections`, { valueAsNumber: true })}
                />
              </div>
              <div className="col-order">
                <input
                  type="number"
                  {...register(`tiers.${index}.ordersPerSecond`, { valueAsNumber: true })}
                />
              </div>
              <div className="col-order">
                <input
                  type="number"
                  {...register(`tiers.${index}.ordersPerMinute`, { valueAsNumber: true })}
                />
              </div>
            </div>
          ))}
        </div>

        <div className="form-actions">
          <button
            type="button"
            className="btn-secondary"
            onClick={() => reset()}
            disabled={!isDirty}
          >
            Reset
          </button>
          <button
            type="submit"
            className="btn-primary"
            disabled={!isDirty || isPending}
          >
            {isPending ? 'Saving...' : 'Save Changes'}
          </button>
        </div>
      </form>
    </div>
  );
};
```

### Integration Settings

```typescript
// src/features/config/components/IntegrationConfig.tsx
import React, { useState } from 'react';
import * as Accordion from '@radix-ui/react-accordion';
import { useIntegrations, useUpdateIntegration } from '../hooks/useIntegrations';
import { Integration, IntegrationType } from '../types/config.types';
import { WebhookConfigForm } from './WebhookConfigForm';
import { ApiKeyManager } from './ApiKeyManager';

export const IntegrationConfig: React.FC = () => {
  const { data: integrations, isLoading } = useIntegrations();

  if (isLoading) return <div className="loading">Loading integrations...</div>;

  return (
    <div className="integration-config">
      <div className="config-section-header">
        <h2>Integrations</h2>
        <p>Manage third-party service connections and webhooks</p>
      </div>

      <Accordion.Root type="multiple" className="integrations-accordion">
        {integrations?.map((integration: Integration) => (
          <Accordion.Item
            key={integration.id}
            value={integration.id}
            className="integration-item"
          >
            <Accordion.Header className="integration-header">
              <Accordion.Trigger className="integration-trigger">
                <div className="integration-info">
                  <span className="integration-icon">
                    {getIntegrationIcon(integration.type)}
                  </span>
                  <div className="integration-details">
                    <span className="integration-name">{integration.name}</span>
                    <span className={`integration-status ${integration.status}`}>
                      {integration.status}
                    </span>
                  </div>
                </div>
                <span className="chevron">▼</span>
              </Accordion.Trigger>
            </Accordion.Header>

            <Accordion.Content className="integration-content">
              <IntegrationForm integration={integration} />
            </Accordion.Content>
          </Accordion.Item>
        ))}
      </Accordion.Root>

      <div className="section-divider" />

      <div className="webhooks-section">
        <h3>Outbound Webhooks</h3>
        <WebhookConfigForm />
      </div>

      <div className="section-divider" />

      <div className="api-keys-section">
        <h3>API Keys</h3>
        <ApiKeyManager />
      </div>
    </div>
  );
};

const IntegrationForm: React.FC<{ integration: Integration }> = ({ integration }) => {
  const { mutate: updateIntegration } = useUpdateIntegration();

  // Render different forms based on integration type
  switch (integration.type) {
    case IntegrationType.MARKET_DATA:
      return <MarketDataIntegrationForm integration={integration} />;
    case IntegrationType.CLEARING:
      return <ClearingIntegrationForm integration={integration} />;
    case IntegrationType.NOTIFICATION:
      return <NotificationIntegrationForm integration={integration} />;
    default:
      return <GenericIntegrationForm integration={integration} />;
  }
};

function getIntegrationIcon(type: IntegrationType): string {
  const icons: Record<IntegrationType, string> = {
    MARKET_DATA: '📊',
    CLEARING: '🏦',
    NOTIFICATION: '🔔',
    ANALYTICS: '📈',
    STORAGE: '💾',
  };
  return icons[type] || '🔗';
}
```

### CSS Styles

```css
/* src/features/config/styles/config.css */

.config-dashboard {
  padding: 24px;
  max-width: 1200px;
  margin: 0 auto;
}

.dashboard-header {
  margin-bottom: 24px;
}

.dashboard-header h1 {
  margin-bottom: 4px;
}

.dashboard-header .description {
  color: var(--text-tertiary);
}

/* Config Section */
.config-section-header {
  margin-bottom: 24px;
}

.config-section-header h2 {
  font-size: 18px;
  margin-bottom: 4px;
}

.config-section-header p {
  font-size: 13px;
  color: var(--text-tertiary);
}

/* Feature Flags */
.flag-category {
  margin-bottom: 24px;
}

.category-title {
  font-size: 14px;
  text-transform: uppercase;
  color: var(--text-tertiary);
  letter-spacing: 0.5px;
  margin-bottom: 12px;
}

.flags-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.flag-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
}

.flag-info {
  flex: 1;
}

.flag-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 4px;
}

.flag-name {
  font-weight: 500;
}

.flag-status {
  font-size: 11px;
  padding: 2px 6px;
  border-radius: 4px;
}

.flag-status.enabled {
  background: rgba(76, 175, 80, 0.2);
  color: var(--success-color);
}

.flag-status.disabled {
  background: rgba(158, 158, 158, 0.2);
  color: var(--text-tertiary);
}

.flag-description {
  font-size: 13px;
  color: var(--text-secondary);
  margin: 0;
}

.override-count {
  font-size: 11px;
  color: var(--accent-color);
}

.flag-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

/* Switch */
.switch-root {
  width: 42px;
  height: 24px;
  background: var(--bg-tertiary);
  border-radius: 12px;
  position: relative;
  cursor: pointer;
}

.switch-root[data-state="checked"] {
  background: var(--accent-color);
}

.switch-thumb {
  display: block;
  width: 20px;
  height: 20px;
  background: white;
  border-radius: 50%;
  transition: transform 0.15s;
  transform: translateX(2px);
}

.switch-root[data-state="checked"] .switch-thumb {
  transform: translateX(20px);
}

/* Rate Limits Table */
.rate-limit-table {
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  overflow: hidden;
}

.table-header {
  display: grid;
  grid-template-columns: 120px repeat(5, 1fr);
  padding: 12px 16px;
  background: var(--bg-tertiary);
  font-size: 11px;
  text-transform: uppercase;
  color: var(--text-tertiary);
}

.table-row {
  display: grid;
  grid-template-columns: 120px repeat(5, 1fr);
  padding: 12px 16px;
  border-top: 1px solid var(--border-color);
  align-items: center;
}

.table-row input {
  width: 100%;
  padding: 8px;
  border: 1px solid var(--border-color);
  border-radius: 4px;
  background: var(--bg-primary);
  text-align: right;
}

.tier-badge {
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
}

.tier-badge.basic {
  background: var(--bg-tertiary);
  color: var(--text-secondary);
}

.tier-badge.professional {
  background: rgba(33, 150, 243, 0.2);
  color: var(--info-color);
}

.tier-badge.enterprise {
  background: rgba(156, 39, 176, 0.2);
  color: #9c27b0;
}

/* Form Actions */
.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 24px;
}

/* Integrations */
.integrations-accordion {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  overflow: hidden;
}

.integration-item {
  border-bottom: 1px solid var(--border-color);
}

.integration-item:last-child {
  border-bottom: none;
}

.integration-trigger {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
  padding: 16px;
  background: var(--bg-secondary);
  border: none;
  cursor: pointer;
  text-align: left;
}

.integration-info {
  display: flex;
  align-items: center;
  gap: 12px;
}

.integration-icon {
  font-size: 24px;
}

.integration-name {
  font-weight: 500;
}

.integration-status {
  font-size: 11px;
  padding: 2px 6px;
  border-radius: 4px;
}

.integration-status.active {
  background: rgba(76, 175, 80, 0.2);
  color: var(--success-color);
}

.integration-status.inactive {
  background: rgba(158, 158, 158, 0.2);
  color: var(--text-tertiary);
}

.integration-content {
  padding: 16px;
  background: var(--bg-primary);
}

.section-divider {
  margin: 32px 0;
  border-top: 1px solid var(--border-color);
}
```

## Definition of Done

- [ ] Feature flags management with tenant overrides
- [ ] Rate limit configuration by tier
- [ ] Notification template management
- [ ] Integration settings CRUD
- [ ] Webhook configuration
- [ ] API key management
- [ ] Configuration validation
- [ ] Audit logging for all changes
- [ ] Unit tests
- [ ] E2E tests

## Test Cases

```typescript
describe('FeatureFlagsConfig', () => {
  it('should toggle feature flag', async () => {
    render(<FeatureFlagsConfig />);
    
    const toggle = await screen.findByRole('switch', { name: /dark mode/i });
    fireEvent.click(toggle);
    
    expect(updateFlag).toHaveBeenCalledWith({ flagId: 'dark-mode', enabled: false });
  });
});

describe('RateLimitConfig', () => {
  it('should save rate limit changes', async () => {
    render(<RateLimitConfig />);
    
    const input = screen.getByLabelText('Professional API/min');
    fireEvent.change(input, { target: { value: '200' } });
    
    fireEvent.click(screen.getByText('Save Changes'));
    
    expect(updateRateLimits).toHaveBeenCalled();
  });
});
```
