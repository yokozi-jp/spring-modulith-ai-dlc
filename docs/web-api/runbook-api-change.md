---
type: Runbook
title: APIを変更する
description: ControllerやDTOを変えてから、OpenAPI契約とOrvalの生成物を再生成し、フロントエンドで使い、検査を通してコミットするまでの手順と、生成物の差分検査が失敗したときの対処を示す。HTTP APIを追加または変更するとき、契約や生成clientの検査がCIで失敗したときに読む。
tags: [runbook, web-api, openapi, orval]
---

# APIを変更する

ControllerやDTOを変えたら`task api-gen`を実行し、`openapi/openapi.yaml`と`frontend/src/api/generated`を同じコミットに含める。
生成物は手で書き換えず、差分を読んで意図どおりかを確かめる。
契約と生成物の管理方針は[ADR-052](../adr/ADR-052-commit-openapi-contract-and-check-generated-client.md)が定める。

## 前提

- Dockerが動き、`frontend`で`pnpm install`を済ませている。
- `task api-gen`はテスト用のPostgreSQLとRedis（`docker/compose-test.yml`）を起動し、終わると片付ける。

## 手順

1. ControllerとDTOを[OpenAPIのアノテーションとJavadoc](openapi-annotations.md)に従って変える。
2. `OpenApiConfig.CONTRACT_VERSION`を[OpenAPI文書の版](versioning.md#openapi文書の版)に従って上げる（互換な追加はMINOR、破壊的変更はMAJOR）。
3. 契約と生成物を再生成する。

   ```bash
   task api-gen
   ```

   Spectralの違反があると、このTaskが失敗する。
   違反したルール名を[OpenAPIのアノテーションとJavadoc](openapi-annotations.md#作成時のチェックリスト)のチェックリストで探して直す。

4. `git diff -- openapi/openapi.yaml frontend/src/api/generated`で、追加や変更が意図どおりかを読む。
   新しいtagを足すと、生成物のファイルが増える（`git status`で未追跡のファイルを確かめる）。
5. フロントエンドでは生成したquery Hookと型を使い、APIを使うテストは生成したMSW handler（`@/api/generated/mocks`）でHTTP境界を置き換える。
   `vi.mock`で生成関数を差し替えない（[フロントエンドのテストと検証](../frontend/testing.md#api境界)）。
6. 検査を実行する。

   ```bash
   task fe-verify
   task test
   ```

7. 契約、生成物、それを使うコードを同じコミットに含める。
8. 必要なら、破壊的変更と設計書をローカルで確かめる。

   ```bash
   git fetch origin main
   task api-breaking
   task api-docs
   ```

   `task api-breaking`は`origin/main`の契約と比べ、破壊的変更があれば失敗する。
   `task api-docs`は`build/api-docs/index.html`を作る。
   このHTMLはRedocのscriptをCDNから読むため、表示にはインターネット接続が要る。

9. 意図した破壊的変更では、次の作業をすべて行う。

   - 手順2に従い、`OpenApiConfig.CONTRACT_VERSION`のMAJORを上げる。
   - Pull Requestにラベル`api-breaking-approved`を付ける。
   - Pull Request本文に変更の理由、影響、移行方法を書く。
   - Pull Requestタイトルまたはsquash commitに`!`を付けるか、commit footerに`BREAKING CHANGE:`を書く。

   `api-breaking-approved`ラベルは、oasdiffの検出結果をログに残したまま、必須チェック`Check API contract 🔀`を失敗させないためだけに使う。
   ラベルがなければ、検出された破壊的変更によって同チェックが失敗し、mergeできない。

   `api-breaking-approved`ラベルはrelease-pleaseのmajor判定を制御しない。
   [コミットメッセージの規約](../repository/commit-messages.md#破壊的変更)に従うConventional Commitの`!`または`BREAKING CHANGE:`がアプリケーションのmajor releaseを起動する。
   版の判定は[リリース管理](../repository/release-management.md#版の決まり方)に従う。

Orvalの設定（`frontend/orval.config.ts`）だけを変えたときは、`task api-gen`の代わりに`task api-client-gen`で生成物だけを再生成する。
このTaskはDBもJavaも使わない。

## CIが検査すること

- **pre-commit**：契約、`.spectral.yaml`、Orvalの設定、生成物の変更で、コミット済みの契約のSpectralと`task api-client-check`を実行する。
- **Backend CI**：`task be-test`が書き出した契約を、`task be-openapi-check`でコミット済みの`openapi/openapi.yaml`と比べる。
- **Frontend CI**：`task api-client-check`で生成物を再生成し、コミット済みの内容と比べる。
- **API Contract**（`api-contract.yml`）：`task api-lint`、`task api-lint-rules-test`、`task api-breaking`を実行し、`task api-docs`の設計書をartifactにする。
  mainへのmergeでは、設計書をGitHub Pagesに公開する。

## 検査が失敗したとき

drift（生成し直した内容とコミット済みの内容の差）の検査は、次のメッセージで失敗する。

```text
生成物がコミット済みの内容と一致しません。task api-gen を実行し、差分をコミットしてください。
```

- **`task be-openapi-check`が失敗した**：ControllerやDTO、`OpenApiConfig`を変えたあとに契約を再生成していない。
  `task api-gen`を実行し、`openapi/openapi.yaml`の差分をコミットする。
- **`task api-client-check`が失敗した**：契約かOrvalの設定を変えたあとに生成物を再生成していない、または生成物を手で書き換えてステージした。
  `task api-gen`（設定だけなら`task api-client-gen`）を実行し、差分をコミットする。
- **「未追跡の生成物」と表示された**：新しいtagやschemaの生成ファイルをコミットしていない。
  表示されたファイルを`git add`する。
- **`task api-lint`が失敗した**：契約がSpectralのルールに違反している。
  ルール名から[OpenAPIのアノテーションとJavadoc](openapi-annotations.md)の該当箇所を探し、ControllerかDTOを直して`task api-gen`を実行する。
- **`task api-breaking`が失敗した**：mainの契約と比べて互換でない変更がある。
  互換な形（項目の追加、任意のパラメータ）に直すか、意図した変更なら手順9のラベル、本文、リリース記法を適用する。

ステージしていない手書きの変更だけなら、`task be-test`か`task api-gen`の再生成で上書きされて解消する。
その場合は`git status`で生成物が元に戻ったことを確かめる。
