# US-14-02: User Management

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-14-02 |
| **Epic** | Epic 14: Admin Console |
| **Title** | User Management |
| **Priority** | Critical |
| **Story Points** | 13 |
| **Status** | Ready for Development |

## User Story

**As a** platform or tenant administrator  
**I want** to manage users within tenants  
**So that** I can control access and permissions for team members

## Acceptance Criteria

### AC1: User List View
- **Given** I am an admin with user management permission
- **When** I view the user list
- **Then** I see:
  - User name and email
  - Status (Active/Locked/Pending)
  - Assigned roles
  - Last login timestamp
  - MFA status

### AC2: Create User
- **Given** I have create user permission
- **When** I create a new user
- **Then** I can specify:
  - Name and email
  - Initial role assignment
  - MFA requirement
  - Password policy

### AC3: Edit User
- **Given** an existing user
- **When** I edit user details
- **Then** I can modify:
  - Profile information
  - Role assignments
  - Account status
  - API key access

### AC4: Bulk Operations
- **Given** multiple users selected
- **When** I perform bulk action
- **Then** I can:
  - Assign/remove roles
  - Enable/disable accounts
  - Send password reset emails

### AC5: User Session Management
- **Given** a user account
- **When** I view sessions
- **Then** I can:
  - See active sessions
  - Revoke specific sessions
  - Force logout all sessions

## Technical Specification

### User List Component

```typescript
// src/features/users/components/UserList.tsx
import React, { useState, useMemo, useCallback } from 'react';
import {
  useReactTable,
  getCoreRowModel,
  getFilteredRowModel,
  getPaginationRowModel,
  getSortedRowModel,
  ColumnDef,
  flexRender,
  RowSelectionState,
} from '@tanstack/react-table';
import { useUsers } from '../hooks/useUsers';
import { User, UserStatus } from '../types/user.types';
import { UserFilters } from './UserFilters';
import { UserBulkActions } from './UserBulkActions';
import { UserActions } from './UserActions';
import { StatusBadge } from '../../../shared/components/StatusBadge';
import { Avatar } from '../../../shared/components/Avatar';
import { formatRelativeTime } from '../../../shared/utils/formatters';

interface UserListProps {
  tenantId?: string;
}

export const UserList: React.FC<UserListProps> = ({ tenantId }) => {
  const [globalFilter, setGlobalFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState<UserStatus | null>(null);
  const [roleFilter, setRoleFilter] = useState<string | null>(null);
  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  const { data, isLoading, error } = useUsers({
    tenantId,
    status: statusFilter,
    role: roleFilter,
  });

  const columns = useMemo<ColumnDef<User>[]>(() => [
    {
      id: 'select',
      header: ({ table }) => (
        <input
          type="checkbox"
          checked={table.getIsAllPageRowsSelected()}
          onChange={table.getToggleAllPageRowsSelectedHandler()}
        />
      ),
      cell: ({ row }) => (
        <input
          type="checkbox"
          checked={row.getIsSelected()}
          onChange={row.getToggleSelectedHandler()}
          onClick={(e) => e.stopPropagation()}
        />
      ),
    },
    {
      accessorKey: 'name',
      header: 'User',
      cell: ({ row }) => (
        <div className="user-cell">
          <Avatar name={row.original.name} src={row.original.avatarUrl} />
          <div className="user-info">
            <span className="user-name">{row.original.name}</span>
            <span className="user-email">{row.original.email}</span>
          </div>
        </div>
      ),
    },
    {
      accessorKey: 'status',
      header: 'Status',
      cell: ({ getValue }) => <StatusBadge status={getValue<UserStatus>()} />,
    },
    {
      accessorKey: 'roles',
      header: 'Roles',
      cell: ({ getValue }) => (
        <div className="role-badges">
          {getValue<string[]>().slice(0, 2).map(role => (
            <span key={role} className="role-badge">{role}</span>
          ))}
          {getValue<string[]>().length > 2 && (
            <span className="role-badge more">
              +{getValue<string[]>().length - 2}
            </span>
          )}
        </div>
      ),
    },
    {
      accessorKey: 'mfaEnabled',
      header: 'MFA',
      cell: ({ getValue }) => (
        <span className={`mfa-status ${getValue() ? 'enabled' : 'disabled'}`}>
          {getValue() ? '✓ Enabled' : '✗ Disabled'}
        </span>
      ),
    },
    {
      accessorKey: 'lastLoginAt',
      header: 'Last Login',
      cell: ({ getValue }) => {
        const value = getValue<string | null>();
        return value ? formatRelativeTime(value) : 'Never';
      },
    },
    {
      id: 'actions',
      header: '',
      cell: ({ row }) => <UserActions user={row.original} />,
    },
  ], []);

  const table = useReactTable({
    data: data?.users || [],
    columns,
    state: {
      globalFilter,
      rowSelection,
    },
    onGlobalFilterChange: setGlobalFilter,
    onRowSelectionChange: setRowSelection,
    getCoreRowModel: getCoreRowModel(),
    getFilteredRowModel: getFilteredRowModel(),
    getPaginationRowModel: getPaginationRowModel(),
    getSortedRowModel: getSortedRowModel(),
    enableRowSelection: true,
  });

  const selectedUsers = useMemo(() => {
    return table.getSelectedRowModel().rows.map(row => row.original);
  }, [table.getSelectedRowModel().rows]);

  const handleBulkActionComplete = useCallback(() => {
    setRowSelection({});
  }, []);

  return (
    <div className="user-list">
      <div className="list-header">
        <h1>User Management</h1>
        <button className="btn-primary" onClick={() => openCreateModal()}>
          + New User
        </button>
      </div>

      <UserFilters
        globalFilter={globalFilter}
        onGlobalFilterChange={setGlobalFilter}
        statusFilter={statusFilter}
        onStatusFilterChange={setStatusFilter}
        roleFilter={roleFilter}
        onRoleFilterChange={setRoleFilter}
      />

      {selectedUsers.length > 0 && (
        <UserBulkActions
          selectedUsers={selectedUsers}
          onComplete={handleBulkActionComplete}
        />
      )}

      <div className="table-container">
        <table className="data-table">
          <thead>
            {table.getHeaderGroups().map(headerGroup => (
              <tr key={headerGroup.id}>
                {headerGroup.headers.map(header => (
                  <th key={header.id}>
                    {flexRender(
                      header.column.columnDef.header,
                      header.getContext()
                    )}
                  </th>
                ))}
              </tr>
            ))}
          </thead>
          <tbody>
            {table.getRowModel().rows.map(row => (
              <tr
                key={row.id}
                className={row.getIsSelected() ? 'selected' : ''}
                onClick={() => navigateToUser(row.original.id)}
              >
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

      <div className="table-footer">
        <span className="row-count">
          {selectedUsers.length > 0
            ? `${selectedUsers.length} selected`
            : `${data?.total || 0} users`}
        </span>
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
    </div>
  );
};
```

### Create User Form

```typescript
// src/features/users/components/CreateUserForm.tsx
import React from 'react';
import { useForm, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import * as Dialog from '@radix-ui/react-dialog';
import { useCreateUser } from '../hooks/useCreateUser';
import { useRoles } from '../hooks/useRoles';
import { MultiSelect } from '../../../shared/components/MultiSelect';

const createUserSchema = z.object({
  email: z.string().email('Invalid email address'),
  firstName: z.string().min(1, 'First name is required'),
  lastName: z.string().min(1, 'Last name is required'),
  roleIds: z.array(z.string()).min(1, 'At least one role is required'),
  sendInvitation: z.boolean(),
  requireMfa: z.boolean(),
  temporaryPassword: z.string().optional(),
}).refine((data) => {
  if (!data.sendInvitation && !data.temporaryPassword) {
    return false;
  }
  return true;
}, {
  message: 'Either send invitation or set temporary password',
  path: ['temporaryPassword'],
});

type CreateUserData = z.infer<typeof createUserSchema>;

interface CreateUserFormProps {
  tenantId: string;
  isOpen: boolean;
  onClose: () => void;
}

export const CreateUserForm: React.FC<CreateUserFormProps> = ({
  tenantId,
  isOpen,
  onClose,
}) => {
  const { mutate: createUser, isPending } = useCreateUser(tenantId);
  const { data: roles } = useRoles(tenantId);

  const {
    register,
    control,
    handleSubmit,
    watch,
    formState: { errors },
    reset,
  } = useForm<CreateUserData>({
    resolver: zodResolver(createUserSchema),
    defaultValues: {
      sendInvitation: true,
      requireMfa: true,
      roleIds: [],
    },
  });

  const sendInvitation = watch('sendInvitation');

  const onSubmit = (data: CreateUserData) => {
    createUser(data, {
      onSuccess: () => {
        reset();
        onClose();
      },
    });
  };

  const roleOptions = roles?.map(role => ({
    value: role.id,
    label: role.name,
    description: role.description,
  })) || [];

  return (
    <Dialog.Root open={isOpen} onOpenChange={(open) => !open && onClose()}>
      <Dialog.Portal>
        <Dialog.Overlay className="dialog-overlay" />
        <Dialog.Content className="dialog-content">
          <Dialog.Title className="dialog-title">
            Create New User
          </Dialog.Title>

          <form onSubmit={handleSubmit(onSubmit)} className="user-form">
            <div className="form-row">
              <div className="form-field">
                <label htmlFor="firstName">First Name *</label>
                <input
                  id="firstName"
                  {...register('firstName')}
                  placeholder="John"
                />
                {errors.firstName && (
                  <span className="error">{errors.firstName.message}</span>
                )}
              </div>

              <div className="form-field">
                <label htmlFor="lastName">Last Name *</label>
                <input
                  id="lastName"
                  {...register('lastName')}
                  placeholder="Doe"
                />
                {errors.lastName && (
                  <span className="error">{errors.lastName.message}</span>
                )}
              </div>
            </div>

            <div className="form-field">
              <label htmlFor="email">Email Address *</label>
              <input
                id="email"
                type="email"
                {...register('email')}
                placeholder="john.doe@company.com"
              />
              {errors.email && (
                <span className="error">{errors.email.message}</span>
              )}
            </div>

            <div className="form-field">
              <label>Roles *</label>
              <Controller
                name="roleIds"
                control={control}
                render={({ field }) => (
                  <MultiSelect
                    options={roleOptions}
                    value={field.value}
                    onChange={field.onChange}
                    placeholder="Select roles..."
                  />
                )}
              />
              {errors.roleIds && (
                <span className="error">{errors.roleIds.message}</span>
              )}
            </div>

            <div className="form-field checkbox">
              <label>
                <input type="checkbox" {...register('requireMfa')} />
                Require Multi-Factor Authentication
              </label>
            </div>

            <div className="form-field checkbox">
              <label>
                <input type="checkbox" {...register('sendInvitation')} />
                Send invitation email
              </label>
            </div>

            {!sendInvitation && (
              <div className="form-field">
                <label htmlFor="temporaryPassword">Temporary Password *</label>
                <input
                  id="temporaryPassword"
                  type="password"
                  {...register('temporaryPassword')}
                  placeholder="Temporary password"
                />
                {errors.temporaryPassword && (
                  <span className="error">{errors.temporaryPassword.message}</span>
                )}
                <span className="hint">
                  User will be required to change this on first login
                </span>
              </div>
            )}

            <div className="dialog-footer">
              <button type="button" className="btn-secondary" onClick={onClose}>
                Cancel
              </button>
              <button type="submit" className="btn-primary" disabled={isPending}>
                {isPending ? 'Creating...' : 'Create User'}
              </button>
            </div>
          </form>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
};
```

### User Detail View

```typescript
// src/features/users/components/UserDetail.tsx
import React from 'react';
import { useParams } from 'react-router-dom';
import * as Tabs from '@radix-ui/react-tabs';
import { useUser } from '../hooks/useUser';
import { UserProfile } from './UserProfile';
import { UserRoles } from './UserRoles';
import { UserSessions } from './UserSessions';
import { UserApiKeys } from './UserApiKeys';
import { UserAuditLog } from './UserAuditLog';

export const UserDetail: React.FC = () => {
  const { userId } = useParams<{ userId: string }>();
  const { data: user, isLoading } = useUser(userId!);

  if (isLoading) return <div className="loading">Loading user...</div>;
  if (!user) return <div className="not-found">User not found</div>;

  return (
    <div className="user-detail">
      <div className="detail-header">
        <div className="user-header">
          <Avatar name={user.name} src={user.avatarUrl} size="large" />
          <div className="user-header-info">
            <h1>{user.name}</h1>
            <span className="user-email">{user.email}</span>
            <StatusBadge status={user.status} />
          </div>
        </div>
        <UserHeaderActions user={user} />
      </div>

      <Tabs.Root defaultValue="profile" className="user-tabs">
        <Tabs.List className="tabs-list">
          <Tabs.Trigger value="profile" className="tab-trigger">
            Profile
          </Tabs.Trigger>
          <Tabs.Trigger value="roles" className="tab-trigger">
            Roles & Permissions
          </Tabs.Trigger>
          <Tabs.Trigger value="sessions" className="tab-trigger">
            Sessions
          </Tabs.Trigger>
          <Tabs.Trigger value="apikeys" className="tab-trigger">
            API Keys
          </Tabs.Trigger>
          <Tabs.Trigger value="audit" className="tab-trigger">
            Activity Log
          </Tabs.Trigger>
        </Tabs.List>

        <Tabs.Content value="profile" className="tab-content">
          <UserProfile user={user} />
        </Tabs.Content>

        <Tabs.Content value="roles" className="tab-content">
          <UserRoles userId={user.id} />
        </Tabs.Content>

        <Tabs.Content value="sessions" className="tab-content">
          <UserSessions userId={user.id} />
        </Tabs.Content>

        <Tabs.Content value="apikeys" className="tab-content">
          <UserApiKeys userId={user.id} />
        </Tabs.Content>

        <Tabs.Content value="audit" className="tab-content">
          <UserAuditLog userId={user.id} />
        </Tabs.Content>
      </Tabs.Root>
    </div>
  );
};
```

### User Sessions Component

```typescript
// src/features/users/components/UserSessions.tsx
import React from 'react';
import { useUserSessions, useRevokeSession, useRevokeAllSessions } from '../hooks/useUserSessions';
import { UserSession } from '../types/user.types';
import { formatRelativeTime, formatDate } from '../../../shared/utils/formatters';

interface UserSessionsProps {
  userId: string;
}

export const UserSessions: React.FC<UserSessionsProps> = ({ userId }) => {
  const { data: sessions, isLoading } = useUserSessions(userId);
  const { mutate: revokeSession } = useRevokeSession();
  const { mutate: revokeAllSessions } = useRevokeAllSessions();

  const handleRevokeSession = (sessionId: string) => {
    if (confirm('Are you sure you want to revoke this session?')) {
      revokeSession({ userId, sessionId });
    }
  };

  const handleRevokeAll = () => {
    if (confirm('Are you sure you want to revoke all sessions? The user will be logged out everywhere.')) {
      revokeAllSessions({ userId });
    }
  };

  if (isLoading) return <div className="loading">Loading sessions...</div>;

  return (
    <div className="user-sessions">
      <div className="section-header">
        <h3>Active Sessions</h3>
        {sessions && sessions.length > 0 && (
          <button
            className="btn-danger-outline"
            onClick={handleRevokeAll}
          >
            Revoke All Sessions
          </button>
        )}
      </div>

      {sessions?.length === 0 ? (
        <div className="empty-state">No active sessions</div>
      ) : (
        <div className="sessions-list">
          {sessions?.map((session: UserSession) => (
            <div key={session.id} className="session-card">
              <div className="session-device">
                <span className="device-icon">
                  {getDeviceIcon(session.deviceType)}
                </span>
                <div className="device-info">
                  <span className="device-name">
                    {session.browser} on {session.os}
                  </span>
                  <span className="device-details">
                    {session.ipAddress} • {session.location}
                  </span>
                </div>
              </div>

              <div className="session-times">
                <div className="session-time">
                  <span className="label">Created</span>
                  <span className="value">{formatDate(session.createdAt)}</span>
                </div>
                <div className="session-time">
                  <span className="label">Last Activity</span>
                  <span className="value">{formatRelativeTime(session.lastActivityAt)}</span>
                </div>
              </div>

              <div className="session-actions">
                {session.isCurrent ? (
                  <span className="current-session-badge">Current Session</span>
                ) : (
                  <button
                    className="btn-danger-outline btn-sm"
                    onClick={() => handleRevokeSession(session.id)}
                  >
                    Revoke
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

function getDeviceIcon(deviceType: string): string {
  switch (deviceType) {
    case 'desktop': return '🖥️';
    case 'mobile': return '📱';
    case 'tablet': return '📱';
    default: return '💻';
  }
}
```

### Bulk Actions Component

```typescript
// src/features/users/components/UserBulkActions.tsx
import React, { useState } from 'react';
import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import { useBulkUpdateUsers, useBulkDeleteUsers } from '../hooks/useBulkUserActions';
import { useRoles } from '../hooks/useRoles';
import { User } from '../types/user.types';

interface UserBulkActionsProps {
  selectedUsers: User[];
  onComplete: () => void;
}

export const UserBulkActions: React.FC<UserBulkActionsProps> = ({
  selectedUsers,
  onComplete,
}) => {
  const [showRoleModal, setShowRoleModal] = useState(false);
  const { mutate: bulkUpdate } = useBulkUpdateUsers();
  const { mutate: bulkDelete } = useBulkDeleteUsers();
  const { data: roles } = useRoles();

  const userIds = selectedUsers.map(u => u.id);

  const handleEnableUsers = () => {
    bulkUpdate(
      { userIds, update: { status: 'ACTIVE' } },
      { onSuccess: onComplete }
    );
  };

  const handleDisableUsers = () => {
    if (confirm(`Disable ${selectedUsers.length} users?`)) {
      bulkUpdate(
        { userIds, update: { status: 'DISABLED' } },
        { onSuccess: onComplete }
      );
    }
  };

  const handleDeleteUsers = () => {
    if (confirm(`Delete ${selectedUsers.length} users? This cannot be undone.`)) {
      bulkDelete({ userIds }, { onSuccess: onComplete });
    }
  };

  const handleAssignRole = (roleId: string) => {
    bulkUpdate(
      { userIds, update: { addRoleId: roleId } },
      { onSuccess: onComplete }
    );
  };

  const handleRemoveRole = (roleId: string) => {
    bulkUpdate(
      { userIds, update: { removeRoleId: roleId } },
      { onSuccess: onComplete }
    );
  };

  const handleSendPasswordReset = () => {
    // Send password reset emails to all selected users
  };

  return (
    <div className="bulk-actions-bar">
      <span className="selected-count">
        {selectedUsers.length} user{selectedUsers.length > 1 ? 's' : ''} selected
      </span>

      <div className="bulk-action-buttons">
        <button className="btn-secondary btn-sm" onClick={handleEnableUsers}>
          Enable
        </button>
        <button className="btn-secondary btn-sm" onClick={handleDisableUsers}>
          Disable
        </button>

        <DropdownMenu.Root>
          <DropdownMenu.Trigger asChild>
            <button className="btn-secondary btn-sm">
              Assign Role ▼
            </button>
          </DropdownMenu.Trigger>
          <DropdownMenu.Portal>
            <DropdownMenu.Content className="dropdown-content">
              {roles?.map(role => (
                <DropdownMenu.Item
                  key={role.id}
                  className="dropdown-item"
                  onSelect={() => handleAssignRole(role.id)}
                >
                  {role.name}
                </DropdownMenu.Item>
              ))}
            </DropdownMenu.Content>
          </DropdownMenu.Portal>
        </DropdownMenu.Root>

        <button className="btn-secondary btn-sm" onClick={handleSendPasswordReset}>
          Send Password Reset
        </button>

        <button className="btn-danger btn-sm" onClick={handleDeleteUsers}>
          Delete
        </button>
      </div>
    </div>
  );
};
```

### CSS Styles

```css
/* src/features/users/styles/user-management.css */

.user-list {
  padding: 24px;
}

/* User Cell */
.user-cell {
  display: flex;
  align-items: center;
  gap: 12px;
}

.user-info {
  display: flex;
  flex-direction: column;
}

.user-name {
  font-weight: 500;
  color: var(--text-primary);
}

.user-email {
  font-size: 12px;
  color: var(--text-tertiary);
}

/* Role Badges */
.role-badges {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.role-badge {
  padding: 2px 8px;
  background: var(--bg-tertiary);
  border-radius: 4px;
  font-size: 11px;
  color: var(--text-secondary);
}

.role-badge.more {
  background: var(--accent-color);
  color: white;
}

/* MFA Status */
.mfa-status {
  font-size: 12px;
  font-weight: 500;
}

.mfa-status.enabled {
  color: var(--success-color);
}

.mfa-status.disabled {
  color: var(--error-color);
}

/* Bulk Actions Bar */
.bulk-actions-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: var(--accent-color);
  border-radius: 8px;
  margin-bottom: 16px;
}

.selected-count {
  font-weight: 500;
  color: white;
}

.bulk-action-buttons {
  display: flex;
  gap: 8px;
}

/* Sessions */
.sessions-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.session-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px;
  background: var(--bg-secondary);
  border-radius: 8px;
  border: 1px solid var(--border-color);
}

.session-device {
  display: flex;
  align-items: center;
  gap: 12px;
}

.device-icon {
  font-size: 24px;
}

.device-info {
  display: flex;
  flex-direction: column;
}

.device-name {
  font-weight: 500;
}

.device-details {
  font-size: 12px;
  color: var(--text-tertiary);
}

.session-times {
  display: flex;
  gap: 24px;
}

.session-time {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.session-time .label {
  font-size: 10px;
  text-transform: uppercase;
  color: var(--text-tertiary);
}

.session-time .value {
  font-size: 12px;
}

.current-session-badge {
  padding: 4px 8px;
  background: var(--success-color);
  border-radius: 4px;
  font-size: 11px;
  color: white;
}
```

## Definition of Done

- [ ] User list with search, filter, sort
- [ ] Create user with role assignment
- [ ] Edit user profile and roles
- [ ] Bulk operations (enable/disable/assign)
- [ ] Session management and revocation
- [ ] Password reset functionality
- [ ] MFA status display and management
- [ ] Audit logging for all actions
- [ ] Unit tests
- [ ] E2E tests

## Test Cases

```typescript
describe('UserList', () => {
  it('should display users', async () => {
    render(<UserList tenantId="tenant-1" />);
    
    await waitFor(() => {
      expect(screen.getByText('john.doe@test.com')).toBeInTheDocument();
    });
  });

  it('should allow bulk selection', async () => {
    render(<UserList tenantId="tenant-1" />);
    
    const checkboxes = screen.getAllByRole('checkbox');
    fireEvent.click(checkboxes[1]); // First user
    fireEvent.click(checkboxes[2]); // Second user
    
    expect(screen.getByText('2 users selected')).toBeInTheDocument();
  });
});

describe('UserSessions', () => {
  it('should show current session badge', async () => {
    render(<UserSessions userId="user-1" />);
    
    await waitFor(() => {
      expect(screen.getByText('Current Session')).toBeInTheDocument();
    });
  });

  it('should revoke session', async () => {
    render(<UserSessions userId="user-1" />);
    
    const revokeButton = await screen.findByText('Revoke');
    fireEvent.click(revokeButton);
    
    // Confirm dialog handled
    expect(revokeSession).toHaveBeenCalled();
  });
});
```
