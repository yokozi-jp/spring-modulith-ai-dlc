#!/usr/bin/env bash
# block-iwe-normalize.sh — PreToolUse guard that refuses `iwe normalize`.
#
# `iwe normalize` rewrites every document under docs/ mechanically; the
# `docs/knowledge/documentation-authoring.md` convention forbids it outside a committed,
# opt-in run. This hook is that rule's deterministic twin: it detects the
# two ways normalize can be invoked and refuses both.
#
#   1. CLI path  — a shell tool (execute_bash/shell) whose command string
#                  runs `iwe … normalize`.
#   2. MCP path  — the iwe MCP server's normalize tool (tool name mentions
#                  both `iwe` and `normalize`).
#
# Contract: read the harness PreToolUse JSON on stdin, print a reason to
# stderr and exit 2 to REFUSE, exit 0 to ALLOW.
#
# Escape hatch: IWE_ALLOW_NORMALIZE=1 makes the hook pass through. Commit
# the docs first, then relaunch Kiro with that variable set.
#
# Fail-open: any parse trouble, missing field, or undetermined input
# ALLOWS. We only ever refuse on a positive normalize match, so a broken
# payload never blocks a non-destructive tool call.

set -u

# Opt-in override: pass through untouched.
if [ "${IWE_ALLOW_NORMALIZE:-}" = "1" ]; then
  exit 0
fi

# Read the whole payload once. No stdin (e.g. a TTY) -> nothing to judge.
payload="$(cat 2>/dev/null || true)"
[ -n "$payload" ] || exit 0

tool_name=""
command_str=""

# Precise extraction when jq is present; string-match fallback otherwise.
# Both paths stay fail-open: extraction failure leaves the field empty.
if command -v jq >/dev/null 2>&1; then
  tool_name="$(printf '%s' "$payload" | jq -r '.tool_name // empty' 2>/dev/null || true)"
  command_str="$(printf '%s' "$payload" \
    | jq -r '.tool_input.command // .tool_input.arguments.command // empty' 2>/dev/null || true)"
fi

refuse() {
  echo "block-iwe-normalize: refused. \`iwe normalize\` rewrites every document under docs/ and is blocked by the \`docs/knowledge/documentation-authoring.md\` convention. Commit docs/ first, then relaunch Kiro with IWE_ALLOW_NORMALIZE=1 to run it deliberately." 1>&2
  exit 2
}

# --- MCP path: the iwe server's normalize tool ------------------------------
# Tool names surface as e.g. mcp_iwe_iwe_normalize / iwe___normalize; match a
# name that mentions both iwe and normalize.
if [ -n "$tool_name" ]; then
  lc_tool="$(printf '%s' "$tool_name" | tr '[:upper:]' '[:lower:]')"
  case "$lc_tool" in
    *iwe*normalize* | *normalize*iwe*) refuse ;;
  esac
fi

# --- CLI path: a shell command running `iwe … normalize` --------------------
# Prefer the extracted command; if jq was absent, fall back to the raw
# payload so the detection still holds.
haystack="$command_str"
[ -n "$haystack" ] || haystack="$payload"
lc_hay="$(printf '%s' "$haystack" | tr '[:upper:]' '[:lower:]')"

# Match an `iwe` invocation followed (anywhere after) by the `normalize`
# subcommand as a whole word, tolerating flags/args between them.
case "$lc_hay" in
  *iwe*)
    if printf '%s' "$lc_hay" | grep -Eq '(^|[^[:alnum:]_.-])iwe([[:space:]]+[^[:space:]]+)*[[:space:]]+normalize([^[:alnum:]_-]|$)'; then
      refuse
    fi
    ;;
esac

exit 0
