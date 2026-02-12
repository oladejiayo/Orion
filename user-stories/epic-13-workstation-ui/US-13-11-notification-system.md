# US-13-11: Notification System

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-13-11 |
| **Epic** | Epic 13: Workstation UI |
| **Title** | Notification System |
| **Priority** | Medium |
| **Story Points** | 3 |
| **Status** | Ready for Development |

## User Story

**As a** user  
**I want** a comprehensive notification system  
**So that** I receive timely alerts about important events

## Acceptance Criteria

### AC1: Toast Notifications
- **Given** an event occurs
- **When** a notification is triggered
- **Then** a toast appears with:
  - Icon and severity level
  - Title and message
  - Auto-dismiss timer
  - Manual dismiss option

### AC2: Notification Types
- **Given** different event types
- **When** notifications display
- **Then** they show appropriate styling:
  - Success (order filled)
  - Error (order rejected)
  - Warning (limit approaching)
  - Info (system message)

### AC3: Sound Alerts
- **Given** sound is enabled
- **When** a notification triggers
- **Then**:
  - Play appropriate sound
  - Respect volume settings
  - Allow per-type sound config

### AC4: Notification Center
- **Given** the notification icon
- **When** I click to open
- **Then** I see:
  - History of notifications
  - Filter by type
  - Mark as read/clear all

### AC5: Desktop Notifications
- **Given** browser permissions granted
- **When** the app is backgrounded
- **Then** desktop notifications show for critical alerts

## Technical Specification

### Notification Store

```typescript
// src/shared/stores/notification.store.ts
import { create } from 'zustand';

type NotificationType = 'success' | 'error' | 'warning' | 'info';

interface Notification {
  id: string;
  type: NotificationType;
  title: string;
  message?: string;
  timestamp: Date;
  read: boolean;
  persistent?: boolean;
  action?: {
    label: string;
    onClick: () => void;
  };
}

interface NotificationState {
  notifications: Notification[];
  toasts: Notification[];
  unreadCount: number;

  // Actions
  addNotification: (notification: Omit<Notification, 'id' | 'timestamp' | 'read'>) => void;
  removeToast: (id: string) => void;
  markAsRead: (id: string) => void;
  markAllAsRead: () => void;
  clearAll: () => void;
}

export const useNotificationStore = create<NotificationState>((set, get) => ({
  notifications: [],
  toasts: [],
  unreadCount: 0,

  addNotification: (notification) => {
    const id = crypto.randomUUID();
    const newNotification: Notification = {
      ...notification,
      id,
      timestamp: new Date(),
      read: false,
    };

    set((state) => ({
      notifications: [newNotification, ...state.notifications].slice(0, 100),
      toasts: notification.persistent 
        ? state.toasts 
        : [...state.toasts, newNotification],
      unreadCount: state.unreadCount + 1,
    }));

    // Play sound
    playNotificationSound(notification.type);

    // Auto-remove toast after delay
    if (!notification.persistent) {
      const delay = notification.type === 'error' ? 8000 : 5000;
      setTimeout(() => {
        get().removeToast(id);
      }, delay);
    }

    // Desktop notification for critical events
    if (notification.type === 'error' || notification.type === 'warning') {
      showDesktopNotification(newNotification);
    }
  },

  removeToast: (id) => {
    set((state) => ({
      toasts: state.toasts.filter(t => t.id !== id),
    }));
  },

  markAsRead: (id) => {
    set((state) => ({
      notifications: state.notifications.map(n =>
        n.id === id ? { ...n, read: true } : n
      ),
      unreadCount: Math.max(0, state.unreadCount - 1),
    }));
  },

  markAllAsRead: () => {
    set((state) => ({
      notifications: state.notifications.map(n => ({ ...n, read: true })),
      unreadCount: 0,
    }));
  },

  clearAll: () => {
    set({ notifications: [], unreadCount: 0 });
  },
}));

// Sound effects
const SOUNDS: Record<NotificationType, string> = {
  success: '/sounds/success.mp3',
  error: '/sounds/error.mp3',
  warning: '/sounds/warning.mp3',
  info: '/sounds/info.mp3',
};

function playNotificationSound(type: NotificationType) {
  const { soundEnabled, soundVolume } = useThemeStore.getState();
  if (!soundEnabled) return;

  const audio = new Audio(SOUNDS[type]);
  audio.volume = soundVolume / 100;
  audio.play().catch(() => {/* Ignore autoplay errors */});
}

async function showDesktopNotification(notification: Notification) {
  if (!('Notification' in window)) return;
  if (document.hasFocus()) return;

  if (Notification.permission === 'default') {
    await Notification.requestPermission();
  }

  if (Notification.permission === 'granted') {
    new Notification(notification.title, {
      body: notification.message,
      icon: '/logo.png',
      tag: notification.id,
    });
  }
}
```

### Toast Container Component

```typescript
// src/shared/components/ToastContainer.tsx
import React from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { useNotificationStore } from '../stores/notification.store';
import { Toast } from './Toast';

export const ToastContainer: React.FC = () => {
  const { toasts, removeToast } = useNotificationStore();

  return (
    <div className="toast-container">
      <AnimatePresence mode="popLayout">
        {toasts.map((toast) => (
          <motion.div
            key={toast.id}
            layout
            initial={{ opacity: 0, y: 50, scale: 0.9 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, x: 100, scale: 0.9 }}
            transition={{ duration: 0.2 }}
          >
            <Toast
              notification={toast}
              onDismiss={() => removeToast(toast.id)}
            />
          </motion.div>
        ))}
      </AnimatePresence>
    </div>
  );
};
```

### Toast Component

```typescript
// src/shared/components/Toast.tsx
import React, { useEffect, useState } from 'react';

interface ToastProps {
  notification: {
    id: string;
    type: 'success' | 'error' | 'warning' | 'info';
    title: string;
    message?: string;
    action?: {
      label: string;
      onClick: () => void;
    };
  };
  onDismiss: () => void;
  duration?: number;
}

const ICONS = {
  success: '✓',
  error: '✕',
  warning: '⚠',
  info: 'ℹ',
};

export const Toast: React.FC<ToastProps> = ({
  notification,
  onDismiss,
  duration = 5000,
}) => {
  const [progress, setProgress] = useState(100);

  useEffect(() => {
    if (notification.type === 'error') return; // No auto-dismiss for errors

    const interval = setInterval(() => {
      setProgress((prev) => {
        const next = prev - (100 / (duration / 100));
        return next <= 0 ? 0 : next;
      });
    }, 100);

    return () => clearInterval(interval);
  }, [duration, notification.type]);

  return (
    <div className={`toast toast-${notification.type}`}>
      <div className="toast-icon">
        {ICONS[notification.type]}
      </div>

      <div className="toast-content">
        <div className="toast-title">{notification.title}</div>
        {notification.message && (
          <div className="toast-message">{notification.message}</div>
        )}
        {notification.action && (
          <button
            className="toast-action"
            onClick={() => {
              notification.action?.onClick();
              onDismiss();
            }}
          >
            {notification.action.label}
          </button>
        )}
      </div>

      <button className="toast-dismiss" onClick={onDismiss}>
        ×
      </button>

      {notification.type !== 'error' && (
        <div className="toast-progress">
          <div
            className="toast-progress-bar"
            style={{ width: `${progress}%` }}
          />
        </div>
      )}
    </div>
  );
};
```

### Notification Center

```typescript
// src/shared/components/NotificationCenter.tsx
import React, { useState } from 'react';
import * as Popover from '@radix-ui/react-popover';
import { useNotificationStore } from '../stores/notification.store';
import { formatDistanceToNow } from 'date-fns';

export const NotificationCenter: React.FC = () => {
  const {
    notifications,
    unreadCount,
    markAsRead,
    markAllAsRead,
    clearAll,
  } = useNotificationStore();

  const [filter, setFilter] = useState<string | null>(null);

  const filteredNotifications = filter
    ? notifications.filter(n => n.type === filter)
    : notifications;

  return (
    <Popover.Root>
      <Popover.Trigger asChild>
        <button className="notification-trigger">
          <span className="notification-icon">🔔</span>
          {unreadCount > 0 && (
            <span className="notification-badge">{unreadCount}</span>
          )}
        </button>
      </Popover.Trigger>

      <Popover.Portal>
        <Popover.Content className="notification-center" sideOffset={8}>
          <div className="notification-header">
            <h3>Notifications</h3>
            <div className="header-actions">
              <button onClick={markAllAsRead}>Mark all read</button>
              <button onClick={clearAll}>Clear all</button>
            </div>
          </div>

          <div className="notification-filters">
            <button
              className={filter === null ? 'active' : ''}
              onClick={() => setFilter(null)}
            >
              All
            </button>
            <button
              className={filter === 'error' ? 'active' : ''}
              onClick={() => setFilter('error')}
            >
              Errors
            </button>
            <button
              className={filter === 'warning' ? 'active' : ''}
              onClick={() => setFilter('warning')}
            >
              Warnings
            </button>
            <button
              className={filter === 'success' ? 'active' : ''}
              onClick={() => setFilter('success')}
            >
              Success
            </button>
          </div>

          <div className="notification-list">
            {filteredNotifications.length === 0 ? (
              <div className="empty-state">No notifications</div>
            ) : (
              filteredNotifications.map((notification) => (
                <div
                  key={notification.id}
                  className={`notification-item ${notification.read ? 'read' : 'unread'}`}
                  onClick={() => markAsRead(notification.id)}
                >
                  <div className={`notification-icon ${notification.type}`}>
                    {notification.type === 'success' && '✓'}
                    {notification.type === 'error' && '✕'}
                    {notification.type === 'warning' && '⚠'}
                    {notification.type === 'info' && 'ℹ'}
                  </div>
                  <div className="notification-body">
                    <div className="notification-title">{notification.title}</div>
                    {notification.message && (
                      <div className="notification-message">
                        {notification.message}
                      </div>
                    )}
                    <div className="notification-time">
                      {formatDistanceToNow(notification.timestamp, {
                        addSuffix: true,
                      })}
                    </div>
                  </div>
                </div>
              ))
            )}
          </div>
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
};
```

### Notification Hook for Easy Usage

```typescript
// src/shared/hooks/useNotification.ts
import { useCallback } from 'react';
import { useNotificationStore } from '../stores/notification.store';

export function useNotification() {
  const addNotification = useNotificationStore(state => state.addNotification);

  const success = useCallback((title: string, message?: string) => {
    addNotification({ type: 'success', title, message });
  }, [addNotification]);

  const error = useCallback((title: string, message?: string) => {
    addNotification({ type: 'error', title, message, persistent: true });
  }, [addNotification]);

  const warning = useCallback((title: string, message?: string) => {
    addNotification({ type: 'warning', title, message });
  }, [addNotification]);

  const info = useCallback((title: string, message?: string) => {
    addNotification({ type: 'info', title, message });
  }, [addNotification]);

  const orderFilled = useCallback((orderId: string, details: string) => {
    addNotification({
      type: 'success',
      title: 'Order Filled',
      message: details,
      action: {
        label: 'View',
        onClick: () => {
          // Navigate to trade blotter
        },
      },
    });
  }, [addNotification]);

  const orderRejected = useCallback((orderId: string, reason: string) => {
    addNotification({
      type: 'error',
      title: 'Order Rejected',
      message: reason,
      persistent: true,
    });
  }, [addNotification]);

  return {
    success,
    error,
    warning,
    info,
    orderFilled,
    orderRejected,
  };
}
```

### CSS Styles

```css
/* src/shared/styles/notifications.css */

/* Toast Container */
.toast-container {
  position: fixed;
  bottom: 24px;
  right: 24px;
  display: flex;
  flex-direction: column-reverse;
  gap: 12px;
  z-index: 9999;
  max-width: 400px;
}

/* Toast */
.toast {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 16px;
  background: var(--bg-primary);
  border-radius: 8px;
  border-left: 4px solid;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
  position: relative;
  overflow: hidden;
}

.toast-success {
  border-left-color: var(--success-color);
}

.toast-error {
  border-left-color: var(--error-color);
}

.toast-warning {
  border-left-color: var(--warning-color);
}

.toast-info {
  border-left-color: var(--info-color);
}

.toast-icon {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: bold;
  flex-shrink: 0;
}

.toast-success .toast-icon {
  background: var(--success-color);
  color: white;
}

.toast-error .toast-icon {
  background: var(--error-color);
  color: white;
}

.toast-warning .toast-icon {
  background: var(--warning-color);
  color: black;
}

.toast-info .toast-icon {
  background: var(--info-color);
  color: white;
}

.toast-content {
  flex: 1;
  min-width: 0;
}

.toast-title {
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 4px;
}

.toast-message {
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.4;
}

.toast-action {
  margin-top: 8px;
  padding: 4px 12px;
  background: var(--bg-hover);
  border: none;
  border-radius: 4px;
  color: var(--accent-color);
  cursor: pointer;
  font-size: 12px;
}

.toast-dismiss {
  position: absolute;
  top: 8px;
  right: 8px;
  width: 20px;
  height: 20px;
  background: transparent;
  border: none;
  color: var(--text-tertiary);
  cursor: pointer;
  font-size: 16px;
}

.toast-dismiss:hover {
  color: var(--text-primary);
}

.toast-progress {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  height: 3px;
  background: var(--bg-tertiary);
}

.toast-progress-bar {
  height: 100%;
  background: var(--accent-color);
  transition: width 0.1s linear;
}

/* Notification Center */
.notification-trigger {
  position: relative;
  padding: 8px;
  background: transparent;
  border: none;
  cursor: pointer;
}

.notification-badge {
  position: absolute;
  top: 0;
  right: 0;
  min-width: 18px;
  height: 18px;
  background: var(--error-color);
  border-radius: 9px;
  font-size: 11px;
  font-weight: 600;
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
}

.notification-center {
  width: 380px;
  max-height: 500px;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.3);
}

.notification-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border-color);
}

.notification-header h3 {
  font-size: 14px;
  font-weight: 600;
}

.header-actions {
  display: flex;
  gap: 12px;
}

.header-actions button {
  background: none;
  border: none;
  color: var(--accent-color);
  cursor: pointer;
  font-size: 12px;
}

.notification-filters {
  display: flex;
  gap: 4px;
  padding: 8px 16px;
  border-bottom: 1px solid var(--border-color);
}

.notification-filters button {
  padding: 4px 10px;
  background: transparent;
  border: none;
  border-radius: 4px;
  color: var(--text-secondary);
  font-size: 12px;
  cursor: pointer;
}

.notification-filters button.active {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.notification-list {
  max-height: 350px;
  overflow-y: auto;
}

.notification-item {
  display: flex;
  gap: 12px;
  padding: 12px 16px;
  cursor: pointer;
  transition: background 0.15s;
}

.notification-item:hover {
  background: var(--bg-hover);
}

.notification-item.unread {
  background: rgba(41, 98, 255, 0.05);
}

.notification-item .notification-icon {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  flex-shrink: 0;
}

.notification-item .notification-icon.success {
  background: rgba(76, 175, 80, 0.2);
  color: var(--success-color);
}

.notification-item .notification-icon.error {
  background: rgba(244, 67, 54, 0.2);
  color: var(--error-color);
}

.notification-body {
  flex: 1;
  min-width: 0;
}

.notification-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-primary);
}

.notification-message {
  font-size: 12px;
  color: var(--text-secondary);
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.notification-time {
  font-size: 11px;
  color: var(--text-tertiary);
  margin-top: 4px;
}

.empty-state {
  padding: 32px;
  text-align: center;
  color: var(--text-tertiary);
}
```

## Definition of Done

- [ ] Toast notification component
- [ ] Notification types with distinct styling
- [ ] Auto-dismiss with progress bar
- [ ] Sound alerts (configurable)
- [ ] Notification center with history
- [ ] Filter by type
- [ ] Mark as read functionality
- [ ] Desktop notifications for critical alerts
- [ ] Framer Motion animations
- [ ] Unit tests for store
- [ ] Integration tests

## Test Cases

```typescript
describe('NotificationStore', () => {
  it('should add notification and increment unread count', () => {
    const { result } = renderHook(() => useNotificationStore());
    
    act(() => {
      result.current.addNotification({
        type: 'success',
        title: 'Test',
      });
    });

    expect(result.current.notifications.length).toBe(1);
    expect(result.current.unreadCount).toBe(1);
  });

  it('should auto-remove toast after delay', async () => {
    const { result } = renderHook(() => useNotificationStore());
    
    act(() => {
      result.current.addNotification({
        type: 'info',
        title: 'Test',
      });
    });

    expect(result.current.toasts.length).toBe(1);

    await waitFor(() => {
      expect(result.current.toasts.length).toBe(0);
    }, { timeout: 6000 });
  });
});

describe('Toast', () => {
  it('should display title and message', () => {
    render(
      <Toast
        notification={{ id: '1', type: 'success', title: 'Success!', message: 'Done' }}
        onDismiss={() => {}}
      />
    );

    expect(screen.getByText('Success!')).toBeInTheDocument();
    expect(screen.getByText('Done')).toBeInTheDocument();
  });

  it('should call onDismiss when dismiss clicked', () => {
    const onDismiss = jest.fn();
    render(
      <Toast
        notification={{ id: '1', type: 'success', title: 'Test' }}
        onDismiss={onDismiss}
      />
    );

    fireEvent.click(screen.getByText('×'));
    expect(onDismiss).toHaveBeenCalled();
  });
});
```
