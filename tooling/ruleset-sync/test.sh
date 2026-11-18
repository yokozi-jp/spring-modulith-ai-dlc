#!/usr/bin/env bash
# test.sh: 偽の gh を PATH に置いて、ruleset-sync.mjs の diff と apply の終了コードとメッセージを確かめる。
# Run: bash tooling/ruleset-sync/test.sh (task ruleset-sync-test が実行する)

set -u

dir="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
out="$tmp/out"
trap 'rm -rf "$tmp"' EXIT
failures=0

mkdir -p "$tmp/bin" "$tmp/repo/.github/rulesets"
cd "$tmp/repo" || exit 1

cat > .github/rulesets/main.json <<'JSON'
{"name":"main","target":"branch","enforcement":"active","conditions":{},"bypass_actors":[],
 "rules":[{"type":"deletion"},{"type":"required_status_checks","parameters":{"required_status_checks":[{"context":"CI gate"}]}}]}
JSON

# 偽の gh: GET は $FAKE_LIVE を返し、PUT は入力を $FAKE_LIVE に書いて反映したことにする（$FAKE_NOAPPLY があれば書かない）。
cat > "$tmp/bin/gh" <<'SH'
#!/usr/bin/env bash
if [[ " $* " == *" -X PUT "* ]]; then
  [ -n "${FAKE_NOAPPLY:-}" ] || cp "${@: -1}" "$FAKE_LIVE"
  echo "$*" >> "$FAKE_LOG"
else
  cat "$FAKE_LIVE"
fi
SH
chmod +x "$tmp/bin/gh"
export PATH="$tmp/bin:$PATH" FAKE_LIVE="$tmp/live.json" FAKE_LOG="$tmp/gh.log"

# expect <名前> <終了コード> <出力に含まれる文字列> <コマンド...>
expect() {
  local name="$1" want="$2" fragment="$3"
  shift 3
  "$@" > "$out" 2>&1
  local code=$?
  if [ "$code" != "$want" ] || ! grep -qF -- "$fragment" "$out"; then
    echo "FAIL: $name (終了コード $code、期待 $want、期待する出力: $fragment)"
    sed 's/^/  | /' "$out"
    failures=$((failures + 1))
  else
    echo "ok: $name"
  fi
}

# 同じ内容で、キーの順と余分なフィールド（id など）が違う live
cat > "$FAKE_LIVE" <<'JSON'
{"id":1,"target":"branch","name":"main","enforcement":"active","bypass_actors":[],"conditions":{},
 "rules":[{"type":"required_status_checks","parameters":{"required_status_checks":[{"context":"CI gate"}]}},{"type":"deletion"}],"updated_at":"x"}
JSON
expect "同じなら diff は通る（キーの順と id は無視する）" 0 "ruleset-sync OK" node "$dir/ruleset-sync.mjs" diff

sed 's/"CI gate"/"Old"/' "$FAKE_LIVE" > "$tmp/live2.json" && cp "$tmp/live2.json" "$FAKE_LIVE"
expect "必須チェックが違えば diff は rules の種類を示して失敗する" 1 "rules.required_status_checks" node "$dir/ruleset-sync.mjs" diff

sed -i 's/"enforcement":"active"/"enforcement":"disabled"/' "$FAKE_LIVE"
expect "enforcement の違いも示す" 1 "enforcement" node "$dir/ruleset-sync.mjs" diff

expect "apply は控えを保存して反映する" 0 "main.json と同じになった" node "$dir/ruleset-sync.mjs" apply
[ -n "$(ls build/ruleset-backup/*.json 2>/dev/null)" ] && echo "ok: 反映前の控えが build/ruleset-backup/ にある" || { echo "FAIL: 控えがない"; failures=$((failures + 1)); }
grep -q '"enforcement": "disabled"' build/ruleset-backup/*.json && echo "ok: 控えは反映前の内容" || { echo "FAIL: 控えが反映前の内容でない"; failures=$((failures + 1)); }

sed -i 's/"CI gate"/"Old"/' "$FAKE_LIVE"
expect "反映後も違えば apply は失敗する" 1 "反映後も" env FAKE_NOAPPLY=1 node "$dir/ruleset-sync.mjs" apply

expect "引数がなければ使い方を出す" 2 "使い方" node "$dir/ruleset-sync.mjs"

[ "$failures" -eq 0 ] || { echo "$failures 件失敗"; exit 1; }
echo "all passed"
