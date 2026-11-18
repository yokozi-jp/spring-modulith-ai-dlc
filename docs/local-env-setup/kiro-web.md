---
type: Runbook
title: Kiro Web の開発環境
description: Kiro Web のサンドボックスで、ローカルと同じ Task、検査、テストを実行できるようにする手順と、サンドボックス固有の制約。Kiro Web でタスクやテストを実行する前、サンドボックスで道具が見つからないか動かないときに読む。
tags: [runbook, setup, kiro-web, mise, podman]
---

# Kiro Web の開発環境

Kiro Web のサンドボックスは WSL とは別の環境で、`task` が入っておらず、入っている mise は `mise.lock` を読めない。
`docs/local-env-setup/kiro-web.sh` を source すると、ローカルと同じ道具とコンテナの実行環境が使える。

## 使い方

tool call の最初に、リポジトリのルートで次を実行する。

```bash
source docs/local-env-setup/kiro-web.sh
```

tool call ごとにシェルは新しくなるので、PATH と環境変数は呼び出しごとに設定し直す。
入れ済みの道具は `mise.toml` と `mise.lock` が変わるまで入れ直さないため、2 回目以降は 1 秒かからない。
初回は mise、Vite+、Compose を取得するので、数秒から数十秒かかる。

道具と状態は `~/.cache/kiro-web` に置き、リポジトリには何も書かない。
置き場所は環境変数 `KIRO_WEB_HOME` で変えられる。

初回の後は、ルートの `npm ci` とフロントエンドの `task fe-install` を実行する（[開発ワークフロー](../tooling/dev-workflow.md)）。

## 入る道具

- **mise**：CI の composite action と同じ版を、release の checksum を確かめて入れる。
  サンドボックスの mise は `mise.lock` の version 3 を読めない。
- **Task、betterleaks、iwe**：`mise install --locked` で `mise.lock` の版と checksum から入れる（[ADR-090](../adr/ADR-090-pin-cli-tools-with-mise-lock.md)）。
- **Vite+（`vp`）**：`02-setup-viteplus.sh` を、書き込める場所を指して実行する。
- **Java、Gradle、`gh`**：サンドボックスに入っているものを使う。
- **`docker`、`docker compose`**：podman の API サービスを起動する `kiro-web-docker.sh` と、Compose の単体の binary を使う。

サンドボックスの `docker` は podman への別名で、そのままでは動かない。
`kiro-web-docker.sh` は、`/etc`、`/run`、`/var/lib` を書ける overlay にした mount namespace で podman のサービスを起動する。
`docker`、`docker compose`、Testcontainers（環境変数 `DOCKER_HOST`）は、このサービスへつなぐ。

## サンドボックスの制約

- **書き込める場所**：`~/.cache`、`/tmp`、ワークスペースだけである。
  `~/.local`、`/etc`、`/run`、`/var` は読み取り専用なので、ホームの設定を前提にする道具には保存先を指定する。
- **tool call の終了で消えるもの**：`/tmp`、起動したプロセス、コンテナである。
  `nohup` や `&` で背景に回しても残らないので、[開発ワークフロー](../tooling/dev-workflow.md)の背景で push する手順は使えず、フォアグラウンドで実行する。
  1 回の上限は 30 分で、`timeout` に指定する。
- **コンテナの使い方**：`task compose-up` で起動したコンテナは、次の tool call には残らない。
  `task test` のように、起動から片付けまでを 1 回で終える Task は動く。
- **出力の読み方**：長い出力はファイルへ書き、`grep` と `tail` で必要な行だけ読む。

## 確認済みの Task

2026-11-18 に、次の Task をこの環境で成功させた。

- バックエンド：`task check`、`task test`（PostgreSQL の rollback 検証、Testcontainers を使う全テスト、カバレッジの検証、`api-lint`）
- フロントエンド：`task fe-verify`
- docs と CI の設定：`task okf-check`、`task lint-md`、`task adr-check`、`task skills-check`、`task ci-gate-check`、`task release-check`
- 静的解析：`task lint-actions`、`task lint-actions-security`、`task lint-docker`、`task api-lint`、`task otel-collector-check`、`task lint-duplicates`
- 秘密の検査：`task scan-secrets`、`task scan-secrets-all`
- Git フック：`npm ci` で入る Lefthook の commit-msg と pre-commit

## 動かない Task

次の Task は、サンドボックスの別のプロセス（`localhost:8080` が uvicorn として応答する）が 8080 を使っているため、この環境では動かない。
`docker/compose.yml` の Keycloak と、`docker/compose-test.yml` の E2E 用 backend が、ホストの 8080 に固定で公開するためである。

- `task compose-up`、`task dev`
- `task e2e`

`task be-mutation-test`、`task scan-dast`、`task scan-dast-active` は試していない。

## 既知の問題

- `task test` の `ApiExceptionHandlerTest` が 1 回だけ失敗した。
  OpenTelemetry の exporter が、Collector の無い `127.0.0.1:4318` への送信失敗を ERROR で記録し、テストがそのログを拾ったためである。
  `task be-test` の再実行と、そのテストの単体の実行は成功し、その後の `task test` も成功した。
- Gradle の起動時に、JGit が `~/.config/jgit` に書けないという例外のログが出る。
  実行結果には影響しない。
- `iwes`（LSP サーバー）は標準入出力で待ち受けるので、`iwes --version` は終了しない。
- `pkill -f` は、自分のシェルのコマンドラインにも一致して自分を止めるので使わない。

## 版を上げるとき

- mise の版は `.github/actions/setup-mise/action.yml` を正とし、`kiro-web.sh` はそこから読む。
- Compose の版は `kiro-web.sh` の `_kw_compose_version` にあり、手で上げる。
- iwe 0.24.2 の npm パッケージは glibc 2.39 以上を要し、サンドボックスの glibc 2.34 では起動しなかった。
  静的リンクの musl 版を配る 0.29.2 へ上げたので、サンドボックスでも起動する。
