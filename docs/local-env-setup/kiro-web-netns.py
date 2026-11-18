#!/usr/bin/env python3
"""Kiro Web のサンドボックスで、コマンドを専用のネットワーク名前空間のなかで実行する（ADR-091）。

名前空間のなかでは 127.0.0.1:8080 が空いている。外への通信は veth とホスト側の NAT で通し、名前解決はホスト側の dnsmasq が受ける。
使い方: kiro-web-netns COMMAND [ARG...]
"""
import fcntl
import os
import signal
import subprocess
import sys
import tempfile
import time

from pyroute2 import IPRoute

ENV_SCRIPT = os.path.join(os.path.dirname(os.path.realpath(__file__)), "kiro-web.sh")
# ponytail: 名前とアドレスが固定なので、同時に動かせるのは 1 つだけである。並べたくなったら pid から名前と /30 を決める。
HOST_IF, CHILD_IF = "kwh0", "kwc0"
HOST_IP, CHILD_IP, PREFIX = "10.200.77.1", "10.200.77.2", 30
SUBNET = f"{HOST_IP.rsplit('.', 1)[0]}.0/{PREFIX}"
# サンドボックスの名前解決は 127.0.0.11 が受ける。名前空間からは届かないので、ホスト側の dnsmasq が中継する。
UPSTREAM_DNS = "127.0.0.11"
NAT_RULE = ["POSTROUTING", "-s", SUBNET, "!", "-d", SUBNET, "-j", "MASQUERADE"]


def netns_inode(pid):
    return os.stat(f"/proc/{pid}/ns/net").st_ino


def start_child(cmd, ready_fd, resolv):
    # 子は、親がネットワークを設定し終えるまで待つ。親が途中で失敗してパイプが閉じたら、コマンドを実行せずに終わる。
    script = ('read -r _ <&%d || exit 1; mount --bind "$KW_RESOLV" /etc/resolv.conf; '
              'source "$KW_ENV" && exec "$@"') % ready_fd
    child = subprocess.Popen(
        ["unshare", "-n", "-m", "--propagation", "private", "bash", "-c", script, "bash", *cmd],
        pass_fds=[ready_fd], env={**os.environ, "KW_RESOLV": resolv, "KW_ENV": ENV_SCRIPT},
    )
    host = netns_inode(os.getpid())
    for _ in range(100):
        try:
            if netns_inode(child.pid) != host:
                return child
        except FileNotFoundError:
            pass
        time.sleep(0.05)
    child.kill()
    raise RuntimeError("名前空間を作れませんでした")


def connect(ipr, pid):
    ipr.link("add", ifname=HOST_IF, kind="veth", peer={"ifname": CHILD_IF, "net_ns_pid": pid})
    host_idx = ipr.link_lookup(ifname=HOST_IF)[0]
    ipr.addr("add", index=host_idx, address=HOST_IP, prefixlen=PREFIX)
    ipr.link("set", index=host_idx, state="up")
    with IPRoute(netns=f"/proc/{pid}/ns/net") as inner:
        inner.link("set", index=inner.link_lookup(ifname="lo")[0], state="up")
        idx = inner.link_lookup(ifname=CHILD_IF)[0]
        inner.addr("add", index=idx, address=CHILD_IP, prefixlen=PREFIX)
        inner.link("set", index=idx, state="up")
        inner.route("add", dst="default", gateway=HOST_IP)
    if subprocess.run(["iptables", "-t", "nat", "-C", *NAT_RULE], capture_output=True).returncode != 0:
        subprocess.run(["iptables", "-t", "nat", "-A", *NAT_RULE], check=True)


def main(cmd):
    # tool call ごとに /tmp は別なので、ロックは tool call をまたいで共有される KIRO_WEB_HOME に置く。
    home = os.environ.get("KIRO_WEB_HOME", os.path.expanduser("~/.cache/kiro-web"))
    lock = open(os.path.join(home, "netns.lock"), "w")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        sys.exit("kiro-web-netns: 別の kiro-web-netns が動いています")

    ipr = IPRoute()
    # カーネルは終わった名前空間を非同期に片付けるので、直前の実行の veth が少しの間残る。
    for idx in ipr.link_lookup(ifname=HOST_IF):
        ipr.link("del", index=idx)

    work = tempfile.mkdtemp(prefix="kiro-web-netns.")
    resolv = os.path.join(work, "resolv.conf")
    with open(resolv, "w") as f:
        f.write(f"nameserver {HOST_IP}\noptions ndots:0\n")

    ready_r, ready_w = os.pipe()
    child = start_child(cmd, ready_r, resolv)
    os.close(ready_r)
    dns_pid = os.path.join(work, "dnsmasq.pid")
    try:
        connect(ipr, child.pid)
        # dnsmasq は待ち受けを始めてから背景に回るので、戻った時点で名前解決できる。
        subprocess.run(
            ["dnsmasq", "--conf-file=/dev/null", "--user=root", "--no-hosts", f"--pid-file={dns_pid}",
             "--bind-interfaces", f"--listen-address={HOST_IP}", "--no-resolv", f"--server={UPSTREAM_DNS}"],
            check=True,
        )
        os.write(ready_w, b"go\n")
    except BaseException:
        child.kill()
        raise
    finally:
        os.close(ready_w)
    try:
        code = child.wait()
    finally:
        if os.path.exists(dns_pid):
            with open(dns_pid) as f:
                os.kill(int(f.read()), signal.SIGTERM)
    sys.exit(code)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1:])
