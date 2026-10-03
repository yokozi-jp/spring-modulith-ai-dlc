---
type: ADR
title: 'ADR-044: 絶対時刻の保持精度をマイクロ秒にそろえる'
description: 絶対時刻の保持精度をマイクロ秒に固定し、DB、API、イベントで同じ精度を使い、システムの Clock をマイクロ秒単位の tick で生成する決定。
tags: [adr, datetime, precision, backend, web-api]
---

# ADR-044: 絶対時刻の保持精度をマイクロ秒にそろえる

## Status

Proposed

## Date

2026-10-03

## Context

[ADR-006](ADR-006-utc-instant-absolute-time-policy.md) は絶対時刻を UTC の `Instant` と `timestamptz` にそろえたが、精度は要件として明示するとだけ定め、値を決めていない。
`DemoApplication.clock()` が返す `Clock.systemUTC()` は OS が提供する精度で時刻を返し、Linux 上の JDK 25 ではナノ秒の端数を含む値を返す。
PostgreSQL の `timestamptz` はマイクロ秒までしか保持せず、それより細かい端数を丸める。
そのため、メモリ上の `Instant` と DB から読み戻した `Instant` が一致しない。
テストは `Clock.fixed(...)` にマイクロ秒精度の値を渡すため、この不一致を検出できない。

## Decision

絶対時刻の保持精度はマイクロ秒とし、DB、API、イベントで同じ精度を使う。
`DemoApplication.clock()` は `Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000))` を返し、生成時点でマイクロ秒に切り捨てる。
API の小数部は `Instant.toString()` の出力に従う。

## Consequences

### Positive

- 保存して読み戻した値が元の `Instant` と一致する。
- API とイベントに出る値が DB の値と一致し、比較や重複判定がずれない。
- 個々の呼び出し側で精度を切り捨てる必要がない。

### Negative

- 同じマイクロ秒の中で起きた事象は、時刻だけでは前後を区別できない。

### Neutral

- フロントエンドの `Date` はミリ秒までしか保持しないため、フロントエンドは [ADR-047](ADR-047-parse-api-datetimes-with-temporal.md) のとおり `Temporal` で解析する。
- API の小数部の桁数は値によって 0 桁、3 桁、6 桁に変わる。

## Alternatives Considered

### 選択肢1: ナノ秒のまま保持し、境界で切り捨てる

- **Description**：`Clock.systemUTC()` を維持し、DB、API、イベントへ渡すときに切り捨てる。
- **Pros**：メモリ上では最も細かい順序を保てる。
- **Cons**：すべての境界で切り捨てが要り、一か所でも漏れると往復の不一致が残る。

### 選択肢2: ミリ秒にそろえる

- **Description**：`Clock.tickMillis(...)` 相当の精度にし、`Date` と合わせる。
- **Pros**：フロントエンドまで同じ精度になる。
- **Cons**：PostgreSQL が保持できるマイクロ秒の精度を捨てる。

### 選択肢3: 使う箇所で `Instant.truncatedTo(ChronoUnit.MICROS)` を呼ぶ

- **Description**：時刻を取得する各クラスで切り捨てる。
- **Pros**：`Clock` の生成を変えずに済む。
- **Cons**：呼び忘れを検出できず、規約を守る負担が各クラスに分散する。

## References

- [ADR-006: 絶対時刻を UTC / Instant / timestamptz に統一する](ADR-006-utc-instant-absolute-time-policy.md)
- [日時とタイムゾーンの規約](../datetime/timezone-conventions.md)
- [Oracle Java SE 25, `Clock`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/Clock.html)
- [PostgreSQL 18, Date/Time Types](https://www.postgresql.org/docs/18/datatype-datetime.html)
- `backend/src/test/java/com/example/demo/DemoApplicationTest.java`
