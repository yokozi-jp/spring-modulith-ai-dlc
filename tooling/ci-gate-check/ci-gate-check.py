#!/usr/bin/env python3
"""ci.yml の集約ジョブが、pull_request で動く検査を取りこぼしていないか確かめる（docs/adr/ADR-087）。

使い方: ci-gate-check.py [workflows のディレクトリ] [excluded.tsv]
"""

import re
import sys
from pathlib import Path

import yaml

GATE_JOB = "gate"
USES_LOCAL = re.compile(r"^\./\.github/workflows/([^/@]+\.ya?ml)$")


def triggers(workflow):
    # YAML 1.1 は、キーの on を真偽値の True として読む。
    on = workflow.get("on", workflow.get(True))
    if isinstance(on, str):
        return {on: None}
    if isinstance(on, list):
        return {name: None for name in on}
    return on or {}


def read_excluded(path):
    excluded = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        name, _, reason = line.partition("\t")
        if not reason.strip():
            raise SystemExit(f"{path}: 理由のない行があります: {line!r}")
        excluded[name] = reason
    return excluded


def main():
    directory = Path(sys.argv[1] if len(sys.argv) > 1 else ".github/workflows")
    excluded = read_excluded(Path(sys.argv[2] if len(sys.argv) > 2 else Path(__file__).with_name("excluded.tsv")))
    workflows = {p.name: yaml.safe_load(p.read_text(encoding="utf-8")) for p in sorted(directory.glob("*.y*ml"))}
    errors = []

    ci = workflows.get("ci.yml")
    if ci is None:
        raise SystemExit("ci.yml がありません。")
    jobs = ci["jobs"]

    gate = jobs.get(GATE_JOB)
    if gate is None:
        errors.append(f"ci.yml に集約ジョブ {GATE_JOB} がありません。")
    else:
        if str(gate.get("if", "")).replace(" ", "") not in ("always()", "${{always()}}"):
            errors.append(f"ci.yml の {GATE_JOB} に if: always() がありません。")
        needs = gate.get("needs", [])
        needs = [needs] if isinstance(needs, str) else needs
        for job in jobs:
            if job != GATE_JOB and job not in needs:
                errors.append(f"ci.yml の {job} が {GATE_JOB} の needs にありません。")
        for job in needs:
            if job not in jobs:
                errors.append(f"ci.yml の {GATE_JOB} の needs に、存在しないジョブ {job} があります。")

    called = {}
    for job_id, job in jobs.items():
        match = USES_LOCAL.match(str(job.get("uses", "")))
        if match:
            called[match.group(1)] = job_id

    for name in called:
        workflow = workflows.get(name)
        if workflow is None:
            errors.append(f"ci.yml が呼ぶ {name} がありません。")
            continue
        if "workflow_call" not in triggers(workflow):
            errors.append(f"{name} に workflow_call がありません。")
        group = str((workflow.get("concurrency") or {}).get("group", "")) if isinstance(workflow.get("concurrency"), dict) else str(workflow.get("concurrency", ""))
        if "github.workflow" in group:
            errors.append(f"{name} の concurrency の group が github.workflow を使っています。呼ばれると呼び出し側の名前になり、呼ばれた workflow どうしが取り消し合います。")

    for name, workflow in workflows.items():
        if name == "ci.yml":
            continue
        runs_on_pr = "pull_request" in triggers(workflow)
        if runs_on_pr and name not in called and name not in excluded:
            errors.append(f"{name} は pull_request で動きますが、ci.yml から呼ばれておらず、除外の一覧にもありません。")

    for name in excluded:
        if name not in workflows:
            errors.append(f"除外の一覧の {name} がありません。")
        elif name in called:
            errors.append(f"{name} は ci.yml から呼ばれているので、除外の一覧から消してください。")
        elif "pull_request" not in triggers(workflows[name]):
            errors.append(f"{name} は pull_request で動かないので、除外の一覧から消してください。")

    for error in errors:
        print(f"error: {error}", file=sys.stderr)
    if errors:
        raise SystemExit(1)
    print(f"ci-gate-check OK: 呼ぶ workflow {len(called)} 本、除外 {len(excluded)} 本")


main()
