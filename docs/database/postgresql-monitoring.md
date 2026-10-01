---
type: Convention
title: PostgreSQLの監視
description: PostgreSQLのサーバーログの扱い、監視するメトリクスとしきい値の目安、通知後の対応の決め方、スロークエリの記録、認証失敗の検知を定める規約。DBの監視とアラートを設定するとき、スロークエリを調査できるようにするときに読む。
tags: [convention, database, postgresql, monitoring, observability, future-arch-guidelines]
---

# PostgreSQLの監視

サーバーログはERROR以上を出力し、監視の対象にはしない。
メトリクスはスロークエリ、CPU、メモリ、ストレージ、接続数、接続の滞留を監視する。
アラートを設定するときは、通知後の対応と終了条件を同時に決める。

## サーバーログ

- サーバーのメッセージはERROR以上をログに出力する。
- サーバーのメッセージのログは監視の対象にしない。DBアクセスの失敗はアプリケーションで検知できるためである。
- サーバーのログも監視する場合は、アプリケーションでの検知と重複して通知しないよう設計する。

DBの死活は、アプリケーションのヘルスチェックなどの外形監視で検知し、DB単体の死活監視は行わない。

## メトリクス

次の項目を監視する。
しきい値は目安であり、発生の頻度に応じて調整する。

- **スロークエリ**：`pg_stat_statements`で、オンライン処理は1分、定時処理は10分などと区別して検知する。
- **CPU使用率**：90%以上が一定時間続いた場合。
- **空きメモリ**：5%未満が一定時間続いた場合。
- **ローカルストレージの空き容量**：10%未満になった場合。
- **ストレージの使用量**：予算の見積もりに使った容量を大きく超えた場合。大きなテーブルの複写などを早く検知する。
- **接続数**：`max_connections`の95%などを超えた場合。
- **接続の滞留**：保守運用ユーザーの接続が3分などを超えて残っている場合。`pg_stat_activity`を定期的に参照して検知する。

```sql
SELECT pid, usename, application_name, client_addr, state, backend_start,
       now() - backend_start AS connection_duration
FROM pg_stat_activity
WHERE usename LIKE '%\_ope\_%'
  AND now() - backend_start > interval '3 minutes';
```

保守運用ユーザーの命名は[PostgreSQLのロールと監査](postgresql-roles-and-audit.md)に従う。

次の場合は、監視を省いてよい。

- 処理の終了期限の検査で代替できる場合は、性能のためのスロークエリ、CPU、メモリの監視。
- 費用の監視を別に行っている場合は、自動で拡張されるストレージの使用量の監視。
- アプリケーションの性能監視とエラーログで確認できる場合は、接続数の監視。

## 通知

アラートを設定するときは、通知を受けた後の対応と、終了とする条件を同時に決める。
通知されても誰も対応しない状態を防ぐためである。

- **CPU使用率**：原因の機能やSQLを特定して対策を取る。翌営業日に再現しなければ終了する。
- **ストレージの空き容量**：ログの改廃の設定を確認し、正常ならインスタンスのサイズを上げて関係者へ共有する。翌営業日に再現しなければ終了する。

`pg_stat_activity`を参照する検査は、結果をWARNかERRORのログとして出力し、ログの監視から通知する。
監視の経路をアプリケーションのログと揃えるためである。
アプリケーションのログの規約は[可観測性データの規約](../observability/conventions.md)に従う。

## スロークエリの記録

監視とは別に、遅いSQLを後から追跡できるようにする。
複数のSQLを含む処理が全体として遅くなった場合に、どのSQLが遅いかを調べるためである。

- **`log_min_duration_statement`**：`300000`（5分以上のSQLをログに出す）。
- **`auto_explain.log_min_duration`**：`300000`（5分以上のSQLの実行計画をログに出す）。
- **`auto_explain.log_format`**：`text`。

SQLごとの統計は`pg_stat_statements`で分析する。

## 認証失敗の検知

DBへのログインが繰り返し失敗した場合は、サーバーログの認証失敗を集計して通知する。
回数そのものより、普段と異なる動きを検知することを目的にする。

## 出典

- フューチャー株式会社「PostgreSQL設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forDB/postgresql_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
