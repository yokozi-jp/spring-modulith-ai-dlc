---
inclusion: always
name: datetime
description: 日時を扱うすべてのタスクで守る短いルール。詳細な規約は docs/datetime/ にある。日時の保存、API、表示、テストを変更するときに使用する。
---

# 日時の短いルール

日時を扱うときは、次を必ず守る。
詳細（DST の扱い、精度、自動強制、日付だけや時刻だけの値）は `docs/datetime/index.md` から読む。

- 絶対時刻は、Java では `Instant`、PostgreSQL では `TIMESTAMP WITH TIME ZONE` にし、UTC で扱う。
- 現在時刻は `Clock` をコンストラクタで受け取り、`Instant.now(clock)` で取る。引数なしの `now()` や `ZoneId.systemDefault()` を使わない。
- API は絶対時刻を `Z` 付きの RFC 3339 文字列で返す。
- 表示用のタイムゾーン変換はフロントエンドの `Intl.DateTimeFormat` で行う。
- テストでは `Clock.fixed(...)` を使い、`Instant` をマイクロ秒精度にそろえる。
