---
type: ADR
title: 'ADR-114: 暦日を full-date で受け渡し、SQL で日時から暦日と境界を作らない'
description: API の暦日を UTC の 0 時の瞬間ではなく full-date で受け渡し、暦日と期間の境界を SQL の関数で作らず Java で求めて bind する決定の理由を記録する。Spectral、Jackson の設定、ArchUnit で止める範囲も示す。
tags: [adr, datetime, timezone, web-api, database]
---

# ADR-114: 暦日を full-date で受け渡し、SQL で日時から暦日と境界を作らない

## Status

Proposed

## Date

2026-11-23

## Context

[日時とタイムゾーンの規約](../datetime/timezone-conventions.md)は、日付だけの値を`LocalDate`と`DATE`にすると定める。
APIでの形と、SQLでの求め方は定めていなかった（[issue #586](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/586)）。

画面はブラウザの既定のタイムゾーンで表示する（[ADR-047](ADR-047-parse-api-datetimes-with-temporal.md)）。
暦日を`2023-09-30T00:00:00Z`のようなUTCの0時の瞬間で返すと、UTCより西のタイムゾーンで前日に見える。

PostgreSQLの`timestamptz`に対する`date_trunc`、`CURRENT_DATE`、`::date`は、セッションの`TimeZone`で暦を決める（[PostgreSQL 18, Date/Time Functions and Operators](https://www.postgresql.org/docs/18/functions-datetime.html)）。
このリポジトリのセッションはUTCなので、SQLで暦日を作ると日本の0時から9時に前日になる。
[ADR-046](ADR-046-derive-local-dates-with-configured-business-zone.md)がJavaの`LocalDate.now(clock)`を止めた理由と同じである。

Jacksonは`LocalDate`を`"2023-09-30"`で書く。
ただし既定の読み取りは寛容で、`"2023-09-29T15:00:00Z"`（東京の9月30日）を`2023-09-29`として受ける。
`spring.jackson.default-leniency: false`にすると、この入力は失敗する。
このリポジトリの`application.yaml`と`JsonMapper`で、`Instant`の読み取りが変わらないことを確かめた。
変わるのは、`Instant`と`LocalDate`の空文字列が`null`ではなく失敗になる点である。

## Decision

APIの暦日は`LocalDate`で受け渡し、`full-date`（`YYYY-MM-DD`）で表す。
項目名は暦日なら`Date`、絶対時刻なら`At`で終え、Spectralが`format: date`と`format: date-time`を求める。

`application.yaml`に`spring.jackson.default-leniency: false`を置き、`LocalDate`が時刻つきの文字列を受けないようにする。

暦日と期間の境界は、Javaで業務タイムゾーン（ADR-046）から求めて引数で渡す。
SQLの`CURRENT_DATE`、`now()`、`date_trunc`、`::date`、`AT TIME ZONE`では作らない。
DBの関数には、互換関数でも現在時刻とタイムゾーンを読ませない。

`DateTimeConventionsArchTest`は、jOOQの`DSL`でDBの時計を読む関数の呼び出しを拒否する。
日時から暦日や境界を作る`DSL.trunc(...)`などは機械で止めず、レビューで確認する。

## Consequences

### Positive

- 暦日がブラウザのタイムゾーンで前日に見える誤りを、契約の形で防げる。
- SQLの暦がセッションの`TimeZone`に依存しなくなり、DBのセッション設定が変わっても結果が変わらない。
- 名前と`format`の食い違いを、Spectralが、Orvalでクライアントを生成する前の生成済みOpenAPIで止める。

### Negative

- 空文字列の`Instant`と`LocalDate`を`null`として受ける入力は、400になる。
- 暦日の境界をSQLで書けず、Javaで求める記述が増える。
- `DSL.trunc(...)`や`cast(DATE)`は機械で止められず、レビューに残る。

### Neutral

- 現時点のAPIとプロダクションコードに、暦日を返す項目と、DBの時計を読む呼び出しはない。

## Alternatives Considered

### 選択肢1: 暦日をUTCの0時の瞬間で返す

- **Description**：`Instant`として`2023-09-30T00:00:00Z`を返し、画面で変換する。
- **Pros**：絶対時刻と形が同じで、型が一つで済む。
- **Cons**：ブラウザの既定のタイムゾーンがUTCより西だと前日に見える。暦日と絶対時刻をAPIの契約で区別できない。

### 選択肢2: 暦日と境界をSQLの関数で作る

- **Description**：`date_trunc('day', ...)`や`CURRENT_DATE`で、DBが暦日と境界を求める。
- **Pros**：Javaから引数を渡さずに済む。
- **Cons**：結果がセッションの`TimeZone`で変わり、テストの`Clock.fixed(...)`も効かない。

### 選択肢3: 型ごとに`@JsonFormat(lenient = OptBoolean.FALSE)`を付ける

- **Description**：全体の設定を変えず、`LocalDate`の項目ごとに寛容な読み取りを止める。
- **Pros**：ほかの型の読み取りを変えない。
- **Cons**：付け漏れが検査なしで通る。調べた範囲で、全体の設定で変わる読み取りは空文字列の2型だけだった。
  全体の設定で別の読み取りが変わると分かったときの切り替え先として残す。

## References

- [ADR-046: 業務日付と地域の日時を設定値の業務タイムゾーンで明示して求める](ADR-046-derive-local-dates-with-configured-business-zone.md)
- [ADR-047: フロントエンドで API の日時を Temporal で解析する](ADR-047-parse-api-datetimes-with-temporal.md)
- [日時とタイムゾーンの規約](../datetime/timezone-conventions.md)
- [DateTimeConventionsArchTest.java](../../backend/src/test/java/com/example/demo/architecture/DateTimeConventionsArchTest.java)
- [JacksonConfigTest.java](../../backend/src/test/java/com/example/demo/config/JacksonConfigTest.java)
- [PostgreSQL 18, Date/Time Functions and Operators](https://www.postgresql.org/docs/18/functions-datetime.html)
