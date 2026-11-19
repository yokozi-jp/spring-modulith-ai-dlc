#!/usr/bin/env python3
"""Show that each invariant can fail: apply one code regression to a copy of the model,
check it against a config, and require TLC to report the invariant that guards it.
Exit 0 when every mutant is caught.

Each mutant: (name, code change it mirrors, [(text in the model, replacement), ...],
base config in model/, constant overrides, invariant).
When you change the model, keep each anchor text present; the script fails if one is missing.
"""
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent.parent
MODEL = next((HERE / "model").glob("*.tla"))

MUTANTS = [
    (
        "children-before-root",
        "JooqOrderRepository.update writes the lines before tableWriter.updateCheckingVersion",
        [
            ('       THEN /\\ w\' = [w EXCEPT ![i].pc = "root"]', '       THEN /\\ w\' = [w EXCEPT ![i].pc = "saved"]'),
            ('  /\\ w\' = [w EXCEPT ![i].pc = "commit"]\n  /\\ UNCHANGED <<root, rootPend, child, childPend>>',
             '  /\\ w\' = [w EXCEPT ![i].pc = "root"]\n  /\\ UNCHANGED <<root, rootPend, child, childPend>>'),
            ('            /\\ w\' = [w EXCEPT ![i].pc = "saved"]', '            /\\ w\' = [w EXCEPT ![i].pc = "commit"]'),
        ],
        "two-saves",
        {},
        "OnlyConflictErrors",
    ),
    (
        "unversioned-root",
        "JooqOrderRepository.update uses tableWriter.updateWhere (lock_no = lock_no + 1) for the root",
        [
            ("  /\\ IF root.lock = w[i].expected\n       THEN /\\ rootPend\' = [holder |-> i, lock |-> w[i].expected + 1, tag |-> i]",
             "  /\\ IF TRUE\n       THEN /\\ rootPend\' = [holder |-> i, lock |-> root.lock + 1, tag |-> i]"),
        ],
        "two-saves",
        {},
        "NoLostUpdate",
    ),
    (
        "no-delete-of-removed-lines",
        "JooqOrderRepository.update drops root.deleteChildren for lines not in the aggregate",
        [("![i].ops = dels \\o keep", "![i].ops = keep")],
        "two-saves",
        {},
        "AggregateWhole",
    ),
]


def config(base: str, overrides: dict, invariant: str) -> str:
    text = (HERE / "model" / f"{base}.cfg").read_text()
    for key, value in overrides.items():
        text, n = re.subn(rf"^  {key} = \d+$", f"  {key} = {value}", text, flags=re.M)
        assert n == 1, key
    head = text[: text.index("INVARIANTS")]
    return head + f"INVARIANTS\n  TypeOK\n  {invariant}\n"


def main() -> int:
    source = MODEL.read_text()
    failed = 0
    for name, change, edits, base, overrides, invariant in MUTANTS:
        mutated = source
        missing = [old for old, _ in edits if mutated.count(old) != 1]
        if missing:
            print(f"FAIL {name}: anchor text not found exactly once; update MUTANTS: {missing[0]!r}")
            failed += 1
            continue
        for old, new in edits:
            mutated = mutated.replace(old, new)
        work = pathlib.Path(tempfile.mkdtemp(prefix=f"mutant-{name}-"))
        (work / MODEL.name).write_text(mutated)
        (work / "mutant.cfg").write_text(config(base, overrides, invariant))
        env = dict(os.environ, MODEL_DIR=str(work), EXPECT=invariant)
        result = subprocess.run(
            [str(HERE / "scripts" / "check.sh"), "mutant"], env=env, capture_output=True, text=True
        )
        verdict = "caught" if result.returncode == 0 else "MISSED"
        print(f"{verdict:6} {name} -> {invariant}: {change}")
        output = (result.stdout + result.stderr).strip()
        print("       " + output.replace("\n", "\n       "))
        if result.returncode != 0:
            failed += 1
        else:
            shutil.rmtree(work, ignore_errors=True)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
