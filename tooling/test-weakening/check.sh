#!/usr/bin/env bash
# check.sh: origin/main との merge base から HEAD までで、テストファイルの削除とテストケースの減少を標準エラーに警告する。
# 終了コードは常に 0。TESTS_ACK=1 で警告を抑える。比較の起点が無いときは理由を出して通す（task adr-check と同じ形）。
# 環境変数: TESTS_DIFF_BASE（既定 origin/main）、TESTS_ACK
# ponytail: テストケースの数は行頭の宣言の行数で数える概算で、コメントと文字列の中の宣言も数える。見落とした PR が出たら CI の annotation を足す。

set -u

base="${TESTS_DIFF_BASE:-origin/main}"
test_file='(Tests?\.java|\.test\.tsx?)$'
test_decl='^[[:space:]]*(@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate|ArchTest)\b|(it|test)\()'

merge_base="$(git merge-base "$base" HEAD 2>/dev/null)" || {
  echo "Test weakening check: 比較起点 $base が無いためスキップします。" >&2
  exit 0
}

count() { git show "$1:$2" 2>/dev/null | grep -cE "$test_decl"; }

deleted=()
reduced=()
while IFS=$'\t' read -r status old new; do
  new="${new:-$old}"
  printf '%s\n' "$old" | grep -qE "$test_file" || continue
  case "$status" in
    D) deleted+=("$old") ;;
    R*|M)
      before="$(count "$merge_base" "$old")"
      after="$(count HEAD "$new")"
      if [ "$after" -lt "$before" ]; then reduced+=("$new ($before -> $after)"); fi
      ;;
  esac
done < <(git -c core.quotepath=off diff --name-status -M "$merge_base" HEAD)

if [ "${#deleted[@]}" -eq 0 ] && [ "${#reduced[@]}" -eq 0 ]; then exit 0; fi

if [ "${TESTS_ACK:-}" = "1" ]; then
  echo "Test weakening check: TESTS_ACK=1 のため警告を抑制しました。" >&2
  exit 0
fi

{
  echo ""
  echo "⚠ テストを弱める変更の可能性: 人が読む Pull Request の条件に当たる場合があります。"
  if [ "${#deleted[@]}" -gt 0 ]; then
    echo "  削除したテストファイル:"
    printf '    - %s\n' "${deleted[@]}"
  fi
  if [ "${#reduced[@]}" -gt 0 ]; then
    echo "  テストケースが減ったファイル（merge base -> HEAD の宣言の行数）:"
    printf '    - %s\n' "${reduced[@]}"
  fi
  echo "  理由を Pull Request の本文に書いてください（docs/pull-request/review-request-and-merge.md の「人が必ず読むPull Request」）。"
  echo "  意図した変更なら TESTS_ACK=1 を付けて再実行できます（例: TESTS_ACK=1 git push）。"
  echo ""
} >&2
exit 0
