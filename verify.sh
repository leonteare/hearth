#!/usr/bin/env bash
# Compile and run the unit tests, then print one PASS/FAIL line (with the failing tests' names).
# Exit code is non-zero on any failure. release.sh runs this before it builds.
#
#   ./verify.sh                 # compileDebugKotlin + testDebugUnitTest (screenshot tests compare with their references)
#   ./verify.sh --tests 'im.flume.hearth.playback.QueuePlannerTest'   # extra args go to Gradle
set -uo pipefail
cd "$(dirname "$0")"

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) windows=1 ;;
    *) windows=0 ;;
esac

# Android Studio's bundled JDK, when nothing else is set.
if [ -z "${JAVA_HOME:-}" ] && [ "$windows" = 1 ] && [ -d "/c/Program Files/Android/Android Studio/jbr" ]; then
    export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
fi

# local.properties is git-ignored, so worktrees don't have it: point Gradle at the usual SDK location.
if [ ! -f local.properties ] && [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
    if [ "$windows" = 1 ] && [ -n "${LOCALAPPDATA:-}" ]; then
        export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
    elif [ -d "$HOME/Library/Android/sdk" ]; then
        export ANDROID_HOME="$HOME/Library/Android/sdk"
    else
        export ANDROID_HOME="$HOME/Android/Sdk"
    fi
fi

# Every module's unit test results (app/, core/*/, domain/*/, feature/*/).
results() { find . \( -path './.*' -o -path ./build-logic \) -prune -o -path '*/build/test-results/testDebugUnitTest/*.xml' "$@" -print 2>/dev/null; }
log=$(mktemp)
started=$(mktemp)   # only results newer than this count, so a stale run can't be reported
trap 'rm -f "$log" "$started"' EXIT

./gradlew --console=plain -q compileDebugKotlin testDebugUnitTest "$@" >"$log" 2>&1
status=$?

# Each <testcase> that holds a <failure> or <error> is a failed test.
failed=""
fresh=$(results -newer "$started")
if [ -n "$fresh" ]; then
    # shellcheck disable=SC2086
    failed=$(awk '
        /<testcase / {
            name = $0; sub(/.*<testcase ([^>]* )?name="/, "", name); sub(/".*/, "", name)
            cls = $0; sub(/.*classname="/, "", cls); sub(/".*/, "", cls)
        }
        /<failure|<error/ { print "  " cls " > " name }
    ' $fresh 2>/dev/null | sed "s/&apos;/'/g; s/&quot;/\"/g; s/&lt;/</g; s/&gt;/>/g; s/&amp;/\&/g" | sort -u)
fi

if [ "$status" = 0 ]; then
    # shellcheck disable=SC2046
    total=$(cat $(results) /dev/null | grep -o '<testcase ' | wc -l | tr -d ' ')
    echo "PASS: compiled, $total tests passed"
    exit 0
fi

if [ -n "$failed" ]; then
    echo "FAIL: $(printf '%s\n' "$failed" | wc -l | tr -d ' ') failing test(s):"
    printf '%s\n' "$failed"
    # A screenshot test that no longer matches its reference leaves a comparison image in its module's build output.
    diffs=$(find . \( -path './.*' -o -path ./build-logic \) -prune -o -path '*/build/outputs/roborazzi/*_compare.png' -newer "$started" -print 2>/dev/null)
    if [ -n "$diffs" ]; then
        echo "Screenshots that changed (each image shows reference | new | difference):"
        printf '  %s\n' $diffs
        echo "If the new look is intended, record it: ./gradlew testDebugUnitTest -Proborazzi.test.record=true"
    fi
else
    # No failing tests recorded: the compile (or Gradle itself) failed. Show the errors.
    grep -E '^e: |error:|FAILURE|What went wrong' -A3 "$log" | head -40
    echo "FAIL: build did not compile or Gradle failed (see above)"
fi
exit 1
