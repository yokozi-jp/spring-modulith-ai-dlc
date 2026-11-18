---
type: Runbook
title: Kiro Web の開発環境
description: Kiro Web のサンドボックスで、ローカルと同じ Task、検査、テスト、開発用の依存、E2E、DAST を実行する手順と、サンドボックス固有の制約。Kiro Web でタスクやテストを実行する前、サンドボックスで道具やコンテナが動かないときに読む。
tags: [runbook, setup, kiro-web, mise, podman]
---

# Kiro Web の開発環境

Kiro Web では、tool call の最初に `kiro-web.sh` を source して、道具と PATH を揃える。
ホストの 8080 から 8082 を公開するサービスを起動する Task は、`kiro-web-netns` の中で実行する。
仕組みと理由は [ADR-091](../adr/ADR-091-support-kiro-web-sandbox-as-development-environment.md) にある。

## 道具を揃える

tool call の最初に、リポジトリのルートで次を実行する。

```bash
source docs/local-env-setup/kiro-web.sh
```

tool call ごとにシェルは新しくなるので、呼び出しごとに実行する。
入れ済みの道具は入れ直さないので、2 回目以降は 1 秒かからない。
道具と状態は `~/.cache/kiro-web` に置かれ、置き場所は環境変数 `KIRO_WEB_HOME` で変えられる。

初めて使うときは、続けてルートの依存とフロントエンドの依存を入れる。

```bash
npm ci
task fe-install
```

## ホストの 8080 から 8082 を使う Task

サンドボックスの別のプロセスが、ホストの 8080、8081、8082 を使っている。
これらのポートを公開するサービスを起動する Task は、そのままでは起動しないので、コマンドの前に `kiro-web-netns` を付ける。

```bash
kiro-web-netns task e2e
```

対象の Task は次のとおり。

- `task compose-up`、`task compose-up-backend`、`task dev`
- `task e2e`
- `task scan-dast`、`task scan-dast-active`

`task test`、`task be-test`、`task be-mutation-test` は、Testcontainers が空いているポートを選ぶので、付けずに実行する。

`kiro-web-netns` の名前空間とコンテナは、呼び出しの終了で消える。
開発用の依存を使う作業は、起動から片付けまでを 1 回の呼び出しで済ませる。

```bash
kiro-web-netns bash -c 'task compose-up && task be-migrate && task oidc-check; task compose-down'
```

`task dev` は終わらないので、同じ呼び出しのなかで `timeout 600 task dev &` のように背景で起動し、確かめる処理を続ける。

`kiro-web-netns` は同時に 1 つしか動かせず、2 つ目は `別の kiro-web-netns が動いています` で止まる。

## サンドボックスの制約

- 書けるのは `~/.cache`、`/tmp`、ワークスペースだけである。
- `/tmp` は tool call ごとに消えるので、後で読むログは `$KIRO_WEB_HOME` に書く。
- 背景のプロセスも tool call の終了で止まるので、push はフォアグラウンドで実行する（[開発ワークフロー](../tooling/dev-workflow.md)の「エージェントがcommitとpushをするとき」）。
- 1 回の tool call の上限は 30 分である。
  長い Task には `timeout` を付ける。
- `task dev` の backend の `/actuator/health` は、認証なしでは 401 を返す。
  起動の待ち合わせでは、応答があることを条件にする。

## 版を上げるとき

- mise の版は `.github/actions/setup-mise/action.yml` を正とし、`kiro-web.sh` はそこから読む。
- Compose の版は `mise.kiro-web.toml` にある。
  書き換えたら `MISE_ENV=kiro-web mise lock --platform linux-x64 docker-compose` を実行し、`mise.kiro-web.lock` を同じコミットに含める。
  上げた後は `kiro-web-netns task e2e` で、イメージのビルドと起動を確かめる。
