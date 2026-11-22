#!/usr/bin/env python3
"""mutants.toml の変異を TenantContext.java へ当て、TenantContextExhaustiveTest が失敗することを確かめる。

使い方（リポジトリのルートで）: .agents/skills/exhaustive-verify-tenant-context/scripts/mutants.py
対照（変異なし）が通り、すべての変異でテストが失敗すれば 0 で終わる。
失敗がコンパイルエラーなら caught と数えず FAIL にする（テストが捕まえたことにならない）。
ソースは終了時に必ず元へ戻す。
"""
import subprocess
import sys
import tomllib
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(__file__).resolve().parents[4]
source = root / "backend/src/main/java/com/example/demo/shared/tenant/TenantContext.java"
mutants = tomllib.loads((Path(__file__).resolve().parents[1] / "mutants.toml").read_text())["mutant"]
report = root / "backend/build/test-results/test/TEST-com.example.demo.shared.tenant.TenantContextExhaustiveTest.xml"


def run_test() -> str:
    """テストを流し、pass、fail（テストの失敗）、error（コンパイルなどの失敗）のどれかを返す。"""
    report.unlink(missing_ok=True)
    result = subprocess.run(
        ["./gradlew", "test", "--tests", "*TenantContextExhaustiveTest", "-PtestEnvFile=../.env.test"],
        cwd=root / "backend", capture_output=True, text=True,
    )
    if result.returncode == 0:
        return "pass"
    if report.exists() and int(ET.parse(report).getroot().get("failures")) > 0:
        return "fail"
    print(result.stdout[-3000:] + result.stderr[-3000:])
    return "error"


original = source.read_text()
status = 0
try:
    outcome = run_test()
    print(f"{'control' if outcome == 'pass' else 'FAIL   control'}: 変異なしのテスト = {outcome}")
    status |= outcome != "pass"
    for mutant in mutants:
        text = original
        for edit in mutant["edits"]:
            if text.count(edit["old"]) != 1:
                print(f"FAIL   {mutant['name']}: old が TenantContext.java にちょうど一度は現れない")
                status = 1
                break
            text = text.replace(edit["old"], edit["new"])
        else:
            source.write_text(text)
            outcome = run_test()
            print(f"{'caught' if outcome == 'fail' else 'MISSED' if outcome == 'pass' else 'FAIL  '} {mutant['name']}: {mutant['change']}")
            status |= outcome != "fail"
finally:
    source.write_text(original)
sys.exit(status)
