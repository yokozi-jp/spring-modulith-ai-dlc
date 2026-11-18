---
type: ADR
title: 'ADR-090: Task、betterleaks、iwe の版を mise.toml と mise.lock に固定し、ローカルと CI で同じ lock から入れる'
description: Task、betterleaks、iwe の版を mise.toml に書き、ローカルと CI が mise.lock の版と checksum から入れ、PATH を mise の shims で通す判断。版を固定する CLI ツールを足すとき、mise の設定や CI での入れ方を変えるときに読む。
tags: [adr, tooling, mise, ci, security]
---

# ADR-090: Task、betterleaks、iwe の版を mise.toml と mise.lock に固定し、ローカルと CI で同じ lock から入れる

## Status

Proposed

## Date

2026-11-18

## Context

Task、betterleaks、iwe は、版の置き場所と入れ方が道具ごとに違っていた。
Task は `docs/local-env-setup/versions.env` に版と sha256 を書き、`00-setup-task.sh` が GitHub Releases から入れていた。
CI は `go-task/setup-task` を 13 本の workflow の 17 か所で使い、同じ版と checksum をそれぞれに書いていた。
betterleaks は `05-setup-go-betterleaks.sh` が Go を入れてから `go install` で入れ、CI も `actions/setup-go` と `go install` で入れていた。
Go はこのためだけに入れていた。
iwe は `06-setup-iwe.sh` が `npm install -g` で入れ、CI は `versions.env` を `grep` して版を読んでいた。

このため、版を上げるたびに `versions.env` と CI の複製を手で揃える必要があった。
また、betterleaks が公開している cosign の署名を検証していなかった。

[#254](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/254) の調査で、`yokozi-jp/agent-software-factory` の [ADR 0009](https://github.com/yokozi-jp/agent-software-factory/blob/main/docs/adr/0009-mise-for-pinned-cli-tools.md) と同じく、mise の `mise.toml` と `mise.lock` に寄せると決めた。
[ADR-010](ADR-010-adopt-task-as-project-task-runner.md) は Task の版を固定してローカルと CI で同じ版を使うと決めており、この ADR はその版を置く場所と入れ方を変える。

Proposed の間に Decision を一度書き直した。
最初の案は CI の各 step に `jdx/mise-action` と mise の版を書いていたので、Task の版の複製が mise の版の複製に移っただけだった。
また、lock を強制するのは CI の `MISE_LOCKED=1` だけで、ローカルの shims は lock に無い版を入れて `mise.lock` を書き換えられた。
このため、CI の設定を composite action へまとめ、lock の強制を `mise.toml` へ移した。

## Decision

- `mise.toml` の `[tools]` に `task`、`betterleaks`、`npm:@iwe-org/iwe` を exact 版で書き、`mise lock --platform linux-x64` で `mise.lock` に URL と checksum を記録する。
- npm backend の iwe について mise が作る `.mise/locks/npm-iwe-org-iwe/<版>/`（依存の integrity を持つ lockfile）も commit する。
- `mise.toml` の `[settings]` に `locked = true` を書き、ローカルでも CI でも lock に無い版を入れさせない。
- `mise.toml` には、この2つのほかに何も書かない。タスク（Task の役割）、環境変数、Node.js と pnpm（Vite+ の役割）、Java は書かない。
- ローカルでは apt で入れた mise が `mise install` を実行する。
- PATH は mise の shims で通す。`04-setup-shell.sh` が `~/.bashrc` に `eval "$(mise activate bash --shims)"` を足す。Taskfile と Lefthook は `betterleaks` と `iwe` をそのまま呼ぶ。
- CI は composite action `.github/actions/setup-mise` を使う。この action だけが `jdx/mise-action` の SHA と mise の版を持ち、入力 `tools` でジョブが使う道具だけを入れる。
- CI ではキャッシュを使わない。復元したキャッシュは `mise.lock` と照合されないためである。
- `Taskfile.yml` を paths 条件に入れる workflow には、`mise.toml`、`mise.lock`、`.github/actions/setup-mise/` も入れる。以前は Task の版を上げると各 workflow 自身が変わって走ったが、今は `mise.toml` と `mise.lock` しか変わらないためである。
- 番号付きのセットアップスクリプトのうち `00-setup-task.sh`、`05-setup-go-betterleaks.sh`、`06-setup-iwe.sh` と `versions.env` を削除する。

`mise activate` ではなく shims を選んだ理由は、PATH が作業ディレクトリに左右されないことである。
`mise activate` はプロンプトのたびに、そのときのディレクトリの `mise.toml` に合わせて PATH を書き換える。
そのため、リポジトリの外のシェルから起動したエディタや Kiro は、リポジトリの道具の PATH を受け継がない。
shims は決まったディレクトリなので、受け継いだ PATH のまま、実行するときのディレクトリの `mise.toml` から版を選ぶ。
どちらも `~/.bashrc` から通すので、対話シェルを経ずに起動したプロセスに PATH が通らない点は同じである。

## Consequences

### Positive

- 版を上げる作業は、`mise.toml` を書き換えて `mise lock` を実行し、`mise.lock` と `.mise/locks/` を commit するだけになる。CI の 17 か所にあった Task の版と checksum の複製がなくなり、mise の版と `jdx/mise-action` の SHA は composite action の1か所にまとまる。
- `mise lock` は betterleaks の配布物を cosign の署名で検証してから sha256 を `mise.lock` に記録する。`mise install` は Task と betterleaks を `mise.lock` の sha256、iwe を `.mise/locks/` の npm の integrity と照合して入れる。`mise.lock` の Task の sha256 は、`versions.env` に書いていた値と同じである。
- shims が lock に無い版を黙って入れることがなくなる。`mise.toml` の版だけを上げて lock を作り直さないと、`task` は `task@<版> is not in the lockfile` で止まる。
- Go を入れる必要がなくなる。

### Negative

- 開発者は mise を入れ、shims を PATH に通す必要がある。shims が PATH にないと `task` が見つからない。
- shims は `mise.toml` のあるチェックアウトの中でだけ道具を選べる。この変更より前に切ったブランチや worktree では、古い `task` を消すと Git フックが動かなくなる。
- iwe は実行に Node.js を要するが、mise は Node.js を入れない。ローカルでは Vite+、CI では `actions/setup-node` が入れる。
- `.mise/locks/` が欠けると、`mise install` は iwe の install で失敗する（mise のエラーは `mise lock` での修復を案内する）。
- Dependabot は `mise.toml` と composite action の `version` を更新しない。[#389](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/389) で Renovate を入れるまで手で上げる。`jdx/mise-action` の SHA は、`dependabot.yml` の `directories` に composite action を足して Dependabot に更新させる。

### Neutral

- `mise.lock` は `linux-x64` だけを持つ。開発環境は WSL、CI は `ubuntu-latest` である。ほかの platform で開発する人が来たら `mise lock --platform` に足す。
- CI の mise の版とローカルの apt の mise の版はずれうる。mise 2026.11.0 と 2026.11.9 で同じ `mise.lock` と `.mise/locks/` が作られ、どちらでも `mise install --locked` が通ることを確かめた。
- Java（apt の Corretto）、Vite+、Kiro CLI はこの ADR の対象外であり、番号付きのスクリプトに残る。Kiro CLI は [#387](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/387) で扱う。
- [ADR-010](ADR-010-adopt-task-as-project-task-runner.md) の「ローカル導入スクリプトと CI で同じバージョンを使う」は、この ADR を Accepted にするときに、この ADR を指す記述へ直す。

## Alternatives Considered

### 選択肢1: versions.env とスクリプトを保つ

- **Description**：現状のまま、版を `versions.env` に書き、スクリプトと CI の step で入れる。
- **Pros**：新しい道具を入れずに済む。
- **Cons**：版と checksum の複製と、betterleaks の署名を検証しない状態が残る。

### 選択肢2: mise activate で PATH を通す

- **Description**：`~/.bashrc` に `eval "$(mise activate bash)"` を書く。
- **Pros**：リポジトリの外では道具が PATH から外れ、mise の対話シェル向けの既定の使い方に沿う。
- **Cons**：PATH がプロンプトのときのディレクトリで決まるので、リポジトリの外のシェルから起動したエディタや Kiro が道具を見つけられない。

### 選択肢3: Taskfile とフックから mise exec で呼ぶ

- **Description**：`agent-software-factory` と同じく、スクリプトが `mise exec --` で道具を呼び、PATH に頼らない。
- **Pros**：PATH の設定に依存しない。
- **Cons**：`task` は人が直接打つ入口なので、結局 shims か activate が要る。呼び出しのたびに `mise exec --` を書くことになる。

### 選択肢4: 各 workflow の step に jdx/mise-action と MISE_LOCKED=1 を書く

- **Description**：composite action を作らず、17 か所の step にそれぞれ mise の版、`MISE_LOCKED=1`、`cache: false` を書く。
- **Pros**：composite action のための Dependabot と lint の設定が要らない。
- **Cons**：mise の版を上げるたびに 17 か所を手で直すことになる。`MISE_LOCKED=1` は書いた step にしか効かず、ローカルでは lock を強制しない。

## References

- [#385](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/385)：この判断を実装する Issue
- [#254](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/254)：mise の採用を決めた調査
- [ADR-010](ADR-010-adopt-task-as-project-task-runner.md)：Task の採用と版の固定
- [mise.lock の docs](https://mise.jdx.dev/dev-tools/mise-lock.html)
- [jdx/mise-action](https://github.com/jdx/mise-action)
