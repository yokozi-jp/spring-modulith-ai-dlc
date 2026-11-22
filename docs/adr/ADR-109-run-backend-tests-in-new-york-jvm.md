---
type: ADR
title: 'ADR-109: バックエンドのテストを America/New_York の JVM で流す'
description: プロセスのタイムゾーンに頼る誤りを落とすため、バックエンドのテストの JVM を本番とも業務とも違い夏時間のある America/New_York にする決定。本番、bootRun、seedLocalData は UTC のままにする。
tags: [adr, datetime, timezone, backend, testing]
---

# ADR-109: バックエンドのテストを America/New_York の JVM で流す

## Status

Proposed

## Date

2026-11-22

## Context

[ADR-006](ADR-006-utc-instant-absolute-time-policy.md) は JVM を `-Duser.timezone=UTC` に固定すると決め、テストも UTC の JVM で流してきた。
本番も UTC なので、テストの JVM の既定のタイムゾーンが本番と同じになり、JVM の既定に頼る誤りが UTC では結果が変わらず、テストで見えない。
Error Prone と ArchUnit は自前のコードの誤りを静的に止めるが、ライブラリと設定の経路は見ない。

[issue #322](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/322) の調査で次を確かめた。

- `test` の `user.timezone` を `America/New_York` にしても、99 クラスの 1153 件がすべて通る。
- `application.yaml` の `SET TIME ZONE 'UTC'` を消すと、America/New_York の JVM では `DemoApplicationTest` の「DB セッションのタイムゾーンは UTC である」が落ちる。UTC の JVM では落ちない。PgJDBC が JVM の既定のタイムゾーンを接続の `TimeZone` として送るためである。

## Decision

バックエンドのテストの JVM（`test` タスクと PIT）の `user.timezone` を `America/New_York` にする。
本番（`Dockerfile` の `JAVA_TOOL_OPTIONS`）、`bootRun`、`seedLocalData` は UTC のままにする。

`America/New_York` を選ぶ理由は、業務のタイムゾーン（東京）とも本番（UTC）とも違い、夏時間があるからである。
プロセスのタイムゾーンに頼る誤りは、どちらを前提にしていても結果が変わる。

`JvmTimeZoneTest` が、既定のタイムゾーンが `America/New_York` であることを確かめ、設定が外れたら落ちる。

ADR-006 の「JVM は `-Duser.timezone=UTC`」は、テストについてこの ADR が改める。

## Consequences

### Positive

- JVM の既定のタイムゾーンに頼るライブラリと設定の誤りが、テストで落ちる。
- `connection-init-sql` の `SET TIME ZONE 'UTC'` を、`DemoApplicationTest` が確かめるようになる。

### Negative

- テストの JVM と本番の JVM でタイムゾーンが違うので、テストの失敗が本番では起きない設定の差から来ることがあり、原因の切り分けに手間がかかる。

### Neutral

- 現在時刻を使うテストは、これまでどおり `Clock.fixed(...)` を渡す。

## Alternatives Considered

### 選択肢1: UTC のままにする

- **Description**：テストも UTC の JVM で流す。
- **Pros**：変更が要らない。
- **Cons**：JVM の既定に頼る誤りが、本番と同じ UTC では見えない。

### 選択肢2: 東京にする

- **Description**：業務のタイムゾーンで流す。
- **Pros**：業務の前提に近い。
- **Cons**：東京は夏時間がなく、業務を前提にした誤りも結果が変わらない。

### 選択肢3: 複数のタイムゾーンで流す

- **Description**：UTC、東京、America/New_York の組でテストを繰り返す。
- **Pros**：どのタイムゾーンを前提にした誤りも落とせる。
- **Cons**：テストの時間が組の数だけ増える。夏時間のある America/New_York 一つで、UTC と東京の両方と結果が変わるので、一つで足りる。

## References

- [issue #585](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/585)
- [issue #322](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/322)
- [ADR-006: 絶対時刻を UTC / Instant / timestamptz に統一する](ADR-006-utc-instant-absolute-time-policy.md)
- [日時とタイムゾーンの規約](../datetime/timezone-conventions.md)
