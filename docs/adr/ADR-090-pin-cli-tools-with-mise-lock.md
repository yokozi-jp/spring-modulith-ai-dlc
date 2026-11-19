---
type: ADR
title: 'ADR-090: Task、betterleaks、iwe、Java、zizmor、Kiro CLI の版を mise.toml と mise.lock に固定し、ローカルと CI で同じ lock から入れる'
description: Task、betterleaks、iwe、Java、zizmor、Kiro CLI の版を mise.toml に書き、ローカルと CI が mise.lock の版と checksum から入れ、PATH を mise の shims で通す判断。版を固定する CLI ツールを足すとき、mise の設定や CI での入れ方を変えるときに読む。
tags: [adr, tooling, mise, ci, security]
---

# ADR-090: Task、betterleaks、iwe、Java、zizmor、Kiro CLI の版を mise.toml と mise.lock に固定し、ローカルと CI で同じ lock から入れる

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

Proposed の間に Decision を書き直した。
最初の案は CI の各 step に `jdx/mise-action` と mise の版を書いていたので、Task の版の複製が mise の版の複製に移っただけだった。
このため、CI の設定を composite action へまとめた。
lock の強制を `mise.toml` の `[settings] locked = true` へ移す案も試したが、`[tools]` のほかを書いた `mise.toml` は新しいチェックアウトごとに `mise trust` を求め、新しい worktree で shims と Git フックが止まったので採らなかった（選択肢5）。
Kiro CLI を足した後は `mise.toml` が trust を要するので、この理由は残らない。選択肢5を採らない理由は、グローバルの mise の設定に道具を持つ開発者がグローバルの lock も要る点だけになる。

zizmor も Proposed の間に足した。
版が Taskfile の `docker run`（1.29.0）と `zizmor-action`（版の指定なし）でずれ、[#424](https://github.com/yokozi-jp/spring-modulith-ai-dlc/pull/424) では CI の 1.32.0 だけが `self-repository` の alert を出した。

Java も Proposed の間に足した。
最初の案は Java を対象外にしていた。
そのときローカルは `01-setup-java.sh` が apt で `25.0.2` を入れ、CI は 9 か所の `actions/setup-java` が Corretto の `25` の最新を入れていたので、パッチの版がずれていた。
また、TLA+ の検査の道具（[#502](https://github.com/yokozi-jp/spring-modulith-ai-dlc/pull/502)）を CI で動かすのに、`mise.lock` から入る Java が要った。

Kiro CLI も Proposed の間に足した。
[#255](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/255) の調査で採用と決め、作業は [#387](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/387) で行った。
`03-setup-kiro.sh` は公式のインストーラー（`https://cli.kiro.dev/install`）を実行するので、版が開発者ごと、実行ごとに変わっていた。
mise の registry に Kiro CLI はなく（`mise registry` に `kiro` を含む行はない）、mise の http backend で公式の zip を取る。
この backend の `url` と `bin_path` は、`mise.toml` に trust を要させる。
そのため、最初の案にあった「`mise.toml` は trust を要さない」を取り下げた。
これにより、選択肢5を退けた理由のうち trust の費用は、この案でも払うことになった（選択肢5）。
trust を避けて Kiro CLI を固定する案は選択肢7、trust の範囲を広げる案は選択肢6で退けた。

## Decision

- `mise.toml` の `[tools]` に `task`、`betterleaks`、`npm:@iwe-org/iwe`、`java`、`zizmor`、`http:kiro-cli` を exact 版で書き、`mise lock --platform linux-x64` で `mise.lock` に URL と checksum を記録する。
- npm backend の iwe について mise が作る `.mise/locks/npm-iwe-org-iwe/<版>/`（依存の integrity を持つ lockfile）も commit する。
- Kiro CLI は `[tools."http:kiro-cli"]` に公式の zip の URL（`https://prod.download.cli.kiro.dev/stable/<版>/kirocli-x86_64-linux.zip`）と `bin_path` を書く。各版の sha256 は公式の `https://prod.download.cli.kiro.dev/stable/index.json` にあり、`checksum_url` と `checksum_expr` で読んで `mise.lock` に記録する。PGP 署名（`<zip>.asc`）は検証しない。信頼の根拠は `index.json` の sha256 と、記録後の `mise.lock` である。
- `mise.toml` に書くのは `[tools]` だけにする。タスク（Task の役割）、環境変数、設定、Node.js と pnpm（Vite+ の役割）は書かない。
- `http:kiro-cli` の option（`url`、`bin_path`、`checksum_url` のどれも）は `mise trust` を要する。`/home/projects` のような広い範囲を `trusted_config_paths` で信頼せず、チェックアウトごとに `mise.toml` を読んで `mise trust` を実行する。mise の trust はパスごとで、中身が変わっても取り消されない。手順は `setup.md` と `dev-workflow.md` の worktree の節に書く。
- Kiro CLI の Java の LSP（`.kiro/settings/lsp.json`）が使う jdtls と、jdtls に `@Slf4j` を解釈させる Lombok の jar も、`http:jdtls` と `http:lombok` として Eclipse と Maven Central の URL を書く（[#455](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/455)）。
- Kiro Web の `kiro-web.sh` は、sandbox の `MISE_STATE_DIR` が空なので `mise trust` を実行する。サンドボックスは自分の `kiro-cli` を持ち、LSP の機能も無いので、`http:kiro-cli`、`http:jdtls`、`http:lombok` は入れない。
- `task lint-actions-security` と Lefthook の zizmor のジョブは、Docker の image でなく mise の zizmor を使う。`zizmor.yml` の `zizmor-action` の `version` には `mise.toml` と同じ版を書く。版を書く場所はこの2か所だけである。action の SHA が持つ版の一覧に無い版は動かないので、zizmor を上げるときは SHA も確かめる。
- ローカルでは apt で入れた mise が `mise install --locked` を実行する。
- PATH は mise の shims で通す。`04-setup-shell.sh` が `~/.bashrc` と `~/.profile` に `eval "$(mise activate bash --shims)"` を足す。VS Code Server と、そこから起動する Kiro とエージェントのシェルは、対話なしのログインシェルの PATH を受け継ぎ、Ubuntu の `~/.bashrc` は対話シェルでなければ途中で読むのをやめるので、`~/.profile` にも要る。Taskfile と Lefthook は `betterleaks` と `iwe` をそのまま呼ぶ。
- CI は composite action `.github/actions/setup-mise` を使う。この action だけが `jdx/mise-action` の SHA と mise の版を持ち、入力 `tools` でジョブが使う道具だけを入れる。
- composite action は `MISE_LOCKED=1` をジョブの環境変数にし、install と後の step の shims に lock に無い版を拒ませる。
- Java は実行用のイメージ（`backend/Dockerfile` の `amazoncorretto:25-*`）と同じ Corretto の 25 系にする。
  CI は `tools` に `java` を渡し、`jdx/mise-action` が mise の環境変数（`env` の既定）として `JAVA_HOME` を後の step に渡す。
  ローカルは `JAVA_HOME` を設定せず、Gradle は PATH の shims の `java` を使う。
- CI ではキャッシュを使わない。復元したキャッシュは `mise.lock` と照合されないためである。
- setup-mise を使う workflow は、paths 条件に `mise.toml`、`mise.lock`、`.github/actions/setup-mise/**` も入れる。以前は Task の版を上げると各 workflow 自身が変わって走ったが、今は `mise.toml` と `mise.lock` しか変わらないためである。
- CI の job は使う道具だけを入れるので、`task mise-lock-check` が `mise install --locked --dry-run` で全ての道具の版が `mise.lock` に載ることを検査する。
- `task ci-gate-check` が、この paths 条件と、`mise.lock` を通さずに道具を入れる action（`ci-gate-check.py` の `BANNED_USES`）を検査する。
- 番号付きのセットアップスクリプトのうち `00-setup-task.sh`、`01-setup-java.sh`、`03-setup-kiro.sh`、`05-setup-go-betterleaks.sh`、`06-setup-iwe.sh` と `versions.env` を削除する。

`mise activate` ではなく shims を選んだ理由は、PATH が作業ディレクトリに左右されないことである。
`mise activate` はプロンプトのたびに、そのときのディレクトリの `mise.toml` に合わせて PATH を書き換える。
そのため、リポジトリの外のシェルから起動したエディタや Kiro は、リポジトリの道具の PATH を受け継がない。
shims は決まったディレクトリなので、受け継いだ PATH のまま、実行するときのディレクトリの `mise.toml` から版を選ぶ。
どちらも `~/.bashrc` だけから通すと、対話シェルを経ずに起動したプロセスに PATH が通らない。そのため shims の行は `~/.profile` にも書く。

## Consequences

### Positive

- 版を上げる作業は、`mise.toml` を書き換えて `mise lock` を実行し、`mise.lock` と `.mise/locks/` を commit するだけになる。CI の 17 か所にあった Task の版と checksum の複製がなくなり、mise の版と `jdx/mise-action` の SHA は composite action の1か所にまとまる。
- `mise lock` は betterleaks の配布物を cosign の署名で検証してから sha256 を `mise.lock` に記録する。`mise install --locked` は Task と betterleaks を `mise.lock` の sha256、iwe を `.mise/locks/` の npm の integrity と照合して入れる。`mise.lock` の Task の sha256 は、`versions.env` に書いていた値と同じである。
- CI では、`mise.toml` の版だけを上げて lock を作り直さない変更が `is not in the lockfile` で止まる。
- Go を入れる必要がなくなる。
- ローカルと CI の Java が、`mise.lock` の同じ版と checksum になる。

### Negative

- `mise.toml` が trust を要するので、clone と新しい worktree のたびに `mise trust` を実行する。忘れると shims が `Config files in … are not trusted` で止まり、`task` を呼ぶ Git フックも止まる。mise のエラーが直し方を示す。CI の `jdx/mise-action` は trust を自動で済ませる。
- CI は `tools` で名指しした道具だけを入れるので、Kiro CLI（3.5.0 の zip は 139,487,134 byte）を入れない。Kiro CLI を動かす job を足すときは、`tools` に `http:kiro-cli` を書く。
- 公式のインストーラーが `~/.local/bin` に入れた `kiro-cli` が残ると、`~/.profile` に shims の行が無い環境のログインシェルでは、`~/.profile` が `~/.bashrc` の後に `~/.local/bin` を先頭へ足すので、shims より先に使われる。`setup.md` の手順で消す。
- Kiro CLI は起動時に自己更新を確かめる（設定 `app.disableAutoupdates` が `kiro-cli settings list` に出る）。自己更新が mise の install 先の `kiro-cli` を書き換えるかは確かめていない。書き換えると、`mise install --locked` は checksum を見直さず、固定が崩れる。`setup.md` に自己更新を止める設定を書いたが、これで止まるかは未確認である。
- 開発者は mise を入れ、shims を PATH に通す必要がある。shims が PATH にないと `task` が見つからない。
- `~/.bashrc` に apt の Corretto の `JAVA_HOME` が残る環境では、Gradle が mise の Java でなく apt の Java を使う。これを見つける検査はなく、`docs/local-env-setup/setup.md` の手順で消す。
- shims は `mise.toml` のあるチェックアウトの中でだけ道具を選べる。
  `task` も `java` も、リポジトリの外では動かない。
  この変更より前に切ったブランチや worktree では、古い `task` や apt の Java を消すと、Git フックや Gradle が動かなくなる。
- 実行用のイメージの Java は Dockerfile の digest で決まり、Dependabot が上げる。
  `mise.toml` の Java は Dependabot が上げないため、イメージのパッチの版が先へ進み、ローカルと CI は手で上げるまで元の版に残る。
  以前の `actions/setup-java` の `25` は Corretto の最新のパッチを追っていたので、CI はイメージに近かった。
  この変更の時点で `actions/setup-java` は `25.0.5.1.2` を入れていたが、mise がまだその版を持たないため `25.0.5.0.10` に固定した。
- iwe は実行に Node.js を要するが、mise は Node.js を入れない。ローカルでは Vite+、CI では `actions/setup-node` が入れる。
- ローカルの shims は lock を強制しない。`mise.toml` の版だけを上げると、shims は初回の実行でその版を入れて `mise.lock` を書き換える。書き換わった `mise.lock` は差分に出て、commit しなければ CI が止める。
- `.mise/locks/` が欠けると、`mise install --locked` は iwe の install で失敗する（mise のエラーは `mise lock` での修復を案内する）。
- 版を上げる PR では、setup-mise を使う workflow がすべて走る。betterleaks か iwe だけを上げても backend、frontend、e2e、dast、mutation test が走る。
- Dependabot は `mise.toml`（Java を含む）と composite action の `version` を更新しない。[#389](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/389) で Renovate を入れるまで手で上げる。`jdx/mise-action` の SHA は、`dependabot.yml` の `directories` に composite action を足して Dependabot に更新させる。

### Neutral

- `mise.lock` は `linux-x64` だけを持つ。開発環境は WSL、CI は `ubuntu-latest` である。ほかの platform で開発する人が来たら `mise lock --platform` に足す。
- CI の mise の版とローカルの apt の mise の版はずれうる。mise 2026.11.0 と 2026.11.9 で同じ `mise.lock` と `.mise/locks/` が作られ、どちらでも `mise install --locked` が通ることを確かめた。
- zizmor の `self-repository` は `uses: ./.github/actions/setup-mise` に `$/` の形を勧めるが、`ci.yml` と同じく actionlint（1.7.12）がまだ受け付けない（rhysd/actionlint#732）ので `./` のままにする。`.github/zizmor.yml` でこの rule を無効にし、ローカルと CI で同じ版と同じ設定にする。GitHub の API を使う audit は、token のあるCIでだけ動く。
- Kiro Web のサンドボックスも同じ `mise.lock` から入れる（[ADR-093](ADR-093-support-kiro-web-sandbox-as-development-environment.md)）。
  iwe はサンドボックスの glibc 2.34 で動く 0.29.2 へ上げた。
- Vite+ はこの ADR の対象外であり、番号付きのスクリプトに残る。
- Kiro CLI は `linux-x64` だけを `mise.lock` に持つ。aarch64 の WSL では `mise install --locked` が失敗する。
- Kiro CLI の版の自動更新は [#389](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/389) で Renovate を入れるまで、手で上げる。Dependabot は `http:` backend を扱えない。
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
- **Cons**：mise の版を上げるたびに 17 か所を手で直すことになる。`MISE_LOCKED=1` は書いた step にしか効かない。

### 選択肢5: mise.toml に `[settings] locked = true` を書く

- **Description**：lock の強制を `mise.toml` に書き、ローカルの shims にも CI にも lock に無い版を拒ませる。
- **Pros**：ローカルでも lock に無い版が入らない。CI の `MISE_LOCKED=1` も要らない。
- **Cons**：`[tools]` のほかを書いた `mise.toml` は、チェックアウトごとに `mise trust` を要する。新しい worktree では trust する前に shims が `Config files in … are not trusted` で止まり、`task` を呼ぶ Git フックも止まる。CI の `jdx/mise-action` は trust を自動で済ませる。Kiro CLI を足した後は、trust の費用はどの案でも払うので、この案を採らない理由はグローバルの lock の費用だけになる。グローバルの mise の設定に道具を持つ開発者は、グローバルの lock も要るようになる。

### 選択肢6: `/home/projects` を `trusted_config_paths` に入れる

- **Description**：`04-setup-shell.sh` が mise のグローバル設定に `/home/projects` を足し、その下の `mise.toml` をすべて信頼する。
- **Pros**：新しい worktree でも `mise trust` が要らない。
- **Cons**：同じディレクトリの別のリポジトリや取り込んだ第三者のコードの `mise.toml` も、確かめずに信頼する。`mise.toml` は URL やタスクを書けるので、信頼の範囲は最小にする。

### 選択肢7: trust を避けるため、Kiro CLI を `mise.toml` に書かない

- **Description**：`03-setup-kiro.sh` を残し、版を決めた zip を取って `index.json` の sha256 と照合する。または #389 の Renovate か mise の registry への追加を待つ。
- **Pros**：`mise.toml` が trust を要さず、`mise-trust-check` と新しい worktree の手順が変わらない。
- **Cons**：版と checksum の置き場所がまた分かれ、`mise.lock` に記録されない。Kiro CLI だけ `mise install --locked` の外に残り、一本化できない。

## References

- [#385](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/385)：この判断を実装する Issue
- [#254](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/254)：mise の採用を決めた調査
- [ADR-010](ADR-010-adopt-task-as-project-task-runner.md)：Task の採用と版の固定
- [mise.lock の docs](https://mise.jdx.dev/dev-tools/mise-lock.html)
- [jdx/mise-action](https://github.com/jdx/mise-action)
