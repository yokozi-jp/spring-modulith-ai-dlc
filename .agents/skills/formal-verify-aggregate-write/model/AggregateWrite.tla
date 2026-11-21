-------------------------- MODULE AggregateWrite --------------------------
(***************************************************************************)
(* Two requests save the same aggregate (a root row and its child rows) at *)
(* the same time, under PostgreSQL READ COMMITTED with row locks.          *)
(* The write path is JooqOrderRepository.update through TableWriter        *)
(* .update and ChildWriter.replace, in the order that                      *)
(* docs/database/postgresql-concurrency-control.md ("親子のテーブルの保存の *)
(* 順序") prescribes. The root is ordering.t_order and the children are    *)
(* ordering.t_order_line, unique on (tenant_public_id, order_id, line_no)  *)
(* (uk_t_order_line).                                                      *)
(* The children are written as a difference: one DELETE of the saved rows  *)
(* the aggregate no longer has (keys fixed from the SELECT), one UPDATE    *)
(* per remaining row, one multi-row INSERT of the new rows.                *)
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
(* The root row lock orders the two requests' child statements, so they   *)
(* never wait for each other. When it does not (the children-before-root  *)
(* mutant), the model is coarser than PostgreSQL: an UPDATE waits for a    *)
(* row the other request inserted and has not committed, and a statement   *)
(* that waited sees the rows the other request committed meanwhile.        *)
(* PostgreSQL's UPDATE and DELETE do neither, because their snapshot is    *)
(* taken when the statement starts.                                        *)
(*                                                                         *)
(* The model leaves out the foreign key check of a child INSERT            *)
(* (fk_t_order_line). It locks the parent row FOR KEY SHARE, which only    *)
(* FOR UPDATE (a DELETE, or an UPDATE of a unique-key column) conflicts    *)
(* with. In the order checked here the request already holds the root row  *)
(* lock, so the check never waits.                                         *)
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
             "child-update-miss", "unique-conflict", "deadlock"}

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
               ops |-> <<>>, idx |-> 1, outcome |-> "none"]]

\* What a statement of request i sees: its own uncommitted write, or the
\* committed row.
ChildSees(i, k) == IF childPend[k].holder = i THEN childPend[k].exists
                   ELSE child[k].exists

HeldByOther(i, holder) == holder \notin {None, i}

\* The saved child rows an operation's DELETE matches when it runs.
DeleteTargets(i, keys) == {k \in keys: ChildSees(i, k)}

NextOp(i) == w[i].ops[w[i].idx]

\* Request i's next statement waits for a row lock the other request holds.
Blocked(i) ==
  CASE w[i].pc = "root" -> HeldByOther(i, rootPend.holder)
    [] w[i].pc = "ops" /\ w[i].idx <= Len(w[i].ops) ->
         IF NextOp(i).op = "delete"
           THEN \E k \in DeleteTargets(i, NextOp(i).keys):
                  HeldByOther(i, childPend[k].holder)
           ELSE \E k \in NextOp(i).keys: HeldByOther(i, childPend[k].holder)
    [] OTHER -> FALSE

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

\* TableWriter.update: UPDATE t_order SET ..., lock_no = expected + 1 WHERE
\* public_id = ? AND lock_no = expected RETURNING order_id. Zero rows: the
\* row exists, so requireOneRow throws ConflictException (VERSION).
UpdateRoot(i) ==
  /\ w[i].pc = "root" /\ ~Blocked(i)
  /\ IF root.lock = w[i].expected
       THEN /\ rootPend' = [holder |-> i, lock |-> w[i].expected + 1, tag |-> i]
            /\ w' = [w EXCEPT ![i].pc = "saved"]
            /\ UNCHANGED <<root, child, childPend>>
       ELSE Fail(i, "conflict")

\* ChildWriter.replace: SELECT line_no FROM t_order_line WHERE order_id = ?
\* (the saved set), then, in this order: one DELETE of the saved rows the
\* aggregate no longer has (removed = saved \ plan; skipped when empty), one
\* UPDATE per remaining row (kept = saved \cap plan; the code updates them in
\* the order the lines were passed, the model in ascending line_no),
\* and one multi-row INSERT of the new rows (added = plan \ saved; skipped
\* when empty).
Ascending(S) ==
  [n \in 1..Cardinality(S) |->
     CHOOSE k \in S: Cardinality({j \in S: j < k}) = n - 1]

ReadSaved(i) ==
  /\ w[i].pc = "saved"
  /\ LET saved == {k \in Keys: ChildSees(i, k)}
         plan == w[i].plan
         removed == saved \ plan
         added == plan \ saved
         deletes == IF removed = {} THEN <<>>
                    ELSE <<[op |-> "delete", keys |-> removed]>>
         updates == [n \in 1..Cardinality(saved \cap plan) |->
                       [op |-> "update",
                        keys |-> {Ascending(saved \cap plan)[n]}]]
         inserts == IF added = {} THEN <<>>
                    ELSE <<[op |-> "insert", keys |-> added]>>
     IN w' = [w EXCEPT ![i].pc = "ops", ![i].ops = deletes \o updates \o inserts,
                       ![i].idx = 1]
  /\ UNCHANGED <<root, rootPend, child, childPend>>

\* One child statement, once the rows it locks are free.
\*  delete: deletes every row it matches when it runs.
\*  update: 0 rows is IllegalStateException (a 500) from ChildWriter.
\*  insert: an existing key is 23505, which TableWriter's translator turns
\*          into ConflictException (UNIQUE, a 409).
ChildOp(i) ==
  /\ w[i].pc = "ops" /\ w[i].idx <= Len(w[i].ops) /\ ~Blocked(i)
  /\ LET op == NextOp(i)
         next == [w EXCEPT ![i].idx = w[i].idx + 1]
     IN CASE op.op = "delete" ->
               /\ childPend' = [j \in Keys |->
                                 IF j \in DeleteTargets(i, op.keys)
                                   THEN [holder |-> i, exists |-> FALSE, tag |-> i]
                                   ELSE childPend[j]]
               /\ w' = next
               /\ UNCHANGED <<root, rootPend, child>>
          [] op.op = "update" ->
               LET k == CHOOSE k \in op.keys: TRUE
               IN IF ChildSees(i, k)
                    THEN /\ childPend' = [childPend EXCEPT ![k] = [holder |-> i, exists |-> TRUE, tag |-> i]]
                         /\ w' = next
                         /\ UNCHANGED <<root, rootPend, child>>
                    ELSE Fail(i, "child-update-miss")
          [] op.op = "insert" ->
               IF \E k \in op.keys: ChildSees(i, k)
                 THEN Fail(i, "unique-conflict")
                 ELSE /\ childPend' = [k \in Keys |->
                                         IF k \in op.keys
                                           THEN [holder |-> i, exists |-> TRUE, tag |-> i]
                                           ELSE childPend[k]]
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

\* lock_timeout ends a wait: TableWriter's translator turns 55P03 into
\* ConflictException (LOCK, a 409) for every statement, including the
\* children's.
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
\* A child UNIQUE conflict is a 409 too, but it means the children collided,
\* which the root row lock must make unreachable.
OnlyConflictErrors ==
  \A i \in Writers: w[i].outcome \in {"none", "committed", "conflict", "lock-conflict"}

=============================================================================
