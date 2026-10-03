---
type: ADR
title: 'ADR-046: 業務日付と地域の日時を設定値の業務タイムゾーンで明示して求める'
description: 現在時刻を Instant.now(clock) だけで取り、業務日付と地域の日時は設定値の業務タイムゾーンで ofInstant により求め、Clock を迂回する now と UTC の Clock に頼る now(Clock) を ArchUnit で拒否する決定。
tags: [adr, datetime, timezone, backend]
---

# ADR-046: 業務日付と地域の日時を設定値の業務タイムゾーンで明示して求める

## Status

Proposed

## Date

2026-10-03

## Context

[ADR-006](ADR-006-utc-instant-absolute-time-policy.md)は、現在時刻を注入した`Clock`の`now(Clock)`で取ると定め、その`Clock`は`Clock.systemUTC()`である。

`LocalDate.now(clock)`のような`Instant`以外の`now(Clock)`は、`Clock`のゾーンで日付や時刻を決める（[Oracle Java SE 25, `LocalDate.now(Clock)`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/LocalDate.html#now(java.time.Clock))）。
注入する`Clock`がUTCなので、日本の業務では0時から9時の間に前日の日付になる。
この呼び出しはADR-006にも従来のArchUnitにも違反せず、誤りがテストでも見つかりにくい。

従来のArchUnitは引数なしの`now()`だけを拒否していた。
`LocalDate.now(ZoneId)`、`OffsetDateTime.now(ZoneOffset.UTC)`、`InstantSource.system()`はシステム時計を直接読むため、テストの`Clock.fixed(...)`が効かない。
`InstantSource`はJava 17で入り、現在時刻が必要なメソッドへ渡す使い方を[Javaの文書](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/InstantSource.html)が勧めている。

現時点のプロダクションコードには、業務日付や地域の日時を使う処理がない。

## Decision

現在時刻は`Instant.now(clock)`または`clock.millis()`だけで取る。

業務日付と地域の日時は、`LocalDate.ofInstant(Instant.now(clock), businessZone)`のように、業務タイムゾーンを明示して求める。
`LocalDateTime.ofInstant`と`ZonedDateTime.ofInstant(..., zone)`も同じように使う。
業務タイムゾーンはIANAタイムゾーンIDの設定値から受け取り、`Clock`のゾーンやJVMの既定タイムゾーンから取らない。
業務日付を管理テーブルで持つモジュールは、[業務日付](../database/postgresql-temporal-data.md#業務日付)を正とする。

設定値は、業務タイムゾーンを使う最初の処理と同じ変更で追加する。
そのときは`application.yaml`に1項目を置き、環境変数`BUSINESS_TIME_ZONE`を既定値なしで参照し（[ADR-008](ADR-008-single-application-yaml-external-config.md)）、`ZoneId`へバインドしてコンストラクタで注入する。
環境変数は`.env.example`にも加える。
YAMLのキーは、使うモジュールの設定の接頭辞に合わせる。

`DateTimeConventionsArchTest`は次の呼び出しとメソッド参照（`Instant::now`など）を拒否する。

- `java.time`の`now()`と`now(ZoneId)`。
  `now(ZoneOffset)`は`now(ZoneId)`へ束縛されるため含まれる。
- `Instant`以外の`java.time`型の`now(Clock)`。
  `java.time.chrono`の日付型も含む。
- `InstantSource.system()`。

## Consequences

### Positive

- UTCの日付を業務日付として使う誤りを、ビルドで検出できる。
- `java.time`の`now(...)`で現在時刻を取る経路が注入`Clock`だけになり、テストの`Clock.fixed(...)`がそれらの時刻取得に効く。
  `java.time.chrono.Chronology.dateNow(...)`は名前が`now`ではないため、この規則では検出しない。

### Negative

- `LocalDate.now(clock)`より記述が長くなる。
- 業務タイムゾーンを使い始めると、環境ごとに設定値が一つ増える。

### Neutral

- 使う処理がないため、設定値はまだ追加しない。
- ADR-006の「`now(Clock)`で取得する」を、`Instant.now(clock)`に限るよう狭める。

## Alternatives Considered

### 選択肢1: 業務タイムゾーンの`Clock`を注入する

- **Description**：`Clock.system(ZoneId.of("Asia/Tokyo"))`のような`Clock`を注入し、`LocalDate.now(clock)`をそのまま使う。
- **Pros**：呼び出し側の記述が短い。
- **Cons**：ADR-006がUTCに固定した絶対時刻の`Clock`へ業務のゾーンが混ざり、`ZonedDateTime.now(clock)`などの結果が注入する`Clock`の設定で変わる。ゾーンの依存がコードから読み取れない。

### 選択肢2: 規約とレビューだけで防ぐ

- **Description**：ArchUnitで拒否せず、文書に書いてレビューで確認する。
- **Pros**：テストの保守が要らない。
- **Cons**：誤りは例外を出さず、日本時間の0時から9時の間だけ値が変わるため、レビューとテストで見落としやすい。

### 選択肢3: 設定値を既定値`Asia/Tokyo`付きで今追加する

- **Description**：`application.yaml`に業務タイムゾーンを既定値付きで置く。
- **Pros**：最初の処理を書くときに設定を探さずに済む。
- **Cons**：使う処理がなく、ADR-008は`application.yaml`に既定値を置かないと定めている。

## References

- [ADR-006: 絶対時刻を UTC / Instant / timestamptz に統一する](ADR-006-utc-instant-absolute-time-policy.md)
- [ADR-008: application.yaml を単一にし、設定を外部から注入する](ADR-008-single-application-yaml-external-config.md)
- [日時とタイムゾーンの規約](../datetime/timezone-conventions.md)
- [PostgreSQLの時間で変わる業務データ](../database/postgresql-temporal-data.md)
- [DateTimeConventionsArchTest.java](../../backend/src/test/java/com/example/demo/architecture/DateTimeConventionsArchTest.java)
- Oracle Java SE 25, `InstantSource`: <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/InstantSource.html>
