# US-17-04: End-to-End Testing

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-17-04 |
| **Epic** | Epic 17: Testing & Quality |
| **Title** | End-to-End Testing |
| **Priority** | High |
| **Story Points** | 8 |
| **Status** | Ready for Development |

## User Story

**As a** QA engineer  
**I want** automated end-to-end tests for critical user journeys  
**So that** we can validate the complete system before releases

## Acceptance Criteria

### AC1: Playwright Setup
- **Given** E2E test framework
- **When** tests execute
- **Then**:
  - Cross-browser testing
  - Visual regression
  - Video recording
  - Trace viewer

### AC2: Critical Path Tests
- **Given** core user journeys
- **When** tests run
- **Then**:
  - Login/authentication
  - Order submission
  - Position viewing
  - Portfolio management

### AC3: Test Environment
- **Given** E2E tests
- **When** environment configured
- **Then**:
  - Isolated test data
  - Mock external services
  - Deterministic state
  - Fast reset capability

### AC4: Visual Regression
- **Given** UI components
- **When** visual tests run
- **Then**:
  - Screenshot comparison
  - Diff highlighting
  - Baseline management
  - Cross-browser snapshots

### AC5: CI Integration
- **Given** E2E test suite
- **When** pipeline runs
- **Then**:
  - Parallel execution
  - Artifact collection
  - Report generation
  - Failure notifications

## Technical Specification

### Playwright Configuration

```typescript
// playwright.config.ts
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 4 : undefined,
  reporter: [
    ['html', { outputFolder: 'playwright-report' }],
    ['json', { outputFile: 'test-results/results.json' }],
    ['junit', { outputFile: 'test-results/junit.xml' }],
    process.env.CI ? ['github'] : ['list'],
  ],
  
  use: {
    baseURL: process.env.BASE_URL || 'http://localhost:3000',
    trace: 'on-first-retry',
    video: 'on-first-retry',
    screenshot: 'only-on-failure',
    
    // Authentication state
    storageState: 'e2e/.auth/user.json',
  },

  projects: [
    // Setup project for authentication
    {
      name: 'setup',
      testMatch: /.*\.setup\.ts/,
    },
    
    {
      name: 'chromium',
      use: { 
        ...devices['Desktop Chrome'],
        viewport: { width: 1920, height: 1080 },
      },
      dependencies: ['setup'],
    },
    {
      name: 'firefox',
      use: { 
        ...devices['Desktop Firefox'],
        viewport: { width: 1920, height: 1080 },
      },
      dependencies: ['setup'],
    },
    {
      name: 'webkit',
      use: { 
        ...devices['Desktop Safari'],
        viewport: { width: 1920, height: 1080 },
      },
      dependencies: ['setup'],
    },
    
    // Mobile viewports
    {
      name: 'Mobile Chrome',
      use: { ...devices['Pixel 5'] },
      dependencies: ['setup'],
    },
    {
      name: 'Mobile Safari',
      use: { ...devices['iPhone 13'] },
      dependencies: ['setup'],
    },
  ],

  // Web server configuration
  webServer: process.env.CI ? undefined : {
    command: 'npm run dev',
    url: 'http://localhost:3000',
    reuseExistingServer: !process.env.CI,
    timeout: 120 * 1000,
  },
});
```

### Authentication Setup

```typescript
// e2e/auth.setup.ts
import { test as setup, expect } from '@playwright/test';

const authFile = 'e2e/.auth/user.json';

setup('authenticate', async ({ page }) => {
  // Go to login page
  await page.goto('/login');
  
  // Fill credentials
  await page.getByLabel('Email').fill(process.env.TEST_USER_EMAIL!);
  await page.getByLabel('Password').fill(process.env.TEST_USER_PASSWORD!);
  
  // Submit login form
  await page.getByRole('button', { name: 'Sign in' }).click();
  
  // Wait for redirect to dashboard
  await page.waitForURL('/dashboard');
  
  // Verify authentication
  await expect(page.getByTestId('user-menu')).toBeVisible();
  
  // Save authentication state
  await page.context().storageState({ path: authFile });
});
```

### Page Object Model

```typescript
// e2e/pages/OrderPage.ts
import { Page, Locator, expect } from '@playwright/test';
import { Order, OrderType, OrderSide } from '@/types/order';

export class OrderPage {
  readonly page: Page;
  readonly symbolInput: Locator;
  readonly quantityInput: Locator;
  readonly priceInput: Locator;
  readonly orderTypeSelect: Locator;
  readonly sideToggle: Locator;
  readonly submitButton: Locator;
  readonly orderConfirmation: Locator;
  readonly errorMessage: Locator;

  constructor(page: Page) {
    this.page = page;
    this.symbolInput = page.getByLabel('Symbol');
    this.quantityInput = page.getByLabel('Quantity');
    this.priceInput = page.getByLabel('Price');
    this.orderTypeSelect = page.getByLabel('Order Type');
    this.sideToggle = page.getByTestId('side-toggle');
    this.submitButton = page.getByRole('button', { name: 'Submit Order' });
    this.orderConfirmation = page.getByTestId('order-confirmation');
    this.errorMessage = page.getByRole('alert');
  }

  async goto() {
    await this.page.goto('/trading/order');
    await expect(this.symbolInput).toBeVisible();
  }

  async selectSymbol(symbol: string) {
    await this.symbolInput.fill(symbol);
    await this.page.getByRole('option', { name: symbol }).click();
  }

  async setQuantity(quantity: number) {
    await this.quantityInput.fill(quantity.toString());
  }

  async setPrice(price: number) {
    await this.priceInput.fill(price.toString());
  }

  async selectOrderType(type: OrderType) {
    await this.orderTypeSelect.click();
    await this.page.getByRole('option', { name: type }).click();
  }

  async selectSide(side: OrderSide) {
    await this.sideToggle.getByRole('radio', { name: side }).click();
  }

  async submitOrder(): Promise<string> {
    await this.submitButton.click();
    await expect(this.orderConfirmation).toBeVisible({ timeout: 10000 });
    
    const orderId = await this.orderConfirmation.getByTestId('order-id').textContent();
    return orderId!;
  }

  async submitLimitOrder(params: {
    symbol: string;
    side: OrderSide;
    quantity: number;
    price: number;
  }): Promise<string> {
    await this.selectSymbol(params.symbol);
    await this.selectOrderType(OrderType.LIMIT);
    await this.selectSide(params.side);
    await this.setQuantity(params.quantity);
    await this.setPrice(params.price);
    return this.submitOrder();
  }

  async expectError(message: string) {
    await expect(this.errorMessage).toBeVisible();
    await expect(this.errorMessage).toContainText(message);
  }
}
```

### Critical Path Tests

```typescript
// e2e/tests/order-flow.spec.ts
import { test, expect } from '@playwright/test';
import { OrderPage } from '../pages/OrderPage';
import { PositionsPage } from '../pages/PositionsPage';
import { OrderSide, OrderType, OrderStatus } from '@/types/order';

test.describe('Order Flow - Critical Path', () => {
  let orderPage: OrderPage;
  let positionsPage: PositionsPage;

  test.beforeEach(async ({ page }) => {
    orderPage = new OrderPage(page);
    positionsPage = new PositionsPage(page);
  });

  test('should complete full buy order flow', async ({ page }) => {
    // Navigate to order page
    await orderPage.goto();

    // Submit a limit buy order
    const orderId = await orderPage.submitLimitOrder({
      symbol: 'AAPL',
      side: OrderSide.BUY,
      quantity: 100,
      price: 150.00,
    });

    expect(orderId).toBeTruthy();

    // Verify order appears in order list
    await page.goto('/orders');
    const orderRow = page.getByTestId(`order-${orderId}`);
    await expect(orderRow).toBeVisible();
    await expect(orderRow.getByText('PENDING')).toBeVisible();

    // Verify order details
    await orderRow.click();
    await expect(page.getByTestId('order-detail-panel')).toBeVisible();
    await expect(page.getByText('Symbol: AAPL')).toBeVisible();
    await expect(page.getByText('Quantity: 100')).toBeVisible();
  });

  test('should update position after order fill', async ({ page }) => {
    // Get initial position
    await positionsPage.goto();
    const initialQuantity = await positionsPage.getPositionQuantity('AAPL');

    // Submit buy order
    await orderPage.goto();
    await orderPage.submitLimitOrder({
      symbol: 'AAPL',
      side: OrderSide.BUY,
      quantity: 50,
      price: 150.00,
    });

    // Wait for order to fill (in test environment, this is simulated)
    await page.waitForTimeout(2000);

    // Verify position updated
    await positionsPage.goto();
    await positionsPage.waitForPositionUpdate('AAPL');
    
    const newQuantity = await positionsPage.getPositionQuantity('AAPL');
    expect(newQuantity).toBe(initialQuantity + 50);
  });

  test('should reject order with insufficient funds', async ({ page }) => {
    await orderPage.goto();

    // Try to submit order exceeding buying power
    await orderPage.selectSymbol('AAPL');
    await orderPage.selectOrderType(OrderType.MARKET);
    await orderPage.selectSide(OrderSide.BUY);
    await orderPage.setQuantity(1000000);
    
    await orderPage.submitButton.click();

    // Verify error message
    await orderPage.expectError('Insufficient buying power');
  });

  test('should cancel pending order', async ({ page }) => {
    // Submit order
    await orderPage.goto();
    const orderId = await orderPage.submitLimitOrder({
      symbol: 'AAPL',
      side: OrderSide.BUY,
      quantity: 100,
      price: 100.00, // Low price to stay pending
    });

    // Navigate to orders and cancel
    await page.goto('/orders');
    await page.getByTestId(`order-${orderId}`).click();
    await page.getByRole('button', { name: 'Cancel Order' }).click();

    // Confirm cancellation
    await page.getByRole('button', { name: 'Confirm' }).click();

    // Verify status changed
    await expect(page.getByTestId(`order-${orderId}`).getByText('CANCELLED')).toBeVisible();
  });
});

test.describe('Portfolio Dashboard - Critical Path', () => {
  test('should display portfolio summary correctly', async ({ page }) => {
    await page.goto('/dashboard');

    // Verify key metrics are displayed
    await expect(page.getByTestId('total-value')).toBeVisible();
    await expect(page.getByTestId('daily-pnl')).toBeVisible();
    await expect(page.getByTestId('positions-count')).toBeVisible();

    // Verify positions table
    const positionsTable = page.getByTestId('positions-table');
    await expect(positionsTable).toBeVisible();
    
    // Should have at least one position
    const rows = positionsTable.getByRole('row');
    await expect(rows).toHaveCount({ min: 2 }); // Header + at least 1 data row
  });

  test('should navigate between portfolio views', async ({ page }) => {
    await page.goto('/dashboard');

    // Navigate to positions
    await page.getByRole('tab', { name: 'Positions' }).click();
    await expect(page.getByTestId('positions-view')).toBeVisible();

    // Navigate to orders
    await page.getByRole('tab', { name: 'Orders' }).click();
    await expect(page.getByTestId('orders-view')).toBeVisible();

    // Navigate to activity
    await page.getByRole('tab', { name: 'Activity' }).click();
    await expect(page.getByTestId('activity-view')).toBeVisible();
  });
});
```

### Visual Regression Tests

```typescript
// e2e/tests/visual.spec.ts
import { test, expect } from '@playwright/test';

test.describe('Visual Regression Tests', () => {
  test('dashboard looks correct', async ({ page }) => {
    await page.goto('/dashboard');
    await page.waitForLoadState('networkidle');
    
    // Mask dynamic content
    await expect(page).toHaveScreenshot('dashboard.png', {
      mask: [
        page.getByTestId('timestamp'),
        page.getByTestId('market-price'),
        page.getByTestId('pnl-value'),
      ],
      maxDiffPixelRatio: 0.02,
    });
  });

  test('order form looks correct', async ({ page }) => {
    await page.goto('/trading/order');
    await page.waitForLoadState('networkidle');
    
    await expect(page.getByTestId('order-form')).toHaveScreenshot('order-form.png', {
      maxDiffPixelRatio: 0.02,
    });
  });

  test('positions table looks correct', async ({ page }) => {
    await page.goto('/positions');
    await page.waitForLoadState('networkidle');
    
    await expect(page.getByTestId('positions-table')).toHaveScreenshot('positions-table.png', {
      mask: [
        page.locator('[data-testid*="price"]'),
        page.locator('[data-testid*="pnl"]'),
      ],
      maxDiffPixelRatio: 0.02,
    });
  });

  test('responsive - mobile view', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await page.goto('/dashboard');
    await page.waitForLoadState('networkidle');
    
    await expect(page).toHaveScreenshot('dashboard-mobile.png', {
      mask: [
        page.getByTestId('timestamp'),
        page.getByTestId('market-price'),
      ],
      maxDiffPixelRatio: 0.02,
    });
  });
});
```

### Test Fixtures

```typescript
// e2e/fixtures/trading.ts
import { test as base, expect } from '@playwright/test';
import { OrderPage } from '../pages/OrderPage';
import { PositionsPage } from '../pages/PositionsPage';
import { DashboardPage } from '../pages/DashboardPage';

// Extend test with custom fixtures
export const test = base.extend<{
  orderPage: OrderPage;
  positionsPage: PositionsPage;
  dashboardPage: DashboardPage;
  testAccount: { id: string; balance: number };
}>({
  orderPage: async ({ page }, use) => {
    const orderPage = new OrderPage(page);
    await use(orderPage);
  },

  positionsPage: async ({ page }, use) => {
    const positionsPage = new PositionsPage(page);
    await use(positionsPage);
  },

  dashboardPage: async ({ page }, use) => {
    const dashboardPage = new DashboardPage(page);
    await use(dashboardPage);
  },

  // Test account fixture with cleanup
  testAccount: async ({ request }, use) => {
    // Create test account
    const response = await request.post('/api/test/accounts', {
      data: {
        initialBalance: 100000,
        type: 'test',
      },
    });
    const account = await response.json();

    await use(account);

    // Cleanup after test
    await request.delete(`/api/test/accounts/${account.id}`);
  },
});

export { expect };
```

### GitHub Actions E2E Pipeline

```yaml
# .github/workflows/e2e-tests.yml
name: E2E Tests

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]

jobs:
  e2e-tests:
    runs-on: ubuntu-latest
    container:
      image: mcr.microsoft.com/playwright:v1.40.0-jammy
    
    services:
      postgres:
        image: postgres:15
        env:
          POSTGRES_DB: orion_test
          POSTGRES_USER: test
          POSTGRES_PASSWORD: test
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5

    steps:
      - uses: actions/checkout@v4

      - name: Setup Node.js
        uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: 'npm'
          cache-dependency-path: frontend/package-lock.json

      - name: Install dependencies
        run: |
          cd frontend
          npm ci

      - name: Start test environment
        run: |
          docker-compose -f docker-compose.test.yml up -d
          ./scripts/wait-for-services.sh
        env:
          DATABASE_URL: postgres://test:test@postgres:5432/orion_test

      - name: Run E2E tests
        run: |
          cd frontend
          npx playwright test --shard=${{ matrix.shard }}/${{ strategy.job-count }}
        env:
          BASE_URL: http://localhost:3000
          TEST_USER_EMAIL: ${{ secrets.TEST_USER_EMAIL }}
          TEST_USER_PASSWORD: ${{ secrets.TEST_USER_PASSWORD }}

      - name: Upload test results
        uses: actions/upload-artifact@v4
        if: always()
        with:
          name: playwright-report-${{ matrix.shard }}
          path: frontend/playwright-report/
          retention-days: 30

      - name: Upload test videos
        uses: actions/upload-artifact@v4
        if: failure()
        with:
          name: test-videos-${{ matrix.shard }}
          path: frontend/test-results/
          retention-days: 7

    strategy:
      fail-fast: false
      matrix:
        shard: [1, 2, 3, 4]
```

## Definition of Done

- [ ] Playwright configured
- [ ] Page Object Model implemented
- [ ] Authentication flow working
- [ ] Critical path tests written
- [ ] Visual regression enabled
- [ ] Cross-browser testing
- [ ] Mobile viewport testing
- [ ] CI/CD integration
- [ ] Test reports generated
- [ ] Documentation complete

## Test Cases

```yaml
test-cases:
  - name: "Cross-browser consistency"
    given: "E2E tests"
    when: "Run on Chrome, Firefox, Safari"
    then: "All tests pass on all browsers"
  
  - name: "Visual regression detection"
    given: "UI changes"
    when: "Visual tests run"
    then: "Differences highlighted for review"
  
  - name: "Parallel execution"
    given: "Large test suite"
    when: "Tests run in CI"
    then: "Execution time < 15 minutes"
```
