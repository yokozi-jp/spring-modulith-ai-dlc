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
        "no-time-budget",
        "EventPublicationResubmitter.resubmit drops the lock-at-most-for / 2 budget from withFilter",
        [("                /\\ now - inst[i].runStart <= Budget\n", "")],
        "run-overruns-lock",
        {},
        "ResubmissionsCapped",
    ),
    (
        "no-time-budget",
        "same change, checked against the one-delivery promise",
        [("                /\\ now - inst[i].runStart <= Budget\n", "")],
        "run-overruns-lock",
        {},
        "OneDeliveryAtATime",
    ),
    (
        "is-due-ignores-cap",
        "ResubmissionPolicies.isDue drops attempts <= maxResubmissions",
        [("                /\\ inst[i].snapAttempts <= MaxResubmissions\n", "")],
        "documented",
        {},
        "ResubmissionsCapped",
    ),
    (
        "lock-ignores-expiry",
        "the lock provider takes the lock without lock_until <= now (or a second LockProvider bean)",
        [("  /\\ IF lock.until <= now\n", "  /\\ IF TRUE\n")],
        "documented",
        {},
        "OneLockedRun",
    ),
    (
        "staleness-from-publication-date",
        "Spring Modulith back to 2.1.1: the staleness monitor measures from publication_date",
        [("  /\\ now > row.lastAttempt + Threshold(row.status)", "  /\\ now > Threshold(row.status)"),
         ("  Max(row.since, row.lastAttempt + Threshold(row.status) + 1) + Interval + RunMax",
          "  Max(row.since, Threshold(row.status) + 1) + Interval + RunMax")],
        "in-flight-slow-runs",
        {},
        "InFlightNeverMarkedStale",
    ),
    (
        "startup-check-too-weak",
        "the startup check accepts in-flight-staleness shorter than a thread wait plus a listener run",
        [("       /\\ InFlightStaleness > 2 * LockAtMostFor \\* isInFlightStalenessLongerThanTwiceLockAtMostFor",
          "       /\\ InFlightStaleness >= 1")],
        "listener-up-to-lock",
        {"InFlightStaleness": 5, "QueueMax": 3},
        "InFlightNeverMarkedStale",
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
        print("       " + result.stdout.strip().replace("\n", "\n       "))
        if result.returncode != 0:
            failed += 1
        else:
            shutil.rmtree(work, ignore_errors=True)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
