#!/usr/bin/env python3
"""Show that each invariant can fail: apply one code regression to a copy of the model
and require TLC to report the invariant it guards. Exit 0 when every mutant is caught.

Each mutant is (name, code change it mirrors, text in OrderPayment.tla, replacement, invariant).
When you change the model, keep each `old` text present; the script fails if one is missing.
"""
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent.parent
MODEL = HERE / "model" / "OrderPayment.tla"

MUTANTS = [
    (
        "no-unique-index",
        "drop uk_2_t_payment (order_public_id) from 006-create-payment-tables.yaml",
        "  /\\ IF Len(payments) = 0\n       THEN /\\ payments' = Append",
        "  /\\ IF TRUE\n       THEN /\\ payments' = Append",
        "AtMostOnePaymentRecord",
    ),
    (
        "timeout-recorded-as-failed",
        "PaymentGatewayClient.charge returns ChargeOutcome.failed() for every RestClientException",
        "        /\\ dpc' = [dpc EXCEPT ![d] = \"insert\"]\n     \\/ dpc' = [dpc EXCEPT ![d] = \"markFailed\"]",
        "        /\\ dpc' = [dpc EXCEPT ![d] = \"insert\"]\n        /\\ UNCHANGED dres\n     \\/ /\\ dpc' = [dpc EXCEPT ![d] = \"insert\"]\n        /\\ dres' = [dres EXCEPT ![d] = \"REJECTED\"]",
        "RecordMatchesGateway",
    ),
    (
        "cancel-confirmed-order",
        "Order.cancel drops ensureDraft()",
        "IF orderStatus = \"DRAFT\" /\\ orderLock = cmdLock[c]",
        "IF (orderStatus = \"DRAFT\" \\/ c = \"cancel\") /\\ orderLock = cmdLock[c]",
        "ChargedOnlyIfConfirmed",
    ),
    (
        "listener-swallows-gateway-error",
        "OrderConfirmedListener.on catches the exception from chargeOrder.handle and returns",
        "        /\\ dpc' = [dpc EXCEPT ![d] = \"insert\"]\n     \\/ dpc' = [dpc EXCEPT ![d] = \"markFailed\"]",
        "        /\\ dpc' = [dpc EXCEPT ![d] = \"insert\"]\n     \\/ dpc' = [dpc EXCEPT ![d] = \"markCompleted\"]",
        "CompletedOnlyWithRecord",
    ),
    (
        "listener-swallows-gateway-error",
        "same change, checked against the end-state promise alone",
        "        /\\ dpc' = [dpc EXCEPT ![d] = \"insert\"]\n     \\/ dpc' = [dpc EXCEPT ![d] = \"markFailed\"]",
        "        /\\ dpc' = [dpc EXCEPT ![d] = \"insert\"]\n     \\/ dpc' = [dpc EXCEPT ![d] = \"markCompleted\"]",
        "ChargeNeverSilentlyLost",
    ),
]

# The mutated DReply branch also changes dres, so drop dres from its UNCHANGED list.
DREPLY_UNCHANGED = "                 gateway, dres, launched, rpc>>\n\nRecordOf"


def main() -> int:
    source = MODEL.read_text()
    failed = 0
    for name, change, old, new, invariant in MUTANTS:
        if source.count(old) != 1:
            print(f"FAIL {name}: anchor text not found exactly once; update MUTANTS")
            failed += 1
            continue
        mutated = source.replace(old, new)
        if "dres' = [dres EXCEPT ![d] = \"REJECTED\"]" in new:
            mutated = mutated.replace(DREPLY_UNCHANGED, "                 gateway, launched, rpc>>\n\nRecordOf")
        work = pathlib.Path(tempfile.mkdtemp(prefix=f"mutant-{name}-"))
        (work / "OrderPayment.tla").write_text(mutated)
        (work / "mutant.cfg").write_text(
            "CONSTANTS\n  MaxDeliveries = 3\n  GatewayRejectsConcurrentKey = FALSE\n"
            f"SPECIFICATION Spec\nINVARIANTS\n  TypeOK\n  {invariant}\n"
        )
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
