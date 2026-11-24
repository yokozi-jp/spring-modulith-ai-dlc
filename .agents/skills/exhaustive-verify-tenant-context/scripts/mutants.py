#!/usr/bin/env python3
"""mutants.toml の変異を TenantContext.java へ当て、TenantContextExhaustiveTest が失敗することを確かめる。

使い方（リポジトリのルートで）: .agents/skills/exhaustive-verify-tenant-context/scripts/mutants.py
対照（変異なし）が通り、すべての変異でテストが失敗すれば 0 で終わる。
失敗がコンパイルエラーなら caught と数えず FAIL にする（テストが捕まえたことにならない）。
TenantContext.java に未コミットの変更があると、何も書き換えずに止まる。
終了、Ctrl-C、SIGTERM、SIGHUP では元の内容へ書き戻す。SIGKILL で止めたら `git checkout -- <TenantContext.java>` で戻す。
"""
import re
import signal
import subprocess
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
SOURCE = ROOT / "backend/src/main/java/com/example/demo/shared/tenancy/TenantContext.java"
MUTANTS = tomllib.loads((Path(__file__).resolve().parents[1] / "mutants.toml").read_text(encoding="utf-8"))["mutant"]
GRADLE_TIMEOUT_SECONDS = 900


def run_test() -> str:
    """テストを流し、pass、fail（テストの失敗）、error（コンパイルなどの失敗、時間切れ）のどれかを返す。"""
    try:
        result = subprocess.run(
            ["./gradlew", "test", "--tests", "*TenantContextExhaustiveTest", "-PtestEnvFile=../.env.test"],
            cwd=ROOT / "backend", capture_output=True, text=True, timeout=GRADLE_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired:
        print(f"Gradle が {GRADLE_TIMEOUT_SECONDS} 秒で終わらなかった")
        return "error"
    if result.returncode == 0:
        return "pass"
    # Gradle はテストの失敗を「<クラス> > <テスト> FAILED」の行で出す。コンパイルの失敗にはこの行がない。
    if re.search(r"TenantContextExhaustiveTest > .* FAILED", result.stdout):
        return "fail"
    print(result.stdout[-3000:] + result.stderr[-3000:])
    return "error"


def apply_edits(original: str, mutant: dict) -> str | None:
    """変異の edits を順に当てる。old がちょうど一度は現れなければ None を返す。"""
    text = original
    for edit in mutant["edits"]:
        if text.count(edit["old"]) != 1:
            return None
        text = text.replace(edit["old"], edit["new"])
    return text


def main() -> int:
    if subprocess.run(["git", "diff", "--quiet", "--", str(SOURCE)], cwd=ROOT).returncode != 0:
        print(f"{SOURCE} に未コミットの変更がある。commit か退避をしてから流す。")
        return 2
    # SIGTERM と SIGHUP は finally を通らないため、SystemExit に変えて通す。
    for name in (signal.SIGTERM, signal.SIGHUP):
        signal.signal(name, lambda number, frame: sys.exit(128 + number))
    original = SOURCE.read_text(encoding="utf-8")
    failed = False
    try:
        outcome = run_test()
        print(f"{'control' if outcome == 'pass' else 'FAIL   control'}: 変異なしのテスト = {outcome}")
        failed |= outcome != "pass"
        for mutant in MUTANTS:
            mutated = apply_edits(original, mutant)
            if mutated is None:
                print(f"FAIL   {mutant['name']}: old が TenantContext.java にちょうど一度は現れない")
                failed = True
                continue
            SOURCE.write_text(mutated, encoding="utf-8")
            outcome = run_test()
            label = {"fail": "caught", "pass": "MISSED"}.get(outcome, "FAIL  ")
            print(f"{label} {mutant['name']}: {mutant['change']}")
            failed |= outcome != "fail"
    finally:
        SOURCE.write_text(original, encoding="utf-8")
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())
