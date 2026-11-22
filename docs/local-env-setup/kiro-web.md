---
type: Runbook
title: Kiro Web の開発環境
description: Kiro Web のサンドボックスで、ローカルと同じ Task、検査、テスト、開発用の依存、E2E、DAST を実行する手順と、Agent Config の iwe の設定、サンドボックス固有の制約。Kiro Web でタスクやテストを実行する前、サンドボックスで道具やコンテナが動かないとき、`task` や `docker` が見つからない、`gh` が mise のエラーで止まるなどの症状が出たとき、iwe の MCP サーバーが接続しないときに読む。
tags: [runbook, setup, kiro-web, mise, podman]
---

# Kiro Web の開発環境

Kiro Web では、tool call の最初に `kiro-web.sh` を source して、道具と PATH を揃える。
ホストの 8080 から 8082 を公開するサービスを起動する Task は、`kiro-web-netns` の中で実行する。
仕組みと理由は [ADR-093](../adr/ADR-093-support-kiro-web-sandbox-as-development-environment.md) にある。

## 道具を揃える

tool call の最初に、リポジトリのルートで次を実行する。

```bash
source docs/local-env-setup/kiro-web.sh
```

tool call ごとにシェルは新しくなるので、呼び出しごとに実行する。
入れ済みの道具は入れ直さないので、2 回目以降は 1 秒かからない。
道具と状態は `~/.cache/kiro-web` に置かれ、置き場所は環境変数 `KIRO_WEB_HOME` で変えられる。

[iwe の MCP サーバーを Agent Config に設定する](#iwe-の-mcp-サーバーを-agent-config-に設定する)と、セッションの開始時に `iwe-mcp.sh` が道具を揃えてから iwe を起動するので、source しなくても使える。
`iwe-mcp.sh` は podman のサービスを起動しないので、コンテナを使う前には tool call で `kiro-web.sh` を source する。

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

## iwe の MCP サーバーを Agent Config に設定する

この節は、人が Kiro Web の画面で行う。
エージェントは実行できないので、iwe の MCP サーバーが接続しないときは、利用者にこの設定を依頼する。

Kiro Web はリポジトリの `.kiro/settings/mcp.json` を読まない（[サンドボックスの制約](#サンドボックスの制約)）。
Agent Config > Powers & MCP の `iwe` を、次の手順で `.kiro/settings/mcp.json` の `iwe` に揃える。

1. リポジトリの `.kiro/settings/mcp.json` を開き、`mcpServers.iwe` の値を読む。
2. Agent Config > Powers & MCP の `iwe` を、1 の値と同じにする。
   `command` と `args` に加えて、`disabledTools` などの他のキーも写す。
3. 新しいセッションを始め、最初の tool call の前に `iwe` の MCP サーバーが接続していて、`iwe_find` が使えることを確かめる。

値の正本は `.kiro/settings/mcp.json` であり、この Runbook には写さない。
`.kiro/settings/mcp.json` の `iwe` を変えたら、Agent Config の値にも同じ変更を入れる。

接続しないときの症状は `MCP server "iwe" failed to connect: Connection closed` である。
原因は、Agent Config の `iwe` が `.kiro/settings/mcp.json` と違うことである。
たとえば #482 より前のコマンド `cd "$(git rev-parse --show-toplevel)/docs" && exec iwec` は、git の外の `/projects/sandbox` で `cd` が失敗する。

## 症状と対処

素のサンドボックスでは、`kiro-web.sh` を source していないために、道具が無い、またはコンテナが動かないように見える。
次の症状が出たら、表の対処を試す。

| 症状                                                         | 原因                                                              | 対処                                                                                                         |
| ------------------------------------------------------------ | ----------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| `could not find a working conmon binary`                     | 素の `docker` を呼んでいる。                                      | `kiro-web.sh` を source する。                                                                               |
| `Config files in ... are not trusted`                        | `gh` や `java` の shim が mise に止められている。                 | `kiro-web.sh` を source する。                                                                               |
| `task: command not found`、`iwe` や `lefthook` が無い        | `kiro-web.sh` を source していない。                              | `kiro-web.sh` を source する。                                                                               |
| `/tmp` のファイルが次の tool call で消える                   | `/tmp` は tool call ごとに消える。                                | ログは `$KIRO_WEB_HOME` に書く。                                                                             |
| `grep_search` の `includePattern` に一致しない               | パスの起点がワークスペースのルート（`/projects/sandbox`）である。 | ルートからのパスで書く。例は `spring-modulith-ai-dlc/docs/**/*.md`。                                         |
| `execute_bash` の出力が 30000 文字で切れる                   | 長い出力は、プレビューに切り詰められる。                          | 全文はセッションの tool-outputs に保存されるので、`grep` と `tail` で読む。                                  |
| `docker info --format '{{.ServerVersion}}'` が失敗する       | `docker` は Podman への中継で、`ServerVersion` を返さない。       | `--format` を付けず、`docker info` の本文を読む。                                                            |

## サンドボックスの制約

- ワークスペースのルートは `/projects/sandbox` で、リポジトリはその子のディレクトリにある。
- Kiro Web は、リポジトリの `.kiro/settings`、`.kiro/steering`、`.kiro/skills`、`.kiro/hooks` を読まない。
  読むのは、Agent Config の値と、Agent Config > Cloud Config にアップロードした写しである。
  MCP の設定は Agent Config > Powers & MCP の値で、サンドボックスでは `/projects/.kiro/settings/mcp.json` に置かれる。
  steering、skills、hooks の写しは、リポジトリと食い違うことがある。
  hooks の写しが古いと、`block-generated-writes` のようにリポジトリにある hook が動かない。
  写しを最新にする手順は、まだ決めていない（[#351](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/351)）。
- 書けるのは `~/.cache`、`/tmp`、ワークスペースだけである。
- `/tmp` は tool call ごとに消えるので、後で読むログは `$KIRO_WEB_HOME` に書く。
- 背景のプロセスも tool call の終了で止まるので、`task agent-commit`と`task agent-push`は`task agent-wait`と同じ tool call で実行する（[開発ワークフロー](../tooling/dev-workflow.md)の「エージェントがcommitとpushをするとき」）。
- 1 回の tool call の上限は 30 分である。
  長い Task には `timeout` を付ける。
- `task dev` の backend の `/actuator/health` は、認証なしでは 401 を返す。
  起動の待ち合わせでは、応答があることを条件にする。

## 版を上げるとき

- mise の版は `.github/actions/setup-mise/action.yml` を正とし、`kiro-web.sh` はそこから読む。
- Compose の版は `mise.kiro-web.toml` にある。
  書き換えたら `MISE_ENV=kiro-web mise lock --platform linux-x64 docker-compose` を実行し、`mise.kiro-web.lock` を同じコミットに含める。
  上げた後は `kiro-web-netns task e2e` で、イメージのビルドと起動を確かめる。
