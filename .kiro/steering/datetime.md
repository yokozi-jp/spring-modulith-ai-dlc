---
inclusion: fileMatch
fileMatchPattern: ["backend/src/**/*.java", "backend/src/main/resources/db/**", "frontend/src/**/*.{ts,tsx}", "docs/datetime/**"]
name: datetime
description: 日時の保存、API、表示、テストを変更するときに守る短いルール。Java、DB の changeset、フロントエンドのコードを扱うときに読み込む。詳細な規約は docs/datetime/ にある。
---

# 日時の短いルール

日時を扱うときは、次を必ず守る。
詳細（DST の扱い、精度、自動強制、日付だけや時刻だけの値）は `docs/datetime/index.md` から読む。

- 絶対時刻は、Java では `Instant`、PostgreSQL では `TIMESTAMP WITH TIME ZONE` にし、UTC で扱う。
- 現在時刻は `Clock` をコンストラクタで受け取り、`Instant.now(clock)` で取る。`Clock` を受け取らない `now(...)` や `ZoneId.systemDefault()` を使わない。
- API は絶対時刻を `Z` 付きの RFC 3339 文字列で返す。
- 表示用のタイムゾーン変換はフロントエンドの `Temporal.Instant` の `toLocaleString` で行う。
- 絶対時刻はマイクロ秒精度で保持する（`docs/datetime/timezone-conventions.md` の「精度」）。
- テストでは `Clock.fixed(...)` にマイクロ秒精度の `Instant` を渡す。
