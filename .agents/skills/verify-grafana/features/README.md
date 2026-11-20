# ローカルの Grafana の指標の検証の機能一覧

このディレクトリは、ローカルの Grafana で確かめる指標の群ごとに、手順と既知の失敗を持つ。
指標を調べる前にこの一覧を読み、該当するファイルを手順として使う。
指標の名前と閾値の正文は `docs/performance-test/metrics-and-monitoring.md` と `docs/observability/` にある。
手順と食い違ったら、手順を直す。

- [red-http.md](red-http.md)：リクエストの Rate、Errors、Duration
- [jvm.md](jvm.md)：ヒープ、Metaspace、GC、スレッド、CPU
- [db-pool-and-sql.md](db-pool-and-sql.md)：Hikari の接続プールと jOOQ のクエリ
