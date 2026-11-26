# JVM の指標

ヒープ、Metaspace、GC、スレッド、CPU が Grafana の Prometheus で見られる。

## Sub-features

ヒープ、Metaspace、GC、スレッド、CPU の PromQL は、`check.sh` の `metrics` 配列の「JVM」の行が持つ。
GC（`jvm_gc_pause_seconds_*`）は、Micrometer の名前のままである。

## How to get to it (user POV)

Grafana の Dashboards から JVM Overview (OpenTelemetry) を開く。

## Driving it with check.sh

`check.sh metrics` の「JVM」の行と、`check.sh dashboards` の JVM Overview を見る。

## Gotchas

- JVM Overview の Classes のパネルは空のままである。
  `check.sh dashboards` は `GAP` と表示する。理由は [ADR-133](../../../../docs/adr/ADR-133-align-metrics-with-opentelemetry-semantic-conventions.md) にある。
- JVM Overview の変数 `instance` は `service.instance.id` である。再起動の直後は古い系列が 5 分ほど残る。
- GC の件数は、起動直後は 0 か 1 である。増えることまでは、このスキルで確かめない。
  JVM Overview の Garbage Collection は、GC が起きてから出る。
- 仮想スレッドの pinning は、この指標では見えない（JFR を使う。`docs/performance-test/metrics-and-monitoring.md`）。
