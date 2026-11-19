#!/usr/bin/env python3
"""ci.yml の集約ジョブが、pull_request で動く検査を取りこぼしていないか確かめる（docs/adr/ADR-087）。
あわせて、workflow の paths 条件の食い違いと、mise を使わない道具の入れ方を止める（docs/adr/ADR-090）。

使い方: ci-gate-check.py [workflows のディレクトリ] [excluded.tsv]
"""

import re
import shlex
import sys
from pathlib import Path

import yaml

GATE_JOB = "gate"
GATE_NAME = "CI gate"
USES_LOCAL = re.compile(r"^\./\.github/workflows/([^/@]+\.ya?ml)$")
SETUP_MISE = "./.github/actions/setup-mise"
# setup-mise が入れる道具の版と入れ方を決めるファイル。setup-mise を使う workflow は、これらの変更でも走らせる。
TOOLCHAIN = {"mise.toml", "mise.lock", ".github/actions/setup-mise/**"}
# mise.lock を通さずに道具を入れる action。
BANNED_USES = ("go-task/setup-task@", "actions/setup-go@", "actions/setup-java@")


def triggers(workflow):
    # YAML 1.1 は、キーの on を真偽値の True として読む。on: push や on: [push] も dict にそろえる。
    on = workflow.get("on", workflow.get(True)) or {}
    if isinstance(on, str):
        return {on: None}
    if isinstance(on, list):
        return dict.fromkeys(on)
    return on


def step_uses(workflow):
    return [str(step.get("uses", "")) for job in (workflow.get("jobs") or {}).values() for step in job.get("steps", [])]


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


def check_gate(jobs):
    gate = jobs.get(GATE_JOB)
    if gate is None:
        return [f"ci.yml に集約ジョブ {GATE_JOB} がありません。"]
    errors = []
    if gate.get("name") != GATE_NAME:
        errors.append(f"ci.yml の {GATE_JOB} の name が {GATE_NAME!r} ではありません。ruleset が待つ check 名です。")
    if str(gate.get("if", "")).replace(" ", "") != "always()":
        errors.append(f"ci.yml の {GATE_JOB} に if: always() がありません。")
    needs = gate.get("needs", [])
    errors += [f"ci.yml の {job} が {GATE_JOB} の needs にありません。" for job in jobs if job != GATE_JOB and job not in needs]
    return errors


def check_called(name, workflow):
    errors = []
    if "workflow_call" not in triggers(workflow):
        errors.append(f"{name} に workflow_call がありません。")
    if "pull_request" in triggers(workflow):
        errors.append(f"{name} は ci.yml から呼ばれるのに pull_request でも動きます。PR で二重に走り、check 名も二重になります。")
    group = str(workflow.get("concurrency", {}).get("group", ""))
    if not group.startswith(Path(name).stem + "-"):
        errors.append(f"{name} の concurrency の group が {Path(name).stem}- で始まっていません。呼ばれると github.workflow が CI になり、呼ばれた workflow どうしが取り消し合います。")
    return errors


def changed_sh_globs(workflow):
    # changes ジョブが changed.sh に渡す glob。呼んでいなければ None。
    for step in (workflow.get("jobs", {}).get("changes") or {}).get("steps", []):
        run = str(step.get("run", ""))
        if "changed.sh" in run:
            return set(shlex.split(run.replace("\\\n", " "))) - {"bash", "tooling/ci-changes/changed.sh"}
    return None


def check_paths(name, workflow):
    # push の paths: と changed.sh の引数は同じ一覧である。片方だけ直すと、PR と main で検査の対象が食い違う。
    globs = changed_sh_globs(workflow)
    push = triggers(workflow).get("push") or {}
    if globs is None or "paths" not in push:
        return []
    if set(push["paths"]) != globs:
        only_push, only_args = sorted(set(push["paths"]) - globs), sorted(globs - set(push["paths"]))
        return [f"{name} の push の paths: と changed.sh の引数が違います。push のみ: {only_push}、changed.sh のみ: {only_args}"]
    return []


def check_toolchain(name, workflow):
    uses = step_uses(workflow)
    errors = [f"{name} が {u} を使っています。道具は {SETUP_MISE} で mise.lock から入れてください。" for u in uses if u.startswith(BANNED_USES)]
    if SETUP_MISE not in uses:
        return errors
    lists = {f"{event} の paths:": (triggers(workflow).get(event) or {}).get("paths") for event in ("push", "pull_request")}
    lists["changed.sh の引数"] = changed_sh_globs(workflow)
    return errors + [
        f"{name} は setup-mise を使うのに、{label}に {sorted(TOOLCHAIN - set(globs))} がありません。"
        for label, globs in lists.items()
        if globs is not None and not TOOLCHAIN <= set(globs)
    ]


def main():
    directory = Path(sys.argv[1] if len(sys.argv) > 1 else ".github/workflows")
    excluded = read_excluded(Path(sys.argv[2] if len(sys.argv) > 2 else Path(__file__).with_name("excluded.tsv")))
    workflows = {p.name: yaml.safe_load(p.read_text(encoding="utf-8")) for p in sorted(directory.glob("*.y*ml"))}

    ci = workflows.get("ci.yml")
    if not ci or not ci.get("jobs"):
        raise SystemExit("ci.yml がないか、jobs がありません。")

    errors = check_gate(ci["jobs"])
    if "pull_request" not in triggers(ci):
        errors.append("ci.yml が pull_request で動きません。")

    called = set()
    for job_id, job in ci["jobs"].items():
        if m := USES_LOCAL.match(str(job.get("uses", ""))):
            called.add(m.group(1))
            if job_id != Path(m.group(1)).stem:
                errors.append(f"ci.yml のジョブ {job_id} は、呼ぶ {m.group(1)} のファイル名の stem {Path(m.group(1)).stem} に揃えてください。")
    for name in sorted(called):
        errors += check_called(name, workflows[name]) + check_paths(name, workflows[name]) if name in workflows else [f"ci.yml が呼ぶ {name} がありません。"]

    for name, workflow in workflows.items():
        errors += check_toolchain(name, workflow)
        if name != "ci.yml" and "pull_request" in triggers(workflow) and name not in called | excluded.keys():
            errors.append(f"{name} は pull_request で動きますが、ci.yml から呼ばれておらず、除外の一覧にもありません。")

    for name in excluded:
        if name in called:
            errors.append(f"{name} は ci.yml から呼ばれているので、除外の一覧から消してください。")
        elif name not in workflows or "pull_request" not in triggers(workflows[name]):
            errors.append(f"{name} は pull_request で動く workflow ではないので、除外の一覧から消してください。")

    for error in errors:
        print(f"error: {error}", file=sys.stderr)
    if errors:
        raise SystemExit(1)
    print(f"ci-gate-check OK: 呼ぶ workflow {len(called)} 本、除外 {len(excluded)} 本")


main()
