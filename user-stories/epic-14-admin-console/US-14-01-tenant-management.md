# US-14-01: Tenant Management

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-14-01 |
| **Epic** | Epic 14: Admin Console |
| **Title** | Tenant Management |
| **Priority** | Critical |
| **Story Points** | 13 |
| **Status** | Ready for Development |

## User Story

**As a** platform administrator  
**I want** to manage tenants (organizations) in the platform  
**So that** I can onboard new clients and manage their entitlements

## Acceptance Criteria

### AC1: Tenant List View
- **Given** I am a platform admin
- **When** I navigate to tenant management
- **Then** I see a list of all tenants with:
  - Tenant name and ID
  - Status (Active/Suspended/Trial)
  - Tier (Standard/Professional/Enterprise)
  - User count
  - Created date

### AC2: Create Tenant
- **Given** I have create tenant permission
- **When** I create a new tenant
- **Then** I can specify:
  - Organization name
  - Primary contact
  - Tier and billing plan
  - Initial entitlements
  - Data residency region

### AC3: Edit Tenant
- **Given** an existing tenant
- **When** I edit tenant details
- **Then** I can modify:
  - Organization settings
  - Entitlements/features
  - Rate limits
  - Billing information

### AC4: Tenant Entitlements
- **Given** a tenant details view
- **When** I manage entitlements
- **Then** I can enable/disable:
  - Asset classes
  - Trading features
  - API access
  - Integration connectors

### AC5: Tenant Suspension
- **Given** I need to suspend a tenant
- **When** I perform suspension
- **Then**:
  - All users are logged out
  - API access is blocked
  - Data is preserved
  - Audit entry is created

## Technical Specification

### Tenant List Component

```typescript
// src/features/tenants/components/TenantList.tsx
import React, { useState, useMemo } from 'react';
import {
  useReactTable,
  getCoreRowModel,
  getFilteredRowModel,
  getPaginationRowModel,
  getSortedRowModel,
  ColumnDef,
  flexRender,
  SortingState,
} from '@tanstack/react-table';
import { useTenants } from '../hooks/useTenants';
import { Tenant, TenantStatus, TenantTier } from '../types/tenant.types';
import { TenantFilters } from './TenantFilters';
import { TenantActions } from './TenantActions';
import { StatusBadge } from '../../../shared/components/StatusBadge';
import { formatDate } from '../../../shared/utils/formatters';

export const TenantList: React.FC = () => {
  const [sorting, setSorting] = useState<SortingState>([]);
  const [globalFilter, setGlobalFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState<TenantStatus | null>(null);

  const { data: tenants, isLoading, error } = useTenants({
    status: statusFilter,
  });

  const columns = useMemo<ColumnDef<Tenant>[]>(() => [
    {
      accessorKey: 'name',
      header: 'Organization',
      cell: ({ row }) => (
        <div className="tenant-name-cell">
          <span className="tenant-name">{row.original.name}</span>
          <span className="tenant-id">{row.original.id}</span>
        </div>
      ),
    },
    {
      accessorKey: 'status',
      header: 'Status',
      cell: ({ getValue }) => (
        <StatusBadge status={getValue<TenantStatus>()} />
      ),
    },
    {
      accessorKey: 'tier',
      header: 'Tier',
      cell: ({ getValue }) => (
        <span className={`tier-badge tier-${getValue<TenantTier>().toLowerCase()}`}>
          {getValue<TenantTier>()}
        </span>
      ),
    },
    {
      accessorKey: 'userCount',
      header: 'Users',
      cell: ({ getValue, row }) => (
        <span>
          {getValue<number>()} / {row.original.maxUsers || '∞'}
        </span>
      ),
    },
    {
      accessorKey: 'dataRegion',
      header: 'Region',
    },
    {
      accessorKey: 'createdAt',
      header: 'Created',
      cell: ({ getValue }) => formatDate(getValue<string>()),
    },
    {
      id: 'actions',
      header: '',
      cell: ({ row }) => (
        <TenantActions tenant={row.original} />
      ),
    },
  ], []);

  const table = useReactTable({
    data: tenants || [],
    columns,
    state: {
      sorting,
      globalFilter,
    },
    onSortingChange: setSorting,
    onGlobalFilterChange: setGlobalFilter,
    getCoreRowModel: getCoreRowModel(),
    getFilteredRowModel: getFilteredRowModel(),
    getPaginationRowModel: getPaginationRowModel(),
    getSortedRowModel: getSortedRowModel(),
  });

  return (
    <div className="tenant-list">
      <div className="list-header">
        <h1>Tenant Management</h1>
        <button className="btn-primary" onClick={() => openCreateModal()}>
          + New Tenant
        </button>
      </div>

      <TenantFilters
        globalFilter={globalFilter}
        onGlobalFilterChange={setGlobalFilter}
        statusFilter={statusFilter}
        onStatusFilterChange={setStatusFilter}
      />

      <div className="table-container">
        <table className="data-table">
          <thead>
            {table.getHeaderGroups().map(headerGroup => (
              <tr key={headerGroup.id}>
                {headerGroup.headers.map(header => (
                  <th
                    key={header.id}
                    onClick={header.column.getToggleSortingHandler()}
                    className={header.column.getCanSort() ? 'sortable' : ''}
                  >
                    {flexRender(
                      header.column.columnDef.header,
                      header.getContext()
                    )}
                    {header.column.getIsSorted() && (
                      <span className="sort-indicator">
                        {header.column.getIsSorted() === 'asc' ? '↑' : '↓'}
                      </span>
                    )}
                  </th>
                ))}
              </tr>
            ))}
          </thead>
          <tbody>
            {table.getRowModel().rows.map(row => (
              <tr key={row.id} onClick={() => navigateToTenant(row.original.id)}>
                {row.getVisibleCells().map(cell => (
                  <td key={cell.id}>
                    {flexRender(
                      cell.column.columnDef.cell,
                      cell.getContext()
                    )}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="pagination">
        <button
          onClick={() => table.previousPage()}
          disabled={!table.getCanPreviousPage()}
        >
          Previous
        </button>
        <span>
          Page {table.getState().pagination.pageIndex + 1} of{' '}
          {table.getPageCount()}
        </span>
        <button
          onClick={() => table.nextPage()}
          disabled={!table.getCanNextPage()}
        >
          Next
        </button>
      </div>
    </div>
  );
};
```

### Create Tenant Form

```typescript
// src/features/tenants/components/CreateTenantForm.tsx
import React from 'react';
import { useForm, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import * as Dialog from '@radix-ui/react-dialog';
import { useCreateTenant } from '../hooks/useCreateTenant';
import { TenantTier, DataRegion } from '../types/tenant.types';

const createTenantSchema = z.object({
  name: z.string().min(2, 'Name must be at least 2 characters'),
  displayName: z.string().optional(),
  tier: z.nativeEnum(TenantTier),
  dataRegion: z.nativeEnum(DataRegion),
  primaryContact: z.object({
    name: z.string().min(1, 'Contact name is required'),
    email: z.string().email('Invalid email address'),
    phone: z.string().optional(),
  }),
  billing: z.object({
    planId: z.string(),
    billingEmail: z.string().email(),
    paymentMethod: z.enum(['invoice', 'credit_card']),
  }),
  settings: z.object({
    maxUsers: z.number().min(1).max(10000),
    maxApiRateLimit: z.number().min(100),
    retentionDays: z.number().min(30).max(2555),
  }),
  entitlements: z.object({
    assetClasses: z.array(z.string()),
    features: z.array(z.string()),
    integrations: z.array(z.string()),
  }),
});

type CreateTenantData = z.infer<typeof createTenantSchema>;

interface CreateTenantFormProps {
  isOpen: boolean;
  onClose: () => void;
}

export const CreateTenantForm: React.FC<CreateTenantFormProps> = ({
  isOpen,
  onClose,
}) => {
  const { mutate: createTenant, isPending } = useCreateTenant();

  const {
    register,
    control,
    handleSubmit,
    formState: { errors },
    reset,
  } = useForm<CreateTenantData>({
    resolver: zodResolver(createTenantSchema),
    defaultValues: {
      tier: TenantTier.STANDARD,
      dataRegion: DataRegion.US_EAST,
      settings: {
        maxUsers: 50,
        maxApiRateLimit: 1000,
        retentionDays: 365,
      },
      entitlements: {
        assetClasses: ['FX', 'EQUITY'],
        features: ['TRADING', 'REPORTING'],
        integrations: [],
      },
    },
  });

  const onSubmit = (data: CreateTenantData) => {
    createTenant(data, {
      onSuccess: () => {
        reset();
        onClose();
      },
    });
  };

  return (
    <Dialog.Root open={isOpen} onOpenChange={(open) => !open && onClose()}>
      <Dialog.Portal>
        <Dialog.Overlay className="dialog-overlay" />
        <Dialog.Content className="dialog-content dialog-large">
          <Dialog.Title className="dialog-title">
            Create New Tenant
          </Dialog.Title>

          <form onSubmit={handleSubmit(onSubmit)} className="tenant-form">
            {/* Organization Info */}
            <fieldset className="form-section">
              <legend>Organization Information</legend>
              
              <div className="form-row">
                <div className="form-field">
                  <label htmlFor="name">Organization Name *</label>
                  <input
                    id="name"
                    {...register('name')}
                    placeholder="Acme Capital"
                  />
                  {errors.name && (
                    <span className="error">{errors.name.message}</span>
                  )}
                </div>

                <div className="form-field">
                  <label htmlFor="displayName">Display Name</label>
                  <input
                    id="displayName"
                    {...register('displayName')}
                    placeholder="Acme"
                  />
                </div>
              </div>

              <div className="form-row">
                <div className="form-field">
                  <label htmlFor="tier">Service Tier *</label>
                  <Controller
                    name="tier"
                    control={control}
                    render={({ field }) => (
                      <select {...field}>
                        <option value={TenantTier.STANDARD}>Standard</option>
                        <option value={TenantTier.PROFESSIONAL}>Professional</option>
                        <option value={TenantTier.ENTERPRISE}>Enterprise</option>
                      </select>
                    )}
                  />
                </div>

                <div className="form-field">
                  <label htmlFor="dataRegion">Data Region *</label>
                  <Controller
                    name="dataRegion"
                    control={control}
                    render={({ field }) => (
                      <select {...field}>
                        <option value={DataRegion.US_EAST}>US East</option>
                        <option value={DataRegion.US_WEST}>US West</option>
                        <option value={DataRegion.EU_WEST}>EU West</option>
                        <option value={DataRegion.APAC}>Asia Pacific</option>
                      </select>
                    )}
                  />
                </div>
              </div>
            </fieldset>

            {/* Primary Contact */}
            <fieldset className="form-section">
              <legend>Primary Contact</legend>
              
              <div className="form-row">
                <div className="form-field">
                  <label htmlFor="contactName">Name *</label>
                  <input
                    id="contactName"
                    {...register('primaryContact.name')}
                  />
                  {errors.primaryContact?.name && (
                    <span className="error">{errors.primaryContact.name.message}</span>
                  )}
                </div>

                <div className="form-field">
                  <label htmlFor="contactEmail">Email *</label>
                  <input
                    id="contactEmail"
                    type="email"
                    {...register('primaryContact.email')}
                  />
                  {errors.primaryContact?.email && (
                    <span className="error">{errors.primaryContact.email.message}</span>
                  )}
                </div>
              </div>
            </fieldset>

            {/* Settings */}
            <fieldset className="form-section">
              <legend>Settings & Limits</legend>
              
              <div className="form-row">
                <div className="form-field">
                  <label htmlFor="maxUsers">Max Users</label>
                  <input
                    id="maxUsers"
                    type="number"
                    {...register('settings.maxUsers', { valueAsNumber: true })}
                  />
                </div>

                <div className="form-field">
                  <label htmlFor="maxApiRateLimit">API Rate Limit (req/min)</label>
                  <input
                    id="maxApiRateLimit"
                    type="number"
                    {...register('settings.maxApiRateLimit', { valueAsNumber: true })}
                  />
                </div>

                <div className="form-field">
                  <label htmlFor="retentionDays">Data Retention (days)</label>
                  <input
                    id="retentionDays"
                    type="number"
                    {...register('settings.retentionDays', { valueAsNumber: true })}
                  />
                </div>
              </div>
            </fieldset>

            {/* Entitlements */}
            <fieldset className="form-section">
              <legend>Entitlements</legend>
              
              <div className="form-field">
                <label>Asset Classes</label>
                <Controller
                  name="entitlements.assetClasses"
                  control={control}
                  render={({ field }) => (
                    <div className="checkbox-group">
                      {['FX', 'EQUITY', 'FIXED_INCOME', 'COMMODITIES', 'CRYPTO'].map(ac => (
                        <label key={ac} className="checkbox-label">
                          <input
                            type="checkbox"
                            checked={field.value.includes(ac)}
                            onChange={(e) => {
                              if (e.target.checked) {
                                field.onChange([...field.value, ac]);
                              } else {
                                field.onChange(field.value.filter(v => v !== ac));
                              }
                            }}
                          />
                          {ac}
                        </label>
                      ))}
                    </div>
                  )}
                />
              </div>

              <div className="form-field">
                <label>Features</label>
                <Controller
                  name="entitlements.features"
                  control={control}
                  render={({ field }) => (
                    <div className="checkbox-group">
                      {[
                        'TRADING',
                        'REPORTING',
                        'RISK_MANAGEMENT',
                        'ALGO_TRADING',
                        'FIX_CONNECTIVITY',
                        'API_ACCESS',
                      ].map(feature => (
                        <label key={feature} className="checkbox-label">
                          <input
                            type="checkbox"
                            checked={field.value.includes(feature)}
                            onChange={(e) => {
                              if (e.target.checked) {
                                field.onChange([...field.value, feature]);
                              } else {
                                field.onChange(field.value.filter(v => v !== feature));
                              }
                            }}
                          />
                          {feature.replace(/_/g, ' ')}
                        </label>
                      ))}
                    </div>
                  )}
                />
              </div>
            </fieldset>

            <div className="dialog-footer">
              <button type="button" className="btn-secondary" onClick={onClose}>
                Cancel
              </button>
              <button type="submit" className="btn-primary" disabled={isPending}>
                {isPending ? 'Creating...' : 'Create Tenant'}
              </button>
            </div>
          </form>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
};
```

### Tenant Detail View

```typescript
// src/features/tenants/components/TenantDetail.tsx
import React from 'react';
import { useParams } from 'react-router-dom';
import * as Tabs from '@radix-ui/react-tabs';
import { useTenant } from '../hooks/useTenant';
import { TenantOverview } from './TenantOverview';
import { TenantEntitlements } from './TenantEntitlements';
import { TenantUsers } from './TenantUsers';
import { TenantAuditLog } from './TenantAuditLog';
import { TenantSettings } from './TenantSettings';
import { TenantActions } from './TenantActions';

export const TenantDetail: React.FC = () => {
  const { tenantId } = useParams<{ tenantId: string }>();
  const { data: tenant, isLoading, error } = useTenant(tenantId!);

  if (isLoading) return <div className="loading">Loading tenant...</div>;
  if (error) return <div className="error">Error loading tenant</div>;
  if (!tenant) return <div className="not-found">Tenant not found</div>;

  return (
    <div className="tenant-detail">
      <div className="detail-header">
        <div className="header-info">
          <h1>{tenant.name}</h1>
          <span className="tenant-id">{tenant.id}</span>
          <span className={`status-badge status-${tenant.status.toLowerCase()}`}>
            {tenant.status}
          </span>
        </div>
        <TenantActions tenant={tenant} showAll />
      </div>

      <Tabs.Root defaultValue="overview" className="tenant-tabs">
        <Tabs.List className="tabs-list">
          <Tabs.Trigger value="overview" className="tab-trigger">
            Overview
          </Tabs.Trigger>
          <Tabs.Trigger value="entitlements" className="tab-trigger">
            Entitlements
          </Tabs.Trigger>
          <Tabs.Trigger value="users" className="tab-trigger">
            Users ({tenant.userCount})
          </Tabs.Trigger>
          <Tabs.Trigger value="settings" className="tab-trigger">
            Settings
          </Tabs.Trigger>
          <Tabs.Trigger value="audit" className="tab-trigger">
            Audit Log
          </Tabs.Trigger>
        </Tabs.List>

        <Tabs.Content value="overview" className="tab-content">
          <TenantOverview tenant={tenant} />
        </Tabs.Content>

        <Tabs.Content value="entitlements" className="tab-content">
          <TenantEntitlements tenantId={tenant.id} />
        </Tabs.Content>

        <Tabs.Content value="users" className="tab-content">
          <TenantUsers tenantId={tenant.id} />
        </Tabs.Content>

        <Tabs.Content value="settings" className="tab-content">
          <TenantSettings tenant={tenant} />
        </Tabs.Content>

        <Tabs.Content value="audit" className="tab-content">
          <TenantAuditLog tenantId={tenant.id} />
        </Tabs.Content>
      </Tabs.Root>
    </div>
  );
};
```

### Tenant API Service

```typescript
// src/features/tenants/services/tenant.api.ts
import { apiClient } from '../../../shared/services/api-client';
import {
  Tenant,
  CreateTenantRequest,
  UpdateTenantRequest,
  TenantEntitlements,
  TenantStatus,
} from '../types/tenant.types';

export const tenantApi = {
  // List tenants with filtering
  async listTenants(params?: {
    status?: TenantStatus;
    tier?: string;
    search?: string;
    page?: number;
    pageSize?: number;
  }): Promise<{ tenants: Tenant[]; total: number }> {
    const response = await apiClient.get('/admin/tenants', { params });
    return response.data;
  },

  // Get single tenant
  async getTenant(tenantId: string): Promise<Tenant> {
    const response = await apiClient.get(`/admin/tenants/${tenantId}`);
    return response.data;
  },

  // Create tenant
  async createTenant(data: CreateTenantRequest): Promise<Tenant> {
    const response = await apiClient.post('/admin/tenants', data);
    return response.data;
  },

  // Update tenant
  async updateTenant(
    tenantId: string,
    data: UpdateTenantRequest
  ): Promise<Tenant> {
    const response = await apiClient.patch(`/admin/tenants/${tenantId}`, data);
    return response.data;
  },

  // Update entitlements
  async updateEntitlements(
    tenantId: string,
    entitlements: TenantEntitlements
  ): Promise<void> {
    await apiClient.put(
      `/admin/tenants/${tenantId}/entitlements`,
      entitlements
    );
  },

  // Suspend tenant
  async suspendTenant(tenantId: string, reason: string): Promise<void> {
    await apiClient.post(`/admin/tenants/${tenantId}/suspend`, { reason });
  },

  // Reactivate tenant
  async reactivateTenant(tenantId: string): Promise<void> {
    await apiClient.post(`/admin/tenants/${tenantId}/reactivate`);
  },

  // Delete tenant (soft delete)
  async deleteTenant(tenantId: string): Promise<void> {
    await apiClient.delete(`/admin/tenants/${tenantId}`);
  },

  // Get tenant usage metrics
  async getTenantMetrics(tenantId: string): Promise<{
    apiCalls: number;
    activeUsers: number;
    storageUsedMb: number;
    ordersToday: number;
  }> {
    const response = await apiClient.get(`/admin/tenants/${tenantId}/metrics`);
    return response.data;
  },
};
```

### CSS Styles

```css
/* src/features/tenants/styles/tenant-management.css */

.tenant-list {
  padding: 24px;
}

.list-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.list-header h1 {
  font-size: 24px;
  font-weight: 600;
}

/* Tenant Name Cell */
.tenant-name-cell {
  display: flex;
  flex-direction: column;
}

.tenant-name {
  font-weight: 500;
  color: var(--text-primary);
}

.tenant-id {
  font-size: 11px;
  color: var(--text-tertiary);
  font-family: var(--font-mono);
}

/* Tier Badge */
.tier-badge {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
}

.tier-standard {
  background: #e3f2fd;
  color: #1565c0;
}

.tier-professional {
  background: #f3e5f5;
  color: #7b1fa2;
}

.tier-enterprise {
  background: #fff8e1;
  color: #f57c00;
}

/* Tenant Detail */
.tenant-detail {
  padding: 24px;
}

.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 24px;
  padding-bottom: 24px;
  border-bottom: 1px solid var(--border-color);
}

.header-info {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-info h1 {
  margin: 0;
  font-size: 28px;
}

/* Form Styles */
.tenant-form {
  max-height: 60vh;
  overflow-y: auto;
  padding-right: 16px;
}

.form-section {
  margin-bottom: 24px;
  padding: 16px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
}

.form-section legend {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-secondary);
  padding: 0 8px;
}

.form-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
  margin-bottom: 16px;
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-field label {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary);
}

.form-field input,
.form-field select {
  padding: 8px 12px;
  border: 1px solid var(--border-color);
  border-radius: 4px;
  background: var(--bg-secondary);
  color: var(--text-primary);
  font-size: 14px;
}

.form-field input:focus,
.form-field select:focus {
  outline: none;
  border-color: var(--accent-color);
}

.checkbox-group {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}

.checkbox-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  cursor: pointer;
}

.error {
  color: var(--error-color);
  font-size: 12px;
}
```

## Definition of Done

- [ ] Tenant list with search and filters
- [ ] Create tenant wizard
- [ ] Edit tenant details
- [ ] Entitlement management
- [ ] Suspend/reactivate functionality
- [ ] Audit logging for all actions
- [ ] RBAC enforcement
- [ ] Form validation
- [ ] Unit tests
- [ ] E2E tests

## Test Cases

```typescript
describe('TenantList', () => {
  it('should display tenants', async () => {
    render(<TenantList />);
    
    await waitFor(() => {
      expect(screen.getByText('Acme Capital')).toBeInTheDocument();
    });
  });

  it('should filter by status', async () => {
    render(<TenantList />);
    
    fireEvent.click(screen.getByText('Active'));
    
    await waitFor(() => {
      expect(screen.queryByText('Suspended Tenant')).not.toBeInTheDocument();
    });
  });
});

describe('CreateTenantForm', () => {
  it('should validate required fields', async () => {
    render(<CreateTenantForm isOpen={true} onClose={() => {}} />);
    
    fireEvent.click(screen.getByText('Create Tenant'));
    
    await waitFor(() => {
      expect(screen.getByText('Name must be at least 2 characters')).toBeInTheDocument();
    });
  });
});
```
