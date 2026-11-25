---
type: ADR
title: 'ADR-128: バックエンドの全体のテストを pre-push で流さず、CI に任せる'
description: pre-push の be-test（task test）が Backend CI と同じ検査を重ねて流し、複数のセッションが同時に push すると 8 コアを飽和させるため、pre-push から外して CI の必須チェックで担保する決定。
tags: [adr, testing, tooling, ci, backend]
---

# ADR-128: バックエンドの全体のテストを pre-push で流さず、CI に任せる

## Status

Proposed

## Date

2026-11-25

## Context

pre-push の `be-test` は `task test` を流す。`task test` は、マイグレーションの検証、`be-test`（テストとカバレッジの検証）、API 契約の検査を順に実行する。
Backend CI の `Test & Coverage` も、同じ 3 つを実行する（`task be-verify-migrations`、`task be-test`、`task be-openapi-check`）。
`CI gate` は `backend-ci` を needs に持ち、`CI gate` はブランチ保護の必須チェックである。バックエンドのテストが壊れた変更は、CI で止まる。

エージェントは worktree ごとに作業し、複数のセッションが同時に push する（[ADR-104](ADR-104-allocate-test-stack-slots-per-run.md)）。
8 コア、23GB の WSL で 3 つのセッションが同時に push したとき、次を測った。

- ロードアベレージは 48.8（1 分平均）に上がった。コアの 6 倍である。
- Gradle の worker は 14 プロセス、Testcontainers は 8 セット（PostgreSQL、WireMock、Ryuk）が動いていた。
- DB の接続が切れ、`AggregateDeclarationInspectionTest` が落ちた。同じテストは、変更を外した main でも 3 件中 2 件落ちた。
- 1 回の pre-push は 355 秒から 20 分かかった。最近の Backend CI は 156〜343 秒で終わる。

fork の既定を 2 に固定して負荷を下げた（#744）。それでも、全体のテストを push のたびに 2 重に流す構造は残る。

## Decision

pre-push からバックエンドの全体のテスト（`be-test`、中身は `task test`）を外す。
バックエンドの全体のテストは、Backend CI と必須チェックの `CI gate` で担保する。
pre-push に残すのは、`be-lint`、ADR 検査、テストの弱体化の警告、betterleaks の履歴スキャン、フロントエンドのテストとビルド、actionlint、zizmor である。

作業の途中は、変えたクラスのテストだけを流す。変えた範囲の外へ影響しうる変更（共通のクラス、DB のマイグレーション、設定）では、push の前に `task verify` を流してよい。

## Consequences

### Positive

- push のたびの 5〜20 分と、8 コアの占有がなくなる。複数のセッションが同時に push しても、互いを遅くしない。
- 負荷で DB の接続が切れて pre-push が落ちる、という誤った失敗が減る。
- `task test` の slot（compose-test）を、pre-push と `task e2e` が取り合わなくなる。

### Negative

- テストの失敗が、push 前ではなく CI で分かる。分かるまでに 3〜6 分かかり、直して push し直す往復が増える。
- テストが壊れた状態の Draft の Pull Request が増える。Draft はレビューを依頼しないので、レビュアーには影響しない。
- Docker を使えない環境の扱い（pre-push で飛ばす）は、不要になる。

### Neutral

- pre-push の `be-lint` は残す。Backend CI の Lint より先に落とせ、約 70〜100 秒で終わるためである。
- 全体のテストを pre-push に戻す必要が出たら、`lefthook.yml` に job を足し戻すだけでよい。

## Alternatives Considered

### 選択肢1: pre-push のテストを残し、fork を減らすだけにする

- **Description**：fork の既定を 2 にした今の状態（#744）で続ける。
- **Pros**：push 前にテストの失敗が分かる。
- **Cons**：1 回の pre-push が CI と同じ検査を重ね、同時の push で 8 コアを占有する構造が残る。

### 選択肢2: 変えたクラスだけを pre-push で流す

- **Description**：`git diff` から対象のテストクラスを選んで流す。
- **Pros**：重複が小さい。
- **Cons**：影響するテストの選び方を実装し、保守する必要がある。選び誤ると、通ったと誤解する。

### 選択肢3: 同時に流す pre-push の数を worktree をまたいで制限する

- **Description**：ロックで、全体のテストを 1〜2 本に絞る。
- **Pros**：重複したまま負荷を抑えられる。
- **Cons**：待ち時間が増える。CI が同じ検査を流すので、待つ価値が小さい。

## References

- [ADR-085](ADR-085-start-test-dependencies-per-fork-with-testcontainers.md)
- [ADR-104](ADR-104-allocate-test-stack-slots-per-run.md)
- [Lintとテストのリファレンス](../tooling/lint-and-test.md)
- [開発のワークフロー](../tooling/dev-workflow.md)
- #744
