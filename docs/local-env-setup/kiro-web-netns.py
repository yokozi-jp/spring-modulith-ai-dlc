#!/usr/bin/env python3
"""Kiro Web のサンドボックスで、コマンドを専用のネットワーク名前空間のなかで実行する。

サンドボックスの 127.0.0.1:8080 は別のプロセスが使っており、compose.yml と compose-test.yml が 8080 を固定で公開する Task は、
ホストのネットワーク名前空間では起動できない。専用の名前空間では 8080 が空いている。
外へ出る通信は veth とホスト側の NAT で通し、名前解決はホスト側の dnsmasq が受ける。
使い方: kiro-web-netns.py COMMAND [ARG...]
"""
import os
import subprocess
import sys
import tempfile
import time

from pyroute2 import IPRoute

ENV_SCRIPT = os.path.join(os.path.dirname(os.path.realpath(__file__)), "kiro-web.sh")
HOST_IF, CHILD_IF = "kwh0", "kwc0"
HOST_IP, CHILD_IP, PREFIX = "10.200.77.1", "10.200.77.2", 30
SUBNET = f"{HOST_IP.rsplit('.', 1)[0]}.0/{PREFIX}"
# ホストの名前解決は、docker 風の 127.0.0.11 が受ける。ホスト側のプロセスから 127.0.0.11 へ送れば届く。
UPSTREAM_DNS = "127.0.0.11"

INNER = f"""
from pyroute2 import IPRoute
ipr = IPRoute()
ipr.link('set', index=ipr.link_lookup(ifname='lo')[0], state='up')
idx = ipr.link_lookup(ifname='{CHILD_IF}')[0]
ipr.addr('add', index=idx, address='{CHILD_IP}', prefixlen={PREFIX})
ipr.link('set', index=idx, state='up')
ipr.route('add', dst='default', gateway='{HOST_IP}')
"""


def netns_id(pid):
    return os.stat(f"/proc/{pid}/ns/net").st_ino


def main(cmd):
    work = tempfile.mkdtemp(prefix="kiro-web-netns.")
    resolv = os.path.join(work, "resolv.conf")
    with open(resolv, "w") as f:
        f.write(f"nameserver {HOST_IP}\noptions ndots:0\n")

    read_fd, write_fd = os.pipe()
    # 子は、ネットワークの設定が済むまで待ち、名前解決の設定を重ねてから、コマンドへ置き換わる。
    # 環境変数は名前空間ごとに変わる（DOCKER_HOST は名前空間の番号を含む）ので、kiro-web.sh を読み直してから実行する。
    script = ('read -r _ <&%d; mount --bind "$KW_RESOLV" /etc/resolv.conf; '
              'source "$KW_ENV" && exec "$@"') % read_fd
    child = subprocess.Popen(
        ["unshare", "-n", "-m", "--propagation", "private", "bash", "-c", script, "bash", *cmd],
        pass_fds=[read_fd], env={**os.environ, "KW_RESOLV": resolv, "KW_ENV": ENV_SCRIPT},
    )
    host_ns = netns_id(os.getpid())
    for _ in range(100):
        try:
            if netns_id(child.pid) != host_ns:
                break
        except FileNotFoundError:
            pass
        time.sleep(0.05)
    else:
        child.kill()
        sys.exit("kiro-web-netns: 名前空間を作れませんでした")

    ipr = IPRoute()
    stale = ipr.link_lookup(ifname=HOST_IF)
    if stale:
        ipr.link("del", index=stale[0])
    ipr.link("add", ifname=HOST_IF, kind="veth", peer={"ifname": CHILD_IF})
    ipr.link("set", index=ipr.link_lookup(ifname=CHILD_IF)[0], net_ns_pid=child.pid)
    host_idx = ipr.link_lookup(ifname=HOST_IF)[0]
    ipr.addr("add", index=host_idx, address=HOST_IP, prefixlen=PREFIX)
    ipr.link("set", index=host_idx, state="up")

    rule = ["POSTROUTING", "-s", SUBNET, "!", "-d", SUBNET, "-j", "MASQUERADE"]
    if subprocess.run(["iptables", "-t", "nat", "-C", *rule], capture_output=True).returncode != 0:
        subprocess.run(["iptables", "-t", "nat", "-A", *rule], check=True)

    dns = subprocess.Popen(
        ["dnsmasq", "--keep-in-foreground", "--conf-file=/dev/null", "--user=root", "--no-hosts",
         "--bind-interfaces", f"--listen-address={HOST_IP}", "--no-resolv", f"--server={UPSTREAM_DNS}",
         f"--pid-file={work}/dnsmasq.pid"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    subprocess.run(["nsenter", "-t", str(child.pid), "-n", sys.executable, "-c", INNER], check=True)

    os.write(write_fd, b"go\n")
    os.close(write_fd)
    code = child.wait()
    dns.terminate()
    sys.exit(code)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1:])
