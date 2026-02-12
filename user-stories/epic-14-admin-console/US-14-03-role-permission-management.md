# US-14-03: Role & Permission Management

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-14-03 |
| **Epic** | Epic 14: Admin Console |
| **Title** | Role & Permission Management |
| **Priority** | High |
| **Story Points** | 8 |
| **Status** | Ready for Development |

## User Story

**As an** administrator  
**I want** to define and manage roles with granular permissions  
**So that** I can implement principle of least privilege access control

## Acceptance Criteria

### AC1: Role List View
- **Given** I have role management permission
- **When** I view the roles list
- **Then** I see:
  - Role name and description
  - System vs Custom role indicator
  - User count assigned
  - Permission summary

### AC2: Create Custom Role
- **Given** I have create role permission
- **When** I create a new role
- **Then** I can:
  - Set name and description
  - Select permissions from categories
  - Clone from existing role

### AC3: Permission Matrix
- **Given** the role editor
- **When** I configure permissions
- **Then** I see permissions grouped by:
  - Orders (create, view, amend, cancel)
  - Trades (view, export)
  - Positions (view, close)
  - Reports (generate, view, export)
  - Admin (user, tenant, config)

### AC4: Role Assignment
- **Given** a user profile
- **When** I assign roles
- **Then**:
  - Multiple roles can be assigned
  - Effective permissions are merged
  - Changes take effect immediately

### AC5: Role Audit
- **Given** role changes
- **When** permissions change
- **Then**:
  - All changes are logged
  - Before/after comparison available
  - Affected users listed

## Technical Specification

### Role List Component

```typescript
// src/features/roles/components/RoleList.tsx
import React from 'react';
import { useRoles } from '../hooks/useRoles';
import { Role, RoleType } from '../types/role.types';
import { RoleCard } from './RoleCard';

interface RoleListProps {
  tenantId?: string;
}

export const RoleList: React.FC<RoleListProps> = ({ tenantId }) => {
  const { data: roles, isLoading } = useRoles(tenantId);

  const systemRoles = roles?.filter(r => r.type === RoleType.SYSTEM) || [];
  const customRoles = roles?.filter(r => r.type === RoleType.CUSTOM) || [];

  return (
    <div className="role-list">
      <div className="list-header">
        <h1>Roles & Permissions</h1>
        <button className="btn-primary" onClick={() => openCreateModal()}>
          + Create Role
        </button>
      </div>

      <section className="role-section">
        <h2>System Roles</h2>
        <p className="section-description">
          Predefined roles that cannot be modified or deleted
        </p>
        <div className="role-grid">
          {systemRoles.map(role => (
            <RoleCard key={role.id} role={role} readOnly />
          ))}
        </div>
      </section>

      <section className="role-section">
        <h2>Custom Roles</h2>
        <p className="section-description">
          Organization-specific roles with custom permissions
        </p>
        {customRoles.length === 0 ? (
          <div className="empty-state">
            <p>No custom roles defined</p>
            <button className="btn-secondary">Create your first role</button>
          </div>
        ) : (
          <div className="role-grid">
            {customRoles.map(role => (
              <RoleCard key={role.id} role={role} />
            ))}
          </div>
        )}
      </section>
    </div>
  );
};
```

### Role Editor Component

```typescript
// src/features/roles/components/RoleEditor.tsx
import React, { useState, useMemo } from 'react';
import { useForm, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { usePermissions } from '../hooks/usePermissions';
import { useCreateRole, useUpdateRole } from '../hooks/useRoleMutations';
import { Role, Permission, PermissionCategory } from '../types/role.types';
import { PermissionMatrix } from './PermissionMatrix';

const roleSchema = z.object({
  name: z.string().min(2, 'Name must be at least 2 characters'),
  description: z.string().optional(),
  permissions: z.array(z.string()).min(1, 'At least one permission is required'),
});

type RoleFormData = z.infer<typeof roleSchema>;

interface RoleEditorProps {
  role?: Role;
  onClose: () => void;
}

export const RoleEditor: React.FC<RoleEditorProps> = ({ role, onClose }) => {
  const isEditing = !!role;
  const { data: allPermissions } = usePermissions();
  const { mutate: createRole, isPending: isCreating } = useCreateRole();
  const { mutate: updateRole, isPending: isUpdating } = useUpdateRole();

  const {
    register,
    control,
    handleSubmit,
    formState: { errors, isDirty },
    watch,
  } = useForm<RoleFormData>({
    resolver: zodResolver(roleSchema),
    defaultValues: {
      name: role?.name || '',
      description: role?.description || '',
      permissions: role?.permissions.map(p => p.id) || [],
    },
  });

  const selectedPermissions = watch('permissions');

  // Group permissions by category
  const permissionsByCategory = useMemo(() => {
    if (!allPermissions) return new Map<PermissionCategory, Permission[]>();
    
    return allPermissions.reduce((acc, permission) => {
      const category = permission.category;
      if (!acc.has(category)) {
        acc.set(category, []);
      }
      acc.get(category)!.push(permission);
      return acc;
    }, new Map<PermissionCategory, Permission[]>());
  }, [allPermissions]);

  const onSubmit = (data: RoleFormData) => {
    if (isEditing) {
      updateRole(
        { roleId: role.id, data },
        { onSuccess: onClose }
      );
    } else {
      createRole(data, { onSuccess: onClose });
    }
  };

  return (
    <div className="role-editor">
      <div className="editor-header">
        <h2>{isEditing ? 'Edit Role' : 'Create Role'}</h2>
        <button className="btn-icon" onClick={onClose}>×</button>
      </div>

      <form onSubmit={handleSubmit(onSubmit)}>
        <div className="form-section">
          <div className="form-field">
            <label htmlFor="name">Role Name *</label>
            <input
              id="name"
              {...register('name')}
              placeholder="e.g., Senior Trader"
            />
            {errors.name && (
              <span className="error">{errors.name.message}</span>
            )}
          </div>

          <div className="form-field">
            <label htmlFor="description">Description</label>
            <textarea
              id="description"
              {...register('description')}
              placeholder="Describe the role's purpose and responsibilities"
              rows={3}
            />
          </div>
        </div>

        <div className="form-section">
          <h3>Permissions</h3>
          <p className="section-hint">
            Select the permissions this role should have.
            {selectedPermissions.length} permission(s) selected.
          </p>

          <Controller
            name="permissions"
            control={control}
            render={({ field }) => (
              <PermissionMatrix
                permissionsByCategory={permissionsByCategory}
                selectedPermissions={field.value}
                onChange={field.onChange}
              />
            )}
          />
          {errors.permissions && (
            <span className="error">{errors.permissions.message}</span>
          )}
        </div>

        <div className="editor-footer">
          <button type="button" className="btn-secondary" onClick={onClose}>
            Cancel
          </button>
          <button
            type="submit"
            className="btn-primary"
            disabled={!isDirty || isCreating || isUpdating}
          >
            {isEditing ? 'Save Changes' : 'Create Role'}
          </button>
        </div>
      </form>
    </div>
  );
};
```

### Permission Matrix Component

```typescript
// src/features/roles/components/PermissionMatrix.tsx
import React from 'react';
import * as Accordion from '@radix-ui/react-accordion';
import { Permission, PermissionCategory } from '../types/role.types';

interface PermissionMatrixProps {
  permissionsByCategory: Map<PermissionCategory, Permission[]>;
  selectedPermissions: string[];
  onChange: (permissions: string[]) => void;
  readOnly?: boolean;
}

const CATEGORY_LABELS: Record<PermissionCategory, string> = {
  ORDERS: 'Order Management',
  TRADES: 'Trade Management',
  POSITIONS: 'Position Management',
  INSTRUMENTS: 'Instruments & Market Data',
  REPORTS: 'Reports & Analytics',
  RISK: 'Risk Management',
  ADMIN_USERS: 'User Administration',
  ADMIN_TENANTS: 'Tenant Administration',
  ADMIN_CONFIG: 'System Configuration',
};

export const PermissionMatrix: React.FC<PermissionMatrixProps> = ({
  permissionsByCategory,
  selectedPermissions,
  onChange,
  readOnly = false,
}) => {
  const isPermissionSelected = (permissionId: string) => {
    return selectedPermissions.includes(permissionId);
  };

  const togglePermission = (permissionId: string) => {
    if (readOnly) return;
    
    if (isPermissionSelected(permissionId)) {
      onChange(selectedPermissions.filter(p => p !== permissionId));
    } else {
      onChange([...selectedPermissions, permissionId]);
    }
  };

  const toggleCategory = (category: PermissionCategory) => {
    if (readOnly) return;
    
    const categoryPermissions = permissionsByCategory.get(category) || [];
    const categoryIds = categoryPermissions.map(p => p.id);
    const allSelected = categoryIds.every(id => selectedPermissions.includes(id));

    if (allSelected) {
      // Deselect all in category
      onChange(selectedPermissions.filter(p => !categoryIds.includes(p)));
    } else {
      // Select all in category
      const newPermissions = new Set([...selectedPermissions, ...categoryIds]);
      onChange(Array.from(newPermissions));
    }
  };

  const getCategorySelectionState = (category: PermissionCategory) => {
    const categoryPermissions = permissionsByCategory.get(category) || [];
    const selectedCount = categoryPermissions.filter(p => 
      selectedPermissions.includes(p.id)
    ).length;

    if (selectedCount === 0) return 'none';
    if (selectedCount === categoryPermissions.length) return 'all';
    return 'partial';
  };

  return (
    <Accordion.Root type="multiple" className="permission-matrix">
      {Array.from(permissionsByCategory.entries()).map(([category, permissions]) => {
        const selectionState = getCategorySelectionState(category);

        return (
          <Accordion.Item key={category} value={category} className="category-item">
            <Accordion.Header className="category-header">
              <div className="category-checkbox">
                <input
                  type="checkbox"
                  checked={selectionState === 'all'}
                  ref={(el) => {
                    if (el) el.indeterminate = selectionState === 'partial';
                  }}
                  onChange={() => toggleCategory(category)}
                  disabled={readOnly}
                />
              </div>
              <Accordion.Trigger className="category-trigger">
                <span className="category-name">
                  {CATEGORY_LABELS[category]}
                </span>
                <span className="category-count">
                  {permissions.filter(p => selectedPermissions.includes(p.id)).length}
                  /{permissions.length}
                </span>
                <span className="chevron">▼</span>
              </Accordion.Trigger>
            </Accordion.Header>

            <Accordion.Content className="category-content">
              <div className="permission-list">
                {permissions.map(permission => (
                  <label
                    key={permission.id}
                    className={`permission-item ${readOnly ? 'readonly' : ''}`}
                  >
                    <input
                      type="checkbox"
                      checked={isPermissionSelected(permission.id)}
                      onChange={() => togglePermission(permission.id)}
                      disabled={readOnly}
                    />
                    <div className="permission-info">
                      <span className="permission-name">{permission.name}</span>
                      <span className="permission-description">
                        {permission.description}
                      </span>
                    </div>
                  </label>
                ))}
              </div>
            </Accordion.Content>
          </Accordion.Item>
        );
      })}
    </Accordion.Root>
  );
};
```

### Role Types

```typescript
// src/features/roles/types/role.types.ts

export enum RoleType {
  SYSTEM = 'SYSTEM',
  CUSTOM = 'CUSTOM',
}

export enum PermissionCategory {
  ORDERS = 'ORDERS',
  TRADES = 'TRADES',
  POSITIONS = 'POSITIONS',
  INSTRUMENTS = 'INSTRUMENTS',
  REPORTS = 'REPORTS',
  RISK = 'RISK',
  ADMIN_USERS = 'ADMIN_USERS',
  ADMIN_TENANTS = 'ADMIN_TENANTS',
  ADMIN_CONFIG = 'ADMIN_CONFIG',
}

export interface Permission {
  id: string;
  code: string;
  name: string;
  description: string;
  category: PermissionCategory;
}

export interface Role {
  id: string;
  tenantId: string | null; // null for system roles
  name: string;
  description?: string;
  type: RoleType;
  permissions: Permission[];
  userCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface CreateRoleRequest {
  name: string;
  description?: string;
  permissions: string[]; // Permission IDs
}

export interface UpdateRoleRequest {
  name?: string;
  description?: string;
  permissions?: string[];
}

// Effective permissions for a user (merged from all roles)
export interface EffectivePermissions {
  userId: string;
  permissions: Set<string>;
  roles: Role[];
}
```

### CSS Styles

```css
/* src/features/roles/styles/role-management.css */

.role-list {
  padding: 24px;
}

.role-section {
  margin-bottom: 32px;
}

.role-section h2 {
  font-size: 18px;
  margin-bottom: 4px;
}

.section-description {
  color: var(--text-tertiary);
  font-size: 13px;
  margin-bottom: 16px;
}

.role-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
}

/* Role Card */
.role-card {
  padding: 20px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s;
}

.role-card:hover:not(.readonly) {
  border-color: var(--accent-color);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.role-card-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 12px;
}

.role-name {
  font-size: 16px;
  font-weight: 600;
}

.role-type-badge {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 10px;
  text-transform: uppercase;
}

.role-type-badge.system {
  background: var(--bg-tertiary);
  color: var(--text-secondary);
}

.role-type-badge.custom {
  background: var(--accent-color);
  color: white;
}

.role-description {
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 12px;
}

.role-stats {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: var(--text-tertiary);
}

/* Permission Matrix */
.permission-matrix {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  overflow: hidden;
}

.category-item {
  border-bottom: 1px solid var(--border-color);
}

.category-item:last-child {
  border-bottom: none;
}

.category-header {
  display: flex;
  align-items: center;
}

.category-checkbox {
  padding: 12px;
  border-right: 1px solid var(--border-color);
}

.category-trigger {
  flex: 1;
  display: flex;
  align-items: center;
  padding: 12px 16px;
  background: var(--bg-secondary);
  border: none;
  cursor: pointer;
  text-align: left;
}

.category-trigger:hover {
  background: var(--bg-hover);
}

.category-name {
  flex: 1;
  font-weight: 500;
}

.category-count {
  font-size: 12px;
  color: var(--text-tertiary);
  margin-right: 8px;
}

.chevron {
  font-size: 10px;
  transition: transform 0.2s;
}

.category-item[data-state="open"] .chevron {
  transform: rotate(180deg);
}

.category-content {
  overflow: hidden;
}

.permission-list {
  padding: 8px;
}

.permission-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px;
  border-radius: 4px;
  cursor: pointer;
}

.permission-item:hover:not(.readonly) {
  background: var(--bg-hover);
}

.permission-info {
  display: flex;
  flex-direction: column;
}

.permission-name {
  font-size: 13px;
  font-weight: 500;
}

.permission-description {
  font-size: 11px;
  color: var(--text-tertiary);
}

/* Editor */
.role-editor {
  max-width: 800px;
  margin: 0 auto;
  padding: 24px;
}

.editor-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.editor-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 24px;
  padding-top: 24px;
  border-top: 1px solid var(--border-color);
}
```

## Definition of Done

- [ ] Role list view with system/custom separation
- [ ] Create custom role with permission selection
- [ ] Edit role permissions
- [ ] Clone existing role
- [ ] Delete custom roles
- [ ] Permission matrix with categories
- [ ] Effective permissions calculation
- [ ] Audit logging for role changes
- [ ] Unit tests
- [ ] E2E tests

## Test Cases

```typescript
describe('RoleList', () => {
  it('should separate system and custom roles', async () => {
    render(<RoleList />);
    
    expect(screen.getByText('System Roles')).toBeInTheDocument();
    expect(screen.getByText('Custom Roles')).toBeInTheDocument();
  });
});

describe('PermissionMatrix', () => {
  it('should toggle category selection', () => {
    const onChange = jest.fn();
    render(
      <PermissionMatrix
        permissionsByCategory={mockPermissions}
        selectedPermissions={[]}
        onChange={onChange}
      />
    );

    fireEvent.click(screen.getByText('Order Management'));
    // All permissions in category should be selected
    expect(onChange).toHaveBeenCalled();
  });

  it('should show indeterminate state for partial selection', () => {
    render(
      <PermissionMatrix
        permissionsByCategory={mockPermissions}
        selectedPermissions={['orders.create']}
        onChange={() => {}}
      />
    );

    const checkbox = screen.getByRole('checkbox', { name: /orders/i });
    expect(checkbox.indeterminate).toBe(true);
  });
});
```
