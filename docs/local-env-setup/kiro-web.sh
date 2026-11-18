#!/bin/bash
# Kiro Web のサンドボックスでローカルと同じ道具を使えるようにする。実行せず source する（ADR-093）。
#   source docs/local-env-setup/kiro-web.sh
# 道具と状態は KIRO_WEB_HOME（既定は ~/.cache/kiro-web）へ置き、リポジトリには書かない。
# 入れ済みの道具は、mise の設定と lock が変わるまで入れ直さない。
# KIRO_WEB_NO_DOCKER=1 なら、docker の代わりの podman のサービスを起動しない。

# サンドボックスの外（WSL など）で読むと docker と DOCKER_HOST を podman へ向けてしまうので、何もしない。
if [ ! -d /inner_container ]; then
  echo "kiro-web: Kiro Web のサンドボックスではないため、何もしません" >&2
  return 0 2>/dev/null || exit 0
fi

_kw_repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export KIRO_WEB_HOME="${KIRO_WEB_HOME:-$HOME/.cache/kiro-web}"

# 古い mise（サンドボックスに入っている版）は mise.lock の version 3 を読めず、書き換えもするため、
# CI の composite action と同じ版を取り直す（ADR-090）。
_kw_mise_version="$(sed -nE 's/^[[:space:]]+version: ([0-9][0-9.]*)$/\1/p' "$_kw_repo/.github/actions/setup-mise/action.yml")"

export MISE_DATA_DIR="$KIRO_WEB_HOME/mise-data"
export MISE_STATE_DIR="$KIRO_WEB_HOME/mise-state"
# mise.kiro-web.toml（サンドボックスだけで使う Compose）も読ませる。
export MISE_ENV=kiro-web
# サンドボックスに入っている Java、Gradle、gh などを、書き込める MISE_DATA_DIR からも見つけさせる。
export MISE_SHARED_INSTALL_DIRS="${MISE_SHARED_INSTALL_DIRS:-$HOME/.local/share/mise/installs}"
# vp と pnpm の保存先。XDG_CONFIG_HOME は git の設定が読めなくなるため動かさない。
export XDG_DATA_HOME="$KIRO_WEB_HOME/xdg/data"
export VP_HOME="$KIRO_WEB_HOME/vite-plus"
export GRADLE_USER_HOME="$KIRO_WEB_HOME/gradle"
# ローカルは 01-setup-java.sh が file.encoding を設定する。java.util.prefs の既定の保存先 ~/.java は書けない。
export JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Djava.util.prefs.userRoot=$KIRO_WEB_HOME/java-prefs"
# Testcontainers は podman のサービスへつなぐ。コンテナ内と誤認してブリッジの gateway を宛先にしないよう、宛先を loopback に固定する。
export DOCKER_HOST="unix:///tmp/kiro-web-podman-$(stat -L -c %i /proc/self/ns/net).sock"
export TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1
# podman の API は BuildKit を持たないので、Compose の組み込みの builder（旧 API）でイメージを作る。
export COMPOSE_BAKE=false DOCKER_BUILDKIT=0
export PYTHONUSERBASE="$KIRO_WEB_HOME/python"
export PATH="$KIRO_WEB_HOME/bin:$MISE_DATA_DIR/shims:$VP_HOME/bin:$PATH"

_kw_setup() (
  set -euo pipefail
  mkdir -p "$KIRO_WEB_HOME/bin" "$XDG_DATA_HOME" "$MISE_STATE_DIR"
  # セッションの開始時の MCP サーバーと最初の tool call が、同時に入れ始めることがある。
  exec 8>"$KIRO_WEB_HOME/setup.lock"
  flock 8
  work="$(mktemp -d)"
  trap 'rm -rf "$work"' EXIT

  if [ "$("$KIRO_WEB_HOME/bin/mise" --version 2>/dev/null | cut -d' ' -f1)" != "$_kw_mise_version" ]; then
    echo "kiro-web: mise $_kw_mise_version を入れます" >&2
    asset="mise-v${_kw_mise_version}-linux-x64"
    base="https://github.com/jdx/mise/releases/download/v${_kw_mise_version}"
    curl -fsSL "$base/$asset" -o "$work/$asset"
    curl -fsSL "$base/SHASUMS256.txt" -o "$work/SHASUMS256.txt"
    # ponytail: checksum は binary と同じ release から取る。改ざんされた release は防げない。署名検証へ上げる余地がある。
    (cd "$work" && grep " ./$asset\$" SHASUMS256.txt | sha256sum -c - >&2)
    install -m 755 "$work/$asset" "$KIRO_WEB_HOME/bin/mise"
  fi

  ln -sf "$_kw_repo/docs/local-env-setup/kiro-web-docker.sh" "$KIRO_WEB_HOME/bin/docker"
  ln -sf "$_kw_repo/docs/local-env-setup/kiro-web-netns.py" "$KIRO_WEB_HOME/bin/kiro-web-netns"
  rm -f "$KIRO_WEB_HOME/bin/docker-compose"

  cd "$_kw_repo"
  stamp="$MISE_DATA_DIR/.locked-$(cat mise.toml mise.lock mise.kiro-web.toml mise.kiro-web.lock | sha256sum | cut -c1-16)"
  if [ ! -e "$stamp" ]; then
    # サンドボックスのグローバル設定にも道具があり、--locked だと lock に無いとして止まる。リポジトリの道具だけを名指しする。
    # shellcheck disable=SC2046
    mise install --locked $(mise ls --local --json | jq -r 'keys[]') >&2
    mise reshim
    touch "$stamp"
  fi

  if [ ! -x "$VP_HOME/bin/vp" ]; then
    echo "kiro-web: Vite+ を入れます" >&2
    VP_NODE_MANAGER=no bash "$_kw_repo/docs/local-env-setup/02-setup-viteplus.sh" >"$KIRO_WEB_HOME/vp-install.log" 2>&1 \
      || { cat "$KIRO_WEB_HOME/vp-install.log" >&2; exit 1; }
  fi

  # ci-gate-check が PyYAML を使い（ローカルは apt の python3-yaml）、kiro-web-netns.py が pyroute2 を使う。
  python_packages=(pyyaml==6.0.3 pyroute2==0.9.7)
  python3 -c 'import sys
from importlib.metadata import version
sys.exit(any(version(p.split("==")[0]) != p.split("==")[1] for p in sys.argv[1:]))' "${python_packages[@]}" 2>/dev/null \
    || python3 -m pip install --quiet --user --root-user-action=ignore --disable-pip-version-check "${python_packages[@]}" >&2

  # 背景に残る podman のサービスがロックを受け継がないよう、起動の前に閉じる。
  exec 8>&-
  # Gradle のテスト（Testcontainers）は docker の代わりを経由せずサービスへつなぐので、先に起動しておく。
  [ -n "${KIRO_WEB_NO_DOCKER:-}" ] || "$KIRO_WEB_HOME/bin/docker" info >/dev/null
)

_kw_setup || echo "kiro-web: セットアップに失敗しました" >&2
unset -f _kw_setup
unset _kw_repo _kw_mise_version
