---
inclusion: fileMatch
fileMatchPattern: ["**/Taskfile.yml", "**/Taskfile.yaml", "**/taskfile.yml", "**/taskfile.yaml"]
name: taskfile-best-practices
description: ルートのTaskfileを新規作成または編集するときの規約。厳格なシェル設定、タスクの公開範囲と説明、変数上書き、環境ファイルの分離、順次実行、後片付け、破壊操作の確認、外部ツールのバージョン固定、CIとフックとの一致、文書同期を定める。
---

# Taskfileの書き方

このプロジェクトは、開発コマンドの共通入口としてTaskを使う。
実行するTaskのバージョンは`docs/local-env-setup/versions.env`へ固定する。
基準となる定義は#[[file:Taskfile.yml]]を参照する。

## スキーマとシェル設定

Taskfileはスキーマバージョン3を使う。
コマンドの途中で失敗を見落とさないよう、ルートで次の設定を維持する。

```yaml
version: "3"
set: [errexit, nounset, pipefail]
```

Taskはコマンドごとにシェルを実行する。
変数代入、`cd`、`trap`などの状態を複数行で共有する処理は、一つの複数行コマンドへまとめる。
単純な作業ディレクトリの変更には`cd`ではなく`dir`を使う。

## 公開タスクと説明

利用者が直接実行するタスクには`desc`を付ける。
`task --list`には`desc`を持つ公開タスクだけを表示し、前提確認などの部品は`internal: true`にする。
引数なしの`task`と`task help`は一覧表示だけを行い、時間のかかる処理や破壊操作を開始しない。

タスク名は用途が分かる名前にし、既存の接頭辞を維持する。
バックエンドは`be-*`、Composeは`compose-*`、検査は`lint-*`または`scan-*`を使う。

## 変数の上書き

利用者やCIから変更できる値には、CLI変数または環境変数を優先するデフォルトを定義する。

```yaml
vars:
  TEST_ENV_FILE: '{{.TEST_ENV_FILE | default ".env.test"}}'
```

Taskfile変数とシェル環境変数は同じものではない。
コマンドが環境変数として参照する値は`env`で明示的に渡す。
未設定と空文字の両方を拒否する必要がある資格情報は、空文字を許す`requires`だけに任せず`preconditions`で検証する。

## 環境ファイルの分離

ルートの`dotenv`で`.env`を全タスクへ読み込ませない。
ローカルDB操作だけが`.env`を読み、テストだけが`.env.test`を読む。
本番マイグレーションとDB切り戻しはローカルの環境ファイルを読まず、CIまたはデプロイ環境から注入された資格情報だけを使う。

環境ファイルを切り替えられるタスクは、`COMPOSE_ENV_FILE`または`TEST_ENV_FILE`の上書きを維持する。
タスクから別のタスクを呼ぶ場合も、選択された環境ファイルを明示的に引き継ぐ。

## タスクの実行順序

Taskの`deps`に複数のタスクを書くと並列実行される。
起動後にマイグレーションする処理や、静的解析後に隔離テストを実行する処理には`deps`を使わない。
順序が必要な処理は`cmds`内で`task:`を順番に呼ぶ。

```yaml
tasks:
  verify:
    cmds:
      - task: be-lint
      - task: test
```

並列実行してよいことを確認できた独立処理だけに`deps`を使う。

## 後片付けと終了コード

一時的なComposeスタックを使うタスクは、途中で失敗しても停止とvolume削除を実行する。
元の処理が失敗した場合は元の終了コードを返し、元の処理が成功して後片付けだけ失敗した場合は後片付けの終了コードを返す。

Taskの`defer`で実行した後片付けの失敗は、元のタスクを失敗させない。
後片付けの失敗も検出する必要があるワンショットタスクでは、`trap`などで終了コードを明示的に保存する。

## 破壊操作の確認

DB切り戻しとvolume削除は、明示確認変数がなければ外部操作の前に失敗させる。
対話式の`prompt`は`--yes`で迂回できるため、既存の`CONFIRM_ROLLBACK=yes`と`CONFIRM_RESET=yes`を置き換えない。

本番DB操作は、接続先、スキーマ、マイグレーション用ユーザー、パスワードをGradle実行前に検証する。
資格情報の値をTaskfileへ記録したり、ログへ出したりしない。

## 外部ツールとテンプレート

CIでTaskを導入するActionはcommit SHAへ固定し、Taskの正確なバージョンと配布物のチェックサムを指定する。
Dockerで実行するLintとスキャンは、イメージのタグとdigestを固定する。

Taskの`{{`と`}}`はGoテンプレートとして解釈される。
Dockerの`--format`など別のGoテンプレートをコマンドへ渡す場合は、Task側でエスケープするか、同じ結果を得られる既存コマンドへ置き換える。

## CIとGitフック

CIとLefthookは処理を複製せず、Taskfileの同じタスクを呼ぶ。
入口タスクが呼ぶ部品タスクとCIが呼ぶ部品タスクの処理を一致させる。
Taskfileを変更したら、CIのpaths条件に`Taskfile.yml`が含まれていることも確認する。

## 文書の同期

公開タスクの追加、削除、改名、挙動変更では、次の文書を同じ変更で更新する。

- #[[file:README.md]]の「開発コマンド」と「Lint・テスト」
- #[[file:docs/dev-workflow.md]]
- #[[file:docs/lint-and-test.md]]
- DB操作を変えた場合は#[[file:docs/database-migrations.md]]
- 導入バージョンを変えた場合は#[[file:docs/local-env-setup/versions.env]]とCI

変更後は`task --list`、対象タスク、`task lint-md`を実行する。
順序、環境分離、確認ゲート、後片付けに触れた場合は、その失敗経路も検証する。

## 出典

- Task Guide：<https://taskfile.dev/docs/guide>
- Taskfile Schema：<https://taskfile.dev/docs/reference/schema>
- Task Installation：<https://taskfile.dev/docs/installation>
