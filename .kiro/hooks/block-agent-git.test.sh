#!/usr/bin/env bash
# block-agent-git.test.sh: feeds PreToolUse JSON to the hook and checks the exit code.
# Run: bash .kiro/hooks/block-agent-git.test.sh (task skills-check runs it)

set -u

here="$(cd "$(dirname "$0")" && pwd)"
. "$here/../../tooling/scratch-git/scratch-git.sh" || exit 1
matcher="$(jq -r '.hooks[0].matcher' "$here/block-agent-git.json")"
nojq="$(mktemp -d)"
trap 'rm -rf "$nojq"' EXIT
failures=0

# expect <code> <label> <tool> <command> [PATH]: run the hook with a shell tool's command string.
expect() {
  local want="$1" label="$2" tool="$3" command="$4" path="${5:-$PATH}"
  local input err code
  input="$(jq -n --arg tool "$tool" --arg command "$command" '{tool_name: $tool, tool_input: {command: $command}}')"
  err="$(printf '%s' "$input" | PATH="$path" bash "$here/block-agent-git.sh" 2>&1 >/dev/null)"
  code=$?
  if [ "$code" != "$want" ]; then
    echo "FAIL: $label (exit $code, want $want)"
    failures=$((failures + 1))
  elif [ "$want" = 2 ] && ! { printf '%s' "$err" | grep -q 'task agent-commit' && printf '%s' "$err" | grep -q 'task agent-push'; }; then
    echo "FAIL: $label (stderr lacks the Tasks to use: $err)"
    failures=$((failures + 1))
  elif [ "$want" = 2 ] && [ "$(jq -n --arg tool "$tool" --arg matcher "$matcher" '$tool | test($matcher)')" != true ]; then
    echo "FAIL: $label (the matcher in block-agent-git.json does not match $tool, so Kiro never runs the hook)"
    failures=$((failures + 1))
  fi
}

expect 2 "git commit" execute_bash 'git commit -m x'
expect 2 "git push" execute_bash 'git push'
expect 2 "git push with arguments" execute_bash 'git push -u origin feature/x'
expect 2 "git -C path push" execute_bash 'git -C ../worktree push'
expect 2 "git -c key=value commit" execute_bash 'git -c core.hooksPath=/dev/null commit -m x'
expect 2 "git --no-pager commit" execute_bash 'git --no-pager commit -m x'
expect 2 "git --git-dir=path push" execute_bash 'git --git-dir=.git push'
expect 2 "after cd and &&" execute_bash 'cd repo && git commit -m x'
expect 2 "after a semicolon" execute_bash 'git status; git push'
expect 2 "after a pipe" execute_bash 'echo x | git push'
expect 2 "on a later line" execute_bash $'git status\ngit commit -m x'
expect 2 "environment assignment" execute_bash 'GIT_AUTHOR_NAME=a git commit -m x'
expect 2 "sudo" execute_bash 'sudo git push'
expect 2 "command substitution" execute_bash 'echo $(git push)'
expect 2 "subshell" execute_bash '(git push)'
expect 2 "bash tool name" bash 'git push'
expect 2 "shell tool name" shell 'git push'
expect 2 "executeBash tool name" executeBash 'git push'

expect 0 "task agent-commit" execute_bash 'task agent-commit -- /tmp/message.txt a.txt'
expect 0 "task agent-push" execute_bash 'task agent-push && AGENT_GIT_WAIT_SECONDS=1500 task agent-wait'
expect 0 "git status" execute_bash 'git status'
expect 0 "git diff" execute_bash 'git diff --stat'
expect 0 "git add" execute_bash 'git add frontend/lint/x.js'
expect 0 "git log with a commit in the option" execute_bash 'git log --grep=commit --oneline'
expect 0 "git log of a revision named commit" execute_bash 'git log commit'
expect 0 "git commit-tree" execute_bash 'git commit-tree HEAD^{tree}'
expect 0 "branch named like a subcommand" execute_bash 'git branch push'
expect 0 "text that mentions git push" execute_bash 'echo git push'
expect 0 "quoted argument that mentions git commit" execute_bash 'gh api repos/o/r/pulls/1/comments/2/replies -f body="git commit is blocked"'
# The hook does not read the tool name. The matcher keeps Kiro from running it for a write tool.
if [ "$(jq -n --arg matcher "$matcher" '"fs_write" | test($matcher)')" = true ]; then
  echo "FAIL: the matcher in block-agent-git.json matches fs_write"
  failures=$((failures + 1))
fi
expect 0 "no command" execute_bash ''

printf '%s' '{"tool_name":"execute_bash","tool_input":{"arguments":{"command":"git push"}}}' | bash "$here/block-agent-git.sh" >/dev/null 2>&1
if [ $? != 2 ]; then
  echo "FAIL: arguments.command (exit not 2)"
  failures=$((failures + 1))
fi
printf '' | bash "$here/block-agent-git.sh" >/dev/null 2>&1 || { echo "FAIL: empty stdin"; failures=$((failures + 1)); }
printf '%s' '{not json' | bash "$here/block-agent-git.sh" >/dev/null 2>&1 || { echo "FAIL: invalid json"; failures=$((failures + 1)); }

# PATH without jq: only bash, cat, and grep.
for tool in bash cat grep; do ln -s "$(command -v "$tool")" "$nojq/$tool"; done
expect 0 "missing jq" execute_bash 'git push' "$nojq"

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
