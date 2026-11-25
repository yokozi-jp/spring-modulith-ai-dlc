# JVM の指標

ヒープ、Metaspace、GC、スレッド、CPU が Grafana の Prometheus で見られる。

## Sub-features

- ヒープ：`jvm_memory_used_bytes{jvm_memory_type="heap"}`（`jvm_memory_pool_name` が G1 Eden Space、G1 Old Gen など）
- Metaspace：`jvm_memory_used_bytes{jvm_memory_pool_name="Metaspace"}`
- GC：`jvm_gc_pause_seconds_*`（属性 `gc`、`action`、`cause`）。この名前は Micrometer のままである
- スレッド：`jvm_threads_live`、`jvm_threads_states`（属性 `state`）
- CPU：`jvm_cpu_recent_utilization`

## How to get to it (user POV)

Grafana の Dashboards から JVM Overview (OpenTelemetry) を開く。

## Driving it with check.sh

`check.sh metrics` の「JVM」と「Process CPU」の行と、`check.sh dashboards` の JVM Overview を見る。

## Gotchas

- JVM Overview の Classes のパネルは空のままである。
  Micrometer が `jvm.class.count` の単位を `classes` で送り、名前が `jvm_class_count_classes` になる。
  パネルは `jvm_class_count` を引く（[ADR-133](../../../../docs/adr/ADR-133-align-metrics-with-opentelemetry-semantic-conventions.md)）。
  `check.sh dashboards` は `GAP` と表示する。
- JVM Overview の変数 `instance` は `service.instance.id` である。再起動の直後は古い系列が 5 分ほど残る。
- GC の件数は、起動直後は 0 か 1 である。増えることまでは、このスキルで確かめない。
  JVM Overview の Garbage Collection は、GC が起きて `jvm_gc_pause_seconds_sum` が増えてから出る。
- 仮想スレッドの pinning は、この指標では見えない（JFR を使う。`docs/performance-test/metrics-and-monitoring.md`）。
