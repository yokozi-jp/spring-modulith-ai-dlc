------------------------- MODULE EventResubmission -------------------------
(***************************************************************************)
(* One event publication in modulith.event_publication, two app instances, *)
(* and discrete time. Covers the periodic resubmission job                 *)
(* (EventPublicationResubmitter) under the ShedLock lock, the Spring       *)
(* Modulith 2.1.2 staleness monitor, listener deliveries, and instance     *)
(* crashes.                                                                *)
(*                                                                         *)
(* Paths are relative to backend/src/main/java/com/example/demo/shared/    *)
(* infrastructure/persistence/. Spring Modulith and ShedLock are cited by  *)
(* class name. Each action is one SQL statement or one in-memory decision. *)
(* One time unit stands for any fixed duration; the constants are the      *)
(* settings in that unit.                                                  *)
(***************************************************************************)
EXTENDS Naturals

CONSTANTS
  Interval,          \* event-publication.resubmission.interval
  LockAtMostFor,     \* event-publication.resubmission.lock-at-most-for
  WaitAge,           \* event-publication.resubmission.wait-age
  Multiplier,        \* policies.<name>.multiplier (an integer here)
  MaxWait,           \* policies.<name>.max-wait
  MaxResubmissions,  \* policies.<name>.max-resubmissions
  InFlightStaleness, \* event-publication.resubmission.in-flight-staleness
  \* Timing of the environment. These are not settings: they are what the
  \* comments in application.yaml and EventPublicationResubmissionConfig
  \* assume about the running system.
  ListenerMax,       \* longest listener run
  QueueMax,          \* longest wait for a free thread (concurrency-limit)
  RunMax,            \* longest resubmission run or staleness check
  MaxCrashes,
  Horizon

Instances == {1, 2}
None == 0

\* EventPublicationResubmissionConfig.Properties.lockAtLeastFor
LockAtLeastFor == Interval \div 2
\* EventPublicationResubmitter.resubmit: budget = lockAtMostFor / 2
Budget == LockAtMostFor \div 2

Min(a, b) == IF a < b THEN a ELSE b
Max(a, b) == IF a > b THEN a ELSE b
RECURSIVE Pow(_, _)
Pow(b, e) == IF e = 0 THEN 1 ELSE b * Pow(b, e - 1)

\* EventPublicationResubmissionConfig.Policy.waitBefore(waitAge, attempts)
WaitBefore(attempts) == Min(WaitAge * Pow(Multiplier, attempts - 1), MaxWait)

\* The startup checks on EventPublicationResubmissionConfig.Properties.
\* The model only covers settings the app accepts.
ASSUME /\ LockAtMostFor > Interval            \* isLockAtMostForLongerThanInterval
       /\ MaxWait >= WaitAge                  \* isMaxWaitNotShorterThanWaitAge
       /\ InFlightStaleness > 2 * LockAtMostFor \* isInFlightStalenessLongerThanTwiceLockAtMostFor
       \* lock-at-most-for bounds the wait for a thread inside a run, and the
       \* listener run (ADR-079).
       /\ QueueMax <= LockAtMostFor /\ ListenerMax <= LockAtMostFor
       /\ WaitAge >= 1 /\ Interval >= 1 /\ MaxResubmissions >= 1

\* Delivery slots: slot 1 is the first delivery; slot a is the delivery
\* started when completion_attempts became a. Extra slots show an overrun.
Slots == 1..(MaxResubmissions + 2)

VARIABLES
  now,
  row,    \* event_publication: status, completion_attempts,
          \* last_resubmission_date, and when the status last changed
  lock,   \* modulith.shedlock: lock_until, locked_at, locked_by
  inst,   \* each instance: alive, and the state of its resubmission run
  exec,   \* each listener delivery: state, instance, time queued or started
  mon,    \* each instance's staleness monitor: whether its last find
          \* listed the row and the mark is still to come, when, and the
          \* status the find saw
  crashes,
  staleHitInFlight  \* the staleness monitor marked the row FAILED while a
                    \* delivery was queued or running

vars == <<now, row, lock, inst, exec, mon, crashes, staleHitInFlight>>

Statuses == {"PUBLISHED", "PROCESSING", "FAILED", "RESUBMITTED", "ARCHIVED"}

TypeOK ==
  /\ now \in 0..Horizon
  /\ row.status \in Statuses
  /\ row.attempts \in 1..(MaxResubmissions + 3)
  /\ \A i \in Instances: inst[i].phase \in {"idle", "locked", "read"}
  /\ \A k \in Slots: exec[k].st \in {"none", "queued", "running", "done"}
  /\ \A i \in Instances: mon[i].found \in BOOLEAN

\* The resubmitter's first run is after initialDelay = interval.
IdleRun(t) == [up |-> TRUE, next |-> t, phase |-> "idle", runStart |-> 0,
               snap |-> FALSE, snapAttempts |-> 0, snapLast |-> 0]

NoFind == [found |-> FALSE, at |-> 0, st |-> "PUBLISHED"]

Init ==
  /\ now = 0
  \* JdbcEventPublicationRepositoryV2.create: status PUBLISHED,
  \* completion_attempts 1, last_resubmission_date = publication_date = 0.
  /\ row = [status |-> "PUBLISHED", attempts |-> 1, lastAttempt |-> 0,
            since |-> 0]
  /\ lock = [until |-> 0, at |-> 0, by |-> None]
  /\ inst = [i \in Instances |-> IdleRun(Interval)]
  \* The first delivery is queued on the publishing instance after commit.
  /\ exec = [k \in Slots |->
               IF k = 1 THEN [st |-> "queued", on |-> 1, t |-> 0]
                        ELSE [st |-> "none", on |-> None, t |-> 0]]
  /\ mon = [i \in Instances |-> NoFind]
  /\ crashes = 0
  /\ staleHitInFlight = FALSE

RowExists == row.status # "ARCHIVED"
InFlight(k) == exec[k].st \in {"queued", "running"}
SetStatus(s) == IF row.status = s THEN row
                ELSE [row EXCEPT !.status = s, !.since = now]

-----------------------------------------------------------------------------
(* EventPublicationResubmitter.resubmitOnce: one run of one instance.       *)
(* The job and the staleness monitor are fixed-delay tasks, which          *)
(* SimpleAsyncTaskScheduler (spring.threads.virtual.enabled) runs one at a *)
(* time on its single scheduler thread. So an instance's run and its       *)
(* monitor never overlap, and a run that is due waits for the monitor.     *)

ThreadFree(i) == inst[i].phase = "idle" /\ ~mon[i].found

\* resubmitOnce captures now, then DefaultLockingTaskExecutor.executeWithLock
\* -> JooqStorageAccessor.updateRecord: UPDATE shedlock SET lock_until =
\* now + lockAtMostFor, locked_at = now, locked_by = host WHERE name = ? AND
\* lock_until <= now. The lock uses DB time; the model has one clock.
\* Not acquired: the run is skipped and the next run is interval later.
RunStart(i) ==
  /\ inst[i].up /\ ThreadFree(i) /\ inst[i].next <= now
  /\ IF lock.until <= now
       THEN /\ lock' = [until |-> now + LockAtMostFor, at |-> now, by |-> i]
            /\ inst' = [inst EXCEPT ![i].phase = "locked", ![i].runStart = now]
       ELSE /\ inst' = [inst EXCEPT ![i].next = now + Interval]
            /\ UNCHANGED lock
  /\ UNCHANGED <<now, row, exec, mon, crashes, staleHitInFlight>>

\* FailedEventPublications.resubmit -> DefaultEventPublicationRegistry
\* .processFailedPublications -> findFailedPublications:
\* SELECT ... WHERE status = 'FAILED'. The run keeps this snapshot while
\* it handles the other publications of the list (up to max-per-run, each
\* dispatch can wait for concurrency-limit).
RunRead(i) ==
  /\ inst[i].up /\ inst[i].phase = "locked"
  /\ inst' = [inst EXCEPT ![i].phase = "read",
                          ![i].snap = (row.status = "FAILED"),
                          ![i].snapAttempts = row.attempts,
                          ![i].snapLast = row.lastAttempt]
  /\ UNCHANGED <<now, row, lock, exec, mon, crashes, staleHitInFlight>>

\* For the snapshot: the withFilter predicate (the time budget from the
\* captured now, then ResubmissionPolicies.isDue: attempts <=
\* max-resubmissions and lastAttempt + waitBefore(attempts) before the
\* captured now), then JdbcEventPublicationRepositoryV2.markResubmitted:
\* UPDATE SET status = 'RESUBMITTED', completion_attempts + 1,
\* last_resubmission_date = now WHERE id = ? AND status != 'RESUBMITTED'.
\* One row means the listener is dispatched to the executor here.
\* Then JooqStorageAccessor.unlock: UPDATE shedlock SET lock_until =
\* max(locked_at + lockAtLeastFor, now) WHERE name = ? AND locked_by = host,
\* and the next run is interval later (fixedDelay).
RunFinish(i) ==
  /\ inst[i].up /\ inst[i].phase = "read"
  /\ LET due == /\ inst[i].snap
                /\ now - inst[i].runStart <= Budget
                /\ inst[i].snapAttempts <= MaxResubmissions
                /\ inst[i].snapLast + WaitBefore(inst[i].snapAttempts)
                     < inst[i].runStart
          a == row.attempts + 1
     IN IF due /\ row.status \in {"PUBLISHED", "PROCESSING", "FAILED"}
          THEN /\ row' = [row EXCEPT !.status = "RESUBMITTED", !.attempts = a,
                                     !.lastAttempt = now, !.since = now]
               /\ exec' = IF a \in Slots
                            THEN [exec EXCEPT ![a] = [st |-> "queued", on |-> i, t |-> now]]
                            ELSE exec
          ELSE UNCHANGED <<row, exec>>
  /\ lock' = IF lock.by = i
               THEN [lock EXCEPT !.until = Max(lock.at + LockAtLeastFor, now)]
               ELSE lock
  /\ inst' = [inst EXCEPT ![i] = IdleRun(now + Interval)]
  /\ UNCHANGED <<now, mon, crashes, staleHitInFlight>>

-----------------------------------------------------------------------------
(* Spring Modulith's staleness monitor on every instance:                  *)
(* StalenessMonitorConfiguration runs DefaultEventPublicationRegistry      *)
(* .markStalePublicationsFailed with fixed delay check-interval            *)
(* (= interval). For each of PUBLISHED, PROCESSING, RESUBMITTED,           *)
(* markFailed(Status, Staleness) runs findByStatus, keeps the rows whose   *)
(* getLastPublicationDate() (last_resubmission_date, which the insert sets *)
(* to publication_date) is before now - staleness, then calls              *)
(* JdbcEventPublicationRepositoryV2.markFailed(id) for each of them:       *)
(* UPDATE SET status = 'FAILED' WHERE id = ? AND completion_date IS NULL   *)
(* AND status != 'FAILED'. The UPDATE does not check the status or the     *)
(* date again, so the row can change between the find and the mark.       *)

Threshold(st) == IF st = "PUBLISHED" THEN WaitAge ELSE InFlightStaleness

StaleNow ==
  /\ row.status \in {"PUBLISHED", "PROCESSING", "RESUBMITTED"}
  /\ now > row.lastAttempt + Threshold(row.status)

\* A find must list a stale row within interval + RunMax of the row
\* becoming stale (the fixed delay plus one run), or as soon as an
\* instance's scheduler thread is free after that.
StaleDeadline ==
  Max(row.since, row.lastAttempt + Threshold(row.status) + 1) + Interval + RunMax

\* findByStatus and the date filter list the row. One row, so the find
\* keeps a flag and the status it saw. A run does its finds and marks one
\* after another, so an instance has at most one mark to come.
StaleFind(i) ==
  /\ inst[i].up /\ ThreadFree(i) /\ StaleNow
  /\ mon' = [mon EXCEPT ![i] = [found |-> TRUE, at |-> now, st |-> row.status]]
  /\ UNCHANGED <<now, row, lock, inst, exec, crashes, staleHitInFlight>>

\* markFailed(id) for the listed row, whatever its status is now.
StaleMark(i) ==
  /\ inst[i].up /\ mon[i].found
  /\ mon' = [mon EXCEPT ![i] = NoFind]
  /\ IF RowExists /\ row.status # "FAILED"
       THEN /\ row' = SetStatus("FAILED")
            /\ staleHitInFlight' = (staleHitInFlight \/ \E k \in Slots: InFlight(k))
       ELSE UNCHANGED <<row, staleHitInFlight>>
  /\ UNCHANGED <<now, lock, inst, exec, crashes>>

-----------------------------------------------------------------------------
(* Listener deliveries: CompletionRegisteringMethodInterceptor around       *)
(* the listener, on the executor (spring.task.execution, concurrency-limit).*)

\* markProcessing: UPDATE SET status = 'PROCESSING' WHERE id = ? AND
\* status != 'PROCESSING'.
ExecStart(k) ==
  /\ exec[k].st = "queued" /\ inst[exec[k].on].up
  /\ exec' = [exec EXCEPT ![k].st = "running", ![k].t = now]
  /\ row' = IF RowExists THEN SetStatus("PROCESSING") ELSE row
  /\ UNCHANGED <<now, lock, inst, mon, crashes, staleHitInFlight>>

\* markCompleted, completion-mode archive: copy to event_publication_archive
\* and delete the row.
ExecSucceed(k) ==
  /\ exec[k].st = "running"
  /\ exec' = [exec EXCEPT ![k] = [st |-> "done", on |-> None, t |-> 0]]
  /\ row' = SetStatus("ARCHIVED")
  /\ UNCHANGED <<now, lock, inst, mon, crashes, staleHitInFlight>>

\* markFailed: UPDATE SET status = 'FAILED' WHERE id = ? AND status != 'FAILED'.
ExecFail(k) ==
  /\ exec[k].st = "running"
  /\ exec' = [exec EXCEPT ![k] = [st |-> "done", on |-> None, t |-> 0]]
  /\ row' = IF RowExists /\ row.status # "FAILED" THEN SetStatus("FAILED") ELSE row
  /\ UNCHANGED <<now, lock, inst, mon, crashes, staleHitInFlight>>

-----------------------------------------------------------------------------
(* Crash and restart. A crash keeps the lock row until lock_until, and     *)
(* kills the deliveries and the staleness monitor run on that instance     *)
(* without any mark.                                                       *)

Crash(i) ==
  /\ inst[i].up /\ crashes < MaxCrashes
  /\ inst' = [inst EXCEPT ![i] = [IdleRun(0) EXCEPT !.up = FALSE]]
  /\ exec' = [k \in Slots |-> IF InFlight(k) /\ exec[k].on = i
                                THEN [st |-> "done", on |-> None, t |-> 0]
                                ELSE exec[k]]
  /\ mon' = [mon EXCEPT ![i] = NoFind]
  /\ crashes' = crashes + 1
  /\ UNCHANGED <<now, row, lock, staleHitInFlight>>

Restart(i) ==
  /\ ~inst[i].up
  /\ inst' = [inst EXCEPT ![i] = IdleRun(now + Interval)]
  /\ UNCHANGED <<now, row, lock, exec, mon, crashes, staleHitInFlight>>

-----------------------------------------------------------------------------
(* Time. A step that is due now must happen before time moves on.          *)

MustActNow ==
  \/ \E i \in Instances:
       /\ inst[i].up
       /\ \/ ThreadFree(i) /\ inst[i].next <= now
          \/ inst[i].phase # "idle" /\ inst[i].runStart + RunMax = now
  \/ \E i \in Instances: inst[i].up /\ mon[i].found /\ mon[i].at + RunMax = now
  \* A mark to come discharges the find: it marks the row within RunMax.
  \/ /\ StaleNow /\ StaleDeadline <= now
     /\ \E i \in Instances: inst[i].up /\ ThreadFree(i)
     /\ ~\E i \in Instances: mon[i].found
  \/ \E k \in Slots:
       \/ exec[k].st = "queued" /\ exec[k].t + QueueMax = now
       \/ exec[k].st = "running" /\ exec[k].t + ListenerMax = now

Tick ==
  /\ now < Horizon /\ ~MustActNow
  /\ now' = now + 1
  /\ UNCHANGED <<row, lock, inst, exec, mon, crashes, staleHitInFlight>>

AtHorizon == now = Horizon /\ ~MustActNow /\ UNCHANGED vars

Next ==
  \/ \E i \in Instances:
       \/ RunStart(i) \/ RunRead(i) \/ RunFinish(i) \/ Crash(i) \/ Restart(i)
       \/ StaleFind(i) \/ StaleMark(i)
  \/ \E k \in Slots: ExecStart(k) \/ ExecSucceed(k) \/ ExecFail(k)
  \/ Tick \/ AtHorizon

Spec == Init /\ [][Next]_vars

-----------------------------------------------------------------------------
(* Properties.                                                             *)

\* The listener never runs twice at the same time for one publication.
\* This is what in-flight-staleness and its startup check exist for.
OneDeliveryAtATime ==
  \A j, k \in Slots: j # k => ~(InFlight(j) /\ InFlight(k))

\* The staleness monitor never marks a delivery that is still queued or
\* running as FAILED. This is what the startup check on in-flight-staleness
\* (isInFlightStalenessLongerThanTwiceLockAtMostFor) promises.
InFlightNeverMarkedStale == ~staleHitInFlight

\* The job resubmits a publication at most max-resubmissions times.
ResubmissionsCapped == row.attempts <= MaxResubmissions + 1

\* At most one instance is inside the locked part of a run.
OneLockedRun ==
  \A i, j \in Instances:
    i # j => ~(inst[i].phase # "idle" /\ inst[j].phase # "idle")

=============================================================================
