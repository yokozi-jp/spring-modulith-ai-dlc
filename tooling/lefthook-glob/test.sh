#!/usr/bin/env bash
# test.sh: lefthook.yml の run を echo に置き換えた設定で本物の lefthook を動かし、glob が意図した job を選ぶことを確かめる。
# ** が 0 階層に一致しないために、ディレクトリ直下のファイルで job が走らなかった不具合（#230）の再発を止める。
# Run: bash tooling/lefthook-glob/test.sh (task lint-lefthook が実行する)

set -uo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root" || exit 1
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
failures=0

# lefthook は --file に加えてステージ済みのファイルも照合する。コミット中のフックの中でも結果が変わらないよう、
# 何もステージされていない空のリポジトリで動かす。
# linked worktree のコミットでは GIT_DIR などが渡り、git init が本物のリポジトリを bare にするため、リポジトリ固有の変数を全部外す。
# 開発者の LEFTHOOK_* も結果を変えるため外す。lefthook-local.yml は dump に混ざるので、この検査は手元の上書きなしで読む。
# shellcheck disable=SC2046
unset $(git rev-parse --local-env-vars) LEFTHOOK_EXCLUDE LEFTHOOK_ONLY LEFTHOOK_OUTPUT LEFTHOOK_CONFIG
lefthook="$root/node_modules/.bin/lefthook"
git init -q "$tmp/repo"

# run の本体を捨て、job 名を出すだけにする。files は、実行中のブランチに依らないよう消す（--file が代わりに渡す）。
# glob を持たない job は常に走るため、何もしない run にして一致の比較から外す。出力は production の output 設定に依らないよう固定する。
# この検査が見るのは glob だけで、run 内の {files} や {staged_files} の参照は見ない。
"$lefthook" dump --format json | node -e '
  const config = JSON.parse(require("fs").readFileSync(0, "utf8"));
  const stub = (job) => {
    if (job.group) job.group.jobs.forEach(stub);
    else { job.run = job.glob ? `echo MATCHED ${job.name}` : "true"; delete job.files; }
  };
  config.output = ["execution_out"];
  for (const hook of ["pre-commit", "pre-push"]) config[hook].jobs.forEach(stub);
  process.stdout.write(JSON.stringify(config));
' > "$tmp/lefthook.json" || { echo "FAIL: 設定を変換できません"; exit 1; }

# expect <フック> <ファイル> <一致する job の名前をスペースで区切った文字列。無ければ空>
expect() {
  local hook="$1" file="$2" want="$3" got
  got="$(cd "$tmp/repo" && LEFTHOOK_CONFIG="$tmp/lefthook.json" "$lefthook" run "$hook" --file "$file" --no-tty 2>&1 \
    | sed -n 's/^MATCHED //p' | sort | tr '\n' ' ' | sed 's/ $//')"
  want="$(printf '%s\n' $want | sort | tr '\n' ' ' | sed 's/ $//')"
  if [ "$got" = "$want" ]; then
    echo "ok: $hook $file"
  else
    echo "FAIL: $hook $file"
    echo "  want: ${want:-(なし)}"
    echo "  got:  ${got:-(なし)}"
    failures=$((failures + 1))
  fi
}

expect pre-commit docs/index.md "okf-check markdownlint"
expect pre-commit docs/tooling/index.md "okf-check markdownlint"
expect pre-commit README.md "okf-check markdownlint"
expect pre-commit frontend/package.json "frontend-check"
expect pre-commit frontend/apps/web/package.json "api-contract-check frontend-check"
expect pre-commit Taskfile.yml "okf-check lint-taskfile"
expect pre-commit .github/workflows/ci.yml "okf-check lint-taskfile"
expect pre-commit lefthook.yml "lint-lefthook lint-taskfile"
expect pre-commit tooling/task-calls/test.sh "lint-taskfile"
expect pre-commit tooling/agent-git/agent-git.sh "agent-git-test"
expect pre-commit tooling/agent-git/test.sh "agent-git-test"
expect pre-push backend/build.gradle "be-lint be-test"
expect pre-push backend/settings.gradle "be-lint be-test"
expect pre-push backend/src/main/java/App.java "be-lint be-test"
expect pre-push frontend/package.json "frontend-test-build"
expect pre-push .github/workflows/ci.yml "actionlint zizmor"

[ "$failures" -eq 0 ] || { echo "$failures 件失敗"; exit 1; }
echo "lefthook の glob の検査に成功しました。"
