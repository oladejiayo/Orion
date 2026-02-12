# US-12-05: Dashboard API

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-12-05 |
| **Epic** | Epic 12: Analytics & Data Products |
| **Title** | Dashboard API |
| **Priority** | Medium |
| **Story Points** | 8 |
| **Status** | Ready for Development |

## User Story

**As a** dashboard user  
**I want** APIs to fetch dashboard configurations and widget data  
**So that** I can build interactive dashboards with real-time analytics

## Acceptance Criteria

### AC1: Dashboard Configuration Management
- **Given** a user with dashboard permissions
- **When** they create or modify a dashboard
- **Then** the system:
  - Stores dashboard layout and widget configurations
  - Supports default dashboards per role
  - Allows dashboard duplication and sharing
  - Maintains version history

### AC2: Widget Data Retrieval
- **Given** a dashboard with multiple widgets
- **When** the dashboard is loaded
- **Then** widget data is fetched:
  - Parallel queries for independent widgets
  - Cached results for performance
  - Proper error handling per widget
  - Refresh indicators for stale data

### AC3: Dashboard Personalization
- **Given** a shared or default dashboard
- **When** a user personalizes widget settings
- **Then** personalizations are stored:
  - User-specific filter preferences
  - Widget collapse/expand states
  - Custom date ranges
  - Layout modifications (if allowed)

### AC4: Real-Time Dashboard Updates
- **Given** a dashboard with real-time widgets
- **When** underlying data changes
- **Then** updates are pushed:
  - WebSocket subscription per widget
  - Configurable refresh intervals
  - Graceful degradation on connection loss
  - Update batching for performance

### AC5: Dashboard Export
- **Given** a dashboard with visualizations
- **When** export is requested
- **Then** the dashboard is exported:
  - PNG screenshot of full dashboard
  - PDF with all widgets
  - Scheduled snapshots to email

## Technical Specification

### Dashboard Entities

```typescript
// src/analytics/dashboards/entities/dashboard.entity.ts
import { Entity, PrimaryGeneratedColumn, Column, Index, CreateDateColumn, UpdateDateColumn } from 'typeorm';

export enum DashboardType {
  TRADING = 'TRADING',
  RISK = 'RISK',
  CLIENT = 'CLIENT',
  OPERATIONS = 'OPERATIONS',
  EXECUTIVE = 'EXECUTIVE',
  CUSTOM = 'CUSTOM',
}

export enum WidgetType {
  KPI = 'KPI',
  LINE_CHART = 'LINE_CHART',
  BAR_CHART = 'BAR_CHART',
  PIE_CHART = 'PIE_CHART',
  AREA_CHART = 'AREA_CHART',
  TABLE = 'TABLE',
  HEATMAP = 'HEATMAP',
  GAUGE = 'GAUGE',
  MAP = 'MAP',
  SPARKLINE = 'SPARKLINE',
  TICKER = 'TICKER',
}

@Entity('dashboards')
@Index(['tenantId', 'createdBy'])
@Index(['tenantId', 'isDefault'])
export class DashboardEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ type: 'uuid' })
  tenantId: string;

  @Column({ type: 'varchar', length: 255 })
  name: string;

  @Column({ type: 'text', nullable: true })
  description: string;

  @Column({ type: 'varchar', length: 50 })
  dashboardType: DashboardType;

  @Column({ type: 'jsonb' })
  layout: DashboardLayout;

  @Column({ type: 'jsonb' })
  widgets: DashboardWidget[];

  @Column({ type: 'jsonb', nullable: true })
  globalFilters: GlobalFilter[];

  @Column({ type: 'integer', default: 300 })
  refreshIntervalSeconds: number;

  @Column({ type: 'boolean', default: false })
  isDefault: boolean;

  @Column({ type: 'varchar', array: true, default: '{}' })
  defaultForRoles: string[];

  @Column({ type: 'varchar', length: 20, default: 'PRIVATE' })
  visibility: 'PRIVATE' | 'SHARED' | 'PUBLIC';

  @Column({ type: 'uuid', array: true, default: '{}' })
  sharedWithUsers: string[];

  @Column({ type: 'uuid' })
  createdBy: string;

  @Column({ type: 'boolean', default: true })
  isActive: boolean;

  @CreateDateColumn()
  createdAt: Date;

  @UpdateDateColumn()
  updatedAt: Date;
}

interface DashboardLayout {
  type: 'grid' | 'freeform';
  columns: number;
  rowHeight: number;
  margin: [number, number];
  containerPadding: [number, number];
}

interface DashboardWidget {
  id: string;
  type: WidgetType;
  title: string;
  subtitle?: string;
  
  // Position and size
  position: {
    x: number;
    y: number;
    w: number;
    h: number;
    minW?: number;
    minH?: number;
    maxW?: number;
    maxH?: number;
  };
  
  // Data configuration
  dataSource: WidgetDataSource;
  
  // Visual configuration
  visualization: VisualizationConfig;
  
  // Behavior
  refreshIntervalSeconds?: number;
  isRealtime?: boolean;
  drilldownEnabled?: boolean;
  drilldownConfig?: DrilldownConfig;
}

interface WidgetDataSource {
  type: 'query' | 'api' | 'websocket';
  
  // For query type
  query?: {
    dimensions: any[];
    measures: any[];
    filters: any[];
    orderBy?: any[];
    limit?: number;
  };
  
  // For API type
  endpoint?: string;
  method?: 'GET' | 'POST';
  
  // For WebSocket type
  channel?: string;
  
  // Parameter mapping from global filters
  parameterMapping?: Record<string, string>;
}

interface VisualizationConfig {
  // Chart specific
  chartType?: string;
  xAxis?: { field: string; label?: string; format?: string };
  yAxis?: { field: string; label?: string; format?: string };
  series?: { field: string; label?: string; color?: string }[];
  
  // KPI specific
  valueField?: string;
  valueFormat?: string;
  comparisonField?: string;
  comparisonLabel?: string;
  thresholds?: { value: number; color: string }[];
  
  // Table specific
  columns?: { field: string; label: string; width?: number; format?: string }[];
  pagination?: boolean;
  sorting?: boolean;
  
  // Common
  colors?: string[];
  showLegend?: boolean;
  showTooltip?: boolean;
  animation?: boolean;
}

interface GlobalFilter {
  id: string;
  name: string;
  label: string;
  type: 'date_range' | 'select' | 'multi_select' | 'search';
  defaultValue?: any;
  options?: { value: any; label: string }[];
  dynamicOptions?: {
    source: string;
    valueField: string;
    labelField: string;
  };
}

interface DrilldownConfig {
  targetDashboard?: string;
  targetReport?: string;
  parameterMapping: Record<string, string>;
}

// User personalization
@Entity('dashboard_personalizations')
@Index(['userId', 'dashboardId'], { unique: true })
export class DashboardPersonalizationEntity {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ type: 'uuid' })
  userId: string;

  @Column({ type: 'uuid' })
  dashboardId: string;

  @Column({ type: 'jsonb', nullable: true })
  filterPreferences: Record<string, any>;

  @Column({ type: 'jsonb', nullable: true })
  widgetStates: Record<string, { collapsed?: boolean; hidden?: boolean }>;

  @Column({ type: 'jsonb', nullable: true })
  layoutOverrides: any;

  @CreateDateColumn()
  createdAt: Date;

  @UpdateDateColumn()
  updatedAt: Date;
}
```

### Dashboard Service

```typescript
// src/analytics/dashboards/services/dashboard.service.ts
import { Injectable, Logger, NotFoundException, ForbiddenException } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { DashboardEntity, DashboardType, WidgetType } from '../entities/dashboard.entity';
import { DashboardPersonalizationEntity } from '../entities/dashboard-personalization.entity';
import { OlapQueryEngineService } from '../../query-engine/services/olap-query-engine.service';
import { DashboardWebSocketGateway } from '../gateways/dashboard-websocket.gateway';

export interface CreateDashboardDto {
  name: string;
  description?: string;
  dashboardType: DashboardType;
  layout: any;
  widgets: any[];
  globalFilters?: any[];
  refreshIntervalSeconds?: number;
  isDefault?: boolean;
  defaultForRoles?: string[];
  visibility?: 'PRIVATE' | 'SHARED' | 'PUBLIC';
  sharedWithUsers?: string[];
}

export interface WidgetDataRequest {
  dashboardId: string;
  widgetId: string;
  filters?: Record<string, any>;
  pagination?: { page: number; pageSize: number };
}

export interface WidgetDataResponse {
  widgetId: string;
  data: any;
  metadata: {
    rowCount: number;
    lastUpdated: Date;
    executionTimeMs: number;
    cached: boolean;
  };
}

@Injectable()
export class DashboardService {
  private readonly logger = new Logger(DashboardService.name);

  constructor(
    @InjectRepository(DashboardEntity)
    private readonly dashboardRepo: Repository<DashboardEntity>,
    @InjectRepository(DashboardPersonalizationEntity)
    private readonly personalizationRepo: Repository<DashboardPersonalizationEntity>,
    private readonly queryEngine: OlapQueryEngineService,
    private readonly wsGateway: DashboardWebSocketGateway,
  ) {}

  async createDashboard(
    tenantId: string,
    userId: string,
    dto: CreateDashboardDto,
  ): Promise<DashboardEntity> {
    // Validate widgets
    this.validateWidgets(dto.widgets);

    const dashboard = this.dashboardRepo.create({
      tenantId,
      name: dto.name,
      description: dto.description,
      dashboardType: dto.dashboardType,
      layout: dto.layout,
      widgets: dto.widgets,
      globalFilters: dto.globalFilters || [],
      refreshIntervalSeconds: dto.refreshIntervalSeconds || 300,
      isDefault: dto.isDefault || false,
      defaultForRoles: dto.defaultForRoles || [],
      visibility: dto.visibility || 'PRIVATE',
      sharedWithUsers: dto.sharedWithUsers || [],
      createdBy: userId,
      isActive: true,
    });

    const saved = await this.dashboardRepo.save(dashboard);
    this.logger.log(`Created dashboard ${saved.id}: ${saved.name}`);
    return saved;
  }

  async getDashboard(
    tenantId: string,
    userId: string,
    dashboardId: string,
    userRoles: string[],
  ): Promise<DashboardEntity & { personalization?: DashboardPersonalizationEntity }> {
    const dashboard = await this.dashboardRepo.findOne({
      where: { id: dashboardId, tenantId, isActive: true },
    });

    if (!dashboard) {
      throw new NotFoundException('Dashboard not found');
    }

    // Check access
    if (!this.hasAccess(dashboard, userId, userRoles)) {
      throw new ForbiddenException('Access denied');
    }

    // Get personalization
    const personalization = await this.personalizationRepo.findOne({
      where: { userId, dashboardId },
    });

    return { ...dashboard, personalization };
  }

  async getDefaultDashboard(
    tenantId: string,
    userId: string,
    userRoles: string[],
    dashboardType?: DashboardType,
  ): Promise<DashboardEntity | null> {
    // First check for role-specific default
    const roleDefault = await this.dashboardRepo
      .createQueryBuilder('d')
      .where('d.tenantId = :tenantId', { tenantId })
      .andWhere('d.isActive = true')
      .andWhere('d.isDefault = true')
      .andWhere('d.defaultForRoles && :roles', { roles: userRoles })
      .andWhere(dashboardType ? 'd.dashboardType = :type' : '1=1', { type: dashboardType })
      .getOne();

    if (roleDefault) return roleDefault;

    // Fall back to tenant default
    return this.dashboardRepo.findOne({
      where: {
        tenantId,
        isActive: true,
        isDefault: true,
        defaultForRoles: [],
        ...(dashboardType && { dashboardType }),
      },
    });
  }

  async listDashboards(
    tenantId: string,
    userId: string,
    userRoles: string[],
    options: {
      type?: DashboardType;
      visibility?: string;
      limit?: number;
      offset?: number;
    } = {},
  ): Promise<{ dashboards: DashboardEntity[]; total: number }> {
    const query = this.dashboardRepo
      .createQueryBuilder('d')
      .where('d.tenantId = :tenantId', { tenantId })
      .andWhere('d.isActive = true')
      .andWhere(
        `(d.visibility = 'PUBLIC' 
          OR d.createdBy = :userId 
          OR :userId = ANY(d.sharedWithUsers))`,
        { userId },
      );

    if (options.type) {
      query.andWhere('d.dashboardType = :type', { type: options.type });
    }

    if (options.visibility) {
      query.andWhere('d.visibility = :visibility', { visibility: options.visibility });
    }

    const [dashboards, total] = await query
      .orderBy('d.updatedAt', 'DESC')
      .limit(options.limit || 20)
      .offset(options.offset || 0)
      .getManyAndCount();

    return { dashboards, total };
  }

  async getWidgetData(
    tenantId: string,
    userId: string,
    request: WidgetDataRequest,
  ): Promise<WidgetDataResponse> {
    const startTime = Date.now();

    const dashboard = await this.dashboardRepo.findOne({
      where: { id: request.dashboardId, tenantId },
    });

    if (!dashboard) {
      throw new NotFoundException('Dashboard not found');
    }

    const widget = dashboard.widgets.find(w => w.id === request.widgetId);
    if (!widget) {
      throw new NotFoundException('Widget not found');
    }

    let data: any;
    let cached = false;

    if (widget.dataSource.type === 'query') {
      const result = await this.executeWidgetQuery(
        tenantId,
        widget.dataSource.query!,
        request.filters,
        request.pagination,
      );
      data = result.data;
      cached = result.cached;
    } else if (widget.dataSource.type === 'api') {
      data = await this.fetchWidgetApi(widget.dataSource);
    }

    return {
      widgetId: request.widgetId,
      data,
      metadata: {
        rowCount: Array.isArray(data) ? data.length : 1,
        lastUpdated: new Date(),
        executionTimeMs: Date.now() - startTime,
        cached,
      },
    };
  }

  async getAllWidgetData(
    tenantId: string,
    userId: string,
    dashboardId: string,
    filters?: Record<string, any>,
  ): Promise<WidgetDataResponse[]> {
    const dashboard = await this.dashboardRepo.findOne({
      where: { id: dashboardId, tenantId },
    });

    if (!dashboard) {
      throw new NotFoundException('Dashboard not found');
    }

    // Execute all widget queries in parallel
    const widgetPromises = dashboard.widgets.map(widget =>
      this.getWidgetData(tenantId, userId, {
        dashboardId,
        widgetId: widget.id,
        filters,
      }).catch(error => ({
        widgetId: widget.id,
        data: null,
        error: error.message,
        metadata: {
          rowCount: 0,
          lastUpdated: new Date(),
          executionTimeMs: 0,
          cached: false,
        },
      })),
    );

    return Promise.all(widgetPromises);
  }

  private async executeWidgetQuery(
    tenantId: string,
    queryDef: any,
    filters?: Record<string, any>,
    pagination?: { page: number; pageSize: number },
  ): Promise<{ data: any; cached: boolean }> {
    // Merge global filters with widget filters
    const mergedFilters = [
      ...queryDef.filters,
      ...Object.entries(filters || {}).map(([dimension, value]) => ({
        dimension,
        operator: 'EQ',
        value,
      })),
    ];

    const query = {
      tenantId,
      dimensions: queryDef.dimensions,
      measures: queryDef.measures,
      filters: mergedFilters,
      orderBy: queryDef.orderBy,
      limit: pagination ? pagination.pageSize : queryDef.limit,
      offset: pagination ? (pagination.page - 1) * pagination.pageSize : 0,
      options: {
        useCache: true,
        cacheTtlSeconds: 60, // Shorter cache for dashboards
      },
    };

    const result = await this.queryEngine.execute(query);
    return { data: result.data, cached: result.cached };
  }

  private async fetchWidgetApi(dataSource: any): Promise<any> {
    // External API call for widget data
    // Implementation depends on requirements
    return {};
  }

  async savePersonalization(
    userId: string,
    dashboardId: string,
    personalization: Partial<DashboardPersonalizationEntity>,
  ): Promise<DashboardPersonalizationEntity> {
    const existing = await this.personalizationRepo.findOne({
      where: { userId, dashboardId },
    });

    if (existing) {
      Object.assign(existing, personalization);
      return this.personalizationRepo.save(existing);
    }

    return this.personalizationRepo.save({
      userId,
      dashboardId,
      ...personalization,
    });
  }

  async duplicateDashboard(
    tenantId: string,
    userId: string,
    dashboardId: string,
    newName: string,
  ): Promise<DashboardEntity> {
    const original = await this.dashboardRepo.findOne({
      where: { id: dashboardId, tenantId },
    });

    if (!original) {
      throw new NotFoundException('Dashboard not found');
    }

    const duplicate = this.dashboardRepo.create({
      ...original,
      id: undefined,
      name: newName,
      createdBy: userId,
      isDefault: false,
      visibility: 'PRIVATE',
      sharedWithUsers: [],
      createdAt: undefined,
      updatedAt: undefined,
    });

    return this.dashboardRepo.save(duplicate);
  }

  async subscribeToDashboard(
    tenantId: string,
    userId: string,
    dashboardId: string,
    connectionId: string,
  ): Promise<void> {
    const dashboard = await this.dashboardRepo.findOne({
      where: { id: dashboardId, tenantId },
    });

    if (!dashboard) {
      throw new NotFoundException('Dashboard not found');
    }

    // Subscribe to real-time updates for each widget
    for (const widget of dashboard.widgets) {
      if (widget.isRealtime) {
        await this.wsGateway.subscribeToWidget(
          connectionId,
          dashboardId,
          widget.id,
        );
      }
    }

    this.logger.debug(`User ${userId} subscribed to dashboard ${dashboardId}`);
  }

  async unsubscribeFromDashboard(
    dashboardId: string,
    connectionId: string,
  ): Promise<void> {
    await this.wsGateway.unsubscribeFromDashboard(connectionId, dashboardId);
  }

  private validateWidgets(widgets: any[]): void {
    for (const widget of widgets) {
      if (!widget.id || !widget.type || !widget.title) {
        throw new Error('Widget must have id, type, and title');
      }

      if (!widget.position || widget.position.w <= 0 || widget.position.h <= 0) {
        throw new Error('Widget must have valid position');
      }

      if (!widget.dataSource) {
        throw new Error('Widget must have dataSource configuration');
      }
    }
  }

  private hasAccess(
    dashboard: DashboardEntity,
    userId: string,
    userRoles: string[],
  ): boolean {
    if (dashboard.visibility === 'PUBLIC') return true;
    if (dashboard.createdBy === userId) return true;
    if (dashboard.sharedWithUsers.includes(userId)) return true;
    if (dashboard.defaultForRoles.some(role => userRoles.includes(role))) return true;
    return false;
  }
}
```

### Dashboard WebSocket Gateway

```typescript
// src/analytics/dashboards/gateways/dashboard-websocket.gateway.ts
import {
  WebSocketGateway,
  WebSocketServer,
  SubscribeMessage,
  OnGatewayConnection,
  OnGatewayDisconnect,
  ConnectedSocket,
  MessageBody,
} from '@nestjs/websockets';
import { Logger } from '@nestjs/common';
import { Server, Socket } from 'socket.io';

interface DashboardSubscription {
  dashboardId: string;
  widgetIds: string[];
  filters: Record<string, any>;
}

@WebSocketGateway({
  namespace: '/dashboards',
  cors: {
    origin: process.env.CORS_ORIGINS?.split(',') || '*',
    credentials: true,
  },
})
export class DashboardWebSocketGateway implements OnGatewayConnection, OnGatewayDisconnect {
  @WebSocketServer()
  server: Server;

  private readonly logger = new Logger(DashboardWebSocketGateway.name);
  private subscriptions = new Map<string, DashboardSubscription[]>();

  handleConnection(client: Socket): void {
    this.logger.debug(`Client connected: ${client.id}`);
    this.subscriptions.set(client.id, []);
  }

  handleDisconnect(client: Socket): void {
    this.logger.debug(`Client disconnected: ${client.id}`);
    this.subscriptions.delete(client.id);
  }

  @SubscribeMessage('subscribe:dashboard')
  handleSubscribe(
    @ConnectedSocket() client: Socket,
    @MessageBody() data: { dashboardId: string; widgetIds?: string[]; filters?: Record<string, any> },
  ): void {
    const subscription: DashboardSubscription = {
      dashboardId: data.dashboardId,
      widgetIds: data.widgetIds || [],
      filters: data.filters || {},
    };

    const clientSubs = this.subscriptions.get(client.id) || [];
    clientSubs.push(subscription);
    this.subscriptions.set(client.id, clientSubs);

    // Join room for dashboard
    client.join(`dashboard:${data.dashboardId}`);
    
    if (data.widgetIds) {
      data.widgetIds.forEach(widgetId => {
        client.join(`widget:${data.dashboardId}:${widgetId}`);
      });
    }

    this.logger.debug(`Client ${client.id} subscribed to dashboard ${data.dashboardId}`);
  }

  @SubscribeMessage('unsubscribe:dashboard')
  handleUnsubscribe(
    @ConnectedSocket() client: Socket,
    @MessageBody() data: { dashboardId: string },
  ): void {
    const clientSubs = this.subscriptions.get(client.id) || [];
    const filtered = clientSubs.filter(s => s.dashboardId !== data.dashboardId);
    this.subscriptions.set(client.id, filtered);

    client.leave(`dashboard:${data.dashboardId}`);
    this.logger.debug(`Client ${client.id} unsubscribed from dashboard ${data.dashboardId}`);
  }

  @SubscribeMessage('refresh:widget')
  async handleWidgetRefresh(
    @ConnectedSocket() client: Socket,
    @MessageBody() data: { dashboardId: string; widgetId: string; filters?: Record<string, any> },
  ): Promise<void> {
    // Trigger widget data refresh
    // In production, this would call DashboardService
    client.emit('widget:refreshing', { widgetId: data.widgetId });
  }

  async subscribeToWidget(
    connectionId: string,
    dashboardId: string,
    widgetId: string,
  ): Promise<void> {
    const socket = this.server.sockets.sockets.get(connectionId);
    if (socket) {
      socket.join(`widget:${dashboardId}:${widgetId}`);
    }
  }

  async unsubscribeFromDashboard(
    connectionId: string,
    dashboardId: string,
  ): Promise<void> {
    const socket = this.server.sockets.sockets.get(connectionId);
    if (socket) {
      socket.leave(`dashboard:${dashboardId}`);
    }
  }

  broadcastWidgetUpdate(
    dashboardId: string,
    widgetId: string,
    data: any,
  ): void {
    this.server.to(`widget:${dashboardId}:${widgetId}`).emit('widget:update', {
      dashboardId,
      widgetId,
      data,
      timestamp: new Date().toISOString(),
    });
  }

  broadcastDashboardUpdate(
    dashboardId: string,
    updates: { widgetId: string; data: any }[],
  ): void {
    this.server.to(`dashboard:${dashboardId}`).emit('dashboard:update', {
      dashboardId,
      updates,
      timestamp: new Date().toISOString(),
    });
  }
}
```

### Dashboard Controller

```typescript
// src/analytics/dashboards/controllers/dashboard.controller.ts
import {
  Controller,
  Get,
  Post,
  Put,
  Delete,
  Body,
  Param,
  Query,
  UseGuards,
  Req,
} from '@nestjs/common';
import { ApiTags, ApiOperation, ApiBearerAuth } from '@nestjs/swagger';
import { JwtAuthGuard } from '../../../auth/guards/jwt-auth.guard';
import { TenantContext } from '../../../common/decorators/tenant-context.decorator';
import { DashboardService, CreateDashboardDto, WidgetDataRequest } from '../services/dashboard.service';
import { DashboardType } from '../entities/dashboard.entity';

@ApiTags('Dashboards')
@ApiBearerAuth()
@UseGuards(JwtAuthGuard)
@Controller('analytics/dashboards')
export class DashboardController {
  constructor(private readonly dashboardService: DashboardService) {}

  @Post()
  @ApiOperation({ summary: 'Create dashboard' })
  async createDashboard(
    @TenantContext() ctx: { tenantId: string; userId: string },
    @Body() dto: CreateDashboardDto,
  ) {
    return this.dashboardService.createDashboard(ctx.tenantId, ctx.userId, dto);
  }

  @Get()
  @ApiOperation({ summary: 'List dashboards' })
  async listDashboards(
    @TenantContext() ctx: { tenantId: string; userId: string; roles: string[] },
    @Query('type') type?: DashboardType,
    @Query('visibility') visibility?: string,
    @Query('limit') limit?: number,
    @Query('offset') offset?: number,
  ) {
    return this.dashboardService.listDashboards(
      ctx.tenantId,
      ctx.userId,
      ctx.roles,
      { type, visibility, limit, offset },
    );
  }

  @Get('default')
  @ApiOperation({ summary: 'Get default dashboard' })
  async getDefaultDashboard(
    @TenantContext() ctx: { tenantId: string; userId: string; roles: string[] },
    @Query('type') type?: DashboardType,
  ) {
    return this.dashboardService.getDefaultDashboard(
      ctx.tenantId,
      ctx.userId,
      ctx.roles,
      type,
    );
  }

  @Get(':id')
  @ApiOperation({ summary: 'Get dashboard' })
  async getDashboard(
    @TenantContext() ctx: { tenantId: string; userId: string; roles: string[] },
    @Param('id') id: string,
  ) {
    return this.dashboardService.getDashboard(ctx.tenantId, ctx.userId, id, ctx.roles);
  }

  @Get(':id/data')
  @ApiOperation({ summary: 'Get all widget data' })
  async getAllWidgetData(
    @TenantContext() ctx: { tenantId: string; userId: string },
    @Param('id') dashboardId: string,
    @Query() filters: Record<string, any>,
  ) {
    return this.dashboardService.getAllWidgetData(
      ctx.tenantId,
      ctx.userId,
      dashboardId,
      filters,
    );
  }

  @Post(':id/widgets/:widgetId/data')
  @ApiOperation({ summary: 'Get widget data' })
  async getWidgetData(
    @TenantContext() ctx: { tenantId: string; userId: string },
    @Param('id') dashboardId: string,
    @Param('widgetId') widgetId: string,
    @Body() body: { filters?: Record<string, any>; pagination?: { page: number; pageSize: number } },
  ) {
    return this.dashboardService.getWidgetData(ctx.tenantId, ctx.userId, {
      dashboardId,
      widgetId,
      filters: body.filters,
      pagination: body.pagination,
    });
  }

  @Post(':id/duplicate')
  @ApiOperation({ summary: 'Duplicate dashboard' })
  async duplicateDashboard(
    @TenantContext() ctx: { tenantId: string; userId: string },
    @Param('id') dashboardId: string,
    @Body('name') newName: string,
  ) {
    return this.dashboardService.duplicateDashboard(
      ctx.tenantId,
      ctx.userId,
      dashboardId,
      newName,
    );
  }

  @Put(':id/personalization')
  @ApiOperation({ summary: 'Save personalization' })
  async savePersonalization(
    @TenantContext() ctx: { tenantId: string; userId: string },
    @Param('id') dashboardId: string,
    @Body() personalization: any,
  ) {
    return this.dashboardService.savePersonalization(
      ctx.userId,
      dashboardId,
      personalization,
    );
  }
}
```

## Database Schema

```sql
-- Dashboards
CREATE TABLE dashboards (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    dashboard_type VARCHAR(50) NOT NULL,
    layout JSONB NOT NULL,
    widgets JSONB NOT NULL,
    global_filters JSONB,
    refresh_interval_seconds INTEGER DEFAULT 300,
    is_default BOOLEAN DEFAULT FALSE,
    default_for_roles VARCHAR[] DEFAULT '{}',
    visibility VARCHAR(20) DEFAULT 'PRIVATE',
    shared_with_users UUID[] DEFAULT '{}',
    created_by UUID NOT NULL,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_dashboards_tenant ON dashboards(tenant_id, created_by);
CREATE INDEX idx_dashboards_default ON dashboards(tenant_id, is_default, dashboard_type);
CREATE INDEX idx_dashboards_visibility ON dashboards(tenant_id, visibility);

-- Dashboard personalizations
CREATE TABLE dashboard_personalizations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    dashboard_id UUID NOT NULL REFERENCES dashboards(id) ON DELETE CASCADE,
    filter_preferences JSONB,
    widget_states JSONB,
    layout_overrides JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, dashboard_id)
);

CREATE INDEX idx_dash_personal_user ON dashboard_personalizations(user_id);
```

## Definition of Done

- [ ] Dashboard CRUD operations working
- [ ] Widget data fetching with caching
- [ ] Parallel widget data loading
- [ ] WebSocket real-time updates
- [ ] Dashboard personalization saved
- [ ] Access control enforced
- [ ] Default dashboard by role
- [ ] Dashboard duplication working
- [ ] Unit tests for service
- [ ] Integration tests for API
- [ ] WebSocket tests

## Test Cases

### Unit Tests
```typescript
describe('DashboardService', () => {
  it('should fetch widget data in parallel', async () => {
    const dashboard = createDashboardWith3Widgets();
    
    const startTime = Date.now();
    const results = await service.getAllWidgetData(
      tenantId,
      userId,
      dashboard.id,
    );
    const elapsed = Date.now() - startTime;

    expect(results).toHaveLength(3);
    expect(elapsed).toBeLessThan(2000); // Should be parallel, not serial
  });

  it('should return default dashboard for role', async () => {
    await createDashboard({ isDefault: true, defaultForRoles: ['TRADER'] });
    
    const dashboard = await service.getDefaultDashboard(
      tenantId,
      userId,
      ['TRADER'],
    );

    expect(dashboard).toBeDefined();
    expect(dashboard.defaultForRoles).toContain('TRADER');
  });
});

describe('DashboardWebSocketGateway', () => {
  it('should broadcast widget updates to subscribed clients', async () => {
    const client = await connectWebSocket();
    await client.emit('subscribe:dashboard', { dashboardId: 'dash-1' });
    
    gateway.broadcastWidgetUpdate('dash-1', 'widget-1', { value: 100 });
    
    const update = await client.waitForEvent('widget:update');
    expect(update.widgetId).toBe('widget-1');
    expect(update.data.value).toBe(100);
  });
});
```
