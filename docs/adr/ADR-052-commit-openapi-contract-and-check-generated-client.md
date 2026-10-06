---
type: ADR
title: 'ADR-052: OpenAPI 契約をリポジトリにコミットし、生成物と破壊的変更を CI で検査する'
description: springdoc が生成する OpenAPI 契約を openapi/openapi.yaml にコミットし、Orval の生成物もコミットしたうえで、drift、Spectral、oasdiff を pre-commit と CI で検査し、info.version を契約の版として手で管理する決定。
tags: [adr, api, openapi, frontend, ci]
---

# ADR-052: OpenAPI 契約をリポジトリにコミットし、生成物と破壊的変更を CI で検査する

## Status

Proposed

## Date

2026-10-03

## Context

バックエンドは springdoc-openapi で、Controller から OpenAPI 3.1 契約を生成する（[ADR-013](ADR-013-standardize-http-api-contracts.md)）。
現在の契約は、テスト `OpenApiContractTest` が `/v3/api-docs` を取得し、Gradle の `exportOpenApi` タスクが `backend/build/openapi/openapi.json` へ書き出す。
この JSON はビルドのたびに作り直す一時ファイルで、リポジトリにはコミットしていない。

フロントエンドは Orval で API client を生成すると決めている（[ADR-024](ADR-024-adopt-orval-for-frontend-api-client.md)）。
ただし ADR-024 は、契約の snapshot の置き場所、生成 Task、drift 検査、生成物の Git 管理を、最初の業務 API まで先送りしている。
この先送りのままでは、業務 API を足す変更のたびに、開発者が置き場所と手順を個別に決めることになる。

ADR-013 は、Spectral を破壊的変更の検出に使わず、独立クライアントが生じた時点で main の OpenAPI との差分検査を CI に足すと決めている。
しかし、生成した OpenAPI を API 設計書としても使うため、互換でない変更を PR の時点で見えるようにしたい。
SPA と同時に配備する間も、契約の変更履歴を Git の差分で読めることには価値がある。

OpenAPI の `info.version` は、仕様では OpenAPI 文書の版であり、記述対象のアプリの版とは別である（[OAS 3.1.1 Info Object](https://spec.openapis.org/oas/v3.1.1.html#info-object)）。
現在は `OpenApiConfig` に `0.0.1` を固定しており、版の上げ方を決めていない。
[APIの互換性と廃止](../web-api/versioning.md) は、API の版をリリース版の番号と連動させないと定めている。

## Decision

### 契約のファイル

- springdoc が生成する契約を、リポジトリルートの `openapi/openapi.yaml` に YAML でコミットする。
- springdoc の `writer-with-order-by-keys` でキーの順序を固定し、生成のたびに同じ内容のファイルになるようにする。
- 生成には既存の `exportOpenApi` を流用し、出力先を `openapi/openapi.yaml` に変える。
  新しい Gradle プラグインは入れない。
  CI と `task test` での書き出しは、全テストを実行する `test` タスクが行う（[ADR-064](ADR-064-write-openapi-contract-from-test-task-in-ci.md)）。
- `openapi/openapi.yaml` は生成物として扱い、手で編集しない。

### Orval

- 設定は `frontend/orval.config.ts` に置き、[OrvalとAPI境界](../frontend/api-client-orval.md) の設計どおり tags-split、react-query、fetch、clean、MSW の mock を使う。
- 入力は `../openapi/openapi.yaml` にする。
- 生成物 `frontend/src/api/generated/**` はコミットし、`routeTree.gen.ts` と同様に fmt、lint、knip、jscpd、coverage の対象から外す。

### Task

- `task api-gen`：テスト用の依存を起動し、契約の生成、Spectral の検査、Orval の再生成を実行して、依存を片付ける。
- `task api-check`：生成し直した契約と生成物が、コミット済みの内容と一致するかを `git diff --exit-code` で検査する。
- `task api-breaking`：docker の oasdiff（digest で固定）で `origin/main` の契約と比べ、`--fail-on ERR` で破壊的変更を失敗にする。
  base に契約がなければ比較をとばす。
- `task api-docs`：docker の Redocly CLI（digest で固定、`REDOCLY_TELEMETRY=off`）の `build-docs` で静的 HTML の設計書を作る。
- 開発者が API を変えたときに実行するコマンドは `task api-gen` の一つにする。
  pre-commit での自動再生成と watch は作らない。

### ガード

違反はすべてビルドの失敗にする。

- Lefthook の pre-commit は、DB の要らない検査だけを実行する。
  コミット済みの契約の Spectral と、Orval で再生成した生成物に差分がないことの二つである。
- CI は、契約の drift、生成物の drift、Spectral、oasdiff を検査する。
- oasdiff は新しい workflow `api-contract.yml` で実行する。
  ラベルの付け外しで再判定するため、`pull_request` の types に `labeled` と `unlabeled` を含める。
- `api-contract.yml` のジョブを必須チェックに加え、[main ブランチの保護設定](../repository/branch-protection.md) を更新する。

### 破壊的変更の例外

- 意図した破壊的変更は、PR にラベル `api-breaking-approved` を付け、PR 本文に理由を書けば通す。
  このとき oasdiff は実行して結果をログに残し、失敗にしない。
- ラベルの有無は `${{ }}` を `run:` に直接埋めず、`env` で渡す（zizmor の template injection 対策）。
- CODEOWNERS とレビュー必須の設定は変えない。

### 設計書の公開

- Redocly で作った静的 HTML を、PR では CI の artifact にし、main へのマージで GitHub Pages に公開する。

### info.version

- `info.version` は契約の版として手で管理し、アプリのリリース版と連動させない。
- 初期値は `0.1.0` にする。
  互換な追加で MINOR、破壊的変更で MAJOR を上げ、`/api/v1` を導入するときに `1.0.0` にする。
- アプリの版が必要なら、契約ではなく `/actuator/info` で返す。
- Orval の `override.header` で、生成物の header から版の行を外す。
- MINOR の上げ忘れは機械で検出できないため、PR のチェックリストで確認する。

### 既存の ADR との関係

- この ADR は、ADR-024 が先送りした契約の snapshot の置き場所、生成 Task、drift 検査、生成物の Git 管理を確定する。
- ADR-013 の「独立クライアントが生じた時点で main ブランチの OpenAPI との差分検査を CI に追加する」を変更し、今から oasdiff で検査する。

## Consequences

### Positive

- 契約の変更が PR の差分として読め、API 設計書の変更履歴が Git に残る。
- バックエンドの変更で生成 client が古くなると、pre-commit と CI が失敗するため、フロントエンドの型と契約のずれに気付ける。
- 意図しない破壊的変更を PR の時点で検出でき、意図した変更はラベルと理由で記録できる。
- Orval と Redocly は DB なしで動くため、フロントエンドの開発者は Java を起動せずに client を再生成できる。
- `info.version` がリリースのたびに変わらないため、契約と生成物に版だけの差分が出ない。

### Negative

- 契約を変えるたびに、契約と生成物を同じコミットに含める手間が増える。
- `task api-gen` はテスト用の PostgreSQL と Redis を起動するため、数十秒以上かかる。
- 生成物をコミットするため、リポジトリと PR の差分が大きくなる。
- ラベルは triage 権限があれば誰でも付けられ、ruleset は承認なしの merge を許す。
  例外の承認は規約にとどまり、強制できない。
- `info.version` の MINOR の上げ忘れは機械で検出できない。

### Neutral

- 業務 API がない間、コミットする契約の `paths` は空である。
- 必須チェックの追加と GitHub Pages の有効化は、リポジトリの所有者が設定する。
- レビュー担当者が二人以上になったら、CODEOWNERS に `/openapi/` を足し、コード所有者のレビューを必須にするかを判断し直す。

## Alternatives Considered

### 選択肢1: 契約を frontend/openapi/openapi.json に置く

- **Description**：Orval の入力に近い `frontend/` の下に JSON で置く。
- **Pros**：フロントエンドだけで生成が完結し、パスが短くなる。
- **Cons**：契約の生成元はバックエンドで、Spectral と oasdiff も契約全体を検査するため、片方の下に置くと所有が曖昧になる。
  JSON は YAML より差分を読みにくい。

### 選択肢2: springdoc-openapi-gradle-plugin を使う

- **Description**：プラグインがアプリを起動し、`/v3/api-docs` を取得してファイルへ書き出す。
- **Pros**：springdoc の公式の手段で、設定が少ない。
- **Cons**：`exportOpenApi` が同じことをテストの中で済ませており、プラグインを足すとアプリの起動方法と依存が二系統になる。

### 選択肢3: 契約をコミットしない

- **Description**：CI とローカルで毎回生成し、ファイルを Git に残さない。
- **Pros**：生成物の差分とコミットの手間がない。
- **Cons**：PR で契約の変化を読めず、oasdiff の base を作るために main でも Java とテスト用 DB を起動する必要がある。

### 選択肢4: 起動中の bootRun から curl で取得する

- **Description**：旧リポジトリ spring-modulith-ai-harness の方式で、開発者が起動したアプリから `curl` で取得する。
- **Pros**：Gradle の設定が要らない。
- **Cons**：手元の起動状態と設定によって出力が変わり、CI で同じ手順を再現しにくい。

### 選択肢5: info.version をアプリのリリース版と連動させる

- **Description**：release-please が更新するアプリの版を `info.version` に埋め込む（調査の案 C-2）。
- **Pros**：契約がどのリリースに対応するかが分かる。
- **Cons**：[APIの互換性と廃止](../web-api/versioning.md) と衝突し、API が変わらないリリースでも版が上がる。
  release-please の PR は契約を再生成しないため、drift 検査が失敗しうる。

### 選択肢6: info.version に major だけを書く

- **Description**：パスの版と同じ `0` や `1` だけを持ち、ほぼ変えない（調査の案 C-3）。
- **Pros**：版を上げる手間がない。
- **Cons**：semver の形にならず、互換な追加の履歴が残らない。

### 選択肢7: ラベルに加えて CODEOWNERS の承認を必須にする

- **Description**：`/openapi/` をコード所有者の対象にし、破壊的変更にレビューの承認を求める。
- **Pros**：例外の承認を GitHub の設定で強制できる。
- **Cons**：作成者以外のレビュー担当者がいないため、承認を必須にすると merge できなくなる。

### 選択肢8: oasdiff-action を使う

- **Description**：GitHub Actions の oasdiff-action で差分を検査する。
- **Pros**：PR へのコメントなどの機能を設定だけで使える。
- **Cons**：既定の `review: true` で差分を oasdiff.com へ送る。
  README の例は浮動の tag で、SHA で固定しても Taskfile とローカルで同じコマンドにならない。

### 選択肢9: 静的 HTML を Scalar CLI で作る

- **Description**：Scalar CLI で設計書の HTML を作る。
- **Pros**：表示が新しく、試行の機能がある。
- **Cons**：単体の HTML にするには CDN の script を読む形になる可能性があり、調査の時点で確認できていない。

### 選択肢10: pre-commit で契約と生成物を自動で再生成する

- **Description**：Controller を変えたコミットで、hook が `task api-gen` を実行する。
- **Pros**：開発者が再生成を忘れない。
- **Cons**：テスト用 DB の起動で pre-commit が遅くなり、hook がファイルを書き換えると、ステージした内容とコミットされる内容がずれる。

## References

- [ADR-013: HTTP API 契約を標準化する](ADR-013-standardize-http-api-contracts.md)
- [ADR-024: Frontend API client 生成に Orval を採用する](ADR-024-adopt-orval-for-frontend-api-client.md)
- [ADR-053: OpenAPI の説明を Javadoc から生成し、アノテーションを最小限にする](ADR-053-document-openapi-from-javadoc-with-minimal-annotations.md)
- [APIの互換性と廃止](../web-api/versioning.md)
- [OAS 3.1.1 Info Object](https://spec.openapis.org/oas/v3.1.1.html#info-object)
- [oasdiff](https://github.com/oasdiff/oasdiff)
- [oasdiff-action](https://github.com/oasdiff/oasdiff-action)
- [Redocly CLI build-docs](https://redocly.com/docs/cli/commands/build-docs)
- [Orval output configuration](https://orval.dev/docs/reference/configuration/output)
