# Retrieval Guard - Pre-Command Search Rules
# Part of Android Self-Healing Autonomous Agent
# Runs BEFORE every command execution

## Purpose
Prevent known failures by searching memory for similar commands and their fixes BEFORE execution.

## Rules

### Rule 1: Search Failure Patterns
```bash
# Before ANY command, search for similar failures
search_failures() {
    local cmd="$1"
    local base_cmd=$(echo "$cmd" | awk '{print $1}')
    local sig=$(echo -n "$cmd" | sha256sum | cut -d' ' -f1 | cut -c1-16)

    # Search by command family
    grep -i "$base_cmd" "$MEMORY_ROOT/knowledge/failure-patterns.md" 2>/dev/null | head -5

    # Search by signature (exact command match)
    grep "$sig" "$MEMORY_ROOT/logs/agent-run-$(date +%Y%m%d).jsonl" 2>/dev/null | tail -3
}
```

### Rule 2: Search Repair Playbook
```bash
# Check for known fixes for this command family
search_fixes() {
    local cmd="$1"
    local base_cmd=$(echo "$cmd" | awk '{print $1}')

    # Search playbook for this command
    grep -B2 -A5 "$base_cmd" "$MEMORY_ROOT/knowledge/command-repair-playbook.md" 2>/dev/null | head -20
}
```

### Rule 3: Apply Preemptive Fixes
```bash
# Apply known fixes before they fail
apply_preemptive_fixes() {
    local cmd="$1"

    case "$cmd" in
        ./gradlew*)
            # FIX-001: Ensure wrapper executable
            [[ -f "./gradlew" && ! -x "./gradlew" ]] && chmod +x ./gradlew
            # FIX-002: Ensure Android SDK
            [[ -z "${ANDROID_SDK_ROOT:-}" && -d "/opt/android-sdk" ]] && export ANDROID_SDK_ROOT=/opt/android-sdk
            # FIX-006: Ensure Gradle timeouts
            ensure_gradle_timeouts
            ;;
        clang*|clang++*)
            # FIX-003: Ensure clang symlinks
            ensure_clang
            ;;
        java*)
            # FIX-004: Ensure Java LD_LIBRARY_PATH
            ensure_java_libpath
            ;;
        git\ push*|git\ pull*|git\ fetch*)
            # FIX-005: Ensure git credentials work
            setup_git_creds
            ;;
    esac
}
```

### Rule 4: Check Retry Budget
```bash
# Verify we haven't exhausted retry budget
check_retry_budget() {
    local cmd="$1"
    local sig=$(echo -n "$cmd" | sha256sum | cut -d' ' -f1 | cut -c1-16)
    local today=$(date +%Y%m%d)
    local log_file="$MEMORY_ROOT/logs/agent-run-$today.jsonl"

    # Per-signature budget (3)
    local sig_count=$(grep -c "\"signature\":\"$sig\"" "$log_file" 2>/dev/null || echo 0)
    if [[ $sig_count -ge 3 ]]; then
        echo "BLOCKED: Signature budget exhausted (3/3)"
        return 1
    fi

    # Per-session budget (10)
    local session_count=$(grep -c '"event":"command_failure"' "$log_file" 2>/dev/null || echo 0)
    if [[ $session_count -ge 10 ]]; then
        echo "BLOCKED: Session budget exhausted (10/10)"
        return 1
    fi

    # Per-family budget (3)
    local base_cmd=$(echo "$cmd" | awk '{print $1}')
    local family_count=$(grep "\"command\":\"$base_cmd" "$log_file" 2>/dev/null | grep -c '"event":"command_failure"' || echo 0)
    if [[ $family_count -ge 3 ]]; then
        echo "BLOCKED: Family budget exhausted for $base_cmd (3/3)"
        return 1
    fi

    return 0
}
```

### Rule 5: Check Rate Limits
```bash
# Check if API endpoint is rate limited
check_rate_limit() {
    local cmd="$1"

    # Only for curl/wget/http commands
    if [[ "$cmd" =~ ^(curl|wget|http) ]]; then
        local endpoint=$(echo "$cmd" | grep -oE 'https?://[^/]+' | head -1)
        if [[ -n "$endpoint" ]]; then
            local today=$(date +%Y%m%d)
            local log_file="$MEMORY_ROOT/logs/agent-run-$today.jsonl"
            local endpoint_count=$(grep "$endpoint" "$log_file" 2>/dev/null | grep -c '"failure_type":"RATE_LIMIT"' || echo 0)
            if [[ $endpoint_count -ge 2 ]]; then
                echo "WARNING: Endpoint $endpoint has recent rate limits ($endpoint_count/2)"
                # Could parse Retry-After from last failure
            fi
        fi
    fi
}
```

## Integration Point

```bash
# In agent-run, BEFORE executing command:
pre_execution_guard() {
    local cmd="$1"

    echo "=== Retrieval Guard ==="
    search_failures "$cmd"
    search_fixes "$cmd"
    apply_preemptive_fixes "$cmd"
    check_retry_budget "$cmd" || return 1
    check_rate_limit "$cmd"
    echo "=== Guard Passed ==="
}
```

## Logging

All guard checks are logged:
```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "level": "INFO",
  "event": "retrieval_guard",
  "command": "./gradlew build",
  "failures_found": 2,
  "fixes_applied": ["chmod +x ./gradlew", "ANDROID_SDK_ROOT set"],
  "budget_ok": true
}
```
