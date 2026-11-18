---
type: Runbook
title: Kiro Web の開発環境
description: Kiro Web のサンドボックスで、ローカルと同じ Task、検査、テスト、開発用の依存、E2E、DAST、ミューテーションテストを実行できるようにする手順と、サンドボックス固有の制約。Kiro Web でタスクやテストを実行する前、サンドボックスで道具やコンテナが動かないときに読む。
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

## ホストの 8080 を使う Task

サンドボックスの別のプロセス（`localhost:8080` が uvicorn として応答する）が、ホストの 8080 を使っている。
`docker/compose.yml` の Keycloak と、`docker/compose-test.yml` の E2E 用 backend は、ホストの 8080 に固定で公開するので、そのままでは起動しない。

8080 を使う Task は、専用のネットワーク名前空間のなかで実行する。
次のように、コマンドの前に `kiro-web-netns` を付ける。

```bash
kiro-web-netns task e2e
```

`kiro-web-netns` は、名前空間のなかで `kiro-web.sh` を読み直してからコマンドを実行する。
名前空間のなかでは 8080 が空いていて、外への通信は NAT で通る。
名前空間は呼び出しの終了で消えるので、コンテナも一緒に消える。

次の Task は `kiro-web-netns` を付けて実行する。

- `task compose-up`、`task dev`
- `task e2e`

`task test` と `task be-test` は、付けなくても動くことを確かめた。
`task be-mutation-test`、`task scan-dast`、`task scan-dast-active` は、付けて確かめた。

## 入る道具

- **mise**：CI の composite action と同じ版を、release の checksum を確かめて入れる。
  サンドボックスの mise は `mise.lock` の version 3 を読めない。
- **Task、betterleaks、iwe**：`mise install --locked` で `mise.lock` の版と checksum から入れる（[ADR-090](../adr/ADR-090-pin-cli-tools-with-mise-lock.md)）。
- **Vite+（`vp`）**：`02-setup-viteplus.sh` を、書き込める場所を指して実行する。
- **Java、Gradle、`gh`**：サンドボックスに入っているものを使う。
- **`docker`、`docker compose`**：podman の API サービスを起動する `kiro-web-docker.sh` と、Compose の単体の binary を使う。
- **PyYAML、pyroute2**：`task ci-gate-check` と `kiro-web-netns` が使う Python のパッケージを、`~/.cache/kiro-web/python` へ入れる。

サンドボックスの `docker` は podman への別名で、そのままでは動かない。
`kiro-web-docker.sh` は、`/etc`、`/run`、`/var` を書ける overlay にした mount namespace で podman のサービスを起動する。
`docker`、`docker compose`、Testcontainers（環境変数 `DOCKER_HOST`）は、このサービスへつなぐ。
サービスは名前空間ごとに別のソケットで起動する。

## サンドボックスの制約

- **書き込める場所**：`~/.cache`、`/tmp`、ワークスペースだけである。
  `~/.local`、`/etc`、`/run`、`/var` は読み取り専用なので、ホームの設定を前提にする道具には保存先を指定する。
- **tool call の終了で消えるもの**：`/tmp`、起動したプロセス、コンテナである。
  `nohup` や `&` で背景に回しても残らないので、[開発ワークフロー](../tooling/dev-workflow.md)の背景で push する手順は使えず、フォアグラウンドで実行する。
  1 回の上限は 30 分で、`timeout` に指定する。
- **コンテナの使い方**：`task compose-up` で起動したコンテナは、次の tool call には残らない。
  起動から確認、片付けまでを 1 回の呼び出しで済ませる。
- **Compose の版**：2.40.3 を使い、旧 API のビルダー（`COMPOSE_BAKE=false`、`DOCKER_BUILDKIT=0`）でイメージを作る。
  Compose 5 以降は buildx を要し、podman の API では作れない。
- **出力の読み方**：長い出力はファイルへ書き、`grep` と `tail` で必要な行だけ読む。

## 確認済みの Task

2026-11-18 に、次の Task をこの環境で成功させた。

- バックエンド：`task check`、`task test`、`task be-mutation-test`（`PIT_CHANGED_SINCE` で差分の 4 クラスを指定し、5 件の変異をすべて検出）
- フロントエンド：`task fe-verify`
- 統合：`kiro-web-netns task e2e`（Playwright の 33 件）
- 開発用の依存：`kiro-web-netns task compose-up`、`task be-migrate`、`task dev`（backend が起動し、Keycloak の discovery が 200 を返す）
- スキャン：`kiro-web-netns task scan-dast`、`task scan-dast-active`、`task scan-secrets`、`task scan-secrets-all`
- docs と CI の設定：`task okf-check`、`task lint-md`、`task adr-check`、`task skills-check`、`task ci-gate-check`、`task release-check`
- 静的解析：`task lint-actions`、`task lint-actions-security`、`task lint-docker`、`task api-lint`、`task otel-collector-check`、`task lint-duplicates`
- Git フック：`npm ci` で入る Lefthook の commit-msg、pre-commit、pre-push

## 既知の問題

- `task test` の `ApiExceptionHandlerTest` が 1 回だけ失敗した。
  OpenTelemetry の exporter が、Collector の無い `127.0.0.1:4318` への送信失敗を ERROR で記録し、テストがそのログを拾ったためである。
  `task be-test` の再実行と、そのテストの単体の実行は成功し、その後の `task test` も成功した。
- `task dev` の起動後、`/actuator/health` は認証なしで 401 を返す。
  待ち合わせに使うときは、200 ではなく応答があることを条件にする。
- Gradle の起動時に、JGit が `~/.config/jgit` に書けないという例外のログが出る。
  実行結果には影響しない。
- `iwes`（LSP サーバー）は標準入出力で待ち受けるので、`iwes --version` は終了しない。
- `pkill -f` は、自分のシェルのコマンドラインにも一致して自分を止めるので使わない。

## podman 固有の対処

次は、podman の互換 API とサンドボックスの制約に合わせた対処で、`kiro-web-docker.sh` にある。

- **healthcheck**：podman は `CMD` の引数の 1 要素に空白があると、空白で分割する。
  Keycloak の healthcheck が常に成功する検査になり、Keycloak の起動前に `--wait` が返った。
  両方の compose ファイルの Keycloak を `CMD-SHELL` に直してある。
- **healthcheck の定期実行**：systemd が無いので、サービスと同じ呼び出しのなかで 2 秒ごとに `healthcheck run` を実行する。
- **ポートの転送規則**：前の呼び出しは片付けずに終わるので、netavark の規則が残る。
  ホストの名前空間でサービスを起動するときに、残った規則を消す。
- **cgroup**：cgroup のファイルが読み取り専用なので、コンテナは cgroup なしで動かし、ビルドの `RUN` は cgroup を使わない crun の包みを経由する。

## 版を上げるとき

- mise の版は `.github/actions/setup-mise/action.yml` を正とし、`kiro-web.sh` はそこから読む。
- Compose の版は `kiro-web.sh` の `_kw_compose_version` にあり、手で上げる。
  上げる前に、`kiro-web-netns task e2e` でイメージのビルドを確かめる。
- iwe 0.24.2 の npm パッケージは glibc 2.39 以上を要し、サンドボックスの glibc 2.34 では起動しなかった。
  静的リンクの musl 版を配る 0.29.2 へ上げたので、サンドボックスでも起動する。
