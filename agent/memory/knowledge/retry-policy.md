# Retry Policy Specification
# Part of Android Self-Healing Autonomous Agent
# Version: 1.0

## Retry Budget

| Scope | Limit | Window | Action on Exhaust |
|-------|-------|--------|-------------------|
| Per Command Signature | 3 attempts | Session | Fail task, alert |
| Per Session | 10 total retries | Session | Pause, require manual |
| Per Command Family | 3 attempts | Session | Switch strategy |
| Per API Endpoint | 2 attempts | 5 min | Backoff, queue |

**Command Signature**: SHA256(command + args) first 16 chars
**Command Family**: Base command (e.g., `gradle`, `git`, `curl`, `adb`)

## Backoff Strategy

### Exponential Backoff with Jitter

| Attempt | Base Delay | Jitter Range | Max Delay |
|---------|------------|--------------|-----------|
| 1 | 1s | ±0.5s | 1.5s |
| 2 | 2s | ±1s | 3s |
| 3 | 4s | ±2s | 6s |

**Formula**: `delay = min(base * 2^(attempt-1), max_delay) + random(-jitter, +jitter)`

### Failure-Type Specific Backoff

| Failure Type | Attempt 1 | Attempt 2 | Attempt 3 | Special Handling |
|--------------|-----------|-----------|-----------|------------------|
| RATE_LIMIT (429) | Parse Retry-After | Parse Retry-After | Parse Retry-After | Header-aware, respect server |
| TIMEOUT | 1s | 2s | 4s | Increase client timeout |
| TRANSIENT | 1s | 2s | 4s | Standard exponential |
| NETWORK | 1s | 2s | 4s | Check connectivity first |
| PERMISSION | 0s (fail fast) | - | - | Require manual fix |
| MISSING_DEPENDENCY | 0s (fail fast) | - | - | Install, then retry |
| WRONG_SYNTAX | 0s (fail fast) | - | - | Fix syntax, then retry |
| VALIDATION | 0s (fail fast) | - | - | Fix input, then retry |

## Header-Aware Delay Parsing

### Supported Headers

| Header | Format | Parsing |
|--------|--------|---------|
| `Retry-After` | Seconds (integer) | Direct use |
| `Retry-After` | HTTP-date (RFC 7231) | `date -d "$header" +%s` - now |
| `retry-after-ms` | Milliseconds (integer) | Divide by 1000 |
| `x-ratelimit-reset` | Unix timestamp (seconds) | `header - now` |
| `x-ratelimit-remaining` | Integer | If 0, use reset header |

### Parsing Algorithm

```bash
parse_retry_delay() {
    local headers="$1"  # All response headers as string
    local now=$(date +%s)

    # Priority order
    if echo "$headers" | grep -qi "retry-after:"; then
        local val=$(echo "$headers" | grep -i "retry-after:" | head -1 | sed 's/.*[Rr]etry-[Aa]fter[:=]\s*//' | awk '{print $1}')
        if [[ "$val" =~ ^[0-9]+$ ]]; then
            echo "$val"
            return
        fi
        # HTTP-date
        local retry_date=$(date -d "$val" +%s 2>/dev/null)
        if [[ -n "$retry_date" ]]; then
            echo $((retry_date - now))
            return
        fi
    fi

    if echo "$headers" | grep -qi "retry-after-ms:"; then
        local val=$(echo "$headers" | grep -i "retry-after-ms:" | head -1 | sed 's/.*[Rr]etry-[Aa]fter-ms[:=]\s*//' | awk '{print $1}')
        if [[ "$val" =~ ^[0-9]+$ ]]; then
            echo $((val / 1000))
            return
        fi
    fi

    if echo "$headers" | grep -qi "x-ratelimit-reset:"; then
        local val=$(echo "$headers" | grep -i "x-ratelimit-reset:" | head -1 | sed 's/.*[Xx]-[Rr]ate[lL]imit-[Rr]eset[:=]\s*//' | awk '{print $1}')
        if [[ "$val" =~ ^[0-9]+$ ]]; then
            echo $((val - now))
            return
        fi
    fi

    echo 0  # No header found
}
```

## Retry Decision Matrix

```
                    ┌─────────────────────┐
                    │ Command Failed      │
                    └──────────┬──────────┘
                               │
                    ┌──────────▼──────────┐
                    │ Classify Failure    │
                    └──────────┬──────────┘
                               │
              ┌────────────────┼────────────────┐
              ▼                ▼                ▼
       ┌──────────┐    ┌──────────────┐  ┌────────────┐
       │Retryable │    │ Header-Aware │  │ Fail-Fast  │
       │(TRANSIENT│    │ (RATE_LIMIT) │  │ (PERM, CFG,│
       │ NETWORK, │    │              │  │  DEP, SYN, │
       │ TIMEOUT) │    │              │  │  VAL)      │
       └────┬─────┘    └──────┬───────┘  └─────┬──────┘
            │                 │                 │
      ┌─────▼─────┐     ┌─────▼─────┐     ┌─────▼─────┐
      │ Check     │     │ Parse     │     │ Log, alert│
      │ Budget    │     │ Headers   │     │ No retry  │
      └─────┬─────┘     └─────┬─────┘     └───────────┘
            │                 │
      ┌─────▼─────┐     ┌─────▼─────┐
      │ Budget OK?│     │ Budget OK?│
      └─────┬─────┘     └─────┬─────┘
       ┌───┴───┐         ┌────┴────┐
       ▼       ▼         ▼         ▼
      Yes     No        Yes       No
       │       │         │         │
       ▼       ▼         ▼         ▼
    Backoff  Alert    Wait      Alert
    +Retry   Manual    Headers   Manual
            Fix             +Retry
```

## Implementation Checklist

- [x] Per-signature budget tracking (3/session)
- [x] Per-session total budget (10/session)
- [x] Per-family budget (3/session)
- [x] Per-endpoint budget (2/5min)
- [x] Exponential backoff with jitter
- [x] Header parsing: Retry-After (seconds)
- [x] Header parsing: Retry-After (HTTP-date)
- [x] Header parsing: retry-after-ms
- [x] Header parsing: x-ratelimit-reset
- [x] Fail-fast for non-retryable types
- [x] Structured logging of all retry decisions
- [x] Metrics: retry_count, retry_exhausted, header_delay_used

## Configuration Overrides

```bash
# Environment variables (set in android-dev.env)
export AGENT_RETRY_MAX_ATTEMPTS=3
export AGENT_RETRY_BASE_DELAY=1
export AGENT_RETRY_MAX_DELAY=30
export AGENT_RETRY_SESSION_BUDGET=10
export AGENT_RETRY_FAMILY_BUDGET=3
export AGENT_RETRY_ENDPOINT_BUDGET=2
export AGENT_RETRY_ENDPOINT_WINDOW=300
```

## Metrics Emitted

| Metric | Type | Description |
|--------|------|-------------|
| `retry.attempt` | Counter | Each retry attempt |
| `retry.exhausted` | Counter | Budget exhausted |
| `retry.header_delay_used` | Counter | Server header delay honored |
| `retry.backoff_ms` | Histogram | Actual backoff applied |
| `retry.failure_type` | Counter | By classification |
