# US-16-05: Custom Dashboards

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-16-05 |
| **Epic** | Epic 16: Observability |
| **Title** | Custom Dashboards |
| **Priority** | Medium |
| **Story Points** | 5 |
| **Status** | Ready for Development |

## User Story

**As a** stakeholder (ops, dev, business)  
**I want** role-specific dashboards  
**So that** I can monitor the metrics relevant to my responsibilities

## Acceptance Criteria

### AC1: Operations Dashboard
- **Given** SRE/Ops user
- **When** accessing dashboard
- **Then**:
  - System health overview
  - SLI/SLO status
  - Incident timeline
  - Resource utilization

### AC2: Developer Dashboard
- **Given** developer user
- **When** accessing dashboard
- **Then**:
  - Service metrics
  - Error rates by endpoint
  - Trace integration
  - Log links

### AC3: Business Dashboard
- **Given** business stakeholder
- **When** accessing dashboard
- **Then**:
  - Trading volume
  - Order metrics
  - Revenue indicators
  - Client activity

### AC4: Infrastructure Dashboard
- **Given** platform engineer
- **When** accessing dashboard
- **Then**:
  - Kubernetes resources
  - Node health
  - Database metrics
  - Network traffic

### AC5: Dashboard Management
- **Given** Grafana setup
- **When** dashboards organized
- **Then**:
  - Folder hierarchy
  - Team permissions
  - Version control
  - Alerting integration

## Technical Specification

### Operations Dashboard (SRE)

```json
{
  "dashboard": {
    "title": "Orion Platform - Operations Overview",
    "uid": "orion-ops-overview",
    "tags": ["orion", "operations", "sre"],
    "timezone": "browser",
    "refresh": "30s",
    "templating": {
      "list": [
        {
          "name": "environment",
          "type": "query",
          "datasource": "Prometheus",
          "query": "label_values(up{job=~\"orion.*\"}, environment)",
          "current": { "text": "production", "value": "production" }
        },
        {
          "name": "service",
          "type": "query",
          "datasource": "Prometheus",
          "query": "label_values(http_server_requests_seconds_count{namespace=\"orion\"}, service)",
          "includeAll": true,
          "multi": true
        }
      ]
    },
    "panels": [
      {
        "title": "System Health",
        "type": "stat",
        "gridPos": { "x": 0, "y": 0, "w": 6, "h": 4 },
        "targets": [
          {
            "expr": "sum(up{namespace=\"orion\", environment=\"$environment\"}) / count(up{namespace=\"orion\", environment=\"$environment\"}) * 100",
            "legendFormat": "Health %"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "unit": "percent",
            "thresholds": {
              "mode": "absolute",
              "steps": [
                { "value": 0, "color": "red" },
                { "value": 80, "color": "yellow" },
                { "value": 95, "color": "green" }
              ]
            }
          }
        }
      },
      {
        "title": "SLO - Availability",
        "type": "gauge",
        "gridPos": { "x": 6, "y": 0, "w": 6, "h": 4 },
        "targets": [
          {
            "expr": "(1 - sum(rate(http_server_requests_seconds_count{namespace=\"orion\", status=~\"5..\"}[30d])) / sum(rate(http_server_requests_seconds_count{namespace=\"orion\"}[30d]))) * 100"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "unit": "percent",
            "min": 99,
            "max": 100,
            "thresholds": {
              "steps": [
                { "value": 99, "color": "red" },
                { "value": 99.9, "color": "yellow" },
                { "value": 99.99, "color": "green" }
              ]
            }
          }
        }
      },
      {
        "title": "Error Budget Remaining",
        "type": "stat",
        "gridPos": { "x": 12, "y": 0, "w": 6, "h": 4 },
        "targets": [
          {
            "expr": "((0.001 * 30 * 24 * 60 * 60) - sum(increase(http_server_requests_seconds_count{namespace=\"orion\", status=~\"5..\"}[30d]))) / (0.001 * 30 * 24 * 60 * 60) * 100"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "unit": "percent",
            "thresholds": {
              "steps": [
                { "value": 0, "color": "red" },
                { "value": 25, "color": "yellow" },
                { "value": 50, "color": "green" }
              ]
            }
          }
        }
      },
      {
        "title": "Request Rate",
        "type": "timeseries",
        "gridPos": { "x": 0, "y": 4, "w": 12, "h": 6 },
        "targets": [
          {
            "expr": "sum(rate(http_server_requests_seconds_count{namespace=\"orion\", service=~\"$service\"}[5m])) by (service)",
            "legendFormat": "{{service}}"
          }
        ]
      },
      {
        "title": "Error Rate",
        "type": "timeseries",
        "gridPos": { "x": 12, "y": 4, "w": 12, "h": 6 },
        "targets": [
          {
            "expr": "sum(rate(http_server_requests_seconds_count{namespace=\"orion\", status=~\"5..\", service=~\"$service\"}[5m])) by (service) / sum(rate(http_server_requests_seconds_count{namespace=\"orion\", service=~\"$service\"}[5m])) by (service) * 100",
            "legendFormat": "{{service}}"
          }
        ],
        "fieldConfig": {
          "defaults": { "unit": "percent" }
        }
      },
      {
        "title": "Latency P95",
        "type": "timeseries",
        "gridPos": { "x": 0, "y": 10, "w": 12, "h": 6 },
        "targets": [
          {
            "expr": "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{namespace=\"orion\", service=~\"$service\"}[5m])) by (service, le))",
            "legendFormat": "{{service}}"
          }
        ],
        "fieldConfig": {
          "defaults": { "unit": "s" }
        }
      },
      {
        "title": "Active Alerts",
        "type": "alertlist",
        "gridPos": { "x": 12, "y": 10, "w": 12, "h": 6 },
        "options": {
          "showOptions": "current",
          "sortOrder": 1,
          "stateFilter": { "firing": true, "pending": true }
        }
      }
    ]
  }
}
```

### Business Dashboard

```json
{
  "dashboard": {
    "title": "Orion Platform - Business Metrics",
    "uid": "orion-business",
    "tags": ["orion", "business", "trading"],
    "timezone": "browser",
    "refresh": "1m",
    "panels": [
      {
        "title": "Orders Today",
        "type": "stat",
        "gridPos": { "x": 0, "y": 0, "w": 4, "h": 4 },
        "targets": [
          {
            "expr": "sum(increase(orders_submitted_total{namespace=\"orion\"}[24h]))"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "unit": "short",
            "color": { "mode": "thresholds" },
            "thresholds": {
              "steps": [
                { "value": 0, "color": "blue" }
              ]
            }
          }
        }
      },
      {
        "title": "Fill Rate",
        "type": "gauge",
        "gridPos": { "x": 4, "y": 0, "w": 4, "h": 4 },
        "targets": [
          {
            "expr": "sum(increase(orders_filled_total{namespace=\"orion\"}[24h])) / sum(increase(orders_submitted_total{namespace=\"orion\"}[24h])) * 100"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "unit": "percent",
            "min": 0,
            "max": 100,
            "thresholds": {
              "steps": [
                { "value": 0, "color": "red" },
                { "value": 80, "color": "yellow" },
                { "value": 95, "color": "green" }
              ]
            }
          }
        }
      },
      {
        "title": "Active Positions",
        "type": "stat",
        "gridPos": { "x": 8, "y": 0, "w": 4, "h": 4 },
        "targets": [
          {
            "expr": "sum(positions_active_total{namespace=\"orion\"})"
          }
        ]
      },
      {
        "title": "Trading Volume (USD)",
        "type": "stat",
        "gridPos": { "x": 12, "y": 0, "w": 4, "h": 4 },
        "targets": [
          {
            "expr": "sum(increase(trading_volume_usd_total{namespace=\"orion\"}[24h]))"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "unit": "currencyUSD"
          }
        }
      },
      {
        "title": "Order Volume by Asset Class",
        "type": "timeseries",
        "gridPos": { "x": 0, "y": 4, "w": 12, "h": 8 },
        "targets": [
          {
            "expr": "sum(rate(orders_submitted_total{namespace=\"orion\"}[5m])) by (asset_class) * 60",
            "legendFormat": "{{asset_class}}"
          }
        ],
        "fieldConfig": {
          "defaults": { "unit": "short" }
        }
      },
      {
        "title": "Order Types Distribution",
        "type": "piechart",
        "gridPos": { "x": 12, "y": 4, "w": 6, "h": 8 },
        "targets": [
          {
            "expr": "sum(increase(orders_submitted_total{namespace=\"orion\"}[24h])) by (order_type)"
          }
        ]
      },
      {
        "title": "Top Trading Pairs",
        "type": "table",
        "gridPos": { "x": 18, "y": 4, "w": 6, "h": 8 },
        "targets": [
          {
            "expr": "topk(10, sum(increase(orders_submitted_total{namespace=\"orion\"}[24h])) by (symbol))",
            "format": "table",
            "instant": true
          }
        ]
      },
      {
        "title": "Order Latency (Avg)",
        "type": "stat",
        "gridPos": { "x": 0, "y": 12, "w": 4, "h": 4 },
        "targets": [
          {
            "expr": "histogram_quantile(0.5, sum(rate(order_processing_seconds_bucket{namespace=\"orion\"}[5m])) by (le)) * 1000"
          }
        ],
        "fieldConfig": {
          "defaults": { "unit": "ms" }
        }
      },
      {
        "title": "Client Activity",
        "type": "timeseries",
        "gridPos": { "x": 4, "y": 12, "w": 20, "h": 6 },
        "targets": [
          {
            "expr": "count(count by (client_id) (rate(http_server_requests_seconds_count{namespace=\"orion\"}[5m])))",
            "legendFormat": "Active Clients"
          }
        ]
      }
    ]
  }
}
```

### Service Developer Dashboard

```json
{
  "dashboard": {
    "title": "Orion - Service Details",
    "uid": "orion-service-detail",
    "tags": ["orion", "developer", "service"],
    "templating": {
      "list": [
        {
          "name": "service",
          "type": "query",
          "datasource": "Prometheus",
          "query": "label_values(http_server_requests_seconds_count{namespace=\"orion\"}, service)"
        }
      ]
    },
    "panels": [
      {
        "title": "Service Status",
        "type": "stat",
        "gridPos": { "x": 0, "y": 0, "w": 6, "h": 3 },
        "targets": [
          {
            "expr": "up{namespace=\"orion\", service=\"$service\"}"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "mappings": [
              { "type": "value", "options": { "0": { "text": "DOWN", "color": "red" } } },
              { "type": "value", "options": { "1": { "text": "UP", "color": "green" } } }
            ]
          }
        }
      },
      {
        "title": "Replica Count",
        "type": "stat",
        "gridPos": { "x": 6, "y": 0, "w": 6, "h": 3 },
        "targets": [
          {
            "expr": "count(up{namespace=\"orion\", service=\"$service\"})"
          }
        ]
      },
      {
        "title": "Requests/sec",
        "type": "stat",
        "gridPos": { "x": 12, "y": 0, "w": 6, "h": 3 },
        "targets": [
          {
            "expr": "sum(rate(http_server_requests_seconds_count{namespace=\"orion\", service=\"$service\"}[5m]))"
          }
        ],
        "fieldConfig": {
          "defaults": { "unit": "reqps" }
        }
      },
      {
        "title": "Error Rate",
        "type": "stat",
        "gridPos": { "x": 18, "y": 0, "w": 6, "h": 3 },
        "targets": [
          {
            "expr": "sum(rate(http_server_requests_seconds_count{namespace=\"orion\", service=\"$service\", status=~\"5..\"}[5m])) / sum(rate(http_server_requests_seconds_count{namespace=\"orion\", service=\"$service\"}[5m])) * 100"
          }
        ],
        "fieldConfig": {
          "defaults": { "unit": "percent" }
        }
      },
      {
        "title": "Latency by Endpoint",
        "type": "heatmap",
        "gridPos": { "x": 0, "y": 3, "w": 12, "h": 8 },
        "targets": [
          {
            "expr": "sum(rate(http_server_requests_seconds_bucket{namespace=\"orion\", service=\"$service\"}[5m])) by (uri, le)",
            "format": "heatmap"
          }
        ]
      },
      {
        "title": "Top Errors by Endpoint",
        "type": "table",
        "gridPos": { "x": 12, "y": 3, "w": 12, "h": 8 },
        "targets": [
          {
            "expr": "topk(10, sum(increase(http_server_requests_seconds_count{namespace=\"orion\", service=\"$service\", status=~\"5..\"}[1h])) by (uri, status))",
            "format": "table",
            "instant": true
          }
        ],
        "transformations": [
          {
            "id": "organize",
            "options": {
              "renameByName": { "uri": "Endpoint", "status": "Status", "Value": "Errors" }
            }
          }
        ]
      },
      {
        "title": "JVM Heap Memory",
        "type": "timeseries",
        "gridPos": { "x": 0, "y": 11, "w": 8, "h": 6 },
        "targets": [
          {
            "expr": "jvm_memory_used_bytes{namespace=\"orion\", service=\"$service\", area=\"heap\"}",
            "legendFormat": "{{pod}} - Used"
          },
          {
            "expr": "jvm_memory_max_bytes{namespace=\"orion\", service=\"$service\", area=\"heap\"}",
            "legendFormat": "{{pod}} - Max"
          }
        ],
        "fieldConfig": {
          "defaults": { "unit": "bytes" }
        }
      },
      {
        "title": "GC Pauses",
        "type": "timeseries",
        "gridPos": { "x": 8, "y": 11, "w": 8, "h": 6 },
        "targets": [
          {
            "expr": "rate(jvm_gc_pause_seconds_sum{namespace=\"orion\", service=\"$service\"}[5m])",
            "legendFormat": "{{pod}} - {{gc}}"
          }
        ],
        "fieldConfig": {
          "defaults": { "unit": "s" }
        }
      },
      {
        "title": "Thread Count",
        "type": "timeseries",
        "gridPos": { "x": 16, "y": 11, "w": 8, "h": 6 },
        "targets": [
          {
            "expr": "jvm_threads_live_threads{namespace=\"orion\", service=\"$service\"}",
            "legendFormat": "{{pod}}"
          }
        ]
      },
      {
        "title": "Recent Traces",
        "type": "table",
        "gridPos": { "x": 0, "y": 17, "w": 24, "h": 6 },
        "datasource": "Tempo",
        "targets": [
          {
            "queryType": "search",
            "serviceName": "$service",
            "limit": 20
          }
        ]
      }
    ]
  }
}
```

### Dashboard Provisioning

```yaml
# kubernetes/grafana/dashboard-provisioning.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: grafana-dashboards-config
  namespace: monitoring
data:
  dashboards.yaml: |
    apiVersion: 1
    providers:
      - name: 'orion-dashboards'
        orgId: 1
        folder: 'Orion Platform'
        folderUid: 'orion'
        type: file
        disableDeletion: false
        updateIntervalSeconds: 30
        allowUiUpdates: true
        options:
          path: /var/lib/grafana/dashboards/orion
      
      - name: 'kubernetes-dashboards'
        orgId: 1
        folder: 'Kubernetes'
        folderUid: 'k8s'
        type: file
        disableDeletion: false
        options:
          path: /var/lib/grafana/dashboards/kubernetes
      
      - name: 'aws-dashboards'
        orgId: 1
        folder: 'AWS'
        folderUid: 'aws'
        type: file
        disableDeletion: false
        options:
          path: /var/lib/grafana/dashboards/aws
```

### Team Permissions

```yaml
# kubernetes/grafana/folder-permissions.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: grafana-folder-permissions
  namespace: monitoring
data:
  permissions.yaml: |
    folders:
      - uid: orion
        title: "Orion Platform"
        permissions:
          - role: Admin
            permission: Admin
          - team: sre-team
            permission: Edit
          - team: dev-team
            permission: View
      
      - uid: orion-business
        title: "Business Metrics"
        permissions:
          - role: Admin
            permission: Admin
          - team: business-team
            permission: View
          - team: product-team
            permission: View
      
      - uid: k8s
        title: "Kubernetes"
        permissions:
          - role: Admin
            permission: Admin
          - team: platform-team
            permission: Edit
          - team: sre-team
            permission: View
```

## Definition of Done

- [ ] Operations dashboard deployed
- [ ] Business dashboard deployed
- [ ] Developer service dashboard
- [ ] Infrastructure dashboard
- [ ] Dashboard folder organization
- [ ] Team permissions configured
- [ ] Variables and templating
- [ ] Dashboard links working
- [ ] Alerting integration
- [ ] Documentation complete

## Test Cases

```yaml
test-cases:
  - name: "Dashboard loads correctly"
    given: "User accesses Grafana"
    when: "Opening operations dashboard"
    then: "All panels render within 3 seconds"
  
  - name: "Team permissions"
    given: "Business team user"
    when: "Accessing dashboards"
    then: "Can view business dashboard but not edit"
  
  - name: "Variable filtering"
    given: "Service variable selected"
    when: "Dashboard refreshes"
    then: "All panels filter to selected service"
```
