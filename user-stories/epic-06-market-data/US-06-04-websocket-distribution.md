# User Story: US-06-04 - Market Data Distribution (WebSocket)

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-06-04 |
| **Epic** | Epic 06 - Market Data System |
| **Title** | Market Data Distribution (WebSocket) |
| **Priority** | P0 - Critical |
| **Story Points** | 8 |
| **PRD Reference** | FR-MD-03 |

## User Story

**As a** frontend developer  
**I want** to receive real-time market data via WebSocket  
**So that** I can display live prices to users with minimal latency

## Description

Build a WebSocket distribution layer that streams market data to clients with subscription management, authentication, and configurable update rates.

## Acceptance Criteria

- [ ] WebSocket endpoint with JWT authentication
- [ ] Symbol subscription/unsubscription
- [ ] Heartbeat and connection health
- [ ] Rate limiting per client
- [ ] Tenant isolation
- [ ] Graceful connection handling

## Technical Details

### WebSocket Gateway

```typescript
// services/market-data-service/src/distribution/market-data.gateway.ts
import {
  WebSocketGateway,
  WebSocketServer,
  SubscribeMessage,
  OnGatewayConnection,
  OnGatewayDisconnect,
  OnGatewayInit,
  ConnectedSocket,
  MessageBody,
} from '@nestjs/websockets';
import { Server, Socket } from 'socket.io';
import { UseGuards } from '@nestjs/common';
import { JwtService } from '@nestjs/jwt';
import { logger, metrics } from '@orion/observability';
import { WsJwtGuard } from '../guards/ws-jwt.guard';
import { SubscriptionManager } from './subscription-manager.service';
import { BestPrice } from '../aggregation/aggregated-price.interface';

interface ClientContext {
  tenantId: string;
  userId: string;
  subscriptions: Set<string>;
  lastActivity: number;
  rateLimit: {
    tokens: number;
    lastRefill: number;
  };
}

@WebSocketGateway({
  namespace: '/market-data',
  cors: {
    origin: process.env.CORS_ORIGINS?.split(',') || ['http://localhost:3000'],
    credentials: true,
  },
  transports: ['websocket', 'polling'],
})
export class MarketDataGateway
  implements OnGatewayInit, OnGatewayConnection, OnGatewayDisconnect
{
  @WebSocketServer()
  private server: Server;

  private readonly clients = new Map<string, ClientContext>();
  private readonly maxSubscriptionsPerClient = 100;
  private readonly rateLimitTokens = 100;
  private readonly rateLimitRefillMs = 1000;

  constructor(
    private readonly jwtService: JwtService,
    private readonly subscriptionManager: SubscriptionManager,
  ) {}

  afterInit() {
    logger.info('Market data WebSocket gateway initialized');
    
    // Start heartbeat
    setInterval(() => this.sendHeartbeats(), 30000);
    
    // Start rate limit refill
    setInterval(() => this.refillRateLimits(), this.rateLimitRefillMs);
  }

  async handleConnection(client: Socket) {
    try {
      // Extract and verify JWT from handshake
      const token = this.extractToken(client);
      if (!token) {
        client.emit('error', { code: 'UNAUTHORIZED', message: 'No token provided' });
        client.disconnect();
        return;
      }

      const payload = await this.jwtService.verifyAsync(token);
      
      // Initialize client context
      const context: ClientContext = {
        tenantId: payload.tenantId,
        userId: payload.sub,
        subscriptions: new Set(),
        lastActivity: Date.now(),
        rateLimit: {
          tokens: this.rateLimitTokens,
          lastRefill: Date.now(),
        },
      };

      this.clients.set(client.id, context);

      // Join tenant room for broadcasts
      client.join(`tenant:${payload.tenantId}`);

      logger.info('Client connected', {
        clientId: client.id,
        tenantId: payload.tenantId,
        userId: payload.sub,
      });

      metrics.increment('ws.connections');
      metrics.gauge('ws.active_connections', this.clients.size);

      // Send connection acknowledgment
      client.emit('connected', {
        clientId: client.id,
        serverTime: Date.now(),
      });

    } catch (error) {
      logger.error('Connection failed', { error, clientId: client.id });
      client.emit('error', { code: 'AUTH_FAILED', message: 'Authentication failed' });
      client.disconnect();
    }
  }

  handleDisconnect(client: Socket) {
    const context = this.clients.get(client.id);
    
    if (context) {
      // Unsubscribe from all symbols
      for (const symbol of context.subscriptions) {
        this.subscriptionManager.unsubscribe(client.id, symbol);
      }
      
      this.clients.delete(client.id);

      logger.info('Client disconnected', {
        clientId: client.id,
        tenantId: context.tenantId,
      });
    }

    metrics.decrement('ws.connections');
    metrics.gauge('ws.active_connections', this.clients.size);
  }

  @SubscribeMessage('subscribe')
  async handleSubscribe(
    @ConnectedSocket() client: Socket,
    @MessageBody() data: { symbols: string[] },
  ) {
    const context = this.clients.get(client.id);
    if (!context) {
      return { error: 'Not authenticated' };
    }

    // Rate limiting
    if (!this.checkRateLimit(context)) {
      return { error: 'Rate limit exceeded' };
    }

    const { symbols } = data;
    const subscribed: string[] = [];
    const errors: string[] = [];

    for (const symbol of symbols) {
      // Check subscription limit
      if (context.subscriptions.size >= this.maxSubscriptionsPerClient) {
        errors.push(`${symbol}: max subscriptions reached`);
        continue;
      }

      // Validate symbol exists
      if (!this.subscriptionManager.isValidSymbol(symbol)) {
        errors.push(`${symbol}: invalid symbol`);
        continue;
      }

      // Add subscription
      context.subscriptions.add(symbol);
      this.subscriptionManager.subscribe(client.id, symbol);
      
      // Join symbol room
      client.join(`symbol:${symbol}`);
      subscribed.push(symbol);
    }

    logger.debug('Subscription request', {
      clientId: client.id,
      subscribed,
      errors,
    });

    metrics.increment('ws.subscriptions', { count: String(subscribed.length) });

    // Send current prices for subscribed symbols
    const currentPrices = await this.subscriptionManager.getCurrentPrices(subscribed);
    if (currentPrices.length > 0) {
      client.emit('snapshot', { prices: currentPrices });
    }

    return { subscribed, errors: errors.length > 0 ? errors : undefined };
  }

  @SubscribeMessage('unsubscribe')
  handleUnsubscribe(
    @ConnectedSocket() client: Socket,
    @MessageBody() data: { symbols: string[] },
  ) {
    const context = this.clients.get(client.id);
    if (!context) {
      return { error: 'Not authenticated' };
    }

    const { symbols } = data;
    const unsubscribed: string[] = [];

    for (const symbol of symbols) {
      if (context.subscriptions.has(symbol)) {
        context.subscriptions.delete(symbol);
        this.subscriptionManager.unsubscribe(client.id, symbol);
        client.leave(`symbol:${symbol}`);
        unsubscribed.push(symbol);
      }
    }

    logger.debug('Unsubscription request', {
      clientId: client.id,
      unsubscribed,
    });

    return { unsubscribed };
  }

  @SubscribeMessage('ping')
  handlePing(@ConnectedSocket() client: Socket) {
    const context = this.clients.get(client.id);
    if (context) {
      context.lastActivity = Date.now();
    }
    return { pong: Date.now() };
  }

  /**
   * Broadcast price update to subscribed clients
   */
  broadcastPrice(price: BestPrice): void {
    this.server.to(`symbol:${price.symbol}`).emit('price', price);
    metrics.increment('ws.broadcasts', { symbol: price.symbol });
  }

  /**
   * Broadcast to specific tenant
   */
  broadcastToTenant(tenantId: string, event: string, data: unknown): void {
    this.server.to(`tenant:${tenantId}`).emit(event, data);
  }

  private extractToken(client: Socket): string | null {
    // Try auth header
    const authHeader = client.handshake.headers.authorization;
    if (authHeader?.startsWith('Bearer ')) {
      return authHeader.substring(7);
    }

    // Try query parameter
    return client.handshake.query.token as string || null;
  }

  private checkRateLimit(context: ClientContext): boolean {
    if (context.rateLimit.tokens <= 0) {
      return false;
    }
    context.rateLimit.tokens--;
    return true;
  }

  private refillRateLimits(): void {
    const now = Date.now();
    
    for (const context of this.clients.values()) {
      const elapsed = now - context.rateLimit.lastRefill;
      const tokensToAdd = Math.floor(elapsed / this.rateLimitRefillMs) * 10;
      
      if (tokensToAdd > 0) {
        context.rateLimit.tokens = Math.min(
          context.rateLimit.tokens + tokensToAdd,
          this.rateLimitTokens,
        );
        context.rateLimit.lastRefill = now;
      }
    }
  }

  private sendHeartbeats(): void {
    const now = Date.now();
    
    for (const [clientId, context] of this.clients) {
      // Check for inactive clients (no activity in 2 minutes)
      if (now - context.lastActivity > 120000) {
        const client = this.server.sockets.sockets.get(clientId);
        if (client) {
          logger.info('Disconnecting inactive client', { clientId });
          client.disconnect();
        }
      }
    }

    // Send heartbeat to all connected clients
    this.server.emit('heartbeat', { timestamp: now });
  }

  /**
   * Get connection statistics
   */
  getStats(): {
    totalConnections: number;
    subscriptionsBySymbol: Map<string, number>;
  } {
    const subscriptionsBySymbol = new Map<string, number>();
    
    for (const context of this.clients.values()) {
      for (const symbol of context.subscriptions) {
        const count = subscriptionsBySymbol.get(symbol) || 0;
        subscriptionsBySymbol.set(symbol, count + 1);
      }
    }

    return {
      totalConnections: this.clients.size,
      subscriptionsBySymbol,
    };
  }
}
```

### Price Broadcast Service

```typescript
// services/market-data-service/src/distribution/price-broadcast.service.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { EventConsumerService } from '@orion/event-model';
import { logger, metrics } from '@orion/observability';
import { MarketDataGateway } from './market-data.gateway';
import { BestPrice } from '../aggregation/aggregated-price.interface';

@Injectable()
export class PriceBroadcastService implements OnModuleInit {
  constructor(
    private readonly consumer: EventConsumerService,
    private readonly gateway: MarketDataGateway,
  ) {}

  async onModuleInit() {
    // Subscribe to aggregated prices and broadcast to WebSocket clients
    this.consumer.registerHandler({
      eventTypes: ['market-data.best-price'],
      handle: async (event) => {
        const price = event.payload as BestPrice;
        this.gateway.broadcastPrice(price);
      },
    });

    logger.info('Price broadcast service started');
  }
}
```

### WebSocket JWT Guard

```typescript
// services/market-data-service/src/guards/ws-jwt.guard.ts
import { CanActivate, ExecutionContext, Injectable } from '@nestjs/common';
import { JwtService } from '@nestjs/jwt';
import { WsException } from '@nestjs/websockets';
import { Socket } from 'socket.io';

@Injectable()
export class WsJwtGuard implements CanActivate {
  constructor(private readonly jwtService: JwtService) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    try {
      const client: Socket = context.switchToWs().getClient();
      const token = this.extractToken(client);

      if (!token) {
        throw new WsException('Unauthorized');
      }

      const payload = await this.jwtService.verifyAsync(token);
      client.data.user = payload;
      
      return true;
    } catch (error) {
      throw new WsException('Unauthorized');
    }
  }

  private extractToken(client: Socket): string | null {
    const authHeader = client.handshake.headers.authorization;
    if (authHeader?.startsWith('Bearer ')) {
      return authHeader.substring(7);
    }
    return client.handshake.query.token as string || null;
  }
}
```

### Client SDK (TypeScript)

```typescript
// packages/market-data-client/src/index.ts
import { io, Socket } from 'socket.io-client';

export interface MarketDataClientOptions {
  url: string;
  token: string;
  reconnection?: boolean;
  reconnectionAttempts?: number;
  reconnectionDelay?: number;
}

export interface BestPrice {
  symbol: string;
  bestBid: { price: number; size: number; source: string };
  bestAsk: { price: number; size: number; source: string };
  midPrice: number;
  spread: number;
  spreadBps: number;
  timestamp: number;
}

export type PriceCallback = (price: BestPrice) => void;
export type ErrorCallback = (error: { code: string; message: string }) => void;

export class MarketDataClient {
  private socket: Socket;
  private priceCallbacks = new Map<string, Set<PriceCallback>>();
  private connected = false;

  constructor(private readonly options: MarketDataClientOptions) {
    this.socket = io(`${options.url}/market-data`, {
      auth: { token: options.token },
      query: { token: options.token },
      transports: ['websocket'],
      reconnection: options.reconnection ?? true,
      reconnectionAttempts: options.reconnectionAttempts ?? 10,
      reconnectionDelay: options.reconnectionDelay ?? 1000,
    });

    this.setupEventHandlers();
  }

  private setupEventHandlers(): void {
    this.socket.on('connect', () => {
      this.connected = true;
      console.log('Connected to market data service');
    });

    this.socket.on('disconnect', (reason) => {
      this.connected = false;
      console.log('Disconnected:', reason);
    });

    this.socket.on('error', (error) => {
      console.error('Socket error:', error);
    });

    this.socket.on('price', (price: BestPrice) => {
      const callbacks = this.priceCallbacks.get(price.symbol);
      if (callbacks) {
        for (const callback of callbacks) {
          try {
            callback(price);
          } catch (error) {
            console.error('Price callback error:', error);
          }
        }
      }
    });

    this.socket.on('snapshot', (data: { prices: BestPrice[] }) => {
      for (const price of data.prices) {
        const callbacks = this.priceCallbacks.get(price.symbol);
        if (callbacks) {
          for (const callback of callbacks) {
            callback(price);
          }
        }
      }
    });

    this.socket.on('heartbeat', () => {
      // Connection is alive
    });
  }

  /**
   * Subscribe to price updates for symbols
   */
  async subscribe(
    symbols: string[],
    callback: PriceCallback,
  ): Promise<{ subscribed: string[]; errors?: string[] }> {
    // Register callbacks
    for (const symbol of symbols) {
      if (!this.priceCallbacks.has(symbol)) {
        this.priceCallbacks.set(symbol, new Set());
      }
      this.priceCallbacks.get(symbol)!.add(callback);
    }

    // Send subscription request
    return new Promise((resolve) => {
      this.socket.emit('subscribe', { symbols }, (response: any) => {
        resolve(response);
      });
    });
  }

  /**
   * Unsubscribe from symbols
   */
  async unsubscribe(symbols: string[]): Promise<{ unsubscribed: string[] }> {
    // Remove callbacks
    for (const symbol of symbols) {
      this.priceCallbacks.delete(symbol);
    }

    return new Promise((resolve) => {
      this.socket.emit('unsubscribe', { symbols }, (response: any) => {
        resolve(response);
      });
    });
  }

  /**
   * Remove a specific callback
   */
  removeCallback(symbol: string, callback: PriceCallback): void {
    const callbacks = this.priceCallbacks.get(symbol);
    if (callbacks) {
      callbacks.delete(callback);
      if (callbacks.size === 0) {
        this.priceCallbacks.delete(symbol);
      }
    }
  }

  /**
   * Check connection status
   */
  isConnected(): boolean {
    return this.connected;
  }

  /**
   * Disconnect from server
   */
  disconnect(): void {
    this.socket.disconnect();
    this.priceCallbacks.clear();
  }
}

// React Hook
export function useMarketData(
  client: MarketDataClient,
  symbols: string[],
): Map<string, BestPrice> {
  const [prices, setPrices] = React.useState<Map<string, BestPrice>>(new Map());

  React.useEffect(() => {
    const callback: PriceCallback = (price) => {
      setPrices(prev => new Map(prev).set(price.symbol, price));
    };

    client.subscribe(symbols, callback);

    return () => {
      client.unsubscribe(symbols);
    };
  }, [client, symbols.join(',')]);

  return prices;
}
```

### Server-Sent Events Alternative

```typescript
// services/market-data-service/src/distribution/sse.controller.ts
import { Controller, Get, Sse, Query, UseGuards, Req } from '@nestjs/common';
import { Observable, Subject, interval, map, takeUntil } from 'rxjs';
import { JwtAuthGuard } from '@orion/security';
import { PriceCacheService } from '../aggregation/price-cache.service';
import { BestPrice } from '../aggregation/aggregated-price.interface';

interface SseMessage {
  data: string;
  type?: string;
  id?: string;
}

@Controller('market-data')
export class SseController {
  private readonly priceUpdates = new Subject<BestPrice>();

  constructor(private readonly priceCache: PriceCacheService) {}

  @Get('stream')
  @UseGuards(JwtAuthGuard)
  @Sse()
  stream(
    @Query('symbols') symbolsParam: string,
    @Req() request: any,
  ): Observable<SseMessage> {
    const symbols = symbolsParam?.split(',') || [];
    const closed = new Subject<void>();

    // Handle client disconnect
    request.on('close', () => {
      closed.next();
      closed.complete();
    });

    return this.priceUpdates.pipe(
      takeUntil(closed),
      map((price) => {
        if (!symbols.includes(price.symbol)) {
          return null;
        }
        return {
          data: JSON.stringify(price),
          type: 'price',
          id: `${price.symbol}-${price.timestamp}`,
        };
      }),
      // Filter null values
      map(msg => msg!),
    );
  }

  /**
   * Called by price broadcast service to push updates
   */
  pushPrice(price: BestPrice): void {
    this.priceUpdates.next(price);
  }
}
```

## Definition of Done

- [ ] WebSocket gateway implemented
- [ ] JWT authentication working
- [ ] Subscription management functional
- [ ] Rate limiting active
- [ ] Heartbeat mechanism working
- [ ] Client SDK published
- [ ] SSE alternative available
- [ ] Tests pass

## Dependencies

- **US-06-03**: Best Price Aggregation Engine
- **US-02-03**: JWT Token Validation

## Test Cases

```typescript
describe('MarketDataGateway', () => {
  it('should authenticate client with valid token', async () => {
    const token = generateValidToken();
    
    const client = await connectClient(token);
    
    expect(client.connected).toBe(true);
  });

  it('should reject client with invalid token', async () => {
    const client = await connectClient('invalid-token');
    
    expect(client.connected).toBe(false);
  });

  it('should receive price updates for subscribed symbols', async () => {
    const client = await connectClient(validToken);
    const prices: BestPrice[] = [];
    
    client.on('price', (price) => prices.push(price));
    await client.emit('subscribe', { symbols: ['EUR/USD'] });

    // Simulate price broadcast
    gateway.broadcastPrice(mockPrice('EUR/USD'));

    expect(prices).toHaveLength(1);
    expect(prices[0].symbol).toBe('EUR/USD');
  });

  it('should enforce rate limits', async () => {
    const client = await connectClient(validToken);
    
    // Exhaust rate limit
    for (let i = 0; i < 150; i++) {
      await client.emit('subscribe', { symbols: [`SYM${i}`] });
    }

    const response = await client.emit('subscribe', { symbols: ['EXTRA'] });
    
    expect(response.error).toBe('Rate limit exceeded');
  });
});
```
