# ADR-006: 絶対時刻を UTC / Instant / timestamptz に統一する

## Status

Accepted

## Date

2026-09-11

## Context

本 ADR は、既に実装済みの決定を遡って記録した（backfill）。

日時の扱いは、保存・API・表示・テストで解釈がずれると、
再現困難なバグや、環境依存の挙動を生む。
バックエンドの実行環境・DB セッション・ログ・API 表現で、
絶対時刻の解釈を一意に固定したい。

## Decision

絶対時刻を UTC に統一し、Java では `Instant`、PostgreSQL では
`TIMESTAMP WITH TIME ZONE` を使う。表示用タイムゾーンへの変換はフロントエンドが担う。

- 現在時刻は `Clock` をコンストラクタ注入し、`now(Clock)` で取得する。
- システム `Clock` の生成は `DemoApplication.clock()`（`Clock.systemUTC()`）だけに限定する。
- JVM は `-Duser.timezone=UTC`、DB セッションと Jackson も UTC に固定する。
- API の絶対時刻は RFC 3339 の UTC 表現（常に `Z`）で返す。
- 規約は Error Prone（`JavaTimeDefaultTimeZone`、`JavaUtilDate`）と
  ArchUnit（`DateTimeConventionsArchTest`）で機械的に強制する。

## Consequences

### Positive

- 保存・API・ログの時刻解釈が一意になり、環境依存の挙動を避けられる。
- テストで `Clock.fixed(...)` により時刻を固定でき、再現性が高い。
- 規約違反をコンパイル時・テスト時に検出できる。

### Negative

- 現在時刻を使うクラスは `Clock` 注入が必須になり、定型が増える。
- 日付だけ・時刻だけの値や地域時刻は、別の型で明示的に扱う判断が要る。

### Neutral

- 表示用タイムゾーンはバックエンドの保存形式へ混ぜず、フロントで変換する。
- 精度は層ごとに異なる（Instant ナノ秒 / PostgreSQL マイクロ秒 / Date ミリ秒）ため、
  保持精度を要件として明示する。

## Alternatives Considered

### Alternative 1: 地域時刻（例：JST）で保存する

- 説明：ローカルタイムゾーンで保存・表示する。
- Pros：単一地域なら一見単純。
- Cons：DST・多地域・集計で破綻し、絶対時刻の比較が不正確になる。

### Alternative 2: 固定オフセット（+09:00）で表現する

- 説明：オフセット付きで保持・表現する。
- Pros：ローカル時刻を復元しやすい。
- Cons：夏時間や法改正を表せず、地域 ID なしでは将来の予定を正しく扱えない。

## References

- [`../../.kiro/steering/datetime-timezone-conventions.md`](../../.kiro/steering/datetime-timezone-conventions.md)
- `backend/src/test/java/com/example/demo/architecture/DateTimeConventionsArchTest.java`
- `backend/build.gradle`（Error Prone 設定）
