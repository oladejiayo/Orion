# US-13-01: Workspace Layout System

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-13-01 |
| **Epic** | Epic 13: Workstation UI |
| **Title** | Workspace Layout System |
| **Priority** | Critical |
| **Story Points** | 13 |
| **Status** | Ready for Development |

## User Story

**As a** trader  
**I want** a flexible workspace layout system  
**So that** I can arrange my trading panels according to my workflow preferences

## Acceptance Criteria

### AC1: Drag-and-Drop Panel Arrangement
- **Given** a workspace with multiple panels
- **When** I drag a panel by its header
- **Then** I can:
  - Drop it to create new split regions (left/right/top/bottom)
  - Drop it as a tab within existing panel groups
  - Preview the drop target zone while dragging
  - Cancel drag with Escape key

### AC2: Panel Management
- **Given** the workspace layout manager
- **When** I interact with panels
- **Then** I can:
  - Maximize any panel to full workspace
  - Close panels with X button
  - Pop out panels to separate windows
  - Minimize panel groups to header-only

### AC3: Workspace Persistence
- **Given** a configured workspace layout
- **When** I save the workspace
- **Then**:
  - Layout configuration is persisted to backend
  - Individual panel states are preserved
  - Workspace can be loaded on next login
  - Changes are auto-saved every 30 seconds

### AC4: Multiple Workspaces
- **Given** the workspace selector
- **When** I manage workspaces
- **Then** I can:
  - Create new workspaces
  - Rename existing workspaces
  - Delete workspaces
  - Switch between workspaces
  - Duplicate existing workspace

### AC5: Panel Linking
- **Given** multiple panels (e.g., Market Data, Chart, Order Ticket)
- **When** I select an instrument in one panel
- **Then**:
  - Linked panels update to the same instrument
  - Link groups are configurable via color coding
  - Panels can be unlinked individually

## Technical Specification

### Layout Store

```typescript
// src/shared/stores/layout.store.ts
import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import { IJsonModel, Model, Actions } from 'flexlayout-react';

export interface PanelConfig {
  id: string;
  type: string;
  title: string;
  props?: Record<string, any>;
  linkGroup?: string;
}

export interface WorkspaceConfig {
  id: string;
  name: string;
  layoutModel: IJsonModel;
  panels: PanelConfig[];
  isDefault: boolean;
  createdAt: string;
  updatedAt: string;
}

interface LayoutState {
  // Current workspace
  activeWorkspaceId: string | null;
  workspaces: WorkspaceConfig[];
  layoutModel: Model | null;
  
  // Link groups
  linkGroups: Map<string, string>; // panelId -> linkColor
  selectedInstrument: Map<string, string>; // linkColor -> instrumentId
  
  // Actions
  loadWorkspace: (workspaceId: string) => Promise<void>;
  saveWorkspace: () => Promise<void>;
  createWorkspace: (name: string, template?: string) => Promise<WorkspaceConfig>;
  deleteWorkspace: (workspaceId: string) => Promise<void>;
  duplicateWorkspace: (workspaceId: string, newName: string) => Promise<WorkspaceConfig>;
  
  // Layout actions
  setLayoutModel: (model: Model) => void;
  addPanel: (config: PanelConfig, targetNode?: string) => void;
  removePanel: (panelId: string) => void;
  updatePanelProps: (panelId: string, props: Record<string, any>) => void;
  
  // Linking
  setLinkGroup: (panelId: string, linkColor: string | null) => void;
  setLinkedInstrument: (linkColor: string, instrumentId: string) => void;
}

export const useLayoutStore = create<LayoutState>()(
  persist(
    (set, get) => ({
      activeWorkspaceId: null,
      workspaces: [],
      layoutModel: null,
      linkGroups: new Map(),
      selectedInstrument: new Map(),

      loadWorkspace: async (workspaceId: string) => {
        const workspace = get().workspaces.find(w => w.id === workspaceId);
        if (!workspace) {
          throw new Error('Workspace not found');
        }

        const model = Model.fromJson(workspace.layoutModel);
        set({
          activeWorkspaceId: workspaceId,
          layoutModel: model,
        });
      },

      saveWorkspace: async () => {
        const { activeWorkspaceId, layoutModel, workspaces } = get();
        if (!activeWorkspaceId || !layoutModel) return;

        const updatedWorkspaces = workspaces.map(w => {
          if (w.id === activeWorkspaceId) {
            return {
              ...w,
              layoutModel: layoutModel.toJson() as IJsonModel,
              updatedAt: new Date().toISOString(),
            };
          }
          return w;
        });

        set({ workspaces: updatedWorkspaces });

        // Sync to backend
        await workspaceApi.save(activeWorkspaceId, updatedWorkspaces.find(w => w.id === activeWorkspaceId)!);
      },

      createWorkspace: async (name: string, template?: string) => {
        const baseLayout = template 
          ? getTemplateLayout(template) 
          : getDefaultLayout();

        const workspace: WorkspaceConfig = {
          id: crypto.randomUUID(),
          name,
          layoutModel: baseLayout,
          panels: [],
          isDefault: false,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        };

        set(state => ({
          workspaces: [...state.workspaces, workspace],
        }));

        await workspaceApi.create(workspace);
        return workspace;
      },

      deleteWorkspace: async (workspaceId: string) => {
        set(state => ({
          workspaces: state.workspaces.filter(w => w.id !== workspaceId),
          activeWorkspaceId: state.activeWorkspaceId === workspaceId 
            ? state.workspaces.find(w => w.isDefault)?.id || null
            : state.activeWorkspaceId,
        }));

        await workspaceApi.delete(workspaceId);
      },

      duplicateWorkspace: async (workspaceId: string, newName: string) => {
        const source = get().workspaces.find(w => w.id === workspaceId);
        if (!source) throw new Error('Source workspace not found');

        const duplicate: WorkspaceConfig = {
          ...source,
          id: crypto.randomUUID(),
          name: newName,
          isDefault: false,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        };

        set(state => ({
          workspaces: [...state.workspaces, duplicate],
        }));

        await workspaceApi.create(duplicate);
        return duplicate;
      },

      setLayoutModel: (model: Model) => {
        set({ layoutModel: model });
      },

      addPanel: (config: PanelConfig, targetNode?: string) => {
        const { layoutModel } = get();
        if (!layoutModel) return;

        layoutModel.doAction(
          Actions.addNode(
            {
              type: 'tab',
              id: config.id,
              name: config.title,
              component: config.type,
              config: config.props,
            },
            targetNode || 'root',
            0,
            -1
          )
        );
      },

      removePanel: (panelId: string) => {
        const { layoutModel } = get();
        if (!layoutModel) return;

        layoutModel.doAction(Actions.deleteTab(panelId));
      },

      updatePanelProps: (panelId: string, props: Record<string, any>) => {
        const { layoutModel } = get();
        if (!layoutModel) return;

        layoutModel.doAction(
          Actions.updateNodeAttributes(panelId, { config: props })
        );
      },

      setLinkGroup: (panelId: string, linkColor: string | null) => {
        set(state => {
          const newGroups = new Map(state.linkGroups);
          if (linkColor) {
            newGroups.set(panelId, linkColor);
          } else {
            newGroups.delete(panelId);
          }
          return { linkGroups: newGroups };
        });
      },

      setLinkedInstrument: (linkColor: string, instrumentId: string) => {
        set(state => {
          const newInstruments = new Map(state.selectedInstrument);
          newInstruments.set(linkColor, instrumentId);
          return { selectedInstrument: newInstruments };
        });
      },
    }),
    {
      name: 'orion-layout',
      storage: createJSONStorage(() => localStorage),
      partialize: (state) => ({
        activeWorkspaceId: state.activeWorkspaceId,
        linkGroups: Array.from(state.linkGroups.entries()),
      }),
    }
  )
);

// Helper functions
function getDefaultLayout(): IJsonModel {
  return {
    global: {
      tabEnableFloat: true,
      tabEnablePopout: true,
    },
    borders: [],
    layout: {
      type: 'row',
      weight: 100,
      children: [
        {
          type: 'tabset',
          weight: 60,
          children: [
            {
              type: 'tab',
              name: 'Order Blotter',
              component: 'OrderBlotter',
            },
          ],
        },
        {
          type: 'column',
          weight: 40,
          children: [
            {
              type: 'tabset',
              weight: 50,
              children: [
                {
                  type: 'tab',
                  name: 'Market Data',
                  component: 'MarketDataGrid',
                },
              ],
            },
            {
              type: 'tabset',
              weight: 50,
              children: [
                {
                  type: 'tab',
                  name: 'Positions',
                  component: 'PositionMonitor',
                },
              ],
            },
          ],
        },
      ],
    },
  };
}

function getTemplateLayout(template: string): IJsonModel {
  const templates: Record<string, IJsonModel> = {
    trader: getDefaultLayout(),
    analyst: {
      global: { tabEnableFloat: true },
      borders: [],
      layout: {
        type: 'row',
        children: [
          { type: 'tabset', weight: 70, children: [{ type: 'tab', name: 'Charts', component: 'ChartWidget' }] },
          { type: 'tabset', weight: 30, children: [{ type: 'tab', name: 'Analytics', component: 'AnalyticsDashboard' }] },
        ],
      },
    },
  };

  return templates[template] || getDefaultLayout();
}
```

### Layout Manager Component

```typescript
// src/app/components/LayoutManager.tsx
import React, { useCallback, useEffect, useRef } from 'react';
import { Layout, Model, ITabRenderValues, TabNode, IJsonModel } from 'flexlayout-react';
import 'flexlayout-react/style/light.css';
import { useLayoutStore } from '../../shared/stores/layout.store';
import { PanelRegistry } from './PanelRegistry';
import { PanelHeader } from './PanelHeader';

interface LayoutManagerProps {
  onLayoutChange?: () => void;
}

export const LayoutManager: React.FC<LayoutManagerProps> = ({ onLayoutChange }) => {
  const { 
    layoutModel, 
    setLayoutModel, 
    saveWorkspace,
    activeWorkspaceId,
  } = useLayoutStore();
  
  const layoutRef = useRef<Layout>(null);
  const autoSaveTimerRef = useRef<NodeJS.Timeout | null>(null);

  // Initialize layout model
  useEffect(() => {
    if (!layoutModel && activeWorkspaceId) {
      // Load from backend or use default
      const defaultModel = Model.fromJson(getDefaultLayout());
      setLayoutModel(defaultModel);
    }
  }, [activeWorkspaceId]);

  // Auto-save every 30 seconds
  useEffect(() => {
    autoSaveTimerRef.current = setInterval(() => {
      saveWorkspace();
    }, 30000);

    return () => {
      if (autoSaveTimerRef.current) {
        clearInterval(autoSaveTimerRef.current);
      }
    };
  }, [saveWorkspace]);

  // Factory function for rendering panel content
  const factory = useCallback((node: TabNode) => {
    const component = node.getComponent();
    const config = node.getConfig();
    
    const PanelComponent = PanelRegistry.get(component || '');
    if (!PanelComponent) {
      return <div className="panel-error">Unknown panel: {component}</div>;
    }

    return <PanelComponent {...config} panelId={node.getId()} />;
  }, []);

  // Custom tab rendering with link indicator
  const onRenderTab = useCallback((
    node: TabNode,
    renderValues: ITabRenderValues
  ) => {
    const linkColor = useLayoutStore.getState().linkGroups.get(node.getId());
    
    if (linkColor) {
      renderValues.leading = (
        <div 
          className="link-indicator"
          style={{ backgroundColor: linkColor }}
          title={`Linked: ${linkColor}`}
        />
      );
    }
  }, []);

  // Handle model changes
  const onModelChange = useCallback((model: Model) => {
    setLayoutModel(model);
    onLayoutChange?.();
  }, [setLayoutModel, onLayoutChange]);

  // Handle tab context menu
  const onContextMenu = useCallback((node: TabNode, event: React.MouseEvent) => {
    event.preventDefault();
    // Show context menu for panel operations
  }, []);

  if (!layoutModel) {
    return <div className="layout-loading">Loading workspace...</div>;
  }

  return (
    <div className="layout-manager">
      <Layout
        ref={layoutRef}
        model={layoutModel}
        factory={factory}
        onRenderTab={onRenderTab}
        onModelChange={onModelChange}
        onContextMenu={onContextMenu}
        classNameMapper={(className) => `orion-${className}`}
        supportsPopout={true}
        popoutURL="/popout.html"
      />
    </div>
  );
};
```

### Panel Registry

```typescript
// src/app/components/PanelRegistry.tsx
import React, { lazy, Suspense, ComponentType } from 'react';
import { PanelSkeleton } from '../../shared/components/PanelSkeleton';

// Lazy load panel components for code splitting
const OrderBlotter = lazy(() => import('../../features/blotter/components/OrderBlotter'));
const TradeBlotter = lazy(() => import('../../features/blotter/components/TradeBlotter'));
const OrderTicket = lazy(() => import('../../features/order-ticket/components/OrderTicket'));
const MarketDataGrid = lazy(() => import('../../features/market-data/components/MarketDataGrid'));
const PositionMonitor = lazy(() => import('../../features/positions/components/PositionMonitor'));
const ChartWidget = lazy(() => import('../../features/charts/components/ChartWidget'));
const RiskDashboard = lazy(() => import('../../features/risk/components/RiskDashboard'));
const AnalyticsDashboard = lazy(() => import('../../features/analytics/components/AnalyticsDashboard'));

interface PanelWrapperProps {
  Component: ComponentType<any>;
  props: Record<string, any>;
}

const PanelWrapper: React.FC<PanelWrapperProps> = ({ Component, props }) => (
  <Suspense fallback={<PanelSkeleton />}>
    <Component {...props} />
  </Suspense>
);

type PanelComponent = ComponentType<any>;

class PanelRegistryClass {
  private panels = new Map<string, PanelComponent>();

  constructor() {
    // Register all available panels
    this.register('OrderBlotter', OrderBlotter);
    this.register('TradeBlotter', TradeBlotter);
    this.register('OrderTicket', OrderTicket);
    this.register('MarketDataGrid', MarketDataGrid);
    this.register('PositionMonitor', PositionMonitor);
    this.register('ChartWidget', ChartWidget);
    this.register('RiskDashboard', RiskDashboard);
    this.register('AnalyticsDashboard', AnalyticsDashboard);
  }

  register(name: string, component: PanelComponent): void {
    this.panels.set(name, component);
  }

  get(name: string): ComponentType<any> | undefined {
    const Component = this.panels.get(name);
    if (!Component) return undefined;

    return (props: any) => <PanelWrapper Component={Component} props={props} />;
  }

  list(): string[] {
    return Array.from(this.panels.keys());
  }
}

export const PanelRegistry = new PanelRegistryClass();
```

### Workspace Selector Component

```typescript
// src/app/components/WorkspaceSelector.tsx
import React, { useState } from 'react';
import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import { 
  ChevronDownIcon, 
  PlusIcon, 
  CopyIcon, 
  TrashIcon,
  CheckIcon,
} from '@radix-ui/react-icons';
import { useLayoutStore, WorkspaceConfig } from '../../shared/stores/layout.store';
import { Dialog } from '../../shared/components/Dialog';

export const WorkspaceSelector: React.FC = () => {
  const {
    workspaces,
    activeWorkspaceId,
    loadWorkspace,
    createWorkspace,
    deleteWorkspace,
    duplicateWorkspace,
  } = useLayoutStore();

  const [isCreateDialogOpen, setCreateDialogOpen] = useState(false);
  const [newWorkspaceName, setNewWorkspaceName] = useState('');
  const [selectedTemplate, setSelectedTemplate] = useState('default');

  const activeWorkspace = workspaces.find(w => w.id === activeWorkspaceId);

  const handleCreate = async () => {
    if (!newWorkspaceName.trim()) return;
    
    const workspace = await createWorkspace(newWorkspaceName, selectedTemplate);
    await loadWorkspace(workspace.id);
    setCreateDialogOpen(false);
    setNewWorkspaceName('');
  };

  const handleDuplicate = async (workspace: WorkspaceConfig) => {
    const duplicate = await duplicateWorkspace(workspace.id, `${workspace.name} (Copy)`);
    await loadWorkspace(duplicate.id);
  };

  const handleDelete = async (workspace: WorkspaceConfig) => {
    if (workspace.isDefault) {
      alert('Cannot delete default workspace');
      return;
    }

    if (confirm(`Delete workspace "${workspace.name}"?`)) {
      await deleteWorkspace(workspace.id);
    }
  };

  return (
    <>
      <DropdownMenu.Root>
        <DropdownMenu.Trigger asChild>
          <button className="workspace-selector-trigger">
            <span>{activeWorkspace?.name || 'Select Workspace'}</span>
            <ChevronDownIcon />
          </button>
        </DropdownMenu.Trigger>

        <DropdownMenu.Portal>
          <DropdownMenu.Content className="workspace-dropdown-content" sideOffset={5}>
            <DropdownMenu.Label className="dropdown-label">
              Workspaces
            </DropdownMenu.Label>
            
            {workspaces.map(workspace => (
              <DropdownMenu.Item
                key={workspace.id}
                className="workspace-item"
                onSelect={() => loadWorkspace(workspace.id)}
              >
                <span className="workspace-name">
                  {workspace.id === activeWorkspaceId && <CheckIcon className="check-icon" />}
                  {workspace.name}
                </span>
                <div className="workspace-actions">
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      handleDuplicate(workspace);
                    }}
                    title="Duplicate"
                  >
                    <CopyIcon />
                  </button>
                  {!workspace.isDefault && (
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        handleDelete(workspace);
                      }}
                      title="Delete"
                    >
                      <TrashIcon />
                    </button>
                  )}
                </div>
              </DropdownMenu.Item>
            ))}

            <DropdownMenu.Separator />

            <DropdownMenu.Item
              className="create-workspace-item"
              onSelect={() => setCreateDialogOpen(true)}
            >
              <PlusIcon />
              <span>New Workspace</span>
            </DropdownMenu.Item>
          </DropdownMenu.Content>
        </DropdownMenu.Portal>
      </DropdownMenu.Root>

      <Dialog
        open={isCreateDialogOpen}
        onOpenChange={setCreateDialogOpen}
        title="Create Workspace"
      >
        <div className="create-workspace-form">
          <div className="form-field">
            <label>Workspace Name</label>
            <input
              type="text"
              value={newWorkspaceName}
              onChange={(e) => setNewWorkspaceName(e.target.value)}
              placeholder="My Workspace"
              autoFocus
            />
          </div>

          <div className="form-field">
            <label>Template</label>
            <select
              value={selectedTemplate}
              onChange={(e) => setSelectedTemplate(e.target.value)}
            >
              <option value="default">Default (Trader)</option>
              <option value="analyst">Analyst</option>
              <option value="operations">Operations</option>
              <option value="blank">Blank</option>
            </select>
          </div>

          <div className="form-actions">
            <button onClick={() => setCreateDialogOpen(false)}>Cancel</button>
            <button onClick={handleCreate} className="primary">Create</button>
          </div>
        </div>
      </Dialog>
    </>
  );
};
```

### Panel Linking Hook

```typescript
// src/shared/hooks/usePanelLink.ts
import { useCallback, useEffect } from 'react';
import { useLayoutStore } from '../stores/layout.store';

const LINK_COLORS = ['#4CAF50', '#2196F3', '#FF9800', '#E91E63', '#9C27B0'];

export interface UsePanelLinkResult {
  linkedInstrumentId: string | null;
  linkColor: string | null;
  availableColors: string[];
  setLinkColor: (color: string | null) => void;
  updateLinkedInstrument: (instrumentId: string) => void;
  isLinked: boolean;
}

export function usePanelLink(panelId: string): UsePanelLinkResult {
  const { 
    linkGroups, 
    selectedInstrument,
    setLinkGroup,
    setLinkedInstrument,
  } = useLayoutStore();

  const linkColor = linkGroups.get(panelId) || null;
  const linkedInstrumentId = linkColor ? (selectedInstrument.get(linkColor) || null) : null;

  // Update linked instrument for all panels in same link group
  const updateLinkedInstrument = useCallback((instrumentId: string) => {
    if (linkColor) {
      setLinkedInstrument(linkColor, instrumentId);
    }
  }, [linkColor, setLinkedInstrument]);

  // Set or remove link color for this panel
  const setLinkColor = useCallback((color: string | null) => {
    setLinkGroup(panelId, color);
  }, [panelId, setLinkGroup]);

  // Get colors not used by this panel
  const usedColors = new Set(linkGroups.values());
  const availableColors = LINK_COLORS.filter(c => 
    !usedColors.has(c) || c === linkColor
  );

  return {
    linkedInstrumentId,
    linkColor,
    availableColors,
    setLinkColor,
    updateLinkedInstrument,
    isLinked: !!linkColor,
  };
}
```

### Workspace API Service

```typescript
// src/shared/services/workspace.api.ts
import { apiClient } from './api-client';
import { WorkspaceConfig } from '../stores/layout.store';

export const workspaceApi = {
  async list(): Promise<WorkspaceConfig[]> {
    const response = await apiClient.get<WorkspaceConfig[]>('/user/workspaces');
    return response.data;
  },

  async get(workspaceId: string): Promise<WorkspaceConfig> {
    const response = await apiClient.get<WorkspaceConfig>(`/user/workspaces/${workspaceId}`);
    return response.data;
  },

  async create(workspace: WorkspaceConfig): Promise<WorkspaceConfig> {
    const response = await apiClient.post<WorkspaceConfig>('/user/workspaces', workspace);
    return response.data;
  },

  async save(workspaceId: string, workspace: WorkspaceConfig): Promise<void> {
    await apiClient.put(`/user/workspaces/${workspaceId}`, workspace);
  },

  async delete(workspaceId: string): Promise<void> {
    await apiClient.delete(`/user/workspaces/${workspaceId}`);
  },

  async setDefault(workspaceId: string): Promise<void> {
    await apiClient.post(`/user/workspaces/${workspaceId}/set-default`);
  },
};
```

### CSS Styles

```css
/* src/styles/layout.css */

.layout-manager {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
}

/* FlexLayout overrides */
.orion-flexlayout__layout {
  --color-1: var(--bg-primary);
  --color-2: var(--bg-secondary);
  --color-3: var(--border-color);
  --color-text: var(--text-primary);
  --color-drag1: var(--accent-color);
  --color-drag2: var(--accent-color-light);
}

.orion-flexlayout__tab {
  background-color: var(--bg-primary);
  border-color: var(--border-color);
}

.orion-flexlayout__tab_button {
  padding: 4px 12px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary);
}

.orion-flexlayout__tab_button--selected {
  color: var(--text-primary);
  background-color: var(--bg-secondary);
}

.orion-flexlayout__tabset-header {
  background-color: var(--bg-tertiary);
  border-bottom: 1px solid var(--border-color);
}

/* Link indicator */
.link-indicator {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 6px;
  flex-shrink: 0;
}

/* Workspace selector */
.workspace-selector-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
}

.workspace-selector-trigger:hover {
  background: var(--bg-tertiary);
}

.workspace-dropdown-content {
  min-width: 240px;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  box-shadow: var(--shadow-lg);
  padding: 8px;
}

.workspace-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-radius: 4px;
  cursor: pointer;
}

.workspace-item:hover {
  background: var(--bg-secondary);
}

.workspace-actions {
  display: flex;
  gap: 4px;
  opacity: 0;
}

.workspace-item:hover .workspace-actions {
  opacity: 1;
}

.workspace-actions button {
  padding: 4px;
  background: transparent;
  border: none;
  cursor: pointer;
  color: var(--text-secondary);
}

.workspace-actions button:hover {
  color: var(--text-primary);
}

/* Panel loading state */
.layout-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  font-size: 16px;
  color: var(--text-secondary);
}

.panel-error {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--error-color);
  background: var(--error-bg);
}
```

## Definition of Done

- [ ] FlexLayout integration working
- [ ] Drag-and-drop panel arrangement
- [ ] Panel maximize/close/popout
- [ ] Workspace save/load to backend
- [ ] Multiple workspace support
- [ ] Panel linking with color groups
- [ ] Auto-save functionality
- [ ] Workspace templates
- [ ] Keyboard shortcuts (Ctrl+S to save)
- [ ] Unit tests for layout store
- [ ] Integration tests for workspace operations

## Test Cases

### Unit Tests
```typescript
describe('useLayoutStore', () => {
  it('should create a new workspace', async () => {
    const { createWorkspace, workspaces } = useLayoutStore.getState();
    
    await createWorkspace('Test Workspace', 'default');
    
    expect(workspaces).toHaveLength(1);
    expect(workspaces[0].name).toBe('Test Workspace');
  });

  it('should manage link groups', () => {
    const { setLinkGroup, linkGroups } = useLayoutStore.getState();
    
    setLinkGroup('panel-1', '#4CAF50');
    setLinkGroup('panel-2', '#4CAF50');
    
    expect(linkGroups.get('panel-1')).toBe('#4CAF50');
    expect(linkGroups.get('panel-2')).toBe('#4CAF50');
  });
});

describe('usePanelLink', () => {
  it('should sync instrument across linked panels', () => {
    const { result: panel1 } = renderHook(() => usePanelLink('panel-1'));
    const { result: panel2 } = renderHook(() => usePanelLink('panel-2'));
    
    // Link both panels
    act(() => {
      panel1.current.setLinkColor('#4CAF50');
      panel2.current.setLinkColor('#4CAF50');
    });
    
    // Update instrument from panel 1
    act(() => {
      panel1.current.updateLinkedInstrument('EURUSD');
    });
    
    // Panel 2 should receive the update
    expect(panel2.current.linkedInstrumentId).toBe('EURUSD');
  });
});
```
