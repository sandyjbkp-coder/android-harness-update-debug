# Failure Patterns Knowledge Base
# Part of Android Self-Healing Autonomous Agent
# Updated: $(date)

## Failure Taxonomy (12 Types)

| Code | Type | Description | Retryable | Typical Fix |
|------|------|-------------|-----------|-------------|
| FP-TR | TRANSIENT | Temporary glitch, flaky network, race condition | Yes (3x) | Wait + retry |
| FP-PM | PERMANENT | Logic error, bug, fundamental incompatibility | No | Code/config change |
| FP-CF | CONFIGURATION | Wrong config, missing env var, bad path | No | Fix config |
| FP-MD | MISSING_DEPENDENCY | Tool not installed, lib missing, package absent | No | Install dependency |
| FP-WP | WRONG_PATH | Path typo, relative/absolute mismatch, symlink broken | No | Correct path |
| FP-WS | WRONG_SYNTAX | Bad flags, typo in command, version mismatch | No | Fix syntax |
| FP-PE | PERMISSION | Not root, SELinux, file perms, capabilities | Sometimes | Add perms/sudo |
| FP-RL | RATE_LIMIT | API quota, 429, throttle | Yes (header-aware) | Wait Retry-After |
| FP-NW | NETWORK | DNS, connectivity, proxy, firewall | Yes (3x) | Check connectivity |
| FP-TO | TIMEOUT | Slow response, hang, deadline exceeded | Yes (3x) | Increase timeout |
| FP-VA | VALIDATION | Schema mismatch, type error, constraint violation | No | Fix input |
| FP-UN | UNKNOWN | Unclassified, new error pattern | Maybe | Investigate |

## Documented Patterns

### FP-001: Gradle Wrapper Permission Denied
- **Type**: FP-PE (PERMISSION)
- **Command**: `./gradlew assembleDebug`
- **Error**: `Permission denied: ./gradlew`
- **Root Cause**: Wrapper script not executable after checkout
- **Fix**: `chmod +x ./gradlew`
- **Verification**: `ls -la ./gradlew` shows `-rwxr-xr-x`
- **Occurrences**: 3
- **Last Seen**: 2024-01-15

### FP-002: Android SDK Not Found in Chroot
- **Type**: FP-MD (MISSING_DEPENDENCY)
- **Command**: `gradle build`
- **Error**: `SDK location not found. Define location with sdk.dir`
- **Root Cause**: Android SDK only in host, not in chroot
- **Fix**: Bind-mount SDK or set ANDROID_SDK_ROOT to host path
- **Workaround**: Use `export ANDROID_SDK_ROOT=/opt/android-sdk` in chroot profile
- **Occurrences**: 5
- **Last Seen**: 2024-01-20

### FP-003: Clang Not Found in PATH
- **Type**: FP-MD (MISSING_DEPENDENCY)
- **Command**: `clang --version`
- **Error**: `clang: command not found`
- **Root Cause**: Clang installed in `/usr/bin/clang-18` but not symlinked
- **Fix**: `ln -sf /usr/bin/clang-18 /usr/bin/clang` (in chroot)
- **Persistence**: Add to `/etc/profile.d/clang.sh`
- **Occurrences**: 2
- **Last Seen**: 2024-01-18

### FP-004: Java LD_LIBRARY_PATH Missing
- **Type**: FP-CF (CONFIGURATION)
- **Command**: `java -version`
- **Error**: `libjli.so: cannot open shared object file`
- **Root Cause**: Java needs LD_LIBRARY_PATH to find libjli.so
- **Fix**: `export LD_LIBRARY_PATH=/usr/lib/jvm/java-17-openjdk/lib/server:$LD_LIBRARY_PATH`
- **Occurrences**: 4
- **Last Seen**: 2024-01-22

### FP-005: Git Credential Helper Fails in Chroot
- **Type**: FP-CF (CONFIGURATION)
- **Command**: `git push origin main`
- **Error**: `fatal: credential helper 'cache' failed`
- **Root Cause**: Git credential cache socket not accessible in chroot
- **Fix**: `git config --global credential.helper store` or use SSH keys
- **Occurrences**: 1
- **Last Seen**: 2024-01-10

### FP-006: Network Timeout in Gradle Dependency Download
- **Type**: FP-TO (TIMEOUT) / FP-NW (NETWORK)
- **Command**: `./gradlew dependencies`
- **Error**: `Read timed out` / `Connection reset`
- **Root Cause**: Slow network, Maven Central throttling
- **Fix**: Increase timeout: `./gradlew --offline` or configure proxy
- **Retry**: Exponential backoff 1s, 2s, 4s
- **Occurrences**: 7
- **Last Seen**: 2024-01-25

---

## Pattern Lookup Usage

```bash
# Search by error keyword
grep -i "permission denied" failure-patterns.md

# Search by failure type
grep "FP-PE" failure-patterns.md

# Get fix for pattern
grep -A 5 "FP-001" failure-patterns.md | grep "Fix:"
```

## Adding New Patterns

When a new failure is encountered:
1. Capture exact command + error + exit code
2. Classify using taxonomy above
3. Find root cause (≥2 hypotheses)
4. Apply fix, verify
5. Add entry here with FP-XXX ID
6. Add fix to command-repair-playbook.md
