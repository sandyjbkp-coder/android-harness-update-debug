# Session Initialization - Mandatory Memory Loading
# Part of Android Self-Healing Autonomous Agent
# Runs automatically on EVERY session start

## 9 Mandatory Load Steps

### Step 1: Load Agent Identity
```bash
# Source AGENT_RULES.md for identity, mission, constraints
source "$AGENT_ROOT/skills/AGENT_RULES.md"
echo "Identity loaded: $(grep '^agent_name:' "$AGENT_ROOT/skills/AGENT_RULES.md" | cut -d' ' -f2)"
```

### Step 2: Load User Preferences
```bash
# Load user preferences from memory
if [[ -f "$MEMORY_ROOT/sessions/user-preferences.md" ]]; then
    source "$MEMORY_ROOT/sessions/user-preferences.md"
    echo "User preferences loaded"
fi
```

### Step 3: Load Device Context
```bash
# Capture current device state
cat > "$MEMORY_ROOT/sessions/device-context-$(date +%s).md" <<EOF
# Device Context - $(date)
Device: $(getprop ro.product.model 2>/dev/null || echo "unknown")
Android: $(getprop ro.build.version.release 2>/dev/null || echo "unknown")
Kernel: $(uname -r)
Root: $(which su | head -1)
KernelSU: $(ksud --version 2>/dev/null || echo "not detected")
Chroot: ${CHROOT_ROOT:-not active}
Toolchain: $(clang --version 2>/dev/null | head -1 || echo "clang not found")
Java: $(java -version 2>&1 | head -1 || echo "java not found")
Gradle: $(gradle --version 2>/dev/null | head -1 || echo "gradle not found")
EOF
echo "Device context captured"
```

### Step 4: Load Project Context
```bash
# Capture project state
PROJECT_ROOT="/storage/emulated/0/Download/AndroidHarness-CI-main/AndroidHarness-CI-main"
cat > "$MEMORY_ROOT/sessions/project-context-$(date +%s).md" <<EOF
# Project Context - $(date)
Project: AndroidHarness-CI
Root: $PROJECT_ROOT
Git Branch: $(cd "$PROJECT_ROOT" && git branch --show-current 2>/dev/null || echo "unknown")
Git Status: $(cd "$PROJECT_ROOT" && git status --short 2>/dev/null | wc -l) changes
Last Commit: $(cd "$PROJECT_ROOT" && git log -1 --oneline 2>/dev/null || echo "none")
Build Config: $(cat "$PROJECT_ROOT/app/build.gradle.kts" 2>/dev/null | head -5 || echo "not found")
EOF
echo "Project context captured"
```

### Step 5: Search Failure Patterns
```bash
# Load recent failure patterns relevant to current task
TASK_KEYWORDS="${1:-}"
if [[ -n "$TASK_KEYWORDS" ]]; then
    echo "Searching failure patterns for: $TASK_KEYWORDS"
    grep -i "$(echo "$TASK_KEYWORDS" | cut -d' ' -f1)" "$MEMORY_ROOT/knowledge/failure-patterns.md" 2>/dev/null | head -10
else
    echo "No task keywords provided, loading recent failures"
    tail -50 "$MEMORY_ROOT/knowledge/failure-patterns.md"
fi
```

### Step 6: Search Repair Playbook
```bash
# Load relevant fixes from repair playbook
if [[ -n "$TASK_KEYWORDS" ]]; then
    echo "Searching repair playbook for: $TASK_KEYWORDS"
    grep -i "$(echo "$TASK_KEYWORDS" | cut -d' ' -f1)" "$MEMORY_ROOT/knowledge/command-repair-playbook.md" 2>/dev/null | head -10
else
    echo "Loading all verified fixes"
    grep "^## FIX-" "$MEMORY_ROOT/knowledge/command-repair-playbook.md"
fi
```

### Step 7: Filter Expired/Superseded Records
```bash
# Remove memory entries older than 90 days unless marked permanent
find "$MEMORY_ROOT/sessions" -name "*.md" -mtime +90 -not -name "*permanent*" -delete 2>/dev/null
find "$MEMORY_ROOT/logs" -name "*.jsonl" -mtime +30 -delete 2>/dev/null
echo "Expired records filtered"
```

### Step 8: State Memories Used
```bash
# Record which memories were loaded for this session
cat > "$MEMORY_ROOT/sessions/session-$(date +%s)-init.md" <<EOF
# Session Initialization - $(date)
Task: ${1:-"none specified"}

## Memories Loaded
- Agent Rules: skills/AGENT_RULES.md
- Device Context: sessions/device-context-$(date +%s).md
- Project Context: sessions/project-context-$(date +%s).md
- Failure Patterns: knowledge/failure-patterns.md (searched)
- Repair Playbook: knowledge/command-repair-playbook.md (searched)
- Retry Policy: knowledge/retry-policy.md

## Filters Applied
- Expired sessions (>90 days): removed
- Expired logs (>30 days): removed

## Active Constraints
- Safety: No destructive commands without confirmation
- Retry Budget: 3/signature, 10/session, 3/family, 2/endpoint
- Root: KernelSU required for system commands
EOF
echo "Session initialization complete"
```

### Step 9: Verify Toolchain
```bash
# Quick toolchain health check
echo "=== Toolchain Verification ==="
for tool in clang gcc rustc java gradle git adb su; do
    if command -v "$tool" &>/dev/null; then
        echo "✓ $tool: $($tool --version 2>&1 | head -1)"
    else
        echo "✗ $tool: NOT FOUND"
    fi
done
```

## Usage

```bash
# Auto-run on session start (add to shell profile)
source /path/to/agent/memory/autostart/session-init.md "your task description"

# Or run manually
bash /path/to/agent/memory/autostart/session-init.md "build android app"
```

## Integration with agent-run

The `agent-run` script should call this at the start of each execution:
```bash
# In agent-run, before executing command
source "$MEMORY_ROOT/autostart/session-init.md" "$cmd"
```
