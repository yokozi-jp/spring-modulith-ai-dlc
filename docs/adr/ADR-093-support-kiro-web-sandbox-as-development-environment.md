---
type: ADR
title: 'ADR-093: Kiro Web のサンドボックスを開発環境に加え、道具とコンテナを source するスクリプトで揃える'
description: Kiro Web のサンドボックスで WSL と同じ Task、テスト、E2E、DAST を動かすため、mise、podman の API サービス、専用のネットワーク名前空間を source するスクリプトで用意する判断。Kiro Web の開発環境の仕組みを変えるとき、サンドボックスで道具やコンテナが動かないときに読む。
tags: [adr, tooling, kiro-web, mise, podman, container]
---

# ADR-093: Kiro Web のサンドボックスを開発環境に加え、道具とコンテナを source するスクリプトで揃える

## Status

Proposed

## Date

2026-11-18

## Context

[ADR-010](ADR-010-adopt-task-as-project-task-runner.md) と [ADR-090](ADR-090-pin-cli-tools-with-mise-lock.md) は、開発環境を WSL 上の Linux としている。
エージェントは Kiro Web のサンドボックスでも作業するが、そこでは `task` が無く、Task、検査、テストを実行できなかった。

サンドボックスには、WSL と違う制約がある。

- `~/.local`、`/etc`、`/run`、`/var` は読み取り専用で、書けるのは `~/.cache`、`/tmp`、ワークスペースだけである。
  apt も使えない。
- 入っている mise（2026.9.12）は、`mise.lock` の lockfile version 3 を読めず、実行すると `mise.lock` を書き換える。
- glibc が 2.34 である。
  iwe 0.24.2 の npm パッケージは glibc 2.39 以上を要し、起動しなかった。
- `docker` は podman への別名で、docker daemon は無い。
  podman も、上の読み取り専用の場所に書こうとして動かない。
  cgroup のファイルも読み取り専用である。
- サンドボックスの別のプロセスが、ホストの 8080、8081、8082 を使っている。
  `docker/compose.yml` と `docker/compose-test.yml` は Keycloak、E2E の backend、WireMock をこれらのポートに固定で公開する。
- tool call が終わると、`/tmp`、起動したプロセス、コンテナが消える。
- ワークスペースのルートは git の外の `/projects/sandbox` で、リポジトリはその子にある。
  Kiro Web が読む MCP の設定は Agent Config の値であり、リポジトリの `.kiro/settings/mcp.json` ではない。
  Decision は、Kiro Web がこのファイルを読むと想定して書いていたので、Proposed のまま書き直した。

## Decision

- `docs/local-env-setup/kiro-web.sh` を、Kiro Web の tool call の最初に source する。
  道具と状態は `~/.cache/kiro-web` に置き、リポジトリには書かない。
  `/inner_container`（podman の補助バイナリの置き場所）が無い環境では、何もせずに戻る。
- mise は CI の composite action と同じ版を取り直し、Task、betterleaks、iwe、Java は WSL と CI と同じ `mise.lock` から `mise install --locked` で入れる。
- iwe を、静的リンクの musl 版を配る 0.29.2 へ上げる。
- サンドボックスだけで使う Compose は、`mise.kiro-web.toml` と `mise.kiro-web.lock` に固定し、`MISE_ENV=kiro-web` で入れる。
  版は 2.x に置く。
- `docker` の代わりに `kiro-web-docker.sh` を置く。
  `/etc`、`/run`、`/var` を書ける overlay にした mount namespace で podman の API サービスを起動し、docker、Compose、Testcontainers をそこへ向ける。
  コンテナは cgroup なしで動かす。
- systemd が無く podman が healthcheck を定期実行しないので、同じスクリプトが各コンテナの `interval` ごとに `healthcheck run` を実行する。
- iwe の MCP サーバーは `docs/local-env-setup/iwe-mcp.sh` から起動する。
  `.kiro/settings/mcp.json` の `iwe` のコマンドは、リポジトリのルートでも、git の外のワークスペースのルートでも、このスクリプトを見つけて起動する。
  Kiro Web は MCP サーバーを tool call より先に、git の外のワークスペースのルートで起動し、このファイルを読まない。
  そのため Kiro Web では、このコマンドを Agent Config > Powers & MCP の `iwe` に入れて使う。
  コマンドは子のディレクトリからもこのスクリプトを探す。
  子のディレクトリは `.git` がディレクトリの本体の checkout だけを候補にし、横に作った git worktree を選ばない。
  本体の checkout が 2 つあれば、名前順で先のものを選ぶ。
  サンドボックスでは、このスクリプトが `kiro-web.sh` で道具を揃えてから `iwec` を実行する。
- ホストの 8080 から 8082 を公開するサービスを起動する Task は、`kiro-web-netns` で作る専用のネットワーク名前空間のなかで実行する。
  外への通信は veth と NAT で通し、名前解決はホスト側の dnsmasq が中継する。
- Compose の healthcheck のうち、シェルの構文を含む検査は `CMD-SHELL` で書く。
  podman の互換 API は `CMD` の 1 要素に含まれる空白で引数を分割し、Keycloak の検査が常に成功していた。

## Consequences

### Positive

- Kiro Web のエージェントが、`task test`、`task e2e`、`task scan-dast` などを WSL と同じ Task で実行し、push の前に Lefthook のフックを通せる。
- Task、betterleaks、iwe、Java は WSL と CI と同じ lock から入るので、版がずれない。
- WSL で `kiro-web.sh` を source しても、環境を変えない。

### Negative

- podman と名前空間を扱う、サンドボックス専用のスクリプトを保守する必要がある。
  サンドボックスの構成（`/inner_container` の場所、使用中のポート、読み取り専用の場所）が変わると動かなくなりうる。
- Compose は 2.x に留まる。
  Compose 5 以降は buildx を要し、podman の API でイメージを作れない。
- コンテナは tool call をまたいで残らないので、開発用の依存を使う作業は 1 回の呼び出しで起動から片付けまでを済ませる。
- `kiro-web-netns` は、インターフェースの名前とアドレスが固定なので、同時に 1 つしか動かせない。
- mise の binary は、同じ release の checksum で確かめるだけで、署名は検証しない。

### Neutral

- WSL は引き続き主な開発環境である。
  ADR-010 と ADR-090 の WSL の記述は変えない。
- `CMD-SHELL` への変更は Docker でも同じ意味で動く。

## Alternatives Considered

### 選択肢1: Compose のホストのポートを環境変数で変えられるようにする

- **Description**：`docker/compose.yml` と `docker/compose-test.yml` の 8080 から 8082 を `${…:-8080}` のように変数にし、サンドボックスでは別のポートを使う。
- **Pros**：名前空間を作るスクリプトが要らない。
- **Cons**：Keycloak の realm の redirect URI と issuer、`.env.example` と `.env.test.example`、Vite の proxy、E2E が 8080 を前提にしており、ポートを変えると WSL と別の設定を保守することになる。

### 選択肢2: docker daemon のあるサンドボックスのイメージを求める

- **Description**：Kiro Web の側で、docker daemon を動かせるサンドボックスを用意してもらう。
- **Pros**：podman の互換の差と、mount namespace の工夫が要らない。
- **Cons**：リポジトリの外の判断で、いつ使えるか分からない。

### 選択肢3: Compose 5 と buildx を使う

- **Description**：Compose を最新に保ち、buildx を入れてイメージを作る。
- **Pros**：Compose の版を上げ続けられる。
- **Cons**：buildx は BuildKit を要し、podman の API だけではイメージを作れない。
  BuildKit をコンテナで動かす方法は、cgroup の制約の下で試していない。

### 選択肢4: Compose を curl と release の checksum で入れる

- **Description**：`kiro-web.sh` に版を書き、release の `.sha256` で確かめて入れる。
- **Pros**：mise の環境別設定が要らない。
- **Cons**：ADR-090 の lock とは別の固定の仕組みが増え、Renovate からも見えない。

## References

- [Kiro Web の開発環境](../local-env-setup/kiro-web.md)：使い方と制約
- [ADR-090](ADR-090-pin-cli-tools-with-mise-lock.md)：Task、betterleaks、iwe、Java を mise.lock に固定する判断
- [iwe の Issue への回答](https://github.com/yokozi-jp/agent-software-factory/issues/243#issuecomment-6482095083)：iwe 0.29.2 で npm パッケージを静的リンクの musl 版にした
- [#424](https://github.com/yokozi-jp/spring-modulith-ai-dlc/pull/424)：この判断を実装する Pull Request
