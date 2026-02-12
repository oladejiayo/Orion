# US-13-10: Theme & Preferences

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-13-10 |
| **Epic** | Epic 13: Workstation UI |
| **Title** | Theme & Preferences |
| **Priority** | Medium |
| **Story Points** | 5 |
| **Status** | Ready for Development |

## User Story

**As a** user  
**I want** to customize the theme and application preferences  
**So that** I can personalize my trading workstation

## Acceptance Criteria

### AC1: Theme Selection
- **Given** the preferences dialog
- **When** I select a theme
- **Then** I can choose:
  - Dark theme (default)
  - Light theme
  - High contrast theme
  - System preference

### AC2: Color Customization
- **Given** theme settings
- **When** I customize colors
- **Then** I can modify:
  - Accent color
  - Buy/Sell colors
  - P&L positive/negative colors

### AC3: Display Settings
- **Given** display preferences
- **When** I adjust settings
- **Then** I can configure:
  - Font size (small/medium/large)
  - Number formatting (locale)
  - Date/time format
  - Decimal precision

### AC4: Preferences Persistence
- **Given** saved preferences
- **When** I reload the application
- **Then** my preferences are restored

### AC5: Preferences Sync
- **Given** multiple devices
- **When** preferences are changed
- **Then** they sync across devices (optional)

## Technical Specification

### Theme Store

```typescript
// src/shared/stores/theme.store.ts
import { create } from 'zustand';
import { persist } from 'zustand/middleware';

type ThemeMode = 'dark' | 'light' | 'high-contrast' | 'system';
type FontSize = 'small' | 'medium' | 'large';
type DateFormat = 'MM/DD/YYYY' | 'DD/MM/YYYY' | 'YYYY-MM-DD';
type TimeFormat = '12h' | '24h';

interface ThemeColors {
  accent: string;
  buyColor: string;
  sellColor: string;
  positiveColor: string;
  negativeColor: string;
}

interface ThemeState {
  // Theme
  mode: ThemeMode;
  resolvedMode: 'dark' | 'light' | 'high-contrast';
  colors: ThemeColors;

  // Display
  fontSize: FontSize;
  locale: string;
  dateFormat: DateFormat;
  timeFormat: TimeFormat;
  decimalPrecision: number;

  // Sound
  soundEnabled: boolean;
  soundVolume: number;

  // Actions
  setMode: (mode: ThemeMode) => void;
  setColor: (key: keyof ThemeColors, value: string) => void;
  setFontSize: (size: FontSize) => void;
  setLocale: (locale: string) => void;
  setDateFormat: (format: DateFormat) => void;
  setTimeFormat: (format: TimeFormat) => void;
  setDecimalPrecision: (precision: number) => void;
  setSoundEnabled: (enabled: boolean) => void;
  setSoundVolume: (volume: number) => void;
  resetToDefaults: () => void;
}

const DEFAULT_COLORS: ThemeColors = {
  accent: '#2962FF',
  buyColor: '#26a69a',
  sellColor: '#ef5350',
  positiveColor: '#4CAF50',
  negativeColor: '#F44336',
};

const DEFAULT_STATE = {
  mode: 'dark' as ThemeMode,
  resolvedMode: 'dark' as const,
  colors: DEFAULT_COLORS,
  fontSize: 'medium' as FontSize,
  locale: 'en-US',
  dateFormat: 'MM/DD/YYYY' as DateFormat,
  timeFormat: '24h' as TimeFormat,
  decimalPrecision: 5,
  soundEnabled: true,
  soundVolume: 50,
};

export const useThemeStore = create<ThemeState>()(
  persist(
    (set, get) => ({
      ...DEFAULT_STATE,

      setMode: (mode) => {
        const resolvedMode = resolveThemeMode(mode);
        set({ mode, resolvedMode });
        applyTheme(resolvedMode, get().colors);
      },

      setColor: (key, value) => {
        set((state) => ({
          colors: { ...state.colors, [key]: value },
        }));
        applyTheme(get().resolvedMode, get().colors);
      },

      setFontSize: (fontSize) => {
        set({ fontSize });
        applyFontSize(fontSize);
      },

      setLocale: (locale) => set({ locale }),
      setDateFormat: (dateFormat) => set({ dateFormat }),
      setTimeFormat: (timeFormat) => set({ timeFormat }),
      setDecimalPrecision: (decimalPrecision) => set({ decimalPrecision }),
      setSoundEnabled: (soundEnabled) => set({ soundEnabled }),
      setSoundVolume: (soundVolume) => set({ soundVolume }),

      resetToDefaults: () => {
        set(DEFAULT_STATE);
        applyTheme(DEFAULT_STATE.resolvedMode, DEFAULT_STATE.colors);
        applyFontSize(DEFAULT_STATE.fontSize);
      },
    }),
    {
      name: 'orion-theme',
      onRehydrateStorage: () => (state) => {
        if (state) {
          applyTheme(state.resolvedMode, state.colors);
          applyFontSize(state.fontSize);
        }
      },
    }
  )
);

function resolveThemeMode(mode: ThemeMode): 'dark' | 'light' | 'high-contrast' {
  if (mode === 'system') {
    return window.matchMedia('(prefers-color-scheme: dark)').matches
      ? 'dark'
      : 'light';
  }
  return mode;
}

function applyTheme(mode: 'dark' | 'light' | 'high-contrast', colors: ThemeColors) {
  const root = document.documentElement;
  
  // Set theme class
  root.classList.remove('theme-dark', 'theme-light', 'theme-high-contrast');
  root.classList.add(`theme-${mode}`);

  // Apply custom colors as CSS variables
  root.style.setProperty('--accent-color', colors.accent);
  root.style.setProperty('--buy-color', colors.buyColor);
  root.style.setProperty('--sell-color', colors.sellColor);
  root.style.setProperty('--positive-color', colors.positiveColor);
  root.style.setProperty('--negative-color', colors.negativeColor);
}

function applyFontSize(size: FontSize) {
  const root = document.documentElement;
  const sizes = {
    small: '12px',
    medium: '14px',
    large: '16px',
  };
  root.style.setProperty('--base-font-size', sizes[size]);
}
```

### Theme Provider

```typescript
// src/shared/providers/ThemeProvider.tsx
import React, { useEffect } from 'react';
import { useThemeStore } from '../stores/theme.store';

export const ThemeProvider: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => {
  const { mode, setMode } = useThemeStore();

  // Listen for system theme changes
  useEffect(() => {
    if (mode !== 'system') return;

    const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
    
    const handleChange = () => {
      setMode('system'); // Re-resolve system mode
    };

    mediaQuery.addEventListener('change', handleChange);
    return () => mediaQuery.removeEventListener('change', handleChange);
  }, [mode, setMode]);

  return <>{children}</>;
};
```

### Preferences Dialog

```typescript
// src/features/settings/components/PreferencesDialog.tsx
import React from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import * as Tabs from '@radix-ui/react-tabs';
import { useThemeStore } from '../../../shared/stores/theme.store';
import { ThemeSettings } from './ThemeSettings';
import { DisplaySettings } from './DisplaySettings';
import { SoundSettings } from './SoundSettings';
import { ShortcutSettings } from './ShortcutSettings';

interface PreferencesDialogProps {
  isOpen: boolean;
  onClose: () => void;
}

export const PreferencesDialog: React.FC<PreferencesDialogProps> = ({
  isOpen,
  onClose,
}) => {
  const { resetToDefaults } = useThemeStore();

  return (
    <Dialog.Root open={isOpen} onOpenChange={(open) => !open && onClose()}>
      <Dialog.Portal>
        <Dialog.Overlay className="dialog-overlay" />
        <Dialog.Content className="preferences-dialog">
          <Dialog.Title className="dialog-title">Preferences</Dialog.Title>

          <Tabs.Root defaultValue="theme" className="preferences-tabs">
            <Tabs.List className="tabs-list">
              <Tabs.Trigger value="theme" className="tab-trigger">
                Theme
              </Tabs.Trigger>
              <Tabs.Trigger value="display" className="tab-trigger">
                Display
              </Tabs.Trigger>
              <Tabs.Trigger value="sound" className="tab-trigger">
                Sound
              </Tabs.Trigger>
              <Tabs.Trigger value="shortcuts" className="tab-trigger">
                Shortcuts
              </Tabs.Trigger>
            </Tabs.List>

            <Tabs.Content value="theme" className="tab-content">
              <ThemeSettings />
            </Tabs.Content>

            <Tabs.Content value="display" className="tab-content">
              <DisplaySettings />
            </Tabs.Content>

            <Tabs.Content value="sound" className="tab-content">
              <SoundSettings />
            </Tabs.Content>

            <Tabs.Content value="shortcuts" className="tab-content">
              <ShortcutSettings />
            </Tabs.Content>
          </Tabs.Root>

          <div className="dialog-footer">
            <button
              className="btn-secondary"
              onClick={resetToDefaults}
            >
              Reset to Defaults
            </button>
            <button className="btn-primary" onClick={onClose}>
              Done
            </button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
};
```

### Theme Settings Tab

```typescript
// src/features/settings/components/ThemeSettings.tsx
import React from 'react';
import * as RadioGroup from '@radix-ui/react-radio-group';
import { useThemeStore } from '../../../shared/stores/theme.store';
import { ColorPicker } from './ColorPicker';

export const ThemeSettings: React.FC = () => {
  const {
    mode,
    colors,
    setMode,
    setColor,
  } = useThemeStore();

  return (
    <div className="settings-section">
      <div className="setting-group">
        <h3 className="setting-group-title">Theme Mode</h3>
        <RadioGroup.Root
          value={mode}
          onValueChange={(value) => setMode(value as any)}
          className="theme-radio-group"
        >
          <div className="radio-item">
            <RadioGroup.Item value="dark" className="radio-button">
              <RadioGroup.Indicator className="radio-indicator" />
            </RadioGroup.Item>
            <label>Dark</label>
          </div>
          <div className="radio-item">
            <RadioGroup.Item value="light" className="radio-button">
              <RadioGroup.Indicator className="radio-indicator" />
            </RadioGroup.Item>
            <label>Light</label>
          </div>
          <div className="radio-item">
            <RadioGroup.Item value="high-contrast" className="radio-button">
              <RadioGroup.Indicator className="radio-indicator" />
            </RadioGroup.Item>
            <label>High Contrast</label>
          </div>
          <div className="radio-item">
            <RadioGroup.Item value="system" className="radio-button">
              <RadioGroup.Indicator className="radio-indicator" />
            </RadioGroup.Item>
            <label>System</label>
          </div>
        </RadioGroup.Root>
      </div>

      <div className="setting-group">
        <h3 className="setting-group-title">Colors</h3>
        
        <div className="color-setting">
          <label>Accent Color</label>
          <ColorPicker
            color={colors.accent}
            onChange={(color) => setColor('accent', color)}
          />
        </div>

        <div className="color-setting">
          <label>Buy Color</label>
          <ColorPicker
            color={colors.buyColor}
            onChange={(color) => setColor('buyColor', color)}
          />
        </div>

        <div className="color-setting">
          <label>Sell Color</label>
          <ColorPicker
            color={colors.sellColor}
            onChange={(color) => setColor('sellColor', color)}
          />
        </div>

        <div className="color-setting">
          <label>Positive P&L</label>
          <ColorPicker
            color={colors.positiveColor}
            onChange={(color) => setColor('positiveColor', color)}
          />
        </div>

        <div className="color-setting">
          <label>Negative P&L</label>
          <ColorPicker
            color={colors.negativeColor}
            onChange={(color) => setColor('negativeColor', color)}
          />
        </div>
      </div>
    </div>
  );
};
```

### Display Settings Tab

```typescript
// src/features/settings/components/DisplaySettings.tsx
import React from 'react';
import * as Select from '@radix-ui/react-select';
import { useThemeStore } from '../../../shared/stores/theme.store';

export const DisplaySettings: React.FC = () => {
  const {
    fontSize,
    locale,
    dateFormat,
    timeFormat,
    decimalPrecision,
    setFontSize,
    setLocale,
    setDateFormat,
    setTimeFormat,
    setDecimalPrecision,
  } = useThemeStore();

  return (
    <div className="settings-section">
      <div className="setting-group">
        <h3 className="setting-group-title">Text Size</h3>
        <div className="toggle-buttons">
          {(['small', 'medium', 'large'] as const).map((size) => (
            <button
              key={size}
              className={`toggle-btn ${fontSize === size ? 'active' : ''}`}
              onClick={() => setFontSize(size)}
            >
              {size.charAt(0).toUpperCase() + size.slice(1)}
            </button>
          ))}
        </div>
      </div>

      <div className="setting-group">
        <h3 className="setting-group-title">Number Format</h3>
        <Select.Root value={locale} onValueChange={setLocale}>
          <Select.Trigger className="select-trigger">
            <Select.Value />
          </Select.Trigger>
          <Select.Portal>
            <Select.Content className="select-content">
              <Select.Item value="en-US" className="select-item">
                <Select.ItemText>English (US) - 1,234.56</Select.ItemText>
              </Select.Item>
              <Select.Item value="en-GB" className="select-item">
                <Select.ItemText>English (UK) - 1,234.56</Select.ItemText>
              </Select.Item>
              <Select.Item value="de-DE" className="select-item">
                <Select.ItemText>German - 1.234,56</Select.ItemText>
              </Select.Item>
              <Select.Item value="fr-FR" className="select-item">
                <Select.ItemText>French - 1 234,56</Select.ItemText>
              </Select.Item>
            </Select.Content>
          </Select.Portal>
        </Select.Root>
      </div>

      <div className="setting-group">
        <h3 className="setting-group-title">Date Format</h3>
        <Select.Root value={dateFormat} onValueChange={(v) => setDateFormat(v as any)}>
          <Select.Trigger className="select-trigger">
            <Select.Value />
          </Select.Trigger>
          <Select.Portal>
            <Select.Content className="select-content">
              <Select.Item value="MM/DD/YYYY" className="select-item">
                <Select.ItemText>MM/DD/YYYY</Select.ItemText>
              </Select.Item>
              <Select.Item value="DD/MM/YYYY" className="select-item">
                <Select.ItemText>DD/MM/YYYY</Select.ItemText>
              </Select.Item>
              <Select.Item value="YYYY-MM-DD" className="select-item">
                <Select.ItemText>YYYY-MM-DD</Select.ItemText>
              </Select.Item>
            </Select.Content>
          </Select.Portal>
        </Select.Root>
      </div>

      <div className="setting-group">
        <h3 className="setting-group-title">Time Format</h3>
        <div className="toggle-buttons">
          <button
            className={`toggle-btn ${timeFormat === '12h' ? 'active' : ''}`}
            onClick={() => setTimeFormat('12h')}
          >
            12 Hour
          </button>
          <button
            className={`toggle-btn ${timeFormat === '24h' ? 'active' : ''}`}
            onClick={() => setTimeFormat('24h')}
          >
            24 Hour
          </button>
        </div>
      </div>

      <div className="setting-group">
        <h3 className="setting-group-title">Price Decimal Places</h3>
        <Select.Root 
          value={String(decimalPrecision)} 
          onValueChange={(v) => setDecimalPrecision(parseInt(v))}
        >
          <Select.Trigger className="select-trigger">
            <Select.Value />
          </Select.Trigger>
          <Select.Portal>
            <Select.Content className="select-content">
              {[2, 3, 4, 5, 6].map((n) => (
                <Select.Item key={n} value={String(n)} className="select-item">
                  <Select.ItemText>{n} decimals</Select.ItemText>
                </Select.Item>
              ))}
            </Select.Content>
          </Select.Portal>
        </Select.Root>
      </div>
    </div>
  );
};
```

### CSS Variables & Themes

```css
/* src/shared/styles/themes.css */

:root {
  /* Base sizing */
  --base-font-size: 14px;
  
  /* Spacing */
  --spacing-xs: 4px;
  --spacing-sm: 8px;
  --spacing-md: 16px;
  --spacing-lg: 24px;
  --spacing-xl: 32px;
}

/* Dark Theme (default) */
.theme-dark {
  --bg-primary: #1a1a2e;
  --bg-secondary: #16162a;
  --bg-tertiary: #0f0f1a;
  --bg-hover: #252541;
  
  --text-primary: #ffffff;
  --text-secondary: #b8b8cc;
  --text-tertiary: #6e6e8a;
  
  --border-color: #2d2d4a;
  
  --success-color: var(--positive-color, #4CAF50);
  --error-color: var(--negative-color, #F44336);
  --warning-color: #FF9800;
  --info-color: #2196F3;
}

/* Light Theme */
.theme-light {
  --bg-primary: #ffffff;
  --bg-secondary: #f5f5f5;
  --bg-tertiary: #e8e8e8;
  --bg-hover: #eeeeee;
  
  --text-primary: #1a1a1a;
  --text-secondary: #555555;
  --text-tertiary: #888888;
  
  --border-color: #dddddd;
  
  --success-color: var(--positive-color, #2E7D32);
  --error-color: var(--negative-color, #C62828);
  --warning-color: #F57C00;
  --info-color: #1565C0;
}

/* High Contrast Theme */
.theme-high-contrast {
  --bg-primary: #000000;
  --bg-secondary: #0a0a0a;
  --bg-tertiary: #141414;
  --bg-hover: #1f1f1f;
  
  --text-primary: #ffffff;
  --text-secondary: #ffffff;
  --text-tertiary: #cccccc;
  
  --border-color: #ffffff;
  
  --success-color: #00ff00;
  --error-color: #ff0000;
  --warning-color: #ffff00;
  --info-color: #00ffff;
}

/* Preferences Dialog */
.preferences-dialog {
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 700px;
  max-height: 80vh;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  overflow: hidden;
  z-index: 1000;
}

.dialog-title {
  padding: 16px 20px;
  font-size: 18px;
  font-weight: 600;
  border-bottom: 1px solid var(--border-color);
}

.preferences-tabs {
  display: flex;
  flex-direction: column;
  height: calc(80vh - 140px);
}

.tabs-list {
  display: flex;
  border-bottom: 1px solid var(--border-color);
}

.tab-trigger {
  padding: 12px 20px;
  background: transparent;
  border: none;
  border-bottom: 2px solid transparent;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.15s;
}

.tab-trigger:hover {
  color: var(--text-primary);
}

.tab-trigger[data-state="active"] {
  color: var(--accent-color);
  border-bottom-color: var(--accent-color);
}

.tab-content {
  flex: 1;
  padding: 20px;
  overflow-y: auto;
}

.setting-group {
  margin-bottom: 24px;
}

.setting-group-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
  margin-bottom: 12px;
}

.color-setting {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
}

.toggle-buttons {
  display: flex;
  gap: 8px;
}

.toggle-btn {
  padding: 8px 16px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 4px;
  color: var(--text-secondary);
  cursor: pointer;
}

.toggle-btn.active {
  background: var(--accent-color);
  border-color: var(--accent-color);
  color: white;
}

.dialog-footer {
  display: flex;
  justify-content: space-between;
  padding: 16px 20px;
  border-top: 1px solid var(--border-color);
}
```

## Definition of Done

- [ ] Dark/Light/High-contrast themes
- [ ] System theme detection
- [ ] Color customization
- [ ] Font size settings
- [ ] Number/date/time formatting
- [ ] Sound preferences
- [ ] Theme persistence in localStorage
- [ ] CSS variables for all theme tokens
- [ ] Unit tests for theme store
- [ ] Visual regression tests

## Test Cases

```typescript
describe('ThemeStore', () => {
  it('should apply dark theme by default', () => {
    const { result } = renderHook(() => useThemeStore());
    
    expect(result.current.resolvedMode).toBe('dark');
    expect(document.documentElement.classList.contains('theme-dark')).toBe(true);
  });

  it('should resolve system theme correctly', () => {
    window.matchMedia = jest.fn().mockReturnValue({
      matches: true, // Dark mode
      addEventListener: jest.fn(),
    });

    const { result } = renderHook(() => useThemeStore());
    act(() => result.current.setMode('system'));
    
    expect(result.current.resolvedMode).toBe('dark');
  });

  it('should apply custom colors', () => {
    const { result } = renderHook(() => useThemeStore());
    
    act(() => result.current.setColor('accent', '#FF5722'));
    
    expect(document.documentElement.style.getPropertyValue('--accent-color'))
      .toBe('#FF5722');
  });
});

describe('PreferencesDialog', () => {
  it('should render all tabs', () => {
    render(<PreferencesDialog isOpen={true} onClose={() => {}} />);
    
    expect(screen.getByText('Theme')).toBeInTheDocument();
    expect(screen.getByText('Display')).toBeInTheDocument();
    expect(screen.getByText('Sound')).toBeInTheDocument();
    expect(screen.getByText('Shortcuts')).toBeInTheDocument();
  });
});
```
