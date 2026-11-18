#!/usr/bin/env bash
# test.sh: 偽の gh を PATH に置いて、ruleset-sync.mjs の diff と apply の終了コードとメッセージを確かめる。
# Run: bash tooling/ruleset-sync/test.sh (task ruleset-check が実行する)

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

# 偽の gh: GET は $FAKE_LIVE を返す。PUT は、控えがなければ失敗し、あれば呼び出しを $FAKE_LOG に残す。
# 入力を $FAKE_LIVE に書いて反映したことにする（$FAKE_NOAPPLY なら書かず、$FAKE_PUTFAIL なら失敗する）。
cat > "$tmp/bin/gh" <<'SH'
#!/usr/bin/env bash
if [[ " $* " == *" -X PUT "* ]]; then
  ls build/ruleset-backup/*.json > /dev/null 2>&1 || { echo "PUT の前に控えがありません" >> "$FAKE_LOG"; exit 1; }
  [ -z "${FAKE_PUTFAIL:-}" ] || exit 1
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
expect "同じなら apply も何もしない" 0 "ruleset-sync OK" env CONFIRM_RULESET=yes node "$dir/ruleset-sync.mjs" apply
[ ! -s "$FAKE_LOG" ] && echo "ok: 同じなら PUT しない" || { echo "FAIL: 同じなのに PUT した"; failures=$((failures + 1)); }

cp "$FAKE_LIVE" "$tmp/same.json"
sed -i 's/"CI gate"/"Old"/; s/"enforcement":"active"/"enforcement":"disabled"/' "$FAKE_LIVE"
expect "違えば diff は項目を示して失敗する" 1 "enforcement, rules.required_status_checks" node "$dir/ruleset-sync.mjs" diff
[ ! -s "$FAKE_LOG" ] && echo "ok: diff は PUT しない" || { echo "FAIL: diff が PUT した"; failures=$((failures + 1)); }

expect "確認変数がなければ apply は控えだけ保存して止まる" 1 "CONFIRM_RULESET=yes" node "$dir/ruleset-sync.mjs" apply
[ ! -s "$FAKE_LOG" ] && echo "ok: 確認がなければ PUT しない" || { echo "FAIL: 確認なしで PUT した"; failures=$((failures + 1)); }
expect "控えは反映前の内容" 0 ".json" grep -rl '"enforcement": "disabled"' build/ruleset-backup
grep -rq '"id"\|"updated_at"' build/ruleset-backup && { echo "FAIL: 控えに PUT が受け取らないフィールドがある"; failures=$((failures + 1)); } || echo "ok: 控えは PUT が受け取るフィールドだけを持つ"

expect "確認変数があれば apply は反映する" 0 "main.json と同じになった" env CONFIRM_RULESET=yes node "$dir/ruleset-sync.mjs" apply
expect "PUT は ruleset の endpoint に main.json を送る" 0 "rulesets/23297515 --input .github/rulesets/main.json" cat "$FAKE_LOG"

cp "$tmp/same.json" "$FAKE_LIVE" && sed -i 's/"CI gate"/"Old"/' "$FAKE_LIVE"
expect "反映後も違えば apply は失敗する" 1 "反映後も" env CONFIRM_RULESET=yes FAKE_NOAPPLY=1 node "$dir/ruleset-sync.mjs" apply
expect "PUT が失敗すれば apply も失敗する" 1 "" env CONFIRM_RULESET=yes FAKE_PUTFAIL=1 node "$dir/ruleset-sync.mjs" apply

expect "引数がなければ使い方を出す" 2 "使い方" node "$dir/ruleset-sync.mjs"

[ "$failures" -eq 0 ] || { echo "$failures 件失敗"; exit 1; }
echo "all passed"
