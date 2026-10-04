---
type: ADR
title: 'ADR-055: DB のロック待ち、文の実行、トランザクション中の待機の上限を接続ごとに設定する'
description: アプリの DB 接続ごとに、HikariCP の connection-init-sql で lock_timeout、statement_timeout、idle_in_transaction_session_timeout を環境変数の値で設定する決定。postgresql.conf では設定せず、lock_timeout を statement_timeout より短くする。
tags: [adr, backend, database, postgresql, resilience]
---

# ADR-055: DB のロック待ち、文の実行、トランザクション中の待機の上限を接続ごとに設定する

## Status

Proposed

## Date

2026-10-03

## Context

[ADR-054](ADR-054-detect-optimistic-lock-conflicts-by-update-count.md) で、楽観的ロックを UPDATE の条件の `lock_no` と更新件数で判定する方式に変えた。
`SELECT ... FOR UPDATE NOWAIT` を使わなくなったため、他のトランザクションが更新中の行への UPDATE は、PostgreSQL の既定では無期限にロックを待つ。

長く実行される文と、トランザクションを開いたまま待つセッションも、接続と行のロックを持ち続ける。
[ADR-019](ADR-019-define-resilience-and-capacity-guardrails.md) は HikariCP の pool を DB への同時実行の上限にし、接続取得の待機上限を 5 秒にしている。
接続を持ち続ける要求があると、他の要求は接続を待って失敗する。
ADR-019 の Neutral は PostgreSQL の通信を各 starter の timeout に任せているが、PostgreSQL の `lock_timeout`、`statement_timeout`、`idle_in_transaction_session_timeout` の既定値は 0（無効）である（[PostgreSQL, Client Connection Defaults](https://www.postgresql.org/docs/18/runtime-config-client.html)）。

PostgreSQL の文書は、`statement_timeout` と `lock_timeout` を `postgresql.conf` で設定するとすべてのセッションに効くため勧めていない。
アプリの接続では、HikariCP の `connection-init-sql` が新しい物理接続ごとに `SET TIME ZONE 'UTC'` をすでに実行している（[ADR-006](ADR-006-utc-instant-absolute-time-policy.md)）。
`connectionInitSql` は接続を pool に加える前に実行され、失敗すると接続の失敗として扱われる（[HikariCP, Configuration](https://github.com/brettwooldridge/HikariCP#infrequently-used)）。

`docs/nfr/` に DB に固有の時間の目標はない。
決める材料は、ADR-019 の値（接続取得 5 秒、外部呼び出しの接続 1 秒と全体 2 秒、`retry` 1 回）、[性能テストの目標値](../performance-test/performance-targets.md)の更新系の目標値（コア 500 ミリ秒、標準と管理 800 ミリ秒）、[非機能要件の適用範囲と合意相手](../nfr/scope-and-stakeholders.md)の自システム内の処理を 2 秒以内にする例、`application.yaml` の `timeout-per-shutdown-phase: 30s` である。

## Decision

アプリの DB 接続ごとに、`spring.datasource.hikari.connection-init-sql` で次の三つを設定する。

```yaml
connection-init-sql: >-
  SET TIME ZONE 'UTC';
  SET lock_timeout = ${DB_LOCK_TIMEOUT_MS};
  SET statement_timeout = ${DB_STATEMENT_TIMEOUT_MS};
  SET idle_in_transaction_session_timeout = ${DB_IDLE_IN_TRANSACTION_TIMEOUT_MS}
```

- 値はミリ秒の整数で、環境変数 `DB_LOCK_TIMEOUT_MS`、`DB_STATEMENT_TIMEOUT_MS`、`DB_IDLE_IN_TRANSACTION_TIMEOUT_MS` から渡す。
  `application.yaml` には既定値を書かない（[ADR-008](ADR-008-single-application-yaml-external-config.md)）。
- `lock_timeout` は `statement_timeout` より短くする。
  同じか長いと、ロック待ちより先に `statement_timeout` が効き、ロック待ちの上限として働かない。
- `postgresql.conf`、RDS のパラメータグループ、`ALTER ROLE ... SET` では設定しない。
- 5 秒を超えて実行する正当な理由があるトランザクション（レポートやバッチ）は、そのトランザクションだけ `SET LOCAL statement_timeout` で上限を上げ、理由をコードに書く。
  その仕組みは、該当する処理ができるまで作らない。

ローカル（`.env.example`）とテスト（`.env.test`）の開始値は、次の三つにする。
本番の値は、`DB_POOL_*` と同じく負荷試験で決める。

- **`DB_LOCK_TIMEOUT_MS=1000`**：更新系の目標値 800 ミリ秒より長いため、通常の更新 1 件の後ろで待つ要求は成功する。
  `NOWAIT` の即時の失敗を短い待ちに置き換え、自システム内の 2 秒の予算に収まる。
- **`DB_STATEMENT_TIMEOUT_MS=5000`**：接続取得の待機上限と同じにし、1 つの要求の文が、他の要求が接続を待てる時間より長く接続を持たないようにする。
  `lock_timeout` より長い。
- **`DB_IDLE_IN_TRANSACTION_TIMEOUT_MS=10000`**：`ChargeOrderCommandHandler` は `@ApplicationModuleListener` のトランザクションの中で決済を呼び、その間（全体 2 秒と接続 1 秒）はトランザクション中の待機になる（[ADR-050](ADR-050-define-backend-class-roles-and-naming.md)）。
  この時間より十分に長く、停止時の 30 秒より短くする。

ADR-054 の方式では、行のロックは決済を呼んだ後の `update` の UPDATE が取る。
そのため、`ChargeOrderCommandHandler` は決済を呼んでいる間に行のロックを持たず、`idle_in_transaction_session_timeout` が守るのは接続とトランザクションだけである。

## Consequences

### Positive

- ロック待ち、長い文、開いたままのトランザクションが、接続と行のロックを無期限に持たない。
- 値を環境変数で渡し、未設定ならアプリの起動に失敗するため、環境ごとの設定漏れに気付ける。
- ロック待ちが `lock_timeout` で終わり、楽観的ロックの UPDATE は競合として返る（ADR-054）。

### Negative

- 値は新しい物理接続にだけ効くため、変えたらアプリを再起動する。
- `lock_timeout` で失敗した文は SQLSTATE `55P03` になる。
  楽観的ロックの UPDATE は競合として扱うが、それ以外の文は今は 500 になる。
- `statement_timeout` で取り消された文は SQLSTATE `57014` になり、今は 500 になる。
- `idle_in_transaction_session_timeout` を超えたセッションは SQLSTATE `25P03` で終了し、HikariCP はその接続を捨てる。
- 正当に 5 秒を超える処理を作るときは、`SET LOCAL` を書く手間が増える。

### Neutral

- Liquibase のマイグレーションと jOOQ のコード生成は、Gradle からマイグレーション用ロールで接続し、HikariCP を通らないため、この上限を受けない。
- 本番の値は負荷試験と、決済などの外部連携の時間予算から決め直す。

## Alternatives Considered

### 選択肢1: postgresql.conf か RDS のパラメータグループで設定する

- **Description**：DB クラスタの設定で三つの上限を決める。
- **Pros**：アプリの設定を変えずに、すべての接続に効く。
- **Cons**：マイグレーション、運用、監視の接続にも効き、長い DDL やバッチが止まる。
  PostgreSQL の文書も、`statement_timeout` と `lock_timeout` をこの方法で設定することを勧めていない。

### 選択肢2: ALTER ROLE ... SET でアプリのロールに設定する

- **Description**：アプリのロール `DB_USERNAME` に既定の値を設定する。
- **Pros**：アプリのロールの接続だけに効く。
- **Cons**：値が DB の中にあり、環境変数とリポジトリのレビューで管理できない。
  ローカルとテストでは initdb、本番では RDS で、それぞれ設定する手順が要る。

### 選択肢3: JDBC の URL の options で渡す

- **Description**：`jdbc:postgresql://...?options=-c lock_timeout=1000` のように接続パラメータで渡す。
- **Pros**：接続の開始時に効き、SQL を実行しない。
- **Cons**：URL は `DB_HOST`、`DB_PORT`、`DB_NAME` から組み立てており、エスケープした値が URL に混ざる。
  タイムゾーンの固定と設定の場所が分かれる。

### 選択肢4: トランザクションごとに SET LOCAL で設定する

- **Description**：必要な処理だけが `SET LOCAL` で上限を決める。
- **Pros**：処理ごとに適した値にできる。
- **Cons**：書き忘れた処理は無期限に待つ。

### 選択肢5: @Transactional(timeout) か JDBC の setQueryTimeout を使う

- **Description**：Spring か JDBC のタイムアウトで文を取り消す。
- **Pros**：アプリの中で完結する。
- **Cons**：ロック待ちだけを短く打ち切れず、トランザクション中に待つセッションも終わらせられない。

## References

- [ADR-006: 絶対時刻を UTC / Instant / timestamptz に統一する](ADR-006-utc-instant-absolute-time-policy.md)
- [ADR-008: application.yaml を単一にし、設定を外部から注入する](ADR-008-single-application-yaml-external-config.md)
- [ADR-019: 外部連携の耐障害性と容量制御を標準化する](ADR-019-define-resilience-and-capacity-guardrails.md)
- [ADR-050: バックエンドのクラスの役割と命名を定める](ADR-050-define-backend-class-roles-and-naming.md)
- [ADR-054: 楽観的ロックの競合を UPDATE の条件の lock_no と更新件数で判定する](ADR-054-detect-optimistic-lock-conflicts-by-update-count.md)
- [DB接続情報とロール分離](../database/connections.md)
- [PostgreSQL の排他制御](../database/postgresql-concurrency-control.md)
- [PostgreSQL, Client Connection Defaults](https://www.postgresql.org/docs/18/runtime-config-client.html)
- [HikariCP, Configuration](https://github.com/brettwooldridge/HikariCP#infrequently-used)
