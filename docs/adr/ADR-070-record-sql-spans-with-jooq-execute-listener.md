---
type: ADR
title: 'ADR-070: SQL の span を jOOQ の ExecuteListener で作り、SQL の文と値を入れない'
description: jOOQ の ExecuteListener で SQL ごとに Micrometer の Observation を作り、SQL の文、バインドの値、例外のメッセージを span に入れない決定。JDBC 全体の計装を採らない理由と、失敗した SQL の span の status の扱いも含む。
tags: [adr, observability, backend, jooq, opentelemetry]
---

# ADR-070: SQL の span を jOOQ の ExecuteListener で作り、SQL の文と値を入れない

## Status

Proposed

## Date

2026-10-07

## Context

Issue #151 は、画面の API 呼び出し、Spring Boot の処理、SQL を Grafana の Tempo で一本の trace として見られるようにする。
ブラウザから Spring Boot までは、W3C Trace Context の `traceparent` と Micrometer Tracing の標準の伝播でつながる（[ADR-068](ADR-068-collect-browser-telemetry-with-faro-via-collector.md)）。
一方、Spring Boot 4.1 の標準の構成には SQL の span を作る仕組みがない。
`JooqAutoConfiguration` は `ExecuteListenerProvider` の Bean を jOOQ の設定に入れるが、SQL の観測は行わない。

SQL の span には次の制約がある。

- SQL は発生源で渡さない値である（[可観測性データの規約](../observability/conventions.md) の「発生源で渡さない値」）。
  バインドの値は利用者の入力を含みうる。
- バックエンドの traces は Collector で加工しない（[ADR-015](ADR-015-structure-and-protect-observability-data.md)）。
  そのため、span に入れた値は保存先までそのまま届く。
- 業務の DB アクセスは jOOQ で行い（[ADR-003](ADR-003-adopt-jooq-for-data-access.md)）、jOOQ の技術的な共通処理は `shared` に置く（[ADR-048](ADR-048-add-shared-module-for-jooq-common-code.md)）。
- PostgreSQL のエラーのメッセージは、制約違反の行の値を含みうる。

## Decision

jOOQ の `ExecuteListener` で、jOOQ が発行する SQL ごとに Micrometer の `Observation`（名前は `jooq.query`）を作る。
実装は jOOQ の共通処理として `shared.infrastructure.persistence` の `JooqObservationConfig` に置く（ADR-048）。

- span の名前（contextual name）は SQL の種類（`READ`、`WRITE`、`DDL`、`BATCH`、`ROUTINE`、`OTHER`）にする。
- low cardinality の key value として `db.system.name=postgresql` を付ける。
- SQL の文、バインドの値、例外のメッセージは、どの key value にも入れない。
- 失敗した SQL には、例外のクラス名だけを `error.type` として付け、`Observation.error` は呼ばない。
  `Observation.error` は例外のメッセージを span の event に残すためである。
- 親は registry の現在の observation（HTTP の server の observation など）から決まる。
  scope は開かず、SQL の実行中も現在の span を変えない。

## Consequences

### Positive

- 画面の API 呼び出しから SQL までが同じ trace ID でつながり、どの要求でどの種類の SQL が何回、何秒かかったかを Tempo で見られる。
- SQL の文と値が span に入らないので、Collector で消す処理に頼らずに済む。
- 新しい依存を足さない（jOOQ と、`spring-boot-starter-opentelemetry` が持つ Micrometer Observation だけを使う）。

### Negative

- JDBC を直接使う処理（Spring Modulith の event publication の `JdbcTemplate` など）の SQL は span にならない。
- span に SQL の文がないので、遅い query を trace から特定できない。
  文で探すときは、`pg_stat_statements` などの DB 側の統計を使う。
- 失敗した SQL の span の status は ERROR にならず、UNSET のままになる。
  失敗は `error.type` の有無で見分ける。
- 記録する例外のクラスは、Spring Boot の `ExceptionTranslatorExecuteListener` との順序で、jOOQ の例外か Spring の変換後の例外かが変わる。
  順序は固定しない。

### Neutral

- HTTP の要求の外（Spring Modulith のイベントの listener、起動時の処理など）の SQL は、親のない trace になる。
- Observation は span と一緒に `jooq.query` の timer のメトリクスも作る。
  tag は `db.system.name` と `error.type`（と Micrometer が足す `error`）で、値の数は少ない。

## Alternatives Considered

### 選択肢1: datasource-micrometer

- **Description**：`net.ttddyy.observation:datasource-micrometer-spring-boot` で DataSource を包み、JDBC の接続と SQL を観測する。
- **Pros**：jOOQ を通さない JDBC の SQL も span になる。
- **Cons**：SQL の文を tag に入れ、外す設定がない。依存が増える。

### 選択肢2: OpenTelemetry の JDBC の計装

- **Description**：OpenTelemetry Instrumentation の JDBC のライブラリで DataSource を包む。
- **Pros**：OpenTelemetry の semantic conventions に沿った属性を付ける。
- **Cons**：`db.query.text` に正規化した SQL の文を入れる。依存が増える。

### 選択肢3: Collector で SQL の文を消す

- **Description**：JDBC の計装を入れ、バックエンドの traces の pipeline の transform で SQL の文の属性を消す。
- **Pros**：計装の選択肢が広がる。
- **Cons**：SQL は発生源で渡さない値であり、発生源で入れない計装を選べる。バックエンドの traces を加工しない方針（ADR-015）も変える必要がある。

## References

- [#151: 2/4 画面の操作から DB までを一本のトレースで見られるようにする](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/151)
- [jOOQ の ExecuteListener](https://www.jooq.org/doc/3.21/manual/sql-execution/execute-listeners/)
- [Micrometer Observation](https://docs.micrometer.io/micrometer/reference/observation.html)
- [ADR-015: 可観測性データを構造化し保護する](ADR-015-structure-and-protect-observability-data.md)
- [ADR-003: データアクセスに jOOQ を採用](ADR-003-adopt-jooq-for-data-access.md)
- [ADR-048: jOOQ の共通処理を共有モジュール shared に置く](ADR-048-add-shared-module-for-jooq-common-code.md)
- [ADR-068: ブラウザのテレメトリを Faro Web SDK で集め、同一オリジンの /collect から Collector へ送る](ADR-068-collect-browser-telemetry-with-faro-via-collector.md)
- [可観測性データの規約](../observability/conventions.md)
