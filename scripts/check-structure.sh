#!/usr/bin/env bash
# The structure rules in CLAUDE.md, checked instead of remembered. verify.sh runs this first; it needs no
# Gradle, no SDK and about a second. One line per problem, nothing on success, non-zero exit if anything failed.
#
#   scripts/check-structure.sh             # all checks
#
# Checked here: the module dependency rule, Kotlin file size, a `//` header line on every file, no inline
# fully-qualified names, no android.util.Log, a README.md per module, and that CLAUDE.md's module table and
# file names match the repo.
#
# Two grandfather lists keep the checks on for new code without a 150-file rewrite:
#   scripts/large-files.txt      - files allowed over the line limit
#   scripts/no-header-files.txt  - files written before the header rule was enforced
# A listed path that no longer exists is not an error (files move and split), so a list only ever shrinks: when
# you next touch one of these files, give it a header or split it and delete its line. Don't add new ones.
set -uo pipefail
cd "$(dirname "$0")/.."

# ---------------------------------------------------------------------------
# Rules: edit these tables, not the code below.
# ---------------------------------------------------------------------------

# Core layers, lowest first. A core module may depend on the ones to its left only.
CORE_LAYERS="common network data domain playback ui"

# Modules the dependency rule skips: :app wires everything together, :core:testing is test-only glue.
DEPS_EXEMPT=":app :core:testing"

# The allowed :domain:* -> :domain:* edges, as "<from> <to>" pairs.
DOMAIN_EDGES=":domain:requests :domain:music"

# Kotlin files in src/main over this many lines fail unless listed in scripts/large-files.txt.
MAX_LINES=400

LARGE_FILES=scripts/large-files.txt
NO_HEADER_FILES=scripts/no-header-files.txt

# ---------------------------------------------------------------------------

problems=0
fail() { printf '  %s\n' "$1"; problems=$((problems + 1)); }

# Every Kotlin file, with build outputs, worktrees and the build-logic build left out; kt_files narrows that to
# the modules' main sources, which is what the per-file rules below are about. One find: on Windows it isn't free.
declare -A kt_names
kt_files=()
while read -r f; do
    kt_names["${f##*/}"]=1
    case "$f" in */src/main/*) kt_files+=("$f") ;; esac
done < <(find . \
    \( -path './.git' -o -path './.claude' -o -path './build-logic' -o -name build \) -prune -o \
    -name '*.kt' -print)

# A list file as a set of paths: '#' comments and blank lines dropped, any leading ./ removed.
read_list() {
    [ -f "$1" ] || return 0
    sed -e 's/#.*//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e 's|^\./||' "$1" | grep -v '^$'
}

declare -A allow_large allow_no_header
while read -r p; do allow_large["$p"]=1; done < <(read_list "$LARGE_FILES")
while read -r p; do allow_no_header["$p"]=1; done < <(read_list "$NO_HEADER_FILES")

# --- One pass over every Kotlin file: size, header line, inline names, android.util.Log --------------------
#
# `// fqn-ok` at the end of a line allows a fully-qualified im.flume.hearth name on it; a comment line that
# starts with `// fqn-ok` allows them anywhere in that file. The escape is for the places where a name is data
# rather than a reference: class names in strings (LegacyWorkers' old worker names), intent extra keys, a
# throwable's own javaClass.name. Code that uses a type still imports it.
if [ "${#kt_files[@]}" -gt 0 ]; then
    while IFS=$'\t' read -r kind file arg1 arg2; do
        rel=${file#./}
        case "$kind" in
            file)
                if [ "$arg1" -gt "$MAX_LINES" ] && [ -z "${allow_large[$rel]:-}" ]; then
                    fail "$rel: $arg1 lines, over $MAX_LINES (split it, or list it in $LARGE_FILES)"
                fi
                if [ "$arg2" = nohdr ] && [ -z "${allow_no_header[$rel]:-}" ]; then
                    fail "$rel:1: no // header line saying what the file holds"
                fi
                ;;
            fqn) fail "$rel:$arg1: inline fully-qualified name; import it (or end the line with // fqn-ok)" ;;
            log) fail "$rel:$arg1: android.util.Log; use logHandledError from :core:common" ;;
        esac
    done < <(awk '
        FNR == 1 { header[FILENAME] = ($0 ~ /^\/\//) ? "hdr" : "nohdr"; order[++n] = FILENAME }
        { lines[FILENAME] = FNR }
        /^[[:space:]]*\/\/[[:space:]]*fqn-ok([[:space:]:-].*)?$/ { whole_file[FILENAME] = 1 }
        /im\.flume\.hearth\./ {
            if ($0 !~ /^[[:space:]]*(package|import)[[:space:]]/ &&
                $0 !~ /^[[:space:]]*(\/\/|\*|\/\*)/ &&
                $0 !~ /\/\/[[:space:]]*fqn-ok/) { hit_file[++h] = FILENAME; hit_line[h] = FNR }
        }
        /android\.util\.Log/ { print "log\t" FILENAME "\t" FNR }
        END {
            for (i = 1; i <= h; i++) if (!(hit_file[i] in whole_file)) print "fqn\t" hit_file[i] "\t" hit_line[i]
            for (i = 1; i <= n; i++) print "file\t" order[i] "\t" lines[order[i]] "\t" header[order[i]]
        }
    ' "${kt_files[@]}")
fi

# --- The dependency rule -----------------------------------------------------------------------------------

# A core module's position in CORE_LAYERS, or 0 for a core module that is not a layer (:core:testing).
layer_rank() {
    local want=$1 i=0 l
    for l in $CORE_LAYERS; do
        i=$((i + 1))
        [ "$l" = "$want" ] && { echo "$i"; return; }
    done
    echo 0
}
domain_layer=$(layer_rank domain)

edge_allowed() {
    local from=$1 to=$2 a b
    # shellcheck disable=SC2086
    set -- $DOMAIN_EDGES
    while [ "$#" -ge 2 ]; do
        a=$1; b=$2; shift 2
        [ "$from" = "$a" ] && [ "$to" = "$b" ] && return 0
    done
    return 1
}

while read -r build; do
    dir=${build%/build.gradle.kts}
    module=":${dir#./}"
    module=${module//\//:}
    case " $DEPS_EXEMPT " in *" $module "*) continue ;; esac

    while IFS='|' read -r line dep; do
        why=""
        if [[ $module == :feature:* && $dep == :feature:* ]]; then
            why="features never depend on each other"
        elif [[ $module == :core:* ]]; then
            mine=$(layer_rank "${module#:core:}")
            if [ "$mine" -gt 0 ]; then
                if [[ $dep == :core:* ]]; then
                    theirs=$(layer_rank "${dep#:core:}")
                    if [ "$theirs" -gt 0 ] && [ "$theirs" -ge "$mine" ]; then
                        why="core modules depend downward only ($CORE_LAYERS)"
                    fi
                elif [[ $dep == :domain:* ]] && [ "$mine" -lt "$domain_layer" ]; then
                    why="a core module below :core:domain can't depend on a domain module"
                fi
            fi
        elif [[ $module == :domain:* ]]; then
            if [ "$dep" = ":core:domain" ]; then
                why=":core:domain is the aggregator above the domain modules"
            elif [[ $dep == :domain:* ]] && ! edge_allowed "$module" "$dep"; then
                why="domain modules don't depend on each other"
            fi
        fi
        [ -n "$why" ] && fail "${build#./}:$line: $module must not depend on $dep ($why)"
    done < <(grep -nE 'project\(":[^"]+"\)' "$build" | sed -E 's/^([0-9]+):.*project\("(:[^"]+)"\).*/\1|\2/')
done < <(find . -maxdepth 3 -name build.gradle.kts -not -path './build-logic/*' -not -path './build.gradle.kts' | sort)

# --- CLAUDE.md still describes the repo --------------------------------------------------------------------
modules=$(grep -oE 'include\("(:[^"]+)"\)' settings.gradle.kts | sed -e 's/include("//' -e 's/")//')
for m in $modules; do
    grep -qF "\`$m\`" CLAUDE.md || fail "CLAUDE.md: the module table has no row for $m"
done

# Only backticked file names (`Something.kt`) are checked, and by base name: CLAUDE.md names classes far more
# often than files and few classes have a file to themselves, so checking every backticked word would be noise
# rather than a stale-doc signal. A name holding a `<placeholder>` is skipped.
while read -r name; do
    case "$name" in *'<'*) continue ;; esac
    base=${name##*/}
    [ -n "${kt_names[$base]:-}" ] || fail "CLAUDE.md: mentions \`$name\`, which is not in the repo any more"
done < <(grep -oE '`[^`]+\.kt`' CLAUDE.md | tr -d '`' | sort -u)

# Every module explains itself, so an agent can read one README instead of the whole map.
for m in $modules; do
    dir=${m#:}
    dir=${dir//:/\/}
    [ -f "$dir/README.md" ] || fail "$dir: no README.md"
done

[ "$problems" = 0 ] && exit 0
exit 1
