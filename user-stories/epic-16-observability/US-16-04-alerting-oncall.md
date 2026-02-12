# US-16-04: Alerting & On-Call

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-16-04 |
| **Epic** | Epic 16: Observability |
| **Title** | Alerting & On-Call |
| **Priority** | High |
| **Story Points** | 8 |
| **Status** | Ready for Development |

## User Story

**As a** on-call engineer  
**I want** intelligent alerting with actionable notifications  
**So that** I can respond to incidents quickly and effectively

## Acceptance Criteria

### AC1: Alertmanager Configuration
- **Given** Prometheus alerts
- **When** thresholds breached
- **Then**:
  - Alert grouping
  - Inhibition rules
  - Silence management
  - Multi-channel routing

### AC2: PagerDuty Integration
- **Given** critical alerts
- **When** routed to PagerDuty
- **Then**:
  - Incident created
  - On-call notified
  - Escalation policies
  - Acknowledgement sync

### AC3: Slack Integration
- **Given** warning alerts
- **When** sent to Slack
- **Then**:
  - Rich message format
  - Alert actions (ack, silence)
  - Thread grouping
  - Channel routing

### AC4: Alert Rules
- **Given** SLO requirements
- **When** rules defined
- **Then**:
  - Multi-window burn rates
  - Error budget alerts
  - Latency percentile alerts
  - Capacity alerts

### AC5: Runbook Automation
- **Given** alert context
- **When** runbook linked
- **Then**:
  - Auto-remediation scripts
  - Diagnostic commands
  - Escalation procedures
  - Post-incident actions

## Technical Specification

### Alertmanager Configuration

```yaml
# kubernetes/monitoring/alertmanager-config.yaml
apiVersion: v1
kind: Secret
metadata:
  name: alertmanager-config
  namespace: monitoring
stringData:
  alertmanager.yaml: |
    global:
      resolve_timeout: 5m
      smtp_smarthost: 'smtp.example.com:587'
      smtp_from: 'alertmanager@orion.example.com'
      smtp_auth_username: 'alertmanager'
      smtp_auth_password: '${SMTP_PASSWORD}'
      pagerduty_url: 'https://events.pagerduty.com/v2/enqueue'
      slack_api_url: '${SLACK_WEBHOOK_URL}'

    route:
      receiver: 'default-receiver'
      group_by: ['alertname', 'cluster', 'service', 'severity']
      group_wait: 30s
      group_interval: 5m
      repeat_interval: 4h
      routes:
        # Critical alerts go to PagerDuty immediately
        - match:
            severity: critical
          receiver: 'pagerduty-critical'
          group_wait: 10s
          repeat_interval: 1h
          continue: true
        
        # High severity alerts
        - match:
            severity: high
          receiver: 'pagerduty-high'
          group_wait: 30s
          repeat_interval: 2h
          continue: true
        
        # Warning alerts to Slack
        - match:
            severity: warning
          receiver: 'slack-warning'
          group_wait: 1m
          repeat_interval: 4h
        
        # Info alerts to Slack (quieter channel)
        - match:
            severity: info
          receiver: 'slack-info'
          group_wait: 5m
          repeat_interval: 24h
        
        # Database alerts to dedicated team
        - match_re:
            alertname: '^(Aurora|Postgres|Redis).*'
          receiver: 'slack-database-team'
          continue: true
        
        # Trading alerts - highest priority
        - match_re:
            alertname: '^(Order|Position|Trade).*'
            severity: critical|high
          receiver: 'pagerduty-trading'
          group_wait: 0s
          repeat_interval: 30m

    inhibit_rules:
      # Don't alert on individual pods if the entire service is down
      - source_match:
          alertname: 'ServiceDown'
        target_match:
          alertname: 'PodNotReady'
        equal: ['service', 'namespace']
      
      # Don't alert on high latency if there are errors
      - source_match:
          alertname: 'HighErrorRate'
        target_match:
          alertname: 'HighLatency'
        equal: ['service']
      
      # Don't alert if cluster is under maintenance
      - source_match:
          alertname: 'ClusterMaintenance'
        target_match_re:
          severity: 'warning|info'
        equal: ['cluster']

    receivers:
      - name: 'default-receiver'
        slack_configs:
          - channel: '#orion-alerts'
            send_resolved: true
            title: '{{ template "slack.title" . }}'
            text: '{{ template "slack.text" . }}'
            actions:
              - type: button
                text: 'Runbook'
                url: '{{ (index .Alerts 0).Annotations.runbook_url }}'
              - type: button
                text: 'Dashboard'
                url: '{{ (index .Alerts 0).Annotations.dashboard_url }}'
              - type: button
                text: 'Silence'
                url: '{{ template "slack.silence_url" . }}'

      - name: 'pagerduty-critical'
        pagerduty_configs:
          - service_key: '${PAGERDUTY_SERVICE_KEY_CRITICAL}'
            severity: critical
            description: '{{ template "pagerduty.description" . }}'
            details:
              firing: '{{ template "pagerduty.firing" . }}'
              cluster: '{{ .GroupLabels.cluster }}'
              service: '{{ .GroupLabels.service }}'
              runbook: '{{ (index .Alerts 0).Annotations.runbook_url }}'

      - name: 'pagerduty-high'
        pagerduty_configs:
          - service_key: '${PAGERDUTY_SERVICE_KEY_HIGH}'
            severity: error
            description: '{{ template "pagerduty.description" . }}'
            details:
              firing: '{{ template "pagerduty.firing" . }}'

      - name: 'pagerduty-trading'
        pagerduty_configs:
          - service_key: '${PAGERDUTY_SERVICE_KEY_TRADING}'
            severity: critical
            description: '[TRADING] {{ template "pagerduty.description" . }}'
            client: 'Orion Platform'
            client_url: 'https://grafana.orion.example.com'

      - name: 'slack-warning'
        slack_configs:
          - channel: '#orion-alerts'
            send_resolved: true
            color: '{{ template "slack.color" . }}'
            title: '{{ template "slack.title" . }}'
            text: '{{ template "slack.text" . }}'

      - name: 'slack-info'
        slack_configs:
          - channel: '#orion-alerts-info'
            send_resolved: false
            title: '{{ template "slack.title" . }}'
            text: '{{ template "slack.text" . }}'

      - name: 'slack-database-team'
        slack_configs:
          - channel: '#orion-database-team'
            send_resolved: true
            title: '[Database] {{ template "slack.title" . }}'
            text: '{{ template "slack.text" . }}'

    templates:
      - '/etc/alertmanager/templates/*.tmpl'
```

### Alert Templates

```yaml
# kubernetes/monitoring/alertmanager-templates.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: alertmanager-templates
  namespace: monitoring
data:
  slack.tmpl: |
    {{ define "slack.title" }}
    [{{ .Status | toUpper }}{{ if eq .Status "firing" }}:{{ .Alerts.Firing | len }}{{ end }}] {{ .GroupLabels.alertname }}
    {{ end }}

    {{ define "slack.text" }}
    {{ range .Alerts }}
    *Alert:* {{ .Annotations.summary }}
    *Severity:* `{{ .Labels.severity }}`
    *Service:* {{ .Labels.service }}
    *Description:* {{ .Annotations.description }}
    {{ if .Annotations.runbook_url }}*Runbook:* <{{ .Annotations.runbook_url }}|View Runbook>{{ end }}
    {{ if .Annotations.dashboard_url }}*Dashboard:* <{{ .Annotations.dashboard_url }}|View Dashboard>{{ end }}
    *Started:* {{ .StartsAt.Format "2006-01-02 15:04:05 UTC" }}
    {{ end }}
    {{ end }}

    {{ define "slack.color" }}
    {{ if eq .Status "firing" }}
    {{ if eq (index .Alerts 0).Labels.severity "critical" }}danger{{ else if eq (index .Alerts 0).Labels.severity "warning" }}warning{{ else }}good{{ end }}
    {{ else }}good{{ end }}
    {{ end }}

    {{ define "slack.silence_url" }}
    {{ .ExternalURL }}/#/silences/new?filter=%7B{{ range .GroupLabels.SortedPairs }}{{ .Name }}%3D%22{{ .Value }}%22%2C%20{{ end }}%7D
    {{ end }}

  pagerduty.tmpl: |
    {{ define "pagerduty.description" }}
    {{ .GroupLabels.alertname }}: {{ (index .Alerts 0).Annotations.summary }}
    {{ end }}

    {{ define "pagerduty.firing" }}
    {{ range .Alerts.Firing }}
    - {{ .Annotations.summary }}
    {{ end }}
    {{ end }}
```

### PrometheusRules for Alerting

```yaml
# kubernetes/monitoring/alert-rules.yaml
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: orion-alerts
  namespace: monitoring
  labels:
    release: prometheus
spec:
  groups:
    # SLO-based alerts using multi-window burn rates
    - name: orion.slo.alerts
      rules:
        # Fast burn - 2% budget consumed in 1 hour (high severity)
        - alert: HighErrorBudgetBurn
          expr: |
            (
              orion:http_errors:rate5m > (14.4 * 0.001)  # 14.4x burn rate
              and
              orion:http_errors:rate1h > (14.4 * 0.001)
            )
            or
            (
              orion:http_errors:rate5m > (6 * 0.001)  # 6x burn rate  
              and
              orion:http_errors:rate6h > (6 * 0.001)
            )
          for: 2m
          labels:
            severity: critical
          annotations:
            summary: "High error rate consuming error budget quickly"
            description: "Service {{ $labels.service }} is experiencing elevated error rates that will exhaust the monthly error budget."
            runbook_url: "https://runbooks.orion.example.com/HighErrorBudgetBurn"
            dashboard_url: "https://grafana.orion.example.com/d/slo-dashboard?var-service={{ $labels.service }}"

        # Slow burn - will exhaust budget before month end
        - alert: SlowErrorBudgetBurn
          expr: |
            (
              orion:http_errors:rate5m > (3 * 0.001)
              and
              orion:http_errors:rate24h > (3 * 0.001)
            )
            or
            (
              orion:http_errors:rate5m > (1 * 0.001)
              and
              orion:http_errors:rate3d > (1 * 0.001)
            )
          for: 1h
          labels:
            severity: warning
          annotations:
            summary: "Error rate will exhaust monthly budget"
            description: "Service {{ $labels.service }} error rate will consume the monthly error budget before month end."
            runbook_url: "https://runbooks.orion.example.com/SlowErrorBudgetBurn"

    # Latency alerts
    - name: orion.latency.alerts
      rules:
        - alert: HighP99Latency
          expr: |
            orion:http_latency:p99 > 2
          for: 5m
          labels:
            severity: warning
          annotations:
            summary: "P99 latency exceeds 2 seconds"
            description: "Service {{ $labels.service }} P99 latency is {{ $value | humanizeDuration }}"
            runbook_url: "https://runbooks.orion.example.com/HighLatency"

        - alert: CriticalLatency
          expr: |
            orion:http_latency:p95 > 5
          for: 2m
          labels:
            severity: critical
          annotations:
            summary: "P95 latency exceeds 5 seconds"
            description: "Service {{ $labels.service }} is critically slow. P95: {{ $value | humanizeDuration }}"

    # Infrastructure alerts
    - name: orion.infrastructure.alerts
      rules:
        - alert: PodNotReady
          expr: |
            kube_pod_status_ready{namespace="orion", condition="true"} == 0
          for: 5m
          labels:
            severity: warning
          annotations:
            summary: "Pod {{ $labels.pod }} is not ready"
            description: "Pod {{ $labels.namespace }}/{{ $labels.pod }} has been not ready for more than 5 minutes."

        - alert: PodCrashLooping
          expr: |
            rate(kube_pod_container_status_restarts_total{namespace="orion"}[15m]) * 60 * 15 > 3
          for: 5m
          labels:
            severity: critical
          annotations:
            summary: "Pod {{ $labels.pod }} is crash looping"
            description: "Pod {{ $labels.namespace }}/{{ $labels.pod }} is restarting frequently."

        - alert: HighMemoryUsage
          expr: |
            container_memory_working_set_bytes{namespace="orion"} / container_spec_memory_limit_bytes > 0.9
          for: 5m
          labels:
            severity: warning
          annotations:
            summary: "Container {{ $labels.container }} memory usage > 90%"
            description: "Container {{ $labels.pod }}/{{ $labels.container }} is using {{ $value | humanizePercentage }} of its memory limit."

        - alert: HighCPUUsage
          expr: |
            rate(container_cpu_usage_seconds_total{namespace="orion"}[5m]) / container_spec_cpu_quota * container_spec_cpu_period > 0.9
          for: 10m
          labels:
            severity: warning
          annotations:
            summary: "Container {{ $labels.container }} CPU usage > 90%"

    # Database alerts
    - name: orion.database.alerts
      rules:
        - alert: AuroraHighCPU
          expr: |
            aws_rds_cpuutilization_average > 80
          for: 10m
          labels:
            severity: warning
          annotations:
            summary: "Aurora cluster CPU > 80%"
            description: "Aurora cluster {{ $labels.dbinstance_identifier }} CPU at {{ $value }}%"

        - alert: AuroraHighConnections
          expr: |
            orion:db:connection_pool_usage > 0.85
          for: 5m
          labels:
            severity: warning
          annotations:
            summary: "Database connection pool nearly exhausted"
            description: "Connection pool {{ $labels.pool }} is {{ $value | humanizePercentage }} utilized"

        - alert: AuroraReplicaLag
          expr: |
            aws_rds_aurora_replica_lag_average > 1000
          for: 5m
          labels:
            severity: warning
          annotations:
            summary: "Aurora replica lag > 1 second"

        - alert: RedisHighMemory
          expr: |
            redis_memory_used_bytes / redis_memory_max_bytes > 0.9
          for: 5m
          labels:
            severity: critical
          annotations:
            summary: "Redis memory usage > 90%"

    # Kafka alerts
    - name: orion.kafka.alerts
      rules:
        - alert: KafkaConsumerLag
          expr: |
            orion:kafka:consumer_lag > 10000
          for: 5m
          labels:
            severity: warning
          annotations:
            summary: "Kafka consumer lag is high"
            description: "Consumer group {{ $labels.consumer_group }} on topic {{ $labels.topic }} has lag of {{ $value }}"

        - alert: KafkaConsumerLagCritical
          expr: |
            orion:kafka:consumer_lag > 100000
          for: 2m
          labels:
            severity: critical
          annotations:
            summary: "Kafka consumer lag is critically high"

    # Business alerts
    - name: orion.business.alerts
      rules:
        - alert: LowOrderRate
          expr: |
            orion:orders:rate1m < 0.1
          for: 15m
          labels:
            severity: warning
          annotations:
            summary: "Order submission rate is unusually low"
            description: "Order rate is {{ $value }}/min, which is below expected threshold"

        - alert: HighOrderRejectionRate
          expr: |
            sum(rate(orders_rejected_total[5m])) / sum(rate(orders_submitted_total[5m])) > 0.1
          for: 5m
          labels:
            severity: warning
          annotations:
            summary: "Order rejection rate > 10%"
            description: "{{ $value | humanizePercentage }} of orders are being rejected"
```

### Runbook Automation Lambda

```python
# lambda/runbook-automation/handler.py
import json
import boto3
import os
import logging

logger = logging.getLogger()
logger.setLevel(logging.INFO)

ssm = boto3.client('ssm')
eks = boto3.client('eks')
sns = boto3.client('sns')

RUNBOOK_ACTIONS = {
    'HighMemoryUsage': 'restart_pod',
    'PodCrashLooping': 'collect_diagnostics',
    'KafkaConsumerLag': 'scale_consumers',
    'AuroraHighCPU': 'alert_dba_team',
    'HighErrorRate': 'rollback_deployment'
}

def handler(event, context):
    logger.info(f"Received alert: {json.dumps(event)}")
    
    alert_name = event.get('alertname')
    labels = event.get('labels', {})
    status = event.get('status')
    
    if status != 'firing':
        logger.info(f"Alert {alert_name} resolved, no action needed")
        return {'statusCode': 200, 'body': 'Resolved alert, no action'}
    
    action = RUNBOOK_ACTIONS.get(alert_name)
    if not action:
        logger.info(f"No automated action for {alert_name}")
        return {'statusCode': 200, 'body': 'No automation configured'}
    
    try:
        result = execute_action(action, labels, event)
        
        # Send notification about automated action
        sns.publish(
            TopicArn=os.environ['NOTIFICATION_TOPIC'],
            Subject=f'Runbook Action Executed: {action}',
            Message=json.dumps({
                'alert': alert_name,
                'action': action,
                'result': result,
                'labels': labels
            })
        )
        
        return {'statusCode': 200, 'body': json.dumps(result)}
        
    except Exception as e:
        logger.error(f"Failed to execute action: {e}")
        raise

def execute_action(action, labels, event):
    if action == 'restart_pod':
        return restart_pod(labels['namespace'], labels['pod'])
    elif action == 'collect_diagnostics':
        return collect_pod_diagnostics(labels['namespace'], labels['pod'])
    elif action == 'scale_consumers':
        return scale_deployment(labels['namespace'], labels.get('deployment', labels['service']), 2)
    elif action == 'alert_dba_team':
        return escalate_to_team('database', event)
    elif action == 'rollback_deployment':
        return rollback_deployment(labels['namespace'], labels.get('deployment', labels['service']))
    
    return {'action': action, 'status': 'not_implemented'}

def restart_pod(namespace, pod_name):
    # Use kubectl through SSM
    command = f"kubectl delete pod {pod_name} -n {namespace}"
    response = ssm.send_command(
        InstanceIds=[os.environ['BASTION_INSTANCE_ID']],
        DocumentName='AWS-RunShellScript',
        Parameters={'commands': [command]}
    )
    return {'action': 'restart_pod', 'pod': pod_name, 'command_id': response['Command']['CommandId']}

def collect_pod_diagnostics(namespace, pod_name):
    commands = [
        f"kubectl logs {pod_name} -n {namespace} --tail=1000 > /tmp/{pod_name}-logs.txt",
        f"kubectl describe pod {pod_name} -n {namespace} > /tmp/{pod_name}-describe.txt",
        f"aws s3 cp /tmp/{pod_name}-logs.txt s3://{os.environ['DIAGNOSTICS_BUCKET']}/diagnostics/",
        f"aws s3 cp /tmp/{pod_name}-describe.txt s3://{os.environ['DIAGNOSTICS_BUCKET']}/diagnostics/"
    ]
    response = ssm.send_command(
        InstanceIds=[os.environ['BASTION_INSTANCE_ID']],
        DocumentName='AWS-RunShellScript',
        Parameters={'commands': commands}
    )
    return {'action': 'collect_diagnostics', 'pod': pod_name, 'command_id': response['Command']['CommandId']}

def scale_deployment(namespace, deployment, scale_factor):
    command = f"kubectl scale deployment {deployment} -n {namespace} --replicas=$(kubectl get deployment {deployment} -n {namespace} -o jsonpath='{{.spec.replicas}}' | awk '{{print $1 * {scale_factor}}}')"
    response = ssm.send_command(
        InstanceIds=[os.environ['BASTION_INSTANCE_ID']],
        DocumentName='AWS-RunShellScript',
        Parameters={'commands': [command]}
    )
    return {'action': 'scale_deployment', 'deployment': deployment, 'factor': scale_factor}

def rollback_deployment(namespace, deployment):
    command = f"kubectl rollout undo deployment {deployment} -n {namespace}"
    response = ssm.send_command(
        InstanceIds=[os.environ['BASTION_INSTANCE_ID']],
        DocumentName='AWS-RunShellScript',
        Parameters={'commands': [command]}
    )
    return {'action': 'rollback', 'deployment': deployment}

def escalate_to_team(team, event):
    sns.publish(
        TopicArn=os.environ[f'{team.upper()}_TEAM_TOPIC'],
        Subject=f'[ESCALATION] {event.get("alertname")}',
        Message=json.dumps(event)
    )
    return {'action': 'escalate', 'team': team}
```

## Definition of Done

- [ ] Alertmanager deployed with HA
- [ ] PagerDuty integration working
- [ ] Slack notifications configured
- [ ] Alert grouping and inhibition
- [ ] SLO-based alert rules
- [ ] Multi-window burn rate alerts
- [ ] Runbook links in alerts
- [ ] Automated remediation for common issues
- [ ] On-call schedules configured
- [ ] Documentation complete

## Test Cases

```yaml
test-cases:
  - name: "Critical alert routing"
    given: "Critical severity alert fires"
    when: "Alertmanager processes alert"
    then: "PagerDuty incident created within 1 minute"
  
  - name: "Alert grouping"
    given: "Multiple similar alerts fire"
    when: "Alerts have same group labels"
    then: "Single grouped notification sent"
  
  - name: "Automated remediation"
    given: "HighMemoryUsage alert"
    when: "Lambda triggered"
    then: "Pod restarted automatically"
```
