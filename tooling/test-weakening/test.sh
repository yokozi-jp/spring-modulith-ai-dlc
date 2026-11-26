#!/usr/bin/env bash
# test.sh: 小さな git の履歴を作って、check.sh が削除、減少、rename、変化なし、イベントの fixture の変更、削除、rename、追加を区別して警告することを確かめる。
# Run: bash tooling/test-weakening/test.sh (task test-weakening-test が実行する)

set -u
. "$(dirname "$0")/../scratch-git/scratch-git.sh" || exit 1

dir="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
failures=0

git init -q "$tmp/repo" -b main
cd "$tmp/repo" || exit 1
mkdir -p src
printf '@Test\nvoid a() {}\n@Test\nvoid b() {}\n' > src/FooTest.java
printf "it('a', () => {});\nit('b', () => {});\n" > src/bar.test.ts
echo x > src/Main.java
fixtures=backend/src/test/resources/events
mkdir -p "$fixtures"
echo '{"orderId":"o-1"}' > "$fixtures/com.example.Foo.v1.json"
git add -A && git commit -q -m base
git update-ref refs/remotes/origin/main HEAD

run_check() { bash "$dir/check.sh" 2>&1 1>/dev/null; }
reset_pr() { git switch -q -C pr refs/remotes/origin/main; }

expect_has() {
  if printf '%s' "$3" | grep -qF -- "$2"; then echo "ok: $1"; else echo "FAIL: $1 ($2 が出力に無い)"; failures=$((failures + 1)); fi
}
expect_empty() {
  if [ -z "$2" ]; then echo "ok: $1"; else echo "FAIL: $1 (出力あり: $2)"; failures=$((failures + 1)); fi
}

reset_pr; git rm -q src/FooTest.java; git commit -q -m pr
expect_has "テストファイルの削除を挙げる" "src/FooTest.java" "$(run_check)"

reset_pr; printf "it('a', () => {});\n" > src/bar.test.ts; git commit -qam pr
expect_has "テストケースの減少を挙げる" "src/bar.test.ts (2 -> 1)" "$(run_check)"

reset_pr; git mv src/FooTest.java src/BarTest.java; git commit -q -m pr
expect_empty "rename は削除として数えない" "$(run_check)"

reset_pr
printf "it('a', () => {});\nit('b', () => {});\nit('c', () => {});\n" > src/bar.test.ts
echo y > src/Main.java
printf '@Test\nvoid n() {}\n' > src/NewTest.java
git add -A; git commit -q -m pr
expect_empty "増加、追加、テスト以外の変更は警告しない" "$(run_check)"

reset_pr; git mv src/FooTest.java src/Foo.java; git commit -q -m pr
expect_has "テストでない名前への rename を挙げる" "src/FooTest.java -> src/Foo.java" "$(run_check)"

reset_pr; printf "test.each([1])('a', () => {});\n" > src/bar.test.ts; git commit -qam pr
expect_has "each の宣言も数える" "src/bar.test.ts (2 -> 1)" "$(run_check)"

reset_pr; git mv src/bar.test.ts src/bar.spec.ts; printf "it('a', () => {});\n// x\n" > src/bar.spec.ts; git commit -qam pr
expect_has "spec のテストケースの減少を挙げる" "src/bar.spec.ts (2 -> 1)" "$(run_check)"

reset_pr; git rm -q src/FooTest.java; git commit -q -m pr
expect_has "TESTS_ACK=1 は警告を抑える" "TESTS_ACK=1 のため" "$(TESTS_ACK=1 run_check)"
bash "$dir/check.sh" >/dev/null 2>&1
if [ "$?" -eq 0 ]; then echo "ok: 警告があっても終了コードは 0"; else echo "FAIL: 終了コードが 0 でない"; failures=$((failures + 1)); fi

reset_pr; echo '{"orderId":"o-2"}' > "$fixtures/com.example.Foo.v1.json"; git commit -qam pr
expect_has "fixture の変更を挙げる" "$fixtures/com.example.Foo.v1.json (M)" "$(run_check)"

reset_pr; git rm -q "$fixtures/com.example.Foo.v1.json"; git commit -q -m pr
expect_has "fixture の削除を挙げる" "$fixtures/com.example.Foo.v1.json (D)" "$(run_check)"

reset_pr; git mv "$fixtures/com.example.Foo.v1.json" "$fixtures/com.example.Bar.v1.json"; git commit -q -m pr
expect_has "fixture の rename を挙げる" "$fixtures/com.example.Foo.v1.json -> $fixtures/com.example.Bar.v1.json (R)" "$(run_check)"

reset_pr; echo '{"orderId":"o-1","note":"n"}' > "$fixtures/com.example.Foo.v2.json"; git add -A; git commit -q -m pr
expect_empty "fixture の追加は警告しない" "$(run_check)"

expect_has "比較起点が無ければ理由を出して通す" "スキップします" "$(TESTS_DIFF_BASE=missing run_check)"

[ "$failures" -eq 0 ] || { echo "$failures 件失敗"; exit 1; }
echo "all passed"
