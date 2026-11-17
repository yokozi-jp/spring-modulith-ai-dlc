#!/usr/bin/env bash
# test.sh: 小さなスキルの木を作って skills-check.mjs の終了コードとメッセージを確かめる。
# Run: bash tooling/skills-check/test.sh (task skills-check が本検査の前に実行する)

set -u

dir="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
out="$(mktemp)"
trap 'rm -rf "$tmp" "$out"' EXIT
failures=0
commit=0123456789abcdef0123456789abcdef01234567

# content_hash <パス>: kiro/install.sh の content_hash と同じ手順（バイト順に並べたパスと内容のハッシュ）。
# 下の木のファイル名では、skills CLI の computedHash（localeCompare の順）とも同じ値になる。
content_hash() (
  if [ -d "$1" ]; then
    cd "$1" && find . -type f | sed 's#^\./##' | LC_ALL=C sort | while IFS= read -r f; do printf '%s' "$f"; cat -- "$f"; done
  else
    cat -- "$1"
  fi | sha256sum | cut -d' ' -f1
)

# lock_set <名前> <スキル> <JSON>: skills-lock.json のそのスキルの項目に JSON のフィールドを重ねる。値が null のフィールドは消す。
lock_set() {
  node -e '
    const fs = require("fs");
    const [file, skill, fields] = process.argv.slice(1);
    const lock = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, "utf8")) : { version: 1, skills: {} };
    const entry = { ...lock.skills[skill], ...JSON.parse(fields) };
    for (const key of Object.keys(entry)) if (entry[key] === null) delete entry[key];
    lock.skills[skill] = entry;
    fs.writeFileSync(file, JSON.stringify(lock, null, 2) + "\n");
  ' "$tmp/$1/skills-lock.json" "$2" "$3"
}

# rehash <名前> <スキル>: lock の computedHash を今の内容に合わせる
rehash() {
  lock_set "$1" "$2" "{\"computedHash\":\"$(content_hash "$tmp/$1/.agents/skills/$2")\"}"
}

# skill_md <名前> <スキル> <SKILL.md の内容>: SKILL.md を書き換えて computedHash を合わせる
skill_md() {
  printf '%b' "$3" >"$tmp/$1/.agents/skills/$2/SKILL.md"
  rehash "$1" "$2"
}

# tree <名前>: 正常な2スキル（foo、bar）の木を $tmp/<名前> に作る
tree() {
  local t="$tmp/$1" name
  for name in foo bar; do
    mkdir -p "$t/.agents/skills/$name" "$t/.kiro/skills"
    printf -- '---\nname: %s\ndescription: d\n---\n' "$name" >"$t/.agents/skills/$name/SKILL.md"
    ln -s "../../.agents/skills/$name" "$t/.kiro/skills/$name"
    lock_set "$1" "$name" '{"source":"o/r","ref":"aaa"}'
    rehash "$1" "$name"
  done
}

# expect <名前> <終了コード> <メッセージの断片>
expect() {
  local code
  node "$dir/skills-check.mjs" "$tmp/$1" >"$out" 2>&1
  code=$?
  if [ "$code" != "$2" ] || ! grep -qF -- "$3" "$out"; then
    echo "FAIL: $1 (exit $code, want $2; fragment '$3')"
    sed 's/^/    /' "$out"
    failures=$((failures + 1))
  fi
}

tree ok
expect ok 0 "skills-check OK: 2 スキル"

tree link-copy
rm "$tmp/link-copy/.kiro/skills/foo" && cp -r "$tmp/link-copy/.agents/skills/foo" "$tmp/link-copy/.kiro/skills/foo"
expect link-copy 1 "foo: .kiro/skills/foo が ../../.agents/skills/foo へのシンボリックリンクではありません"

tree name-mismatch
skill_md name-mismatch foo '---\nname: other\ndescription: d\n---\n'
expect name-mismatch 1 "foo: SKILL.md の name がディレクトリ名と一致しません"

tree hash-mismatch
echo edited >>"$tmp/hash-mismatch/.agents/skills/foo/SKILL.md"
expect hash-mismatch 1 "foo: 内容ハッシュ $(content_hash "$tmp/hash-mismatch/.agents/skills/foo") が skills-lock.json の computedHash と一致しません"

tree noted-stale
lock_set noted-stale foo '{"note":"modified"}'
echo edited >>"$tmp/noted-stale/.agents/skills/foo/SKILL.md"
expect noted-stale 1 "foo: 内容ハッシュ"

tree noted-rehashed
lock_set noted-rehashed foo '{"note":"modified"}'
echo edited >>"$tmp/noted-rehashed/.agents/skills/foo/SKILL.md"
rehash noted-rehashed foo
expect noted-rehashed 0 "skills-check OK"

tree frontmatter-split
skill_md frontmatter-split foo '---\nname: foo\ndescription: d。\n続き。\n---\n'
expect frontmatter-split 1 "foo: SKILL.md のフロントマターの 4 行目が「キー:」、字下げ、「- 」、「#」のどれでも始まりません"

tree frontmatter-unclosed
skill_md frontmatter-unclosed foo '---\nname: foo\ndescription: d\n'
expect frontmatter-unclosed 1 "foo: SKILL.md のフロントマターが --- で閉じていません"

tree frontmatter-no-description
skill_md frontmatter-no-description foo '---\nname: foo\n---\n'
expect frontmatter-no-description 1 "foo: SKILL.md のフロントマターに description がありません"

tree frontmatter-yaml
skill_md frontmatter-yaml foo '---\n# comment\nname: foo\ndescription: d。\n  続き。\nmetadata.version: "1"\nallowed-tools:\n- Bash\n---\n'
expect frontmatter-yaml 0 "skills-check OK"

tree local-ok
lock_set local-ok foo '{"source":"./local","sourceType":"local","ref":null,"note":"authored"}'
expect local-ok 0 "skills-check OK"

tree local-no-note
lock_set local-no-note foo '{"source":"local","sourceType":"local","ref":null}'
expect local-no-note 1 "foo: 取得元のないスキル（sourceType が local）には note が必要です"

tree unlocked
mkdir "$tmp/unlocked/.agents/skills/extra"
expect unlocked 1 ".agents/skills/extra が skills-lock.json にありません"

tree ref-split
lock_set ref-split bar '{"ref":"bbb"}'
expect ref-split 1 "o/r: ref が複数あります（aaa, bbb）"

# record <名前> <.kiro からのパス>: 記録にそのパスと現在のハッシュを足す
record() {
  printf '%s\t%s\n' "$2" "$(content_hash "$tmp/$1/.kiro/$2")" >>"$tmp/$1/.kiro/.kiro-plugins-installed"
}

# plugin <名前>: 正常な木に、kiro/install.sh が入れた形のスキル baz を足す
plugin() {
  local t="$tmp/$1"
  tree "$1"
  mkdir -p "$t/.kiro/skills/baz"
  printf -- '---\nname: baz\ndescription: d\n---\n' >"$t/.kiro/skills/baz/SKILL.md"
  printf '# cursor-plugins %s\n' "$commit" >"$t/.kiro/.kiro-plugins-installed"
  record "$1" skills/baz
}

plugin plugin-ok
expect plugin-ok 0 "skills-check OK: 2 スキル、kiro/install.sh で入れた 1 スキル"

plugin plugin-edited
echo edited >>"$tmp/plugin-edited/.kiro/skills/baz/SKILL.md"
expect plugin-edited 1 ".kiro/skills/baz: 内容ハッシュが .kiro/.kiro-plugins-installed と一致しません"

plugin plugin-no-hash
printf '# cursor-plugins %s\nskills/baz\n' "$commit" >"$tmp/plugin-no-hash/.kiro/.kiro-plugins-installed"
expect plugin-no-hash 1 ".kiro/skills/baz: .kiro/.kiro-plugins-installed に内容ハッシュがありません"

plugin plugin-missing
rm -r "$tmp/plugin-missing/.kiro/skills/baz"
expect plugin-missing 1 ".kiro/skills/baz: .kiro/.kiro-plugins-installed にありますが、ファイルがありません"

plugin plugin-both
printf 'skills/foo\tx\n' >>"$tmp/plugin-both/.kiro/.kiro-plugins-installed"
expect plugin-both 1 "foo: skills-lock.json と .kiro/.kiro-plugins-installed の両方にあります"

plugin plugin-duplicate
record plugin-duplicate skills/baz
expect plugin-duplicate 1 ".kiro/.kiro-plugins-installed の 3 行目: skills/baz が重複しています"

plugin plugin-no-commit
sed -i 1d "$tmp/plugin-no-commit/.kiro/.kiro-plugins-installed"
expect plugin-no-commit 1 ".kiro/.kiro-plugins-installed の1行目は「# cursor-plugins <40 桁のコミット SHA>」でなければなりません"

plugin plugin-commit-not-first
manifest="$tmp/plugin-commit-not-first/.kiro/.kiro-plugins-installed"
{ sed 1d "$manifest"; sed -n 1p "$manifest"; } >"$manifest.new" && mv "$manifest.new" "$manifest"
expect plugin-commit-not-first 1 ".kiro/.kiro-plugins-installed の1行目は「# cursor-plugins <40 桁のコミット SHA>」でなければなりません"
expect plugin-commit-not-first 1 ".kiro/.kiro-plugins-installed の 2 行目: # で始まる行は1行目の取得元コミットだけに置けます"

plugin plugin-commit-short
sed -i '1s/.*/# cursor-plugins 0123abc/' "$tmp/plugin-commit-short/.kiro/.kiro-plugins-installed"
expect plugin-commit-short 1 ".kiro/.kiro-plugins-installed の1行目は「# cursor-plugins <40 桁のコミット SHA>」でなければなりません"

plugin plugin-comment
printf '# note\n' >>"$tmp/plugin-comment/.kiro/.kiro-plugins-installed"
expect plugin-comment 1 ".kiro/.kiro-plugins-installed の 3 行目: # で始まる行は1行目の取得元コミットだけに置けます"

plugin plugin-dirty
sed -i "1s/.*/# cursor-plugins $commit-dirty/" "$tmp/plugin-dirty/.kiro/.kiro-plugins-installed"
expect plugin-dirty 1 "取得元コミット $commit-dirty は未コミットの変更を含みます"

plugin plugin-outside
mkdir "$tmp/plugin-outside/tooling" && echo x >"$tmp/plugin-outside/tooling/a"
record plugin-outside ../tooling
expect plugin-outside 1 "../tooling は skills、agents、steering の直下の要素でも kiro-permissions.yaml でもありません"

plugin plugin-dotdot
printf 'skills/..\tx\n' >>"$tmp/plugin-dotdot/.kiro/.kiro-plugins-installed"
expect plugin-dotdot 1 "skills/.. は skills、agents、steering の直下の要素でも kiro-permissions.yaml でもありません"

plugin plugin-dot
mkdir "$tmp/plugin-dot/.kiro/agents" && echo ok >"$tmp/plugin-dot/.kiro/agents/x.md"
record plugin-dot agents/x.md
record plugin-dot agents/.
expect plugin-dot 1 "agents/. は skills、agents、steering の直下の要素でも kiro-permissions.yaml でもありません"

plugin plugin-node-modules
mkdir "$tmp/plugin-node-modules/.kiro/skills/baz/node_modules" && echo x >"$tmp/plugin-node-modules/.kiro/skills/baz/node_modules/m.js"
expect plugin-node-modules 0 "skills-check OK"

plugin plugin-symlink
ln -s /etc/hostname "$tmp/plugin-symlink/.kiro/skills/baz/link"
expect plugin-symlink 1 ".kiro/skills/baz/link: シンボリックリンクは認められません"

plugin plugin-byte-order
printf b >"$tmp/plugin-byte-order/.kiro/skills/baz/B.md" && printf a >"$tmp/plugin-byte-order/.kiro/skills/baz/a.md"
sed -i '/^skills\/baz/d' "$tmp/plugin-byte-order/.kiro/.kiro-plugins-installed"
record plugin-byte-order skills/baz
expect plugin-byte-order 0 "skills-check OK"

plugin plugin-agent-file
mkdir "$tmp/plugin-agent-file/.kiro/agents" && echo ok >"$tmp/plugin-agent-file/.kiro/agents/x.md"
record plugin-agent-file agents/x.md
expect plugin-agent-file 0 "skills-check OK"
echo edited >>"$tmp/plugin-agent-file/.kiro/agents/x.md"
expect plugin-agent-file 1 ".kiro/agents/x.md: 内容ハッシュが .kiro/.kiro-plugins-installed と一致しません"

plugin plugin-agent-unlisted
mkdir "$tmp/plugin-agent-unlisted/.kiro/agents" && echo ok >"$tmp/plugin-agent-unlisted/.kiro/agents/x.md"
expect plugin-agent-unlisted 1 ".kiro/agents/x.md が .kiro/.kiro-plugins-installed にありません"

plugin plugin-steering-unlisted
mkdir "$tmp/plugin-steering-unlisted/.kiro/steering" && echo ok >"$tmp/plugin-steering-unlisted/.kiro/steering/baz.md"
expect plugin-steering-unlisted 1 ".kiro/steering/baz.md が .kiro/.kiro-plugins-installed にありません"

plugin plugin-steering-repo
mkdir "$tmp/plugin-steering-repo/.kiro/steering" && echo ok >"$tmp/plugin-steering-repo/.kiro/steering/backend.md"
expect plugin-steering-repo 0 "skills-check OK"

plugin plugin-permissions
echo allow >"$tmp/plugin-permissions/.kiro/kiro-permissions.yaml"
record plugin-permissions kiro-permissions.yaml
expect plugin-permissions 0 "skills-check OK"
echo edited >>"$tmp/plugin-permissions/.kiro/kiro-permissions.yaml"
expect plugin-permissions 1 ".kiro/kiro-permissions.yaml: 内容ハッシュが .kiro/.kiro-plugins-installed と一致しません"

plugin plugin-permissions-unlisted
echo allow >"$tmp/plugin-permissions-unlisted/.kiro/kiro-permissions.yaml"
expect plugin-permissions-unlisted 1 ".kiro/kiro-permissions.yaml が .kiro/.kiro-plugins-installed にありません"

plugin plugin-unknown-dir
mkdir "$tmp/plugin-unknown-dir/.kiro/skills/extra"
expect plugin-unknown-dir 1 ".kiro/skills/extra が skills-lock.json にも .kiro/.kiro-plugins-installed にもありません"

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
