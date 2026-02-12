# User Story: US-07-02 - RFQ Distribution to Liquidity Providers

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-07-02 |
| **Epic** | Epic 07 - RFQ Workflow |
| **Title** | RFQ Distribution to Liquidity Providers |
| **Priority** | P0 - Critical |
| **Story Points** | 8 |
| **PRD Reference** | FR-RFQ-02 |

## User Story

**As a** trading platform  
**I want** to distribute validated RFQs to eligible liquidity providers  
**So that** traders receive competitive quotes from multiple sources

## Description

Implement LP distribution with support for multiple transport protocols (FIX, REST, WebSocket), LP eligibility rules, and fair distribution timing.

## Acceptance Criteria

- [ ] LP eligibility evaluation per RFQ
- [ ] Multi-protocol gateway (FIX 4.4, REST, WebSocket)
- [ ] Fair simultaneous distribution
- [ ] Distribution confirmation tracking
- [ ] Retry logic for failed deliveries
- [ ] RFQ status updated to DISTRIBUTED

## Technical Details

### LP Configuration Schema

```sql
-- migrations/20240120_create_lp_configuration.sql

CREATE TYPE lp_protocol AS ENUM ('fix', 'rest', 'websocket');
CREATE TYPE lp_status AS ENUM ('active', 'inactive', 'suspended');

CREATE TABLE liquidity_providers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    
    -- Identity
    code VARCHAR(20) NOT NULL,
    name VARCHAR(100) NOT NULL,
    legal_entity VARCHAR(100),
    
    -- Connection
    protocol lp_protocol NOT NULL,
    connection_config JSONB NOT NULL,
    
    -- Status
    status lp_status NOT NULL DEFAULT 'active',
    is_streaming BOOLEAN DEFAULT FALSE,
    
    -- Capabilities
    supported_asset_classes TEXT[] NOT NULL,
    supported_instruments UUID[] DEFAULT '{}',
    min_size NUMERIC(20, 8),
    max_size NUMERIC(20, 8),
    
    -- Performance
    avg_response_time_ms INTEGER DEFAULT 0,
    quote_rate_percent NUMERIC(5, 2) DEFAULT 0,
    
    -- Limits
    max_concurrent_rfqs INTEGER DEFAULT 100,
    daily_volume_limit NUMERIC(20, 2),
    
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    UNIQUE(tenant_id, code)
);

-- RFQ-LP Distribution tracking
CREATE TABLE rfq_distributions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rfq_id UUID NOT NULL REFERENCES rfqs(id),
    lp_id UUID NOT NULL REFERENCES liquidity_providers(id),
    
    -- Timing
    sent_at TIMESTAMPTZ,
    acknowledged_at TIMESTAMPTZ,
    
    -- Status
    delivery_status VARCHAR(20) NOT NULL DEFAULT 'pending',
    retry_count INTEGER DEFAULT 0,
    last_error TEXT,
    
    -- Response
    quote_received BOOLEAN DEFAULT FALSE,
    quote_id UUID,
    
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_rfq_distributions_rfq ON rfq_distributions(rfq_id);
CREATE INDEX idx_rfq_distributions_lp ON rfq_distributions(lp_id, delivery_status);
```

### LP Gateway Interfaces

```typescript
// services/rfq-service/src/gateway/lp-gateway.interface.ts

export interface RfqRequest {
  rfqId: string;
  symbol: string;
  assetClass: string;
  side: 'buy' | 'sell' | 'two_way';
  quantity: number;
  notionalAmount?: number;
  settlementDate: Date;
  expiresAt: Date;
  clientRef?: string;
}

export interface RfqAcknowledgement {
  rfqId: string;
  lpId: string;
  status: 'accepted' | 'rejected';
  rejectionReason?: string;
  receivedAt: Date;
}

export interface LpGateway {
  readonly protocol: 'fix' | 'rest' | 'websocket';
  
  connect(): Promise<void>;
  disconnect(): Promise<void>;
  isConnected(): boolean;
  
  sendRfq(lpId: string, rfq: RfqRequest): Promise<RfqAcknowledgement>;
  cancelRfq(lpId: string, rfqId: string): Promise<void>;
}
```

### FIX Protocol Gateway

```typescript
// services/rfq-service/src/gateway/fix-gateway.ts
import { Injectable, OnModuleInit, OnModuleDestroy } from '@nestjs/common';
import { logger, metrics } from '@orion/observability';
import { LpGateway, RfqRequest, RfqAcknowledgement } from './lp-gateway.interface';

interface FixSession {
  senderCompId: string;
  targetCompId: string;
  host: string;
  port: number;
  heartbeatInterval: number;
  socket?: any;
  seqNum: number;
  connected: boolean;
}

@Injectable()
export class FixGateway implements LpGateway, OnModuleInit, OnModuleDestroy {
  readonly protocol = 'fix' as const;
  private sessions: Map<string, FixSession> = new Map();
  private pendingRequests: Map<string, (ack: RfqAcknowledgement) => void> = new Map();

  async onModuleInit() {
    // Sessions initialized on-demand
  }

  async onModuleDestroy() {
    await this.disconnect();
  }

  async connect(): Promise<void> {
    // Connect all configured sessions
    for (const [lpId, session] of this.sessions) {
      await this.connectSession(lpId, session);
    }
  }

  async disconnect(): Promise<void> {
    for (const [lpId, session] of this.sessions) {
      if (session.connected) {
        await this.sendLogout(session);
        session.socket?.end();
        session.connected = false;
      }
    }
  }

  isConnected(): boolean {
    return Array.from(this.sessions.values()).every(s => s.connected);
  }

  async initializeSession(lpId: string, config: any): Promise<void> {
    const session: FixSession = {
      senderCompId: config.senderCompId,
      targetCompId: config.targetCompId,
      host: config.host,
      port: config.port,
      heartbeatInterval: config.heartbeatInterval || 30,
      seqNum: 1,
      connected: false,
    };

    this.sessions.set(lpId, session);
    await this.connectSession(lpId, session);
  }

  private async connectSession(lpId: string, session: FixSession): Promise<void> {
    // Simplified FIX connection (real implementation would use quickfix or similar)
    const net = await import('net');
    
    return new Promise((resolve, reject) => {
      const socket = net.createConnection({
        host: session.host,
        port: session.port,
      });

      socket.on('connect', async () => {
        session.socket = socket;
        await this.sendLogon(session);
        session.connected = true;
        
        logger.info('FIX session connected', { lpId, targetCompId: session.targetCompId });
        metrics.increment('lp.fix.connected', { lpId });
        resolve();
      });

      socket.on('data', (data: Buffer) => {
        this.handleFixMessage(lpId, data.toString());
      });

      socket.on('error', (err) => {
        logger.error('FIX connection error', { lpId, error: err.message });
        session.connected = false;
        reject(err);
      });

      socket.on('close', () => {
        session.connected = false;
        this.scheduleReconnect(lpId);
      });
    });
  }

  async sendRfq(lpId: string, rfq: RfqRequest): Promise<RfqAcknowledgement> {
    const session = this.sessions.get(lpId);
    if (!session?.connected) {
      throw new Error(`LP ${lpId} not connected`);
    }

    const fixMessage = this.buildQuoteRequest(session, rfq);
    
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.pendingRequests.delete(rfq.rfqId);
        reject(new Error('RFQ acknowledgement timeout'));
      }, 5000);

      this.pendingRequests.set(rfq.rfqId, (ack) => {
        clearTimeout(timeout);
        resolve(ack);
      });

      session.socket?.write(fixMessage);
      session.seqNum++;
      
      metrics.increment('lp.fix.rfq_sent', { lpId });
    });
  }

  async cancelRfq(lpId: string, rfqId: string): Promise<void> {
    const session = this.sessions.get(lpId);
    if (!session?.connected) return;

    const cancelMessage = this.buildQuoteCancel(session, rfqId);
    session.socket?.write(cancelMessage);
    session.seqNum++;
  }

  private buildQuoteRequest(session: FixSession, rfq: RfqRequest): string {
    // FIX 4.4 Quote Request (R)
    const fields = [
      `8=FIX.4.4`,
      `9=0`, // Length placeholder
      `35=R`, // MsgType = Quote Request
      `49=${session.senderCompId}`,
      `56=${session.targetCompId}`,
      `34=${session.seqNum}`,
      `52=${this.formatTimestamp(new Date())}`,
      `131=${rfq.rfqId}`, // QuoteReqID
      `146=1`, // NoRelatedSym
      `55=${rfq.symbol}`, // Symbol
      `54=${this.mapSide(rfq.side)}`, // Side
      `38=${rfq.quantity}`, // OrderQty
      `64=${this.formatDate(rfq.settlementDate)}`, // SettlDate
      `126=${this.formatTimestamp(rfq.expiresAt)}`, // ExpireTime
    ];

    // Calculate body length and add checksum
    const body = fields.slice(2).join('\x01');
    fields[1] = `9=${body.length}`;
    
    const fullMessage = fields.join('\x01') + '\x01';
    const checksum = this.calculateChecksum(fullMessage);
    
    return fullMessage + `10=${checksum}\x01`;
  }

  private buildQuoteCancel(session: FixSession, rfqId: string): string {
    const fields = [
      `8=FIX.4.4`,
      `9=0`,
      `35=Z`, // MsgType = Quote Cancel
      `49=${session.senderCompId}`,
      `56=${session.targetCompId}`,
      `34=${session.seqNum}`,
      `52=${this.formatTimestamp(new Date())}`,
      `131=${rfqId}`,
      `298=5`, // QuoteCancelType = Cancel All
    ];

    const body = fields.slice(2).join('\x01');
    fields[1] = `9=${body.length}`;
    
    const fullMessage = fields.join('\x01') + '\x01';
    const checksum = this.calculateChecksum(fullMessage);
    
    return fullMessage + `10=${checksum}\x01`;
  }

  private handleFixMessage(lpId: string, message: string): void {
    const fields = this.parseFixMessage(message);
    const msgType = fields['35'];

    switch (msgType) {
      case 'A': // Logon
        logger.info('FIX logon confirmed', { lpId });
        break;
      case '0': // Heartbeat
        break;
      case 'b': // Quote Request Acknowledgement
        this.handleQuoteRequestAck(lpId, fields);
        break;
      case 'S': // Quote
        this.handleQuote(lpId, fields);
        break;
      case 'j': // Business Message Reject
        this.handleReject(lpId, fields);
        break;
    }
  }

  private handleQuoteRequestAck(lpId: string, fields: Record<string, string>): void {
    const rfqId = fields['131'];
    const status = fields['297'];
    
    const ack: RfqAcknowledgement = {
      rfqId,
      lpId,
      status: status === '0' ? 'accepted' : 'rejected',
      rejectionReason: fields['300'],
      receivedAt: new Date(),
    };

    const resolver = this.pendingRequests.get(rfqId);
    if (resolver) {
      resolver(ack);
      this.pendingRequests.delete(rfqId);
    }
  }

  private handleQuote(lpId: string, fields: Record<string, string>): void {
    // Emit quote received event - handled by quote collection service
  }

  private handleReject(lpId: string, fields: Record<string, string>): void {
    const rfqId = fields['131'];
    const reason = fields['58'];
    
    logger.warn('FIX business reject', { lpId, rfqId, reason });
    
    const resolver = this.pendingRequests.get(rfqId);
    if (resolver) {
      resolver({
        rfqId,
        lpId,
        status: 'rejected',
        rejectionReason: reason,
        receivedAt: new Date(),
      });
      this.pendingRequests.delete(rfqId);
    }
  }

  private async sendLogon(session: FixSession): Promise<void> {
    const fields = [
      `8=FIX.4.4`,
      `9=0`,
      `35=A`, // Logon
      `49=${session.senderCompId}`,
      `56=${session.targetCompId}`,
      `34=${session.seqNum}`,
      `52=${this.formatTimestamp(new Date())}`,
      `98=0`, // EncryptMethod = None
      `108=${session.heartbeatInterval}`,
    ];

    const body = fields.slice(2).join('\x01');
    fields[1] = `9=${body.length}`;
    
    const fullMessage = fields.join('\x01') + '\x01';
    const checksum = this.calculateChecksum(fullMessage);
    
    session.socket?.write(fullMessage + `10=${checksum}\x01`);
    session.seqNum++;
  }

  private async sendLogout(session: FixSession): Promise<void> {
    const fields = [
      `8=FIX.4.4`,
      `9=0`,
      `35=5`, // Logout
      `49=${session.senderCompId}`,
      `56=${session.targetCompId}`,
      `34=${session.seqNum}`,
      `52=${this.formatTimestamp(new Date())}`,
    ];

    const body = fields.slice(2).join('\x01');
    fields[1] = `9=${body.length}`;
    
    const fullMessage = fields.join('\x01') + '\x01';
    const checksum = this.calculateChecksum(fullMessage);
    
    session.socket?.write(fullMessage + `10=${checksum}\x01`);
  }

  private scheduleReconnect(lpId: string): void {
    setTimeout(async () => {
      const session = this.sessions.get(lpId);
      if (session && !session.connected) {
        logger.info('Attempting FIX reconnection', { lpId });
        try {
          await this.connectSession(lpId, session);
        } catch (err) {
          this.scheduleReconnect(lpId);
        }
      }
    }, 5000);
  }

  private parseFixMessage(message: string): Record<string, string> {
    const fields: Record<string, string> = {};
    const parts = message.split('\x01');
    
    for (const part of parts) {
      const [tag, value] = part.split('=');
      if (tag && value) {
        fields[tag] = value;
      }
    }
    
    return fields;
  }

  private mapSide(side: string): string {
    switch (side) {
      case 'buy': return '1';
      case 'sell': return '2';
      case 'two_way': return 'B';
      default: return '1';
    }
  }

  private formatTimestamp(date: Date): string {
    return date.toISOString().replace(/[-:]/g, '').replace('T', '-').slice(0, 17);
  }

  private formatDate(date: Date): string {
    return date.toISOString().slice(0, 10).replace(/-/g, '');
  }

  private calculateChecksum(message: string): string {
    let sum = 0;
    for (let i = 0; i < message.length; i++) {
      sum += message.charCodeAt(i);
    }
    return (sum % 256).toString().padStart(3, '0');
  }
}
```

### REST Gateway

```typescript
// services/rfq-service/src/gateway/rest-gateway.ts
import { Injectable } from '@nestjs/common';
import { HttpService } from '@nestjs/axios';
import { logger, metrics } from '@orion/observability';
import { LpGateway, RfqRequest, RfqAcknowledgement } from './lp-gateway.interface';

interface RestLpConfig {
  baseUrl: string;
  apiKey: string;
  timeout: number;
}

@Injectable()
export class RestGateway implements LpGateway {
  readonly protocol = 'rest' as const;
  private configs: Map<string, RestLpConfig> = new Map();

  constructor(private readonly httpService: HttpService) {}

  async connect(): Promise<void> {
    // REST is stateless, no persistent connection
  }

  async disconnect(): Promise<void> {
    // No-op for REST
  }

  isConnected(): boolean {
    return true;
  }

  configureLP(lpId: string, config: RestLpConfig): void {
    this.configs.set(lpId, config);
  }

  async sendRfq(lpId: string, rfq: RfqRequest): Promise<RfqAcknowledgement> {
    const config = this.configs.get(lpId);
    if (!config) {
      throw new Error(`LP ${lpId} not configured`);
    }

    const startTime = Date.now();

    try {
      const response = await this.httpService.axiosRef.post(
        `${config.baseUrl}/api/v1/rfq`,
        {
          rfqId: rfq.rfqId,
          symbol: rfq.symbol,
          side: rfq.side,
          quantity: rfq.quantity,
          settlementDate: rfq.settlementDate.toISOString(),
          expiresAt: rfq.expiresAt.toISOString(),
        },
        {
          headers: {
            'Authorization': `Bearer ${config.apiKey}`,
            'Content-Type': 'application/json',
            'X-Request-ID': rfq.rfqId,
          },
          timeout: config.timeout || 5000,
        },
      );

      metrics.timing('lp.rest.response_time', Date.now() - startTime, { lpId });
      metrics.increment('lp.rest.rfq_sent', { lpId, status: 'success' });

      return {
        rfqId: rfq.rfqId,
        lpId,
        status: response.data.accepted ? 'accepted' : 'rejected',
        rejectionReason: response.data.rejectionReason,
        receivedAt: new Date(),
      };
    } catch (error) {
      metrics.increment('lp.rest.rfq_sent', { lpId, status: 'error' });
      logger.error('REST RFQ delivery failed', { lpId, rfqId: rfq.rfqId, error });
      throw error;
    }
  }

  async cancelRfq(lpId: string, rfqId: string): Promise<void> {
    const config = this.configs.get(lpId);
    if (!config) return;

    try {
      await this.httpService.axiosRef.delete(
        `${config.baseUrl}/api/v1/rfq/${rfqId}`,
        {
          headers: {
            'Authorization': `Bearer ${config.apiKey}`,
          },
          timeout: 3000,
        },
      );
    } catch (error) {
      logger.warn('REST RFQ cancellation failed', { lpId, rfqId });
    }
  }
}
```

### LP Distribution Service

```typescript
// services/rfq-service/src/distribution/distribution.service.ts
import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { logger, metrics } from '@orion/observability';
import { transactionalOutbox } from '@orion/event-model';
import { RfqEntity } from '../entities/rfq.entity';
import { LiquidityProviderEntity } from '../entities/liquidity-provider.entity';
import { RfqDistributionEntity } from '../entities/rfq-distribution.entity';
import { FixGateway } from '../gateway/fix-gateway';
import { RestGateway } from '../gateway/rest-gateway';
import { WebSocketGateway } from '../gateway/websocket-gateway';
import { RfqRequest, LpGateway } from '../gateway/lp-gateway.interface';

@Injectable()
export class DistributionService {
  private gateways: Map<string, LpGateway> = new Map();

  constructor(
    @InjectRepository(RfqEntity)
    private readonly rfqRepository: Repository<RfqEntity>,
    @InjectRepository(LiquidityProviderEntity)
    private readonly lpRepository: Repository<LiquidityProviderEntity>,
    @InjectRepository(RfqDistributionEntity)
    private readonly distributionRepository: Repository<RfqDistributionEntity>,
    private readonly fixGateway: FixGateway,
    private readonly restGateway: RestGateway,
    private readonly wsGateway: WebSocketGateway,
  ) {
    this.gateways.set('fix', fixGateway);
    this.gateways.set('rest', restGateway);
    this.gateways.set('websocket', wsGateway);
  }

  async distributeRfq(rfqId: string): Promise<void> {
    const rfq = await this.rfqRepository.findOne({ where: { id: rfqId } });
    
    if (!rfq || rfq.status !== 'validated') {
      logger.warn('Cannot distribute RFQ', { rfqId, status: rfq?.status });
      return;
    }

    // Find eligible LPs
    const eligibleLPs = await this.findEligibleLPs(rfq);
    
    if (eligibleLPs.length === 0) {
      logger.warn('No eligible LPs for RFQ', { rfqId, symbol: rfq.symbol });
      await this.rejectRfq(rfq, 'No eligible liquidity providers');
      return;
    }

    // Create distribution records
    const distributions = eligibleLPs.map(lp => 
      this.distributionRepository.create({
        rfqId: rfq.id,
        lpId: lp.id,
        deliveryStatus: 'pending',
      })
    );

    await this.distributionRepository.save(distributions);

    // Build RFQ request
    const rfqRequest: RfqRequest = {
      rfqId: rfq.id,
      symbol: rfq.symbol,
      assetClass: rfq.assetClass,
      side: rfq.side,
      quantity: Number(rfq.quantity),
      notionalAmount: rfq.notionalAmount ? Number(rfq.notionalAmount) : undefined,
      settlementDate: rfq.settlementDate!,
      expiresAt: rfq.expiresAt,
    };

    // Distribute simultaneously
    const distributionPromises = eligibleLPs.map(async (lp, index) => {
      const distribution = distributions[index];
      return this.sendToLP(lp, rfqRequest, distribution);
    });

    // Wait for all with fair timing
    await Promise.allSettled(distributionPromises);

    // Update RFQ status
    const successCount = await this.distributionRepository.count({
      where: { rfqId, deliveryStatus: 'delivered' },
    });

    if (successCount > 0) {
      await transactionalOutbox(
        this.rfqRepository.manager,
        async (manager) => {
          await manager.update(RfqEntity, rfqId, {
            status: 'distributed',
            updatedAt: new Date(),
          });
        },
        {
          topic: 'orion.events.rfq',
          eventType: 'rfq.distributed',
          aggregateType: 'rfq',
          aggregateId: rfqId,
          payload: {
            rfqId,
            lpCount: successCount,
            distributedAt: new Date(),
          },
        },
      );

      metrics.increment('rfq.distributed', { lpCount: successCount.toString() });
      logger.info('RFQ distributed', { rfqId, lpCount: successCount });
    } else {
      await this.rejectRfq(rfq, 'All LP deliveries failed');
    }
  }

  private async findEligibleLPs(rfq: RfqEntity): Promise<LiquidityProviderEntity[]> {
    // Query active LPs that support this instrument/asset class
    const lps = await this.lpRepository
      .createQueryBuilder('lp')
      .where('lp.tenant_id = :tenantId', { tenantId: rfq.tenantId })
      .andWhere('lp.status = :status', { status: 'active' })
      .andWhere(':assetClass = ANY(lp.supported_asset_classes)', { 
        assetClass: rfq.assetClass 
      })
      .andWhere('(lp.min_size IS NULL OR lp.min_size <= :quantity)', {
        quantity: rfq.quantity,
      })
      .andWhere('(lp.max_size IS NULL OR lp.max_size >= :quantity)', {
        quantity: rfq.quantity,
      })
      .getMany();

    // Filter by concurrent RFQ limits
    const eligibleLPs: LiquidityProviderEntity[] = [];
    
    for (const lp of lps) {
      const activeRfqs = await this.distributionRepository.count({
        where: {
          lpId: lp.id,
          deliveryStatus: 'delivered',
        },
      });

      if (activeRfqs < lp.maxConcurrentRfqs) {
        eligibleLPs.push(lp);
      }
    }

    return eligibleLPs;
  }

  private async sendToLP(
    lp: LiquidityProviderEntity,
    rfq: RfqRequest,
    distribution: RfqDistributionEntity,
  ): Promise<void> {
    const gateway = this.gateways.get(lp.protocol);
    
    if (!gateway) {
      logger.error('Unknown LP protocol', { lpId: lp.id, protocol: lp.protocol });
      await this.updateDistribution(distribution.id, 'failed', 'Unknown protocol');
      return;
    }

    const startTime = Date.now();

    try {
      // Mark as sending
      distribution.sentAt = new Date();
      await this.distributionRepository.save(distribution);

      // Send with retry
      const ack = await this.sendWithRetry(gateway, lp.id, rfq, 3);

      if (ack.status === 'accepted') {
        await this.updateDistribution(distribution.id, 'delivered');
        
        metrics.timing('lp.distribution_time', Date.now() - startTime, {
          lpId: lp.id,
          protocol: lp.protocol,
        });
      } else {
        await this.updateDistribution(
          distribution.id, 
          'rejected', 
          ack.rejectionReason
        );
      }
    } catch (error) {
      logger.error('LP distribution failed', { 
        lpId: lp.id, 
        rfqId: rfq.rfqId, 
        error 
      });
      await this.updateDistribution(distribution.id, 'failed', error.message);
    }
  }

  private async sendWithRetry(
    gateway: LpGateway,
    lpId: string,
    rfq: RfqRequest,
    maxRetries: number,
  ) {
    let lastError: Error | undefined;

    for (let attempt = 0; attempt < maxRetries; attempt++) {
      try {
        return await gateway.sendRfq(lpId, rfq);
      } catch (error) {
        lastError = error;
        logger.warn('LP send attempt failed', { 
          lpId, 
          attempt: attempt + 1, 
          maxRetries 
        });
        
        if (attempt < maxRetries - 1) {
          await this.delay(Math.pow(2, attempt) * 100); // Exponential backoff
        }
      }
    }

    throw lastError;
  }

  private async updateDistribution(
    distributionId: string,
    status: string,
    error?: string,
  ): Promise<void> {
    await this.distributionRepository.update(distributionId, {
      deliveryStatus: status,
      acknowledgedAt: status === 'delivered' ? new Date() : undefined,
      lastError: error,
      retryCount: () => 'retry_count + 1',
    });
  }

  private async rejectRfq(rfq: RfqEntity, reason: string): Promise<void> {
    await transactionalOutbox(
      this.rfqRepository.manager,
      async (manager) => {
        await manager.update(RfqEntity, rfq.id, {
          status: 'rejected',
          rejectionReason: reason,
          updatedAt: new Date(),
        });
      },
      {
        topic: 'orion.events.rfq',
        eventType: 'rfq.rejected',
        aggregateType: 'rfq',
        aggregateId: rfq.id,
        payload: {
          rfqId: rfq.id,
          reason,
        },
      },
    );
  }

  private delay(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
  }
}
```

### Distribution Event Handler

```typescript
// services/rfq-service/src/distribution/distribution.handler.ts
import { Injectable } from '@nestjs/common';
import { EventHandler, OnEvent } from '@orion/event-model';
import { DistributionService } from './distribution.service';

@Injectable()
export class DistributionHandler {
  constructor(private readonly distributionService: DistributionService) {}

  @OnEvent('rfq.validated')
  async handleRfqValidated(event: { rfqId: string }): Promise<void> {
    await this.distributionService.distributeRfq(event.rfqId);
  }
}
```

## Definition of Done

- [ ] LP configuration CRUD operations
- [ ] FIX gateway implementation
- [ ] REST gateway implementation
- [ ] WebSocket gateway implementation
- [ ] LP eligibility filtering
- [ ] Simultaneous distribution
- [ ] Retry logic with backoff
- [ ] Distribution tracking

## Dependencies

- **US-07-01**: RFQ Creation and Validation
- **US-05-01**: Event Bus Infrastructure

## Test Cases

```typescript
describe('DistributionService', () => {
  it('should distribute RFQ to eligible LPs', async () => {
    const rfq = await createValidatedRfq();
    await distributionService.distributeRfq(rfq.id);
    
    const distributions = await findDistributions(rfq.id);
    expect(distributions.length).toBeGreaterThan(0);
    expect(distributions.some(d => d.deliveryStatus === 'delivered')).toBe(true);
  });

  it('should filter LPs by asset class', async () => {
    const rfq = await createRfq({ assetClass: 'crypto' });
    const eligibleLPs = await distributionService.findEligibleLPs(rfq);
    
    expect(eligibleLPs.every(lp => 
      lp.supportedAssetClasses.includes('crypto')
    )).toBe(true);
  });

  it('should retry failed deliveries', async () => {
    // Mock gateway to fail first 2 attempts
    const rfq = await createValidatedRfq();
    await distributionService.distributeRfq(rfq.id);
    
    const distribution = await findDistribution(rfq.id);
    expect(distribution.retryCount).toBeLessThanOrEqual(3);
  });
});
```
