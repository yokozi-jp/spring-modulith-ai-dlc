#!/usr/bin/env python3
"""Show that each invariant of a verification skill's model can fail (docs/agents/formal-verification.md).

Run: tooling/formal-verify/mutants.py <skill-dir>

Each [[mutant]] in <skill-dir>/model/mutants.toml mirrors one code regression. First the unmutated model must
pass the mutant's config with all of its invariants (the control); a mutant whose control fails would be caught
without its edits, so it proves nothing and fails the run. Then the edits go into a copy of the model, and TLC
must report each listed invariant when it checks that invariant alone. Exit 0 when every control passes and
every invariant is caught.
"""
import pathlib
import re
import subprocess
import sys
import tempfile
import tomllib

CHECK = pathlib.Path(__file__).resolve().parent / "check.sh"


def tla_value(value) -> str:
    return ("TRUE" if value else "FALSE") if isinstance(value, bool) else str(value)


def describe(name: str, overrides: dict) -> str:
    settings = ", ".join(f"{key} = {tla_value(value)}" for key, value in overrides.items())
    return f"{name} ({settings})" if settings else name


def config(base: str, overrides: dict, invariants: list, expect: str) -> str:
    """The base cfg with its first line set to `expect`, the overrides applied, and INVARIANTS replaced."""
    lines = base.splitlines()[1:]
    for key, value in overrides.items():
        hits = [n for n, line in enumerate(lines) if re.fullmatch(rf"\s+{re.escape(key)} = .*", line)]
        if len(hits) != 1:
            raise ValueError(f"constant {key} is not set exactly once in the base cfg")
        lines[hits[0]] = re.sub(r"=.*", f"= {tla_value(value)}", lines[hits[0]])
    start = lines.index("INVARIANTS")
    end = start + 1
    while end < len(lines) and lines[end].startswith((" ", "\t")):
        end += 1
    lines[start:end] = ["INVARIANTS", *(f"  {name}" for name in invariants)]
    return "\n".join([f"\\* expect: {expect}", *lines]) + "\n"


def apply_edits(source: str, edits: list) -> str:
    for n, edit in enumerate(edits, 1):
        count = source.count(edit["old"])
        if count != 1:
            raise ValueError(f"edit {n}: old text occurs {count} times, not once: {edit['old']!r}")
        source = source.replace(edit["old"], edit["new"])
    return source


def check(spec_name: str, spec: str, cfgs: dict) -> subprocess.CompletedProcess:
    """Run check.sh on a scratch skill dir holding `spec` and `cfgs` (name -> text)."""
    with tempfile.TemporaryDirectory(prefix="formal-verify-mutant-") as work:
        model = pathlib.Path(work, "model")
        model.mkdir()
        (model / spec_name).write_text(spec)
        for name, text in cfgs.items():
            (model / f"{name}.cfg").write_text(text)
        return subprocess.run([str(CHECK), work, *cfgs], capture_output=True, text=True)


def show(result: subprocess.CompletedProcess) -> None:
    output = (result.stdout + result.stderr).strip()
    print("         " + output.replace("\n", "\n         "))


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} <skill-dir>", file=sys.stderr)
        return 2
    model = pathlib.Path(sys.argv[1], "model")
    specs = list(model.glob("*.tla"))
    if len(specs) != 1:
        print(f"FAIL {model} must hold exactly one .tla, found {len(specs)}", file=sys.stderr)
        return 2
    source = specs[0].read_text()
    mutants = tomllib.loads((model / "mutants.toml").read_text())["mutant"]
    failed = 0
    for m in mutants:
        name, invariants = m["name"], m["invariants"]
        base = (model / f"{m['config']}.cfg").read_text()
        overrides = m.get("overrides", {})
        control_overrides = m.get("control_overrides", overrides)
        try:
            mutated = apply_edits(source, m["edits"])
            control_cfg = config(base, control_overrides, invariants, "pass")
            mutant_cfgs = {inv: config(base, overrides, [inv], inv) for inv in invariants}
        except ValueError as e:
            print(f"FAIL     {name}: {e}")
            failed += 1
            continue
        control = f"{describe(m['config'], control_overrides)} with {', '.join(invariants)}"
        result = check(specs[0].name, source, {"control": control_cfg})
        if result.returncode != 0:
            print(f"VACUOUS  {name}: the unmutated model does not pass {control}")
            show(result)
            failed += 1
            continue
        print(f"control  {name}: the unmutated model passes {control}")
        for inv, cfg in mutant_cfgs.items():
            result = check(specs[0].name, mutated, {inv: cfg})
            verdict = "caught" if result.returncode == 0 else "MISSED"
            print(f"{verdict:8} {name} -> {inv} on {describe(m['config'], overrides)}: {m['change']}")
            if result.returncode != 0:
                show(result)
                failed += 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
