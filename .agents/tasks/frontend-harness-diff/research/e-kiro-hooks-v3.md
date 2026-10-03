# Kiro CLI 3 の hook の書式と C9、C17 の当てはめ

[c-lint-agent.md](c-lint-agent.md) の C9、C10、C17 の節が「要検証」として残した点を、Kiro の公式文書と CLI 2.x の source で補う。
C9 と C17 の推奨案そのものは c-lint-agent.md にあり、ここでは CLI 3（V3 engine）の書式で成り立つかだけを確かめる。
「確認済み」は一次情報かファイルで確かめた事実、「推測」と「要検証」はそれ以外を指す。
Kiro CLI はこの環境に入っておらず、hook を実際に動かしての確認はしていない。

現リポジトリは V3 engine を使う設定である（`.kiro/settings/cli.json` 6 行目の `"chat.agentEngine": "v3"`）。
なお Kiro CLI の版番号は 2.2x のまま進んでおり、「V3」は CLI 2.2x の中で選ぶ engine の名前である（[CLI changelog](https://kiro.dev/changelog/cli/) の 2.26.0 の記事、[Kiro CLI V3](https://kiro.dev/docs/cli/v3/) の Known gaps）。

参照した文書と更新日は次のとおりである。
文書の間で食い違う点は、本文でこの日付を使ってどちらが新しいかを示す。

| 文書 | 更新日 |
| --- | --- |
| [Hooks migration（CLI 3.0）](https://kiro.dev/docs/cli/v3/hooks-migration/) | 2026-08-04 |
| [Hook actions](https://kiro.dev/docs/hooks/actions/) | 2026-08-04 |
| [Best practices](https://kiro.dev/docs/hooks/best-practices/) | 2026-08-04 |
| [Troubleshooting](https://kiro.dev/docs/hooks/troubleshooting/) | 2026-08-04 |
| [What's new in IDE 1.0: Hooks](https://kiro.dev/docs/ide/whats-new-v1/hooks/) | 2026-08-21 |
| [Hooks](https://kiro.dev/docs/hooks/) | 2026-09-30 |
| [Hook types](https://kiro.dev/docs/hooks/types/) | 2026-09-30 |
| [CLI 3.0 New features](https://kiro.dev/docs/cli/v3/new-features/) | 2026-09-30 |
| [Permissions](https://kiro.dev/docs/permissions/) | 2026-10-01 |
| [Kiro CLI V3](https://kiro.dev/docs/cli/v3/) | 2026-10-01 |
| [Agent configuration reference](https://kiro.dev/docs/custom-agents/configuration-reference/) | 2026-10-02 |

Kiro の文書は IDE、CLI、Web の tab に分かれており、CLI の tab は HTML に埋め込まれた本文から読んだ。

## 標準

hook にはベンダーをまたぐ仕様が無い（c-lint-agent.md で確認済み）。
ここでは Kiro の公式文書が定める書式を標準として扱う。

### 配置と schema

hook は `.kiro/hooks/` に置く独立した JSON ファイルで定義する（確認済み、[Hooks](https://kiro.dev/docs/hooks/) の File naming and location）。
ファイル名は任意の `.json` でよく、一つのファイルに複数の hook を並べてよく、session の開始時に自動で有効になる。
利用者全体の hook は `~/.kiro/hooks/` に置ける（[IDE 1.0: Hooks](https://kiro.dev/docs/ide/whats-new-v1/hooks/) の末尾）。

schema の version は `"v1"` だけである（確認済み、Hooks の Field reference）。
各 hook の field は次のとおりである。

| field | 必須 | 内容 |
| --- | --- | --- |
| `name` | 必須 | 識別名 |
| `description` | 任意 | 説明だけで、動作に影響しない |
| `trigger` | 必須 | PascalCase の trigger 名 |
| `matcher` | 任意 | 正規表現。省略すると常に一致する |
| `action.type` | 必須 | `command` か `agent` |
| `action.command` | 条件付き | `type` が `command` のとき必須 |
| `action.prompt` | 条件付き | `type` が `agent` のとき必須 |
| `timeout` | 任意 | 秒。既定は 60、0 で無効 |
| `enabled` | 任意 | `false` で無効にする。既定は `true` |
| `confirm` | 任意 | Stop の command の実行前に利用者へ確認する |

timeout の単位は文書の間で食い違う。
Hook actions の CLI の tab と Troubleshooting（どちらも 2026-08-04）は `timeout_ms`（既定 30,000 ms）と `cache_ttl_seconds` を挙げている。
新しい Hooks（2026-09-30）の Field reference は `timeout`（秒）だけを挙げており、`timeout_ms` と `cache_ttl_seconds` は CLI 2.x の埋め込み形式の field である（[CLI 2.x の docs/hooks.md](https://github.com/aws/amazon-q-developer-cli/blob/main/docs/hooks.md)）。
古い二文書は 2.x の記述が残ったものだと推測する。

### trigger 名

trigger 名は PascalCase で書く（確認済み、Hooks、[Hook types](https://kiro.dev/docs/hooks/types/)）。
CLI 2.x からの対応は次のとおりである（[Hooks migration](https://kiro.dev/docs/cli/v3/hooks-migration/)）。

| CLI 2.x | CLI 3 |
| --- | --- |
| `agentSpawn` | `SessionStart` |
| `userPromptSubmit` | `UserPromptSubmit` |
| `preToolUse` | `PreToolUse` |
| `postToolUse` | `PostToolUse` |
| `stop` | `Stop` |
| `fileEdited` | `PostFileSave` |
| `fileCreated` | `PostFileCreate` |

CLI 3 で増えた trigger は `PostFileDelete`、`PreTaskExec`、`PostTaskExec`、`Manual`、`SessionEnd` である。
`SessionEnd` は migration の表（2026-08-04）に無く、後から CLI V3 だけに足された（Hook types の Session End、CLI changelog 2.25 の「Run a Hook when a V3 session ends」）。
CLI V3 は互換のため `AgentSpawn` と `agentSpawn` も受け付ける（Hook types の Agent Spawn）。

### matcher

matcher は正規表現であり、何に当てるかは trigger で変わる（確認済み、Hooks migration の Matcher semantics by trigger）。

- **PreToolUse、PostToolUse**：tool 名に当てる。
- **PostFileSave、PostFileCreate、PostFileDelete**：file の path に当てる。
- **UserPromptSubmit**：prompt の本文に当てる。
- **SessionStart、Stop、PreTaskExec、PostTaskExec、Manual**：評価せず、常に実行する。

tool 名には、正式名と分類名のどちらも書ける（確認済み、Hook types の Pre Tool Use の CLI の tab）。
CLI の tab の例は、`fs_write` か `write`、`execute_bash` か `shell`、MCP server 単位の `@git`、`@git/status` である。
IDE の tab は、分類名として `read`、`write`、`shell`、`web`、`spec`、`*` と、接頭辞の `@mcp`、`@powers`、`@builtin` を挙げている。
Hooks の CLI の tab には、書込みの前に型検査を走らせる例として `"matcher": "fs_write|str_replace"` がある。
CLI 3.0 の組込み tool の一覧は、書込み tool を `write / fs_write` と書いている（[CLI 3.0 New features](https://kiro.dev/docs/cli/v3/new-features/)）。
組込み tool の reference は、tool 名を `write`、別名を `fs_write` と `fsWrite` としている（[Built-in tools](https://kiro.dev/docs/reference/built-in-tools/)、2026-09-29）。

正規表現が tool 名の全体に一致する必要があるか、部分一致でよいかは、Kiro の文書に書かれていない（要検証）。
分類名（`write` など）を `|` でつないだ正規表現の中に混ぜて使えるかも書かれていない（要検証）。
STDIN に渡る `tool_name` が `write`、`fs_write`、`str_replace` のどれになるかも書かれていない（要検証）。

CLI 2.x の matcher は正規表現ではなかった（確認済み、source）。
2.x の実装は、完全一致を先に見て、`*` か `?` を含むときだけ glob として照合する（[hooks.rs](https://github.com/aws/amazon-q-developer-cli/blob/main/crates/chat-cli/src/cli/chat/cli/hooks.rs) の `hook_matches_tool`、[pattern_matching.rs](https://github.com/aws/amazon-q-developer-cli/blob/main/crates/chat-cli/src/util/pattern_matching.rs) の `matches_any_pattern`）。
Agent configuration reference（2026-10-02）も、埋め込み形式の matcher は簡略名ではなく内部名（`fs_write`、`execute_bash` など）を使うとしている。
Hooks migration（2026-08-04）の「2.x の正規表現はそのまま移せる」という記述は、この source と食い違う。

### STDIN の JSON

command は session の文脈を JSON で STDIN から受け取り、project root で実行される（確認済み、Hooks の How hooks work）。
PreToolUse の field は次のとおりである（Hook types の CLI の tab の例）。

```json
{
  "hook_event_name": "preToolUse",
  "cwd": "/current/working/directory",
  "session_id": "abc123-def456-789",
  "tool_name": "read",
  "tool_input": {
    "operations": [
      { "mode": "Line", "path": "/current/working/directory/docs/hooks.md" }
    ]
  }
}
```

PostToolUse には `tool_response` が加わり、MCP の tool 名は `@postgres/query` のように server 名を含む（Hook types の MCP tool hooks）。
例の `hook_event_name` は 2.x と同じ camelCase であり、`path` は絶対 path である。

書込み tool の path がどの field に入るかは、Kiro の文書に例が無い（要検証）。
CLI 2.x の source では、`tool_input` は tool の引数をそのまま入れたものであり（hooks.rs の 316 から 317 行目付近）、`fs_write` の引数は `command` と `path` を必須とする（[tool_index.json](https://github.com/aws/amazon-q-developer-cli/blob/main/crates/chat-cli/src/cli/chat/tools/tool_index.json)）。
この調査を走らせている Kiro の session でも、`fs_write` と `str_replace` の tool は書込み先を `path` という引数で受け取る。
このため、CLI 3 でも書込み先は `tool_input.path` に入り、値は絶対 path になりうると推測する。

### exit code と block

command action の exit code の意味は次のとおりである（確認済み、Hook actions の CLI の tab）。

- **0**：成功を表す。
  STDOUT は SessionStart と UserPromptSubmit では文脈に入り、それ以外では捨てられる。
- **2**：PreToolUse、UserPromptSubmit、PreTaskExec に限り、実行を止める。
  STDERR が agent に返る。
- **それ以外**：失敗を表す。
  STDERR を利用者に警告として示し、実行は続く。

2 以外の非 0 の扱いは文書の間で食い違う。
Hook actions の IDE の tab（2026-08-04）は、非 0 なら PreToolUse を止めるとしている。
IDE 1.0: Hooks（2026-08-21）は、2 以外の非 0 は block ではなくエラーとして扱うとしている。
新しいほうの IDE 1.0 と CLI の tab が一致しており、exit 2 はどの記述でも block になる。

PreToolUse の `permissionDecision` は、Kiro の文書に無い（確認済み、上記の Kiro の文書すべてを `permissionDecision` と `hookSpecificOutput` で検索して該当なし）。
STDOUT の JSON で allow や deny を返す仕組みは Claude Code のものであり（[Claude Code: Hooks reference](https://code.claude.com/docs/en/hooks)）、Kiro で PreToolUse を止める方法として文書にあるのは exit 2 だけである。

### Stop と SessionStart

SessionStart は block できない（確認済み、Hooks migration と IDE 1.0 の表）。
exit 0 の STDOUT は agent の文脈に入る（Hook actions の CLI の tab）。
[CLI changelog](https://kiro.dev/changelog/cli/) の V3 の修正一覧には「SessionStart の hook の内容は V3 のすべての turn で参照できる」という項目があり、注入した内容は session の間ずっと文脈に残ると読める（推測）。

Stop が block できるかは文書の間で食い違っており、解消できなかった（要検証）。

| 文書 | 更新日 | Stop の block |
| --- | --- | --- |
| Hooks migration | 2026-08-04 | できない（表の Can block? が No） |
| IDE 1.0: Hooks | 2026-08-21 | できない（表の Can block? が No） |
| Hook types の CLI の tab | 2026-09-30 | STDOUT に `{"decision": "block", "reason": "..."}` を返すと、`reason` が新しい利用者メッセージになり会話が続く |

最も新しいのは Hook types である。
ただし同じ節の STDIN の例は 2.x の event 名（`"stop"`）を使っており、2.x の記述が残っている可能性がある（推測）。
Claude Code の `stop_hook_active` のような、連続 block の歯止めは Kiro の文書に無い（確認済み、検索して該当なし）。

Stop の実行頻度は、turn ごとと読むのが妥当である。
Hook types は「agent が turn を終えて応答し終えたとき」とし、Hooks migration は「session が終わるとき」としている。
後から session の終了用に `SessionEnd` が足されたことは、Stop が session 単位ではないことと整合する（推測）。
Hooks（2026-09-30）は、Stop の command の実行前に利用者へ確認を求める `confirm` と、確認を出すかを実行時に決める `confirmCommand` を足している。

### IDE、CLI、Web での共有

`.kiro/hooks/*.json` は IDE、CLI、Web で共有される（確認済み）。

- Hooks の表は、event-driven hooks、shell command action、agent prompt action を IDE、CLI、Web のすべてで ✓ にしている。
- IDE 1.0: Hooks は、この形式を CLI と共有する schema とし、同じ hook file がすべての surface で動くとしている。
- Kiro CLI V3 は、IDE と Web を動かす harness を CLI に統合し、`.kiro` の設定はすべての surface に持ち運べるとしている。
- Web は repository の `.kiro/hooks/` から project の hook を読み、command action は利用者の計算機ではなく cloud sandbox の中で実行する（Hooks の Setting up hooks の Web の tab）。

ただし trigger の対応は surface ごとに違う（Hook types の Availability by surface）。
`SessionEnd` は CLI V3 だけにあり、`Manual` は IDE で新規に作れない。

agent 設定に埋め込む形式（CLI 2.x）の扱いは、文書の間で食い違う。
Hooks migration（2026-08-04）は、埋め込み形式を「3.0 では使わない」と明記している。
新しい Agent configuration reference（2026-10-02）は、`hooks` field を「Unchanged」に挙げ、`/upgrade-agent` がオブジェクト形式を V3 が求める配列形式へ変換するとしている。
同じ文書の中でも、「CLI だけで IDE は無視する」と「CLI と IDE の両方が受け付ける」の二つの記述が並んでいる。
移行の手段も、Hooks は `kiro-cli agent migrate`、Agent configuration reference は `/upgrade-agent` と書いており、名前がそろっていない。

### workspace の信頼と hook の書換え

agent が `.kiro/hooks/` などへ書き込むときは、どの workspace でも承認を求める（確認済み、[Permissions](https://kiro.dev/docs/permissions/) の Workspace trust）。
CLI changelog 2.26 も、V3 の hook の書込みは hook file を書く前に承認を求めると記している。
信頼していない workspace で読み込まないものとして文書が挙げるのは、custom agent、steering、MCP の設定、skill、workflow であり、hook がこの一覧に入るかは書かれていない（要検証）。

## ベストプラクティス

Kiro の best practices は、次のことを勧めている（確認済み、[Best practices](https://kiro.dev/docs/hooks/best-practices/)）。

- 一つの hook には一つの仕事だけを持たせる。
- 対象を狭い pattern に絞り、trigger の頻度と待ち時間に注意する。
- 想定外の入力や壊れた入力を安全に扱えるか試す。
- hook の目的、期待する挙動、制約を文書に残し、hook の設定を version 管理で共有する。
- 決まった処理には command action を使う。
  agent action より速く、credit を消費しない。

STDIN の JSON は `jq` などで field を取り出すよう、公式の例が示している（[Examples](https://kiro.dev/docs/hooks/examples/) の prompt logging の例）。

PreToolUse で止めたいときは exit 2 を返す。
前節のとおり、2 以外の非 0 と STDOUT の JSON は、文書によって block になるかが違うか、そもそも文書に無い。

チームで共有するガードは hook に置く。
workspace 単位の `permissions.yaml` は利用者ごとに repository の外（`~/.kiro/workspace-roots/<hash>/`）に置かれ、clone した repository から規則を注入できない（確認済み、Permissions の Scopes）。
hook の書換えには承認が要るので、agent が自分でガードを外すには利用者の承認を経る必要がある。

matcher に何を書けば何に当たるかは、文書だけでは決まらない（前節の要検証）。
`/hooks` と `/config` で読み込まれた hook を一覧でき、V3 は不正な matcher を読み込み時に警告する（CLI changelog の修正一覧）。
STDIN を file に書き出すだけの hook を一度置いて、実際の `tool_name` と `tool_input` を確かめるのが、文書の空白を埋める最小の手段である（推測、未実施）。

## アンチパターン

- **CLI 2.x の埋め込み形式で書く**：新しい Agent configuration reference は受け付けると読めるが、Hooks migration は使わないよう明記しており、IDE で無視されるとする記述もある。
  旧リポジトリの `.kiro/agents/default.json`（11 から 38 行目）はこの形式である。
- **2.x の matcher に簡略名を書く**：2.x は完全一致か glob で照合し、内部名（`fs_write`）を使う。
  旧リポジトリの `"matcher": "write"`（`default.json` の 20 行目と 27 行目）は `fs_write` に一致せず、PreToolUse と PostToolUse の hook が 2.x で発火していなかった可能性がある（推測、未実行）。
- **exit 1 で止めようとする**：CLI の tab と IDE 1.0 では警告になり、tool は実行される。
- **Stop の block を規約の強制に使う**：block の可否が文書の間で食い違い、連続 block の歯止めも文書に無い。
  旧リポジトリの `frontend-lint-check.sh`（4 から 7 行目）は、Stop は exit code で止められないと注記し、STDOUT で通知する形にしている。
- **SessionStart で大きな文脈を注入する**：注入した内容は毎 turn の文脈に残ると読める（前節）。
- **path を正規化せずに照合する**：例の `path` は絶対 path である。
  旧リポジトリの `frontend-write-guard.sh` は、payload の最初の `"path"` を `grep -oP` で取り出し（11 行目）、`*frontend/src/*` の部分一致で判定している（17 行目）。
  `tool_input` に path が複数あるときや、`frontend/src` を含む別の path では誤判定しうる。
- **Web の cloud sandbox で動かない前提の command を書く**：Web では command が sandbox で走るので、`jq` などの有無は手元と同じとは限らない（推測）。
  fail-open の hook は、道具が無いと黙って素通しになる。

## デファクトスタンダード

Kiro の v1 schema は、Claude Code の hook と同じ trigger 名（PreToolUse など）と、exit 2 で止める約束を持つ（確認済み、Claude Code: Hooks reference）。
違う点は次の三つである。

- Claude Code は、PreToolUse の STDOUT の JSON で `permissionDecision: "deny"` を返して止められる。
  Kiro の文書にこの仕組みは無い。
- Claude Code の matcher は、英数字と `_`、`-`、`|` などだけなら完全一致の列挙として扱い、それ以外の文字を含むと正規表現として部分一致で照合する（Hooks reference の matcher の節）。
  Kiro は照合の範囲を文書にしていない。
- Claude Code の Stop は `stop_hook_active` で連続実行を検知でき、連続の block に上限がある（c-lint-agent.md で確認済み）。
  Kiro の文書にこの仕組みは無い。

Kiro の公式の例で PreToolUse を使うものは、`fs_write|str_replace` に一致したときに `npx tsc --noEmit` を走らせる例（Hooks の CLI の tab）だけであった。
生成物への書込みを path で拒否する Kiro の例は見つからなかった。
現リポジトリの `block-iwe-normalize` は、docs の規約と、それを機械で強制する PreToolUse hook を対で持つ前例である（c-lint-agent.md）。

## 現リポジトリへの当てはめ

### 既存の hook が CLI 3 の書式に合っているか

`block-iwe-normalize` は v1 の書式に合っている（確認済み、`.kiro/hooks/block-iwe-normalize.json` の 1 から 15 行目）。

| 項目 | 現状 | 判定 |
| --- | --- | --- |
| 配置 | `.kiro/hooks/block-iwe-normalize.json` | 合う |
| `version` | `"v1"`（2 行目） | 合う |
| `trigger` | `"PreToolUse"`（6 行目） | 合う |
| `action` | `type: command`、`bash .kiro/hooks/block-iwe-normalize.sh`（9 から 12 行目） | 合う。project root で実行される |
| `timeout` | 無し | 既定の 60 秒になる |
| 止め方 | STDERR に理由を書き exit 2（`block-iwe-normalize.sh` の 46 から 49 行目） | 合う |
| STDIN | `jq` で `.tool_name` と `.tool_input.command` を取り出す（41 から 43 行目） | 2.x の `execute_bash` の引数（`command`）と合う。V3 は要検証 |
| matcher | `execute_bash\|shell\|bash\|iwe.*normalize\|normalize.*iwe`（8 行目） | 要検証 |

matcher には二つの懸念がある（どちらも要検証）。

- 全体一致で照合する実装なら、MCP の tool 名（`@iwe/...` の形）は先頭の `@` のため `iwe.*normalize` に一致せず、MCP 経路を素通しにする。
  部分一致なら一致する。
- IDE の tool 名が `fsWrite` のような camelCase なら（別名の一覧からの推測）、shell の tool 名も CLI と違い、`execute_bash` に一致しない可能性がある。

script 自体は tool 名と command を自分で判定するので、matcher を広げても誤って止めることはない。
懸念を消す最小の変更は、MCP 側を `@iwe` の接頭辞で書くか、正規表現を `.*iwe.*normalize.*` にすることである（推測、動作は未確認）。

hook の記述は `docs/agents/` には無く、`docs/knowledge/documentation-authoring.md` の 126 から 127 行目にある。
そこでは「Kiro CLI 用」と書いているが、同じ file は IDE と Web でも読まれる（前節）。
Web では command が cloud sandbox で走るので、迂回用の `IWE_ALLOW_NORMALIZE=1` を「Kiro を再起動して設定する」手順は CLI と IDE にしか当てはまらない可能性がある（推測）。

### 旧リポジトリの埋め込み形式との違い

| 項目 | 旧リポジトリ（CLI 2.x、`.kiro/agents/default.json`） | CLI 3 の v1 |
| --- | --- | --- |
| 置き場所 | agent 設定の `hooks` field（11 行目） | `.kiro/hooks/*.json` |
| 構造 | trigger 名を key にしたオブジェクトの中の配列 | `hooks` 配列の各要素に `trigger` |
| trigger 名 | `agentSpawn`、`preToolUse`、`postToolUse`、`stop` | `SessionStart`、`PreToolUse`、`PostToolUse`、`Stop` |
| 識別名 | 無し | `name` が必須 |
| command | `command` を直接書く | `action.type` と `action.command` |
| timeout | `timeout_ms`（ミリ秒） | `timeout`（秒） |
| matcher | 完全一致か glob、内部名 | 正規表現、正式名と分類名 |
| 共有範囲 | その agent を使う CLI だけ | IDE、CLI、Web |

### C9 を CLI 3 の書式で書く場合の具体形

c-lint-agent.md の推奨案（生成物への書込みを PreToolUse で拒否し、最終の検出は CI の再生成差分に置く）を、CLI 3 の書式に直すと次の形になる。
実装はしていない。
対象の path は、`frontend/vite.config.ts` 35 行目の `generatedFiles` にある `src/routeTree.gen.ts` と、ADR-032 の 53 から 54 行目が定める `api/generated/` である。

```json
{
  "version": "v1",
  "hooks": [
    {
      "name": "block-generated-writes",
      "trigger": "PreToolUse",
      "description": "Refuses agent writes to generated frontend files (routeTree.gen.ts, src/api/generated/**). Regenerate instead. Fail-open.",
      "matcher": "write|fs_write|str_replace",
      "action": { "type": "command", "command": "bash .kiro/hooks/block-generated-writes.sh" },
      "timeout": 5
    }
  ]
}
```

判定の script は、`block-iwe-normalize.sh` にならって次の筋にする。

```bash
payload="$(cat 2>/dev/null || true)"
[ -n "$payload" ] && command -v jq >/dev/null 2>&1 || exit 0   # fail-open
path="$(printf '%s' "$payload" | jq -r '.tool_input.path // empty')"
cwd="$(printf '%s' "$payload" | jq -r '.cwd // empty')"
rel="${path#"$cwd"/}"                                           # 絶対 path を root 相対へ
case "$rel" in
  frontend/src/routeTree.gen.ts | frontend/src/api/generated/*)
    echo "block-generated-writes: $rel は生成物である。手で編集せず、生成元を直して再生成する。" >&2
    exit 2 ;;
esac
exit 0
```

この形を選ぶ根拠は次のとおりである。

- **別の file にする**：一つの hook に一つの仕事を持たせる（Best practices）。
  `block-iwe-normalize` と matcher も判定も重ならない。
- **matcher を列挙する**：`write` は IDE と CLI の両方の tab に載っている唯一の表記であり、`fs_write|str_replace` は Hooks の CLI の例の表記である。
  分類名を正規表現に混ぜられるか、`str_replace` が `write` の分類に入るかが分からないため、両方を並べる。
  STDIN を書き出す hook で実際の `tool_name` を確かめたら、当たるものだけに減らす。
- **`tool_input.path` を読み、`cwd` で相対にする**：2.x の `fs_write` の引数と、例の path が絶対 path であることからの推測である。
  `./` や `..` を含む path は、この形では正規化しない。
  `realpath -m --relative-to` は GNU coreutils にしかなく、macOS の標準には無い。
- **迂回用の環境変数を持たない**：生成器（TanStack Router の plugin や Orval）は shell の process として書き込み、書込み tool を通らない。
  正規の再生成は hook に止められない。
- **fail-open にする**：取り出しに失敗しても許可し、最終の検出は CI に置く（c-lint-agent.md の推奨案 3）。
  shell の `sed -i` やリダイレクトも書込み tool を通らないので、hook だけでは守りきれない。

### C17 を入れない判断は CLI 3 でも成り立つか

成り立つ。
CLI 3 の仕様を前提にすると、根拠はむしろ強くなる。

- **SessionStart**：block できないことはすべての文書で一致している。
  注入した内容は毎 turn の文脈に残ると読めるので（CLI changelog）、ディレクトリ構成のような大きな内容を入れると文脈の消費が session の間ずっと続く。
  構成は `architecture.md` と ADR-032 がすでに定めている。
- **Stop**：block できるかは文書の間で食い違い、最も新しい Hook types の記述にも 2.x の記述が残っている形跡がある。
  block できない場合、Stop の検査は agent への通知にとどまり、lefthook の `fe-check` や CI より弱い。
  block できる場合、連続 block の歯止めが文書に無く、検査が通らない限り turn が終わらない状態になりうる。
- **頻度**：Stop は turn ごとに走ると読める。
  旧リポジトリのように 15 秒の検査（`default.json` の 35 行目の `timeout_ms: 15000`）を置くと、毎 turn の待ち時間になる。
  `confirm` で実行前に確認を挟めるが、毎 turn の確認の操作が増える。
- **SessionEnd**：session の破棄時に走るので、agent に結果を返して直させる用途には使えない（推測）。

### 推奨案

1. 新しい hook は `.kiro/hooks/*.json` の v1 形式だけで書き、agent 設定への埋め込みは使わない。
   根拠は、IDE、CLI、Web で共有されるのがこの形式だけであること（[IDE 1.0: Hooks](https://kiro.dev/docs/ide/whats-new-v1/hooks/)、[Hooks](https://kiro.dev/docs/hooks/)）と、埋め込み形式の扱いが文書の間で食い違うこと（[Hooks migration](https://kiro.dev/docs/cli/v3/hooks-migration/)、[Agent configuration reference](https://kiro.dev/docs/custom-agents/configuration-reference/)）である。
2. PreToolUse で止めるときは exit 2 だけを使い、`permissionDecision` や exit 1 は使わない。
   根拠は [Hook actions](https://kiro.dev/docs/hooks/actions/) の CLI の tab と IDE 1.0 の Blocking の記述である。
3. C9 は前節の形で入れる。
   入れる前に、STDIN を書き出すだけの hook で、`fs_write` と `str_replace` の `tool_name` と `tool_input.path` を一度確かめる。
   根拠は、書込み tool の path の field が文書に無いこと（Hook types）と、2.x の source の `fs_write` の引数（[tool_index.json](https://github.com/aws/amazon-q-developer-cli/blob/main/crates/chat-cli/src/cli/chat/tools/tool_index.json)）である。
4. 同じ確認で、`block-iwe-normalize` の matcher が MCP の tool 名に一致するかも見る。
   一致しなければ 8 行目の matcher を直す（`.kiro/hooks/block-iwe-normalize.json` の 8 行目、`block-iwe-normalize.sh` の 51 から 59 行目）。
5. `docs/knowledge/documentation-authoring.md` の 127 行目の「Kiro CLI 用」は、IDE と Web でも読まれる事実に合わせて直す。
   直すのは C9 の実装と同じ変更でよい。
6. C17 は入れない。
   根拠は前節の四点である。

### 利用者が決める論点

- 確かめ用の hook（STDIN を書き出すだけ）を、C9 の実装の前に別の作業として走らせるか、C9 の実装の中で済ませるか。
- `block-iwe-normalize` の matcher の確認と修正を、C9 と同じ変更に含めるか、別の変更にするか。
- C9 の hook を Kiro Web でも効かせる必要があるか。
  必要なら、cloud sandbox に `jq` があるかを確かめるか、`jq` に頼らない取り出しにする。
- 生成物の path を、`frontend/vite.config.ts` の `generatedFiles` と hook の script の二か所に持つことを許すか。
  `api/generated/` は Orval の導入時に増えるので、どちらかを正にする必要がある。
- Stop を将来入れる条件として、Kiro の文書で Stop の block の可否と連続 block の歯止めがそろうことを記録しておくか。
