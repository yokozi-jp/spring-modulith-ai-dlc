#!/usr/bin/env bash
# block-generated-writes.test.sh: feeds PreToolUse JSON to the hook in a temporary repository and checks the exit code.
# Run: bash .kiro/hooks/block-generated-writes.test.sh (task skills-check runs it)

set -u

here="$(cd "$(dirname "$0")" && pwd)"
matcher="$(jq -r '.hooks[0].matcher' "$here/block-generated-writes.json")"
repo="$(mktemp -d)"
nojq="$(mktemp -d)"
trap 'rm -rf "$repo" "$nojq"' EXIT
mkdir -p "$repo/.kiro/hooks"
cp "$here/block-generated-writes.sh" "$repo/.kiro/hooks/"
printf '# cursor-plugins %s\nskills/foo\th\nagents/bar.md\th\nkiro-permissions.yaml\th\n' 0123456789abcdef0123456789abcdef01234567 >"$repo/.kiro/.kiro-plugins-installed"
failures=0

# expect <code> <label> <stdin> [PATH]: run the hook from the repository root, as Kiro does.
expect() {
  local want="$1" label="$2" input="$3" path="${4:-$PATH}"
  local err code tool
  err="$(cd "$repo" && printf '%s' "$input" | PATH="$path" bash .kiro/hooks/block-generated-writes.sh 2>&1 >/dev/null)"
  code=$?
  if [ "$code" != "$want" ]; then
    echo "FAIL: $label (exit $code, want $want)"
    failures=$((failures + 1))
  elif [ "$want" = 2 ] && ! printf '%s' "$err" | grep -qE 'regenerate|reinstall'; then
    echo "FAIL: $label (stderr lacks the steps to regenerate or reinstall: $err)"
    failures=$((failures + 1))
  elif [ "$want" = 2 ]; then
    tool="$(printf '%s' "$input" | jq -r .tool_name)"
    if [ "$(jq -n --arg tool "$tool" --arg matcher "$matcher" '$tool | test($matcher)')" != true ]; then
      echo "FAIL: $label (the matcher in block-generated-writes.json does not match $tool, so Kiro never runs the hook)"
      failures=$((failures + 1))
    fi
  fi
}

expect 2 "fs_write routeTree" '{"tool_name":"fs_write","tool_input":{"path":"frontend/src/routeTree.gen.ts"}}'
expect 2 "absolute path in the repository" "{\"tool_name\":\"fs_write\",\"tool_input\":{\"path\":\"$repo/frontend/src/routeTree.gen.ts\"}}"
expect 2 "dot slash path" '{"tool_name":"fs_write","tool_input":{"path":"./frontend/src/routeTree.gen.ts"}}'
expect 2 "double slash" '{"tool_name":"fs_write","tool_input":{"path":"frontend//src/routeTree.gen.ts"}}'
expect 2 "dot segment" '{"tool_name":"fs_write","tool_input":{"path":"frontend/./src/routeTree.gen.ts"}}'
expect 2 "absolute double slash" "{\"tool_name\":\"fs_write\",\"tool_input\":{\"path\":\"$repo//openapi/openapi.yaml\"}}"
expect 2 "api generated" '{"tool_name":"fs_write","tool_input":{"path":"frontend/src/api/generated/endpoints/order/order.ts"}}'
expect 2 "str_replace path" '{"tool_name":"str_replace","tool_input":{"path":"frontend/src/routeTree.gen.ts"}}'
expect 2 "fsWrite filePath" '{"tool_name":"fsWrite","tool_input":{"filePath":"frontend/src/routeTree.gen.ts"}}'
expect 2 "delete_file targetFile" '{"tool_name":"delete_file","tool_input":{"targetFile":"frontend/src/api/generated/model/order.ts"}}'
expect 2 "write file_path" '{"tool_name":"write","tool_input":{"file_path":"frontend/src/routeTree.gen.ts"}}'
expect 2 "openapi contract" '{"tool_name":"fs_write","tool_input":{"path":"openapi/openapi.yaml"}}'
expect 2 "installed skill file" '{"tool_name":"str_replace","tool_input":{"path":".kiro/skills/foo/SKILL.md"}}'
expect 2 "absolute installed skill file" "{\"tool_name\":\"fs_write\",\"tool_input\":{\"path\":\"$repo/.kiro/skills/foo/SKILL.md\"}}"
expect 2 "installed skill through a dot segment" '{"tool_name":"fs_write","tool_input":{"path":".kiro/./skills/foo/SKILL.md"}}'
expect 2 "installed skill through a double slash" '{"tool_name":"fs_write","tool_input":{"path":".kiro//skills/foo/SKILL.md"}}'
expect 2 "installed agent file" '{"tool_name":"fs_write","tool_input":{"path":".kiro/agents/bar.md"}}'
expect 2 "delete installed skill" '{"tool_name":"delete_file","tool_input":{"path":".kiro/skills/foo"}}'
expect 2 "installed permissions" '{"tool_name":"fs_write","tool_input":{"path":".kiro/kiro-permissions.yaml"}}'
expect 2 "plugin manifest" '{"tool_name":"str_replace","tool_input":{"path":".kiro/.kiro-plugins-installed"}}'
expect 2 "edit_code path" '{"tool_name":"edit_code","tool_input":{"path":"frontend/src/routeTree.gen.ts"}}'
expect 2 "semantic_rename path" '{"tool_name":"semantic_rename","tool_input":{"path":"frontend/src/routeTree.gen.ts","line":0,"character":0,"oldName":"a","newName":"b"}}'
expect 2 "smart_relocate installed source" '{"tool_name":"smart_relocate","tool_input":{"sourcePath":".kiro/skills/foo/SKILL.md","destinationPath":"docs/x.md"}}'
expect 2 "smartRelocate into generated" '{"tool_name":"smartRelocate","tool_input":{"sourcePath":"frontend/src/x.ts","destinationPath":"frontend/src/api/generated/x.ts"}}'
expect 2 "code format_code" '{"tool_name":"code","tool_input":{"operation":"format_code","file_path":".kiro/skills/foo/SKILL.md"}}'
expect 2 "code rename_symbol" '{"tool_name":"code","tool_input":{"operation":"rename_symbol","file_path":"frontend/src/routeTree.gen.ts","row":1,"column":1,"new_name":"x"}}'

expect 0 "ordinary file" '{"tool_name":"fs_write","tool_input":{"path":"frontend/src/routes/index.tsx"}}'
expect 0 "fs_read routeTree" '{"tool_name":"fs_read","tool_input":{"path":"frontend/src/routeTree.gen.ts"}}'
expect 0 "read routeTree" '{"tool_name":"read","tool_input":{"file_path":"frontend/src/routeTree.gen.ts"}}'
expect 0 "spectral fixture" '{"tool_name":"fs_write","tool_input":{"path":"openapi/spectral-fixtures/valid.yaml"}}'
expect 0 "read openapi contract" '{"tool_name":"read_file","tool_input":{"path":"openapi/openapi.yaml"}}'
expect 0 "skill sharing an installed prefix" '{"tool_name":"fs_write","tool_input":{"path":".kiro/skills/foo-local/SKILL.md"}}'
expect 0 "hook script" '{"tool_name":"fs_write","tool_input":{"path":".kiro/hooks/block-generated-writes.sh"}}'
expect 0 "read installed skill" '{"tool_name":"read_file","tool_input":{"path":".kiro/skills/foo/SKILL.md"}}'
expect 0 "user-level kiro" "{\"tool_name\":\"fs_write\",\"tool_input\":{\"path\":\"$HOME/.kiro/skills/foo/SKILL.md\"}}"
expect 0 "another repository's installed skill" '{"tool_name":"fs_write","tool_input":{"path":"/abs/other/.kiro/skills/foo/SKILL.md"}}'
expect 0 "another repository's generated file" '{"tool_name":"fs_write","tool_input":{"path":"/abs/other/frontend/src/routeTree.gen.ts"}}'
expect 0 "nested checkout under the repository root" '{"tool_name":"fs_write","tool_input":{"path":"other/frontend/src/routeTree.gen.ts"}}'
expect 0 "smart_relocate ordinary files" '{"tool_name":"smart_relocate","tool_input":{"sourcePath":"frontend/src/a.ts","destinationPath":"frontend/src/b.ts"}}'
expect 0 "code goto_definition" '{"tool_name":"code","tool_input":{"operation":"goto_definition","file_path":".kiro/skills/foo/SKILL.md","row":1,"column":1}}'
expect 0 "empty stdin" ''
expect 0 "invalid json" '{not json'

# PATH without jq: only bash and cat.
ln -s "$(command -v bash)" "$nojq/bash"
ln -s "$(command -v cat)" "$nojq/cat"
expect 0 "missing jq" '{"tool_name":"fs_write","tool_input":{"path":"frontend/src/routeTree.gen.ts"}}' "$nojq"

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
