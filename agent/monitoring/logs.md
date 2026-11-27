# Logs Specification
# Part of Android Self-Healing Autonomous Agent
# 10 Log Types in JSONL Format, 30-Day Rotation

## Log Types

| Type ID | Name | Description | File Pattern | Retention |
|---------|------|-------------|--------------|-----------|
| LOG-01 | session | Session start/end, task context | session-YYYYMMDD.jsonl | 30 days |
| LOG-02 | command | Command execution, success/failure | agent-run-YYYYMMDD.jsonl | 30 days |
| LOG-03 | retry | Retry attempts, backoff, header parsing | retry-YYYYMMDD.jsonl | 30 days |
| LOG-04 | fix | Fix applied, success/failure | fixes-YYYYMMDD.jsonl | 90 days |
| LOG-05 | memory | Memory load, search, index updates | memory-YYYYMMDD.jsonl | 30 days |
| LOG-06 | guard | Retrieval/command/failure guard events | guard-YYYYMMDD.jsonl | 30 days |
| LOG-07 | metric | Metric emissions (also in metrics/) | metrics-YYYYMMDD.jsonl | 30 days |
| LOG-08 | alert | Alert triggers, escalations | alerts-YYYYMMDD.jsonl | 90 days |
| LOG-09 | error | Unhandled errors, exceptions | errors-YYYYMMDD.jsonl | 90 days |
| LOG-10 | audit | Security-relevant actions (root, config changes) | audit-YYYYMMDD.jsonl | 365 days |

## JSONL Schema (Common Fields)

Every log entry has:
```json
{
  "timestamp": "2024-01-15T10:30:00.123Z",
  "level": "INFO|WARN|ERROR|DEBUG",
  "event": "event_name",
  "session_id": "sess-abc123",
  "agent_version": "1.0.0",
  "trace_id": "optional-correlation-id"
}
```

## Type-Specific Schemas

### LOG-01: Session
```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "level": "INFO",
  "event": "session_start",
  "session_id": "sess-abc123",
  "task": "build android app",
  "device": "pixel7",
  "android_version": "14",
  "kernel_su": true,
  "chroot_active": true
}
```

### LOG-02: Command
```json
{
  "timestamp": "2024-01-15T10:30:05Z",
  "level": "INFO",
  "event": "command_success",
  "session_id": "sess-abc123",
  "command": "./gradlew assembleDebug",
  "signature": "a1b2c3d4e5f6",
  "exit_code": 0,
  "duration_ms": 45230,
  "attempt": 1,
  "failure_type": null
}
```
```json
{
  "timestamp": "2024-01-15T10:30:05Z",
  "level": "ERROR",
  "event": "command_failure",
  "session_id": "sess-abc123",
  "command": "./gradlew assembleDebug",
  "signature": "a1b2c3d4e5f6",
  "exit_code": 1,
  "duration_ms": 5000,
  "attempt": 1,
  "failure_type": "MISSING_DEPENDENCY",
  "error": "SDK location not found..."
}
```

### LOG-03: Retry
```json
{
  "timestamp": "2024-01-15T10:30:10Z",
  "level": "WARN",
  "event": "retry_attempt",
  "session_id": "sess-abc123",
  "command": "./gradlew assembleDebug",
  "signature": "a1b2c3d4e5f6",
  "attempt": 2,
  "failure_type": "MISSING_DEPENDENCY",
  "backoff_ms": 2000,
  "header_delay_used": false,
  "retry_after_parsed": null
}
```

### LOG-04: Fix
```json
{
  "timestamp": "2024-01-15T10:30:15Z",
  "level": "INFO",
  "event": "fix_applied",
  "session_id": "sess-abc123",
  "command": "./gradlew assembleDebug",
  "signature": "a1b2c3d4e5f6",
  "failure_type": "MISSING_DEPENDENCY",
  "fix": "export ANDROID_SDK_ROOT=/opt/android-sdk",
  "fix_source": "playbook:FIX-002",
  "success": true,
  "verification_command": "echo $ANDROID_SDK_ROOT"
}
```

### LOG-05: Memory
```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "level": "INFO",
  "event": "memory_loaded",
  "session_id": "sess-abc123",
  "memory_type": "failure-patterns",
  "files_loaded": 3,
  "patterns_matched": ["FP-002"],
  "duration_ms": 150
}
```

### LOG-06: Guard
```json
{
  "timestamp": "2024-01-15T10:30:03Z",
  "level": "INFO",
  "event": "retrieval_guard",
  "session_id": "sess-abc123",
  "command": "./gradlew build",
  "failures_found": 2,
  "fixes_applied": ["chmod +x ./gradlew", "ANDROID_SDK_ROOT set"],
  "budget_ok": true,
  "budget_remaining": { "signature": 2, "session": 8, "family": 2 }
}
```

### LOG-07: Metric
```json
{
  "timestamp": "2024-01-15T10:30:05Z",
  "level": "INFO",
  "event": "metric_emitted",
  "metric_id": "M-14",
  "name": "command.retry_rate",
  "type": "gauge",
  "value": 0.15,
  "labels": { "command_family": "gradle" }
}
```

### LOG-08: Alert
```json
{
  "timestamp": "2024-01-15T10:30:05Z",
  "level": "ERROR",
  "event": "alert_triggered",
  "alert_id": "ALT-03",
  "name": "high_failure_rate",
  "severity": "HIGH",
  "value": 0.25,
  "threshold": 0.15,
  "labels": { "command_family": "gradle" }
}
```

### LOG-09: Error
```json
{
  "timestamp": "2024-01-15T10:30:05Z",
  "level": "ERROR",
  "event": "unhandled_error",
  "session_id": "sess-abc123",
  "error": "NullPointerException in AgentEngine",
  "stack_trace": "...",
  "context": { "command": "./gradlew", "phase": "execution" }
}
```

### LOG-10: Audit
```json
{
  "timestamp": "2024-01-15T10:30:05Z",
  "level": "INFO",
  "event": "audit",
  "session_id": "sess-abc123",
  "action": "root_command",
  "command": "mount --bind /opt/android-sdk /chroot/opt/android-sdk",
  "user": "root",
  "result": "success"
}
```

## Redaction Rules

The following fields are NEVER logged in plaintext:
- API keys, tokens, passwords
- GitHub tokens, personal access tokens
- SSH private keys
- Database connection strings
- Any field matching: `*key*`, `*token*`, `*password*`, `*secret*`, `*credential*`

Redaction implementation:
```bash
redact() {
    local json="$1"
    echo "$json" | jq 'walk(if type == "object" then with_entries(if .key | test("(?i)(key|token|password|secret|credential)") then .value = "[REDACTED]" else . end) else . end)'
}
```

## Rotation

- Daily files: `type-YYYYMMDD.jsonl`
- Rotation at midnight UTC
- Compression: gzip after 7 days
- Deletion: after retention period (30/90/365 days)

## Query Examples

```bash
# All failures for a command
grep "command_failure" logs/agent-run-20240115.jsonl | jq '.command'

# Retry rate per command family
grep "retry_attempt" logs/retry-20240115.jsonl | jq -r '.command | split(" ")[0]' | sort | uniq -c

# Fixes applied today
cat logs/fixes-20240115.jsonl | jq '.fix'

# Alert history
cat logs/alerts-20240115.jsonl | jq '.alert_id, .severity'
```
