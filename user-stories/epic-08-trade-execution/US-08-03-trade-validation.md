# User Story: US-08-03 - Trade Validation Engine

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-08-03 |
| **Epic** | Epic 08 - Trade Execution |
| **Title** | Trade Validation Engine |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |
| **PRD Reference** | FR-Trade-03, NFR-Compliance-01 |

## User Story

**As a** platform operator  
**I want** comprehensive trade validation  
**So that** only valid trades are booked and regulatory requirements are met

## Description

Implement trade validation engine with configurable rules covering instrument eligibility, counterparty status, settlement dates, regulatory checks, and business rules.

## Acceptance Criteria

- [ ] Instrument validation (exists, tradeable, limits)
- [ ] Counterparty validation (active, credit check)
- [ ] Date validation (trade date, settlement date)
- [ ] Quantity/price validation
- [ ] Regulatory compliance checks
- [ ] Configurable validation rules
- [ ] Validation result with detailed errors

## Technical Details

### Validation Rule Interface

```typescript
// services/trade-service/src/validation/validation-rule.interface.ts

export interface ValidationContext {
  trade: Partial<TradeEntity>;
  tenant: { tenantId: string; config: any };
  user: { id: string; permissions: string[] };
}

export interface ValidationError {
  code: string;
  field?: string;
  message: string;
  severity: 'error' | 'warning';
  metadata?: Record<string, any>;
}

export interface ValidationResult {
  valid: boolean;
  errors: ValidationError[];
  warnings: ValidationError[];
  validatedAt: Date;
  ruleName: string;
}

export interface ValidationRule {
  name: string;
  priority: number;
  enabled: boolean;
  
  validate(context: ValidationContext): Promise<ValidationResult>;
}
```

### Instrument Validation Rule

```typescript
// services/trade-service/src/validation/rules/instrument-validation.rule.ts
import { Injectable } from '@nestjs/common';
import { ValidationRule, ValidationContext, ValidationResult, ValidationError } from '../validation-rule.interface';
import { InstrumentService } from '@orion/reference-data';

@Injectable()
export class InstrumentValidationRule implements ValidationRule {
  name = 'instrument-validation';
  priority = 10;
  enabled = true;

  constructor(private readonly instrumentService: InstrumentService) {}

  async validate(context: ValidationContext): Promise<ValidationResult> {
    const errors: ValidationError[] = [];
    const warnings: ValidationError[] = [];
    const { trade } = context;

    // Get instrument
    const instrument = await this.instrumentService.getById(trade.instrumentId!);

    if (!instrument) {
      errors.push({
        code: 'INSTRUMENT_NOT_FOUND',
        field: 'instrumentId',
        message: `Instrument ${trade.instrumentId} not found`,
        severity: 'error',
      });
      return { valid: false, errors, warnings, validatedAt: new Date(), ruleName: this.name };
    }

    // Check instrument status
    if (instrument.status !== 'active') {
      errors.push({
        code: 'INSTRUMENT_NOT_ACTIVE',
        field: 'instrumentId',
        message: `Instrument ${instrument.symbol} is ${instrument.status}`,
        severity: 'error',
        metadata: { status: instrument.status },
      });
    }

    // Check trading hours (if applicable)
    if (instrument.tradingHours) {
      const isWithinHours = this.isWithinTradingHours(instrument.tradingHours, trade.executionTime);
      if (!isWithinHours) {
        warnings.push({
          code: 'OUTSIDE_TRADING_HOURS',
          field: 'executionTime',
          message: 'Trade executed outside normal trading hours',
          severity: 'warning',
        });
      }
    }

    // Check quantity limits
    if (instrument.minimumSize && Number(trade.quantity) < instrument.minimumSize) {
      errors.push({
        code: 'QUANTITY_BELOW_MINIMUM',
        field: 'quantity',
        message: `Quantity ${trade.quantity} below minimum ${instrument.minimumSize}`,
        severity: 'error',
      });
    }

    if (instrument.maximumSize && Number(trade.quantity) > instrument.maximumSize) {
      errors.push({
        code: 'QUANTITY_ABOVE_MAXIMUM',
        field: 'quantity',
        message: `Quantity ${trade.quantity} exceeds maximum ${instrument.maximumSize}`,
        severity: 'error',
      });
    }

    // Check lot size
    if (instrument.lotSize && Number(trade.quantity) % instrument.lotSize !== 0) {
      warnings.push({
        code: 'QUANTITY_NOT_LOT_SIZE',
        field: 'quantity',
        message: `Quantity ${trade.quantity} is not a multiple of lot size ${instrument.lotSize}`,
        severity: 'warning',
      });
    }

    // Validate symbol matches
    if (trade.symbol && trade.symbol !== instrument.symbol) {
      errors.push({
        code: 'SYMBOL_MISMATCH',
        field: 'symbol',
        message: `Symbol ${trade.symbol} does not match instrument symbol ${instrument.symbol}`,
        severity: 'error',
      });
    }

    return {
      valid: errors.length === 0,
      errors,
      warnings,
      validatedAt: new Date(),
      ruleName: this.name,
    };
  }

  private isWithinTradingHours(tradingHours: any, executionTime?: Date): boolean {
    if (!executionTime) return true;
    // Implement trading hours check based on schedule
    return true;
  }
}
```

### Counterparty Validation Rule

```typescript
// services/trade-service/src/validation/rules/counterparty-validation.rule.ts
import { Injectable } from '@nestjs/common';
import { ValidationRule, ValidationContext, ValidationResult, ValidationError } from '../validation-rule.interface';
import { CounterpartyService } from '@orion/reference-data';
import { CreditService } from '@orion/risk';

@Injectable()
export class CounterpartyValidationRule implements ValidationRule {
  name = 'counterparty-validation';
  priority = 20;
  enabled = true;

  constructor(
    private readonly counterpartyService: CounterpartyService,
    private readonly creditService: CreditService,
  ) {}

  async validate(context: ValidationContext): Promise<ValidationResult> {
    const errors: ValidationError[] = [];
    const warnings: ValidationError[] = [];
    const { trade } = context;

    // Validate client
    const client = await this.counterpartyService.getById(trade.clientId!);
    if (!client) {
      errors.push({
        code: 'CLIENT_NOT_FOUND',
        field: 'clientId',
        message: `Client ${trade.clientId} not found`,
        severity: 'error',
      });
    } else if (client.status !== 'active') {
      errors.push({
        code: 'CLIENT_NOT_ACTIVE',
        field: 'clientId',
        message: `Client ${client.name} is ${client.status}`,
        severity: 'error',
      });
    }

    // Validate counterparty
    const counterparty = await this.counterpartyService.getById(trade.counterpartyId!);
    if (!counterparty) {
      errors.push({
        code: 'COUNTERPARTY_NOT_FOUND',
        field: 'counterpartyId',
        message: `Counterparty ${trade.counterpartyId} not found`,
        severity: 'error',
      });
    } else if (counterparty.status !== 'active') {
      errors.push({
        code: 'COUNTERPARTY_NOT_ACTIVE',
        field: 'counterpartyId',
        message: `Counterparty ${counterparty.name} is ${counterparty.status}`,
        severity: 'error',
      });
    }

    // Credit check (if client found)
    if (client && trade.notionalAmount) {
      const creditCheck = await this.creditService.checkCredit({
        clientId: trade.clientId!,
        amount: Number(trade.notionalAmount),
        currency: trade.currency!,
      });

      if (!creditCheck.approved) {
        if (creditCheck.hardLimit) {
          errors.push({
            code: 'CREDIT_LIMIT_EXCEEDED',
            field: 'clientId',
            message: `Trade exceeds credit limit for ${client.name}`,
            severity: 'error',
            metadata: {
              limit: creditCheck.limit,
              utilization: creditCheck.utilization,
              tradeAmount: trade.notionalAmount,
            },
          });
        } else {
          warnings.push({
            code: 'CREDIT_LIMIT_WARNING',
            field: 'clientId',
            message: `Trade approaching credit limit for ${client.name}`,
            severity: 'warning',
            metadata: {
              limit: creditCheck.limit,
              utilization: creditCheck.utilization,
            },
          });
        }
      }
    }

    return {
      valid: errors.length === 0,
      errors,
      warnings,
      validatedAt: new Date(),
      ruleName: this.name,
    };
  }
}
```

### Settlement Date Validation Rule

```typescript
// services/trade-service/src/validation/rules/settlement-validation.rule.ts
import { Injectable } from '@nestjs/common';
import { ValidationRule, ValidationContext, ValidationResult, ValidationError } from '../validation-rule.interface';
import { CalendarService } from '@orion/reference-data';

@Injectable()
export class SettlementValidationRule implements ValidationRule {
  name = 'settlement-validation';
  priority = 30;
  enabled = true;

  constructor(private readonly calendarService: CalendarService) {}

  async validate(context: ValidationContext): Promise<ValidationResult> {
    const errors: ValidationError[] = [];
    const warnings: ValidationError[] = [];
    const { trade } = context;

    const tradeDate = new Date(trade.tradeDate!);
    const settlementDate = new Date(trade.settlementDate!);
    const today = new Date();
    today.setHours(0, 0, 0, 0);

    // Trade date validation
    if (tradeDate > today) {
      errors.push({
        code: 'FUTURE_TRADE_DATE',
        field: 'tradeDate',
        message: 'Trade date cannot be in the future',
        severity: 'error',
      });
    }

    // Settlement date must be after trade date
    if (settlementDate < tradeDate) {
      errors.push({
        code: 'SETTLEMENT_BEFORE_TRADE',
        field: 'settlementDate',
        message: 'Settlement date cannot be before trade date',
        severity: 'error',
      });
    }

    // Check if settlement date is a business day
    const calendars = await this.getRelevantCalendars(trade.currency!, trade.settlementCurrency!);
    const isBusinessDay = await this.calendarService.isBusinessDay(settlementDate, calendars);
    
    if (!isBusinessDay) {
      errors.push({
        code: 'SETTLEMENT_NON_BUSINESS_DAY',
        field: 'settlementDate',
        message: `Settlement date ${settlementDate.toISOString().split('T')[0]} is not a business day`,
        severity: 'error',
      });
    }

    // Check minimum settlement period
    const businessDays = await this.calendarService.countBusinessDays(
      tradeDate,
      settlementDate,
      calendars,
    );

    const minSettlementDays = this.getMinSettlementDays(trade.assetClass!);
    if (businessDays < minSettlementDays) {
      warnings.push({
        code: 'SHORT_SETTLEMENT',
        field: 'settlementDate',
        message: `Settlement period ${businessDays} days is shorter than standard ${minSettlementDays} days`,
        severity: 'warning',
      });
    }

    // Check maximum settlement period
    const maxSettlementDays = 365;
    if (businessDays > maxSettlementDays) {
      errors.push({
        code: 'SETTLEMENT_TOO_LONG',
        field: 'settlementDate',
        message: `Settlement period ${businessDays} days exceeds maximum ${maxSettlementDays} days`,
        severity: 'error',
      });
    }

    return {
      valid: errors.length === 0,
      errors,
      warnings,
      validatedAt: new Date(),
      ruleName: this.name,
    };
  }

  private async getRelevantCalendars(currency: string, settlementCurrency: string): Promise<string[]> {
    const calendars = new Set<string>();
    
    // Add currency calendars
    const currencyCalendar: Record<string, string> = {
      'USD': 'US',
      'EUR': 'EU',
      'GBP': 'GB',
      'JPY': 'JP',
      'CHF': 'CH',
    };

    if (currencyCalendar[currency]) calendars.add(currencyCalendar[currency]);
    if (currencyCalendar[settlementCurrency]) calendars.add(currencyCalendar[settlementCurrency]);

    return Array.from(calendars);
  }

  private getMinSettlementDays(assetClass: string): number {
    const settlementDays: Record<string, number> = {
      'fx': 2,
      'crypto': 0,
      'equity': 2,
      'bond': 2,
      'commodity': 2,
    };
    return settlementDays[assetClass] || 2;
  }
}
```

### Trade Validation Service

```typescript
// services/trade-service/src/validation/trade-validation.service.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { ModuleRef } from '@nestjs/core';
import { logger, metrics } from '@orion/observability';
import { ValidationRule, ValidationContext, ValidationResult, ValidationError } from './validation-rule.interface';
import { InstrumentValidationRule } from './rules/instrument-validation.rule';
import { CounterpartyValidationRule } from './rules/counterparty-validation.rule';
import { SettlementValidationRule } from './rules/settlement-validation.rule';
import { PriceValidationRule } from './rules/price-validation.rule';
import { RegulatoryValidationRule } from './rules/regulatory-validation.rule';
import { TradeEntity } from '../entities/trade.entity';
import { getCurrentTenant, getCurrentUser } from '@orion/security';

export interface FullValidationResult {
  valid: boolean;
  errors: ValidationError[];
  warnings: ValidationError[];
  ruleResults: ValidationResult[];
  validatedAt: Date;
}

@Injectable()
export class TradeValidationService implements OnModuleInit {
  private rules: ValidationRule[] = [];

  constructor(
    private readonly moduleRef: ModuleRef,
    private readonly instrumentRule: InstrumentValidationRule,
    private readonly counterpartyRule: CounterpartyValidationRule,
    private readonly settlementRule: SettlementValidationRule,
  ) {}

  onModuleInit() {
    // Register rules in priority order
    this.rules = [
      this.instrumentRule,
      this.counterpartyRule,
      this.settlementRule,
      // Additional rules added here
    ].sort((a, b) => a.priority - b.priority);

    logger.info('Trade validation service initialized', {
      ruleCount: this.rules.length,
      rules: this.rules.map(r => r.name),
    });
  }

  async validateTrade(trade: Partial<TradeEntity>): Promise<FullValidationResult> {
    const startTime = Date.now();
    const allErrors: ValidationError[] = [];
    const allWarnings: ValidationError[] = [];
    const ruleResults: ValidationResult[] = [];

    const context: ValidationContext = {
      trade,
      tenant: { tenantId: getCurrentTenant()?.tenantId || '', config: {} },
      user: { id: getCurrentUser()?.id || '', permissions: [] },
    };

    // Run all enabled rules
    for (const rule of this.rules) {
      if (!rule.enabled) continue;

      try {
        const result = await rule.validate(context);
        ruleResults.push(result);
        
        allErrors.push(...result.errors);
        allWarnings.push(...result.warnings);

        // Stop on critical errors if configured
        if (result.errors.some(e => e.severity === 'error')) {
          // Continue to gather all errors for better UX
        }
      } catch (error) {
        logger.error(`Validation rule ${rule.name} threw error`, { error });
        allErrors.push({
          code: 'VALIDATION_RULE_ERROR',
          message: `Validation rule ${rule.name} failed: ${error.message}`,
          severity: 'error',
          metadata: { ruleName: rule.name },
        });
      }
    }

    const result: FullValidationResult = {
      valid: allErrors.length === 0,
      errors: allErrors,
      warnings: allWarnings,
      ruleResults,
      validatedAt: new Date(),
    };

    metrics.timing('trade.validation.time', Date.now() - startTime);
    metrics.increment('trade.validation', { 
      valid: result.valid.toString(),
      errorCount: allErrors.length.toString(),
    });

    return result;
  }

  async validateCreateDto(dto: any): Promise<{ valid: boolean; errors: string[] }> {
    const errors: string[] = [];

    // Basic field validation
    if (!dto.instrumentId) errors.push('instrumentId is required');
    if (!dto.clientId) errors.push('clientId is required');
    if (!dto.counterpartyId) errors.push('counterpartyId is required');
    if (!dto.side || !['buy', 'sell'].includes(dto.side)) errors.push('side must be buy or sell');
    if (!dto.quantity || dto.quantity <= 0) errors.push('quantity must be positive');
    if (!dto.price || dto.price <= 0) errors.push('price must be positive');
    if (!dto.tradeDate) errors.push('tradeDate is required');
    if (!dto.settlementDate) errors.push('settlementDate is required');

    return {
      valid: errors.length === 0,
      errors,
    };
  }
}
```

### Validation Module

```typescript
// services/trade-service/src/validation/validation.module.ts
import { Module } from '@nestjs/common';
import { TradeValidationService } from './trade-validation.service';
import { InstrumentValidationRule } from './rules/instrument-validation.rule';
import { CounterpartyValidationRule } from './rules/counterparty-validation.rule';
import { SettlementValidationRule } from './rules/settlement-validation.rule';
import { PriceValidationRule } from './rules/price-validation.rule';
import { RegulatoryValidationRule } from './rules/regulatory-validation.rule';

@Module({
  providers: [
    TradeValidationService,
    InstrumentValidationRule,
    CounterpartyValidationRule,
    SettlementValidationRule,
    PriceValidationRule,
    RegulatoryValidationRule,
  ],
  exports: [TradeValidationService],
})
export class ValidationModule {}
```

## Definition of Done

- [ ] Instrument validation rule
- [ ] Counterparty validation rule
- [ ] Settlement date validation rule
- [ ] Price validation rule
- [ ] Regulatory checks
- [ ] Validation engine orchestration
- [ ] Detailed error reporting

## Dependencies

- **US-04-01**: Instrument Service
- **US-04-02**: Counterparty Service
- **US-10-01**: Credit Service (placeholder)

## Test Cases

```typescript
describe('TradeValidationService', () => {
  it('should validate valid trade', async () => {
    const result = await validationService.validateTrade({
      instrumentId: 'valid-instrument',
      clientId: 'active-client',
      counterpartyId: 'active-lp',
      side: 'buy',
      quantity: 1000000,
      price: 1.0850,
      tradeDate: new Date(),
      settlementDate: addDays(new Date(), 2),
    });

    expect(result.valid).toBe(true);
    expect(result.errors.length).toBe(0);
  });

  it('should reject inactive instrument', async () => {
    const result = await validationService.validateTrade({
      instrumentId: 'inactive-instrument',
      // ...other fields
    });

    expect(result.valid).toBe(false);
    expect(result.errors.some(e => e.code === 'INSTRUMENT_NOT_ACTIVE')).toBe(true);
  });

  it('should warn on off-hours trade', async () => {
    const result = await validationService.validateTrade({
      instrumentId: 'valid-instrument',
      executionTime: new Date('2024-01-14T03:00:00Z'), // Sunday
      // ...other fields
    });

    expect(result.warnings.some(w => w.code === 'OUTSIDE_TRADING_HOURS')).toBe(true);
  });
});
```
