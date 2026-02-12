# User Story: US-07-06 - RFQ Real-Time Event Streaming

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-07-06 |
| **Epic** | Epic 07 - RFQ Workflow |
| **Title** | RFQ Real-Time Event Streaming |
| **Priority** | P1 - High |
| **Story Points** | 5 |
| **PRD Reference** | FR-RFQ-06 |

## User Story

**As a** trader  
**I want** to receive real-time updates on my RFQs  
**So that** I can monitor quote arrivals, price changes, and execution status without polling

## Description

Implement WebSocket-based real-time RFQ streaming including RFQ state changes, quote arrivals, quote updates, countdown timers, and execution notifications.

## Acceptance Criteria

- [ ] WebSocket subscription to RFQ updates
- [ ] Real-time quote arrival notifications
- [ ] Quote price/ranking updates
- [ ] RFQ state change events
- [ ] Expiry countdown streaming
- [ ] Execution result notifications
- [ ] Support for multiple concurrent RFQ subscriptions

## Technical Details

### RFQ WebSocket Gateway

```typescript
// services/rfq-service/src/streaming/rfq-streaming.gateway.ts
import {
  WebSocketGateway,
  WebSocketServer,
  SubscribeMessage,
  OnGatewayInit,
  OnGatewayConnection,
  OnGatewayDisconnect,
  ConnectedSocket,
  MessageBody,
} from '@nestjs/websockets';
import { Server, Socket } from 'socket.io';
import { UseGuards } from '@nestjs/common';
import { WsJwtGuard, WsUser } from '@orion/security';
import { logger, metrics } from '@orion/observability';
import { RfqStreamingService } from './rfq-streaming.service';

interface SubscribeRfqPayload {
  rfqId: string;
}

interface SubscribeTraderPayload {
  traderId?: string;  // Defaults to current user
}

@WebSocketGateway({
  namespace: '/rfq',
  cors: {
    origin: process.env.CORS_ORIGINS?.split(',') || '*',
    credentials: true,
  },
})
export class RfqStreamingGateway implements OnGatewayInit, OnGatewayConnection, OnGatewayDisconnect {
  @WebSocketServer()
  server: Server;

  private clientRfqSubscriptions: Map<string, Set<string>> = new Map();
  private rfqSubscribers: Map<string, Set<string>> = new Map();
  private traderSubscribers: Map<string, Set<string>> = new Map();

  constructor(private readonly streamingService: RfqStreamingService) {}

  afterInit(): void {
    logger.info('RFQ WebSocket gateway initialized');
  }

  async handleConnection(client: Socket): Promise<void> {
    try {
      const user = await this.streamingService.authenticateClient(client);
      client.data.user = user;
      client.data.tenantId = user.tenantId;
      
      // Join tenant room
      client.join(`tenant:${user.tenantId}`);
      
      this.clientRfqSubscriptions.set(client.id, new Set());
      
      logger.info('RFQ WebSocket client connected', { 
        clientId: client.id, 
        userId: user.id 
      });
      metrics.increment('rfq.ws.connected');
    } catch (error) {
      logger.warn('RFQ WebSocket auth failed', { error });
      client.disconnect(true);
    }
  }

  handleDisconnect(client: Socket): void {
    // Clean up subscriptions
    const subscriptions = this.clientRfqSubscriptions.get(client.id);
    if (subscriptions) {
      for (const rfqId of subscriptions) {
        this.unsubscribeFromRfq(client.id, rfqId);
      }
    }
    this.clientRfqSubscriptions.delete(client.id);

    // Clean up trader subscriptions
    const traderId = client.data.user?.id;
    if (traderId) {
      const clients = this.traderSubscribers.get(traderId);
      clients?.delete(client.id);
    }

    logger.info('RFQ WebSocket client disconnected', { clientId: client.id });
    metrics.increment('rfq.ws.disconnected');
  }

  @UseGuards(WsJwtGuard)
  @SubscribeMessage('subscribe:rfq')
  async handleSubscribeRfq(
    @ConnectedSocket() client: Socket,
    @MessageBody() payload: SubscribeRfqPayload,
  ): Promise<{ success: boolean; rfq?: any }> {
    const { rfqId } = payload;
    const user = client.data.user;

    // Verify access
    const hasAccess = await this.streamingService.verifyRfqAccess(
      rfqId, 
      user.tenantId, 
      user.id
    );

    if (!hasAccess) {
      return { success: false };
    }

    // Add to subscription maps
    this.subscribeToRfq(client.id, rfqId);
    client.join(`rfq:${rfqId}`);

    // Get current state
    const rfqState = await this.streamingService.getRfqState(rfqId);

    logger.debug('Client subscribed to RFQ', { clientId: client.id, rfqId });

    return { success: true, rfq: rfqState };
  }

  @SubscribeMessage('unsubscribe:rfq')
  handleUnsubscribeRfq(
    @ConnectedSocket() client: Socket,
    @MessageBody() payload: SubscribeRfqPayload,
  ): { success: boolean } {
    const { rfqId } = payload;
    
    this.unsubscribeFromRfq(client.id, rfqId);
    client.leave(`rfq:${rfqId}`);

    return { success: true };
  }

  @UseGuards(WsJwtGuard)
  @SubscribeMessage('subscribe:trader')
  async handleSubscribeTrader(
    @ConnectedSocket() client: Socket,
    @MessageBody() payload: SubscribeTraderPayload,
  ): Promise<{ success: boolean }> {
    const traderId = payload.traderId || client.data.user.id;

    // Verify access (can only subscribe to own events or with permission)
    if (traderId !== client.data.user.id) {
      const canView = await this.streamingService.canViewTraderRfqs(
        client.data.user.id,
        traderId,
      );
      if (!canView) {
        return { success: false };
      }
    }

    // Add to trader subscribers
    if (!this.traderSubscribers.has(traderId)) {
      this.traderSubscribers.set(traderId, new Set());
    }
    this.traderSubscribers.get(traderId)!.add(client.id);

    client.join(`trader:${traderId}`);

    return { success: true };
  }

  // Broadcast methods called by event handlers
  broadcastRfqUpdate(rfqId: string, event: string, data: any): void {
    this.server.to(`rfq:${rfqId}`).emit(event, data);
  }

  broadcastTraderEvent(traderId: string, event: string, data: any): void {
    this.server.to(`trader:${traderId}`).emit(event, data);
  }

  broadcastToTenant(tenantId: string, event: string, data: any): void {
    this.server.to(`tenant:${tenantId}`).emit(event, data);
  }

  private subscribeToRfq(clientId: string, rfqId: string): void {
    // Track client's subscriptions
    const clientSubs = this.clientRfqSubscriptions.get(clientId);
    clientSubs?.add(rfqId);

    // Track RFQ's subscribers
    if (!this.rfqSubscribers.has(rfqId)) {
      this.rfqSubscribers.set(rfqId, new Set());
    }
    this.rfqSubscribers.get(rfqId)!.add(clientId);
  }

  private unsubscribeFromRfq(clientId: string, rfqId: string): void {
    const clientSubs = this.clientRfqSubscriptions.get(clientId);
    clientSubs?.delete(rfqId);

    const rfqClients = this.rfqSubscribers.get(rfqId);
    rfqClients?.delete(clientId);

    // Clean up empty sets
    if (rfqClients?.size === 0) {
      this.rfqSubscribers.delete(rfqId);
    }
  }
}
```

### RFQ Streaming Service

```typescript
// services/rfq-service/src/streaming/rfq-streaming.service.ts
import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Socket } from 'socket.io';
import { JwtService } from '@nestjs/jwt';
import { RfqEntity } from '../entities/rfq.entity';
import { QuoteEntity } from '../entities/quote.entity';

export interface RfqState {
  rfq: {
    id: string;
    status: string;
    symbol: string;
    side: string;
    quantity: number;
    expiresAt: Date;
    timeRemaining: number;
  };
  quotes: {
    id: string;
    lpId: string;
    lpName: string;
    bidPrice?: number;
    askPrice?: number;
    ranking: number;
    isBestBid: boolean;
    isBestAsk: boolean;
    status: string;
  }[];
  composite: {
    bestBid?: number;
    bestAsk?: number;
    spread?: number;
    quoteCount: number;
  };
}

@Injectable()
export class RfqStreamingService {
  constructor(
    @InjectRepository(RfqEntity)
    private readonly rfqRepository: Repository<RfqEntity>,
    @InjectRepository(QuoteEntity)
    private readonly quoteRepository: Repository<QuoteEntity>,
    private readonly jwtService: JwtService,
  ) {}

  async authenticateClient(client: Socket): Promise<any> {
    const token = client.handshake.auth?.token || 
                  client.handshake.headers?.authorization?.replace('Bearer ', '');

    if (!token) {
      throw new Error('No token provided');
    }

    return this.jwtService.verify(token);
  }

  async verifyRfqAccess(
    rfqId: string, 
    tenantId: string, 
    userId: string
  ): Promise<boolean> {
    const rfq = await this.rfqRepository.findOne({
      where: { id: rfqId, tenantId },
    });

    return !!rfq;
  }

  async canViewTraderRfqs(viewerId: string, traderId: string): Promise<boolean> {
    // Implement permission check - managers can view team RFQs
    return viewerId === traderId;
  }

  async getRfqState(rfqId: string): Promise<RfqState> {
    const rfq = await this.rfqRepository.findOne({ where: { id: rfqId } });
    
    if (!rfq) {
      throw new Error('RFQ not found');
    }

    const quotes = await this.quoteRepository.find({
      where: { rfqId },
      order: { ranking: 'ASC' },
    });

    const now = Date.now();
    const timeRemaining = Math.max(0, rfq.expiresAt.getTime() - now);

    return {
      rfq: {
        id: rfq.id,
        status: rfq.status,
        symbol: rfq.symbol,
        side: rfq.side,
        quantity: Number(rfq.quantity),
        expiresAt: rfq.expiresAt,
        timeRemaining: Math.floor(timeRemaining / 1000),
      },
      quotes: quotes.map(q => ({
        id: q.id,
        lpId: q.lpId,
        lpName: '',  // Would join with LP table
        bidPrice: q.bidPrice ? Number(q.bidPrice) : undefined,
        askPrice: q.askPrice ? Number(q.askPrice) : undefined,
        ranking: q.ranking || 0,
        isBestBid: q.isBestBid,
        isBestAsk: q.isBestAsk,
        status: q.status,
      })),
      composite: this.calculateComposite(quotes),
    };
  }

  private calculateComposite(quotes: QuoteEntity[]): RfqState['composite'] {
    const validQuotes = quotes.filter(q => q.status === 'valid');
    
    if (validQuotes.length === 0) {
      return { quoteCount: 0 };
    }

    const bids = validQuotes.filter(q => q.bidPrice).map(q => Number(q.bidPrice));
    const asks = validQuotes.filter(q => q.askPrice).map(q => Number(q.askPrice));

    const bestBid = bids.length > 0 ? Math.max(...bids) : undefined;
    const bestAsk = asks.length > 0 ? Math.min(...asks) : undefined;

    return {
      bestBid,
      bestAsk,
      spread: bestBid && bestAsk ? bestAsk - bestBid : undefined,
      quoteCount: validQuotes.length,
    };
  }
}
```

### RFQ Event Broadcasting Handler

```typescript
// services/rfq-service/src/streaming/rfq-event-broadcaster.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { OnEvent } from '@nestjs/event-emitter';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { RfqStreamingGateway } from './rfq-streaming.gateway';
import { RfqEntity } from '../entities/rfq.entity';

interface RfqEvent {
  rfqId: string;
  tenantId?: string;
  traderId?: string;
}

@Injectable()
export class RfqEventBroadcaster {
  constructor(
    private readonly gateway: RfqStreamingGateway,
    @InjectRepository(RfqEntity)
    private readonly rfqRepository: Repository<RfqEntity>,
  ) {}

  @OnEvent('rfq.created')
  async handleRfqCreated(event: RfqEvent & { symbol: string; side: string; quantity: number }) {
    const rfq = await this.rfqRepository.findOne({ where: { id: event.rfqId } });
    if (!rfq) return;

    // Notify trader
    this.gateway.broadcastTraderEvent(rfq.traderId, 'rfq:created', {
      rfqId: event.rfqId,
      symbol: event.symbol,
      side: event.side,
      quantity: event.quantity,
      status: 'pending',
    });
  }

  @OnEvent('rfq.validated')
  async handleRfqValidated(event: RfqEvent) {
    this.gateway.broadcastRfqUpdate(event.rfqId, 'rfq:status', {
      rfqId: event.rfqId,
      status: 'validated',
    });
  }

  @OnEvent('rfq.distributed')
  async handleRfqDistributed(event: RfqEvent & { lpCount: number }) {
    this.gateway.broadcastRfqUpdate(event.rfqId, 'rfq:distributed', {
      rfqId: event.rfqId,
      status: 'distributed',
      lpCount: event.lpCount,
    });
  }

  @OnEvent('quote.received')
  handleQuoteReceived(event: {
    rfqId: string;
    quoteId: string;
    lpId: string;
    bidPrice?: number;
    askPrice?: number;
    status: string;
  }) {
    this.gateway.broadcastRfqUpdate(event.rfqId, 'quote:received', {
      quoteId: event.quoteId,
      lpId: event.lpId,
      bidPrice: event.bidPrice,
      askPrice: event.askPrice,
      status: event.status,
    });
  }

  @OnEvent('quotes.ranked')
  handleQuotesRanked(event: { rfqId: string; rankings: any[] }) {
    this.gateway.broadcastRfqUpdate(event.rfqId, 'quotes:ranked', {
      rfqId: event.rfqId,
      rankings: event.rankings,
    });
  }

  @OnEvent('quote.expired')
  handleQuoteExpired(event: { rfqId: string; quoteId: string; lpId: string }) {
    this.gateway.broadcastRfqUpdate(event.rfqId, 'quote:expired', event);
  }

  @OnEvent('rfq.quoted')
  handleRfqQuoted(event: RfqEvent) {
    this.gateway.broadcastRfqUpdate(event.rfqId, 'rfq:status', {
      rfqId: event.rfqId,
      status: 'quoted',
    });
  }

  @OnEvent('rfq.filled')
  async handleRfqFilled(event: RfqEvent & { 
    tradeId: string; 
    price: number; 
    quantity: number 
  }) {
    const rfq = await this.rfqRepository.findOne({ where: { id: event.rfqId } });
    if (!rfq) return;

    this.gateway.broadcastRfqUpdate(event.rfqId, 'rfq:filled', {
      rfqId: event.rfqId,
      tradeId: event.tradeId,
      price: event.price,
      quantity: event.quantity,
    });

    this.gateway.broadcastTraderEvent(rfq.traderId, 'rfq:filled', {
      rfqId: event.rfqId,
      symbol: rfq.symbol,
      tradeId: event.tradeId,
      price: event.price,
    });
  }

  @OnEvent('rfq.expired')
  async handleRfqExpired(event: RfqEvent) {
    const rfq = await this.rfqRepository.findOne({ where: { id: event.rfqId } });
    if (!rfq) return;

    this.gateway.broadcastRfqUpdate(event.rfqId, 'rfq:expired', {
      rfqId: event.rfqId,
    });

    this.gateway.broadcastTraderEvent(rfq.traderId, 'rfq:expired', {
      rfqId: event.rfqId,
      symbol: rfq.symbol,
    });
  }

  @OnEvent('rfq.cancelled')
  async handleRfqCancelled(event: RfqEvent & { reason: string }) {
    const rfq = await this.rfqRepository.findOne({ where: { id: event.rfqId } });
    if (!rfq) return;

    this.gateway.broadcastRfqUpdate(event.rfqId, 'rfq:cancelled', {
      rfqId: event.rfqId,
      reason: event.reason,
    });

    this.gateway.broadcastTraderEvent(rfq.traderId, 'rfq:cancelled', {
      rfqId: event.rfqId,
      symbol: rfq.symbol,
      reason: event.reason,
    });
  }

  @OnEvent('rfq.extended')
  handleRfqExtended(event: { rfqId: string; newExpiresAt: Date }) {
    this.gateway.broadcastRfqUpdate(event.rfqId, 'rfq:extended', {
      rfqId: event.rfqId,
      newExpiresAt: event.newExpiresAt,
    });
  }
}
```

### Countdown Timer Service

```typescript
// services/rfq-service/src/streaming/countdown-timer.service.ts
import { Injectable, OnModuleInit, OnModuleDestroy } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, In, MoreThan } from 'typeorm';
import { RfqEntity } from '../entities/rfq.entity';
import { RfqStreamingGateway } from './rfq-streaming.gateway';

@Injectable()
export class CountdownTimerService implements OnModuleInit, OnModuleDestroy {
  private intervalId: NodeJS.Timer | null = null;
  private readonly UPDATE_INTERVAL = 1000; // 1 second

  constructor(
    @InjectRepository(RfqEntity)
    private readonly rfqRepository: Repository<RfqEntity>,
    private readonly gateway: RfqStreamingGateway,
  ) {}

  onModuleInit() {
    this.intervalId = setInterval(() => this.broadcastCountdowns(), this.UPDATE_INTERVAL);
  }

  onModuleDestroy() {
    if (this.intervalId) {
      clearInterval(this.intervalId);
    }
  }

  private async broadcastCountdowns(): Promise<void> {
    const now = new Date();
    
    // Get active RFQs with subscribers
    const activeRfqs = await this.rfqRepository.find({
      where: {
        status: In(['distributed', 'quoted']),
        expiresAt: MoreThan(now),
      },
      select: ['id', 'expiresAt'],
    });

    for (const rfq of activeRfqs) {
      const timeRemaining = Math.max(0, Math.floor((rfq.expiresAt.getTime() - now.getTime()) / 1000));
      
      this.gateway.broadcastRfqUpdate(rfq.id, 'rfq:countdown', {
        rfqId: rfq.id,
        timeRemaining,
        expiresAt: rfq.expiresAt,
      });
    }
  }
}
```

### Client-Side SDK

```typescript
// packages/rfq-client/src/rfq-streaming-client.ts
import { io, Socket } from 'socket.io-client';

export interface RfqStreamingOptions {
  url: string;
  token: string;
  reconnection?: boolean;
  reconnectionAttempts?: number;
}

export interface RfqState {
  rfq: any;
  quotes: any[];
  composite: any;
}

type EventCallback<T = any> = (data: T) => void;

export class RfqStreamingClient {
  private socket: Socket;
  private eventHandlers: Map<string, Set<EventCallback>> = new Map();
  private subscribedRfqs: Set<string> = new Set();

  constructor(private options: RfqStreamingOptions) {
    this.socket = io(`${options.url}/rfq`, {
      auth: { token: options.token },
      reconnection: options.reconnection ?? true,
      reconnectionAttempts: options.reconnectionAttempts ?? 5,
    });

    this.setupEventHandlers();
  }

  private setupEventHandlers(): void {
    // Forward all RFQ events
    const events = [
      'rfq:created', 'rfq:status', 'rfq:distributed', 'rfq:quoted',
      'rfq:filled', 'rfq:expired', 'rfq:cancelled', 'rfq:extended',
      'rfq:countdown', 'quote:received', 'quotes:ranked', 'quote:expired',
    ];

    for (const event of events) {
      this.socket.on(event, (data) => this.emit(event, data));
    }

    this.socket.on('connect', () => this.emit('connected', {}));
    this.socket.on('disconnect', () => this.emit('disconnected', {}));
  }

  async subscribeToRfq(rfqId: string): Promise<RfqState> {
    return new Promise((resolve, reject) => {
      this.socket.emit('subscribe:rfq', { rfqId }, (response: any) => {
        if (response.success) {
          this.subscribedRfqs.add(rfqId);
          resolve(response.rfq);
        } else {
          reject(new Error('Failed to subscribe to RFQ'));
        }
      });
    });
  }

  unsubscribeFromRfq(rfqId: string): void {
    this.socket.emit('unsubscribe:rfq', { rfqId });
    this.subscribedRfqs.delete(rfqId);
  }

  subscribeToTrader(traderId?: string): Promise<void> {
    return new Promise((resolve, reject) => {
      this.socket.emit('subscribe:trader', { traderId }, (response: any) => {
        if (response.success) {
          resolve();
        } else {
          reject(new Error('Failed to subscribe to trader events'));
        }
      });
    });
  }

  on<T = any>(event: string, callback: EventCallback<T>): () => void {
    if (!this.eventHandlers.has(event)) {
      this.eventHandlers.set(event, new Set());
    }
    this.eventHandlers.get(event)!.add(callback);

    // Return unsubscribe function
    return () => {
      this.eventHandlers.get(event)?.delete(callback);
    };
  }

  private emit(event: string, data: any): void {
    const handlers = this.eventHandlers.get(event);
    if (handlers) {
      handlers.forEach(handler => handler(data));
    }
  }

  disconnect(): void {
    this.socket.disconnect();
  }
}
```

## Definition of Done

- [ ] WebSocket gateway for RFQ namespace
- [ ] Subscription management (per RFQ, per trader)
- [ ] Event broadcasting for all RFQ lifecycle events
- [ ] Countdown timer streaming
- [ ] Client SDK for React integration
- [ ] Authentication and authorization

## Dependencies

- **US-06-04**: WebSocket Distribution patterns
- **US-07-03**: Quote Collection events

## Test Cases

```typescript
describe('RfqStreamingGateway', () => {
  it('should authenticate and subscribe to RFQ', async () => {
    const client = new RfqStreamingClient({ url, token });
    const state = await client.subscribeToRfq(rfqId);
    
    expect(state.rfq.id).toBe(rfqId);
  });

  it('should receive quote updates', async () => {
    const client = new RfqStreamingClient({ url, token });
    await client.subscribeToRfq(rfqId);
    
    const quotePromise = new Promise((resolve) => {
      client.on('quote:received', resolve);
    });

    // Trigger quote from LP
    await sendLpQuote(rfqId, { askPrice: 1.0850 });
    
    const quote = await quotePromise;
    expect(quote.askPrice).toBe(1.0850);
  });

  it('should receive countdown updates', async () => {
    const client = new RfqStreamingClient({ url, token });
    await client.subscribeToRfq(rfqId);
    
    const countdowns: number[] = [];
    client.on('rfq:countdown', (data) => {
      countdowns.push(data.timeRemaining);
    });

    await waitMs(3000);
    expect(countdowns.length).toBeGreaterThan(0);
    expect(countdowns[countdowns.length - 1]).toBeLessThan(countdowns[0]);
  });
});
```
