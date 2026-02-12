# User Story: US-06-01 - Market Data Ingestion Service

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-06-01 |
| **Epic** | Epic 06 - Market Data System |
| **Title** | Market Data Ingestion Service |
| **Priority** | P0 - Critical |
| **Story Points** | 8 |
| **PRD Reference** | FR-MD-01 |

## User Story

**As a** platform operator  
**I want** to ingest market data from multiple liquidity providers  
**So that** traders have access to real-time prices across venues

## Description

Build a modular ingestion service that connects to various LP feeds (FIX, WebSocket, REST, gRPC) and publishes raw price data to Kafka for downstream processing.

## Acceptance Criteria

- [ ] FIX 4.4 adapter for traditional LPs
- [ ] WebSocket adapter for crypto exchanges
- [ ] REST polling adapter for rate-limited APIs
- [ ] Connection health monitoring
- [ ] Automatic reconnection with backoff
- [ ] Sequence number tracking per source
- [ ] Raw data published to Kafka

## Technical Details

### Feed Handler Interface

```typescript
// services/market-data-service/src/ingestion/feed-handler.interface.ts
import { Observable } from 'rxjs';

export interface RawPriceUpdate {
  source: string;
  sourceSymbol: string;        // LP-specific symbol
  bidPrice: string;            // String to preserve precision
  bidSize: string;
  askPrice: string;
  askSize: string;
  sourceTimestamp: number;
  sequenceNumber: number;
  rawPayload?: unknown;        // Original message for debugging
}

export interface FeedHandlerConfig {
  name: string;
  enabled: boolean;
  connectionConfig: Record<string, unknown>;
  symbols: string[];           // Symbols to subscribe
  reconnectPolicy: {
    maxRetries: number;
    initialDelayMs: number;
    maxDelayMs: number;
  };
}

export interface FeedHandler {
  readonly name: string;
  readonly status: FeedStatus;
  
  connect(): Promise<void>;
  disconnect(): Promise<void>;
  subscribe(symbols: string[]): Promise<void>;
  unsubscribe(symbols: string[]): Promise<void>;
  
  prices$: Observable<RawPriceUpdate>;
  status$: Observable<FeedStatus>;
}

export interface FeedStatus {
  connected: boolean;
  lastHeartbeat?: Date;
  subscribedSymbols: string[];
  messagesReceived: number;
  errors: number;
}
```

### Base Feed Handler

```typescript
// services/market-data-service/src/ingestion/base-feed-handler.ts
import { Injectable } from '@nestjs/common';
import { Subject, Observable, BehaviorSubject } from 'rxjs';
import { logger, metrics } from '@orion/observability';
import { FeedHandler, RawPriceUpdate, FeedStatus, FeedHandlerConfig } from './feed-handler.interface';

@Injectable()
export abstract class BaseFeedHandler implements FeedHandler {
  abstract readonly name: string;
  
  protected readonly pricesSubject = new Subject<RawPriceUpdate>();
  protected readonly statusSubject = new BehaviorSubject<FeedStatus>({
    connected: false,
    subscribedSymbols: [],
    messagesReceived: 0,
    errors: 0,
  });

  protected reconnectAttempts = 0;
  protected reconnectTimeout?: NodeJS.Timeout;

  constructor(protected readonly config: FeedHandlerConfig) {}

  get prices$(): Observable<RawPriceUpdate> {
    return this.pricesSubject.asObservable();
  }

  get status$(): Observable<FeedStatus> {
    return this.statusSubject.asObservable();
  }

  get status(): FeedStatus {
    return this.statusSubject.value;
  }

  abstract connect(): Promise<void>;
  abstract disconnect(): Promise<void>;
  abstract subscribe(symbols: string[]): Promise<void>;
  abstract unsubscribe(symbols: string[]): Promise<void>;

  protected emitPrice(update: RawPriceUpdate): void {
    const currentStatus = this.statusSubject.value;
    this.statusSubject.next({
      ...currentStatus,
      messagesReceived: currentStatus.messagesReceived + 1,
      lastHeartbeat: new Date(),
    });

    metrics.increment('market_data.raw_ticks', { source: this.name });
    this.pricesSubject.next(update);
  }

  protected handleDisconnect(error?: Error): void {
    const currentStatus = this.statusSubject.value;
    this.statusSubject.next({
      ...currentStatus,
      connected: false,
      errors: currentStatus.errors + 1,
    });

    logger.error('Feed disconnected', { source: this.name, error });
    metrics.increment('market_data.disconnections', { source: this.name });

    this.scheduleReconnect();
  }

  protected scheduleReconnect(): void {
    if (this.reconnectAttempts >= this.config.reconnectPolicy.maxRetries) {
      logger.error('Max reconnect attempts reached', { source: this.name });
      return;
    }

    const delay = Math.min(
      this.config.reconnectPolicy.initialDelayMs * Math.pow(2, this.reconnectAttempts),
      this.config.reconnectPolicy.maxDelayMs,
    );

    this.reconnectAttempts++;

    logger.info('Scheduling reconnect', {
      source: this.name,
      attempt: this.reconnectAttempts,
      delayMs: delay,
    });

    this.reconnectTimeout = setTimeout(async () => {
      try {
        await this.connect();
        this.reconnectAttempts = 0;
        
        // Re-subscribe to symbols
        if (this.status.subscribedSymbols.length > 0) {
          await this.subscribe(this.status.subscribedSymbols);
        }
      } catch (error) {
        logger.error('Reconnect failed', { source: this.name, error });
        this.scheduleReconnect();
      }
    }, delay);
  }

  protected clearReconnect(): void {
    if (this.reconnectTimeout) {
      clearTimeout(this.reconnectTimeout);
      this.reconnectTimeout = undefined;
    }
    this.reconnectAttempts = 0;
  }
}
```

### WebSocket Feed Handler (Crypto)

```typescript
// services/market-data-service/src/ingestion/handlers/websocket-feed-handler.ts
import { Injectable } from '@nestjs/common';
import WebSocket from 'ws';
import { logger, metrics } from '@orion/observability';
import { BaseFeedHandler } from '../base-feed-handler';
import { FeedHandlerConfig, RawPriceUpdate } from '../feed-handler.interface';

interface WebSocketFeedConfig extends FeedHandlerConfig {
  connectionConfig: {
    url: string;
    pingIntervalMs: number;
    subscribeMessage: (symbols: string[]) => unknown;
    unsubscribeMessage: (symbols: string[]) => unknown;
  };
}

@Injectable()
export class WebSocketFeedHandler extends BaseFeedHandler {
  readonly name: string;
  private ws?: WebSocket;
  private pingInterval?: NodeJS.Timeout;
  private sequenceNumber = 0;

  constructor(config: WebSocketFeedConfig) {
    super(config);
    this.name = config.name;
  }

  async connect(): Promise<void> {
    const { url, pingIntervalMs } = this.config.connectionConfig as WebSocketFeedConfig['connectionConfig'];

    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(url);

      this.ws.on('open', () => {
        logger.info('WebSocket connected', { source: this.name });
        this.statusSubject.next({
          ...this.status,
          connected: true,
        });
        this.clearReconnect();
        
        // Start ping/pong
        this.pingInterval = setInterval(() => {
          if (this.ws?.readyState === WebSocket.OPEN) {
            this.ws.ping();
          }
        }, pingIntervalMs);

        resolve();
      });

      this.ws.on('message', (data) => {
        this.handleMessage(data);
      });

      this.ws.on('close', (code, reason) => {
        this.handleDisconnect(new Error(`WebSocket closed: ${code} ${reason}`));
      });

      this.ws.on('error', (error) => {
        logger.error('WebSocket error', { source: this.name, error });
        reject(error);
      });

      this.ws.on('pong', () => {
        this.statusSubject.next({
          ...this.status,
          lastHeartbeat: new Date(),
        });
      });
    });
  }

  async disconnect(): Promise<void> {
    this.clearReconnect();
    
    if (this.pingInterval) {
      clearInterval(this.pingInterval);
    }

    if (this.ws) {
      this.ws.close();
      this.ws = undefined;
    }

    this.statusSubject.next({
      ...this.status,
      connected: false,
    });
  }

  async subscribe(symbols: string[]): Promise<void> {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      throw new Error('WebSocket not connected');
    }

    const { subscribeMessage } = this.config.connectionConfig as WebSocketFeedConfig['connectionConfig'];
    const message = subscribeMessage(symbols);
    
    this.ws.send(JSON.stringify(message));

    this.statusSubject.next({
      ...this.status,
      subscribedSymbols: [...new Set([...this.status.subscribedSymbols, ...symbols])],
    });

    logger.info('Subscribed to symbols', { source: this.name, symbols });
  }

  async unsubscribe(symbols: string[]): Promise<void> {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      return;
    }

    const { unsubscribeMessage } = this.config.connectionConfig as WebSocketFeedConfig['connectionConfig'];
    const message = unsubscribeMessage(symbols);
    
    this.ws.send(JSON.stringify(message));

    this.statusSubject.next({
      ...this.status,
      subscribedSymbols: this.status.subscribedSymbols.filter(s => !symbols.includes(s)),
    });
  }

  private handleMessage(data: WebSocket.Data): void {
    try {
      const message = JSON.parse(data.toString());
      const update = this.parseMessage(message);
      
      if (update) {
        this.emitPrice(update);
      }
    } catch (error) {
      logger.error('Failed to parse message', { source: this.name, error });
      metrics.increment('market_data.parse_errors', { source: this.name });
    }
  }

  protected parseMessage(message: unknown): RawPriceUpdate | null {
    // Override in specific implementations
    // Default: Binance-style format
    const msg = message as any;
    
    if (msg.e !== 'bookTicker') return null;

    return {
      source: this.name,
      sourceSymbol: msg.s,
      bidPrice: msg.b,
      bidSize: msg.B,
      askPrice: msg.a,
      askSize: msg.A,
      sourceTimestamp: msg.T || Date.now(),
      sequenceNumber: ++this.sequenceNumber,
      rawPayload: message,
    };
  }
}
```

### FIX 4.4 Feed Handler

```typescript
// services/market-data-service/src/ingestion/handlers/fix-feed-handler.ts
import { Injectable } from '@nestjs/common';
import { initiator } from 'node-quickfix';
import { logger, metrics } from '@orion/observability';
import { BaseFeedHandler } from '../base-feed-handler';
import { FeedHandlerConfig, RawPriceUpdate } from '../feed-handler.interface';

interface FixFeedConfig extends FeedHandlerConfig {
  connectionConfig: {
    host: string;
    port: number;
    senderCompId: string;
    targetCompId: string;
    heartbeatInterval: number;
    configFile: string;
  };
}

@Injectable()
export class FixFeedHandler extends BaseFeedHandler {
  readonly name: string;
  private session?: any;
  private sequenceNumber = 0;

  constructor(config: FixFeedConfig) {
    super(config);
    this.name = config.name;
  }

  async connect(): Promise<void> {
    const { configFile } = this.config.connectionConfig as FixFeedConfig['connectionConfig'];

    return new Promise((resolve, reject) => {
      try {
        this.session = new initiator({
          config: configFile,
          onCreate: () => {
            logger.info('FIX session created', { source: this.name });
          },
          onLogon: () => {
            logger.info('FIX logon successful', { source: this.name });
            this.statusSubject.next({ ...this.status, connected: true });
            this.clearReconnect();
            resolve();
          },
          onLogout: () => {
            logger.info('FIX logout', { source: this.name });
            this.handleDisconnect(new Error('FIX session logout'));
          },
          onMessage: (sessionId: string, msg: any) => {
            this.handleFixMessage(msg);
          },
        });

        this.session.start();
      } catch (error) {
        reject(error);
      }
    });
  }

  async disconnect(): Promise<void> {
    this.clearReconnect();
    
    if (this.session) {
      this.session.stop();
      this.session = undefined;
    }

    this.statusSubject.next({ ...this.status, connected: false });
  }

  async subscribe(symbols: string[]): Promise<void> {
    if (!this.session) {
      throw new Error('FIX session not connected');
    }

    // Send Market Data Request (MsgType V)
    for (const symbol of symbols) {
      const request = {
        8: 'FIX.4.4',
        35: 'V',                    // MsgType: Market Data Request
        262: `MDR_${symbol}_${Date.now()}`,  // MDReqID
        263: '1',                   // SubscriptionRequestType: Snapshot + Updates
        264: '0',                   // MarketDepth: Full book
        265: '1',                   // MDUpdateType: Incremental
        146: '1',                   // NoRelatedSym
        55: symbol,                 // Symbol
        267: '2',                   // NoMDEntryTypes
        269: ['0', '1'],           // MDEntryType: Bid, Offer
      };

      this.session.send(request);
    }

    this.statusSubject.next({
      ...this.status,
      subscribedSymbols: [...new Set([...this.status.subscribedSymbols, ...symbols])],
    });

    logger.info('FIX subscription sent', { source: this.name, symbols });
  }

  async unsubscribe(symbols: string[]): Promise<void> {
    if (!this.session) return;

    // Send Market Data Request with Disable Subscription
    for (const symbol of symbols) {
      const request = {
        8: 'FIX.4.4',
        35: 'V',
        262: `MDR_UNSUB_${symbol}_${Date.now()}`,
        263: '2',                   // SubscriptionRequestType: Disable
        55: symbol,
      };

      this.session.send(request);
    }

    this.statusSubject.next({
      ...this.status,
      subscribedSymbols: this.status.subscribedSymbols.filter(s => !symbols.includes(s)),
    });
  }

  private handleFixMessage(msg: any): void {
    const msgType = msg[35];

    switch (msgType) {
      case 'W':  // Market Data Snapshot
      case 'X':  // Market Data Incremental Refresh
        this.parseMarketData(msg);
        break;
      case 'Y':  // Market Data Request Reject
        logger.error('Market data request rejected', { source: this.name, msg });
        break;
      default:
        // Ignore other message types
    }
  }

  private parseMarketData(msg: any): void {
    const symbol = msg[55];
    const entries = msg[268] || [];

    let bidPrice: string | undefined;
    let bidSize: string | undefined;
    let askPrice: string | undefined;
    let askSize: string | undefined;

    for (const entry of entries) {
      const entryType = entry[269];
      const price = entry[270];
      const size = entry[271];

      if (entryType === '0') {  // Bid
        bidPrice = price;
        bidSize = size;
      } else if (entryType === '1') {  // Offer
        askPrice = price;
        askSize = size;
      }
    }

    if (bidPrice && askPrice) {
      const update: RawPriceUpdate = {
        source: this.name,
        sourceSymbol: symbol,
        bidPrice,
        bidSize: bidSize || '0',
        askPrice,
        askSize: askSize || '0',
        sourceTimestamp: msg[52] ? Date.parse(msg[52]) : Date.now(),
        sequenceNumber: ++this.sequenceNumber,
        rawPayload: msg,
      };

      this.emitPrice(update);
    }
  }
}
```

### Ingestion Service

```typescript
// services/market-data-service/src/ingestion/ingestion.service.ts
import { Injectable, OnModuleInit, OnModuleDestroy } from '@nestjs/common';
import { Subscription, merge } from 'rxjs';
import { logger, metrics } from '@orion/observability';
import { EventBus } from '@orion/event-model';
import { FeedHandler, RawPriceUpdate } from './feed-handler.interface';
import { WebSocketFeedHandler } from './handlers/websocket-feed-handler';
import { FixFeedHandler } from './handlers/fix-feed-handler';
import { feedConfigs } from './feed-configs';

@Injectable()
export class IngestionService implements OnModuleInit, OnModuleDestroy {
  private readonly handlers: Map<string, FeedHandler> = new Map();
  private subscription?: Subscription;

  constructor(private readonly eventBus: EventBus) {}

  async onModuleInit() {
    // Initialize feed handlers from configuration
    for (const config of feedConfigs) {
      if (!config.enabled) continue;

      let handler: FeedHandler;

      switch (config.type) {
        case 'websocket':
          handler = new WebSocketFeedHandler(config);
          break;
        case 'fix':
          handler = new FixFeedHandler(config);
          break;
        // Add more handler types as needed
        default:
          logger.warn('Unknown feed type', { type: config.type });
          continue;
      }

      this.handlers.set(config.name, handler);
    }

    // Connect all handlers
    await this.connectAll();

    // Merge all price streams and publish to Kafka
    const priceStreams = Array.from(this.handlers.values()).map(h => h.prices$);
    
    this.subscription = merge(...priceStreams).subscribe({
      next: async (update) => {
        await this.publishRawPrice(update);
      },
      error: (error) => {
        logger.error('Price stream error', { error });
      },
    });

    logger.info('Ingestion service started', {
      handlers: Array.from(this.handlers.keys()),
    });
  }

  async onModuleDestroy() {
    this.subscription?.unsubscribe();
    
    for (const handler of this.handlers.values()) {
      await handler.disconnect();
    }
  }

  private async connectAll(): Promise<void> {
    const connections = Array.from(this.handlers.entries()).map(
      async ([name, handler]) => {
        try {
          await handler.connect();
          
          // Subscribe to configured symbols
          const config = feedConfigs.find(c => c.name === name);
          if (config?.symbols?.length) {
            await handler.subscribe(config.symbols);
          }
        } catch (error) {
          logger.error('Failed to connect handler', { name, error });
        }
      },
    );

    await Promise.allSettled(connections);
  }

  private async publishRawPrice(update: RawPriceUpdate): Promise<void> {
    try {
      await this.eventBus.publish({
        topic: 'orion.market-data.raw',
        eventType: 'market-data.raw-price',
        aggregateType: 'market-data',
        aggregateId: `${update.source}:${update.sourceSymbol}`,
        payload: update,
        metadata: {
          source: update.source,
          symbol: update.sourceSymbol,
        },
      });

      metrics.increment('market_data.published', { source: update.source });
    } catch (error) {
      logger.error('Failed to publish raw price', { error });
      metrics.increment('market_data.publish_errors', { source: update.source });
    }
  }

  /**
   * Get status of all feed handlers
   */
  getStatus(): Map<string, FeedHandler['status']> {
    const status = new Map<string, FeedHandler['status']>();
    
    for (const [name, handler] of this.handlers) {
      status.set(name, handler.status);
    }
    
    return status;
  }
}
```

### Feed Configuration

```typescript
// services/market-data-service/src/ingestion/feed-configs.ts
import { FeedHandlerConfig } from './feed-handler.interface';

interface ExtendedConfig extends FeedHandlerConfig {
  type: 'websocket' | 'fix' | 'rest' | 'grpc';
}

export const feedConfigs: ExtendedConfig[] = [
  // Binance WebSocket (Crypto)
  {
    type: 'websocket',
    name: 'binance',
    enabled: true,
    connectionConfig: {
      url: 'wss://stream.binance.com:9443/ws',
      pingIntervalMs: 30000,
      subscribeMessage: (symbols: string[]) => ({
        method: 'SUBSCRIBE',
        params: symbols.map(s => `${s.toLowerCase()}@bookTicker`),
        id: Date.now(),
      }),
      unsubscribeMessage: (symbols: string[]) => ({
        method: 'UNSUBSCRIBE',
        params: symbols.map(s => `${s.toLowerCase()}@bookTicker`),
        id: Date.now(),
      }),
    },
    symbols: ['BTCUSDT', 'ETHUSDT', 'SOLUSDT'],
    reconnectPolicy: {
      maxRetries: 10,
      initialDelayMs: 1000,
      maxDelayMs: 60000,
    },
  },
  
  // Coinbase WebSocket (Crypto)
  {
    type: 'websocket',
    name: 'coinbase',
    enabled: true,
    connectionConfig: {
      url: 'wss://ws-feed.exchange.coinbase.com',
      pingIntervalMs: 30000,
      subscribeMessage: (symbols: string[]) => ({
        type: 'subscribe',
        product_ids: symbols,
        channels: ['ticker'],
      }),
      unsubscribeMessage: (symbols: string[]) => ({
        type: 'unsubscribe',
        product_ids: symbols,
        channels: ['ticker'],
      }),
    },
    symbols: ['BTC-USD', 'ETH-USD'],
    reconnectPolicy: {
      maxRetries: 10,
      initialDelayMs: 1000,
      maxDelayMs: 60000,
    },
  },
  
  // Example FIX LP (FX)
  {
    type: 'fix',
    name: 'lp-alpha',
    enabled: process.env.LP_ALPHA_ENABLED === 'true',
    connectionConfig: {
      host: process.env.LP_ALPHA_HOST || 'localhost',
      port: parseInt(process.env.LP_ALPHA_PORT || '5001'),
      senderCompId: process.env.LP_ALPHA_SENDER || 'ORION',
      targetCompId: process.env.LP_ALPHA_TARGET || 'LP_ALPHA',
      heartbeatInterval: 30,
      configFile: './config/fix/lp-alpha.cfg',
    },
    symbols: ['EUR/USD', 'GBP/USD', 'USD/JPY'],
    reconnectPolicy: {
      maxRetries: 10,
      initialDelayMs: 5000,
      maxDelayMs: 300000,
    },
  },
];
```

## Definition of Done

- [ ] WebSocket handler implemented
- [ ] FIX handler implemented
- [ ] Connection health monitoring works
- [ ] Automatic reconnection functional
- [ ] Raw prices published to Kafka
- [ ] Metrics exposed
- [ ] Tests pass

## Dependencies

- **US-05-01**: Kafka Topic Configuration
- **US-05-03**: Event Publisher Library

## Test Cases

```typescript
describe('IngestionService', () => {
  it('should publish raw prices to Kafka', async () => {
    const publishSpy = jest.spyOn(eventBus, 'publish');

    // Simulate price update
    mockFeedHandler.emit({
      source: 'test-feed',
      sourceSymbol: 'BTC/USD',
      bidPrice: '50000.00',
      askPrice: '50001.00',
    });

    await waitForPublish();

    expect(publishSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        topic: 'orion.market-data.raw',
        eventType: 'market-data.raw-price',
      }),
    );
  });

  it('should reconnect on disconnect', async () => {
    const connectSpy = jest.spyOn(handler, 'connect');
    
    // Simulate disconnect
    handler.simulateDisconnect();

    await waitFor(1100); // Initial delay

    expect(connectSpy).toHaveBeenCalledTimes(2);
  });
});
```
