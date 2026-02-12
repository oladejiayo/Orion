# REST API Conventions

> Standards for all REST APIs exposed by BFF services to browser clients. Internal service communication uses gRPC — see [gRPC Services Reference](grpc-services.md).

---

## 1. General Rules

| Convention | Standard |
|-----------|---------|
| **Base URL** | `https://<host>/api/v1/` |
| **Content-Type** | `application/json` (request and response) |
| **Authentication** | `Authorization: Bearer <JWT>` on every request |
| **Versioning** | URL path prefix: `/api/v1/`, `/api/v2/` |
| **Naming** | kebab-case for URL paths, camelCase for JSON fields |
| **HTTP methods** | GET (read), POST (create/action), PUT (full update), PATCH (partial), DELETE |
| **Idempotency** | POST requests requiring idempotency must include `Idempotency-Key` header |

---

## 2. Request Headers

| Header | Required | Description |
|--------|----------|-------------|
| `Authorization` | ✅ | `Bearer <JWT>` — validated by BFF |
| `Content-Type` | ✅ (POST/PUT/PATCH) | `application/json` |
| `X-Correlation-Id` | ❌ | Client-provided tracking ID (BFF generates one if absent) |
| `X-Request-Id` | ❌ | Unique request identifier |
| `Idempotency-Key` | ❌ | Required for non-idempotent POST operations (RFQ create, order place) |

---

## 3. Response Format

### Success (200/201)

```json
{
  "data": { ... },
  "meta": {
    "correlationId": "corr-abc-123",
    "timestamp": "2026-02-12T12:34:56.789Z"
  }
}
```

### Paginated List (200)

```json
{
  "data": [ ... ],
  "pagination": {
    "page": 1,
    "pageSize": 25,
    "totalItems": 150,
    "totalPages": 6,
    "hasNext": true,
    "nextCursor": "eyJpZCI6MTAwfQ=="
  },
  "meta": {
    "correlationId": "corr-abc-123",
    "timestamp": "2026-02-12T12:34:56.789Z"
  }
}
```

### Error (4xx/5xx)

```json
{
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "Request validation failed",
    "correlationId": "corr-abc-123",
    "details": [
      {
        "code": "FIELD_REQUIRED",
        "message": "instrumentId is required",
        "field": "instrumentId"
      }
    ]
  }
}
```

---

## 4. HTTP Status Codes

| Code | Meaning | When |
|------|---------|------|
| `200` | OK | Successful read or update |
| `201` | Created | Successful create (POST) |
| `204` | No Content | Successful delete |
| `400` | Bad Request | Validation failure |
| `401` | Unauthorized | Missing or invalid JWT |
| `403` | Forbidden | Insufficient role or entitlement |
| `404` | Not Found | Entity does not exist |
| `409` | Conflict | Optimistic concurrency failure (e.g., RFQ already accepted) |
| `429` | Too Many Requests | Rate limit exceeded |
| `500` | Internal Server Error | Unexpected failure |
| `503` | Service Unavailable | Kill switch active or dependency down |

---

## 5. BFF API Endpoints (Planned)

### Authentication

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/me` | Current user profile, roles, entitlements |

### Market Data

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/market/snapshot` | Latest tick snapshot for instruments |
| `WS` | `/api/v1/stream/market` | Real-time tick streaming (subscribe/unsubscribe) |

### RFQ

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/rfqs` | Create new RFQ |
| `GET` | `/api/v1/rfqs` | List RFQs (filtered, paginated) |
| `GET` | `/api/v1/rfqs/{id}` | RFQ details (quotes, status) |
| `POST` | `/api/v1/rfqs/{id}/accept` | Accept a quote |
| `POST` | `/api/v1/rfqs/{id}/cancel` | Cancel RFQ |

### Trades

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/trades` | List trades (filtered, paginated) |
| `GET` | `/api/v1/trades/{id}` | Trade details + confirmation status |

### Orders (V1+)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/orders` | Place order |
| `POST` | `/api/v1/orders/{id}/cancel` | Cancel order |
| `POST` | `/api/v1/orders/{id}/amend` | Amend order |
| `GET` | `/api/v1/orders` | List orders (filtered, paginated) |

### Admin

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/admin/instruments` | Create instrument |
| `PATCH` | `/api/v1/admin/instruments/{id}` | Update instrument |
| `POST` | `/api/v1/admin/killswitch/enable` | Activate kill switch |
| `POST` | `/api/v1/admin/killswitch/disable` | Deactivate kill switch |
| `POST` | `/api/v1/admin/limits` | Update trading limits |

---

## 6. Pagination

Two strategies supported:

| Strategy | When to Use | Parameters |
|----------|-----------|------------|
| **Offset** | Small datasets, random page access | `?page=2&pageSize=25` |
| **Cursor** | Large datasets, sequential access | `?cursor=eyJpZCI6MTAwfQ==&pageSize=25` |

---

## 7. WebSocket Protocol

### Connection

```
WS wss://<host>/api/v1/stream/market
Authorization: Bearer <JWT>
```

### Subscribe

```json
{ "action": "SUBSCRIBE", "instrumentIds": ["EUR/USD", "GBP/USD"] }
```

### Unsubscribe

```json
{ "action": "UNSUBSCRIBE", "instrumentIds": ["EUR/USD"] }
```

### Tick Update (server → client)

```json
{
  "type": "TICK",
  "instrumentId": "EUR/USD",
  "bid": "1.0850",
  "ask": "1.0852",
  "mid": "1.0851",
  "spread": "0.0002",
  "timestamp": "2026-02-12T12:34:56.789Z",
  "quality": { "stale": false, "indicative": false }
}
```

---

*Last updated after US-01-06*
