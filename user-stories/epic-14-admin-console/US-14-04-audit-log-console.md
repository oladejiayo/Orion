# US-14-04: Audit Log Console

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-14-04 |
| **Epic** | Epic 14: Admin Console |
| **Title** | Audit Log Console |
| **Priority** | High |
| **Story Points** | 8 |
| **Status** | Ready for Development |

## User Story

**As an** administrator or compliance officer  
**I want** to search and view audit logs  
**So that** I can investigate activities and meet compliance requirements

## Acceptance Criteria

### AC1: Audit Log List
- **Given** I have audit access permission
- **When** I view the audit log
- **Then** I see entries with:
  - Timestamp
  - Actor (user/system)
  - Action type
  - Resource affected
  - IP address/location

### AC2: Advanced Search
- **Given** the audit log
- **When** I search
- **Then** I can filter by:
  - Date range
  - Action type
  - User/Actor
  - Resource type
  - Tenant

### AC3: Log Detail View
- **Given** an audit entry
- **When** I click to view details
- **Then** I see:
  - Full event payload
  - Before/after comparison
  - Related events
  - Request metadata

### AC4: Export Functionality
- **Given** filtered audit logs
- **When** I export
- **Then** I can download as:
  - CSV
  - JSON
  - PDF report

### AC5: Retention & Compliance
- **Given** audit log settings
- **When** viewing retention
- **Then** I see:
  - Retention period
  - Archive status
  - Compliance tags

## Technical Specification

### Audit Log List Component

```typescript
// src/features/audit/components/AuditLogList.tsx
import React, { useState, useCallback } from 'react';
import { useAuditLogs } from '../hooks/useAuditLogs';
import { AuditLogFilters } from './AuditLogFilters';
import { AuditLogTable } from './AuditLogTable';
import { AuditLogDetail } from './AuditLogDetail';
import { AuditExportDialog } from './AuditExportDialog';
import { AuditEntry, AuditFilters } from '../types/audit.types';
import { DateRangePicker } from '../../../shared/components/DateRangePicker';

export const AuditLogList: React.FC = () => {
  const [filters, setFilters] = useState<AuditFilters>({
    startDate: new Date(Date.now() - 24 * 60 * 60 * 1000), // Last 24 hours
    endDate: new Date(),
  });
  const [selectedEntry, setSelectedEntry] = useState<AuditEntry | null>(null);
  const [showExportDialog, setShowExportDialog] = useState(false);

  const { data, isLoading, fetchNextPage, hasNextPage } = useAuditLogs(filters);

  const entries = data?.pages.flatMap(page => page.entries) || [];

  const handleFilterChange = useCallback((newFilters: Partial<AuditFilters>) => {
    setFilters(prev => ({ ...prev, ...newFilters }));
  }, []);

  return (
    <div className="audit-log-list">
      <div className="list-header">
        <h1>Audit Log</h1>
        <div className="header-actions">
          <button
            className="btn-secondary"
            onClick={() => setShowExportDialog(true)}
          >
            Export
          </button>
        </div>
      </div>

      <AuditLogFilters
        filters={filters}
        onChange={handleFilterChange}
      />

      <AuditLogTable
        entries={entries}
        isLoading={isLoading}
        onSelectEntry={setSelectedEntry}
        onLoadMore={fetchNextPage}
        hasMore={hasNextPage}
      />

      {selectedEntry && (
        <AuditLogDetail
          entry={selectedEntry}
          onClose={() => setSelectedEntry(null)}
        />
      )}

      {showExportDialog && (
        <AuditExportDialog
          filters={filters}
          onClose={() => setShowExportDialog(false)}
        />
      )}
    </div>
  );
};
```

### Audit Log Table

```typescript
// src/features/audit/components/AuditLogTable.tsx
import React, { useRef, useCallback } from 'react';
import { AuditEntry, ActionType, ActionSeverity } from '../types/audit.types';
import { formatDateTime, formatRelativeTime } from '../../../shared/utils/formatters';
import { VirtualList } from '../../../shared/components/VirtualList';

interface AuditLogTableProps {
  entries: AuditEntry[];
  isLoading: boolean;
  onSelectEntry: (entry: AuditEntry) => void;
  onLoadMore: () => void;
  hasMore: boolean;
}

const ACTION_COLORS: Record<ActionSeverity, string> = {
  INFO: 'var(--info-color)',
  WARNING: 'var(--warning-color)',
  CRITICAL: 'var(--error-color)',
};

const ACTION_ICONS: Record<string, string> = {
  CREATE: '+',
  UPDATE: '✎',
  DELETE: '🗑',
  LOGIN: '→',
  LOGOUT: '←',
  ACCESS: '👁',
  EXPORT: '⬇',
  APPROVE: '✓',
  REJECT: '✗',
};

export const AuditLogTable: React.FC<AuditLogTableProps> = ({
  entries,
  isLoading,
  onSelectEntry,
  onLoadMore,
  hasMore,
}) => {
  const observerRef = useRef<IntersectionObserver | null>(null);

  const lastRowRef = useCallback((node: HTMLElement | null) => {
    if (isLoading) return;
    if (observerRef.current) observerRef.current.disconnect();

    observerRef.current = new IntersectionObserver(entries => {
      if (entries[0].isIntersecting && hasMore) {
        onLoadMore();
      }
    });

    if (node) observerRef.current.observe(node);
  }, [isLoading, hasMore, onLoadMore]);

  return (
    <div className="audit-table-container">
      <table className="audit-table">
        <thead>
          <tr>
            <th className="col-timestamp">Timestamp</th>
            <th className="col-action">Action</th>
            <th className="col-actor">Actor</th>
            <th className="col-resource">Resource</th>
            <th className="col-details">Details</th>
            <th className="col-location">Location</th>
          </tr>
        </thead>
        <tbody>
          {entries.map((entry, index) => (
            <tr
              key={entry.id}
              ref={index === entries.length - 1 ? lastRowRef : null}
              className={`severity-${entry.severity.toLowerCase()}`}
              onClick={() => onSelectEntry(entry)}
            >
              <td className="col-timestamp">
                <div className="timestamp-cell">
                  <span className="timestamp-date">
                    {formatDateTime(entry.timestamp)}
                  </span>
                  <span className="timestamp-relative">
                    {formatRelativeTime(entry.timestamp)}
                  </span>
                </div>
              </td>
              <td className="col-action">
                <span
                  className="action-badge"
                  style={{ backgroundColor: ACTION_COLORS[entry.severity] }}
                >
                  <span className="action-icon">
                    {ACTION_ICONS[entry.actionType] || '•'}
                  </span>
                  {entry.actionType}
                </span>
              </td>
              <td className="col-actor">
                <div className="actor-cell">
                  <span className="actor-name">{entry.actorName}</span>
                  <span className="actor-email">{entry.actorEmail}</span>
                </div>
              </td>
              <td className="col-resource">
                <div className="resource-cell">
                  <span className="resource-type">{entry.resourceType}</span>
                  <span className="resource-id">{entry.resourceId}</span>
                </div>
              </td>
              <td className="col-details">
                <span className="details-preview">
                  {entry.description || '-'}
                </span>
              </td>
              <td className="col-location">
                <div className="location-cell">
                  <span className="ip-address">{entry.ipAddress}</span>
                  <span className="geo-location">{entry.geoLocation}</span>
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {isLoading && (
        <div className="loading-indicator">Loading more entries...</div>
      )}
    </div>
  );
};
```

### Audit Log Detail Panel

```typescript
// src/features/audit/components/AuditLogDetail.tsx
import React from 'react';
import * as Sheet from '@radix-ui/react-dialog';
import { AuditEntry } from '../types/audit.types';
import { useAuditEntryDetails } from '../hooks/useAuditEntryDetails';
import { JsonViewer } from '../../../shared/components/JsonViewer';
import { DiffViewer } from '../../../shared/components/DiffViewer';
import { formatDateTime } from '../../../shared/utils/formatters';

interface AuditLogDetailProps {
  entry: AuditEntry;
  onClose: () => void;
}

export const AuditLogDetail: React.FC<AuditLogDetailProps> = ({
  entry,
  onClose,
}) => {
  const { data: details, isLoading } = useAuditEntryDetails(entry.id);

  return (
    <Sheet.Root open onOpenChange={(open) => !open && onClose()}>
      <Sheet.Portal>
        <Sheet.Overlay className="sheet-overlay" />
        <Sheet.Content className="sheet-content audit-detail-sheet">
          <Sheet.Title className="sheet-title">
            Audit Entry Details
          </Sheet.Title>

          <div className="detail-content">
            {/* Summary Section */}
            <section className="detail-section">
              <h3>Summary</h3>
              <div className="detail-grid">
                <div className="detail-item">
                  <label>Event ID</label>
                  <span className="mono">{entry.id}</span>
                </div>
                <div className="detail-item">
                  <label>Timestamp</label>
                  <span>{formatDateTime(entry.timestamp)}</span>
                </div>
                <div className="detail-item">
                  <label>Action</label>
                  <span className={`action-badge ${entry.severity.toLowerCase()}`}>
                    {entry.actionType}
                  </span>
                </div>
                <div className="detail-item">
                  <label>Severity</label>
                  <span className={`severity-badge ${entry.severity.toLowerCase()}`}>
                    {entry.severity}
                  </span>
                </div>
              </div>
            </section>

            {/* Actor Section */}
            <section className="detail-section">
              <h3>Actor</h3>
              <div className="detail-grid">
                <div className="detail-item">
                  <label>User</label>
                  <span>{entry.actorName}</span>
                </div>
                <div className="detail-item">
                  <label>Email</label>
                  <span>{entry.actorEmail}</span>
                </div>
                <div className="detail-item">
                  <label>User ID</label>
                  <span className="mono">{entry.actorId}</span>
                </div>
                {entry.tenantId && (
                  <div className="detail-item">
                    <label>Tenant</label>
                    <span className="mono">{entry.tenantId}</span>
                  </div>
                )}
              </div>
            </section>

            {/* Resource Section */}
            <section className="detail-section">
              <h3>Resource</h3>
              <div className="detail-grid">
                <div className="detail-item">
                  <label>Type</label>
                  <span>{entry.resourceType}</span>
                </div>
                <div className="detail-item">
                  <label>ID</label>
                  <span className="mono">{entry.resourceId}</span>
                </div>
                {entry.resourceName && (
                  <div className="detail-item">
                    <label>Name</label>
                    <span>{entry.resourceName}</span>
                  </div>
                )}
              </div>
            </section>

            {/* Request Context */}
            <section className="detail-section">
              <h3>Request Context</h3>
              <div className="detail-grid">
                <div className="detail-item">
                  <label>IP Address</label>
                  <span className="mono">{entry.ipAddress}</span>
                </div>
                <div className="detail-item">
                  <label>Location</label>
                  <span>{entry.geoLocation || 'Unknown'}</span>
                </div>
                <div className="detail-item">
                  <label>User Agent</label>
                  <span className="user-agent">{details?.userAgent || '-'}</span>
                </div>
                {details?.requestId && (
                  <div className="detail-item">
                    <label>Request ID</label>
                    <span className="mono">{details.requestId}</span>
                  </div>
                )}
              </div>
            </section>

            {/* Changes Section (if applicable) */}
            {details?.changes && (
              <section className="detail-section">
                <h3>Changes</h3>
                <DiffViewer
                  before={details.changes.before}
                  after={details.changes.after}
                />
              </section>
            )}

            {/* Full Payload */}
            <section className="detail-section">
              <h3>Event Payload</h3>
              <JsonViewer data={details?.payload || entry} />
            </section>

            {/* Related Events */}
            {details?.relatedEvents && details.relatedEvents.length > 0 && (
              <section className="detail-section">
                <h3>Related Events</h3>
                <div className="related-events">
                  {details.relatedEvents.map(event => (
                    <div key={event.id} className="related-event-item">
                      <span className="event-time">
                        {formatDateTime(event.timestamp)}
                      </span>
                      <span className="event-action">{event.actionType}</span>
                      <span className="event-actor">{event.actorName}</span>
                    </div>
                  ))}
                </div>
              </section>
            )}
          </div>

          <Sheet.Close asChild>
            <button className="sheet-close-btn">×</button>
          </Sheet.Close>
        </Sheet.Content>
      </Sheet.Portal>
    </Sheet.Root>
  );
};
```

### Audit Log Filters

```typescript
// src/features/audit/components/AuditLogFilters.tsx
import React from 'react';
import * as Select from '@radix-ui/react-select';
import { DateRangePicker } from '../../../shared/components/DateRangePicker';
import { AuditFilters, ActionType, ResourceType } from '../types/audit.types';

interface AuditLogFiltersProps {
  filters: AuditFilters;
  onChange: (filters: Partial<AuditFilters>) => void;
}

export const AuditLogFilters: React.FC<AuditLogFiltersProps> = ({
  filters,
  onChange,
}) => {
  return (
    <div className="audit-filters">
      <div className="filter-row">
        <div className="filter-item">
          <label>Date Range</label>
          <DateRangePicker
            startDate={filters.startDate}
            endDate={filters.endDate}
            onChange={({ startDate, endDate }) => 
              onChange({ startDate, endDate })
            }
            presets={[
              { label: 'Last Hour', hours: 1 },
              { label: 'Last 24 Hours', hours: 24 },
              { label: 'Last 7 Days', days: 7 },
              { label: 'Last 30 Days', days: 30 },
            ]}
          />
        </div>

        <div className="filter-item">
          <label>Action Type</label>
          <Select.Root
            value={filters.actionType || ''}
            onValueChange={(value) => 
              onChange({ actionType: value as ActionType || undefined })
            }
          >
            <Select.Trigger className="select-trigger">
              <Select.Value placeholder="All actions" />
            </Select.Trigger>
            <Select.Portal>
              <Select.Content className="select-content">
                <Select.Item value="" className="select-item">
                  All actions
                </Select.Item>
                {Object.values(ActionType).map(action => (
                  <Select.Item key={action} value={action} className="select-item">
                    {action}
                  </Select.Item>
                ))}
              </Select.Content>
            </Select.Portal>
          </Select.Root>
        </div>

        <div className="filter-item">
          <label>Resource Type</label>
          <Select.Root
            value={filters.resourceType || ''}
            onValueChange={(value) => 
              onChange({ resourceType: value as ResourceType || undefined })
            }
          >
            <Select.Trigger className="select-trigger">
              <Select.Value placeholder="All resources" />
            </Select.Trigger>
            <Select.Portal>
              <Select.Content className="select-content">
                <Select.Item value="" className="select-item">
                  All resources
                </Select.Item>
                {Object.values(ResourceType).map(type => (
                  <Select.Item key={type} value={type} className="select-item">
                    {type}
                  </Select.Item>
                ))}
              </Select.Content>
            </Select.Portal>
          </Select.Root>
        </div>

        <div className="filter-item">
          <label>Search</label>
          <input
            type="text"
            placeholder="User, resource, or description..."
            value={filters.searchQuery || ''}
            onChange={(e) => onChange({ searchQuery: e.target.value })}
            className="search-input"
          />
        </div>
      </div>

      <div className="filter-row secondary">
        <div className="filter-item">
          <label>User</label>
          <input
            type="text"
            placeholder="Filter by user..."
            value={filters.actorId || ''}
            onChange={(e) => onChange({ actorId: e.target.value })}
          />
        </div>

        <div className="filter-item">
          <label>Tenant</label>
          <input
            type="text"
            placeholder="Filter by tenant..."
            value={filters.tenantId || ''}
            onChange={(e) => onChange({ tenantId: e.target.value })}
          />
        </div>

        <div className="filter-item">
          <label>IP Address</label>
          <input
            type="text"
            placeholder="Filter by IP..."
            value={filters.ipAddress || ''}
            onChange={(e) => onChange({ ipAddress: e.target.value })}
          />
        </div>
      </div>
    </div>
  );
};
```

### CSS Styles

```css
/* src/features/audit/styles/audit-log.css */

.audit-log-list {
  padding: 24px;
}

/* Filters */
.audit-filters {
  background: var(--bg-secondary);
  padding: 16px;
  border-radius: 8px;
  margin-bottom: 16px;
}

.filter-row {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}

.filter-row.secondary {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--border-color);
}

.filter-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 150px;
}

.filter-item label {
  font-size: 11px;
  text-transform: uppercase;
  color: var(--text-tertiary);
  letter-spacing: 0.5px;
}

/* Audit Table */
.audit-table-container {
  overflow: auto;
  max-height: calc(100vh - 300px);
}

.audit-table {
  width: 100%;
  border-collapse: collapse;
}

.audit-table th {
  position: sticky;
  top: 0;
  background: var(--bg-primary);
  padding: 12px;
  text-align: left;
  font-size: 11px;
  text-transform: uppercase;
  color: var(--text-tertiary);
  border-bottom: 1px solid var(--border-color);
}

.audit-table td {
  padding: 12px;
  border-bottom: 1px solid var(--border-color);
  vertical-align: top;
}

.audit-table tr {
  cursor: pointer;
  transition: background 0.15s;
}

.audit-table tr:hover {
  background: var(--bg-hover);
}

.audit-table tr.severity-critical {
  border-left: 3px solid var(--error-color);
}

.audit-table tr.severity-warning {
  border-left: 3px solid var(--warning-color);
}

/* Cell styles */
.timestamp-cell {
  display: flex;
  flex-direction: column;
}

.timestamp-date {
  font-family: var(--font-mono);
  font-size: 12px;
}

.timestamp-relative {
  font-size: 11px;
  color: var(--text-tertiary);
}

.action-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
  color: white;
}

.action-icon {
  font-size: 10px;
}

.actor-cell,
.resource-cell,
.location-cell {
  display: flex;
  flex-direction: column;
}

.actor-name,
.resource-type {
  font-weight: 500;
}

.actor-email,
.resource-id,
.ip-address,
.geo-location {
  font-size: 11px;
  color: var(--text-tertiary);
}

.resource-id,
.ip-address {
  font-family: var(--font-mono);
}

/* Detail Sheet */
.audit-detail-sheet {
  width: 600px;
  max-width: 90vw;
}

.detail-section {
  margin-bottom: 24px;
}

.detail-section h3 {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-secondary);
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--border-color);
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}

.detail-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.detail-item label {
  font-size: 11px;
  text-transform: uppercase;
  color: var(--text-tertiary);
}

.detail-item span {
  font-size: 13px;
}

.detail-item span.mono {
  font-family: var(--font-mono);
  font-size: 12px;
  word-break: break-all;
}

.severity-badge {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
}

.severity-badge.info {
  background: rgba(33, 150, 243, 0.2);
  color: var(--info-color);
}

.severity-badge.warning {
  background: rgba(255, 152, 0, 0.2);
  color: var(--warning-color);
}

.severity-badge.critical {
  background: rgba(244, 67, 54, 0.2);
  color: var(--error-color);
}

.related-events {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.related-event-item {
  display: flex;
  gap: 12px;
  padding: 8px;
  background: var(--bg-secondary);
  border-radius: 4px;
  font-size: 12px;
}
```

## Definition of Done

- [ ] Audit log list with infinite scroll
- [ ] Advanced filtering (date, action, user, resource)
- [ ] Detail panel with full event data
- [ ] Before/after diff viewer
- [ ] Export to CSV/JSON
- [ ] Related events linking
- [ ] Performance optimized for large datasets
- [ ] Unit tests
- [ ] E2E tests

## Test Cases

```typescript
describe('AuditLogList', () => {
  it('should load audit entries', async () => {
    render(<AuditLogList />);
    
    await waitFor(() => {
      expect(screen.getByText('LOGIN')).toBeInTheDocument();
    });
  });

  it('should filter by date range', async () => {
    render(<AuditLogList />);
    
    // Select last 7 days
    fireEvent.click(screen.getByText('Last 7 Days'));
    
    await waitFor(() => {
      // Verify API called with correct params
      expect(mockApi).toHaveBeenCalledWith(
        expect.objectContaining({ days: 7 })
      );
    });
  });
});

describe('AuditLogDetail', () => {
  it('should show diff viewer for update actions', async () => {
    render(<AuditLogDetail entry={mockUpdateEntry} onClose={() => {}} />);
    
    await waitFor(() => {
      expect(screen.getByText('Changes')).toBeInTheDocument();
    });
  });
});
```
