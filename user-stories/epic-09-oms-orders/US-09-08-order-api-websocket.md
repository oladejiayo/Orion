# User Story: US-09-08 - Order API and WebSocket

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-09-08 |
| **Epic** | Epic 09 - OMS Orders V1 |
| **Title** | Order API and WebSocket |
| **Priority** | P0 - Critical |
| **Story Points** | 8 |
| **PRD Reference** | FR-Order-08, NFR-Performance-02 |

## User Story

**As a** trading application  
**I want** REST APIs and WebSocket connections for orders  
**So that** I can submit, manage, and monitor orders in real-time

## Description

Implement comprehensive REST API endpoints for order submission and management, plus WebSocket channels for real-time order updates, fills, and status changes.

## Acceptance Criteria

- [ ] REST API for order submission
- [ ] REST API for order queries
- [ ] REST API for order modification
- [ ] REST API for order cancellation
- [ ] WebSocket for real-time order updates
- [ ] WebSocket for fill notifications
- [ ] Pagination and filtering
- [ ] Rate limiting
- [ ] OpenAPI documentation

## Technical Details

### Order DTOs

```typescript
// services/order-service/src/dto/order.dto.ts
import {
  IsUUID, IsString, IsNumber, IsEnum, IsOptional,
  IsArray, Min, Max, ValidateNested, IsDateString,
} from 'class-validator';
import { Type, Transform } from 'class-transformer';
import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';

export enum OrderSide {
  BUY = 'buy',
  SELL = 'sell',
}

export enum OrderType {
  MARKET = 'market',
  LIMIT = 'limit',
  STOP = 'stop',
  STOP_LIMIT = 'stop_limit',
  PEGGED = 'pegged',
  TRAILING_STOP = 'trailing_stop',
}

export enum TimeInForce {
  GTC = 'gtc',
  DAY = 'day',
  IOC = 'ioc',
  FOK = 'fok',
  GTD = 'gtd',
}

export class CreateOrderDto {
  @ApiProperty({ description: 'Client ID placing the order' })
  @IsUUID()
  clientId: string;

  @ApiProperty({ description: 'Instrument identifier' })
  @IsString()
  instrumentId: string;

  @ApiProperty({ enum: OrderSide })
  @IsEnum(OrderSide)
  side: OrderSide;

  @ApiProperty({ enum: OrderType })
  @IsEnum(OrderType)
  orderType: OrderType;

  @ApiProperty({ enum: TimeInForce })
  @IsEnum(TimeInForce)
  timeInForce: TimeInForce;

  @ApiProperty({ description: 'Order quantity' })
  @IsNumber()
  @Min(0.00000001)
  quantity: number;

  @ApiPropertyOptional({ description: 'Limit price (required for limit/stop_limit)' })
  @IsOptional()
  @IsNumber()
  @Min(0)
  price?: number;

  @ApiPropertyOptional({ description: 'Stop price (required for stop/stop_limit)' })
  @IsOptional()
  @IsNumber()
  @Min(0)
  stopPrice?: number;

  @ApiPropertyOptional({ description: 'Peg offset for pegged orders' })
  @IsOptional()
  @IsNumber()
  pegOffset?: number;

  @ApiPropertyOptional({ description: 'Trail amount for trailing stops' })
  @IsOptional()
  @IsNumber()
  @Min(0)
  trailAmount?: number;

  @ApiPropertyOptional({ description: 'Expiry date for GTD orders' })
  @IsOptional()
  @IsDateString()
  expiryDate?: string;

  @ApiPropertyOptional({ description: 'Routing strategy', default: 'best' })
  @IsOptional()
  @IsString()
  routingStrategy?: string;

  @ApiPropertyOptional({ description: 'Client order reference' })
  @IsOptional()
  @IsString()
  clientOrderId?: string;

  @ApiPropertyOptional({ description: 'Additional metadata' })
  @IsOptional()
  metadata?: any;
}

export class OrderQueryDto {
  @ApiPropertyOptional()
  @IsOptional()
  @IsUUID()
  clientId?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  instrumentId?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsArray()
  @IsString({ each: true })
  statuses?: string[];

  @ApiPropertyOptional()
  @IsOptional()
  @IsEnum(OrderSide)
  side?: OrderSide;

  @ApiPropertyOptional()
  @IsOptional()
  @IsDateString()
  fromDate?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsDateString()
  toDate?: string;

  @ApiPropertyOptional({ default: 1 })
  @IsOptional()
  @Type(() => Number)
  @IsNumber()
  @Min(1)
  page?: number = 1;

  @ApiPropertyOptional({ default: 50 })
  @IsOptional()
  @Type(() => Number)
  @IsNumber()
  @Min(1)
  @Max(100)
  limit?: number = 50;
}

export class OrderResponseDto {
  @ApiProperty()
  id: string;

  @ApiProperty()
  orderRef: string;

  @ApiPropertyOptional()
  clientOrderId?: string;

  @ApiProperty()
  clientId: string;

  @ApiProperty()
  instrumentId: string;

  @ApiProperty()
  side: string;

  @ApiProperty()
  orderType: string;

  @ApiProperty()
  timeInForce: string;

  @ApiProperty()
  quantity: number;

  @ApiProperty()
  filledQuantity: number;

  @ApiProperty()
  remainingQuantity: number;

  @ApiPropertyOptional()
  price?: number;

  @ApiPropertyOptional()
  stopPrice?: number;

  @ApiPropertyOptional()
  averagePrice?: number;

  @ApiProperty()
  status: string;

  @ApiProperty()
  createdAt: Date;

  @ApiPropertyOptional()
  updatedAt?: Date;
}

export class PaginatedOrdersDto {
  @ApiProperty({ type: [OrderResponseDto] })
  data: OrderResponseDto[];

  @ApiProperty()
  total: number;

  @ApiProperty()
  page: number;

  @ApiProperty()
  limit: number;

  @ApiProperty()
  totalPages: number;
}
```

### Order Controller

```typescript
// services/order-service/src/controllers/order.controller.ts
import {
  Controller, Get, Post, Put, Delete, Body, Param, Query,
  UseGuards, UsePipes, ValidationPipe, HttpCode, HttpStatus,
} from '@nestjs/common';
import {
  ApiTags, ApiOperation, ApiResponse, ApiBearerAuth,
  ApiParam, ApiQuery,
} from '@nestjs/swagger';
import { TenantGuard, TenantId, UserId } from '@orion/auth';
import { RateLimitGuard, RateLimit } from '@orion/rate-limit';
import { OrderService } from '../services/order.service';
import { OrderModificationService } from '../modification/order-modification.service';
import {
  CreateOrderDto, ModifyOrderDto, CancelOrderDto,
  OrderQueryDto, OrderResponseDto, PaginatedOrdersDto,
} from '../dto';

@ApiTags('Orders')
@ApiBearerAuth()
@Controller('api/v1/orders')
@UseGuards(TenantGuard, RateLimitGuard)
@UsePipes(new ValidationPipe({ transform: true, whitelist: true }))
export class OrderController {
  constructor(
    private readonly orderService: OrderService,
    private readonly modificationService: OrderModificationService,
  ) {}

  @Post()
  @ApiOperation({ summary: 'Submit a new order' })
  @ApiResponse({ status: 201, type: OrderResponseDto })
  @ApiResponse({ status: 400, description: 'Validation error' })
  @ApiResponse({ status: 429, description: 'Rate limit exceeded' })
  @RateLimit({ points: 100, duration: 60 })
  async submitOrder(
    @Body() dto: CreateOrderDto,
    @TenantId() tenantId: string,
    @UserId() userId: string,
  ): Promise<OrderResponseDto> {
    const order = await this.orderService.submitOrder(dto, tenantId, userId);
    return this.toResponse(order);
  }

  @Get()
  @ApiOperation({ summary: 'Query orders with filters' })
  @ApiResponse({ status: 200, type: PaginatedOrdersDto })
  async getOrders(
    @Query() query: OrderQueryDto,
    @TenantId() tenantId: string,
  ): Promise<PaginatedOrdersDto> {
    const result = await this.orderService.queryOrders(tenantId, query);
    return {
      data: result.data.map(o => this.toResponse(o)),
      total: result.total,
      page: result.page,
      limit: result.limit,
      totalPages: Math.ceil(result.total / result.limit),
    };
  }

  @Get(':id')
  @ApiOperation({ summary: 'Get order by ID' })
  @ApiParam({ name: 'id', description: 'Order UUID' })
  @ApiResponse({ status: 200, type: OrderResponseDto })
  @ApiResponse({ status: 404, description: 'Order not found' })
  async getOrder(
    @Param('id') id: string,
    @TenantId() tenantId: string,
  ): Promise<OrderResponseDto> {
    const order = await this.orderService.getOrder(id, tenantId);
    return this.toResponse(order);
  }

  @Get('ref/:orderRef')
  @ApiOperation({ summary: 'Get order by reference' })
  @ApiParam({ name: 'orderRef', description: 'Order reference' })
  @ApiResponse({ status: 200, type: OrderResponseDto })
  async getOrderByRef(
    @Param('orderRef') orderRef: string,
    @TenantId() tenantId: string,
  ): Promise<OrderResponseDto> {
    const order = await this.orderService.getOrderByRef(orderRef, tenantId);
    return this.toResponse(order);
  }

  @Put(':id')
  @ApiOperation({ summary: 'Modify an existing order' })
  @ApiParam({ name: 'id', description: 'Order UUID' })
  @ApiResponse({ status: 200, type: OrderResponseDto })
  @ApiResponse({ status: 400, description: 'Invalid modification' })
  @RateLimit({ points: 50, duration: 60 })
  async modifyOrder(
    @Param('id') id: string,
    @Body() dto: Omit<ModifyOrderDto, 'orderId'>,
    @TenantId() tenantId: string,
    @UserId() userId: string,
  ): Promise<OrderResponseDto> {
    const order = await this.modificationService.modifyOrder(
      { ...dto, orderId: id },
      userId,
    );
    return this.toResponse(order);
  }

  @Delete(':id')
  @HttpCode(HttpStatus.OK)
  @ApiOperation({ summary: 'Cancel an order' })
  @ApiParam({ name: 'id', description: 'Order UUID' })
  @ApiResponse({ status: 200, type: OrderResponseDto })
  @ApiResponse({ status: 400, description: 'Order cannot be cancelled' })
  @RateLimit({ points: 50, duration: 60 })
  async cancelOrder(
    @Param('id') id: string,
    @Query('reason') reason: string,
    @TenantId() tenantId: string,
    @UserId() userId: string,
  ): Promise<OrderResponseDto> {
    const order = await this.modificationService.cancelOrder(
      { orderId: id, reason },
      userId,
    );
    return this.toResponse(order);
  }

  @Delete()
  @HttpCode(HttpStatus.OK)
  @ApiOperation({ summary: 'Bulk cancel orders' })
  @ApiQuery({ name: 'ids', description: 'Comma-separated order IDs' })
  async bulkCancel(
    @Query('ids') ids: string,
    @Query('reason') reason: string,
    @TenantId() tenantId: string,
    @UserId() userId: string,
  ): Promise<{ cancelled: string[]; failed: { orderId: string; reason: string }[] }> {
    const orderIds = ids.split(',').map(id => id.trim());
    return this.modificationService.bulkCancel(orderIds, userId, reason);
  }

  @Get(':id/fills')
  @ApiOperation({ summary: 'Get fills for an order' })
  @ApiParam({ name: 'id', description: 'Order UUID' })
  async getOrderFills(
    @Param('id') id: string,
    @TenantId() tenantId: string,
  ): Promise<any[]> {
    return this.orderService.getOrderFills(id, tenantId);
  }

  @Get(':id/history')
  @ApiOperation({ summary: 'Get modification history for an order' })
  @ApiParam({ name: 'id', description: 'Order UUID' })
  async getOrderHistory(
    @Param('id') id: string,
    @TenantId() tenantId: string,
  ): Promise<any[]> {
    return this.modificationService.getModificationHistory(id);
  }

  private toResponse(order: any): OrderResponseDto {
    return {
      id: order.id,
      orderRef: order.orderRef,
      clientOrderId: order.clientOrderId,
      clientId: order.clientId,
      instrumentId: order.instrumentId,
      side: order.side,
      orderType: order.orderType,
      timeInForce: order.timeInForce,
      quantity: Number(order.quantity),
      filledQuantity: Number(order.filledQuantity),
      remainingQuantity: Number(order.remainingQuantity),
      price: order.price ? Number(order.price) : undefined,
      stopPrice: order.stopPrice ? Number(order.stopPrice) : undefined,
      averagePrice: order.averagePrice ? Number(order.averagePrice) : undefined,
      status: order.status,
      createdAt: order.createdAt,
      updatedAt: order.updatedAt,
    };
  }
}
```

### WebSocket Gateway

```typescript
// services/order-service/src/gateways/order.gateway.ts
import {
  WebSocketGateway, WebSocketServer, SubscribeMessage,
  OnGatewayConnection, OnGatewayDisconnect, MessageBody,
  ConnectedSocket,
} from '@nestjs/websockets';
import { Server, Socket } from 'socket.io';
import { UseGuards } from '@nestjs/common';
import { WsAuthGuard, WsTenantExtractor } from '@orion/auth';
import { logger, metrics } from '@orion/observability';

interface SubscriptionPayload {
  channel: 'orders' | 'fills' | 'order';
  clientId?: string;
  orderId?: string;
  instrumentId?: string;
}

@WebSocketGateway({
  namespace: '/ws/orders',
  cors: { origin: '*' },
  transports: ['websocket'],
})
@UseGuards(WsAuthGuard)
export class OrderGateway implements OnGatewayConnection, OnGatewayDisconnect {
  @WebSocketServer()
  server: Server;

  private subscriptions = new Map<string, Set<string>>();

  async handleConnection(client: Socket): Promise<void> {
    try {
      const tenantId = WsTenantExtractor.extract(client);
      client.data.tenantId = tenantId;
      client.join(`tenant:${tenantId}`);

      logger.info('WebSocket client connected', {
        clientId: client.id,
        tenantId,
      });

      metrics.increment('websocket.connections');
    } catch (error) {
      logger.error('WebSocket connection rejected', { error });
      client.disconnect();
    }
  }

  async handleDisconnect(client: Socket): Promise<void> {
    // Clean up subscriptions
    this.subscriptions.delete(client.id);

    logger.info('WebSocket client disconnected', {
      clientId: client.id,
    });

    metrics.decrement('websocket.connections');
  }

  @SubscribeMessage('subscribe')
  handleSubscribe(
    @ConnectedSocket() client: Socket,
    @MessageBody() payload: SubscriptionPayload,
  ): { success: boolean; channel: string } {
    const { channel, clientId, orderId, instrumentId } = payload;
    const tenantId = client.data.tenantId;

    let room: string;

    switch (channel) {
      case 'orders':
        // Subscribe to all orders (optionally filtered)
        room = clientId
          ? `orders:client:${clientId}`
          : instrumentId
            ? `orders:instrument:${instrumentId}`
            : `orders:tenant:${tenantId}`;
        break;

      case 'fills':
        // Subscribe to fill events
        room = clientId
          ? `fills:client:${clientId}`
          : `fills:tenant:${tenantId}`;
        break;

      case 'order':
        // Subscribe to specific order
        if (!orderId) {
          return { success: false, channel: 'order' };
        }
        room = `order:${orderId}`;
        break;

      default:
        return { success: false, channel };
    }

    client.join(room);

    // Track subscription
    if (!this.subscriptions.has(client.id)) {
      this.subscriptions.set(client.id, new Set());
    }
    this.subscriptions.get(client.id).add(room);

    logger.debug('Client subscribed', {
      clientId: client.id,
      room,
    });

    return { success: true, channel: room };
  }

  @SubscribeMessage('unsubscribe')
  handleUnsubscribe(
    @ConnectedSocket() client: Socket,
    @MessageBody() payload: { room: string },
  ): { success: boolean } {
    client.leave(payload.room);
    this.subscriptions.get(client.id)?.delete(payload.room);

    return { success: true };
  }

  /**
   * Broadcast order update to subscribed clients
   */
  async broadcastOrderUpdate(order: any): Promise<void> {
    const payload = {
      type: 'order_update',
      data: order,
      timestamp: new Date().toISOString(),
    };

    // Broadcast to tenant
    this.server.to(`orders:tenant:${order.tenantId}`).emit('order', payload);

    // Broadcast to client-specific room
    this.server.to(`orders:client:${order.clientId}`).emit('order', payload);

    // Broadcast to instrument room
    this.server.to(`orders:instrument:${order.instrumentId}`).emit('order', payload);

    // Broadcast to specific order room
    this.server.to(`order:${order.id}`).emit('order', payload);

    metrics.increment('websocket.broadcasts.orders');
  }

  /**
   * Broadcast fill notification to subscribed clients
   */
  async broadcastFill(fill: any, order: any): Promise<void> {
    const payload = {
      type: 'fill',
      data: {
        fillId: fill.id,
        orderId: order.id,
        orderRef: order.orderRef,
        quantity: fill.quantity,
        price: fill.price,
        filledQuantity: order.filledQuantity,
        remainingQuantity: order.remainingQuantity,
        averagePrice: order.averagePrice,
        isComplete: Number(order.remainingQuantity) === 0,
      },
      timestamp: new Date().toISOString(),
    };

    // Broadcast to tenant
    this.server.to(`fills:tenant:${order.tenantId}`).emit('fill', payload);

    // Broadcast to client-specific room
    this.server.to(`fills:client:${order.clientId}`).emit('fill', payload);

    // Broadcast to specific order room
    this.server.to(`order:${order.id}`).emit('fill', payload);

    metrics.increment('websocket.broadcasts.fills');
  }

  /**
   * Broadcast order status change
   */
  async broadcastStatusChange(order: any, previousStatus: string): Promise<void> {
    const payload = {
      type: 'status_change',
      data: {
        orderId: order.id,
        orderRef: order.orderRef,
        previousStatus,
        newStatus: order.status,
        filledQuantity: order.filledQuantity,
        remainingQuantity: order.remainingQuantity,
      },
      timestamp: new Date().toISOString(),
    };

    this.server.to(`order:${order.id}`).emit('status', payload);
    this.server.to(`orders:tenant:${order.tenantId}`).emit('status', payload);

    metrics.increment('websocket.broadcasts.status');
  }
}
```

### Event Listener for WebSocket Broadcasts

```typescript
// services/order-service/src/listeners/order-event.listener.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { EventSubscriber } from '@orion/events';
import { OrderGateway } from '../gateways/order.gateway';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { OrderEntity } from '../entities/order.entity';

@Injectable()
export class OrderEventListener implements OnModuleInit {
  constructor(
    private readonly eventSubscriber: EventSubscriber,
    private readonly orderGateway: OrderGateway,
    @InjectRepository(OrderEntity)
    private readonly orderRepo: Repository<OrderEntity>,
  ) {}

  async onModuleInit(): Promise<void> {
    // Subscribe to order events
    await this.eventSubscriber.subscribe('order.*', async (event) => {
      await this.handleOrderEvent(event);
    });
  }

  private async handleOrderEvent(event: any): Promise<void> {
    const { type, payload, aggregateId } = event;

    // Get fresh order data
    const order = await this.orderRepo.findOne({ where: { id: aggregateId } });
    if (!order) return;

    switch (type) {
      case 'order.created':
      case 'order.validated':
      case 'order.working':
      case 'order.modified':
        await this.orderGateway.broadcastOrderUpdate(order);
        break;

      case 'order.partial_fill':
      case 'order.filled':
        await this.orderGateway.broadcastFill(payload, order);
        await this.orderGateway.broadcastOrderUpdate(order);
        break;

      case 'order.cancelled':
      case 'order.rejected':
      case 'order.expired':
        await this.orderGateway.broadcastStatusChange(order, payload.previousStatus);
        break;
    }
  }
}
```

### OpenAPI Configuration

```typescript
// services/order-service/src/swagger.config.ts
import { DocumentBuilder, SwaggerModule } from '@nestjs/swagger';
import { INestApplication } from '@nestjs/common';

export function setupSwagger(app: INestApplication): void {
  const config = new DocumentBuilder()
    .setTitle('Orion Order Service API')
    .setDescription('Order management API for the Orion trading platform')
    .setVersion('1.0.0')
    .addBearerAuth()
    .addTag('Orders', 'Order submission and management')
    .build();

  const document = SwaggerModule.createDocument(app, config);
  SwaggerModule.setup('api/docs', app, document);
}
```

## WebSocket Message Types

| Event | Direction | Payload |
|-------|-----------|---------|
| `subscribe` | Client → Server | `{ channel, clientId?, orderId? }` |
| `unsubscribe` | Client → Server | `{ room }` |
| `order` | Server → Client | Order update |
| `fill` | Server → Client | Fill notification |
| `status` | Server → Client | Status change |
| `error` | Server → Client | Error message |

## API Endpoints Summary

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/orders` | Submit new order |
| `GET` | `/api/v1/orders` | Query orders |
| `GET` | `/api/v1/orders/:id` | Get order by ID |
| `GET` | `/api/v1/orders/ref/:orderRef` | Get order by reference |
| `PUT` | `/api/v1/orders/:id` | Modify order |
| `DELETE` | `/api/v1/orders/:id` | Cancel order |
| `DELETE` | `/api/v1/orders` | Bulk cancel |
| `GET` | `/api/v1/orders/:id/fills` | Get order fills |
| `GET` | `/api/v1/orders/:id/history` | Get modification history |

## Definition of Done

- [ ] REST API for CRUD operations
- [ ] WebSocket gateway
- [ ] Real-time order updates
- [ ] Real-time fill notifications
- [ ] Pagination and filtering
- [ ] Rate limiting
- [ ] OpenAPI documentation
- [ ] Authentication/authorization
- [ ] Error handling

## Dependencies

- **US-09-01**: Order Entity
- **US-09-07**: Order Modification Service

## Test Cases

```typescript
describe('OrderController', () => {
  it('should submit a new order', async () => {
    const dto = createOrderDto();

    const response = await request(app.getHttpServer())
      .post('/api/v1/orders')
      .set('Authorization', `Bearer ${token}`)
      .send(dto)
      .expect(201);

    expect(response.body.orderRef).toBeDefined();
    expect(response.body.status).toBe('pending');
  });

  it('should query orders with filters', async () => {
    const response = await request(app.getHttpServer())
      .get('/api/v1/orders')
      .query({ clientId: 'client-1', statuses: 'working,partial_fill' })
      .set('Authorization', `Bearer ${token}`)
      .expect(200);

    expect(response.body.data).toBeArray();
    expect(response.body.total).toBeDefined();
  });

  it('should enforce rate limits', async () => {
    // Exceed rate limit
    for (let i = 0; i < 101; i++) {
      await request(app.getHttpServer())
        .post('/api/v1/orders')
        .set('Authorization', `Bearer ${token}`)
        .send(createOrderDto());
    }

    const response = await request(app.getHttpServer())
      .post('/api/v1/orders')
      .set('Authorization', `Bearer ${token}`)
      .send(createOrderDto())
      .expect(429);
  });
});

describe('OrderGateway', () => {
  it('should receive order updates via WebSocket', async () => {
    const client = io('ws://localhost:3000/ws/orders', { auth: { token } });

    const updates = [];
    client.on('order', (data) => updates.push(data));

    client.emit('subscribe', { channel: 'orders' });

    // Submit order via API
    await request(app.getHttpServer())
      .post('/api/v1/orders')
      .set('Authorization', `Bearer ${token}`)
      .send(createOrderDto());

    await waitFor(() => updates.length > 0);
    expect(updates[0].type).toBe('order_update');
  });

  it('should receive fill notifications', async () => {
    const client = io('ws://localhost:3000/ws/orders');
    
    const fills = [];
    client.on('fill', (data) => fills.push(data));

    client.emit('subscribe', { channel: 'order', orderId: 'order-123' });

    // Simulate fill
    await fillProcessor.processExecutionReport({
      lpOrderId: 'order-123',
      quantity: 100000,
      price: 1.0850,
    });

    await waitFor(() => fills.length > 0);
    expect(fills[0].data.quantity).toBe(100000);
  });
});
```
