---
type: ADR
title: 'ADR-064: OpenAPI 契約を test タスクで書き出し、Backend CI のテスト二重実行をやめる'
description: CI と task test では全テストの実行中に OpenApiContractTest が openapi/openapi.yaml を書き出し、続く検査は Spectral と drift だけにする。単体の再生成は exportOpenApi に残す。あわせて成功時のテスト結果の保存と timeout-minutes を加える決定。
tags: [adr, ci, gradle, openapi]
---

# ADR-064: OpenAPI 契約を test タスクで書き出し、Backend CI のテスト二重実行をやめる

## Status

Proposed

## Date

2026-10-06

## Context

[ADR-052](ADR-052-commit-openapi-contract-and-check-generated-client.md) は、契約の生成に Gradle の `exportOpenApi` タスクを流用すると決めた。
`exportOpenApi` は `OpenApiContractTest` だけを実行する別の Test タスクで、`openapi/openapi.yaml` を書き出す。

Backend CI の Test ジョブは、この決定の結果として `OpenApiContractTest` を 2 回実行している。
1 回目は `task be-test` の `./gradlew migrateDatabase test` で、全テストの一部として走る。
2 回目は `task be-openapi-check` が呼ぶ `be-openapi-lint` の `./gradlew exportOpenApi` で、2 つ目の JVM と Spring context を起動する。
この 2 段目は約 52 秒かかり、そのうち 35 秒から 40 秒が JVM と Spring context の起動である（CI 高速化の調査報告）。
ローカルの `task test` も、`be-test` の後に `be-openapi-lint` を呼ぶため、同じ二重実行をしている。

`exportOpenApi` を `test --tests OpenApiContractTest` に置き換える案は採れない。
`test` には `finalizedBy jacocoTestCoverageVerification` があり、1 クラスのカバレッジではブランチカバレッジ 85% を割って失敗する。

`backend/gradle.properties` は `org.gradle.caching=true` と `org.gradle.configuration-cache=true` を有効にしている。
`~/.gradle/caches` は CI の cache 対象で、build cache の `build-cache-1` を含む。
そのため `test` が build cache から復元されると、契約を書くテスト本体は走らない。

## Decision

ADR-052 の「生成には既存の `exportOpenApi` を流用し」を次のように限定する。
CI と `task test` では、全テストを実行する `test` タスクが契約を書き出す。
単体の再生成（`be-openapi-lint` と `task api-gen`）は、これまでどおり `exportOpenApi` を使う。

`test` タスクは、Gradle のプロジェクトプロパティ `openapiExport` が指定されたときだけ、`openapi.output` をシステムプロパティに渡す。
このとき `openapi/openapi.yaml` を `test` の出力に宣言する。
出力に宣言しないと、`test` が build cache から復元されたときに契約が書かれず、drift 検査が素通りになる。
プロパティの読み取りは `providers.gradleProperty` で行い、configuration cache の入力として追跡させる。

Taskfile は次のように変える。

- `be-test` は `./gradlew migrateDatabase test -PopenapiExport=true` を実行する。
- `be-openapi-check` は DB と Java を使わず、`api-lint` と `api-drift-check` だけを実行する。
  前提は、直前に `be-test` が契約を書き出していることである。
- `test` は `be-test` の後に `be-openapi-lint` ではなく `api-lint` を実行する。

素の `./gradlew test` は契約に触れない。
契約を書き換えるのは、`-PopenapiExport=true` を付ける `task be-test` と `task test` に限る。

`backend-ci.yml` は Taskfile を唯一の入口にしたままで、ステップの名前を `Lint OpenAPI contract and check drift` に改める。
必須チェックの名前はジョブの `name` であり、ステップ名は変わっても影響しない。

同じ変更で、CI の 2 つの小さな改善を加える。
Backend CI の `Upload test results` を `always()` にして、成功した run でも JUnit XML を artifact に残す。
Backend CI と Frontend CI のジョブに `timeout-minutes` を加える。
値は `changes` が 5、Backend Lint が 10、Backend Test が 15、Frontend の Verify が 10、手動実行の Mutation Testing が 60 分である。
Mutation Testing は計測がないため余裕を持たせ、計測が貯まったら見直す。

## Consequences

### Positive

- Backend CI の `OpenApiContractTest` が 1 回になり、2 段目の約 35 秒から 40 秒の JVM と Spring context の起動を省ける。
- ローカルの `task test` と pre-push も同じ二重実行をやめる。
- 素の `./gradlew test` は追跡対象の `openapi/openapi.yaml` を書き換えない。
- drift 検査は、`git diff --exit-code` でコミット済みの契約との差を検出する既存の動作を保つ。
- 成功した run のテスト結果が残り、実行時間の推移を後から計測できる。
- ジョブが固まったときに、既定の 6 時間ではなく数分から数十分で打ち切られる。

### Negative

- `task be-test` を単体で実行すると、`openapi/openapi.yaml` が書き換わる。
  これは `be-openapi-lint` や `task api-gen` と同じ、Task 経由の明示的な副作用である。
- `task be-openapi-check` を `be-test` の前に単体で実行すると、コミット済みの契約を自分自身と比べるため、drift を検出できない。
  前提を Task の `desc` と docs に書いた。
- `-PopenapiExport=true` を付けた `./gradlew cleanTest` は、出力に宣言した `openapi/openapi.yaml` を削除する。
  `git checkout -- openapi/openapi.yaml` か、再度の `task be-test` で戻る。
- `test` と `exportOpenApi` は同じファイルを出力に宣言する。
  同じ起動で両方を指定しない。Taskfile にその組み合わせはない。
- システムプロパティの絶対パスが `test` の build cache のキーに入る。
  リモートの build cache は使わず、CI の runner のパスは安定なので、実害はない。
- `timeout-minutes` を超えるジョブは失敗する。
  Mutation Testing の 60 分は推定で、超えたら値を上げる。

### Neutral

- ADR-052 の Status と他の決定は変えない。
  [ADR の運用ルール](conventions.md) に既存の ADR の一部を変更する書式がないため、ADR-052 の該当箇所に、この ADR へのリンクを 1 文足す。
- `exportOpenApi` と `package-info.java` の「書き出し」の語は、意味が変わらないので残す。

## Alternatives Considered

### 選択肢1: test が常に openapi.output を渡す

- **Description**：プロパティを使わず、`test` が毎回契約を書き出す。
- **Pros**：Taskfile に `-PopenapiExport=true` を付ける必要がない。
- **Cons**：素の `./gradlew test` が追跡対象の `openapi/openapi.yaml` を書き換える、隠れた副作用になる。

### 選択肢2: exportOpenApi を test --tests に置き換える

- **Description**：`exportOpenApi` を消し、`be-openapi-lint` を `test --tests OpenApiContractTest -PopenapiExport=true` にする。
- **Pros**：Gradle のタスクが 1 つ減る。
- **Cons**：`--tests` で絞ると `jacocoTestCoverageVerification` が走り、1 クラスのカバレッジで 85% を割って失敗する。

### 選択肢3: 同じ Gradle 起動で test と exportOpenApi を続ける

- **Description**：`./gradlew test exportOpenApi` のように 1 回の起動で両方を実行する。
- **Pros**：Taskfile の構造を変えずに済む。
- **Cons**：2 つ目の JVM と Spring context が残る。節約できるのは Gradle の起動の数秒だけである。

### 選択肢4: 現状を保つ

- **Description**：二重実行を受け入れる。
- **Pros**：変更がなく、追加の前提も生じない。
- **Cons**：Backend CI の Test ジョブが毎回約 52 秒を 2 段目に使う。

## References

- [ADR-052: OpenAPI 契約をリポジトリにコミットし、生成物と破壊的変更を CI で検査する](ADR-052-commit-openapi-contract-and-check-generated-client.md)
- [ADR-063: Gradle の依存キャッシュを MIT の範囲で restore-keys 付きに復元する](ADR-063-restore-gradle-cache-with-restore-keys-on-mit-caching.md)
- [Lintとテストのリファレンス](../tooling/lint-and-test.md)
- [APIを変更する](../web-api/runbook-api-change.md)
