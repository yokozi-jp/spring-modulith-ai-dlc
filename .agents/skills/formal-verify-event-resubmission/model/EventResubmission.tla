------------------------- MODULE EventResubmission -------------------------
(***************************************************************************)
(* One event publication in modulith.event_publication, two app instances, *)
(* and discrete time. Covers the periodic resubmission job                 *)
(* (EventPublicationResubmitter) under the ShedLock lock, which first      *)
(* marks stale publications FAILED with one UPDATE                         *)
(* (StalePublications.markFailed) and then resubmits, listener deliveries  *)
(* with the abandon-after-attempts default AbandonPolicy, and instance     *)
(* crashes. The built-in staleness monitor is off: setting its thresholds  *)
(* fails startup (EventPublicationResubmissionConfig.                      *)
(* StalenessMonitorProperties).                                            *)
(*                                                                         *)
(* Paths are relative to backend/src/main/java/com/example/demo/shared/    *)
(* eventresubmission/. Spring Modulith and ShedLock are cited by           *)
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
  MaxResubmissions,  \* spring.modulith.events.resubmission.abandon-after-attempts
  PolicyAbandon,     \* policies.<name>.abandon-after-attempts, or MaxResubmissions when unset
  InFlightStaleness, \* event-publication.resubmission.in-flight-staleness
  \* Timing of the environment. These are not settings: they are what the
  \* comments in application.yaml and EventPublicationResubmissionConfig
  \* assume about the running system.
  ListenerMax,       \* longest listener run
  QueueMax,          \* longest wait for a free thread (concurrency-limit)
  RunMax,            \* longest resubmission run, its stale check included
  MaxCrashes,
  Horizon

Instances == {1, 2}
None == 0

\* EventPublicationResubmissionConfig.Properties.lockAtLeastFor
LockAtLeastFor == Interval \div 2
\* EventPublicationResubmitter.dueAt: budget = lockAtMostFor / 2
Budget == LockAtMostFor \div 2

Min(a, b) == IF a < b THEN a ELSE b
Max(a, b) == IF a > b THEN a ELSE b
RECURSIVE Pow(_, _)
Pow(b, e) == IF e = 0 THEN 1 ELSE b * Pow(b, e - 1)

\* ResubmissionPolicies.maxResubmissions: Spring Modulith hands 1 to both the
\* first delivery and the first resubmission, so 1 abandons on the first
\* delivery and the job never resubmits; N >= 2 allows N resubmissions.
MaxDue == IF PolicyAbandon = 1 THEN 0 ELSE PolicyAbandon

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
       /\ WaitAge >= 1 /\ Interval >= 1
       /\ MaxResubmissions >= 2                \* AbandonProperties @Min(2)
       \* Policy @Min(1), and ResubmissionPolicies rejects a policy value at or
       \* above the global one (a policy without the value takes the global one).
       /\ PolicyAbandon \in 1..MaxResubmissions

\* Delivery slots: slot 1 is the first delivery; slot a is the delivery
\* started when completion_attempts became a. Extra slots show an overrun.
\* A slot's seen is getCompletionAttempts() of the publication Spring
\* Modulith hands to the AbandonPolicy when that delivery fails: 1 for the
\* first delivery (the in-memory publication), and the value the run read
\* before markResubmitted for a resubmission (ResubmissionOptionsLearningTest).
Slots == 1..(MaxResubmissions + 2)

VARIABLES
  now,
  row,    \* event_publication: status, completion_attempts,
          \* last_resubmission_date
  lock,   \* modulith.shedlock: lock_until, locked_at, locked_by
  inst,   \* each instance: alive, and the state of its resubmission run
  exec,   \* each listener delivery: state, instance, time queued or started
  crashes,
  staleHitInFlight  \* a stale check marked the row FAILED while a delivery
                    \* was queued or running

vars == <<now, row, lock, inst, exec, crashes, staleHitInFlight>>

\* ARCHIVED and ABANDONED: the row moved to event_publication_archive
\* (completion-mode archive) as COMPLETED or ABANDONED.
Statuses == {"PUBLISHED", "PROCESSING", "FAILED", "RESUBMITTED", "ARCHIVED",
             "ABANDONED"}

TypeOK ==
  /\ now \in 0..Horizon
  /\ row.status \in Statuses
  /\ row.attempts \in 1..(MaxResubmissions + 3)
  /\ \A i \in Instances:
       inst[i].phase \in {"idle", "locked", "checked", "read"}
  /\ \A k \in Slots: exec[k].st \in {"none", "queued", "running", "done"}
  /\ \A k \in Slots: exec[k].seen \in 0..(MaxResubmissions + 3)

\* The resubmitter's first run is after initialDelay = interval.
IdleRun(t) == [up |-> TRUE, next |-> t, phase |-> "idle", runStart |-> 0,
               snap |-> FALSE, snapAttempts |-> 0, snapLast |-> 0]

Init ==
  /\ now = 0
  \* JdbcEventPublicationRepositoryV2.create: status PUBLISHED,
  \* completion_attempts 1, last_resubmission_date = publication_date = 0.
  /\ row = [status |-> "PUBLISHED", attempts |-> 1, lastAttempt |-> 0]
  /\ lock = [until |-> 0, at |-> 0, by |-> None]
  /\ inst = [i \in Instances |-> IdleRun(Interval)]
  \* The first delivery is queued on the publishing instance after commit.
  /\ exec = [k \in Slots |->
               IF k = 1 THEN [st |-> "queued", on |-> 1, t |-> 0, seen |-> 1]
                        ELSE [st |-> "none", on |-> None, t |-> 0, seen |-> 0]]
  /\ crashes = 0
  /\ staleHitInFlight = FALSE

RowExists == row.status \notin {"ARCHIVED", "ABANDONED"}
InFlight(k) == exec[k].st \in {"queued", "running"}
SetStatus(s) == [row EXCEPT !.status = s]

-----------------------------------------------------------------------------
(* EventPublicationResubmitter.resubmitOnce: one run of one instance.      *)
(* Phases of a run that took the lock: locked (stale check to come),       *)
(* checked (stale check done), read.                                       *)
(* SimpleAsyncTaskScheduler (spring.threads.virtual.enabled) runs the      *)
(* fixed-delay job on its single scheduler thread, so an instance has at   *)
(* most one run.                                                           *)

ThreadFree(i) == inst[i].phase = "idle"

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
  /\ UNCHANGED <<now, row, exec, crashes, staleHitInFlight>>

-----------------------------------------------------------------------------
(* The stale check inside the lock, before the resubmission:               *)
(* FailedPublicationSweep calls StalePublications.markFailed with          *)
(* Properties.waitAge and Properties.inFlightStaleness. It is one UPDATE   *)
(* SET status = 'FAILED' WHERE completion_date IS NULL AND ((status =      *)
(* 'PUBLISHED' AND coalesce(last_resubmission_date, publication_date) <    *)
(* now - wait-age) OR (status IN ('PROCESSING', 'RESUBMITTED') AND         *)
(* coalesce(...) < now - in-flight-staleness)). The statement checks the   *)
(* status and the date as it writes, so the row cannot change between a    *)
(* find and a mark.                                                        *)

Threshold(st) == IF st = "PUBLISHED" THEN WaitAge ELSE InFlightStaleness

StaleNow ==
  /\ row.status \in {"PUBLISHED", "PROCESSING", "RESUBMITTED"}
  /\ now > row.lastAttempt + Threshold(row.status)

\* The one UPDATE. One row, so it marks the row or matches nothing.
StaleMark(i) ==
  /\ inst[i].up /\ inst[i].phase = "locked"
  /\ inst' = [inst EXCEPT ![i].phase = "checked"]
  /\ IF StaleNow
       THEN /\ row' = SetStatus("FAILED")
            /\ staleHitInFlight' = (staleHitInFlight \/ \E k \in Slots: InFlight(k))
       ELSE UNCHANGED <<row, staleHitInFlight>>
  /\ UNCHANGED <<now, lock, exec, crashes>>

\* FailedEventPublications.resubmit -> DefaultEventPublicationRegistry
\* .processFailedPublications -> findFailedPublications:
\* SELECT ... WHERE status = 'FAILED'. The run keeps this snapshot while
\* it handles the other publications of the list (up to max-per-run, each
\* dispatch can wait for concurrency-limit).
RunRead(i) ==
  /\ inst[i].up /\ inst[i].phase = "checked"
  /\ inst' = [inst EXCEPT ![i].phase = "read",
                          ![i].snap = (row.status = "FAILED"),
                          ![i].snapAttempts = row.attempts,
                          ![i].snapLast = row.lastAttempt]
  /\ UNCHANGED <<now, row, lock, exec, crashes, staleHitInFlight>>

\* For the snapshot: the withFilter predicate (the time budget from the
\* captured now, then ResubmissionPolicies.isDue: attempts <=
\* abandon-after-attempts and lastAttempt + waitBefore(attempts) before the
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
                /\ inst[i].snapAttempts <= MaxDue
                /\ inst[i].snapLast + WaitBefore(inst[i].snapAttempts)
                     < inst[i].runStart
          a == row.attempts + 1
     IN IF due /\ row.status \in {"PUBLISHED", "PROCESSING", "FAILED"}
          THEN /\ row' = [row EXCEPT !.status = "RESUBMITTED", !.attempts = a,
                                     !.lastAttempt = now]
               /\ exec' = IF a \in Slots
                            THEN [exec EXCEPT ![a] = [st |-> "queued", on |-> i, t |-> now,
                                                      seen |-> inst[i].snapAttempts]]
                            ELSE exec
          ELSE UNCHANGED <<row, exec>>
  /\ lock' = IF lock.by = i
               THEN [lock EXCEPT !.until = Max(lock.at + LockAtLeastFor, now)]
               ELSE lock
  /\ inst' = [inst EXCEPT ![i] = IdleRun(now + Interval)]
  /\ UNCHANGED <<now, crashes, staleHitInFlight>>

-----------------------------------------------------------------------------
(* Listener deliveries: CompletionRegisteringMethodInterceptor around      *)
(* the listener, on the executor (spring.task.execution, concurrency-limit).*)

\* markProcessing: UPDATE SET status = 'PROCESSING' WHERE id = ? AND
\* status != 'PROCESSING'.
ExecStart(k) ==
  /\ exec[k].st = "queued" /\ inst[exec[k].on].up
  /\ exec' = [exec EXCEPT ![k].st = "running", ![k].t = now]
  /\ row' = IF RowExists THEN SetStatus("PROCESSING") ELSE row
  /\ UNCHANGED <<now, lock, inst, crashes, staleHitInFlight>>

\* markCompleted, completion-mode archive: copy to event_publication_archive
\* and delete the row.
ExecSucceed(k) ==
  /\ exec[k].st = "running"
  /\ exec' = [exec EXCEPT ![k] = [st |-> "done", on |-> None, t |-> 0, seen |-> 0]]
  /\ row' = SetStatus("ARCHIVED")
  /\ UNCHANGED <<now, lock, inst, crashes, staleHitInFlight>>

\* DefaultEventPublicationRegistry.markFailed -> markFailedOrAbandoned:
\* AbandonPolicies falls back to ResubmissionProperties.shouldAbandon,
\* getCompletionAttempts() >= abandon-after-attempts on the publication it
\* was handed (seen), not the row. Then markAbandoned copies the row to the
\* archive as ABANDONED and deletes it, without checking the status.
\* Otherwise markFailed: UPDATE SET status = 'FAILED' WHERE id = ? AND
\* status != 'FAILED'.
ExecFail(k) ==
  /\ exec[k].st = "running"
  /\ exec' = [exec EXCEPT ![k] = [st |-> "done", on |-> None, t |-> 0, seen |-> 0]]
  /\ row' = IF ~RowExists THEN row
            ELSE IF exec[k].seen >= PolicyAbandon THEN SetStatus("ABANDONED")
            ELSE IF row.status # "FAILED" THEN SetStatus("FAILED")
            ELSE row
  /\ UNCHANGED <<now, lock, inst, crashes, staleHitInFlight>>

-----------------------------------------------------------------------------
(* Crash and restart. A crash keeps the lock row until lock_until, and     *)
(* kills the run (a stale check included) and the deliveries on that       *)
(* instance without any mark.                                              *)

Crash(i) ==
  /\ inst[i].up /\ crashes < MaxCrashes
  /\ inst' = [inst EXCEPT ![i] = [IdleRun(0) EXCEPT !.up = FALSE]]
  /\ exec' = [k \in Slots |-> IF InFlight(k) /\ exec[k].on = i
                                THEN [st |-> "done", on |-> None, t |-> 0, seen |-> 0]
                                ELSE exec[k]]
  /\ crashes' = crashes + 1
  /\ UNCHANGED <<now, row, lock, staleHitInFlight>>

Restart(i) ==
  /\ ~inst[i].up
  /\ inst' = [inst EXCEPT ![i] = IdleRun(now + Interval)]
  /\ UNCHANGED <<now, row, lock, exec, crashes, staleHitInFlight>>

-----------------------------------------------------------------------------
(* Time. A step that is due now must happen before time moves on.          *)

MustActNow ==
  \/ \E i \in Instances:
       /\ inst[i].up
       /\ \/ ThreadFree(i) /\ inst[i].next <= now
          \/ inst[i].phase # "idle" /\ inst[i].runStart + RunMax = now
  \/ \E k \in Slots:
       \/ exec[k].st = "queued" /\ exec[k].t + QueueMax = now
       \/ exec[k].st = "running" /\ exec[k].t + ListenerMax = now

Tick ==
  /\ now < Horizon /\ ~MustActNow
  /\ now' = now + 1
  /\ UNCHANGED <<row, lock, inst, exec, crashes, staleHitInFlight>>

AtHorizon == now = Horizon /\ ~MustActNow /\ UNCHANGED vars

Next ==
  \/ \E i \in Instances:
       \/ RunStart(i) \/ RunRead(i) \/ RunFinish(i) \/ Crash(i) \/ Restart(i)
       \/ StaleMark(i)
  \/ \E k \in Slots: ExecStart(k) \/ ExecSucceed(k) \/ ExecFail(k)
  \/ Tick \/ AtHorizon

Spec == Init /\ [][Next]_vars

-----------------------------------------------------------------------------
(* Properties.                                                             *)

\* The listener never runs twice at the same time for one publication.
\* This is what in-flight-staleness and its startup check exist for.
OneDeliveryAtATime ==
  \A j, k \in Slots: j # k => ~(InFlight(j) /\ InFlight(k))

\* The stale check never marks a delivery that is still queued or running
\* as FAILED. This is what the lock around the check and the startup check
\* on in-flight-staleness (isInFlightStalenessLongerThanTwiceLockAtMostFor)
\* promise.
InFlightNeverMarkedStale == ~staleHitInFlight

\* The job resubmits a publication at most abandon-after-attempts times.
ResubmissionsCapped == row.attempts <= MaxDue + 1

\* A listener whose policy abandons on the first delivery is never
\* resubmitted by the job, even after a crash left its row in flight.
NoResubmissionWhenAbandonOnFirst == PolicyAbandon = 1 => row.attempts = 1

\* At most one instance is inside the locked part of a run.
OneLockedRun ==
  \A i, j \in Instances:
    i # j => ~(inst[i].phase # "idle" /\ inst[j].phase # "idle")

=============================================================================
