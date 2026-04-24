#!/bin/bash
# Standalone migration runner — bypasses rewrite-gradle-plugin.
#
# Flow:
#   1. Runs discovery-init.gradle.kts to extract per-source-set dirs from the target Gradle project
#      (configuration-only, no compile cascade). The init-script classifies each project as
#      `buildLogic`, `publishedGradlePlugin`, or `production`:
#        - `buildLogic`           — applies java-gradle-plugin AND does NOT publish. The
#                                   plugin stays inside this build (buildSrc, convention plugins,
#                                   pluginManagement-included builds). Safe to auto-migrate.
#        - `publishedGradlePlugin` — applies java-gradle-plugin AND publishes (maven-publish /
#                                   ivy-publish / com.gradle.plugin-publish). Consumed outside
#                                   this build by users on various Gradle versions. SKIPPED by
#                                   default to avoid breaking downstream users; opt in via
#                                   --include-published-plugins.
#        - `production`           — not a Gradle plugin. Never migrated.
#   2. Filters to the selected kinds, plus `.gradle[.kts]` scripts and nested builds.
#   3. Invokes StandaloneRunner against those dirs with the MigrateToProviderApi recipe.
#
# Usage:
#   ./run-migration.sh <target-project-dir> [--apply] [--verbose] [--include-published-plugins]
#
# By default this is a dry run — pass --apply to write changes to disk.

set -e

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="${1:-}"
if [ -z "$TARGET" ] || [ "$TARGET" = "-h" ] || [ "$TARGET" = "--help" ]; then
    echo "usage: $0 <target-project-dir> [--apply] [--verbose] [--include-published-plugins]"
    echo ""
    echo "  target-project-dir            absolute or relative path to a Gradle project"
    echo "  --apply                       write changes (default: dry run)"
    echo "  --verbose                     print unified diffs in dry-run mode"
    echo "  --include-published-plugins   also migrate Gradle plugins published to Maven Central /"
    echo "                                Plugin Portal. OFF by default — these plugins often support"
    echo "                                many Gradle versions back, so auto-migration can break users."
    exit 2
fi
TARGET="$(cd "$TARGET" && pwd)"
shift || true
EXTRA_ARGS=("$@")
INCLUDE_PUBLISHED="no"
for a in "${EXTRA_ARGS[@]}"; do
    case "$a" in
        --include-published-plugins) INCLUDE_PUBLISHED="yes" ;;
    esac
done

echo "[run-migration] target: $TARGET"

# --- 1. build the runner distribution if missing
LAUNCHER="$HERE/build/install/gradle-provider-api-migration/bin/gradle-provider-api-migration"
if [ ! -x "$LAUNCHER" ]; then
    echo "[run-migration] building standalone runner..."
    (cd "$HERE" && ./gradlew installDist -q)
fi

# --- 2. discovery: extract manifest
echo "[run-migration] discovering source sets..."
rm -f "$TARGET/.rewrite-manifest.json"
# The discovery init-script throws on purpose to abort before task execution. That's a "normal"
# failure. Real Gradle failures (JDK mismatch, unresolved dependency in settings.gradle, etc.)
# also end in a failed build — they look identical on exit code, so we rely on the manifest file
# being produced as the success signal. Full output is captured to a tmpfile; on failure we show
# the last 30 lines so the user can see what actually went wrong (wrong JDK is the common one).
DISCOVERY_LOG="$(mktemp -t rewrite-discovery.XXXXXX.log)"
(cd "$TARGET" && ./gradlew --init-script "$HERE/discovery-init.gradle.kts" help) > "$DISCOVERY_LOG" 2>&1 || true
grep -E '^\[rewrite-discovery\]' "$DISCOVERY_LOG" || true
if [ ! -f "$TARGET/.rewrite-manifest.json" ]; then
    echo "[run-migration] ERROR: discovery did not produce .rewrite-manifest.json"
    echo "[run-migration] last 30 lines of Gradle output (full log at $DISCOVERY_LOG):"
    tail -30 "$DISCOVERY_LOG" | sed 's/^/    /'
    echo ""
    echo "[run-migration] Common causes:"
    echo "    - Wrong JDK. The target project's Gradle may need a different Java version"
    echo "      (e.g. 'error: 25.0.2' → target needs JDK <=16 for Gradle 7.x)."
    echo "      Set JAVA_HOME to a compatible JDK and retry."
    echo "    - Unresolved plugins/deps in settings.gradle or the root build script."
    exit 3
fi

# --- 3. filter and run
# Two kinds of inputs:
#   (a) Source-set dirs whose owning project is build-logic per the manifest classification
#       (project applies `java-gradle-plugin`). We walk them recursively.
#   (b) Project root dirs where *.gradle / *.gradle.kts scripts live directly. We add those SCRIPT
#       FILES individually (not the whole project root, which would pull in production src/).
SRC_DIRS=()
SCRIPT_FILES=()

# 3a — source-set dirs marked buildLogic by the manifest (and optionally publishedGradlePlugin).
# Count and report skipped published-plugin source sets for visibility.
SKIPPED_PUBLISHED_PROJECTS=$(python3 -c '
import json, sys
with open(sys.argv[1]) as f:
    data = json.load(f)
projects = sorted({ss["project"] for ss in data.get("sourceSets", []) if ss.get("kind") == "publishedGradlePlugin"})
for p in projects: print(p)
' "$TARGET/.rewrite-manifest.json")
if [ -n "$SKIPPED_PUBLISHED_PROJECTS" ] && [ "$INCLUDE_PUBLISHED" != "yes" ]; then
    echo "[run-migration] skipping published Gradle plugin projects (use --include-published-plugins to migrate):"
    echo "$SKIPPED_PUBLISHED_PROJECTS" | sed 's/^/    /'
fi

while IFS= read -r dir; do
    dir="${dir%\"*}"; dir="${dir#*\"}"
    [ -d "$dir" ] || continue
    SRC_DIRS+=("$dir")
done < <(python3 -c '
import json, sys
include_published = sys.argv[2] == "yes"
with open(sys.argv[1]) as f:
    data = json.load(f)
allowed = {"buildLogic"}
if include_published: allowed.add("publishedGradlePlugin")
for ss in data.get("sourceSets", []):
    if ss.get("kind") not in allowed:
        continue
    for d in ss.get("srcDirs", []):
        print(f"\"{d}\"")
' "$TARGET/.rewrite-manifest.json" "$INCLUDE_PUBLISHED")

# 3b — loose *.gradle[.kts] files at project roots (e.g. documentation/documentation.gradle.kts)
while IFS= read -r proj; do
    proj="${proj%\"*}"; proj="${proj#*\"}"
    [ -d "$proj" ] || continue
    # Find script files at the project's own root only, not deeply nested.
    for f in "$proj"/*.gradle.kts "$proj"/*.gradle; do
        [ -f "$f" ] || continue
        SCRIPT_FILES+=("$f")
    done
done < <(python3 -c '
import json, sys
with open(sys.argv[1]) as f:
    data = json.load(f)
for p in data.get("projectRoots", []):
    print(f"\"{p}\"")
' "$TARGET/.rewrite-manifest.json")

# 3c — nested builds (buildSrc + explicit includeBuild). Walk their entire project dir so we pick up
# both their build scripts AND their Java/Kotlin build-logic source files.
while IFS= read -r nestedPath; do
    nestedPath="${nestedPath%\"*}"; nestedPath="${nestedPath#*\"}"
    [ -d "$nestedPath" ] || continue
    SRC_DIRS+=("$nestedPath")
done < <(python3 -c '
import json, sys
with open(sys.argv[1]) as f:
    data = json.load(f)
for nb in data.get("nestedBuilds", []):
    p = nb.get("path")
    if p:
        print(f"\"{p}\"")
' "$TARGET/.rewrite-manifest.json")

if [ "${#SRC_DIRS[@]}" -eq 0 ] && [ "${#SCRIPT_FILES[@]}" -eq 0 ]; then
    echo "[run-migration] no build-logic sources found"
    exit 0
fi

echo "[run-migration] build-logic dirs: ${#SRC_DIRS[@]}, scripts: ${#SCRIPT_FILES[@]}"
printf '  dir:    %s\n' "${SRC_DIRS[@]}"
printf '  script: %s\n' "${SCRIPT_FILES[@]}"

# 3d — shared classpath from the Gradle distribution's lib/ jars. Supplies org.gradle.* types so
# Spring-style Java buildSrc parses to real LSTs (otherwise the Java parser produces ParseErrors
# that block every recipe).
CLASSPATH_ENTRIES=$(python3 -c '
import json, sys
with open(sys.argv[1]) as f:
    data = json.load(f)
seen = set()
for c in data.get("gradleApi", []):
    if c not in seen:
        seen.add(c)
        print(c)
for ss in data.get("sourceSets", []):
    for c in ss.get("classpath", []):
        if c not in seen:
            seen.add(c)
            print(c)
' "$TARGET/.rewrite-manifest.json" | tr '\n' ':' | sed 's/:$//')

# --- 4. invoke runner
RUNNER_ARGS=()
for d in "${SRC_DIRS[@]}"; do
    RUNNER_ARGS+=("--src-dir" "$d")
done
for f in "${SCRIPT_FILES[@]}"; do
    RUNNER_ARGS+=("--script-file" "$f")
done
if [ -n "$CLASSPATH_ENTRIES" ]; then
    RUNNER_ARGS+=("--classpath" "$CLASSPATH_ENTRIES")
fi

# Default mode is dry-run; pass --apply through.
APPLY_FLAG=""
VERBOSE_FLAG=""
for a in "${EXTRA_ARGS[@]}"; do
    case "$a" in
        --apply) APPLY_FLAG="--apply" ;;
        --verbose|-v) VERBOSE_FLAG="--verbose" ;;
    esac
done

echo "[run-migration] running recipe..."
"$LAUNCHER" "${RUNNER_ARGS[@]}" $APPLY_FLAG $VERBOSE_FLAG

echo "[run-migration] done"
