---
type: Convention
title: 性能チューニングの打ち手
description: 性能テストで特定したボトルネックに対して、アプリケーションの実行環境とJVM、アプリケーションのロジック、DBサーバー、SQLとスキーマの各レイヤーで選ぶ打ち手と、その適用条件を定める規約。ボトルネックを特定した後に対策を選ぶとき、性能のための設定変更やコード変更をレビューするときに読む。
tags: [convention, performance-test, performance, tuning, jvm, postgresql, future-arch-guidelines]
---

# 性能チューニングの打ち手

打ち手は、設定の調整、ロジックの修正、リソースの増強の順に検討し、増強は最適化の後に判断する。
キャッシュと非同期化は、性能テストの段階で新しく持ち込まず、既にある仕組みのパラメータの調整にとどめる。
大量の登録はJDBCのバッチで1,000件ごとに送り、大きなファイルは `COPY` で取り込む。

打ち手は[ボトルネックの特定](bottleneck-analysis.md)で立てた仮説に対して一つずつ適用する。

## 実行環境とJVM

### スケーリング

アプリケーションのCPUやメモリがボトルネックの場合は、リソースの増強を検討する。
アプリケーションはステートレスにするため、スケールアウトを基本の打ち手にする。
1リクエストあたりのリソース消費が大きく、スケールアウトで解消しにくい場合はスケールアップを選ぶ。
増強は費用に直結するため、この文書の他の打ち手を試した後に判断する。

### スレッドと接続プール

リクエストは仮想スレッドで処理するため、同時処理数をスレッドプールの大きさで調整しない（[ADR-019](../adr/ADR-019-define-resilience-and-capacity-guardrails.md)）。
DBを使う処理の同時実行数は、HikariCPの接続プールで制限される。

- 接続プールの大きさは、ADR-019の接続予算の不等式から求めて外部から注入し、同時リクエスト数に合わせて増やさない。
- 接続プールは最小と最大が同じ固定サイズにする。
  HikariCPの `minimum-idle` を明示しないことで固定サイズになる。
- 接続の最大寿命は[PostgreSQLの性能対策と負荷分散](../database/postgresql-performance.md)に従い必ず設定する。

接続を待つリクエストが多いのにDBのCPUに余裕がある場合に限り、接続予算の範囲で接続プールを大きくして測り直す。

### ヒープとMetaspace

- ヒープはコンテナのメモリに対する割合（`-XX:MaxRAMPercentage`）で指定し、絶対値（`-Xmx`）で指定しない。
- 初期ヒープ（`-XX:InitialRAMPercentage`）を最大ヒープと同じ割合にする。
  起動後のヒープの拡張に伴うGCを避け、コンテナのメモリ上限を超えて強制終了される事態を防ぐためである。
- `-XX:MaxMetaspaceSize` でMetaspaceの上限を設定する。
  既定では上限がなく、Spring Bootのようにリフレクションで動的にクラスを生成するフレームワークでは長時間の稼働で増え続けることがある。
  推移は[ロングランテスト](long-run-test.md)で確かめる。

### GCのアルゴリズム

ヒープが数GB程度のWebアプリケーションでは、既定のG1GCから変えない。
ヒープが数十GBを超え、GCの停止時間を極小にしたい場合に限りZGCを検討し、性能テストで検証してから採用する。

### コンテナイメージ

スケールアウトの速さはイメージの取得時間に左右されるため、イメージは[Dockerfile の作り方](../container/dockerfile-conventions.md)に従い小さく保つ。
jlinkによるカスタムランタイムは、Spring Bootでは必要なモジュールを特定しにくく効果も限られるため、オートスケールの立ち上がりが負荷の増加に追いつかないと分かった場合に限り検討する。

## アプリケーションのロジック

ロジックの修正は効果が大きい一方で修正の費用も大きいため、全体の対応方針を決めてから行う。

- **N+1の解消**：一覧で取得した行ごとにループの中で関連データを問い合わせない。
  jOOQでは `JOIN`、`IN` による一括取得、`MULTISET` に書き換える。
- **並行処理**：一つのリクエストの中で独立した複数のI/O（複数の外部API、依存関係のないDBの問い合わせ）を直列に実行している場合は、並行に実行できる。
  例外の扱いとトランザクション境界が複雑になるため、並行処理を採用する条件をシステム全体の方針として決めてから個別の機能に適用する。
- **キャッシュ**：キャッシュの導入と置き場所はアーキテクチャの設計で決めるものであり、[PostgreSQLの性能対策と負荷分散](../database/postgresql-performance.md)の導入条件に従う。
  性能テストの段階では、どのデータを載せるか、TTLをどうするかの調整にとどめる。
- **非同期化**：同期で応答する必要のない処理の非同期化は、[非同期化の判断基準](../integration/async-adoption-criteria.md)に従い設計の段階で決める。
  性能テストの段階では、どの処理を非同期にするか、コンシューマーの並列度をどうするか（[非同期処理の流量制御と性能](../integration/async-flow-control.md)）の調整にとどめる。
- **ページング**：大量の一覧を一度に返している場合は、[ADR-013](../adr/ADR-013-standardize-http-api-contracts.md)のカーソル方式のページングを使う。
  offset方式を例外として使う画面でも、最後のページへの移動のような深いoffsetを避ける。
- **応答項目の絞り込み**：`fields` による絞り込みは、[クエリパラメータ](../web-api/query-parameters.md)の導入条件を満たす場合に限り、項目数が多く転送量の削減の効果が大きいAPIに絞って導入する。
- **ログ**：個々の処理に遅延の要因が見つからないのに性能が伸びない場合は、ログの出力を疑い、[ログの出力コストと量の抑制](../observability/log-performance-and-cost.md)に従って見直す。

## DBサーバー

- **スケーリングとリードレプリカ**：[PostgreSQLの性能対策と負荷分散](../database/postgresql-performance.md)に従う。
  SQLとスキーマが非効率なままリソースだけを増やしても解決しないため、増強は他の打ち手の後に判断する。
- **パラメータ**：`fillfactor`、`shared_buffers`、`work_mem` は[PostgreSQLのサーバー設定と拡張機能](../database/postgresql-server-configuration.md)に従い、性能テストで課題が見つかった時点でレビューで合意してから変える。
- **VACUUMとANALYZE**：更新と削除の多いテーブルで自動バキュームが追いつかない場合は、そのテーブルの `autovacuum_vacuum_threshold` と `autovacuum_vacuum_scale_factor` を調整する。
  大量のデータの投入や削除の後は `ANALYZE` で統計情報を更新する。

## SQLとスキーマ

- **スロークエリの特定**：個別の遅いSQLはスロークエリのログで、閾値に達しないが大量に実行されるSQLは `pg_stat_statements` の実行回数と累積実行時間で見つける。
  設定は[PostgreSQLの監視](../database/postgresql-monitoring.md)に従う。
  ログに出ないSQLは `EXPLAIN (ANALYZE, BUFFERS)` で実行計画を取る。
- **インデックス**：[PostgreSQLの制約とインデックス](../database/postgresql-constraints-and-indexes.md)に従う。
  更新の多いテーブルに過剰なインデックスを張らない。
- **パーティション**：数百万行を超え、改廃が必要なテーブルは[PostgreSQLのパーティションと改廃](../database/postgresql-partitioning-and-retention.md)に従う。
- **SQLの書き方**：`SELECT` で全列を取らず必要な列を指定する。
  否定の条件は `NOT IN` ではなく `NOT EXISTS` で書く。
- **ヒント句**：最後の手段とし、統計情報の更新、インデックスの見直し、SQLの書き換えで解消できない場合に限る。
  使い方は[PostgreSQLの性能対策と負荷分散](../database/postgresql-performance.md)に従う。

### 大量の登録

1件ずつ `INSERT` を送ると、件数分の往復が発生する。
大量の登録では、同じ `INSERT` に複数のパラメータの組を束ねて送るバッチ（JDBCの `addBatch` と `executeBatch`、jOOQのバッチAPI）を使い、1,000件ごとに送る。

一つの `INSERT` に複数行の `VALUES` を書く方式は、SQLの長さとメモリが件数に比例して増えるため、次のどちらかに当たる場合に限る。

- 件数が数十件以下に限られる。
- 計測の結果がバッチを上回り、増えるメモリとCPUを許容できる。

大きくなりうるファイルの取り込みは、[大量データの取り込み性能](../integration/interface-bulk-load.md)に従い `COPY` で設計する。

## 出典

- フューチャー株式会社「はじめての性能テスト」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forPerformanceTest/performance_test.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
