# US-12-03: OLAP Query Engine

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-12-03 |
| **Epic** | Epic 12: Analytics & Data Products |
| **Title** | OLAP Query Engine |
| **Priority** | High |
| **Story Points** | 8 |
| **Status** | Ready for Development |

## User Story

**As a** data analyst  
**I want** a powerful OLAP query engine for multi-dimensional analysis  
**So that** I can slice and dice trading data across multiple dimensions efficiently

## Acceptance Criteria

### AC1: Multi-Dimensional Query Support
- **Given** a query request with dimensions and measures
- **When** the query engine processes the request
- **Then** the engine:
  - Supports GROUP BY on any combination of dimensions
  - Calculates aggregations (SUM, AVG, COUNT, MIN, MAX)
  - Handles hierarchical rollups (day → week → month → year)
  - Returns results within 2 seconds for standard queries

### AC2: Query Optimization
- **Given** a complex analytical query
- **When** the optimizer analyzes the query
- **Then** optimizations are applied:
  - Pre-aggregated tables used when applicable
  - Query rewriting for optimal execution
  - Parallel execution for large scans
  - Result caching for repeated queries

### AC3: Filtering and Predicates
- **Given** a query with filter conditions
- **When** filters are applied
- **Then** the engine:
  - Supports equality, range, and IN list filters
  - Applies tenant isolation automatically
  - Pushes predicates to the database
  - Supports date range shortcuts (MTD, YTD, L30D)

### AC4: Result Caching
- **Given** a query that has been executed recently
- **When** the same query is re-executed
- **Then** caching is applied:
  - Cache key based on query hash
  - TTL configurable per query type
  - Cache invalidation on data updates
  - Cache hit rate metrics tracked

### AC5: Query Security
- **Given** a user executing a query
- **When** the query is validated
- **Then** security is enforced:
  - Tenant isolation applied to all queries
  - User permissions checked for dimensions
  - PII masking applied where required
  - Query audit logged

## Technical Specification

### Query Definition Types

```typescript
// src/analytics/query-engine/types/query.types.ts
export interface AnalyticsQuery {
  queryId?: string;
  tenantId: string;
  userId?: string;
  
  // Dimensions to group by
  dimensions: DimensionSpec[];
  
  // Measures to calculate
  measures: MeasureSpec[];
  
  // Filters
  filters: FilterSpec[];
  
  // Sorting
  orderBy?: OrderSpec[];
  
  // Pagination
  limit?: number;
  offset?: number;
  
  // Options
  options?: QueryOptions;
}

export interface DimensionSpec {
  name: string;
  table: string;
  column: string;
  alias?: string;
  
  // Hierarchy for rollup
  hierarchy?: {
    level: 'DAY' | 'WEEK' | 'MONTH' | 'QUARTER' | 'YEAR';
    dateColumn: string;
  };
  
  // Dimension attributes to include
  attributes?: string[];
}

export interface MeasureSpec {
  name: string;
  aggregation: 'SUM' | 'AVG' | 'COUNT' | 'COUNT_DISTINCT' | 'MIN' | 'MAX' | 'MEDIAN' | 'PERCENTILE';
  column: string;
  table: string;
  alias?: string;
  
  // For percentile
  percentileValue?: number;
  
  // Calculated measure expression
  expression?: string;
}

export interface FilterSpec {
  dimension: string;
  operator: 'EQ' | 'NE' | 'GT' | 'GTE' | 'LT' | 'LTE' | 'IN' | 'NOT_IN' | 'BETWEEN' | 'LIKE' | 'IS_NULL' | 'IS_NOT_NULL';
  value: any;
  values?: any[]; // For IN, NOT_IN, BETWEEN
  
  // Date shortcuts
  dateRange?: 'TODAY' | 'YESTERDAY' | 'L7D' | 'L30D' | 'MTD' | 'QTD' | 'YTD' | 'CUSTOM';
}

export interface OrderSpec {
  column: string;
  direction: 'ASC' | 'DESC';
  nullsLast?: boolean;
}

export interface QueryOptions {
  useCache?: boolean;
  cacheTtlSeconds?: number;
  maxRows?: number;
  timeout?: number;
  includeMetadata?: boolean;
  explain?: boolean;
}

export interface QueryResult {
  queryId: string;
  data: Record<string, any>[];
  metadata: QueryMetadata;
  pagination?: PaginationInfo;
  cached: boolean;
  executionTimeMs: number;
}

export interface QueryMetadata {
  dimensions: string[];
  measures: string[];
  rowCount: number;
  totalRows?: number;
  generatedSql?: string;
  queryPlan?: string;
}

export interface PaginationInfo {
  offset: number;
  limit: number;
  hasMore: boolean;
  totalCount?: number;
}
```

### OLAP Query Engine Service

```typescript
// src/analytics/query-engine/services/olap-query-engine.service.ts
import { Injectable, Logger } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository, DataSource } from 'typeorm';
import { v4 as uuidv4 } from 'uuid';
import { QueryBuilder } from './query-builder.service';
import { QueryOptimizer } from './query-optimizer.service';
import { QueryCache } from './query-cache.service';
import { QueryValidator } from './query-validator.service';
import { QueryAuditService } from './query-audit.service';
import {
  AnalyticsQuery,
  QueryResult,
  QueryMetadata,
  FilterSpec,
} from '../types/query.types';

@Injectable()
export class OlapQueryEngineService {
  private readonly logger = new Logger(OlapQueryEngineService.name);
  private readonly DEFAULT_TIMEOUT = 30000; // 30 seconds
  private readonly MAX_ROWS = 100000;

  constructor(
    private readonly dataSource: DataSource,
    private readonly queryBuilder: QueryBuilder,
    private readonly optimizer: QueryOptimizer,
    private readonly cache: QueryCache,
    private readonly validator: QueryValidator,
    private readonly audit: QueryAuditService,
  ) {}

  async execute(query: AnalyticsQuery): Promise<QueryResult> {
    const queryId = query.queryId || uuidv4();
    const startTime = Date.now();

    try {
      // Validate query
      await this.validator.validate(query);

      // Check cache first
      if (query.options?.useCache !== false) {
        const cachedResult = await this.cache.get(query);
        if (cachedResult) {
          this.logger.debug(`Cache hit for query ${queryId}`);
          await this.audit.logQuery(query, cachedResult, true);
          return { ...cachedResult, queryId, cached: true };
        }
      }

      // Optimize query
      const optimizedQuery = await this.optimizer.optimize(query);

      // Build SQL
      const { sql, parameters } = this.queryBuilder.build(optimizedQuery);

      this.logger.debug(`Executing query ${queryId}: ${sql}`);

      // Execute with timeout
      const timeout = query.options?.timeout || this.DEFAULT_TIMEOUT;
      const data = await this.executeWithTimeout(sql, parameters, timeout);

      // Apply row limit
      const maxRows = Math.min(
        query.options?.maxRows || this.MAX_ROWS,
        this.MAX_ROWS,
      );
      const truncatedData = data.slice(0, maxRows);

      const result: QueryResult = {
        queryId,
        data: truncatedData,
        metadata: {
          dimensions: query.dimensions.map(d => d.alias || d.name),
          measures: query.measures.map(m => m.alias || m.name),
          rowCount: truncatedData.length,
          totalRows: data.length,
          generatedSql: query.options?.explain ? sql : undefined,
        },
        pagination: query.limit ? {
          offset: query.offset || 0,
          limit: query.limit,
          hasMore: data.length > truncatedData.length,
        } : undefined,
        cached: false,
        executionTimeMs: Date.now() - startTime,
      };

      // Cache result
      if (query.options?.useCache !== false) {
        const ttl = query.options?.cacheTtlSeconds || 900; // 15 minutes default
        await this.cache.set(query, result, ttl);
      }

      // Audit log
      await this.audit.logQuery(query, result, false);

      return result;
    } catch (error) {
      this.logger.error(`Query ${queryId} failed: ${error.message}`);
      await this.audit.logQueryError(query, error);
      throw error;
    }
  }

  async executeWithTimeout(
    sql: string,
    parameters: any[],
    timeout: number,
  ): Promise<any[]> {
    return new Promise(async (resolve, reject) => {
      const timer = setTimeout(() => {
        reject(new Error(`Query timeout after ${timeout}ms`));
      }, timeout);

      try {
        const result = await this.dataSource.query(sql, parameters);
        clearTimeout(timer);
        resolve(result);
      } catch (error) {
        clearTimeout(timer);
        reject(error);
      }
    });
  }

  async explain(query: AnalyticsQuery): Promise<string> {
    await this.validator.validate(query);
    const optimizedQuery = await this.optimizer.optimize(query);
    const { sql, parameters } = this.queryBuilder.build(optimizedQuery);
    
    const explainResult = await this.dataSource.query(
      `EXPLAIN ANALYZE ${sql}`,
      parameters,
    );
    
    return explainResult.map((r: any) => r['QUERY PLAN']).join('\n');
  }

  // Convenience methods for common queries
  async getTradesByDimension(
    tenantId: string,
    dimension: 'client' | 'instrument' | 'lp' | 'date',
    dateRange: FilterSpec['dateRange'],
  ): Promise<QueryResult> {
    const dimensionMap: Record<string, DimensionSpec> = {
      client: { name: 'client', table: 'dim_clients', column: 'client_name' },
      instrument: { name: 'instrument', table: 'dim_instruments', column: 'symbol' },
      lp: { name: 'lp', table: 'dim_liquidity_providers', column: 'lp_name' },
      date: { name: 'date', table: 'dim_time', column: 'date', hierarchy: { level: 'DAY', dateColumn: 'date' } },
    };

    return this.execute({
      tenantId,
      dimensions: [dimensionMap[dimension]],
      measures: [
        { name: 'trade_count', aggregation: 'COUNT', column: 'id', table: 'fact_trades' },
        { name: 'total_volume', aggregation: 'SUM', column: 'quantity', table: 'fact_trades' },
        { name: 'total_notional', aggregation: 'SUM', column: 'notional_value', table: 'fact_trades' },
      ],
      filters: [
        { dimension: 'trade_date', operator: 'BETWEEN', dateRange },
      ],
      orderBy: [{ column: 'total_notional', direction: 'DESC' }],
      limit: 100,
    });
  }
}
```

### Query Builder Service

```typescript
// src/analytics/query-engine/services/query-builder.service.ts
import { Injectable } from '@nestjs/common';
import {
  AnalyticsQuery,
  DimensionSpec,
  MeasureSpec,
  FilterSpec,
} from '../types/query.types';

interface BuildResult {
  sql: string;
  parameters: any[];
}

@Injectable()
export class QueryBuilder {
  private paramIndex = 0;
  private parameters: any[] = [];

  build(query: AnalyticsQuery): BuildResult {
    this.paramIndex = 0;
    this.parameters = [];

    const selectClause = this.buildSelect(query);
    const fromClause = this.buildFrom(query);
    const joinClause = this.buildJoins(query);
    const whereClause = this.buildWhere(query);
    const groupByClause = this.buildGroupBy(query);
    const havingClause = ''; // Can be extended
    const orderByClause = this.buildOrderBy(query);
    const limitClause = this.buildLimit(query);

    const sql = [
      selectClause,
      fromClause,
      joinClause,
      whereClause,
      groupByClause,
      havingClause,
      orderByClause,
      limitClause,
    ]
      .filter(Boolean)
      .join('\n');

    return { sql, parameters: this.parameters };
  }

  private buildSelect(query: AnalyticsQuery): string {
    const columns: string[] = [];

    // Add dimensions
    for (const dim of query.dimensions) {
      if (dim.hierarchy) {
        columns.push(this.buildHierarchySelect(dim));
      } else {
        const alias = dim.alias || dim.name;
        columns.push(`${dim.table}.${dim.column} AS "${alias}"`);
      }

      // Add dimension attributes
      if (dim.attributes) {
        for (const attr of dim.attributes) {
          columns.push(`${dim.table}.${attr}`);
        }
      }
    }

    // Add measures
    for (const measure of query.measures) {
      columns.push(this.buildMeasureSelect(measure));
    }

    return `SELECT\n  ${columns.join(',\n  ')}`;
  }

  private buildHierarchySelect(dim: DimensionSpec): string {
    const { level, dateColumn } = dim.hierarchy!;
    const alias = dim.alias || `${dim.name}_${level.toLowerCase()}`;

    switch (level) {
      case 'DAY':
        return `DATE(${dim.table}.${dateColumn}) AS "${alias}"`;
      case 'WEEK':
        return `DATE_TRUNC('week', ${dim.table}.${dateColumn}) AS "${alias}"`;
      case 'MONTH':
        return `DATE_TRUNC('month', ${dim.table}.${dateColumn}) AS "${alias}"`;
      case 'QUARTER':
        return `DATE_TRUNC('quarter', ${dim.table}.${dateColumn}) AS "${alias}"`;
      case 'YEAR':
        return `DATE_TRUNC('year', ${dim.table}.${dateColumn}) AS "${alias}"`;
      default:
        return `${dim.table}.${dateColumn} AS "${alias}"`;
    }
  }

  private buildMeasureSelect(measure: MeasureSpec): string {
    const alias = measure.alias || measure.name;
    const column = measure.expression || `${measure.table}.${measure.column}`;

    switch (measure.aggregation) {
      case 'SUM':
        return `SUM(${column}) AS "${alias}"`;
      case 'AVG':
        return `AVG(${column}) AS "${alias}"`;
      case 'COUNT':
        return `COUNT(${column}) AS "${alias}"`;
      case 'COUNT_DISTINCT':
        return `COUNT(DISTINCT ${column}) AS "${alias}"`;
      case 'MIN':
        return `MIN(${column}) AS "${alias}"`;
      case 'MAX':
        return `MAX(${column}) AS "${alias}"`;
      case 'MEDIAN':
        return `PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY ${column}) AS "${alias}"`;
      case 'PERCENTILE':
        const pct = measure.percentileValue || 0.5;
        return `PERCENTILE_CONT(${pct}) WITHIN GROUP (ORDER BY ${column}) AS "${alias}"`;
      default:
        return `${column} AS "${alias}"`;
    }
  }

  private buildFrom(query: AnalyticsQuery): string {
    // Determine primary fact table
    const factTable = this.determinePrimaryFactTable(query);
    return `FROM ${factTable}`;
  }

  private determinePrimaryFactTable(query: AnalyticsQuery): string {
    // Check measures to determine fact table
    const measureTables = new Set(query.measures.map(m => m.table));
    
    if (measureTables.has('fact_trades')) return 'fact_trades';
    if (measureTables.has('fact_quotes')) return 'fact_quotes';
    if (measureTables.has('fact_orders')) return 'fact_orders';
    if (measureTables.has('fact_risk_snapshots')) return 'fact_risk_snapshots';
    
    // Check for pre-aggregated tables
    if (this.canUseAggregate(query)) {
      return 'agg_daily_trade_summary';
    }
    
    return 'fact_trades';
  }

  private canUseAggregate(query: AnalyticsQuery): boolean {
    // Check if query can be satisfied by pre-aggregated table
    const supportedDimensions = ['tenant_id', 'client_id', 'instrument_id', 'lp_id', 'trade_date'];
    const supportedMeasures = ['trade_count', 'total_volume', 'total_notional', 'total_fees'];
    
    const queryDimensions = query.dimensions.map(d => d.column);
    const queryMeasures = query.measures.map(m => m.name);
    
    return (
      queryDimensions.every(d => supportedDimensions.includes(d)) &&
      queryMeasures.every(m => supportedMeasures.includes(m))
    );
  }

  private buildJoins(query: AnalyticsQuery): string {
    const joins: string[] = [];
    const joinedTables = new Set<string>();
    const factTable = this.determinePrimaryFactTable(query);

    for (const dim of query.dimensions) {
      if (dim.table === factTable || joinedTables.has(dim.table)) continue;

      const joinCondition = this.getJoinCondition(factTable, dim.table);
      if (joinCondition) {
        joins.push(`LEFT JOIN ${dim.table} ON ${joinCondition}`);
        joinedTables.add(dim.table);
      }
    }

    return joins.join('\n');
  }

  private getJoinCondition(factTable: string, dimTable: string): string | null {
    const joinMap: Record<string, Record<string, string>> = {
      fact_trades: {
        dim_clients: 'fact_trades.client_id = dim_clients.client_id AND dim_clients.is_current = TRUE',
        dim_instruments: 'fact_trades.instrument_id = dim_instruments.instrument_id AND dim_instruments.is_current = TRUE',
        dim_liquidity_providers: 'fact_trades.lp_id = dim_liquidity_providers.lp_id AND dim_liquidity_providers.is_current = TRUE',
        dim_time: 'fact_trades.trade_date_key = dim_time.date_key',
      },
      fact_quotes: {
        dim_instruments: 'fact_quotes.instrument_id = dim_instruments.instrument_id AND dim_instruments.is_current = TRUE',
        dim_liquidity_providers: 'fact_quotes.lp_id = dim_liquidity_providers.lp_id AND dim_liquidity_providers.is_current = TRUE',
        dim_time: 'fact_quotes.quote_date_key = dim_time.date_key',
      },
    };

    return joinMap[factTable]?.[dimTable] || null;
  }

  private buildWhere(query: AnalyticsQuery): string {
    const conditions: string[] = [];

    // Tenant isolation (always first)
    conditions.push(`${this.determinePrimaryFactTable(query)}.tenant_id = ${this.addParam(query.tenantId)}`);

    // User filters
    for (const filter of query.filters) {
      const condition = this.buildFilterCondition(filter);
      if (condition) {
        conditions.push(condition);
      }
    }

    return conditions.length > 0 ? `WHERE ${conditions.join('\n  AND ')}` : '';
  }

  private buildFilterCondition(filter: FilterSpec): string | null {
    const column = filter.dimension;

    // Handle date range shortcuts
    if (filter.dateRange) {
      return this.buildDateRangeCondition(column, filter.dateRange);
    }

    switch (filter.operator) {
      case 'EQ':
        return `${column} = ${this.addParam(filter.value)}`;
      case 'NE':
        return `${column} != ${this.addParam(filter.value)}`;
      case 'GT':
        return `${column} > ${this.addParam(filter.value)}`;
      case 'GTE':
        return `${column} >= ${this.addParam(filter.value)}`;
      case 'LT':
        return `${column} < ${this.addParam(filter.value)}`;
      case 'LTE':
        return `${column} <= ${this.addParam(filter.value)}`;
      case 'IN':
        const inParams = filter.values!.map(v => this.addParam(v)).join(', ');
        return `${column} IN (${inParams})`;
      case 'NOT_IN':
        const notInParams = filter.values!.map(v => this.addParam(v)).join(', ');
        return `${column} NOT IN (${notInParams})`;
      case 'BETWEEN':
        return `${column} BETWEEN ${this.addParam(filter.values![0])} AND ${this.addParam(filter.values![1])}`;
      case 'LIKE':
        return `${column} LIKE ${this.addParam(filter.value)}`;
      case 'IS_NULL':
        return `${column} IS NULL`;
      case 'IS_NOT_NULL':
        return `${column} IS NOT NULL`;
      default:
        return null;
    }
  }

  private buildDateRangeCondition(column: string, dateRange: FilterSpec['dateRange']): string {
    const today = new Date();
    today.setHours(0, 0, 0, 0);

    switch (dateRange) {
      case 'TODAY':
        return `${column} = ${this.addParam(today)}`;
      case 'YESTERDAY':
        const yesterday = new Date(today);
        yesterday.setDate(yesterday.getDate() - 1);
        return `${column} = ${this.addParam(yesterday)}`;
      case 'L7D':
        const l7d = new Date(today);
        l7d.setDate(l7d.getDate() - 7);
        return `${column} >= ${this.addParam(l7d)}`;
      case 'L30D':
        const l30d = new Date(today);
        l30d.setDate(l30d.getDate() - 30);
        return `${column} >= ${this.addParam(l30d)}`;
      case 'MTD':
        const mtd = new Date(today.getFullYear(), today.getMonth(), 1);
        return `${column} >= ${this.addParam(mtd)}`;
      case 'QTD':
        const quarter = Math.floor(today.getMonth() / 3);
        const qtd = new Date(today.getFullYear(), quarter * 3, 1);
        return `${column} >= ${this.addParam(qtd)}`;
      case 'YTD':
        const ytd = new Date(today.getFullYear(), 0, 1);
        return `${column} >= ${this.addParam(ytd)}`;
      default:
        return '';
    }
  }

  private buildGroupBy(query: AnalyticsQuery): string {
    if (query.dimensions.length === 0) return '';

    const groupColumns: string[] = [];

    for (const dim of query.dimensions) {
      if (dim.hierarchy) {
        groupColumns.push(this.buildHierarchyGroupBy(dim));
      } else {
        groupColumns.push(`${dim.table}.${dim.column}`);
      }

      if (dim.attributes) {
        for (const attr of dim.attributes) {
          groupColumns.push(`${dim.table}.${attr}`);
        }
      }
    }

    return `GROUP BY ${groupColumns.join(', ')}`;
  }

  private buildHierarchyGroupBy(dim: DimensionSpec): string {
    const { level, dateColumn } = dim.hierarchy!;

    switch (level) {
      case 'DAY':
        return `DATE(${dim.table}.${dateColumn})`;
      case 'WEEK':
        return `DATE_TRUNC('week', ${dim.table}.${dateColumn})`;
      case 'MONTH':
        return `DATE_TRUNC('month', ${dim.table}.${dateColumn})`;
      case 'QUARTER':
        return `DATE_TRUNC('quarter', ${dim.table}.${dateColumn})`;
      case 'YEAR':
        return `DATE_TRUNC('year', ${dim.table}.${dateColumn})`;
      default:
        return `${dim.table}.${dateColumn}`;
    }
  }

  private buildOrderBy(query: AnalyticsQuery): string {
    if (!query.orderBy || query.orderBy.length === 0) return '';

    const orderClauses = query.orderBy.map(o => {
      const nulls = o.nullsLast ? ' NULLS LAST' : '';
      return `"${o.column}" ${o.direction}${nulls}`;
    });

    return `ORDER BY ${orderClauses.join(', ')}`;
  }

  private buildLimit(query: AnalyticsQuery): string {
    const clauses: string[] = [];

    if (query.limit) {
      clauses.push(`LIMIT ${query.limit}`);
    }

    if (query.offset) {
      clauses.push(`OFFSET ${query.offset}`);
    }

    return clauses.join(' ');
  }

  private addParam(value: any): string {
    this.paramIndex++;
    this.parameters.push(value);
    return `$${this.paramIndex}`;
  }
}
```

### Query Optimizer Service

```typescript
// src/analytics/query-engine/services/query-optimizer.service.ts
import { Injectable, Logger } from '@nestjs/common';
import { AnalyticsQuery, DimensionSpec, MeasureSpec } from '../types/query.types';

interface OptimizationStrategy {
  name: string;
  apply: (query: AnalyticsQuery) => AnalyticsQuery | null;
}

@Injectable()
export class QueryOptimizer {
  private readonly logger = new Logger(QueryOptimizer.name);

  private readonly strategies: OptimizationStrategy[] = [
    {
      name: 'UsePreAggregatedTable',
      apply: this.usePreAggregatedTable.bind(this),
    },
    {
      name: 'PushDownFilters',
      apply: this.pushDownFilters.bind(this),
    },
    {
      name: 'RemoveUnusedDimensions',
      apply: this.removeUnusedDimensions.bind(this),
    },
    {
      name: 'OptimizeTimeHierarchy',
      apply: this.optimizeTimeHierarchy.bind(this),
    },
  ];

  async optimize(query: AnalyticsQuery): Promise<AnalyticsQuery> {
    let optimizedQuery = { ...query };

    for (const strategy of this.strategies) {
      const result = strategy.apply(optimizedQuery);
      if (result) {
        this.logger.debug(`Applied optimization: ${strategy.name}`);
        optimizedQuery = result;
      }
    }

    return optimizedQuery;
  }

  private usePreAggregatedTable(query: AnalyticsQuery): AnalyticsQuery | null {
    // Check if all measures can be satisfied by aggregate table
    const aggMeasures = new Map([
      ['trade_count', 'SUM(trade_count)'],
      ['total_volume', 'SUM(total_volume)'],
      ['total_notional', 'SUM(total_notional)'],
      ['buy_count', 'SUM(buy_count)'],
      ['sell_count', 'SUM(sell_count)'],
      ['total_fees', 'SUM(total_fees)'],
    ]);

    const canUseAgg = query.measures.every(m => 
      aggMeasures.has(m.name) || m.aggregation === 'SUM'
    );

    if (!canUseAgg) return null;

    // Check dimensions
    const supportedDims = ['tenant_id', 'client_id', 'instrument_id', 'lp_id', 'trade_date'];
    const queryDims = query.dimensions.map(d => d.column);
    
    if (!queryDims.every(d => supportedDims.includes(d))) return null;

    // Rewrite query to use aggregate table
    const rewrittenMeasures: MeasureSpec[] = query.measures.map(m => {
      if (aggMeasures.has(m.name)) {
        return {
          ...m,
          table: 'agg_daily_trade_summary',
          column: m.name,
        };
      }
      return { ...m, table: 'agg_daily_trade_summary' };
    });

    const rewrittenDims: DimensionSpec[] = query.dimensions.map(d => ({
      ...d,
      table: 'agg_daily_trade_summary',
    }));

    return {
      ...query,
      dimensions: rewrittenDims,
      measures: rewrittenMeasures,
    };
  }

  private pushDownFilters(query: AnalyticsQuery): AnalyticsQuery | null {
    // Already handled in query builder
    return null;
  }

  private removeUnusedDimensions(query: AnalyticsQuery): AnalyticsQuery | null {
    // Remove dimensions that aren't used in output or filters
    return null;
  }

  private optimizeTimeHierarchy(query: AnalyticsQuery): AnalyticsQuery | null {
    // For large date ranges, automatically use coarser granularity
    const dateFilter = query.filters.find(f => f.dateRange);
    if (!dateFilter) return null;

    const timeDim = query.dimensions.find(d => d.hierarchy);
    if (!timeDim) return null;

    // If range > 90 days and granularity is DAY, suggest WEEK
    // If range > 365 days and granularity is WEEK, suggest MONTH
    // This is a hint, not enforcement
    return null;
  }
}
```

### Query Cache Service

```typescript
// src/analytics/query-engine/services/query-cache.service.ts
import { Injectable, Logger } from '@nestjs/common';
import { Redis } from 'ioredis';
import { createHash } from 'crypto';
import { AnalyticsQuery, QueryResult } from '../types/query.types';

@Injectable()
export class QueryCache {
  private readonly logger = new Logger(QueryCache.name);
  private readonly redis: Redis;
  private readonly prefix = 'analytics:query:';

  constructor() {
    this.redis = new Redis({
      host: process.env.REDIS_HOST || 'localhost',
      port: parseInt(process.env.REDIS_PORT || '6379'),
      db: 2, // Dedicated DB for analytics cache
    });
  }

  async get(query: AnalyticsQuery): Promise<QueryResult | null> {
    const key = this.buildCacheKey(query);
    
    try {
      const cached = await this.redis.get(key);
      if (cached) {
        this.logger.debug(`Cache hit: ${key}`);
        return JSON.parse(cached);
      }
    } catch (error) {
      this.logger.warn(`Cache get error: ${error.message}`);
    }

    return null;
  }

  async set(
    query: AnalyticsQuery,
    result: QueryResult,
    ttlSeconds: number,
  ): Promise<void> {
    const key = this.buildCacheKey(query);

    try {
      await this.redis.setex(key, ttlSeconds, JSON.stringify(result));
      this.logger.debug(`Cached query: ${key}, TTL: ${ttlSeconds}s`);
    } catch (error) {
      this.logger.warn(`Cache set error: ${error.message}`);
    }
  }

  async invalidate(pattern: string): Promise<void> {
    const keys = await this.redis.keys(`${this.prefix}${pattern}*`);
    if (keys.length > 0) {
      await this.redis.del(...keys);
      this.logger.debug(`Invalidated ${keys.length} cache entries`);
    }
  }

  async invalidateForTenant(tenantId: string): Promise<void> {
    await this.invalidate(`tenant:${tenantId}`);
  }

  private buildCacheKey(query: AnalyticsQuery): string {
    // Create deterministic key from query
    const normalized = {
      tenantId: query.tenantId,
      dimensions: query.dimensions.map(d => ({
        name: d.name,
        column: d.column,
        hierarchy: d.hierarchy,
      })).sort((a, b) => a.name.localeCompare(b.name)),
      measures: query.measures.map(m => ({
        name: m.name,
        aggregation: m.aggregation,
      })).sort((a, b) => a.name.localeCompare(b.name)),
      filters: query.filters.sort((a, b) => a.dimension.localeCompare(b.dimension)),
      orderBy: query.orderBy,
      limit: query.limit,
      offset: query.offset,
    };

    const hash = createHash('sha256')
      .update(JSON.stringify(normalized))
      .digest('hex')
      .substring(0, 16);

    return `${this.prefix}tenant:${query.tenantId}:${hash}`;
  }

  async getStats(): Promise<{
    hitRate: number;
    keyCount: number;
    memoryUsage: number;
  }> {
    const info = await this.redis.info('stats');
    const keyCount = await this.redis.dbsize();
    const memoryInfo = await this.redis.info('memory');

    // Parse stats
    const hits = parseInt(info.match(/keyspace_hits:(\d+)/)?.[1] || '0');
    const misses = parseInt(info.match(/keyspace_misses:(\d+)/)?.[1] || '0');
    const hitRate = hits + misses > 0 ? hits / (hits + misses) : 0;
    const memoryUsage = parseInt(memoryInfo.match(/used_memory:(\d+)/)?.[1] || '0');

    return { hitRate, keyCount, memoryUsage };
  }
}
```

## Database Schema

```sql
-- Query audit log
CREATE TABLE analytics_query_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    query_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    user_id UUID,
    query_hash VARCHAR(64) NOT NULL,
    query_definition JSONB NOT NULL,
    execution_time_ms INTEGER NOT NULL,
    row_count INTEGER NOT NULL,
    cache_hit BOOLEAN DEFAULT FALSE,
    error_message TEXT,
    client_ip VARCHAR(45),
    user_agent TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_query_audit_tenant ON analytics_query_audit(tenant_id, created_at);
CREATE INDEX idx_query_audit_user ON analytics_query_audit(user_id, created_at);
CREATE INDEX idx_query_audit_hash ON analytics_query_audit(query_hash);

-- Query performance metrics (TimescaleDB)
CREATE TABLE analytics_query_metrics (
    time TIMESTAMPTZ NOT NULL,
    tenant_id UUID NOT NULL,
    query_hash VARCHAR(64) NOT NULL,
    execution_count INTEGER DEFAULT 1,
    avg_execution_time_ms DECIMAL(10, 2),
    max_execution_time_ms INTEGER,
    cache_hits INTEGER DEFAULT 0,
    cache_misses INTEGER DEFAULT 0,
    error_count INTEGER DEFAULT 0
);

SELECT create_hypertable('analytics_query_metrics', 'time', chunk_time_interval => INTERVAL '1 day');

-- Continuous aggregate for query patterns
CREATE MATERIALIZED VIEW mv_hourly_query_stats
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 hour', time) AS bucket,
    tenant_id,
    COUNT(*) as query_count,
    AVG(avg_execution_time_ms) as avg_time_ms,
    SUM(cache_hits) as cache_hits,
    SUM(cache_misses) as cache_misses,
    SUM(error_count) as errors
FROM analytics_query_metrics
GROUP BY time_bucket('1 hour', time), tenant_id;
```

## Definition of Done

- [ ] Query builder generates valid SQL for all query types
- [ ] Optimizer selects pre-aggregated tables when possible
- [ ] Cache implementation with configurable TTL
- [ ] Query validation and security checks
- [ ] Audit logging for all queries
- [ ] Performance metrics collection
- [ ] Query timeout handling
- [ ] Unit tests for query builder
- [ ] Integration tests for full query execution
- [ ] Load tests for concurrent queries

## Test Cases

### Unit Tests
```typescript
describe('QueryBuilder', () => {
  it('should build simple aggregation query', () => {
    const query: AnalyticsQuery = {
      tenantId: 'tenant-1',
      dimensions: [{ name: 'client', table: 'dim_clients', column: 'client_name' }],
      measures: [{ name: 'total', aggregation: 'SUM', column: 'notional_value', table: 'fact_trades' }],
      filters: [],
    };

    const { sql, parameters } = builder.build(query);
    
    expect(sql).toContain('SELECT');
    expect(sql).toContain('SUM(fact_trades.notional_value)');
    expect(sql).toContain('GROUP BY');
    expect(parameters).toContain('tenant-1');
  });

  it('should handle date range shortcuts', () => {
    const query: AnalyticsQuery = {
      tenantId: 'tenant-1',
      dimensions: [],
      measures: [{ name: 'count', aggregation: 'COUNT', column: 'id', table: 'fact_trades' }],
      filters: [{ dimension: 'trade_date', operator: 'BETWEEN', dateRange: 'MTD' }],
    };

    const { sql } = builder.build(query);
    expect(sql).toContain('trade_date >=');
  });
});

describe('QueryCache', () => {
  it('should cache and retrieve query results', async () => {
    const query = createTestQuery();
    const result = createTestResult();

    await cache.set(query, result, 60);
    const cached = await cache.get(query);

    expect(cached).toEqual(result);
  });

  it('should generate consistent cache keys', () => {
    const query1 = createTestQuery();
    const query2 = { ...createTestQuery() }; // Same content, different object

    const key1 = cache.buildCacheKey(query1);
    const key2 = cache.buildCacheKey(query2);

    expect(key1).toBe(key2);
  });
});
```

### Integration Tests
```typescript
describe('OLAP Query Engine Integration', () => {
  it('should execute trade summary query', async () => {
    const result = await engine.execute({
      tenantId: testTenant.id,
      dimensions: [
        { name: 'date', table: 'fact_trades', column: 'trade_date', 
          hierarchy: { level: 'DAY', dateColumn: 'trade_date' } },
      ],
      measures: [
        { name: 'trades', aggregation: 'COUNT', column: 'id', table: 'fact_trades' },
        { name: 'volume', aggregation: 'SUM', column: 'quantity', table: 'fact_trades' },
      ],
      filters: [
        { dimension: 'trade_date', operator: 'BETWEEN', dateRange: 'L7D' },
      ],
      orderBy: [{ column: 'date', direction: 'ASC' }],
    });

    expect(result.data.length).toBeGreaterThan(0);
    expect(result.metadata.dimensions).toContain('date');
    expect(result.executionTimeMs).toBeLessThan(2000);
  });

  it('should use pre-aggregated table for compatible queries', async () => {
    const result = await engine.execute({
      tenantId: testTenant.id,
      dimensions: [
        { name: 'client_id', table: 'agg_daily_trade_summary', column: 'client_id' },
      ],
      measures: [
        { name: 'trade_count', aggregation: 'SUM', column: 'trade_count', table: 'agg_daily_trade_summary' },
      ],
      filters: [],
      options: { explain: true },
    });

    expect(result.metadata.generatedSql).toContain('agg_daily_trade_summary');
  });
});
```
