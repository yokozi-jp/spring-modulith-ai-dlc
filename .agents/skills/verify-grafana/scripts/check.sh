#!/usr/bin/env bash
# ローカルの Grafana の proxy 経由で、Prometheus の主要な指標と同梱ダッシュボードのパネルを調べる。読み取りだけを行う。
# 使い方: bash .kiro/skills/verify-grafana/scripts/check.sh [doctor|metrics|dashboards|all]
# 環境変数: GRAFANA_HOST_PORT（無ければ .env から読む）、EVIDENCE_DIR（既定 build/verify-grafana）
set -euo pipefail
mode="${1:-all}"
if [ -z "${GRAFANA_HOST_PORT:-}" ] && [ -f .env ]; then
  GRAFANA_HOST_PORT="$(sed -n 's/^GRAFANA_HOST_PORT=//p' .env)"
fi
B="http://localhost:${GRAFANA_HOST_PORT:?GRAFANA_HOST_PORT が未設定で、.env にも無い}"
PROM="$B/api/datasources/proxy/uid/prometheus/api/v1/query"
OUT="${EVIDENCE_DIR:-build/verify-grafana}"
mkdir -p "$OUT"
LOG="$OUT/check-$(date -u +%Y%m%dT%H%M%SZ).txt"
exec > >(tee "$LOG") 2>&1
fail=0

# 名前、PromQL。値のある系列が 1 本以上あれば OK。
metrics=(
  "RED Rate|sum(http_server_requests_milliseconds_count)"
  "RED Errors (4xx/5xx)|sum(http_server_requests_milliseconds_count{status=~\"[45]..\"})"
  "RED Duration p95|histogram_quantile(0.95,sum by (le)(rate(http_server_requests_milliseconds_bucket[5m])))"
  "JVM heap|sum(jvm_memory_used_bytes{area=\"heap\"})"
  "JVM metaspace|jvm_memory_used_bytes{id=\"Metaspace\"}"
  "JVM GC pause count|sum(jvm_gc_pause_milliseconds_count)"
  "JVM threads live|jvm_threads_live"
  "Process CPU|process_cpu_usage"
  "Hikari active|hikaricp_connections_active"
  "Hikari pending|hikaricp_connections_pending"
  "Hikari acquire count|hikaricp_connections_acquire_milliseconds_count"
  "jOOQ query count|sum(jooq_query_milliseconds_count)"
  "Logback events|sum(logback_events_total)"
)

q() { curl -sfG "$PROM" --data-urlencode "query=$1"; }
count() { python3 -c 'import json,sys; print(len(json.load(sys.stdin)["data"]["result"]))'; }

doctor() {
  echo "== doctor ($B)"
  curl -sf -o /dev/null "$B/api/health" && echo "OK   Grafana が応答する" || { echo "FAIL Grafana が応答しない"; fail=1; return; }
  q 'up' >/dev/null && echo "OK   proxy 経由で Prometheus に届く" || { echo "FAIL Prometheus の proxy が失敗"; fail=1; }
  n="$(q 'jvm_threads_live{service_name="demo-api"}' | count)"
  if [ "$n" -ge 1 ]; then echo "OK   demo-api の指標が届いている"; else echo "FAIL demo-api の指標が無い（バックエンド未起動、または起動から 1〜2 分以内）"; fail=1; fi
}

metrics() {
  echo "== metrics"
  for m in "${metrics[@]}"; do
    name="${m%%|*}"; expr="${m#*|}"
    n="$(q "$expr" | count || echo ERR)"
    if [ "$n" = ERR ] || [ "$n" -lt 1 ]; then echo "FAIL $name  series=$n  $expr"; fail=1; else echo "OK   $name  series=$n"; fi
  done
}

dashboards() {
  echo "== dashboards（パネルの PromQL を実行し、変数を demo/demo-api と .* に置き換える）"
  python3 - "$B" <<'PY' || fail=1
import json, re, sys, urllib.parse, urllib.request
B = sys.argv[1]
get = lambda u: json.load(urllib.request.urlopen(u))
prom = B + "/api/datasources/proxy/uid/prometheus/api/v1/query?query="
bad = 0
def walk(ps):
    for p in ps:
        yield p
        yield from walk(p.get("panels", []))
for d in get(B + "/api/search?type=dash-db"):
    dash = get(B + "/api/dashboards/uid/" + d["uid"])["dashboard"]
    for p in walk(dash.get("panels", [])):
        for t in p.get("targets", []):
            e = t.get("expr")
            if not e:
                continue
            e = re.sub(r"\$__rate_interval|\$__interval|\$\{__rate_interval\}", "5m", e)
            e = re.sub(r"\$\{?job\}?", "demo/demo-api", e)
            e = re.sub(r"\$\{?\w+\}?", ".*", e)
            try:
                n = len(get(prom + urllib.parse.quote(e))["data"]["result"])
            except Exception as ex:
                n = "ERR " + str(ex)[:40]
            ok = isinstance(n, int) and n > 0
            bad += not ok
            print(("OK   " if ok else "FAIL ") + f"{dash['title']} / {p.get('title')}  series={n}")
sys.exit(1 if bad else 0)
PY
}

case "$mode" in
  doctor) doctor ;;
  metrics) doctor; metrics ;;
  dashboards) doctor; dashboards ;;
  all) doctor; metrics; dashboards ;;
  *) echo "使い方: $0 [doctor|metrics|dashboards|all]"; exit 2 ;;
esac
echo "証拠: $LOG"
exit "$fail"
