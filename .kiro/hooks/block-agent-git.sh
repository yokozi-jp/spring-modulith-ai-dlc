#!/usr/bin/env bash
# block-agent-git.sh: PreToolUse guard that refuses an agent's direct `git commit` and `git push`.
#
# The agent commits and pushes only through `task agent-commit`, `task agent-push`, and
# `task agent-wait` (docs/tooling/dev-workflow.md). Those tasks run Lefthook past the tool
# call's time limit and commit from a copy of the index (ADR-091), which a direct git call skips.
# The tasks run git inside their own scripts, so this hook never sees that git call.
#
# Contract: read the harness PreToolUse JSON on stdin, print a reason to
# stderr and exit 2 to REFUSE, exit 0 to ALLOW.
# Only a shell tool's command string is judged. A `git commit` or `git push` is refused when it
# stands where a command starts: the start of a line, or after `;`, `&`, `|`, `(`, a backtick,
# or `$(`. Environment assignments, `sudo`, `env`, `command`, `time`, `exec`, `nohup`, and git's own
# options (`-C <path>`, `-c <key=value>`, `--no-pager`, `--git-dir=<path>`) may sit in between.
#
# Fail-open: no stdin, invalid JSON, missing jq, or no command ALLOWS.
#
# ponytail: this reads the command string as text, so it is not a shell parser. A line inside a
# heredoc that starts with `git push` is refused, and `bash -c "git push"` or a git call that a
# script makes inside a file passes. The hook steers an agent that follows the docs; it does not
# stop a deliberate bypass. `git commit-tree` and other subcommands pass.

set -u

payload="$(cat 2>/dev/null || true)"
[ -n "$payload" ] || exit 0
command -v jq >/dev/null 2>&1 || exit 0

command_str="$(printf '%s' "$payload" | jq -r '.tool_input.command // .tool_input.arguments.command // empty' 2>/dev/null || true)"
[ -n "$command_str" ] || exit 0

start='(^|[;&|(`]|\$\()[[:space:]]*'
prefix='(([A-Za-z_][A-Za-z0-9_]*=[^[:space:]]*|sudo|env|command|time|exec|nohup)[[:space:]]+)*'
git_options='(([[:space:]]+(-C|-c|--git-dir|--work-tree|--namespace)[[:space:]]+[^[:space:]]+)|([[:space:]]+-[A-Za-z-]+(=[^[:space:]]*)?))*'
subcommand='[[:space:]]+(commit|push)([[:space:]]|$|[;&|)])'

if printf '%s\n' "$command_str" | grep -Eq "${start}${prefix}git${git_options}${subcommand}"; then
  {
    echo "block-agent-git: refused. Do not run \`git commit\` or \`git push\` directly. Commit and push with the Tasks that wait for the hooks (docs/tooling/dev-workflow.md):"
    echo "  task agent-commit -- <message file> <path>... && task agent-wait"
    echo "  task agent-push && task agent-wait"
    echo "In Kiro Web, run \`source docs/local-env-setup/kiro-web.sh\` first and put \`AGENT_GIT_WAIT_SECONDS=1500\` before \`task agent-wait\`."
  } 1>&2
  exit 2
fi

exit 0
