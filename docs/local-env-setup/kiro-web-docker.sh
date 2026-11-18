#!/bin/bash
# Kiro Web のサンドボックスで docker と docker compose の代わりに呼ぶ（kiro-web.sh が $KIRO_WEB_HOME/bin/docker として置く）。
# サンドボックスには docker daemon が無く、podman も /etc、/run、/var/lib が読み取り専用なので動かない。
# そのため、これらを書ける overlay にした mount namespace のなかで podman の API サービスを起動し、docker と Compose をそこへ向ける。
# サービスはサンドボックスの1回の実行（tool call）が終わると消える。コンテナも一緒に消えるので、起動から片付けまでを1回の実行で済ませる。
set -euo pipefail

K="${KIRO_WEB_HOME:-$HOME/.cache/kiro-web}"
P="$K/podman"
sock="/tmp/kiro-web-podman.sock"
podman=/usr/local/bin/podman
# podman の補助バイナリ（conmon など）は、既定の探索先ではなくこの場所にある。
helpers=/inner_container/usr/local/lib/podman

up() { curl -fs --unix-socket "$sock" http://d/_ping >/dev/null 2>&1; }

if ! up; then
  # 実行状態（runroot、tmp_dir、overlay の上層）は毎回作り直す。前の実行のものを引き継ぐと、消えたコンテナを動作中と誤認する。
  ns="$(mktemp -d /tmp/kiro-web-podman.XXXXXX)"
  mkdir -p "$P/graph" "$P/static" "$P/volumes" "$ns"/{etc,run,varlib,varcache}/{up,work} "$ns"/{runroot,tmp} "$ns/etc/up/containers"
  cat >"$ns/storage.conf" <<EOF
[storage]
driver = "overlay"
graphroot = "$P/graph"
runroot = "$ns/runroot"
EOF
  cat >"$ns/containers.conf" <<EOF
[containers]
# cgroup のファイルが読み取り専用で、コンテナの cgroup を作れない。
cgroups = "disabled"
pids_limit = 0
[engine]
conmon_path = ["$helpers/conmon"]
helper_binaries_dir = ["$helpers"]
static_dir = "$P/static"
tmp_dir = "$ns/tmp"
volume_path = "$P/volumes"
cgroup_manager = "cgroupfs"
events_logger = "file"
[network]
network_backend = "netavark"
EOF
  echo '{"default":[{"type":"insecureAcceptAnything"}]}' >"$ns/etc/up/containers/policy.json"
  echo 'unqualified-search-registries = ["docker.io"]' >"$ns/etc/up/containers/registries.conf"
  # overlay は下層の bind mount を引き継がないので、名前解決に要るファイルを写す。
  for f in resolv.conf hosts hostname; do cp -L "/etc/$f" "$ns/etc/up/$f" 2>/dev/null || true; done
  cat >"$ns/enter.sh" <<EOF
#!/bin/bash
set -e
for d in etc:/etc run:/run varlib:/var/lib varcache:/var/cache; do
  n=\${d%%:*}; p=\${d#*:}
  mount -t overlay overlay -o lowerdir=\$p,upperdir=$ns/\$n/up,workdir=$ns/\$n/work \$p
done
exec "\$@"
EOF
  chmod +x "$ns/enter.sh"
  # 前の実行は片付けずに消えるので、netavark のポート転送の規則が残る。残った規則が先に一致し、同じポートが消えたコンテナへ向かう。
  iptables -t nat -F NETAVARK-HOSTPORT-DNAT 2>/dev/null || true
  for chain in $(iptables -t nat -S 2>/dev/null | awk '/^-N NETAVARK-DN-/ {print $2}'); do
    iptables -t nat -F "$chain" && iptables -t nat -X "$chain" || true
  done
  rm -f "$sock"
  export CONTAINERS_STORAGE_CONF="$ns/storage.conf" CONTAINERS_CONF="$ns/containers.conf" TMPDIR=/tmp
  setsid unshare -m "$ns/enter.sh" "$podman" system service --time 0 "unix://$sock" >"$ns/service.log" 2>&1 &
  for _ in $(seq 1 50); do up && break; sleep 0.2; done
  up || { cat "$ns/service.log" >&2; echo "kiro-web-docker: podman のサービスが起動しませんでした" >&2; exit 1; }
  # systemd が無いので、healthcheck の定期実行が動かない。Compose の --wait が待ち続けないよう、代わりに回す。
  setsid bash -c "while up=\$(curl -fs --unix-socket $sock http://d/_ping); do
      for id in \$($podman --url unix://$sock ps -q); do $podman --url unix://$sock healthcheck run \$id >/dev/null 2>&1 || true; done
      sleep 2
    done" >/dev/null 2>&1 &
fi

export DOCKER_HOST="unix://$sock"
case "${1:-}" in
  compose) shift; exec "$K/bin/docker-compose" "$@" ;;
  *) exec "$podman" --url "$DOCKER_HOST" "$@" ;;
esac
