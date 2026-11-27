# Metrics Specification
# Part of Android Self-Healing Autonomous Agent
# 16 Metrics with Types, Targets, and Alerts

## Metric Definitions

### Task Metrics

| Metric ID | Name | Type | Description | Target | Alert Threshold |
|-----------|------|------|-------------|--------|-----------------|
| M-01 | task.total | Counter | Total tasks started | N/A | - |
| M-02 | task.success | Counter | Tasks completed successfully | >90% of total | <80% success rate |
| M-03 | task.failed | Counter | Tasks failed | <10% of total | >20% failure rate |
| M-04 | task.duration_ms | Histogram | Task execution time | p50 < 30s, p95 < 120s | p95 > 300s |

### API Metrics

| Metric ID | Name | Type | Description | Target | Alert Threshold |
|-----------|------|------|-------------|--------|-----------------|
| M-05 | api.request.total | Counter | Total API requests | N/A | - |
| M-06 | api.request.latency_ms | Histogram | API response latency | p50 < 500ms, p95 < 2s | p95 > 10s |
| M-07 | api.request.error_rate | Gauge | Error rate (5xx, 429) | <1% | >5% |
| M-08 | api.rate_limit.hits | Counter | Rate limit (429) responses | 0 | >0 |

### Memory Metrics

| Metric ID | Name | Type | Description | Target | Alert Threshold |
|-----------|------|------|-------------|--------|-----------------|
| M-09 | memory.files_total | Gauge | Total memory files | <1000 | >5000 |
| M-10 | memory.index_freshness_hours | Gauge | Hours since index update | <24 | >48 |
| M-11 | memory.recall_accuracy | Gauge | Cross-session recall success | 100% | <95% |
| M-12 | memory.autostart_duration_ms | Histogram | Session init time | p50 < 2s | p95 > 10s |

### Command Metrics

| Metric ID | Name | Type | Description | Target | Alert Threshold |
|-----------|------|------|-------------|--------|-----------------|
| M-13 | command.total | Counter | Total commands executed | N/A | - |
| M-14 | command.retry_rate | Gauge | Commands requiring retry | <10% | >25% |
| M-15 | command.failure_rate | Gauge | Commands failing after all retries | <5% | >15% |
| M-16 | command.avg_latency_ms | Histogram | Command execution time | p50 < 5s | p95 > 60s |

## Metric Schema (JSON)

Each metric emission follows:
```json
{
  "metric_id": "M-01",
  "name": "task.total",
  "type": "counter",
  "value": 1,
  "timestamp": "2024-01-15T10:30:00Z",
  "labels": {
    "session_id": "sess-abc123",
    "task_type": "build",
    "agent_version": "1.0.0"
  }
}
```

## Collection Points

| Metric | Collected At |
|--------|--------------|
| M-01 to M-04 | Task start, completion, failure |
| M-05 to M-08 | Each API call (LLM, GitHub, etc.) |
| M-09 to M-11 | Session init, memory operations |
| M-12 | Session init completion |
| M-13 to M-16 | Each command execution (agent-run) |

## Aggregation

- **Counters**: Sum over time window
- **Gauges**: Last value in window
- **Histograms**: Percentiles (p50, p95, p99) over window

## Retention

- Raw metrics: 7 days (JSONL in logs/metrics-YYYYMMDD.jsonl)
- Aggregated (1h): 30 days
- Aggregated (1d): 365 days

## Dashboards

See dashboards.md for text-based dashboard definitions.
