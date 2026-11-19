-------------------------- MODULE AggregateWrite --------------------------
(***************************************************************************)
(* Two requests save the same aggregate (a root row and its child rows) at *)
(* the same time, under PostgreSQL READ COMMITTED with row locks.          *)
(* The write path is JooqOrderRepository.update through TableWriter        *)
(* .updateCheckingVersion and LockedRoot, in the order that                *)
(* docs/database/postgresql-concurrency-control.md ("親子のテーブルの保存の *)
(* 順序") prescribes. The root is ordering.t_order and the children are    *)
(* ordering.t_order_line, unique on (order_id, line_no) (uk_t_order_line). *)
(*                                                                         *)
(* PostgreSQL behavior that the model relies on:                           *)
(*  - Each statement sees the rows committed when it starts, plus the      *)
(*    transaction's own writes.                                            *)
(*  - UPDATE and DELETE wait for a row that another open transaction has   *)
(*    written, then evaluate WHERE again on the newest committed version   *)
(*    (EvalPlanQual). The model evaluates a statement only when it is not  *)
(*    waiting, which gives the same result.                                *)
(*  - INSERT waits for an open transaction that wrote the same key, then   *)
(*    fails with 23505 if the key exists.                                  *)
(*  - A wait can end with lock_timeout (55P03), and a wait cycle ends with *)
(*    the deadlock detector aborting one transaction (40P01).              *)
(*  - Row locks are held until commit or rollback.                         *)
(***************************************************************************)
EXTENDS Naturals, Sequences, FiniteSets

Writers == {1, 2}
Keys == {1, 2}        \* line_no of the children
None == 0
NonEmpty == SUBSET Keys \ {{}}

VARIABLES
  root,      \* committed t_order row: lock_no, and which write it holds
  rootPend,  \* uncommitted UPDATE of the root: holder, lock_no, write
  child,     \* committed t_order_line rows: exists, and which write
  childPend, \* uncommitted write of each child row: holder, exists, write
  w          \* each request: program counter, plan, expected lock_no, ...

vars == <<root, rootPend, child, childPend, w>>

Outcomes == {"none", "committed", "conflict", "lock-conflict",
             "child-not-found", "unique-violation", "deadlock"}

TypeOK ==
  /\ \A i \in Writers: w[i].outcome \in Outcomes
  /\ \A i \in Writers: w[i].pc \in {"screen", "find", "root", "saved", "ops",
                                    "commit", "done"}

Init ==
  /\ root = [lock |-> 1, tag |-> None]
  /\ rootPend = [holder |-> None, lock |-> 0, tag |-> None]
  /\ \E start \in NonEmpty:
       child = [k \in Keys |-> [exists |-> k \in start, tag |-> None]]
  /\ childPend = [k \in Keys |-> [holder |-> None, exists |-> FALSE, tag |-> None]]
  \* Each request replaces the lines with a non-empty set (Order.changeLines
  \* requires lines; confirm and cancel keep them).
  /\ \E plan \in [Writers -> NonEmpty]:
       w = [i \in Writers |->
              [pc |-> "screen", plan |-> plan[i], expected |-> 0,
               saved |-> {}, ops |-> <<>>, idx |-> 1, outcome |-> "none"]]

\* What a statement of request i sees: its own uncommitted write, or the
\* committed row.
ChildSees(i, k) == IF childPend[k].holder = i THEN childPend[k].exists
                   ELSE child[k].exists

HeldByOther(i, holder) == holder \notin {None, i}

\* The row that request i's next statement must lock, and who holds it.
WaitsOn(i) ==
  CASE w[i].pc = "root" -> rootPend.holder
    [] w[i].pc = "ops" /\ w[i].idx <= Len(w[i].ops) ->
         childPend[w[i].ops[w[i].idx].key].holder
    [] OTHER -> None

Blocked(i) == HeldByOther(i, WaitsOn(i))

\* Rollback: drop the request's uncommitted writes and release its locks.
Release(i) ==
  /\ rootPend' = IF rootPend.holder = i
                   THEN [holder |-> None, lock |-> 0, tag |-> None] ELSE rootPend
  /\ childPend' = [k \in Keys |->
                     IF childPend[k].holder = i
                       THEN [holder |-> None, exists |-> FALSE, tag |-> None]
                       ELSE childPend[k]]

Fail(i, why) ==
  /\ Release(i)
  /\ w' = [w EXCEPT ![i].pc = "done", ![i].outcome = why]
  /\ UNCHANGED <<root, child>>

-----------------------------------------------------------------------------

\* The screen reads the order (GET) and keeps lock_no, which it sends back
\* as expectedLockNo. This is a separate, earlier request.
ScreenRead(i) ==
  /\ w[i].pc = "screen"
  /\ w' = [w EXCEPT ![i].pc = "find", ![i].expected = root.lock]
  /\ UNCHANGED <<root, rootPend, child, childPend>>

\* In the command's transaction: JooqOrderRepository.findById (one SELECT
\* with multiset), then Order.ensureLockNo. A mismatch is a
\* ConflictException (VERSION).
Find(i) ==
  /\ w[i].pc = "find"
  /\ IF root.lock = w[i].expected
       THEN /\ w' = [w EXCEPT ![i].pc = "root"]
            /\ UNCHANGED <<root, rootPend, child, childPend>>
       ELSE Fail(i, "conflict")

\* TableWriter.updateCheckingVersion: UPDATE t_order SET ..., lock_no =
\* expected + 1 WHERE public_id = ? AND lock_no = expected. Zero rows: the
\* row exists, so requireOneRow throws ConflictException (VERSION).
UpdateRoot(i) ==
  /\ w[i].pc = "root" /\ ~Blocked(i)
  /\ IF root.lock = w[i].expected
       THEN /\ rootPend' = [holder |-> i, lock |-> w[i].expected + 1, tag |-> i]
            /\ w' = [w EXCEPT ![i].pc = "saved"]
            /\ UNCHANGED <<root, child, childPend>>
       ELSE Fail(i, "conflict")

\* JooqOrderRepository.update: SELECT line_no FROM t_order_line WHERE
\* order_id = ?, then the statements in its order: one DELETE of the lines
\* not in the aggregate (LockedRoot.deleteChildren), then for each line in
\* ascending line_no an UPDATE (LockedRoot.updateChild) if it was saved, or
\* an INSERT (insertLine) if not.
ReadSaved(i) ==
  /\ w[i].pc = "saved"
  /\ LET saved == {k \in Keys: ChildSees(i, k)}
         plan == w[i].plan
         dels == [n \in 1..Cardinality(saved \ plan) |->
                    [op |-> "delete", key |-> CHOOSE k \in saved \ plan:
                       Cardinality({j \in saved \ plan: j < k}) = n - 1]]
         keep == [n \in 1..Cardinality(plan) |->
                    LET k == CHOOSE k \in plan:
                               Cardinality({j \in plan: j < k}) = n - 1
                    IN [op |-> IF k \in saved THEN "update" ELSE "insert", key |-> k]]
     IN w' = [w EXCEPT ![i].pc = "ops", ![i].saved = saved, ![i].ops = dels \o keep,
                       ![i].idx = 1]
  /\ UNCHANGED <<root, rootPend, child, childPend>>

\* One child statement, once its row is free.
\*  delete: deletes the row if this statement still sees it.
\*  update: 0 rows is BusinessRuleViolationException (422) from updateChild.
\*  insert: an existing key is 23505; insertLine does not go through
\*          TableWriter, so it is not converted (a 500).
ChildOp(i) ==
  /\ w[i].pc = "ops" /\ w[i].idx <= Len(w[i].ops) /\ ~Blocked(i)
  /\ LET op == w[i].ops[w[i].idx]
         k == op.key
         sees == ChildSees(i, k)
         next == [w EXCEPT ![i].idx = w[i].idx + 1]
     IN CASE op.op = "delete" ->
               /\ childPend' = IF sees
                                 THEN [childPend EXCEPT ![k] = [holder |-> i, exists |-> FALSE, tag |-> i]]
                                 ELSE childPend
               /\ w' = next
               /\ UNCHANGED <<root, rootPend, child>>
          [] op.op = "update" ->
               IF sees
                 THEN /\ childPend' = [childPend EXCEPT ![k] = [holder |-> i, exists |-> TRUE, tag |-> i]]
                      /\ w' = next
                      /\ UNCHANGED <<root, rootPend, child>>
                 ELSE Fail(i, "child-not-found")
          [] op.op = "insert" ->
               IF sees
                 THEN Fail(i, "unique-violation")
                 ELSE /\ childPend' = [childPend EXCEPT ![k] = [holder |-> i, exists |-> TRUE, tag |-> i]]
                      /\ w' = next
                      /\ UNCHANGED <<root, rootPend, child>>

OpsDone(i) ==
  /\ w[i].pc = "ops" /\ w[i].idx > Len(w[i].ops)
  /\ w' = [w EXCEPT ![i].pc = "commit"]
  /\ UNCHANGED <<root, rootPend, child, childPend>>

\* COMMIT: the request's writes become the committed rows.
Commit(i) ==
  /\ w[i].pc = "commit"
  /\ root' = IF rootPend.holder = i THEN [lock |-> rootPend.lock, tag |-> rootPend.tag]
             ELSE root
  /\ child' = [k \in Keys |->
                 IF childPend[k].holder = i
                   THEN [exists |-> childPend[k].exists, tag |-> childPend[k].tag]
                   ELSE child[k]]
  /\ Release(i)
  /\ w' = [w EXCEPT ![i].pc = "done", ![i].outcome = "committed"]

\* lock_timeout ends a wait: TableWriter turns 55P03 into ConflictException
\* (LOCK) for the root, updateChild, and deleteChildren. insertLine does not
\* convert it, but it is still a lock wait, so it is reported the same way.
LockTimeout(i) ==
  /\ Blocked(i)
  /\ Fail(i, "lock-conflict")

\* Each request waits for the other: the deadlock detector aborts one with
\* 40P01, which nothing converts (a 500).
Deadlock(i) ==
  /\ \A j \in Writers: Blocked(j)
  /\ Fail(i, "deadlock")

Done == /\ \A i \in Writers: w[i].pc = "done"
        /\ UNCHANGED vars

Next ==
  \/ \E i \in Writers:
       \/ ScreenRead(i) \/ Find(i) \/ UpdateRoot(i) \/ ReadSaved(i)
       \/ ChildOp(i) \/ OpsDone(i) \/ Commit(i) \/ LockTimeout(i) \/ Deadlock(i)
  \/ Done

Spec == Init /\ [][Next]_vars

-----------------------------------------------------------------------------
(* Properties.                                                             *)

\* Two committed saves never start from the same version: the second one
\* to commit saw the first one's result.
NoLostUpdate ==
  \A i, j \in Writers:
    (i # j /\ w[i].outcome = "committed" /\ w[j].outcome = "committed")
      => w[i].expected # w[j].expected

\* The committed aggregate is one save as a whole: its lines are exactly
\* that save's lines, all written by it.
AggregateWhole ==
  \/ root.tag = None
  \/ \A k \in Keys:
       /\ child[k].exists <=> k \in w[root.tag].plan
       /\ child[k].exists => child[k].tag = root.tag

\* A save that loses a race fails only with a 409 (ConflictException,
\* VERSION or LOCK) that tells the user to reload, never with a 422 or 500.
OnlyConflictErrors ==
  \A i \in Writers: w[i].outcome \in {"none", "committed", "conflict", "lock-conflict"}

=============================================================================
