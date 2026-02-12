# US-13-09: Keyboard Shortcuts & Hotkeys

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-13-09 |
| **Epic** | Epic 13: Workstation UI |
| **Title** | Keyboard Shortcuts & Hotkeys |
| **Priority** | Medium |
| **Story Points** | 5 |
| **Status** | Ready for Development |

## User Story

**As a** power user  
**I want** keyboard shortcuts and hotkeys  
**So that** I can execute common actions quickly without using the mouse

## Acceptance Criteria

### AC1: Global Hotkeys
- **Given** the workstation is active
- **When** I press a hotkey combination
- **Then** the corresponding action executes:
  - F1: Open order ticket
  - F2: Amend selected order
  - Escape: Cancel/close dialogs
  - Ctrl+S: Save workspace

### AC2: Panel Navigation
- **Given** multiple panels open
- **When** I use navigation shortcuts
- **Then** I can:
  - Ctrl+1-9: Switch to panel
  - Ctrl+Tab: Next panel
  - Ctrl+Shift+Tab: Previous panel
  - Ctrl+W: Close current panel

### AC3: Command Palette
- **Given** I press Ctrl+K or Ctrl+P
- **When** the command palette opens
- **Then** I can:
  - Search all commands
  - Execute commands by name
  - See shortcut hints

### AC4: Customization
- **Given** keyboard shortcut settings
- **When** I customize shortcuts
- **Then** I can:
  - View all shortcuts
  - Reassign shortcuts
  - Reset to defaults

### AC5: Context-Aware Shortcuts
- **Given** focus on a specific panel
- **When** I use shortcuts
- **Then** shortcuts apply to:
  - Blotter: Delete row, copy data
  - Chart: Change interval, toggle indicator
  - Order ticket: Submit, cancel

## Technical Specification

### Keyboard Shortcuts Provider

```typescript
// src/shared/providers/KeyboardShortcutsProvider.tsx
import React, { createContext, useContext, useEffect, useCallback, useRef } from 'react';
import { useHotkeys, HotkeyCallback } from 'react-hotkeys-hook';
import { useShortcutStore } from '../stores/shortcut.store';
import { useLayoutStore } from '../../features/layout/stores/layout.store';
import { useOrderTicketStore } from '../../features/order-ticket/stores/order-ticket.store';

interface ShortcutContext {
  registerShortcut: (id: string, keys: string, callback: HotkeyCallback) => void;
  unregisterShortcut: (id: string) => void;
  isEnabled: boolean;
  setEnabled: (enabled: boolean) => void;
}

const KeyboardShortcutsContext = createContext<ShortcutContext | null>(null);

export const KeyboardShortcutsProvider: React.FC<{
  children: React.ReactNode;
}> = ({ children }) => {
  const shortcutsRef = useRef<Map<string, { keys: string; callback: HotkeyCallback }>>(new Map());
  const [isEnabled, setIsEnabled] = React.useState(true);
  
  const { shortcuts } = useShortcutStore();
  const { focusPanel, closePanel, activePanelId } = useLayoutStore();
  const { openTicket, closeTicket, isOpen: isTicketOpen } = useOrderTicketStore();

  // Global shortcuts
  useHotkeys('f1', (e) => {
    e.preventDefault();
    openTicket({ mode: 'new' });
  }, { enabled: isEnabled });

  useHotkeys('escape', () => {
    if (isTicketOpen) {
      closeTicket();
    }
  }, { enabled: isEnabled });

  useHotkeys('ctrl+s', (e) => {
    e.preventDefault();
    // Save workspace
    document.dispatchEvent(new CustomEvent('save-workspace'));
  }, { enabled: isEnabled });

  useHotkeys('ctrl+k, ctrl+p', (e) => {
    e.preventDefault();
    document.dispatchEvent(new CustomEvent('open-command-palette'));
  }, { enabled: isEnabled });

  // Panel navigation (Ctrl+1 through Ctrl+9)
  useHotkeys(
    'ctrl+1, ctrl+2, ctrl+3, ctrl+4, ctrl+5, ctrl+6, ctrl+7, ctrl+8, ctrl+9',
    (e, handler) => {
      e.preventDefault();
      const panelIndex = parseInt(handler.keys?.split('+')[1] || '1') - 1;
      focusPanel(panelIndex);
    },
    { enabled: isEnabled }
  );

  useHotkeys('ctrl+tab', (e) => {
    e.preventDefault();
    focusPanel('next');
  }, { enabled: isEnabled });

  useHotkeys('ctrl+shift+tab', (e) => {
    e.preventDefault();
    focusPanel('previous');
  }, { enabled: isEnabled });

  useHotkeys('ctrl+w', (e) => {
    e.preventDefault();
    if (activePanelId) {
      closePanel(activePanelId);
    }
  }, { enabled: isEnabled });

  // Custom shortcut registration
  const registerShortcut = useCallback((
    id: string,
    keys: string,
    callback: HotkeyCallback
  ) => {
    shortcutsRef.current.set(id, { keys, callback });
  }, []);

  const unregisterShortcut = useCallback((id: string) => {
    shortcutsRef.current.delete(id);
  }, []);

  const value: ShortcutContext = {
    registerShortcut,
    unregisterShortcut,
    isEnabled,
    setEnabled,
  };

  return (
    <KeyboardShortcutsContext.Provider value={value}>
      {children}
    </KeyboardShortcutsContext.Provider>
  );
};

export const useKeyboardShortcuts = () => {
  const context = useContext(KeyboardShortcutsContext);
  if (!context) {
    throw new Error('useKeyboardShortcuts must be used within KeyboardShortcutsProvider');
  }
  return context;
};
```

### Command Palette Component

```typescript
// src/shared/components/CommandPalette.tsx
import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { useShortcutStore } from '../stores/shortcut.store';
import { Command, useCommandRegistry } from '../hooks/useCommandRegistry';

interface CommandPaletteProps {
  isOpen: boolean;
  onClose: () => void;
}

export const CommandPalette: React.FC<CommandPaletteProps> = ({
  isOpen,
  onClose,
}) => {
  const [query, setQuery] = useState('');
  const [selectedIndex, setSelectedIndex] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);

  const { commands } = useCommandRegistry();
  const { shortcuts } = useShortcutStore();

  // Filter commands based on query
  const filteredCommands = useMemo(() => {
    if (!query.trim()) {
      return commands.slice(0, 10); // Show recent/popular
    }

    const lowerQuery = query.toLowerCase();
    return commands
      .filter(cmd => 
        cmd.label.toLowerCase().includes(lowerQuery) ||
        cmd.keywords?.some(k => k.toLowerCase().includes(lowerQuery))
      )
      .slice(0, 20);
  }, [commands, query]);

  // Reset selection when results change
  useEffect(() => {
    setSelectedIndex(0);
  }, [filteredCommands]);

  // Focus input when opened
  useEffect(() => {
    if (isOpen) {
      setTimeout(() => inputRef.current?.focus(), 0);
    } else {
      setQuery('');
    }
  }, [isOpen]);

  const executeCommand = useCallback((command: Command) => {
    command.execute();
    onClose();
  }, [onClose]);

  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault();
        setSelectedIndex(i => 
          Math.min(i + 1, filteredCommands.length - 1)
        );
        break;
      case 'ArrowUp':
        e.preventDefault();
        setSelectedIndex(i => Math.max(i - 1, 0));
        break;
      case 'Enter':
        e.preventDefault();
        if (filteredCommands[selectedIndex]) {
          executeCommand(filteredCommands[selectedIndex]);
        }
        break;
      case 'Escape':
        onClose();
        break;
    }
  }, [filteredCommands, selectedIndex, executeCommand, onClose]);

  const getShortcutForCommand = (commandId: string) => {
    return shortcuts.find(s => s.commandId === commandId)?.keys;
  };

  return (
    <Dialog.Root open={isOpen} onOpenChange={(open) => !open && onClose()}>
      <Dialog.Portal>
        <Dialog.Overlay className="command-palette-overlay" />
        <Dialog.Content className="command-palette">
          <div className="command-palette-input-wrapper">
            <span className="search-icon">⌘</span>
            <input
              ref={inputRef}
              type="text"
              className="command-palette-input"
              placeholder="Type a command or search..."
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={handleKeyDown}
            />
          </div>

          <div className="command-palette-results">
            {filteredCommands.length === 0 ? (
              <div className="no-results">No commands found</div>
            ) : (
              <ul className="command-list">
                {filteredCommands.map((command, index) => (
                  <li
                    key={command.id}
                    className={`command-item ${index === selectedIndex ? 'selected' : ''}`}
                    onClick={() => executeCommand(command)}
                    onMouseEnter={() => setSelectedIndex(index)}
                  >
                    <div className="command-info">
                      {command.icon && (
                        <span className="command-icon">{command.icon}</span>
                      )}
                      <span className="command-label">{command.label}</span>
                      {command.description && (
                        <span className="command-description">
                          {command.description}
                        </span>
                      )}
                    </div>
                    {getShortcutForCommand(command.id) && (
                      <kbd className="shortcut-hint">
                        {formatShortcut(getShortcutForCommand(command.id)!)}
                      </kbd>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
};

function formatShortcut(keys: string): string {
  return keys
    .replace(/ctrl/gi, '⌃')
    .replace(/alt/gi, '⌥')
    .replace(/shift/gi, '⇧')
    .replace(/\+/g, '')
    .toUpperCase();
}
```

### Shortcut Store

```typescript
// src/shared/stores/shortcut.store.ts
import { create } from 'zustand';
import { persist } from 'zustand/middleware';

interface Shortcut {
  id: string;
  commandId: string;
  keys: string;
  description: string;
  category: string;
  isCustom?: boolean;
}

interface ShortcutState {
  shortcuts: Shortcut[];
  
  // Actions
  updateShortcut: (id: string, keys: string) => void;
  resetShortcut: (id: string) => void;
  resetAllShortcuts: () => void;
  isConflict: (keys: string, excludeId?: string) => boolean;
}

const DEFAULT_SHORTCUTS: Shortcut[] = [
  // Global
  { id: 'new-order', commandId: 'order.new', keys: 'f1', description: 'New Order', category: 'Global' },
  { id: 'amend-order', commandId: 'order.amend', keys: 'f2', description: 'Amend Order', category: 'Global' },
  { id: 'save-workspace', commandId: 'workspace.save', keys: 'ctrl+s', description: 'Save Workspace', category: 'Global' },
  { id: 'command-palette', commandId: 'palette.open', keys: 'ctrl+k', description: 'Command Palette', category: 'Global' },
  { id: 'close-dialog', commandId: 'dialog.close', keys: 'escape', description: 'Close Dialog', category: 'Global' },

  // Navigation
  { id: 'panel-1', commandId: 'panel.focus.1', keys: 'ctrl+1', description: 'Focus Panel 1', category: 'Navigation' },
  { id: 'panel-2', commandId: 'panel.focus.2', keys: 'ctrl+2', description: 'Focus Panel 2', category: 'Navigation' },
  { id: 'panel-3', commandId: 'panel.focus.3', keys: 'ctrl+3', description: 'Focus Panel 3', category: 'Navigation' },
  { id: 'next-panel', commandId: 'panel.next', keys: 'ctrl+tab', description: 'Next Panel', category: 'Navigation' },
  { id: 'prev-panel', commandId: 'panel.prev', keys: 'ctrl+shift+tab', description: 'Previous Panel', category: 'Navigation' },
  { id: 'close-panel', commandId: 'panel.close', keys: 'ctrl+w', description: 'Close Panel', category: 'Navigation' },

  // Order Ticket
  { id: 'submit-order', commandId: 'order.submit', keys: 'ctrl+enter', description: 'Submit Order', category: 'Order Ticket' },
  { id: 'toggle-side', commandId: 'order.toggleSide', keys: 'space', description: 'Toggle Buy/Sell', category: 'Order Ticket' },

  // Blotter
  { id: 'cancel-order', commandId: 'blotter.cancel', keys: 'delete', description: 'Cancel Order', category: 'Blotter' },
  { id: 'copy-row', commandId: 'blotter.copy', keys: 'ctrl+c', description: 'Copy Row', category: 'Blotter' },
  { id: 'refresh-blotter', commandId: 'blotter.refresh', keys: 'f5', description: 'Refresh Blotter', category: 'Blotter' },

  // Charts
  { id: 'chart-1m', commandId: 'chart.interval.1m', keys: '1', description: '1 Minute', category: 'Charts' },
  { id: 'chart-5m', commandId: 'chart.interval.5m', keys: '5', description: '5 Minutes', category: 'Charts' },
  { id: 'chart-1h', commandId: 'chart.interval.1h', keys: 'h', description: '1 Hour', category: 'Charts' },
  { id: 'chart-1d', commandId: 'chart.interval.1d', keys: 'd', description: '1 Day', category: 'Charts' },
];

export const useShortcutStore = create<ShortcutState>()(
  persist(
    (set, get) => ({
      shortcuts: DEFAULT_SHORTCUTS,

      updateShortcut: (id, keys) => {
        set((state) => ({
          shortcuts: state.shortcuts.map(s =>
            s.id === id ? { ...s, keys, isCustom: true } : s
          ),
        }));
      },

      resetShortcut: (id) => {
        const defaultShortcut = DEFAULT_SHORTCUTS.find(s => s.id === id);
        if (defaultShortcut) {
          set((state) => ({
            shortcuts: state.shortcuts.map(s =>
              s.id === id ? { ...defaultShortcut } : s
            ),
          }));
        }
      },

      resetAllShortcuts: () => {
        set({ shortcuts: DEFAULT_SHORTCUTS });
      },

      isConflict: (keys, excludeId) => {
        const normalizedKeys = keys.toLowerCase().replace(/\s/g, '');
        return get().shortcuts.some(s =>
          s.id !== excludeId &&
          s.keys.toLowerCase().replace(/\s/g, '') === normalizedKeys
        );
      },
    }),
    {
      name: 'orion-shortcuts',
    }
  )
);
```

### Shortcut Settings Component

```typescript
// src/features/settings/components/ShortcutSettings.tsx
import React, { useState } from 'react';
import { useShortcutStore } from '../../../shared/stores/shortcut.store';

export const ShortcutSettings: React.FC = () => {
  const { shortcuts, updateShortcut, resetShortcut, resetAllShortcuts, isConflict } = useShortcutStore();
  const [editingId, setEditingId] = useState<string | null>(null);
  const [recordedKeys, setRecordedKeys] = useState<string>('');
  const [error, setError] = useState<string | null>(null);

  // Group shortcuts by category
  const groupedShortcuts = shortcuts.reduce((acc, shortcut) => {
    if (!acc[shortcut.category]) {
      acc[shortcut.category] = [];
    }
    acc[shortcut.category].push(shortcut);
    return acc;
  }, {} as Record<string, typeof shortcuts>);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    e.preventDefault();
    
    const parts: string[] = [];
    if (e.ctrlKey) parts.push('ctrl');
    if (e.altKey) parts.push('alt');
    if (e.shiftKey) parts.push('shift');
    
    const key = e.key.toLowerCase();
    if (!['control', 'alt', 'shift', 'meta'].includes(key)) {
      parts.push(key === ' ' ? 'space' : key);
    }

    if (parts.length > 0) {
      const keys = parts.join('+');
      setRecordedKeys(keys);
      
      if (isConflict(keys, editingId || undefined)) {
        setError('This shortcut is already in use');
      } else {
        setError(null);
      }
    }
  };

  const handleSave = () => {
    if (editingId && recordedKeys && !error) {
      updateShortcut(editingId, recordedKeys);
      setEditingId(null);
      setRecordedKeys('');
    }
  };

  const handleCancel = () => {
    setEditingId(null);
    setRecordedKeys('');
    setError(null);
  };

  return (
    <div className="shortcut-settings">
      <div className="settings-header">
        <h2>Keyboard Shortcuts</h2>
        <button
          className="btn-secondary"
          onClick={resetAllShortcuts}
        >
          Reset All
        </button>
      </div>

      {Object.entries(groupedShortcuts).map(([category, categoryShortcuts]) => (
        <div key={category} className="shortcut-category">
          <h3 className="category-title">{category}</h3>
          <div className="shortcut-list">
            {categoryShortcuts.map((shortcut) => (
              <div key={shortcut.id} className="shortcut-row">
                <div className="shortcut-info">
                  <span className="shortcut-description">
                    {shortcut.description}
                  </span>
                  {shortcut.isCustom && (
                    <span className="custom-badge">Custom</span>
                  )}
                </div>

                {editingId === shortcut.id ? (
                  <div className="shortcut-edit">
                    <input
                      type="text"
                      className={`shortcut-input ${error ? 'error' : ''}`}
                      placeholder="Press keys..."
                      value={recordedKeys}
                      onKeyDown={handleKeyDown}
                      autoFocus
                      readOnly
                    />
                    {error && <span className="error-text">{error}</span>}
                    <button className="btn-icon" onClick={handleSave} disabled={!!error}>
                      ✓
                    </button>
                    <button className="btn-icon" onClick={handleCancel}>
                      ✕
                    </button>
                  </div>
                ) : (
                  <div className="shortcut-display">
                    <kbd className="shortcut-keys">
                      {formatShortcutDisplay(shortcut.keys)}
                    </kbd>
                    <button
                      className="btn-icon"
                      onClick={() => setEditingId(shortcut.id)}
                    >
                      ✎
                    </button>
                    {shortcut.isCustom && (
                      <button
                        className="btn-icon"
                        onClick={() => resetShortcut(shortcut.id)}
                        title="Reset to default"
                      >
                        ↺
                      </button>
                    )}
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
};

function formatShortcutDisplay(keys: string): string {
  return keys
    .split('+')
    .map(k => {
      switch (k.toLowerCase()) {
        case 'ctrl': return 'Ctrl';
        case 'alt': return 'Alt';
        case 'shift': return 'Shift';
        case 'space': return '␣';
        case 'escape': return 'Esc';
        case 'delete': return 'Del';
        case 'enter': return '↵';
        default: return k.toUpperCase();
      }
    })
    .join(' + ');
}
```

### CSS Styles

```css
/* src/shared/styles/command-palette.css */

.command-palette-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  backdrop-filter: blur(2px);
  z-index: 1000;
}

.command-palette {
  position: fixed;
  top: 20%;
  left: 50%;
  transform: translateX(-50%);
  width: 600px;
  max-width: calc(100vw - 32px);
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.4);
  z-index: 1001;
  overflow: hidden;
}

.command-palette-input-wrapper {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border-color);
}

.search-icon {
  font-size: 16px;
  color: var(--text-tertiary);
  margin-right: 12px;
}

.command-palette-input {
  flex: 1;
  background: transparent;
  border: none;
  font-size: 16px;
  color: var(--text-primary);
  outline: none;
}

.command-palette-input::placeholder {
  color: var(--text-tertiary);
}

.command-palette-results {
  max-height: 400px;
  overflow-y: auto;
}

.command-list {
  list-style: none;
  margin: 0;
  padding: 8px;
}

.command-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-radius: 4px;
  cursor: pointer;
}

.command-item:hover,
.command-item.selected {
  background: var(--bg-hover);
}

.command-info {
  display: flex;
  align-items: center;
  gap: 10px;
}

.command-icon {
  font-size: 16px;
}

.command-label {
  font-size: 14px;
  color: var(--text-primary);
}

.command-description {
  font-size: 12px;
  color: var(--text-tertiary);
}

.shortcut-hint {
  padding: 2px 6px;
  background: var(--bg-secondary);
  border-radius: 4px;
  font-size: 11px;
  color: var(--text-secondary);
  font-family: var(--font-mono);
}

.no-results {
  padding: 24px;
  text-align: center;
  color: var(--text-tertiary);
}

/* Shortcut Settings */
.shortcut-settings {
  padding: 24px;
}

.shortcut-category {
  margin-bottom: 24px;
}

.category-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 12px;
}

.shortcut-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 0;
  border-bottom: 1px solid var(--border-color);
}

.shortcut-info {
  display: flex;
  align-items: center;
  gap: 8px;
}

.custom-badge {
  font-size: 10px;
  padding: 2px 6px;
  background: var(--accent-color);
  border-radius: 4px;
  color: white;
}

.shortcut-display,
.shortcut-edit {
  display: flex;
  align-items: center;
  gap: 8px;
}

.shortcut-keys {
  padding: 4px 8px;
  background: var(--bg-secondary);
  border-radius: 4px;
  font-family: var(--font-mono);
  font-size: 12px;
}

.shortcut-input {
  width: 150px;
  padding: 6px 10px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 4px;
  font-family: var(--font-mono);
  color: var(--text-primary);
}

.shortcut-input.error {
  border-color: var(--error-color);
}

.error-text {
  font-size: 11px;
  color: var(--error-color);
}

.btn-icon {
  padding: 4px 8px;
  background: transparent;
  border: none;
  cursor: pointer;
  color: var(--text-secondary);
}

.btn-icon:hover {
  color: var(--text-primary);
}
```

## Definition of Done

- [ ] Global hotkey system implemented
- [ ] Panel navigation shortcuts working
- [ ] Command palette functional
- [ ] Shortcut customization UI
- [ ] Conflict detection
- [ ] Context-aware shortcuts per panel
- [ ] Shortcuts persisted to storage
- [ ] Unit tests for shortcut store
- [ ] E2E tests for key combinations

## Test Cases

```typescript
describe('KeyboardShortcuts', () => {
  it('should open order ticket on F1', () => {
    render(<App />);
    
    fireEvent.keyDown(document, { key: 'F1' });
    
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('should detect shortcut conflicts', () => {
    const { result } = renderHook(() => useShortcutStore());
    
    expect(result.current.isConflict('ctrl+s')).toBe(true);
    expect(result.current.isConflict('ctrl+x')).toBe(false);
  });
});

describe('CommandPalette', () => {
  it('should filter commands by query', () => {
    render(<CommandPalette isOpen={true} onClose={() => {}} />);
    
    fireEvent.change(screen.getByPlaceholderText(/search/i), {
      target: { value: 'order' },
    });

    expect(screen.getByText('New Order')).toBeInTheDocument();
  });

  it('should execute command on Enter', () => {
    const mockExecute = jest.fn();
    render(<CommandPalette isOpen={true} onClose={() => {}} />);
    
    fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
    
    expect(mockExecute).toHaveBeenCalled();
  });
});
```
