# JVM の指標

ヒープ、Metaspace、GC、スレッド、CPU が Grafana の Prometheus で見られる。

## Sub-features

- ヒープ：`jvm_memory_used_bytes{area="heap"}`（`id` が G1 Eden Space、G1 Old Gen など）
- Metaspace：`jvm_memory_used_bytes{id="Metaspace"}`
- GC：`jvm_gc_pause_milliseconds_*`（属性 `gc`、`action`、`cause`）
- スレッド：`jvm_threads_live`、`jvm_threads_states`（属性 `state`）
- CPU：`process_cpu_usage`

## How to get to it (user POV)

Grafana の Dashboards から JVM Overview (OpenTelemetry) を開く。

## Driving it with check.sh

`check.sh metrics` の「JVM」と「Process CPU」の行と、`check.sh dashboards` の JVM Overview を見る。

## Gotchas

- 同梱の JVM Overview は全パネルが No data である。
  `instance` 属性が無いため、変数 instance の All（`.+`）が一致しない。
  Explore で `jvm_threads_live` を直接実行すれば値は見える。Issue #563 で扱う。
- 属性名は Micrometer のものである。`jvm_memory_pool_name` や `jvm_memory_type` では空になる。
- GC の件数は、起動直後は 0 か 1 である。増えることまでは、このスキルで確かめない。
- 仮想スレッドの pinning は、この指標では見えない（JFR を使う。`docs/performance-test/metrics-and-monitoring.md`）。
