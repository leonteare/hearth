#!/usr/bin/env bash
# Creates or removes a git worktree for an agent working in parallel.
#
#   scripts/agent-worktree.sh add <name>       # .claude/worktrees/<name> on branch agent/<name>, ready to build
#   scripts/agent-worktree.sh remove <name>    # deletes the folder (even with Windows' long build paths) and branch
#   scripts/agent-worktree.sh prune            # removes every leftover worktree under .claude/worktrees
#
# Worktrees don't get local.properties (it's git-ignored), so `add` copies it in: builds find the SDK.
set -euo pipefail
cd "$(dirname "$0")/.."
root=".claude/worktrees"

remove_dir() {
    local dir="$1"
    [ -d "$dir" ] || return 0
    git worktree unlock "$dir" 2>/dev/null || true
    git worktree remove --force --force "$dir" 2>/dev/null || true
    if [ -d "$dir" ]; then
        # Build outputs nest deeper than Windows' 260-character limit; rd with the \\?\ prefix ignores it.
        if command -v cmd.exe >/dev/null 2>&1; then
            cmd.exe //c "rd /s /q \"\\\\?\\$(cygpath -w "$(pwd)/$dir")\"" >/dev/null 2>&1 || true
        else
            rm -rf "$dir"
        fi
    fi
}

case "${1:-}" in
    add)
        name="${2:?name}"
        git worktree add -q -b "agent/$name" "$root/$name" HEAD
        [ -f local.properties ] && cp local.properties "$root/$name/"
        echo "$root/$name"
        ;;
    remove)
        name="${2:?name}"
        remove_dir "$root/$name"
        git worktree prune
        git branch -q -D "agent/$name" 2>/dev/null || true
        ;;
    prune)
        for dir in "$root"/*/; do [ -d "$dir" ] && remove_dir "${dir%/}"; done
        git worktree prune
        ;;
    *)
        echo "usage: $0 add|remove <name> | prune" >&2; exit 1 ;;
esac
