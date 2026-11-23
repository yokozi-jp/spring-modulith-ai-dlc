---
type: Convention
title: バッチのテスト観点
description: 業務のバッチの単体テスト（期間の範囲、境界の時刻、引数の検査）、spring-batch-test で Job と Step を起動する結合テストの観点（2 回流す、0 件、日回し、追いつき、再開、テナントの分離、同時に 2 本）、未起動と遅延の検知、性能テストの観点を定める規約。Job のテストを書くとき、Job のテストが足りているかを確かめるときに読む。
tags: [convention, batch, spring-batch, testing, future-arch-guidelines]
---

# バッチのテスト観点

期間の範囲と境界の時刻は、純粋な関数の単体テストで確かめる。
Job の振る舞いは、`spring-batch-test` で Job と Step を起動する結合テストで、`Clock` を進めながら確かめる。
起動元のワークフローやスケジューラを通さず、Job を直接起動する。
決定の理由は [ADR-115](../adr/ADR-115-build-business-batches-with-spring-batch.md) に示す。

## 単体テスト

- 追いつきの Runner が期間を求める純粋な関数（[定時起動と追いつき](scheduling-and-catch-up.md#追いつきの-runner)）を、最後の期間、最初の期間、起動した時刻の組で確かめる。
  JobInstance がないとき、最後の期間が COMPLETED のとき、COMPLETED でないとき、流す期間がないときを含める。
- 期間の境界の時刻は、業務タイムゾーンで境界ちょうど、その 1 マイクロ秒前、1 マイクロ秒後の 3 点で確かめる。
  絶対時刻はマイクロ秒精度で持つ（[日時とタイムゾーンの規約](../datetime/timezone-conventions.md#精度)）。
- 業務タイムゾーンに夏時間があるときは、夏時間の始まりと終わりをまたぐ期間を含める（[日時とタイムゾーンの規約](../datetime/timezone-conventions.md#テスト)）。
- `JobParametersValidator` が、不正な引数で Job を流さないことを確かめる。

## 結合テスト

結合テストは、`spring-boot-starter-batch-jdbc-test` の `spring-batch-test` の `@SpringBatchTest` と `JobOperatorTestUtils` で Job と Step を起動する。
DB はほかの DB のテストと同じく Testcontainers の PostgreSQL にし（[バックエンドのテストの実行基盤](../backend/testing-infrastructure.md)）、メタデータの表も changeset で作る。
現在時刻は、テストが進められる `Clock` から取り、テストの中で日を進める。

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
- **2 つのテナントの分離**：2 つのテナントの行を置き、各テナントの結果に、ほかのテナントの行が入らない。
  1 つのテナントの partition を失敗させても、もう 1 つのテナントの partition は COMPLETED になる。
- **同時に 2 本**：同じ JobInstance を 2 つのスレッドで同時に起動し、1 本が `JobExecutionAlreadyRunningException` で止まり、業務の表の結果が 1 本だけ流したときと同じになる。
  同じ行を同時に 2 回書くときの考え方は、[順序保証と冪等性](../integration/async-ordering-and-idempotency.md#冪等なコンシューマー) と同じである。

## 未起動と遅延の検知

- `batch.job.period.lag`（[定時起動と追いつき](scheduling-and-catch-up.md#未起動と遅延の検知)）が、COMPLETED でない期間の数を返すことを確かめる。
  起動しなかった期間、失敗した期間、流している途中の期間で 1 以上になり、追いついたら 0 になる。

## 性能テスト

- バッチの処理時間に加え、DB の読み書きの IOPS、CPU、メモリを測る。
- 期待する性能が出ないときは、1 回の `handle` にまとめる行の数を変えて測る。
- オンラインのピークと Job の実行が重なっても、オンラインの性能が大きく落ちないことを、ロングランテストで確かめる。
- 計画と目標値は [性能テスト](../performance-test/index.md) の規約に従い、目標はバッチウィンドウから決める。

## 出典

- フューチャー株式会社「バッチ設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forBatch/batch_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
