# DB の接続プールと SQL

Hikari の接続の使用状況と、jOOQ のクエリの件数と時間が見られる。

## Sub-features

- Hikari：`hikaricp_connections_active`、`_pending`、`_acquire_milliseconds_*`、`_timeout_total`
- jOOQ：`jooq_query_milliseconds_*`（[ADR-070](../../../../docs/adr/ADR-070-record-sql-spans-with-jooq-execute-listener.md)）

## How to get to it (user POV)

Explore の Prometheus で上の指標を実行する。
同梱のダッシュボードには、これらのパネルが無い。

## Driving it with check.sh

`check.sh metrics` の「Hikari」と「jOOQ」の行を見る。
SQL を発行する `/api/**` を呼ぶと、`jooq_query_*` の件数が増える。

## Gotchas

- `hikaricp_connections_active` と `pending` は、静止していると 0 である。系列があることだけを確かめ、負荷下の値は性能テストで見る。
- 起動時のシーダーや Spring Modulith のスケジューラーも SQL を発行するので、`jooq_query_*` の件数は request を送らなくても 0 にならない。request で増えたことを示すには、前後の差を取る。
