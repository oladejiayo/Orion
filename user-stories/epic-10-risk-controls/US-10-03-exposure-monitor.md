# User Story: US-10-03 - Real-Time Exposure Monitor

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-10-03 |
| **Epic** | Epic 10 - Risk & Controls |
| **Title** | Real-Time Exposure Monitor |
| **Priority** | P0 - Critical |
| **Story Points** | 8 |
| **PRD Reference** | FR-Risk-03, NFR-Performance-02 |

## User Story

**As a** risk manager  
**I want** real-time exposure monitoring across all clients and positions  
**So that** I can identify and respond to risk limit breaches immediately

## Description

Implement real-time exposure calculation and monitoring service that maintains current exposure values in Redis, subscribes to position/trade/market data updates, and triggers alerts when limits are approached or breached.

## Acceptance Criteria

- [ ] Real-time gross exposure calculation
- [ ] Real-time net exposure calculation
- [ ] Daily P&L tracking
- [ ] Exposure update on position changes
- [ ] Exposure update on market price changes
- [ ] Redis cache for fast lookups
- [ ] Limit breach detection
- [ ] WebSocket exposure streaming
- [ ] < 100ms exposure update latency

## Technical Details

### Exposure Cache Service

```typescript
// services/risk-service/src/cache/risk-cache.service.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { Redis } from 'ioredis';
import { InjectRedis } from '@nestjs-modules/ioredis';
import { logger, metrics } from '@orion/observability';

export interface ExposureData {
  clientId: string;
  grossExposure: number;
  netExposure: number;
  longExposure: number;
  shortExposure: number;
  dailyPnL: number;
  dailyTurnover: number;
  openOrderCount: number;
  lastUpdated: Date;
}

export interface PositionCache {
  clientId: string;
  instrumentId: string;
  quantity: number;
  marketValue: number;
  unrealizedPnL: number;
  lastPrice: number;
}

@Injectable()
export class RiskCacheService implements OnModuleInit {
  private readonly exposurePrefix = 'risk:exposure:';
  private readonly positionPrefix = 'risk:position:';
  private readonly pnlPrefix = 'risk:pnl:';

  constructor(@InjectRedis() private readonly redis: Redis) {}

  async onModuleInit(): Promise<void> {
    // Subscribe to position updates
    const subscriber = this.redis.duplicate();
    await subscriber.subscribe('position:updated', 'trade:executed', 'market:price');

    subscriber.on('message', async (channel, message) => {
      const data = JSON.parse(message);
      
      switch (channel) {
        case 'position:updated':
          await this.handlePositionUpdate(data);
          break;
        case 'trade:executed':
          await this.handleTradeExecuted(data);
          break;
        case 'market:price':
          await this.handlePriceUpdate(data);
          break;
      }
    });
  }

  /**
   * Get client gross exposure
   */
  async getGrossExposure(clientId: string): Promise<number> {
    const key = `${this.exposurePrefix}${clientId}`;
    const value = await this.redis.hget(key, 'grossExposure');
    return value ? parseFloat(value) : 0;
  }

  /**
   * Get client net exposure
   */
  async getNetExposure(clientId: string): Promise<number> {
    const key = `${this.exposurePrefix}${clientId}`;
    const value = await this.redis.hget(key, 'netExposure');
    return value ? parseFloat(value) : 0;
  }

  /**
   * Get client daily P&L
   */
  async getDailyPnL(clientId: string): Promise<number> {
    const key = `${this.pnlPrefix}${clientId}:${this.getTodayKey()}`;
    const value = await this.redis.get(key);
    return value ? parseFloat(value) : 0;
  }

  /**
   * Get position for client/instrument
   */
  async getPosition(clientId: string, instrumentId: string): Promise<number> {
    const key = `${this.positionPrefix}${clientId}:${instrumentId}`;
    const value = await this.redis.hget(key, 'quantity');
    return value ? parseFloat(value) : 0;
  }

  /**
   * Get full exposure data
   */
  async getExposureData(clientId: string): Promise<ExposureData | null> {
    const key = `${this.exposurePrefix}${clientId}`;
    const data = await this.redis.hgetall(key);

    if (!data || Object.keys(data).length === 0) {
      return null;
    }

    return {
      clientId,
      grossExposure: parseFloat(data.grossExposure) || 0,
      netExposure: parseFloat(data.netExposure) || 0,
      longExposure: parseFloat(data.longExposure) || 0,
      shortExposure: parseFloat(data.shortExposure) || 0,
      dailyPnL: parseFloat(data.dailyPnL) || 0,
      dailyTurnover: parseFloat(data.dailyTurnover) || 0,
      openOrderCount: parseInt(data.openOrderCount, 10) || 0,
      lastUpdated: data.lastUpdated ? new Date(data.lastUpdated) : new Date(),
    };
  }

  /**
   * Update exposure data
   */
  async updateExposure(clientId: string, updates: Partial<ExposureData>): Promise<void> {
    const key = `${this.exposurePrefix}${clientId}`;
    const updateObj: Record<string, string> = {
      lastUpdated: new Date().toISOString(),
    };

    for (const [field, value] of Object.entries(updates)) {
      if (value !== undefined && field !== 'clientId') {
        updateObj[field] = String(value);
      }
    }

    await this.redis.hset(key, updateObj);
    await this.redis.expire(key, 86400); // 24 hour TTL

    metrics.gauge('risk.exposure.gross', updates.grossExposure || 0, { clientId });
    metrics.gauge('risk.exposure.net', updates.netExposure || 0, { clientId });
  }

  /**
   * Update position cache
   */
  async updatePosition(position: PositionCache): Promise<void> {
    const key = `${this.positionPrefix}${position.clientId}:${position.instrumentId}`;

    await this.redis.hset(key, {
      quantity: String(position.quantity),
      marketValue: String(position.marketValue),
      unrealizedPnL: String(position.unrealizedPnL),
      lastPrice: String(position.lastPrice),
      lastUpdated: new Date().toISOString(),
    });

    await this.redis.expire(key, 86400);
  }

  /**
   * Handle position update event
   */
  private async handlePositionUpdate(data: any): Promise<void> {
    const startTime = Date.now();

    await this.updatePosition({
      clientId: data.clientId,
      instrumentId: data.instrumentId,
      quantity: data.quantity,
      marketValue: data.marketValue,
      unrealizedPnL: data.unrealizedPnL,
      lastPrice: data.lastPrice,
    });

    // Recalculate client exposure
    await this.recalculateClientExposure(data.clientId);

    metrics.timing('risk.cache.position_update', Date.now() - startTime);
  }

  /**
   * Handle trade executed event
   */
  private async handleTradeExecuted(data: any): Promise<void> {
    const clientId = data.clientId;
    const tradeValue = Math.abs(data.quantity * data.price);

    // Update daily turnover
    const key = `${this.exposurePrefix}${clientId}`;
    await this.redis.hincrbyfloat(key, 'dailyTurnover', tradeValue);

    // Update daily P&L if realized
    if (data.realizedPnL) {
      const pnlKey = `${this.pnlPrefix}${clientId}:${this.getTodayKey()}`;
      await this.redis.incrbyfloat(pnlKey, data.realizedPnL);
      await this.redis.expire(pnlKey, 172800); // 48 hour TTL
    }

    metrics.increment('risk.trade.processed');
  }

  /**
   * Handle market price update
   */
  private async handlePriceUpdate(data: any): Promise<void> {
    const { instrumentId, price } = data;

    // Find all positions for this instrument
    const pattern = `${this.positionPrefix}*:${instrumentId}`;
    const keys = await this.redis.keys(pattern);

    for (const key of keys) {
      const position = await this.redis.hgetall(key);
      if (position && position.quantity) {
        const quantity = parseFloat(position.quantity);
        const newMarketValue = quantity * price;
        const costBasis = parseFloat(position.costBasis) || 0;
        const unrealizedPnL = newMarketValue - costBasis;

        await this.redis.hset(key, {
          marketValue: String(newMarketValue),
          unrealizedPnL: String(unrealizedPnL),
          lastPrice: String(price),
          lastUpdated: new Date().toISOString(),
        });

        // Extract clientId from key
        const clientId = key.split(':')[2];
        await this.recalculateClientExposure(clientId);
      }
    }
  }

  /**
   * Recalculate client exposure from positions
   */
  async recalculateClientExposure(clientId: string): Promise<void> {
    const pattern = `${this.positionPrefix}${clientId}:*`;
    const keys = await this.redis.keys(pattern);

    let longExposure = 0;
    let shortExposure = 0;
    let totalUnrealizedPnL = 0;

    for (const key of keys) {
      const position = await this.redis.hgetall(key);
      if (position && position.marketValue) {
        const marketValue = parseFloat(position.marketValue);
        const quantity = parseFloat(position.quantity) || 0;

        if (quantity > 0) {
          longExposure += Math.abs(marketValue);
        } else {
          shortExposure += Math.abs(marketValue);
        }

        totalUnrealizedPnL += parseFloat(position.unrealizedPnL) || 0;
      }
    }

    const grossExposure = longExposure + shortExposure;
    const netExposure = longExposure - shortExposure;

    await this.updateExposure(clientId, {
      grossExposure,
      netExposure,
      longExposure,
      shortExposure,
    });

    // Publish exposure update for monitoring
    await this.redis.publish('risk:exposure:updated', JSON.stringify({
      clientId,
      grossExposure,
      netExposure,
      longExposure,
      shortExposure,
      unrealizedPnL: totalUnrealizedPnL,
      timestamp: new Date().toISOString(),
    }));
  }

  private getTodayKey(): string {
    return new Date().toISOString().split('T')[0];
  }
}
```

### Exposure Monitor Service

```typescript
// services/risk-service/src/monitor/exposure-monitor.service.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { Redis } from 'ioredis';
import { InjectRedis } from '@nestjs-modules/ioredis';
import { Cron, CronExpression } from '@nestjs/schedule';
import { logger, metrics } from '@orion/observability';
import { RiskCacheService, ExposureData } from '../cache/risk-cache.service';
import { RiskLimitService } from '../limits/risk-limit.service';
import { RiskAlertService } from '../alerts/risk-alert.service';
import { RiskLimitType, RiskEntityType } from '../entities/risk-limit.entity';

interface ExposureCheck {
  clientId: string;
  limitType: RiskLimitType;
  currentValue: number;
  limitValue: number;
  utilizationPercent: number;
  status: 'normal' | 'warning' | 'critical' | 'breached';
}

@Injectable()
export class ExposureMonitorService implements OnModuleInit {
  private monitoredClients = new Set<string>();

  constructor(
    @InjectRedis() private readonly redis: Redis,
    private readonly riskCache: RiskCacheService,
    private readonly limitService: RiskLimitService,
    private readonly alertService: RiskAlertService,
  ) {}

  async onModuleInit(): Promise<void> {
    // Subscribe to exposure updates
    const subscriber = this.redis.duplicate();
    await subscriber.subscribe('risk:exposure:updated');

    subscriber.on('message', async (channel, message) => {
      if (channel === 'risk:exposure:updated') {
        const data = JSON.parse(message);
        await this.checkExposureLimits(data.clientId, data);
      }
    });
  }

  /**
   * Check all exposure limits for a client
   */
  async checkExposureLimits(
    clientId: string,
    exposure?: Partial<ExposureData>,
  ): Promise<ExposureCheck[]> {
    const checks: ExposureCheck[] = [];

    // Get current exposure if not provided
    const currentExposure = exposure 
      ? exposure as ExposureData
      : await this.riskCache.getExposureData(clientId);

    if (!currentExposure) return checks;

    // Get tenant ID (would come from client service)
    const tenantId = await this.getTenantId(clientId);

    // Check gross exposure
    const grossLimit = await this.limitService.getEffectiveLimit(
      tenantId,
      RiskEntityType.CLIENT,
      clientId,
      RiskLimitType.GROSS_EXPOSURE,
    );

    if (grossLimit) {
      const check = this.evaluateLimit(
        clientId,
        RiskLimitType.GROSS_EXPOSURE,
        currentExposure.grossExposure,
        grossLimit,
      );
      checks.push(check);
      await this.handleLimitCheck(check, tenantId);
    }

    // Check net exposure
    const netLimit = await this.limitService.getEffectiveLimit(
      tenantId,
      RiskEntityType.CLIENT,
      clientId,
      RiskLimitType.NET_EXPOSURE,
    );

    if (netLimit) {
      const check = this.evaluateLimit(
        clientId,
        RiskLimitType.NET_EXPOSURE,
        Math.abs(currentExposure.netExposure),
        netLimit,
      );
      checks.push(check);
      await this.handleLimitCheck(check, tenantId);
    }

    // Check daily loss
    const dailyPnL = await this.riskCache.getDailyPnL(clientId);
    if (dailyPnL < 0) {
      const lossLimit = await this.limitService.getEffectiveLimit(
        tenantId,
        RiskEntityType.CLIENT,
        clientId,
        RiskLimitType.DAILY_LOSS,
      );

      if (lossLimit) {
        const check = this.evaluateLimit(
          clientId,
          RiskLimitType.DAILY_LOSS,
          Math.abs(dailyPnL),
          lossLimit,
        );
        checks.push(check);
        await this.handleLimitCheck(check, tenantId);
      }
    }

    return checks;
  }

  /**
   * Evaluate a single limit
   */
  private evaluateLimit(
    clientId: string,
    limitType: RiskLimitType,
    currentValue: number,
    limit: any,
  ): ExposureCheck {
    const limitValue = Number(limit.limitValue);
    const utilizationPercent = (currentValue / limitValue) * 100;

    let status: 'normal' | 'warning' | 'critical' | 'breached';

    if (currentValue > limitValue) {
      status = 'breached';
    } else if (utilizationPercent >= Number(limit.criticalThreshold)) {
      status = 'critical';
    } else if (utilizationPercent >= Number(limit.warningThreshold)) {
      status = 'warning';
    } else {
      status = 'normal';
    }

    return {
      clientId,
      limitType,
      currentValue,
      limitValue,
      utilizationPercent,
      status,
    };
  }

  /**
   * Handle limit check result
   */
  private async handleLimitCheck(check: ExposureCheck, tenantId: string): Promise<void> {
    // Record metric
    metrics.gauge(`risk.limit.utilization.${check.limitType}`, check.utilizationPercent, {
      clientId: check.clientId,
    });

    // Generate alerts for warning/critical/breached
    if (check.status !== 'normal') {
      await this.alertService.createAlert({
        tenantId,
        entityType: 'client',
        entityId: check.clientId,
        alertType: `${check.limitType}_${check.status}`,
        severity: check.status === 'breached' ? 'critical' : check.status === 'critical' ? 'critical' : 'warning',
        message: this.formatAlertMessage(check),
        currentValue: check.currentValue,
        thresholdValue: check.limitValue,
      });

      // Log breaches
      if (check.status === 'breached') {
        logger.error('Risk limit breached', {
          clientId: check.clientId,
          limitType: check.limitType,
          currentValue: check.currentValue,
          limitValue: check.limitValue,
        });

        metrics.increment('risk.limit.breach', {
          limitType: check.limitType,
        });
      }
    }
  }

  private formatAlertMessage(check: ExposureCheck): string {
    const statusMessages = {
      warning: `approaching limit (${check.utilizationPercent.toFixed(1)}%)`,
      critical: `near limit (${check.utilizationPercent.toFixed(1)}%)`,
      breached: `EXCEEDED limit (${check.currentValue.toFixed(2)} > ${check.limitValue.toFixed(2)})`,
    };

    return `${check.limitType.replace('_', ' ')} ${statusMessages[check.status] || ''}`;
  }

  /**
   * Periodic full exposure check
   */
  @Cron(CronExpression.EVERY_MINUTE)
  async runPeriodicCheck(): Promise<void> {
    for (const clientId of this.monitoredClients) {
      try {
        await this.checkExposureLimits(clientId);
      } catch (error) {
        logger.error('Periodic exposure check failed', { clientId, error });
      }
    }
  }

  /**
   * Register client for monitoring
   */
  registerClient(clientId: string): void {
    this.monitoredClients.add(clientId);
  }

  /**
   * Unregister client from monitoring
   */
  unregisterClient(clientId: string): void {
    this.monitoredClients.delete(clientId);
  }

  private async getTenantId(clientId: string): Promise<string> {
    // Would call client service
    return 'default-tenant';
  }
}
```

### Exposure WebSocket Gateway

```typescript
// services/risk-service/src/gateways/exposure.gateway.ts
import {
  WebSocketGateway, WebSocketServer, SubscribeMessage,
  OnGatewayConnection, OnGatewayDisconnect, MessageBody,
  ConnectedSocket,
} from '@nestjs/websockets';
import { Server, Socket } from 'socket.io';
import { UseGuards, OnModuleInit } from '@nestjs/common';
import { Redis } from 'ioredis';
import { InjectRedis } from '@nestjs-modules/ioredis';
import { WsAuthGuard, WsTenantExtractor } from '@orion/auth';
import { RiskCacheService } from '../cache/risk-cache.service';

@WebSocketGateway({
  namespace: '/ws/risk',
  cors: { origin: '*' },
})
@UseGuards(WsAuthGuard)
export class ExposureGateway implements OnGatewayConnection, OnGatewayDisconnect, OnModuleInit {
  @WebSocketServer()
  server: Server;

  constructor(
    @InjectRedis() private readonly redis: Redis,
    private readonly riskCache: RiskCacheService,
  ) {}

  async onModuleInit(): Promise<void> {
    // Subscribe to exposure updates
    const subscriber = this.redis.duplicate();
    await subscriber.subscribe('risk:exposure:updated', 'risk:alert:created');

    subscriber.on('message', async (channel, message) => {
      const data = JSON.parse(message);

      if (channel === 'risk:exposure:updated') {
        this.broadcastExposure(data);
      } else if (channel === 'risk:alert:created') {
        this.broadcastAlert(data);
      }
    });
  }

  async handleConnection(client: Socket): Promise<void> {
    const tenantId = WsTenantExtractor.extract(client);
    client.data.tenantId = tenantId;
    client.join(`tenant:${tenantId}`);
  }

  async handleDisconnect(client: Socket): Promise<void> {
    // Cleanup
  }

  @SubscribeMessage('subscribe:exposure')
  async handleSubscribe(
    @ConnectedSocket() client: Socket,
    @MessageBody() payload: { clientId?: string },
  ): Promise<void> {
    const tenantId = client.data.tenantId;

    if (payload.clientId) {
      client.join(`exposure:${payload.clientId}`);
      
      // Send current exposure immediately
      const exposure = await this.riskCache.getExposureData(payload.clientId);
      if (exposure) {
        client.emit('exposure', { type: 'snapshot', data: exposure });
      }
    } else {
      client.join(`exposure:tenant:${tenantId}`);
    }
  }

  @SubscribeMessage('subscribe:alerts')
  handleAlertSubscribe(
    @ConnectedSocket() client: Socket,
  ): void {
    const tenantId = client.data.tenantId;
    client.join(`alerts:${tenantId}`);
  }

  private broadcastExposure(data: any): void {
    // To specific client room
    this.server.to(`exposure:${data.clientId}`).emit('exposure', {
      type: 'update',
      data,
    });

    // To tenant room
    this.server.to(`exposure:tenant:${data.tenantId}`).emit('exposure', {
      type: 'update',
      data,
    });
  }

  private broadcastAlert(data: any): void {
    this.server.to(`alerts:${data.tenantId}`).emit('alert', data);
  }
}
```

## Definition of Done

- [ ] Real-time exposure calculation
- [ ] Position cache updates
- [ ] Price update handling
- [ ] Limit breach detection
- [ ] Alert generation
- [ ] WebSocket streaming
- [ ] < 100ms update latency
- [ ] Periodic validation checks

## Dependencies

- **US-07-02**: Position Service
- **US-04-02**: Market Data Service
- **US-10-01**: Risk Limit Configuration

## Test Cases

```typescript
describe('ExposureMonitorService', () => {
  it('should detect exposure limit breach', async () => {
    setClientLimit({ type: 'gross_exposure', value: 1000000 });

    await riskCache.updateExposure('client-1', { grossExposure: 1100000 });

    const checks = await monitor.checkExposureLimits('client-1');

    const grossCheck = checks.find(c => c.limitType === 'gross_exposure');
    expect(grossCheck.status).toBe('breached');
  });

  it('should update exposure on position change', async () => {
    await redis.publish('position:updated', JSON.stringify({
      clientId: 'client-1',
      instrumentId: 'EUR/USD',
      quantity: 1000000,
      marketValue: 1085000,
    }));

    await waitFor(100);

    const exposure = await riskCache.getExposureData('client-1');
    expect(exposure.grossExposure).toBeGreaterThan(0);
  });

  it('should recalculate on price update', async () => {
    // Set initial position
    await riskCache.updatePosition({
      clientId: 'client-1',
      instrumentId: 'EUR/USD',
      quantity: 1000000,
      marketValue: 1085000,
      lastPrice: 1.085,
    });

    // Price update
    await redis.publish('market:price', JSON.stringify({
      instrumentId: 'EUR/USD',
      price: 1.090,
    }));

    await waitFor(100);

    const exposure = await riskCache.getExposureData('client-1');
    expect(exposure.grossExposure).toBe(1090000);
  });

  it('should stream exposure updates via WebSocket', async () => {
    const client = io('ws://localhost/ws/risk');
    const updates = [];

    client.on('exposure', (data) => updates.push(data));
    client.emit('subscribe:exposure', { clientId: 'client-1' });

    await riskCache.updateExposure('client-1', { grossExposure: 500000 });

    await waitFor(50);
    expect(updates.length).toBeGreaterThan(0);
  });
});
```
