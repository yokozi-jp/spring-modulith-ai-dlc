#!/bin/bash
# Kiro Web のサンドボックスで docker と docker compose の代わりに呼ぶ（kiro-web.sh が $KIRO_WEB_HOME/bin/docker として置く、ADR-091）。
# 書ける overlay にした mount namespace のなかで podman の API サービスを起動し、docker と Compose をそこへ向ける。
# サービスとコンテナは tool call の終了で消える。
set -euo pipefail

kiro_home="${KIRO_WEB_HOME:-$HOME/.cache/kiro-web}"
podman_state="$kiro_home/podman"
# kiro-web.sh が名前空間ごとのソケットを DOCKER_HOST に決める。
sock="${DOCKER_HOST#unix://}"
podman=/usr/local/bin/podman
# podman の補助バイナリ（conmon など）は、既定の探索先ではなくこの場所にある。
helpers=/inner_container/usr/local/lib/podman

up() { curl -fs --unix-socket "$sock" http://d/_ping >/dev/null 2>&1; }

if ! up; then
  # 実行状態（runroot、tmp_dir、overlay の上層）は毎回作り直す。前の実行のものを引き継ぐと、消えたコンテナを動作中と誤認する。
  ns="$(mktemp -d /tmp/kiro-web-podman.XXXXXX)"
  mkdir -p "$podman_state"/{graph,static,volumes} "$ns"/{etc,run,varlib,varcache,vartmp}/{up,work} "$ns"/{runroot,tmp} "$ns/etc/up/containers"
  cat >"$ns/storage.conf" <<EOF
[storage]
driver = "overlay"
graphroot = "$podman_state/graph"
runroot = "$ns/runroot"
EOF
  # cgroup のファイルが読み取り専用なので、コンテナは cgroup なしで動かす。
  # ビルドの RUN は cgroups = "disabled" を見ずに crun を呼ぶので、cgroup を使わない crun を runtime にする。
  printf '%s\n' '#!/bin/sh' 'exec /usr/local/bin/crun --cgroup-manager=disabled "$@"' >"$ns/crun"
  chmod +x "$ns/crun"
  cat >"$ns/containers.conf" <<EOF
[containers]
cgroups = "disabled"
pids_limit = 0
[engine]
conmon_path = ["$helpers/conmon"]
helper_binaries_dir = ["$helpers"]
static_dir = "$podman_state/static"
tmp_dir = "$ns/tmp"
volume_path = "$podman_state/volumes"
cgroup_manager = "cgroupfs"
runtime = "crun"
events_logger = "file"
[engine.runtimes]
crun = ["$ns/crun"]
[network]
network_backend = "netavark"
EOF
  echo '{"default":[{"type":"insecureAcceptAnything"}]}' >"$ns/etc/up/containers/policy.json"
  echo 'unqualified-search-registries = ["docker.io"]' >"$ns/etc/up/containers/registries.conf"
  # overlay は下層の bind mount を引き継がないので、名前解決に要るファイルを写す。
  for f in resolv.conf hosts hostname; do cp -L "/etc/$f" "$ns/etc/up/$f" 2>/dev/null || true; done

  # 前の実行は片付けずに消えるので、この名前空間に netavark のポート転送の規則が残る。残った規則が先に一致し、同じポートが消えたコンテナへ向かう。
  iptables -t nat -F NETAVARK-HOSTPORT-DNAT 2>/dev/null || true
  for chain in $(iptables -t nat -S 2>/dev/null | awk '/^-N NETAVARK-DN-/ {print $2}'); do
    iptables -t nat -F "$chain" && iptables -t nat -X "$chain" || true
  done

  rm -f "$sock"
  export CONTAINERS_STORAGE_CONF="$ns/storage.conf" CONTAINERS_CONF="$ns/containers.conf" TMPDIR=/tmp
  # shellcheck disable=SC2016
  setsid unshare -m bash -ec '
    ns=$1; shift
    for d in etc:/etc run:/run varlib:/var/lib varcache:/var/cache vartmp:/var/tmp; do
      mount -t overlay overlay -o "lowerdir=${d#*:},upperdir=$ns/${d%%:*}/up,workdir=$ns/${d%%:*}/work" "${d#*:}"
    done
    exec "$@"' _ "$ns" "$podman" system service --time 0 "unix://$sock" >"$ns/service.log" 2>&1 &
  for _ in $(seq 1 50); do up && break; sleep 0.2; done
  up || { cat "$ns/service.log" >&2; echo "kiro-web-docker: podman のサービスが起動しませんでした" >&2; exit 1; }

  # systemd が無いので、podman は healthcheck を定期実行しない。各コンテナの interval ごとに代わりに実行する。
  # shellcheck disable=SC2016
  setsid bash -c '
    sock=$1; podman=$2
    pod() { "$podman" --url "unix://$sock" "$@"; }
    declare -A next
    while curl -fs --unix-socket "$sock" http://d/_ping >/dev/null; do
      now=$(date +%s)
      for id in $(pod ps -q); do
        [ "$now" -ge "${next[$id]:-0}" ] || continue
        interval=$(pod inspect -f "{{if .Config.Healthcheck}}{{printf \"%.0f\" .Config.Healthcheck.Interval.Seconds}}{{end}}" "$id" 2>/dev/null || true)
        [ -n "$interval" ] || { next[$id]=$((now + 3600)); continue; }
        # podman は interval を省いた healthcheck に 0 を返す。Docker の既定の 30 秒にそろえる。
        [ "$interval" -ge 1 ] || interval=30
        next[$id]=$((now + interval))
        pod healthcheck run "$id" >/dev/null 2>&1 || true
      done
      sleep 1
    done' _ "$sock" "$podman" >/dev/null 2>&1 &
fi

case "${1:-}" in
  compose) shift; DOCKER_HOST="unix://$sock" exec docker-compose "$@" ;;
  *) exec "$podman" --url "unix://$sock" "$@" ;;
esac
