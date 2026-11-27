# Command Guard - Pre-Execution Checks
# Part of Android Self-Healing Autonomous Agent
# Runs IMMEDIATELY before every command execution

## Purpose
Final safety and validity checks before command runs. Catches issues that retrieval-guard might miss.

## Checks

### Check 1: Command Existence
```bash
check_command_exists() {
    local cmd="$1"
    local base_cmd=$(echo "$cmd" | awk '{print $1}')

    # Handle relative paths
    if [[ "$base_cmd" == ./* ]] || [[ "$base_cmd" == /* ]]; then
        if [[ ! -f "$base_cmd" ]]; then
            echo "ERROR: File not found: $base_cmd"
            return 1
        fi
        if [[ ! -x "$base_cmd" ]]; then
            echo "WARNING: Not executable: $base_cmd (attempting chmod +x)"
            chmod +x "$base_cmd" 2>/dev/null || return 1
        fi
    else
        # Check PATH
        if ! command -v "$base_cmd" &>/dev/null; then
            echo "ERROR: Command not found in PATH: $base_cmd"
            return 1
        fi
    fi
    return 0
}
```

### Check 2: Safety Policy
```bash
check_safety() {
    local cmd="$1"

    # Blocklist patterns
    local blocked_patterns=(
        "rm\s+-rf\s+/"
        "dd\s+if="
        "mkfs"
        "fdisk"
        "parted"
        "format"
        ":(){ :|:& };:"  # Fork bomb
        "chmod\s+-R\s+777\s+/"
        "chown\s+-R\s+root:root\s+/"
        ">\s*/dev/sd"
        ">\s*/dev/mmcblk"
    )

    for pattern in "${blocked_patterns[@]}"; do
        if [[ "$cmd" =~ $pattern ]]; then
            echo "BLOCKED: Safety violation - matches pattern: $pattern"
            return 1
        fi
    done

    # Warn on risky but allowed
    local warn_patterns=(
        "rm\s+-rf"
        "chmod\s+-R"
        "chown\s+-R"
        "su\s+-c"
        "sudo"
    )

    for pattern in "${warn_patterns[@]}"; do
        if [[ "$cmd" =~ $pattern ]]; then
            echo "WARNING: Risky command pattern: $pattern"
        fi
    done

    return 0
}
```

### Check 3: Resource Availability
```bash
check_resources() {
    local cmd="$1"

    # Check disk space for write operations
    if [[ "$cmd" =~ (>\s*|tee\s+|cp\s+|mv\s+|wget\s+|curl\s+.*-o) ]]; then
        local target_dir=$(echo "$cmd" | grep -oE '(>\s*|tee\s+|cp\s+.*\s|mv\s+.*\s|wget\s+.*-O\s+|curl\s+.*-o\s+)[^ ]+' | head -1 | sed 's/.*[>\s]//' | xargs dirname)
        if [[ -n "$target_dir" && -d "$target_dir" ]]; then
            local avail_kb=$(df "$target_dir" | awk 'NR==2 {print $4}')
            if [[ $avail_kb -lt 102400 ]]; then  # Less than 100MB
                echo "WARNING: Low disk space on $target_dir (${avail_kb}KB)"
            fi
        fi
    fi

    # Check memory for heavy commands
    if [[ "$cmd" =~ (gradle|clang|rustc|java.*-Xmx) ]]; then
        local avail_mem_kb=$(grep MemAvailable /proc/meminfo | awk '{print $2}')
        if [[ $avail_mem_kb -lt 512000 ]]; then  # Less than 500MB
            echo "WARNING: Low memory (${avail_mem_kb}KB)"
        fi
    fi
}
```

### Check 4: Environment Validity
```bash
check_environment() {
    local cmd="$1"

    # Check required env vars for known commands
    case "$cmd" in
        ./gradlew*|gradle*)
            if [[ -z "${ANDROID_SDK_ROOT:-}" ]] && [[ ! -f "local.properties" ]]; then
                echo "WARNING: ANDROID_SDK_ROOT not set and no local.properties"
            fi
            if [[ -z "${JAVA_HOME:-}" ]]; then
                echo "WARNING: JAVA_HOME not set"
            fi
            ;;
        clang*|clang++*)
            if ! command -v clang &>/dev/null; then
                echo "ERROR: clang not in PATH"
                return 1
            fi
            ;;
        java*)
            if ! command -v java &>/dev/null; then
                echo "ERROR: java not in PATH"
                return 1
            fi
            if [[ -z "${LD_LIBRARY_PATH:-}" ]] || [[ "$LD_LIBRARY_PATH" != *libjli* ]]; then
                echo "WARNING: LD_LIBRARY_PATH may be missing libjli.so"
            fi
            ;;
        git\ push*|git\ pull*|git\ fetch*)
            if ! git config --global credential.helper &>/dev/null; then
                echo "WARNING: No git credential helper configured"
            fi
            ;;
    esac
}
```

### Check 5: Dependency Health
```bash
check_dependencies() {
    local cmd="$1"
    local base_cmd=$(echo "$cmd" | awk '{print $1}')

    # Quick health check for known tools
    case "$base_cmd" in
        gradle|./gradlew)
            # Check if gradle daemon is responsive
            if pgrep -f "GradleDaemon" &>/dev/null; then
                echo "INFO: Gradle daemon running"
            fi
            ;;
        clang)
            clang --version &>/dev/null || echo "WARNING: clang version check failed"
            ;;
        java)
            java -version 2>&1 | grep -q "version" || echo "WARNING: java version check failed"
            ;;
        git)
            git --version &>/dev/null || echo "WARNING: git version check failed"
            ;;
    esac
}
```

### Check 6: Concurrency Guard
```bash
check_concurrency() {
    local cmd="$1"
    local base_cmd=$(echo "$cmd" | awk '{print $1}')

    # Prevent parallel runs of same heavy command
    local lock_file="/tmp/agent-lock-$base_cmd"
    if [[ "$base_cmd" =~ ^(gradle|./gradlew|clang|rustc|java)$ ]]; then
        if [[ -f "$lock_file" ]]; then
            local pid=$(cat "$lock_file" 2>/dev/null)
            if kill -0 "$pid" 2>/dev/null; then
                echo "BLOCKED: $base_cmd already running (PID: $pid)"
                return 1
            else
                echo "WARNING: Stale lock file, removing"
                rm -f "$lock_file"
            fi
        fi
        echo $$ > "$lock_file"
        trap "rm -f $lock_file" EXIT
    fi
}
```

## Complete Command Guard

```bash
command_guard() {
    local cmd="$1"

    echo "=== COMMAND GUARD ==="

    check_command_exists "$cmd" || return 1
    check_safety "$cmd" || return 1
    check_resources "$cmd"
    check_environment "$cmd" || return 1
    check_dependencies "$cmd"
    check_concurrency "$cmd"

    echo "=== COMMAND GUARD PASSED ==="
    return 0
}
```

## Integration

```bash
# In agent-run, after retrieval-guard, before execution:
pre_execution_checks() {
    local cmd="$1"

    # Retrieval guard (search memory, apply preemptive fixes)
    source "$MEMORY_ROOT/autostart/retrieval-guard.md"
    pre_execution_guard "$cmd" || return 1

    # Command guard (safety, resources, environment)
    source "$MEMORY_ROOT/autostart/command-guard.md"
    command_guard "$cmd" || return 1

    return 0
}
```

## Logging

```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "level": "INFO",
  "event": "command_guard",
  "command": "./gradlew build",
  "checks": {
    "command_exists": true,
    "safety": true,
    "resources": "ok",
    "environment": "warning: JAVA_HOME not set",
    "dependencies": "ok",
    "concurrency": "ok"
  },
  "result": "passed"
}
```
