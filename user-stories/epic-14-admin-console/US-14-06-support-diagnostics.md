# US-14-06: Support & Diagnostics

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-14-06 |
| **Epic** | Epic 14: Admin Console |
| **Title** | Support & Diagnostics |
| **Priority** | Medium |
| **Story Points** | 5 |
| **Status** | Ready for Development |

## User Story

**As a** support administrator  
**I want** tools to diagnose and resolve user issues  
**So that** I can provide efficient customer support

## Acceptance Criteria

### AC1: User Lookup
- **Given** I have support permission
- **When** I search for a user
- **Then** I can find by:
  - Email
  - User ID
  - Phone number
  - Account reference

### AC2: User Context View
- **Given** a user lookup result
- **When** I view user context
- **Then** I see:
  - Account status
  - Recent activity
  - Active sessions
  - Open issues/tickets

### AC3: Impersonation
- **Given** impersonation permission
- **When** I impersonate a user
- **Then**:
  - Session is marked as impersonated
  - All actions are logged
  - User sees notification
  - Time-limited access

### AC4: Data Export
- **Given** GDPR/data request
- **When** I request user data export
- **Then**:
  - All user data is compiled
  - Export is downloadable
  - Request is logged
  - Compliance report generated

### AC5: System Diagnostics
- **Given** system health view
- **When** I check diagnostics
- **Then** I see:
  - Service health status
  - Recent errors
  - Performance metrics
  - Queue depths

## Technical Specification

### Support Dashboard Component

```typescript
// src/features/support/components/SupportDashboard.tsx
import React from 'react';
import * as Tabs from '@radix-ui/react-tabs';
import { UserLookup } from './UserLookup';
import { SystemDiagnostics } from './SystemDiagnostics';
import { DataExportRequests } from './DataExportRequests';
import { SupportTickets } from './SupportTickets';

export const SupportDashboard: React.FC = () => {
  return (
    <div className="support-dashboard">
      <div className="dashboard-header">
        <h1>Support & Diagnostics</h1>
        <p className="description">
          User support tools and system diagnostics
        </p>
      </div>

      <Tabs.Root defaultValue="lookup" className="support-tabs">
        <Tabs.List className="tabs-list">
          <Tabs.Trigger value="lookup" className="tab-trigger">
            User Lookup
          </Tabs.Trigger>
          <Tabs.Trigger value="diagnostics" className="tab-trigger">
            System Health
          </Tabs.Trigger>
          <Tabs.Trigger value="exports" className="tab-trigger">
            Data Requests
          </Tabs.Trigger>
          <Tabs.Trigger value="tickets" className="tab-trigger">
            Support Tickets
          </Tabs.Trigger>
        </Tabs.List>

        <Tabs.Content value="lookup" className="tab-content">
          <UserLookup />
        </Tabs.Content>

        <Tabs.Content value="diagnostics" className="tab-content">
          <SystemDiagnostics />
        </Tabs.Content>

        <Tabs.Content value="exports" className="tab-content">
          <DataExportRequests />
        </Tabs.Content>

        <Tabs.Content value="tickets" className="tab-content">
          <SupportTickets />
        </Tabs.Content>
      </Tabs.Root>
    </div>
  );
};
```

### User Lookup Component

```typescript
// src/features/support/components/UserLookup.tsx
import React, { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useUserLookup } from '../hooks/useUserLookup';
import { UserContextView } from './UserContextView';
import { LookupResult, LookupQuery } from '../types/support.types';

export const UserLookup: React.FC = () => {
  const [results, setResults] = useState<LookupResult[]>([]);
  const [selectedUser, setSelectedUser] = useState<LookupResult | null>(null);
  const { mutate: lookup, isPending } = useUserLookup();

  const { register, handleSubmit } = useForm<LookupQuery>({
    defaultValues: {
      searchType: 'email',
    },
  });

  const onSearch = (data: LookupQuery) => {
    lookup(data, {
      onSuccess: (results) => setResults(results),
    });
  };

  return (
    <div className="user-lookup">
      <div className="lookup-section">
        <h2>Find User</h2>

        <form onSubmit={handleSubmit(onSearch)} className="lookup-form">
          <div className="search-type-selector">
            <label className="radio-label">
              <input
                type="radio"
                value="email"
                {...register('searchType')}
              />
              Email
            </label>
            <label className="radio-label">
              <input
                type="radio"
                value="userId"
                {...register('searchType')}
              />
              User ID
            </label>
            <label className="radio-label">
              <input
                type="radio"
                value="phone"
                {...register('searchType')}
              />
              Phone
            </label>
            <label className="radio-label">
              <input
                type="radio"
                value="accountRef"
                {...register('searchType')}
              />
              Account Ref
            </label>
          </div>

          <div className="search-input-row">
            <input
              type="text"
              placeholder="Enter search value..."
              {...register('query')}
              className="search-input"
            />
            <button type="submit" className="btn-primary" disabled={isPending}>
              {isPending ? 'Searching...' : 'Search'}
            </button>
          </div>
        </form>
      </div>

      {results.length > 0 && (
        <div className="lookup-results">
          <h3>Results ({results.length})</h3>
          <div className="results-list">
            {results.map((result) => (
              <div
                key={result.userId}
                className={`result-card ${selectedUser?.userId === result.userId ? 'selected' : ''}`}
                onClick={() => setSelectedUser(result)}
              >
                <div className="result-avatar">
                  {result.name.charAt(0).toUpperCase()}
                </div>
                <div className="result-info">
                  <span className="result-name">{result.name}</span>
                  <span className="result-email">{result.email}</span>
                  <span className="result-tenant">{result.tenantName}</span>
                </div>
                <div className="result-status">
                  <span className={`status-badge ${result.status.toLowerCase()}`}>
                    {result.status}
                  </span>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {selectedUser && (
        <UserContextView
          userId={selectedUser.userId}
          onClose={() => setSelectedUser(null)}
        />
      )}
    </div>
  );
};
```

### User Context View

```typescript
// src/features/support/components/UserContextView.tsx
import React from 'react';
import * as Tabs from '@radix-ui/react-tabs';
import { useUserContext } from '../hooks/useUserContext';
import { useImpersonate } from '../hooks/useImpersonate';
import { useRequestDataExport } from '../hooks/useRequestDataExport';
import { UserActivity } from './UserActivity';
import { UserSessions } from './UserSessions';
import { UserOrders } from './UserOrders';

interface UserContextViewProps {
  userId: string;
  onClose: () => void;
}

export const UserContextView: React.FC<UserContextViewProps> = ({
  userId,
  onClose,
}) => {
  const { data: context, isLoading } = useUserContext(userId);
  const { mutate: impersonate, isPending: isImpersonating } = useImpersonate();
  const { mutate: requestExport } = useRequestDataExport();

  const handleImpersonate = () => {
    if (confirm('Start impersonation session? All actions will be logged.')) {
      impersonate({ userId, duration: 30 }); // 30 minutes
    }
  };

  const handleDataExport = () => {
    if (confirm('Request data export for this user? This may take several minutes.')) {
      requestExport({ userId, format: 'json' });
    }
  };

  if (isLoading) return <div className="loading">Loading user context...</div>;
  if (!context) return null;

  return (
    <div className="user-context-view">
      <div className="context-header">
        <div className="user-summary">
          <div className="user-avatar large">
            {context.user.name.charAt(0).toUpperCase()}
          </div>
          <div className="user-details">
            <h2>{context.user.name}</h2>
            <span className="user-email">{context.user.email}</span>
            <span className="user-tenant">{context.user.tenantName}</span>
          </div>
          <div className="user-status-info">
            <span className={`status-badge ${context.user.status.toLowerCase()}`}>
              {context.user.status}
            </span>
            <span className="last-login">
              Last login: {formatRelativeTime(context.user.lastLoginAt)}
            </span>
          </div>
        </div>

        <div className="context-actions">
          <button
            className="btn-secondary"
            onClick={handleDataExport}
          >
            Export Data
          </button>
          <button
            className="btn-warning"
            onClick={handleImpersonate}
            disabled={isImpersonating}
          >
            {isImpersonating ? 'Starting...' : 'Impersonate'}
          </button>
          <button className="btn-icon" onClick={onClose}>×</button>
        </div>
      </div>

      <div className="context-summary-cards">
        <div className="summary-card">
          <span className="card-value">{context.activeSessions}</span>
          <span className="card-label">Active Sessions</span>
        </div>
        <div className="summary-card">
          <span className="card-value">{context.openOrders}</span>
          <span className="card-label">Open Orders</span>
        </div>
        <div className="summary-card">
          <span className="card-value">{context.openPositions}</span>
          <span className="card-label">Open Positions</span>
        </div>
        <div className="summary-card">
          <span className="card-value">{context.openTickets}</span>
          <span className="card-label">Support Tickets</span>
        </div>
      </div>

      <Tabs.Root defaultValue="activity" className="context-tabs">
        <Tabs.List className="tabs-list">
          <Tabs.Trigger value="activity" className="tab-trigger">
            Recent Activity
          </Tabs.Trigger>
          <Tabs.Trigger value="sessions" className="tab-trigger">
            Sessions
          </Tabs.Trigger>
          <Tabs.Trigger value="orders" className="tab-trigger">
            Orders
          </Tabs.Trigger>
          <Tabs.Trigger value="tickets" className="tab-trigger">
            Tickets
          </Tabs.Trigger>
        </Tabs.List>

        <Tabs.Content value="activity" className="tab-content">
          <UserActivity userId={userId} />
        </Tabs.Content>

        <Tabs.Content value="sessions" className="tab-content">
          <UserSessions userId={userId} />
        </Tabs.Content>

        <Tabs.Content value="orders" className="tab-content">
          <UserOrders userId={userId} />
        </Tabs.Content>

        <Tabs.Content value="tickets" className="tab-content">
          <UserTickets userId={userId} />
        </Tabs.Content>
      </Tabs.Root>
    </div>
  );
};
```

### System Diagnostics Component

```typescript
// src/features/support/components/SystemDiagnostics.tsx
import React from 'react';
import { useSystemHealth, useServiceStatus } from '../hooks/useSystemHealth';
import { ServiceHealthCard } from './ServiceHealthCard';
import { QueueDepthChart } from './QueueDepthChart';
import { RecentErrorsTable } from './RecentErrorsTable';
import { PerformanceMetrics } from './PerformanceMetrics';

export const SystemDiagnostics: React.FC = () => {
  const { data: health, isLoading } = useSystemHealth();
  const { data: services } = useServiceStatus();

  if (isLoading) return <div className="loading">Loading diagnostics...</div>;

  return (
    <div className="system-diagnostics">
      <div className="diagnostics-header">
        <h2>System Health</h2>
        <div className="overall-status">
          <span className={`status-indicator ${health?.status || 'unknown'}`} />
          <span className="status-text">
            {health?.status === 'healthy' ? 'All Systems Operational' : 
             health?.status === 'degraded' ? 'Performance Degraded' :
             health?.status === 'unhealthy' ? 'Service Issues Detected' :
             'Status Unknown'}
          </span>
        </div>
      </div>

      <section className="services-grid">
        <h3>Service Status</h3>
        <div className="grid">
          {services?.map((service) => (
            <ServiceHealthCard key={service.name} service={service} />
          ))}
        </div>
      </section>

      <section className="metrics-section">
        <h3>Performance Metrics</h3>
        <PerformanceMetrics />
      </section>

      <section className="queues-section">
        <h3>Queue Depths</h3>
        <QueueDepthChart data={health?.queues || []} />
      </section>

      <section className="errors-section">
        <h3>Recent Errors</h3>
        <RecentErrorsTable errors={health?.recentErrors || []} />
      </section>
    </div>
  );
};
```

### Service Health Card

```typescript
// src/features/support/components/ServiceHealthCard.tsx
import React from 'react';
import { ServiceStatus, HealthStatus } from '../types/support.types';

interface ServiceHealthCardProps {
  service: ServiceStatus;
}

const STATUS_COLORS: Record<HealthStatus, string> = {
  healthy: 'var(--success-color)',
  degraded: 'var(--warning-color)',
  unhealthy: 'var(--error-color)',
  unknown: 'var(--text-tertiary)',
};

export const ServiceHealthCard: React.FC<ServiceHealthCardProps> = ({ service }) => {
  return (
    <div className={`service-health-card ${service.status}`}>
      <div className="card-header">
        <span
          className="status-dot"
          style={{ backgroundColor: STATUS_COLORS[service.status] }}
        />
        <span className="service-name">{service.name}</span>
      </div>

      <div className="card-metrics">
        <div className="metric">
          <span className="metric-value">{service.latency}ms</span>
          <span className="metric-label">Latency</span>
        </div>
        <div className="metric">
          <span className="metric-value">{service.uptime}%</span>
          <span className="metric-label">Uptime</span>
        </div>
        <div className="metric">
          <span className="metric-value">{service.requestsPerSecond}/s</span>
          <span className="metric-label">RPS</span>
        </div>
      </div>

      {service.lastError && (
        <div className="last-error">
          <span className="error-time">
            {formatRelativeTime(service.lastError.timestamp)}
          </span>
          <span className="error-message">{service.lastError.message}</span>
        </div>
      )}
    </div>
  );
};
```

### Data Export Requests

```typescript
// src/features/support/components/DataExportRequests.tsx
import React from 'react';
import { useDataExportRequests, useProcessExportRequest } from '../hooks/useDataExport';
import { DataExportRequest, ExportStatus } from '../types/support.types';
import { formatDateTime, formatRelativeTime } from '../../../shared/utils/formatters';

export const DataExportRequests: React.FC = () => {
  const { data: requests, isLoading } = useDataExportRequests();
  const { mutate: processRequest } = useProcessExportRequest();

  const handleDownload = (requestId: string) => {
    // Download completed export
  };

  const handleApprove = (requestId: string) => {
    processRequest({ requestId, action: 'approve' });
  };

  const handleReject = (requestId: string) => {
    processRequest({ requestId, action: 'reject' });
  };

  if (isLoading) return <div className="loading">Loading requests...</div>;

  return (
    <div className="data-export-requests">
      <div className="section-header">
        <h2>Data Export Requests</h2>
        <p>GDPR and compliance data export requests</p>
      </div>

      <div className="requests-list">
        {requests?.map((request: DataExportRequest) => (
          <div key={request.id} className="request-card">
            <div className="request-info">
              <div className="request-header">
                <span className="request-id">#{request.id}</span>
                <span className={`status-badge ${request.status.toLowerCase()}`}>
                  {request.status}
                </span>
              </div>
              <div className="request-user">
                <span className="user-name">{request.userName}</span>
                <span className="user-email">{request.userEmail}</span>
              </div>
              <div className="request-meta">
                <span>Requested: {formatDateTime(request.createdAt)}</span>
                <span>Type: {request.exportType}</span>
              </div>
            </div>

            <div className="request-actions">
              {request.status === ExportStatus.PENDING && (
                <>
                  <button
                    className="btn-primary btn-sm"
                    onClick={() => handleApprove(request.id)}
                  >
                    Approve
                  </button>
                  <button
                    className="btn-secondary btn-sm"
                    onClick={() => handleReject(request.id)}
                  >
                    Reject
                  </button>
                </>
              )}
              {request.status === ExportStatus.COMPLETED && (
                <button
                  className="btn-primary btn-sm"
                  onClick={() => handleDownload(request.id)}
                >
                  Download
                </button>
              )}
              {request.status === ExportStatus.PROCESSING && (
                <span className="processing-indicator">
                  Processing... {request.progress}%
                </span>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};
```

### CSS Styles

```css
/* src/features/support/styles/support.css */

.support-dashboard {
  padding: 24px;
}

/* User Lookup */
.user-lookup {
  max-width: 1200px;
}

.lookup-form {
  background: var(--bg-secondary);
  padding: 20px;
  border-radius: 8px;
  margin-bottom: 24px;
}

.search-type-selector {
  display: flex;
  gap: 16px;
  margin-bottom: 16px;
}

.radio-label {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}

.search-input-row {
  display: flex;
  gap: 12px;
}

.search-input {
  flex: 1;
  padding: 12px;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  font-size: 14px;
}

/* Results */
.results-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.result-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s;
}

.result-card:hover,
.result-card.selected {
  border-color: var(--accent-color);
  background: var(--bg-hover);
}

.result-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: var(--accent-color);
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
}

.result-info {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.result-name {
  font-weight: 500;
}

.result-email {
  font-size: 13px;
  color: var(--text-secondary);
}

.result-tenant {
  font-size: 12px;
  color: var(--text-tertiary);
}

/* User Context View */
.user-context-view {
  margin-top: 24px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  overflow: hidden;
}

.context-header {
  display: flex;
  justify-content: space-between;
  padding: 20px;
  border-bottom: 1px solid var(--border-color);
}

.user-summary {
  display: flex;
  align-items: center;
  gap: 16px;
}

.user-avatar.large {
  width: 60px;
  height: 60px;
  font-size: 24px;
}

.context-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}

.context-summary-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  padding: 20px;
  background: var(--bg-tertiary);
}

.summary-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 16px;
  background: var(--bg-primary);
  border-radius: 8px;
}

.card-value {
  font-size: 24px;
  font-weight: 600;
}

.card-label {
  font-size: 12px;
  color: var(--text-tertiary);
}

/* System Diagnostics */
.system-diagnostics {
  max-width: 1400px;
}

.diagnostics-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.overall-status {
  display: flex;
  align-items: center;
  gap: 8px;
}

.status-indicator {
  width: 12px;
  height: 12px;
  border-radius: 50%;
}

.status-indicator.healthy {
  background: var(--success-color);
}

.status-indicator.degraded {
  background: var(--warning-color);
}

.status-indicator.unhealthy {
  background: var(--error-color);
}

/* Service Health Cards */
.services-grid .grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.service-health-card {
  padding: 16px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
}

.service-health-card.degraded {
  border-left: 3px solid var(--warning-color);
}

.service-health-card.unhealthy {
  border-left: 3px solid var(--error-color);
}

.card-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.service-name {
  font-weight: 500;
}

.card-metrics {
  display: flex;
  justify-content: space-between;
}

.metric {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.metric-value {
  font-size: 18px;
  font-weight: 600;
}

.metric-label {
  font-size: 11px;
  color: var(--text-tertiary);
}

.last-error {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--border-color);
  font-size: 12px;
}

.error-time {
  color: var(--error-color);
}

/* Data Export Requests */
.requests-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.request-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
}

.request-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.request-id {
  font-family: var(--font-mono);
  font-size: 12px;
}

.request-user {
  display: flex;
  flex-direction: column;
}

.request-meta {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: var(--text-tertiary);
  margin-top: 8px;
}

.request-actions {
  display: flex;
  gap: 8px;
}

.processing-indicator {
  font-size: 12px;
  color: var(--accent-color);
}

.btn-warning {
  background: var(--warning-color);
  color: white;
  border: none;
  padding: 8px 16px;
  border-radius: 6px;
  cursor: pointer;
}
```

## Definition of Done

- [ ] User lookup by multiple criteria
- [ ] User context view with full history
- [ ] Impersonation with audit logging
- [ ] GDPR data export functionality
- [ ] System health dashboard
- [ ] Service status monitoring
- [ ] Queue depth visualization
- [ ] Recent errors table
- [ ] Unit tests
- [ ] E2E tests

## Test Cases

```typescript
describe('UserLookup', () => {
  it('should search by email', async () => {
    render(<UserLookup />);
    
    fireEvent.change(screen.getByPlaceholderText('Enter search value...'), {
      target: { value: 'john@example.com' }
    });
    fireEvent.click(screen.getByText('Search'));
    
    await waitFor(() => {
      expect(screen.getByText('John Doe')).toBeInTheDocument();
    });
  });
});

describe('SystemDiagnostics', () => {
  it('should display service health', async () => {
    render(<SystemDiagnostics />);
    
    await waitFor(() => {
      expect(screen.getByText('Order Service')).toBeInTheDocument();
      expect(screen.getByText('healthy')).toBeInTheDocument();
    });
  });
});

describe('DataExportRequests', () => {
  it('should approve export request', async () => {
    render(<DataExportRequests />);
    
    const approveButton = await screen.findByText('Approve');
    fireEvent.click(approveButton);
    
    expect(processRequest).toHaveBeenCalledWith({
      requestId: 'export-1',
      action: 'approve'
    });
  });
});
```
