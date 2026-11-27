# Alerts Specification
# Part of Android Self-Healing Autonomous Agent
# 8 Alerts with Severity, Conditions, and Actions

## Alert Definitions

| Alert ID | Name | Severity | Condition | Cooldown | Escalation |
|----------|------|----------|-----------|----------|------------|
| ALT-01 | task_failure_rate_high | HIGH | task.failed / task.total > 0.20 over 1h | 30 min | Page on-call |
| ALT-02 | command_failure_rate_high | HIGH | command.failure_rate > 0.15 over 15m | 15 min | Slack + Page |
| ALT-03 | retry_budget_exhausted | HIGH | Any signature hits 3/3 retries | 5 min | Immediate |
| ALT-04 | api_rate_limit_hit | HIGH | api.rate_limit.hits > 0 in 5m | 5 min | Slack |
| ALT-05 | api_error_rate_high | MEDIUM | api.request.error_rate > 0.05 over 10m | 10 min | Slack |
| ALT-06 | memory_index_stale | MEDIUM | memory.index_freshness_hours > 48 | 1 hour | Daily digest |
| ALT-07 | disk_space_low | LOW | Disk usage > 85% on agent volumes | 6 hours | Daily digest |
| ALT-08 | toolchain_degraded | MEDIUM | Any core tool (clang, java, gradle) fails health check | 30 min | Slack |

## Alert Schema

```json
{
  "alert_id": "ALT-01",
  "name": "task_failure_rate_high",
  "severity": "HIGH",
  "condition": "task.failed / task.total > 0.20",
  "window": "1h",
  "cooldown": "30m",
  "evaluation_interval": "1m",
  "actions": [
    { "type": "log", "target": "alerts-YYYYMMDD.jsonl" },
    { "type": "notify", "channel": "slack", "template": "task_failure" },
    { "type": "page", "target": "oncall", "after": "2 occurrences" }
  ],
  "labels": {
    "component": "task_orchestrator",
    "team": "android-agent"
  },
  "runbook": "runbooks/task_failure_rate_high.md"
}
```

## Alert Details

### ALT-01: Task Failure Rate High (HIGH)
- **Trigger**: >20% of tasks fail in 1 hour
- **Likely Causes**: Systematic issue (SDK broken, toolchain broken, network down)
- **Auto-Remediation**: Pause new tasks, run diagnostic
- **Runbook**: Check recent failures, verify toolchain, check chroot health

### ALT-02: Command Failure Rate High (HIGH)
- **Trigger**: >15% of commands fail after all retries in 15 min
- **Likely Causes**: New failure pattern, environment drift, dependency change
- **Auto-Remediation**: Enable fail-fast for affected command family
- **Runbook**: Analyze failure types, check for new patterns, update playbook

### ALT-03: Retry Budget Exhausted (HIGH)
- **Trigger**: Any command signature hits 3/3 retries
- **Likely Causes**: Permanent failure misclassified, fix not working
- **Auto-Remediation**: Block command signature, alert immediately
- **Runbook**: Manual investigation required - check failure classification

### ALT-04: API Rate Limit Hit (HIGH)
- **Trigger**: Any 429 response from API
- **Likely Causes**: Quota exceeded, burst limit, wrong credentials
- **Auto-Remediation**: Parse Retry-After, queue requests, switch endpoint
- **Runbook**: Check API quota, verify credentials, review usage

### ALT-05: API Error Rate High (MEDIUM)
- **Trigger**: >5% API errors (5xx, 4xx excluding 429) in 10 min
- **Likely Causes**: API degradation, network issues, bad requests
- **Auto-Remediation**: Increase backoff, circuit breaker
- **Runbook**: Check API status page, review error codes

### ALT-06: Memory Index Stale (MEDIUM)
- **Trigger**: memory.index_freshness_hours > 48
- **Likely Causes**: Index update job failed, no sessions running
- **Auto-Remediation**: Trigger index rebuild on next session
- **Runbook**: Run index rebuild manually, check cron job

### ALT-07: Disk Space Low (LOW)
- **Trigger**: >85% disk usage on agent volumes
- **Likely Causes**: Log accumulation, workspace buildup, failed cleanup
- **Auto-Remediation**: Compress old logs, clean workspace >30 days
- **Runbook**: Run cleanup script, check retention policies

### ALT-08: Toolchain Degraded (MEDIUM)
- **Trigger**: Core tool health check fails (clang, java, gradle, git, adb)
- **Likely Causes**: Tool update broken, chroot issue, PATH problem
- **Auto-Remediation**: Attempt tool reinstall/relink, fallback versions
- **Runbook**: Verify each tool, check chroot bind mounts, review updates

## Alert Actions

### Log Action
Write to `alerts-YYYYMMDD.jsonl` with full context.

### Notify Action (Slack)
```json
{
  "type": "notify",
  "channel": "slack",
  "webhook_url": "${SLACK_WEBHOOK_URL}",
  "template": "alert_template",
  "fields": ["alert_id", "severity", "value", "threshold", "labels"]
}
```

### Page Action
```json
{
  "type": "page",
  "target": "oncall",
  "method": "pagerduty|opsgenie|victorops",
  "integration_key": "${PAGERDUTY_KEY}",
  "after": "2 occurrences"
}
```

### Auto-Remediation Action
```json
{
  "type": "auto_remediate",
  "playbook": "remediation/task_failure_rate_high.sh",
  "max_attempts": 1
}
```

## Suppression Rules

- Suppress ALT-01 if ALT-02 is firing (command failures cause task failures)
- Suppress ALT-05 if ALT-04 is firing (rate limits cause errors)
- Suppress ALT-07 during known maintenance windows

## Testing Alerts

```bash
# Trigger test alert
agent-alert test ALT-01

# List active alerts
agent-alert list

# Silence alert
agent-alert silence ALT-01 1h

# View alert history
cat logs/alerts-$(date +%Y%m%d).jsonl | jq '.alert_id, .severity'
```
