#!/usr/bin/env bash
# ローカルの Grafana の proxy 経由で、Prometheus の主要な指標と同梱ダッシュボードのパネルを調べる。読み取りだけを行う。
# 使い方: bash .kiro/skills/verify-grafana/scripts/check.sh [doctor|metrics|dashboards|all]
# 環境変数: GRAFANA_HOST_PORT、OTEL_SERVICE_NAME、OTEL_SERVICE_NAMESPACE（無ければ .env から読む）、EVIDENCE_DIR（既定 build/verify-grafana）
set -euo pipefail
mode="${1:-all}"
envval() { [ -f .env ] && sed -n "s/^$1=//p" .env | tail -1 || true; }
GRAFANA_HOST_PORT="${GRAFANA_HOST_PORT:-$(envval GRAFANA_HOST_PORT)}"
SVC="${OTEL_SERVICE_NAME:-$(envval OTEL_SERVICE_NAME)}"
NS="${OTEL_SERVICE_NAMESPACE:-$(envval OTEL_SERVICE_NAMESPACE)}"
B="http://localhost:${GRAFANA_HOST_PORT:?GRAFANA_HOST_PORT が未設定で、.env にも無い}"
: "${SVC:?OTEL_SERVICE_NAME が未設定で、.env にも無い}" "${NS:?OTEL_SERVICE_NAMESPACE が未設定で、.env にも無い}"
PROM="$B/api/datasources/proxy/uid/prometheus/api/v1/query"
OUT="${EVIDENCE_DIR:-build/verify-grafana}"
mkdir -p "$OUT"
LOG="$OUT/check-$(date -u +%Y%m%dT%H%M%SZ).txt"
exec > >(tee "$LOG") 2>&1
fail=0

# 名前|PromQL。NaN でない値の系列が 1 本以上あれば OK。名前と属性は OpenTelemetry の semantic conventions（ADR-133）。
# RED Duration p95 は rate を使うので、直前の 5 分に request が要る。
metrics=(
  "RED Rate|sum(http_server_request_duration_seconds_count)"
  "RED Errors (4xx/5xx)|sum(http_server_request_duration_seconds_count{http_response_status_code=~\"[45]..\"})"
  "RED Duration p95|histogram_quantile(0.95,sum by (le)(rate(http_server_request_duration_seconds_bucket[5m])))"
  "JVM heap|sum(jvm_memory_used_bytes{jvm_memory_type=\"heap\"})"
  "JVM metaspace|jvm_memory_used_bytes{jvm_memory_pool_name=\"Metaspace\"}"
  "JVM GC pause count|sum(jvm_gc_pause_seconds_count)"
  "JVM threads live|jvm_threads_live"
  "JVM CPU utilization|jvm_cpu_recent_utilization"
  "Hikari active|hikaricp_connections_active"
  "Hikari pending|hikaricp_connections_pending"
  "Hikari acquire count|hikaricp_connections_acquire_seconds_count"
  "jOOQ query count|sum(jooq_query_seconds_count)"
  "Logback events|sum(logback_events_total)"
)

q() { curl -sfG --max-time 20 "$PROM" --data-urlencode "query=$1"; }
count() { python3 -c 'import json,sys; print(sum(1 for r in json.load(sys.stdin)["data"]["result"] if r["value"][1] not in ("NaN", "+Inf", "-Inf")))'; }

doctor() {
  echo "== doctor ($B)"
  curl -sf --max-time 10 -o /dev/null "$B/api/health" || { echo "FAIL Grafana が応答しない"; return 1; }
  echo "OK   Grafana が応答する"
  q 'up' >/dev/null || { echo "FAIL proxy 経由で Prometheus に届かない"; return 1; }
  echo "OK   proxy 経由で Prometheus に届く"
  n="$(q "jvm_threads_live{service_name=\"$SVC\"}" | count || echo 0)"
  [ "$n" -ge 1 ] || { echo "FAIL $SVC の指標が無い（バックエンド未起動、または起動から 1〜2 分以内）"; return 1; }
  echo "OK   $SVC の指標が届いている（別の worktree のバックエンドの指標でないことは、起動した PID で確かめる）"
}

check_metrics() {
  echo "== metrics"
  for m in "${metrics[@]}"; do
    name="${m%%|*}"; expr="${m#*|}"
    n="$(q "$expr" | count || echo ERR)"
    if [ "$n" = ERR ] || [ "$n" -lt 1 ]; then echo "FAIL $name  series=$n  $expr"; fail=1; else echo "OK   $name  series=$n"; fi
  done
}

check_dashboards() {
  echo "== dashboards（Prometheus のパネルの PromQL を実行する。変数 job は $NS/$SVC、それ以外は All と同じ .+ に置き換える）"
  python3 - "$B" "$NS/$SVC" <<'PY' || fail=1
import json, re, sys, urllib.parse, urllib.request
B, JOB = sys.argv[1], sys.argv[2]
get = lambda u: json.load(urllib.request.urlopen(u, timeout=20))
prom = B + "/api/datasources/proxy/uid/prometheus/api/v1/query?query="
bad = 0
# 空のままにすると決めたパネル（panel が None ならダッシュボード全体）。理由は ADR-133。
KNOWN_EMPTY = {
    ("JVM Overview (OpenTelemetry)", "Classes"): "Micrometer が jvm.class.count の単位を classes で送り、名前が jvm_class_count_classes になる",
    ("RED Metrics (native histogram)", None): "explicit bucket の histogram を送っており、native histogram の系列が無い",
}
def walk(ps):
    for p in ps:
        yield p
        yield from walk(p.get("panels", []))
for d in get(B + "/api/search?type=dash-db"):
    dash = get(B + "/api/dashboards/uid/" + d["uid"])["dashboard"]
    for p in walk(dash.get("panels", [])):
        for t in p.get("targets", []):
            e = t.get("expr")
            ds = t.get("datasource") or p.get("datasource")
            if not e or (isinstance(ds, dict) and ds.get("type") not in (None, "prometheus")):
                continue
            e = re.sub(r"\$\{?__\w+\}?", "5m", e)
            e = re.sub(r"\$\{?job\}?", JOB, e)
            e = re.sub(r"\$\{?\w+\}?", ".+", e)
            try:
                r = get(prom + urllib.parse.quote(e))["data"]["result"]
                n = sum(1 for x in r if "histogram" in x or x["value"][1] not in ("NaN", "+Inf", "-Inf"))
            except Exception as ex:
                n = "ERR " + str(ex)[:40]
            ok = isinstance(n, int) and n > 0
            why = KNOWN_EMPTY.get((dash["title"], p.get("title"))) or KNOWN_EMPTY.get((dash["title"], None))
            label = f"{dash['title']} / {p.get('title')}  series={n}"
            if why and not ok:
                print(f"GAP  {label}  既知: {why}")
                continue
            if why:
                print(f"FAIL {label}  既知の空が解消した。KNOWN_EMPTY から外す")
                bad += 1
                continue
            bad += not ok
            print(("OK   " if ok else "FAIL ") + label)
sys.exit(1 if bad else 0)
PY
}

case "$mode" in
  doctor|metrics|dashboards|all) ;;
  *) echo "使い方: $0 [doctor|metrics|dashboards|all]"; exit 2 ;;
esac
doctor || { echo "doctor が FAIL のため、先に進まない"; echo "証拠: $LOG"; exit 1; }
case "$mode" in
  metrics) check_metrics ;;
  dashboards) check_dashboards ;;
  all) check_metrics; check_dashboards ;;
esac
echo "証拠: $LOG"
exit "$fail"
