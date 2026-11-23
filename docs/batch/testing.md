---
type: Convention
title: バッチのテスト観点
description: 業務のバッチの単体テスト（期間の一覧、境界の時刻、引数の検査）と、spring-batch-test で Job と Step を起動する結合テストの観点（2 回流す、0 件、日回し、追いつき、再開、失敗と終了コード、止まった期間、テナントの分離、同時に 2 本）を定める規約で、Job のテストを書くときや Job のテストが足りているかを確かめるときに読む。
tags: [convention, batch, spring-batch, testing, future-arch-guidelines]
---

# バッチのテスト観点

期間の一覧と境界の時刻は、純粋な関数の単体テストで確かめる。
Job の振る舞いは、`spring-batch-test` で Job と Step を起動する結合テストで、`Clock` を進めながら確かめる。
起動元のワークフローやスケジューラを通さず、Job を直接起動する。
決定の理由は [ADR-115](../adr/ADR-115-build-business-batches-with-spring-batch.md) に示す。

## 単体テスト

- 追いつきの Runner が期間の一覧を求める純粋な関数（[定時起動と追いつき](scheduling-and-catch-up.md#追いつきの-runner)）を、最後の期間、最初の期間、起動した時刻の組で確かめる。
  JobInstance がないとき、最後の期間が COMPLETED のとき、ABANDONED のとき、それ以外のとき、流す期間がないときを含める。
- 期間が締まる時刻の直前に起動したときはその期間を流さず、直後に起動したときは流すことを確かめる（[期間の規則](scheduling-and-catch-up.md#期間の規則)）。
- 期間の境界の時刻は、業務タイムゾーンで境界ちょうど、その 1 マイクロ秒前、1 マイクロ秒後の 3 点で確かめる。
  絶対時刻はマイクロ秒精度で持つ（[日時とタイムゾーンの規約](../datetime/timezone-conventions.md#精度)）。
- 業務タイムゾーンに夏時間があるときは、夏時間の始まりと終わりをまたぐ期間を含める（[日時とタイムゾーンの規約](../datetime/timezone-conventions.md#テスト)）。
- `JobParametersValidator` が、不正な引数で Job を流さないことを確かめる。

## 結合テスト

結合テストは、`spring-boot-starter-batch-jdbc-test` の `spring-batch-test` の `@SpringBatchTest` と `JobOperatorTestUtils` で Job と Step を起動する。
DB はほかの DB のテストと同じく Testcontainers の PostgreSQL にし（[バックエンドのテストの実行基盤](../backend/testing-infrastructure.md)）、メタデータの表も changeset で作る。
現在時刻は、テストが進められる `Clock` から取り、テストの中で日を進める。

バッチの規約に書いた Spring Batch と Spring Boot の振る舞いは、Spring Boot 4.1.2 と Spring Batch 6.0.6 のソースで確かめた。
最初の Job の結合テストは、下の観点でこの振る舞いを固定し、版を上げたときに変わったことを知らせる。

次の観点を、期間で区切るすべての Job で確かめる。

- **2 回流す**：同じ期間を 2 回流しても、業務の表の結果が 1 回だけ流したときと同じになる。
  2 回目の起動は `JobInstanceAlreadyCompleteException` で止まる。
- **0 件**：処理する行がない期間が COMPLETED になり、0 件の終了のログが出る。
  配信の Job は、ヘッダーだけの空ファイルを配信する。
- **日回し**：`Clock` を 1 日ずつ進めて毎日起動し、3 か月続ける。
  期間の境界の前後 1 マイクロ秒に行を置き、どの行も 1 つの期間だけで処理される。
- **期間を飛ばした後の追いつき**：数日起動しなかった後の 1 回の起動で、飛ばした期間を古い順に流す。
- **途中で落とした後の再開**：chunk の途中の 1 件で例外を投げて Job を FAILED にし、次の起動で同じ JobInstance が再開して COMPLETED になる。
  再開でもう一度渡った行も、結果が 1 回だけ流したときと同じになる。
- **retry で解けない失敗**：retry の上限を超えて競合する行と、業務上の失敗の例外を投げる行で、Step と Job が FAILED になり、後の期間が流れない。
  行を直して次の起動をすると、同じ JobInstance が再開して COMPLETED になり、後の期間も流れる。
- **失敗と終了コード**：Job の名前を渡して起動したアプリで、Job が FAILED になると Runner が `SpringApplication.exit` から終了コード 5 を得る。
  Job を流す前に失敗すると、終了コード 1 になる。
- **止まった期間**：最後の JobExecution を ABANDONED にした期間の次の起動で、Runner がその期間を流さずに次の期間を流す。
- **2 つのテナントの分離**：2 つのテナントの行を置き、各テナントの結果に、ほかのテナントの行が入らない。
  1 つのテナントの partition を失敗させても、もう 1 つのテナントの partition は COMPLETED になる。
  テナントを 1 つ足してから再開しても、失敗したテナントの partition は同じテナントのまま再開する。
- **同時に 2 本**：同じ期間を 2 つのスレッドで同時に起動し、業務の表の結果が 1 本だけ流したときと同じになる。
  2 本目が止まるかどうかは確かめない（[冪等性と再開](idempotency-and-restart.md#二重の起動)）。

## 性能テスト

性能テストは [性能テスト](../performance-test/index.md) の規約に従う。

## 出典

- フューチャー株式会社「バッチ設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forBatch/batch_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
