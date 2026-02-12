# US-12-07: Analytics WebSocket

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-12-07 |
| **Epic** | Epic 12: Analytics & Data Products |
| **Title** | Analytics WebSocket |
| **Priority** | Medium |
| **Story Points** | 5 |
| **Status** | Ready for Development |

## User Story

**As a** trader or operations user  
**I want** real-time analytics updates via WebSocket  
**So that** I can monitor key metrics without constant page refreshes

## Acceptance Criteria

### AC1: Real-Time Metric Subscriptions
- **Given** a user connected to the analytics WebSocket
- **When** they subscribe to specific metrics
- **Then** they receive updates:
  - Trade volume counters (updated per trade)
  - Position changes
  - Risk metric updates
  - P&L changes

### AC2: Aggregated Updates
- **Given** high-frequency underlying data
- **When** updates are streamed
- **Then** aggregation is applied:
  - Batched updates (configurable window)
  - Rollup to subscribed granularity
  - Delta updates to minimize bandwidth
  - Sequence numbers for ordering

### AC3: Subscription Management
- **Given** a client WebSocket connection
- **When** subscriptions are modified
- **Then** subscription management works:
  - Subscribe to multiple metric streams
  - Unsubscribe from specific streams
  - Dynamic filter changes
  - Connection keep-alive handling

### AC4: Backpressure Handling
- **Given** a slow client or network
- **When** updates accumulate
- **Then** backpressure is managed:
  - Buffer with configurable limits
  - Skip intermediate updates if behind
  - Send latest snapshot on reconnect
  - Graceful degradation indicators

### AC5: Authorization
- **Given** a WebSocket connection attempt
- **When** authentication is validated
- **Then** authorization is enforced:
  - JWT token validation
  - Tenant isolation
  - Metric-level permissions
  - Session timeout handling

## Technical Specification

### Analytics WebSocket Gateway

```typescript
// src/analytics/websocket/gateways/analytics-websocket.gateway.ts
import {
  WebSocketGateway,
  WebSocketServer,
  SubscribeMessage,
  OnGatewayConnection,
  OnGatewayDisconnect,
  OnGatewayInit,
  ConnectedSocket,
  MessageBody,
  WsException,
} from '@nestjs/websockets';
import { Logger, UseGuards } from '@nestjs/common';
import { Server, Socket } from 'socket.io';
import { JwtService } from '@nestjs/jwt';
import { WsJwtGuard } from '../../../auth/guards/ws-jwt.guard';
import { MetricStreamService } from '../services/metric-stream.service';
import { SubscriptionManager } from '../services/subscription-manager.service';

interface AuthenticatedSocket extends Socket {
  user: {
    userId: string;
    tenantId: string;
    roles: string[];
  };
}

interface SubscribeRequest {
  channel: string;
  metrics: string[];
  filters?: Record<string, any>;
  aggregation?: {
    window: number; // seconds
    type: 'SUM' | 'AVG' | 'LAST' | 'DELTA';
  };
}

interface UnsubscribeRequest {
  channel: string;
  metrics?: string[];
}

@WebSocketGateway({
  namespace: '/analytics/stream',
  cors: {
    origin: process.env.CORS_ORIGINS?.split(',') || '*',
    credentials: true,
  },
  transports: ['websocket', 'polling'],
})
export class AnalyticsWebSocketGateway
  implements OnGatewayInit, OnGatewayConnection, OnGatewayDisconnect
{
  @WebSocketServer()
  server: Server;

  private readonly logger = new Logger(AnalyticsWebSocketGateway.name);
  private connections = new Map<string, AuthenticatedSocket>();

  constructor(
    private readonly jwtService: JwtService,
    private readonly metricStream: MetricStreamService,
    private readonly subscriptionManager: SubscriptionManager,
  ) {}

  afterInit(server: Server): void {
    this.logger.log('Analytics WebSocket Gateway initialized');
    
    // Apply authentication middleware
    server.use(async (socket: AuthenticatedSocket, next) => {
      try {
        const token = socket.handshake.auth?.token || 
          socket.handshake.headers?.authorization?.replace('Bearer ', '');
        
        if (!token) {
          throw new WsException('Missing authentication token');
        }

        const payload = await this.jwtService.verifyAsync(token);
        socket.user = {
          userId: payload.sub,
          tenantId: payload.tenantId,
          roles: payload.roles || [],
        };
        
        next();
      } catch (error) {
        next(new Error('Authentication failed'));
      }
    });
  }

  handleConnection(client: AuthenticatedSocket): void {
    this.connections.set(client.id, client);
    
    // Join tenant room
    client.join(`tenant:${client.user.tenantId}`);
    
    this.logger.debug(
      `Client connected: ${client.id} (tenant: ${client.user.tenantId})`,
    );

    // Send connection acknowledgment
    client.emit('connected', {
      connectionId: client.id,
      timestamp: new Date().toISOString(),
    });
  }

  handleDisconnect(client: AuthenticatedSocket): void {
    // Cleanup subscriptions
    this.subscriptionManager.removeAllForConnection(client.id);
    this.connections.delete(client.id);
    
    this.logger.debug(`Client disconnected: ${client.id}`);
  }

  @SubscribeMessage('subscribe')
  async handleSubscribe(
    @ConnectedSocket() client: AuthenticatedSocket,
    @MessageBody() data: SubscribeRequest,
  ): Promise<{ success: boolean; subscriptionId?: string; error?: string }> {
    try {
      // Validate channel access
      await this.validateChannelAccess(client.user, data.channel);

      // Validate metrics
      this.validateMetrics(data.metrics);

      // Create subscription
      const subscriptionId = await this.subscriptionManager.subscribe(
        client.id,
        client.user.tenantId,
        data.channel,
        data.metrics,
        data.filters,
        data.aggregation,
      );

      // Join channel room
      client.join(`channel:${client.user.tenantId}:${data.channel}`);

      // Send initial snapshot
      const snapshot = await this.metricStream.getSnapshot(
        client.user.tenantId,
        data.channel,
        data.metrics,
        data.filters,
      );
      
      client.emit('snapshot', {
        channel: data.channel,
        data: snapshot,
        timestamp: new Date().toISOString(),
      });

      this.logger.debug(
        `Subscription created: ${subscriptionId} for ${data.channel}`,
      );

      return { success: true, subscriptionId };

    } catch (error) {
      this.logger.warn(`Subscribe failed: ${error.message}`);
      return { success: false, error: error.message };
    }
  }

  @SubscribeMessage('unsubscribe')
  async handleUnsubscribe(
    @ConnectedSocket() client: AuthenticatedSocket,
    @MessageBody() data: UnsubscribeRequest,
  ): Promise<{ success: boolean }> {
    await this.subscriptionManager.unsubscribe(
      client.id,
      data.channel,
      data.metrics,
    );

    // Leave channel room if no subscriptions remain
    const hasOtherSubs = await this.subscriptionManager.hasSubscriptionsForChannel(
      client.id,
      data.channel,
    );
    
    if (!hasOtherSubs) {
      client.leave(`channel:${client.user.tenantId}:${data.channel}`);
    }

    return { success: true };
  }

  @SubscribeMessage('ping')
  handlePing(@ConnectedSocket() client: AuthenticatedSocket): { pong: number } {
    return { pong: Date.now() };
  }

  // Called by MetricStreamService when new data arrives
  async broadcastUpdate(
    tenantId: string,
    channel: string,
    metrics: Record<string, any>,
    sequence: number,
  ): Promise<void> {
    const room = `channel:${tenantId}:${channel}`;
    
    this.server.to(room).emit('update', {
      channel,
      metrics,
      sequence,
      timestamp: new Date().toISOString(),
    });
  }

  // Broadcast tenant-wide update
  async broadcastTenantUpdate(
    tenantId: string,
    event: string,
    data: any,
  ): Promise<void> {
    const room = `tenant:${tenantId}`;
    this.server.to(room).emit(event, {
      data,
      timestamp: new Date().toISOString(),
    });
  }

  // Send to specific client
  async sendToClient(
    connectionId: string,
    event: string,
    data: any,
  ): Promise<void> {
    const client = this.connections.get(connectionId);
    if (client) {
      client.emit(event, data);
    }
  }

  private async validateChannelAccess(
    user: { userId: string; tenantId: string; roles: string[] },
    channel: string,
  ): Promise<void> {
    const channelPermissions: Record<string, string[]> = {
      'trading.metrics': ['TRADER', 'ADMIN', 'ANALYST'],
      'risk.metrics': ['RISK_MANAGER', 'ADMIN', 'ANALYST'],
      'positions': ['TRADER', 'RISK_MANAGER', 'ADMIN'],
      'pnl': ['TRADER', 'ADMIN', 'ANALYST'],
      'operations': ['OPERATIONS', 'ADMIN'],
    };

    const requiredRoles = channelPermissions[channel];
    if (requiredRoles && !requiredRoles.some(r => user.roles.includes(r))) {
      throw new WsException(`Access denied to channel: ${channel}`);
    }
  }

  private validateMetrics(metrics: string[]): void {
    const validMetrics = [
      'trade_count', 'trade_volume', 'trade_notional',
      'position_value', 'position_pnl',
      'exposure', 'utilization', 'var',
      'realized_pnl', 'unrealized_pnl', 'total_pnl',
      'order_count', 'fill_rate', 'avg_latency',
    ];

    const invalid = metrics.filter(m => !validMetrics.includes(m));
    if (invalid.length > 0) {
      throw new WsException(`Invalid metrics: ${invalid.join(', ')}`);
    }
  }

  getConnectionCount(): number {
    return this.connections.size;
  }

  getConnectionsByTenant(tenantId: string): string[] {
    return Array.from(this.connections.entries())
      .filter(([_, socket]) => socket.user.tenantId === tenantId)
      .map(([id, _]) => id);
  }
}
```

### Subscription Manager Service

```typescript
// src/analytics/websocket/services/subscription-manager.service.ts
import { Injectable, Logger } from '@nestjs/common';
import { v4 as uuidv4 } from 'uuid';

interface Subscription {
  id: string;
  connectionId: string;
  tenantId: string;
  channel: string;
  metrics: string[];
  filters: Record<string, any>;
  aggregation: {
    window: number;
    type: 'SUM' | 'AVG' | 'LAST' | 'DELTA';
  };
  lastSequence: number;
  createdAt: Date;
}

@Injectable()
export class SubscriptionManager {
  private readonly logger = new Logger(SubscriptionManager.name);
  private subscriptions = new Map<string, Subscription>();
  private connectionIndex = new Map<string, Set<string>>(); // connectionId -> subscriptionIds
  private channelIndex = new Map<string, Set<string>>(); // channel -> subscriptionIds

  async subscribe(
    connectionId: string,
    tenantId: string,
    channel: string,
    metrics: string[],
    filters: Record<string, any> = {},
    aggregation: Subscription['aggregation'] = { window: 1, type: 'LAST' },
  ): Promise<string> {
    const subscriptionId = uuidv4();
    
    const subscription: Subscription = {
      id: subscriptionId,
      connectionId,
      tenantId,
      channel,
      metrics,
      filters,
      aggregation,
      lastSequence: 0,
      createdAt: new Date(),
    };

    this.subscriptions.set(subscriptionId, subscription);

    // Update connection index
    if (!this.connectionIndex.has(connectionId)) {
      this.connectionIndex.set(connectionId, new Set());
    }
    this.connectionIndex.get(connectionId)!.add(subscriptionId);

    // Update channel index
    const channelKey = `${tenantId}:${channel}`;
    if (!this.channelIndex.has(channelKey)) {
      this.channelIndex.set(channelKey, new Set());
    }
    this.channelIndex.get(channelKey)!.add(subscriptionId);

    this.logger.debug(
      `Created subscription ${subscriptionId}: ${channel} [${metrics.join(', ')}]`,
    );

    return subscriptionId;
  }

  async unsubscribe(
    connectionId: string,
    channel: string,
    metrics?: string[],
  ): Promise<void> {
    const connectionSubs = this.connectionIndex.get(connectionId);
    if (!connectionSubs) return;

    for (const subId of connectionSubs) {
      const sub = this.subscriptions.get(subId);
      if (!sub || sub.channel !== channel) continue;

      if (metrics && metrics.length > 0) {
        // Remove specific metrics
        sub.metrics = sub.metrics.filter(m => !metrics.includes(m));
        
        if (sub.metrics.length === 0) {
          this.removeSubscription(subId);
        }
      } else {
        // Remove entire subscription
        this.removeSubscription(subId);
      }
    }
  }

  async removeAllForConnection(connectionId: string): Promise<void> {
    const subscriptionIds = this.connectionIndex.get(connectionId);
    if (!subscriptionIds) return;

    for (const subId of subscriptionIds) {
      this.removeSubscription(subId);
    }

    this.connectionIndex.delete(connectionId);
    this.logger.debug(`Removed all subscriptions for connection ${connectionId}`);
  }

  private removeSubscription(subscriptionId: string): void {
    const sub = this.subscriptions.get(subscriptionId);
    if (!sub) return;

    // Remove from connection index
    const connectionSubs = this.connectionIndex.get(sub.connectionId);
    if (connectionSubs) {
      connectionSubs.delete(subscriptionId);
    }

    // Remove from channel index
    const channelKey = `${sub.tenantId}:${sub.channel}`;
    const channelSubs = this.channelIndex.get(channelKey);
    if (channelSubs) {
      channelSubs.delete(subscriptionId);
    }

    this.subscriptions.delete(subscriptionId);
  }

  async hasSubscriptionsForChannel(
    connectionId: string,
    channel: string,
  ): Promise<boolean> {
    const connectionSubs = this.connectionIndex.get(connectionId);
    if (!connectionSubs) return false;

    for (const subId of connectionSubs) {
      const sub = this.subscriptions.get(subId);
      if (sub?.channel === channel) return true;
    }

    return false;
  }

  getSubscriptionsForChannel(
    tenantId: string,
    channel: string,
  ): Subscription[] {
    const channelKey = `${tenantId}:${channel}`;
    const subscriptionIds = this.channelIndex.get(channelKey);
    if (!subscriptionIds) return [];

    return Array.from(subscriptionIds)
      .map(id => this.subscriptions.get(id)!)
      .filter(Boolean);
  }

  getSubscription(subscriptionId: string): Subscription | undefined {
    return this.subscriptions.get(subscriptionId);
  }

  updateSequence(subscriptionId: string, sequence: number): void {
    const sub = this.subscriptions.get(subscriptionId);
    if (sub) {
      sub.lastSequence = sequence;
    }
  }

  getStats(): {
    totalSubscriptions: number;
    uniqueConnections: number;
    subscriptionsByChannel: Record<string, number>;
  } {
    const channelCounts: Record<string, number> = {};
    
    for (const [channelKey, subs] of this.channelIndex) {
      channelCounts[channelKey] = subs.size;
    }

    return {
      totalSubscriptions: this.subscriptions.size,
      uniqueConnections: this.connectionIndex.size,
      subscriptionsByChannel: channelCounts,
    };
  }
}
```

### Metric Stream Service

```typescript
// src/analytics/websocket/services/metric-stream.service.ts
import { Injectable, Logger, OnModuleInit } from '@nestjs/common';
import { Kafka, Consumer, EachMessagePayload } from 'kafkajs';
import { AnalyticsWebSocketGateway } from '../gateways/analytics-websocket.gateway';
import { SubscriptionManager } from './subscription-manager.service';
import { DataSource } from 'typeorm';

interface MetricUpdate {
  channel: string;
  tenantId: string;
  metrics: Record<string, number>;
  timestamp: Date;
}

interface AggregationBuffer {
  tenantId: string;
  channel: string;
  metrics: Record<string, number[]>;
  lastFlush: Date;
  sequence: number;
}

@Injectable()
export class MetricStreamService implements OnModuleInit {
  private readonly logger = new Logger(MetricStreamService.name);
  private kafka: Kafka;
  private consumer: Consumer;
  private aggregationBuffers = new Map<string, AggregationBuffer>();
  private flushIntervalMs = 1000; // 1 second

  constructor(
    private readonly gateway: AnalyticsWebSocketGateway,
    private readonly subscriptionManager: SubscriptionManager,
    private readonly dataSource: DataSource,
  ) {
    this.kafka = new Kafka({
      clientId: 'analytics-stream',
      brokers: process.env.KAFKA_BROKERS?.split(',') || ['localhost:9092'],
    });

    this.consumer = this.kafka.consumer({
      groupId: 'analytics-stream-group',
    });
  }

  async onModuleInit(): Promise<void> {
    await this.consumer.connect();
    
    // Subscribe to metric topics
    await this.consumer.subscribe({
      topics: [
        'analytics.trades',
        'analytics.positions',
        'analytics.risk',
        'analytics.pnl',
      ],
      fromBeginning: false,
    });

    await this.consumer.run({
      eachMessage: this.handleMessage.bind(this),
    });

    // Start aggregation flush loop
    setInterval(() => this.flushAggregations(), this.flushIntervalMs);

    this.logger.log('Metric stream service started');
  }

  private async handleMessage(payload: EachMessagePayload): Promise<void> {
    try {
      const { topic, message } = payload;
      const data = JSON.parse(message.value?.toString() || '{}');
      
      const update: MetricUpdate = {
        channel: this.topicToChannel(topic),
        tenantId: data.tenantId,
        metrics: data.metrics,
        timestamp: new Date(data.timestamp),
      };

      await this.processUpdate(update);
    } catch (error) {
      this.logger.error(`Error processing metric message: ${error.message}`);
    }
  }

  private async processUpdate(update: MetricUpdate): Promise<void> {
    const bufferKey = `${update.tenantId}:${update.channel}`;
    
    // Get or create buffer
    let buffer = this.aggregationBuffers.get(bufferKey);
    if (!buffer) {
      buffer = {
        tenantId: update.tenantId,
        channel: update.channel,
        metrics: {},
        lastFlush: new Date(),
        sequence: 0,
      };
      this.aggregationBuffers.set(bufferKey, buffer);
    }

    // Add to buffer
    for (const [metric, value] of Object.entries(update.metrics)) {
      if (!buffer.metrics[metric]) {
        buffer.metrics[metric] = [];
      }
      buffer.metrics[metric].push(value);
    }
  }

  private async flushAggregations(): Promise<void> {
    const now = new Date();

    for (const [key, buffer] of this.aggregationBuffers) {
      if (Object.keys(buffer.metrics).length === 0) continue;

      // Get subscriptions for this channel
      const subscriptions = this.subscriptionManager.getSubscriptionsForChannel(
        buffer.tenantId,
        buffer.channel,
      );

      if (subscriptions.length === 0) {
        // No subscribers, clear buffer
        buffer.metrics = {};
        continue;
      }

      // Aggregate metrics based on subscription preferences
      const aggregated = this.aggregateMetrics(buffer, subscriptions);

      // Increment sequence
      buffer.sequence++;

      // Broadcast to subscribers
      await this.gateway.broadcastUpdate(
        buffer.tenantId,
        buffer.channel,
        aggregated,
        buffer.sequence,
      );

      // Clear buffer
      buffer.metrics = {};
      buffer.lastFlush = now;
    }
  }

  private aggregateMetrics(
    buffer: AggregationBuffer,
    subscriptions: any[],
  ): Record<string, any> {
    const result: Record<string, any> = {};

    // Get unique metrics requested
    const requestedMetrics = new Set<string>();
    for (const sub of subscriptions) {
      for (const metric of sub.metrics) {
        requestedMetrics.add(metric);
      }
    }

    for (const metric of requestedMetrics) {
      const values = buffer.metrics[metric];
      if (!values || values.length === 0) continue;

      // Use LAST aggregation by default (most common for real-time)
      // In production, would consider subscription-specific aggregation
      result[metric] = {
        value: values[values.length - 1],
        count: values.length,
        delta: values.length > 1 ? values[values.length - 1] - values[0] : 0,
      };
    }

    return result;
  }

  async getSnapshot(
    tenantId: string,
    channel: string,
    metrics: string[],
    filters: Record<string, any>,
  ): Promise<Record<string, any>> {
    // Fetch current values from database/cache
    const snapshot: Record<string, any> = {};

    switch (channel) {
      case 'trading.metrics':
        Object.assign(snapshot, await this.getTradingMetrics(tenantId, filters));
        break;
      case 'positions':
        Object.assign(snapshot, await this.getPositionMetrics(tenantId, filters));
        break;
      case 'risk.metrics':
        Object.assign(snapshot, await this.getRiskMetrics(tenantId, filters));
        break;
      case 'pnl':
        Object.assign(snapshot, await this.getPnlMetrics(tenantId, filters));
        break;
    }

    // Filter to requested metrics
    const filtered: Record<string, any> = {};
    for (const metric of metrics) {
      if (snapshot[metric] !== undefined) {
        filtered[metric] = snapshot[metric];
      }
    }

    return filtered;
  }

  private async getTradingMetrics(
    tenantId: string,
    filters: Record<string, any>,
  ): Promise<Record<string, any>> {
    const today = new Date();
    today.setHours(0, 0, 0, 0);

    const result = await this.dataSource.query(`
      SELECT 
        COUNT(*) as trade_count,
        COALESCE(SUM(quantity), 0) as trade_volume,
        COALESCE(SUM(notional_value), 0) as trade_notional
      FROM fact_trades
      WHERE tenant_id = $1 AND trade_date >= $2
    `, [tenantId, today]);

    return {
      trade_count: parseInt(result[0].trade_count, 10),
      trade_volume: parseFloat(result[0].trade_volume),
      trade_notional: parseFloat(result[0].trade_notional),
    };
  }

  private async getPositionMetrics(
    tenantId: string,
    filters: Record<string, any>,
  ): Promise<Record<string, any>> {
    const result = await this.dataSource.query(`
      SELECT 
        COALESCE(SUM(market_value), 0) as position_value,
        COALESCE(SUM(unrealized_pnl), 0) as position_pnl
      FROM positions
      WHERE tenant_id = $1
    `, [tenantId]);

    return {
      position_value: parseFloat(result[0].position_value),
      position_pnl: parseFloat(result[0].position_pnl),
    };
  }

  private async getRiskMetrics(
    tenantId: string,
    filters: Record<string, any>,
  ): Promise<Record<string, any>> {
    const result = await this.dataSource.query(`
      SELECT 
        COALESCE(SUM(gross_exposure), 0) as exposure,
        COALESCE(AVG(utilization_pct), 0) as utilization,
        COALESCE(AVG(var_95), 0) as var
      FROM fact_risk_snapshots
      WHERE tenant_id = $1 AND snapshot_date = CURRENT_DATE
    `, [tenantId]);

    return {
      exposure: parseFloat(result[0].exposure),
      utilization: parseFloat(result[0].utilization),
      var: parseFloat(result[0].var),
    };
  }

  private async getPnlMetrics(
    tenantId: string,
    filters: Record<string, any>,
  ): Promise<Record<string, any>> {
    const result = await this.dataSource.query(`
      SELECT 
        COALESCE(SUM(realized_pnl), 0) as realized_pnl,
        COALESCE(SUM(unrealized_pnl), 0) as unrealized_pnl
      FROM positions
      WHERE tenant_id = $1
    `, [tenantId]);

    return {
      realized_pnl: parseFloat(result[0].realized_pnl),
      unrealized_pnl: parseFloat(result[0].unrealized_pnl),
      total_pnl: parseFloat(result[0].realized_pnl) + parseFloat(result[0].unrealized_pnl),
    };
  }

  private topicToChannel(topic: string): string {
    const mapping: Record<string, string> = {
      'analytics.trades': 'trading.metrics',
      'analytics.positions': 'positions',
      'analytics.risk': 'risk.metrics',
      'analytics.pnl': 'pnl',
    };
    return mapping[topic] || topic;
  }

  // Publish metric update (called by other services)
  async publishMetric(
    tenantId: string,
    channel: string,
    metrics: Record<string, number>,
  ): Promise<void> {
    const update: MetricUpdate = {
      channel,
      tenantId,
      metrics,
      timestamp: new Date(),
    };

    await this.processUpdate(update);
  }
}
```

### Analytics Stream Controller

```typescript
// src/analytics/websocket/controllers/analytics-stream.controller.ts
import { Controller, Get, UseGuards } from '@nestjs/common';
import { ApiTags, ApiOperation, ApiBearerAuth } from '@nestjs/swagger';
import { JwtAuthGuard } from '../../../auth/guards/jwt-auth.guard';
import { SubscriptionManager } from '../services/subscription-manager.service';
import { AnalyticsWebSocketGateway } from '../gateways/analytics-websocket.gateway';

@ApiTags('Analytics Stream')
@ApiBearerAuth()
@UseGuards(JwtAuthGuard)
@Controller('analytics/stream')
export class AnalyticsStreamController {
  constructor(
    private readonly subscriptionManager: SubscriptionManager,
    private readonly gateway: AnalyticsWebSocketGateway,
  ) {}

  @Get('status')
  @ApiOperation({ summary: 'Get stream status' })
  getStatus() {
    const stats = this.subscriptionManager.getStats();
    const connectionCount = this.gateway.getConnectionCount();

    return {
      status: 'healthy',
      connections: connectionCount,
      ...stats,
      timestamp: new Date().toISOString(),
    };
  }

  @Get('channels')
  @ApiOperation({ summary: 'List available channels' })
  getChannels() {
    return {
      channels: [
        {
          name: 'trading.metrics',
          description: 'Real-time trading metrics',
          metrics: ['trade_count', 'trade_volume', 'trade_notional'],
          requiredRoles: ['TRADER', 'ADMIN', 'ANALYST'],
        },
        {
          name: 'positions',
          description: 'Position updates',
          metrics: ['position_value', 'position_pnl'],
          requiredRoles: ['TRADER', 'RISK_MANAGER', 'ADMIN'],
        },
        {
          name: 'risk.metrics',
          description: 'Risk metrics updates',
          metrics: ['exposure', 'utilization', 'var'],
          requiredRoles: ['RISK_MANAGER', 'ADMIN', 'ANALYST'],
        },
        {
          name: 'pnl',
          description: 'P&L updates',
          metrics: ['realized_pnl', 'unrealized_pnl', 'total_pnl'],
          requiredRoles: ['TRADER', 'ADMIN', 'ANALYST'],
        },
      ],
    };
  }
}
```

## Database Schema

```sql
-- WebSocket connection tracking (for monitoring)
CREATE TABLE ws_connections (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id VARCHAR(100) UNIQUE NOT NULL,
    tenant_id UUID NOT NULL,
    user_id UUID NOT NULL,
    connected_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    disconnected_at TIMESTAMPTZ,
    subscriptions JSONB DEFAULT '[]',
    messages_sent INTEGER DEFAULT 0,
    messages_received INTEGER DEFAULT 0
);

CREATE INDEX idx_ws_connections_tenant ON ws_connections(tenant_id, connected_at);
CREATE INDEX idx_ws_connections_active ON ws_connections(disconnected_at) 
  WHERE disconnected_at IS NULL;

-- Metric stream audit (for debugging)
CREATE TABLE metric_stream_audit (
    time TIMESTAMPTZ NOT NULL,
    tenant_id UUID NOT NULL,
    channel VARCHAR(100) NOT NULL,
    subscriber_count INTEGER NOT NULL,
    metrics_sent JSONB NOT NULL
);

SELECT create_hypertable('metric_stream_audit', 'time', chunk_time_interval => INTERVAL '1 hour');

-- Retention policy
SELECT add_retention_policy('metric_stream_audit', INTERVAL '24 hours');
```

## Definition of Done

- [ ] WebSocket gateway with auth middleware
- [ ] Subscription management working
- [ ] Metric aggregation and batching
- [ ] Kafka consumer for metric events
- [ ] Initial snapshot on subscribe
- [ ] Channel-based permission checking
- [ ] Connection keep-alive handling
- [ ] Graceful disconnection cleanup
- [ ] Unit tests for subscription manager
- [ ] Integration tests for WebSocket

## Test Cases

### Unit Tests
```typescript
describe('SubscriptionManager', () => {
  it('should create and track subscriptions', async () => {
    const subId = await manager.subscribe(
      'conn-1',
      'tenant-1',
      'trading.metrics',
      ['trade_count', 'trade_volume'],
    );

    expect(subId).toBeDefined();
    
    const sub = manager.getSubscription(subId);
    expect(sub.metrics).toContain('trade_count');
  });

  it('should clean up on disconnect', async () => {
    await manager.subscribe('conn-1', 'tenant-1', 'trading.metrics', ['trade_count']);
    await manager.subscribe('conn-1', 'tenant-1', 'positions', ['position_value']);
    
    await manager.removeAllForConnection('conn-1');
    
    const stats = manager.getStats();
    expect(stats.totalSubscriptions).toBe(0);
  });
});

describe('MetricStreamService', () => {
  it('should aggregate metrics correctly', async () => {
    await service.publishMetric('tenant-1', 'trading.metrics', { trade_count: 1 });
    await service.publishMetric('tenant-1', 'trading.metrics', { trade_count: 1 });
    await service.publishMetric('tenant-1', 'trading.metrics', { trade_count: 1 });
    
    // After flush
    await sleep(1100);
    
    // Verify aggregation sent to gateway
    expect(mockGateway.broadcastUpdate).toHaveBeenCalledWith(
      'tenant-1',
      'trading.metrics',
      expect.objectContaining({
        trade_count: expect.objectContaining({ count: 3 }),
      }),
      expect.any(Number),
    );
  });
});
```

### WebSocket Integration Tests
```typescript
describe('Analytics WebSocket', () => {
  it('should authenticate and receive updates', async () => {
    const client = io('http://localhost:3000/analytics/stream', {
      auth: { token: validJwtToken },
    });

    await new Promise((resolve) => client.on('connected', resolve));

    client.emit('subscribe', {
      channel: 'trading.metrics',
      metrics: ['trade_count'],
    });

    const snapshot = await new Promise((resolve) => 
      client.on('snapshot', resolve)
    );

    expect(snapshot.channel).toBe('trading.metrics');
    expect(snapshot.data.trade_count).toBeDefined();

    client.disconnect();
  });

  it('should reject unauthorized connections', async () => {
    const client = io('http://localhost:3000/analytics/stream', {
      auth: { token: 'invalid' },
    });

    const error = await new Promise((resolve) =>
      client.on('connect_error', resolve)
    );

    expect(error.message).toContain('Authentication failed');
  });
});
```
