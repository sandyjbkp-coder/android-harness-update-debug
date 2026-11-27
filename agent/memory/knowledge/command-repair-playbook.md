# Command Repair Playbook
# Part of Android Self-Healing Autonomous Agent
# Verified fixes with reusable patterns

## Fix Index

| ID | Failure Pattern | Command Family | Fix Type | Verified |
|----|-----------------|----------------|----------|----------|
| FIX-001 | FP-001: Gradle wrapper perm | gradle | chmod | ✓ |
| FIX-002 | FP-002: Android SDK not found | gradle | env var / bind mount | ✓ |
| FIX-003 | FP-003: Clang not in PATH | clang | symlink + profile | ✓ |
| FIX-004 | FP-004: Java LD_LIBRARY_PATH | java | env var | ✓ |
| FIX-005 | FP-005: Git credential helper | git | config change | ✓ |
| FIX-006 | FP-006: Gradle network timeout | gradle | timeout/offline/proxy | ✓ |

---

## FIX-001: Gradle Wrapper Permission Denied

**Pattern**: FP-001 (PERMISSION)
**Command**: `./gradlew <task>`
**Error**: `Permission denied: ./gradlew` or `./gradlew: Permission denied`
**Exit Code**: 126

**Root Cause**: Gradle wrapper script (`gradlew`) loses executable bit after git checkout, file transfer, or extraction.

**Fix**:
```bash
chmod +x ./gradlew
```

**Verification**:
```bash
ls -la ./gradlew
# Should show: -rwxr-xr-x
./gradlew --version
# Should print Gradle version
```

**Automation**:
```bash
# In agent-run, before executing gradle commands
if [[ "$cmd" =~ ^\./gradlew ]] && [[ ! -x "./gradlew" ]]; then
    chmod +x ./gradlew
fi
```

**Reusable Pattern**: Before executing any `./script` command, check executable bit.

---

## FIX-002: Android SDK Not Found in Chroot

**Pattern**: FP-002 (MISSING_DEPENDENCY)
**Command**: `gradle build` or `./gradlew assembleDebug`
**Error**: `SDK location not found. Define location with sdk.dir in local.properties or set ANDROID_SDK_ROOT`
**Exit Code**: 1

**Root Cause**: Android SDK installed on host (`/opt/android-sdk` or `~/Android/Sdk`) but not accessible inside chroot environment.

**Fix Options** (in order of preference):

1. **Bind Mount (Best for persistent chroot)**:
```bash
# On host, before entering chroot
mount --bind /opt/android-sdk /data/local/tmp/moto-linux-env/rootfs/opt/android-sdk
```

2. **Environment Variable in Chroot Profile**:
```bash
# In /etc/profile.d/android-sdk.sh (inside chroot)
export ANDROID_SDK_ROOT=/opt/android-sdk
export PATH=$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH
```

3. **local.properties in Project**:
```properties
# In project root local.properties
sdk.dir=/opt/android-sdk
```

**Verification**:
```bash
# Inside chroot
echo $ANDROID_SDK_ROOT
ls $ANDROID_SDK_ROOT/platforms
./gradlew --version
```

**Automation**:
```bash
# In android-dev.env or chroot entry script
if [[ -z "${ANDROID_SDK_ROOT:-}" ]] && [[ -d "/opt/android-sdk" ]]; then
    export ANDROID_SDK_ROOT=/opt/android-sdk
fi
```

**Reusable Pattern**: For any tool requiring external SDK/path, check env var first, then standard locations, then bind-mount if in chroot.

---

## FIX-003: Clang Not Found in PATH

**Pattern**: FP-003 (MISSING_DEPENDENCY)
**Command**: `clang --version` or any clang invocation
**Error**: `clang: command not found` or `clang++: command not found`
**Exit Code**: 127

**Root Cause**: LLVM/Clang installed as versioned binary (`clang-18`, `clang-19`) without unversioned symlinks in `/usr/bin`.

**Fix**:
```bash
# Create symlinks (run once, persists)
ln -sf /usr/bin/clang-18 /usr/bin/clang
ln -sf /usr/bin/clang++-18 /usr/bin/clang++
ln -sf /usr/bin/llvm-config-18 /usr/bin/llvm-config
```

**Persistence** (survives chroot restart):
```bash
# Create /etc/profile.d/clang.sh
cat > /etc/profile.d/clang.sh <<'EOF'
# Clang symlinks
if [[ -f /usr/bin/clang-18 ]] && [[ ! -f /usr/bin/clang ]]; then
    ln -sf /usr/bin/clang-18 /usr/bin/clang
    ln -sf /usr/bin/clang++-18 /usr/bin/clang++
    ln -sf /usr/bin/llvm-config-18 /usr/bin/llvm-config
fi
export CC=clang
export CXX=clang++
EOF
chmod +x /etc/profile.d/clang.sh
```

**Verification**:
```bash
clang --version
clang++ --version
which clang
```

**Automation**:
```bash
# In agent-run, before clang commands
ensure_clang() {
    if ! command -v clang &>/dev/null; then
        if command -v clang-18 &>/dev/null; then
            ln -sf "$(command -v clang-18)" /usr/bin/clang
            ln -sf "$(command -v clang++-18)" /usr/bin/clang++
        fi
    fi
}
```

**Reusable Pattern**: For versioned binaries without symlinks, create symlinks in profile.d for persistence.

---

## FIX-004: Java LD_LIBRARY_PATH Missing

**Pattern**: FP-004 (CONFIGURATION)
**Command**: `java -version` or `java -jar ...`
**Error**: `Error: libjli.so: cannot open shared object file: No such file or directory`
**Exit Code**: 1

**Root Cause**: OpenJDK's `libjli.so` not in default library search path. Java needs `LD_LIBRARY_PATH` pointing to `$JAVA_HOME/lib/server` (or `lib/client`).

**Fix**:
```bash
# Find Java home
JAVA_HOME=$(readlink -f "$(which java)" | sed 's:/bin/java::')

# Set library path
export LD_LIBRARY_PATH=$JAVA_HOME/lib/server:$LD_LIBRARY_PATH
```

**Persistence**:
```bash
# In /etc/profile.d/java.sh
cat > /etc/profile.d/java.sh <<'EOF'
if command -v java &>/dev/null; then
    JAVA_HOME=$(readlink -f "$(which java)" | sed 's:/bin/java::')
    if [[ -d "$JAVA_HOME/lib/server" ]]; then
        export LD_LIBRARY_PATH=$JAVA_HOME/lib/server:$LD_LIBRARY_PATH
    fi
fi
EOF
chmod +x /etc/profile.d/java.sh
```

**Verification**:
```bash
java -version
# Should print version without libjli.so error
```

**Automation**:
```bash
# In agent-run, before java commands
ensure_java_libpath() {
    if [[ -z "${LD_LIBRARY_PATH:-}" ]] || [[ "$LD_LIBRARY_PATH" != *libjli* ]]; then
        local java_home=$(readlink -f "$(which java)" 2>/dev/null | sed 's:/bin/java::')
        if [[ -d "$java_home/lib/server" ]]; then
            export LD_LIBRARY_PATH=$java_home/lib/server:$LD_LIBRARY_PATH
        fi
    fi
}
```

**Reusable Pattern**: For JVM-based tools, always ensure LD_LIBRARY_PATH includes lib/server.

---

## FIX-005: Git Credential Helper Fails in Chroot

**Pattern**: FP-005 (CONFIGURATION)
**Command**: `git push`, `git pull`, `git fetch` (any authenticated operation)
**Error**: `fatal: credential helper 'cache' failed` or `unable to connect to cache daemon`
**Exit Code**: 128

**Root Cause**: Git credential cache uses Unix socket (`~/.git-credential-cache/socket`) which doesn't work across chroot boundary or when HOME differs.

**Fix Options**:

1. **Switch to Store Helper** (simplest, stores plaintext):
```bash
git config --global credential.helper store
```

2. **Use SSH Keys** (recommended):
```bash
# Generate SSH key
ssh-keygen -t ed25519 -C "agent@android"
# Add public key to GitHub/GitLab
# Use git@github.com:user/repo.git URLs
```

3. **Disable Helper, Use Token in URL**:
```bash
git config --global credential.helper ""
# Use https://token@github.com/user/repo.git
```

**Verification**:
```bash
git config --global credential.helper
git push origin main  # Should work without cache error
```

**Automation**:
```bash
# In agent setup or chroot entry
setup_git_creds() {
    if [[ ! -f ~/.ssh/id_ed25519 ]]; then
        git config --global credential.helper store
    fi
}
```

**Reusable Pattern**: In isolated environments (chroot, containers), avoid credential cache; use store or SSH.

---

## FIX-006: Gradle Network Timeout

**Pattern**: FP-006 (TIMEOUT / NETWORK)
**Command**: `./gradlew build`, `./gradlew dependencies`, any task downloading artifacts
**Error**: `Read timed out`, `Connection reset`, `Connection timed out`, `PKIX path building failed`
**Exit Code**: 1

**Root Cause**: Slow/unreliable network to Maven Central, Google Maven, or JCenter. Gradle default timeouts too short.

**Fix Options** (combine as needed):

1. **Increase Gradle Timeouts** (in `gradle.properties`):
```properties
# Connection timeout (ms)
systemProp.org.gradle.internal.http.connectionTimeout=120000
# Socket timeout (ms)
systemProp.org.gradle.internal.http.socketTimeout=120000
```

2. **Use Offline Mode** (if deps cached):
```bash
./gradlew --offline build
```

3. **Configure Proxy** (if behind corporate proxy):
```properties
# In gradle.properties
systemProp.http.proxyHost=proxy.example.com
systemProp.http.proxyPort=8080
systemProp.https.proxyHost=proxy.example.com
systemProp.https.proxyPort=8080
```

4. **Use Mirror** (Chinese networks):
```properties
# In init.gradle or settings.gradle
repositories {
    maven { url 'https://maven.aliyun.com/repository/public' }
    maven { url 'https://maven.aliyun.com/repository/google' }
    maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
}
```

5. **Retry with Backoff** (handled by agent-run retry policy)

**Verification**:
```bash
./gradlew dependencies --offline  # Should work if cached
./gradlew build  # Should complete without timeout
```

**Automation**:
```bash
# In agent-run, for gradle commands
ensure_gradle_timeouts() {
    local props="gradle.properties"
    if [[ ! -f "$props" ]] || ! grep -q "connectionTimeout" "$props"; then
        cat >> "$props" <<'EOF'
systemProp.org.gradle.internal.http.connectionTimeout=120000
systemProp.org.gradle.internal.http.socketTimeout=120000
EOF
    fi
}
```

**Reusable Pattern**: For any build tool downloading deps, configure generous timeouts and mirrors proactively.

---

## Fix Application Protocol

When agent-run encounters a failure:

1. **Classify** using failure-patterns.md taxonomy
2. **Search** command-repair-playbook.md for matching FIX-XXX
3. **Apply** least invasive fix first (env var > config > install > symlink)
4. **Verify** fix works with test command
5. **Retry** original command
6. **On Success**: Log fix application, update memory index
7. **On Failure**: Escalate to next fix or alert manual

## Adding New Fixes

When a new fix is discovered:
1. Assign next FIX-XXX ID
2. Document: Pattern, Command, Error, Root Cause, Fix, Verification, Automation
3. Link to failure pattern (FP-XXX)
4. Add automation snippet for agent-run
5. Test in isolation
6. Add to this playbook
