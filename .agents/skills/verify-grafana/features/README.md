# ローカルの Grafana の指標の検証の機能一覧

このディレクトリは、ローカルの Grafana で確かめる指標の群ごとに、手順と既知の失敗を持つ。
指標を調べる前にこの一覧を読み、該当するファイルを手順として使う。
集める指標の種類は `docs/performance-test/metrics-and-monitoring.md` にある。
指標の名前と属性は、バックエンドが出す Micrometer の名前が正で、`check.sh` の `metrics` 配列に持つ。
実際の出力と食い違ったら、`check.sh` と手順を直す。名前を OpenTelemetry に揃える作業は Issue #563 にある。

- [red-http.md](red-http.md)：リクエストの Rate、Errors、Duration
- [jvm.md](jvm.md)：ヒープ、Metaspace、GC、スレッド、CPU
- [db-pool-and-sql.md](db-pool-and-sql.md)：Hikari の接続プールと jOOQ のクエリ
