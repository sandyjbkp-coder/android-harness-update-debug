# Failure Guard - Post-Failure Capture, Diagnose, Fix, Save
# Part of Android Self-Healing Autonomous Agent
# Runs AFTER every command failure

## Purpose
Automatically capture failures, diagnose root cause, apply fixes, retry, and save learnings.

## 7-Step Failure Handling Loop

### Step 1: Capture Exact Failure
```bash
capture_failure() {
    local cmd="$1"
    local exit_code="$2"
    local stdout="$3"
    local stderr="$4"
    local duration_ms="$5"

    local timestamp=$(date -Iseconds)
    local sig=$(echo -n "$cmd" | sha256sum | cut -d' ' -f1 | cut -c1-16)

    cat > "$MEMORY_ROOT/logs/failure-$sig-$(date +%s).json" <<EOF
{
  "timestamp": "$timestamp",
  "signature": "$sig",
  "command": "$cmd",
  "exit_code": $exit_code,
  "stdout": $(echo "$stdout" | jq -Rs .),
  "stderr": $(echo "$stderr" | jq -Rs .),
  "duration_ms": $duration_ms,
  "environment": {
    "pwd": "$(pwd)",
    "user": "$(whoami)",
    "shell": "$SHELL"
  }
}
EOF
}
```

### Step 2: Classify Failure (12 Types)
```bash
classify_failure() {
    local stderr="$1"
    local exit_code="$2"
    local combined="${stderr} ${stdout:-}"

    case "$combined" in
        *"connection refused"*|*"connection reset"*|*"timeout"*|*"timed out"*|*"deadline exceeded"*)
            echo "TRANSIENT"
            ;;
        *"permission denied"*|*"not permitted"*|*"access denied"*|*"Operation not permitted"*)
            echo "PERMISSION"
            ;;
        *"rate limit"*|*"too many requests"*|*"429"*|*"quota exceeded"*)
            echo "RATE_LIMIT"
            ;;
        *"not found"*|*"no such file"*|*"no such directory"*|*"command not found"*|*"127"*)
            echo "MISSING_DEPENDENCY"
            ;;
        *"syntax error"*|*"invalid option"*|*"unrecognized argument"*|*"bad flag"*|*"usage:"*)
            echo "WRONG_SYNTAX"
            ;;
        *"wrong path"*|*"invalid path"*|*"path does not exist"*|*"No such file or directory"*)
            echo "WRONG_PATH"
            ;;
        *"network"*|*"dns"*|*"resolve"*|*"name or service not known"*|*"Temporary failure in name resolution"*)
            echo "NETWORK"
            ;;
        *"configuration"*|*"config"*|*"misconfig"*|*"invalid configuration"*)
            echo "CONFIGURATION"
            ;;
        *"validation"*|*"invalid input"*|*"malformed"*|*"schema"*|*"constraint violation"*)
            echo "VALIDATION"
            ;;
        *)
            if [[ $exit_code -eq 124 ]] || [[ $exit_code -eq 137 ]] || [[ $exit_code -eq 143 ]]; then
                echo "TIMEOUT"
            else
                echo "UNKNOWN"
            fi
            ;;
    esac
}
```

### Step 3: Search Memory for Similar Failures
```bash
search_similar_failures() {
    local failure_type="$1"
    local cmd="$2"
    local base_cmd=$(echo "$cmd" | awk '{print $1}')

    echo "=== Similar Failures ==="

    # Search failure patterns by type
    grep -B2 -A5 "$failure_type" "$MEMORY_ROOT/knowledge/failure-patterns.md" 2>/dev/null | head -30

    # Search logs for same command family
    echo "--- Recent same-command failures ---"
    grep "$base_cmd" "$MEMORY_ROOT/logs/agent-run-$(date +%Y%m%d).jsonl" 2>/dev/null | \
        grep '"event":"command_failure"' | tail -5
}
```

### Step 4: Generate Hypotheses (≥2)
```bash
generate_hypotheses() {
    local failure_type="$1"
    local cmd="$2"
    local stderr="$3"

    echo "=== Hypotheses for $failure_type ==="

    case "$failure_type" in
        "MISSING_DEPENDENCY")
            echo "H1: Tool not installed in current environment"
            echo "H2: Tool installed but not in PATH (versioned binary)"
            echo "H3: Chroot missing bind mount for tool"
            ;;
        "PERMISSION")
            echo "H1: Missing execute permission on script/binary"
            echo "H2: Need root/KernelSU for this operation"
            echo "H3: SELinux/context restriction"
            ;;
        "CONFIGURATION")
            echo "H1: Missing required environment variable"
            echo "H2: Wrong config file path or format"
            echo "H3: Version mismatch between tool and config"
            ;;
        "RATE_LIMIT")
            echo "H1: API quota exceeded, need to wait"
            echo "H2: Burst limit hit, exponential backoff needed"
            echo "H3: Wrong API key/token being used"
            ;;
        "TIMEOUT")
            echo "H1: Network too slow for default timeout"
            echo "H2: Server overloaded, retry with backoff"
            echo "H3: Deadlock/hang in command itself"
            ;;
        "NETWORK")
            echo "H1: DNS resolution failing"
            echo "H2: No internet connectivity"
            echo "H3: Proxy/firewall blocking"
            ;;
        "WRONG_SYNTAX")
            echo "H1: Flag/option typo"
            echo "H2: Version mismatch (flag not supported)"
            echo "H3: Shell quoting/escaping issue"
            ;;
        "WRONG_PATH")
            echo "H1: Relative vs absolute path confusion"
            echo "H2: Symlink broken or missing"
            echo "H3: Working directory not what expected"
            ;;
        *)
            echo "H1: Unknown failure type, need manual investigation"
            echo "H2: Check full error output for clues"
            ;;
    esac
}
```

### Step 5: Apply Targeted Fix (Least Invasive First)
```bash
apply_fix() {
    local failure_type="$1"
    local cmd="$2"
    local base_cmd=$(echo "$cmd" | awk '{print $1}')

    echo "=== Applying Fix for $failure_type ==="

    # Search playbook for specific fix
    local fix_section=$(grep -B2 -A20 "$failure_type" "$MEMORY_ROOT/knowledge/command-repair-playbook.md" 2>/dev/null | head -30)

    if [[ -n "$fix_section" ]]; then
        echo "Found fix in playbook:"
        echo "$fix_section"

        # Auto-apply known fixes based on command family
        case "$base_cmd" in
            "./gradlew"|"gradle")
                apply_gradle_fixes "$failure_type"
                ;;
            "clang"|"clang++")
                apply_clang_fixes "$failure_type"
                ;;
            "java")
                apply_java_fixes "$failure_type"
                ;;
            "git")
                apply_git_fixes "$failure_type"
                ;;
            "curl"|"wget")
                apply_http_fixes "$failure_type"
                ;;
        esac
    else
        echo "No specific fix in playbook, applying generic strategy"
        apply_generic_fix "$failure_type" "$cmd"
    fi
}

apply_gradle_fixes() {
    local type="$1"
    case "$type" in
        "PERMISSION") chmod +x ./gradlew 2>/dev/null ;;
        "MISSING_DEPENDENCY") [[ -z "${ANDROID_SDK_ROOT:-}" && -d "/opt/android-sdk" ]] && export ANDROID_SDK_ROOT=/opt/android-sdk ;;
        "TIMEOUT"|"NETWORK") ensure_gradle_timeouts ;;
    esac
}

apply_clang_fixes() {
    local type="$1"
    case "$type" in
        "MISSING_DEPENDENCY") ensure_clang ;;
    esac
}

apply_java_fixes() {
    local type="$1"
    case "$type" in
        "CONFIGURATION") ensure_java_libpath ;;
    esac
}

apply_git_fixes() {
    local type="$1"
    case "$type" in
        "CONFIGURATION") setup_git_creds ;;
    esac
}

apply_http_fixes() {
    local type="$1"
    case "$type" in
        "RATE_LIMIT") echo "Will honor Retry-After header" ;;
        "TIMEOUT") echo "Will increase timeout" ;;
    esac
}

apply_generic_fix() {
    local type="$1"
    local cmd="$2"

    case "$type" in
        "TRANSIENT"|"NETWORK"|"TIMEOUT")
            echo "Generic: Wait and retry with backoff"
            ;;
        "PERMISSION")
            echo "Generic: Check if root needed, check file perms"
            ;;
        *)
            echo "Generic: Manual investigation required"
            ;;
    esac
}
```

### Step 6: Retry with Corrected Command
```bash
retry_with_fix() {
    local original_cmd="$1"
    local failure_type="$2"
    local attempt="$3"

    echo "=== Retry Attempt $attempt ==="

    # For rate limit, parse Retry-After
    local delay=0
    if [[ "$failure_type" == "RATE_LIMIT" ]]; then
        # Would parse from captured headers
        delay=5
    else
        # Exponential backoff
        delay=$((2 ** (attempt - 1)))
    fi

    sleep "$delay"

    # Re-execute
    eval "$original_cmd"
}
```

### Step 7: Save Fix on Success
```bash
save_fix() {
    local cmd="$1"
    local failure_type="$2"
    local fix_applied="$3"
    local success="$4"

    if [[ "$success" == "true" ]]; then
        local timestamp=$(date -Iseconds)
        local sig=$(echo -n "$cmd" | sha256sum | cut -d' ' -f1 | cut -c1-16)

        # Log successful fix
        cat >> "$MEMORY_ROOT/logs/fixes-$(date +%Y%m%d).jsonl" <<EOF
{"timestamp":"$timestamp","signature":"$sig","command":"$cmd","failure_type":"$failure_type","fix_applied":"$fix_applied","success":true}
EOF

        # Update repair playbook if new fix
        if ! grep -q "$fix_applied" "$MEMORY_ROOT/knowledge/command-repair-playbook.md" 2>/dev/null; then
            echo "NEW FIX DISCOVERED - Consider adding to playbook:"
            echo "Command: $cmd"
            echo "Failure: $failure_type"
            echo "Fix: $fix_applied"
        fi
    fi
}
```

## Complete Failure Guard Function

```bash
failure_guard() {
    local cmd="$1"
    local exit_code="$2"
    local stdout="$3"
    local stderr="$4"
    local duration_ms="$5"

    echo "=== FAILURE GUARD ACTIVATED ==="

    # Step 1: Capture
    capture_failure "$cmd" "$exit_code" "$stdout" "$stderr" "$duration_ms"

    # Step 2: Classify
    local failure_type=$(classify_failure "$stderr" "$exit_code")
    echo "Classified as: $failure_type"

    # Step 3: Search memory
    search_similar_failures "$failure_type" "$cmd"

    # Step 4: Hypotheses
    generate_hypotheses "$failure_type" "$cmd" "$stderr"

    # Step 5: Apply fix
    apply_fix "$failure_type" "$cmd"

    # Step 6: Retry (handled by agent-run retry loop)
    # Step 7: Save (handled on success)

    echo "=== FAILURE GUARD COMPLETE ==="
    echo "$failure_type"
}
```
