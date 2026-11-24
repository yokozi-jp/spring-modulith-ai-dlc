--------------------------- MODULE OrderPayment ---------------------------
(***************************************************************************)
(* One order, from confirm or cancel through the OrderConfirmed event      *)
(* publication to the payment record, with overlapping listener            *)
(* deliveries, crashes, lost gateway replies, and the staleness monitor.   *)
(*                                                                         *)
(* Paths are relative to backend/src/main/java/com/example/demo/.          *)
(* Spring Modulith 2.2.0 behavior is cited by class name.                  *)
(* Each action is one indivisible step in the code: one SQL statement, or  *)
(* one transaction whose effects other transactions see only at commit.    *)
(***************************************************************************)
EXTENDS Naturals, Sequences

CONSTANTS
  \* Listener invocations of the one publication: the original async
  \* delivery plus resubmissions. Bounds the model.
  MaxDeliveries,
  \* FALSE: the gateway follows PaymentGateway.charge's Javadoc: the first
  \* decided result for an Idempotency-Key is returned to every later request.
  \* TRUE: the gateway may also answer a request whose key is still in flight
  \* with a 4xx (such as 409), without deciding. This is outside the written
  \* contract (ADR-072 leaves the classification open).
  GatewayRejectsConcurrentKey

Cmds == {"confirm", "cancel"}
D == 1..MaxDeliveries
NoPub == "NONE"
\* The row left event_publication: archive mode copies it to
\* event_publication_archive and deletes it.
Archived == "ARCHIVED"
\* Spring Modulith (abandon-after-attempts) gave up on the publication; archive
\* mode copies it to the archive as ABANDONED and deletes the row.
Abandoned == "ABANDONED"
RowStatus == {"PUBLISHED", "PROCESSING", "FAILED", "RESUBMITTED"}

VARIABLES
  orderStatus,   \* ordering.t_order.order_status_typ
  orderLock,     \* ordering.t_order.lock_no
  cmdPc,         \* each screen command: "idle", "read", "done"
  cmdLock,       \* lock_no the command read in findById
  pub,           \* modulith.event_publication.status of the one publication
  payments,      \* committed payment.t_payment rows for the order (statuses)
  gateway,       \* gateway idempotency record for Idempotency-Key = orderId
  dpc,           \* program counter of each listener delivery
  dres,          \* what the gateway answered each delivery
  launched,      \* deliveries started so far
  rpc            \* EventPublicationResubmitter: "idle" or "picked"

vars == <<orderStatus, orderLock, cmdPc, cmdLock, pub, payments, gateway,
          dpc, dres, launched, rpc>>

TypeOK ==
  /\ orderStatus \in {"DRAFT", "CONFIRMED", "CANCELLED"}
  /\ orderLock \in Nat
  /\ cmdPc \in [Cmds -> {"idle", "read", "done"}]
  /\ cmdLock \in [Cmds -> Nat]
  /\ pub \in RowStatus \cup {NoPub, Archived, Abandoned}
  /\ payments \in Seq({"PAID", "DECLINED", "FAILED"})
  /\ gateway \in {"NONE", "LOCKED", "PAID", "DECLINED", "REJECTED"}
  /\ dpc \in [D -> {"idle", "start", "readPayment", "readOrder", "charge",
                    "atGateway", "awaitReply", "insert", "markCompleted", "markFailed",
                    "done"}]
  /\ dres \in [D -> {"NONE", "PENDING", "PAID", "DECLINED", "REJECTED", "CONCURRENT",
                     "TRANSIENT"}]
  /\ launched \in 0..MaxDeliveries
  /\ rpc \in {"idle", "picked"}

Init ==
  /\ orderStatus = "DRAFT"
  /\ orderLock = 1
  /\ cmdPc = [c \in Cmds |-> "idle"]
  /\ cmdLock = [c \in Cmds |-> 0]
  /\ pub = NoPub
  /\ payments = <<>>
  /\ gateway = "NONE"
  /\ dpc = [d \in D |-> "idle"]
  /\ dres = [d \in D |-> "NONE"]
  /\ launched = 0
  /\ rpc = "idle"

RowExists == pub \in RowStatus

-----------------------------------------------------------------------------
(* ordering: ConfirmOrderCommandHandler.handle, CancelOrderCommandHandler   *)
(* .handle. The screen sends the lock_no it read as expectedLockNo: the    *)
(* first one (1), or the current one after a reload.                       *)

\* JooqOrderRepository.findById, then Order.ensureLockNo(expectedLockNo).
\* A mismatch throws ConflictException and the command ends.
CmdRead(c) ==
  /\ cmdPc[c] = "idle"
  /\ \E expected \in {1, orderLock}:
       IF orderLock = expected
         THEN /\ cmdPc' = [cmdPc EXCEPT ![c] = "read"]
              /\ cmdLock' = [cmdLock EXCEPT ![c] = orderLock]
         ELSE /\ cmdPc' = [cmdPc EXCEPT ![c] = "done"]
              /\ UNCHANGED cmdLock
  /\ UNCHANGED <<orderStatus, orderLock, pub, payments, gateway, dpc, dres,
                 launched, rpc>>

\* Order.confirm / Order.cancel (ensureDraft on the state read), then
\* JooqOrderRepository.update -> TableWriter.update
\* (UPDATE ... WHERE lock_no = <read lock_no>). Confirm also publishes
\* OrderConfirmed, which Spring Modulith stores in event_publication inside
\* the same transaction. After commit the AFTER_COMMIT listener is queued
\* for async delivery (delivery 1).
CmdWrite(c) ==
  /\ cmdPc[c] = "read"
  /\ cmdPc' = [cmdPc EXCEPT ![c] = "done"]
  /\ IF orderStatus = "DRAFT" /\ orderLock = cmdLock[c]
       THEN /\ orderStatus' = IF c = "confirm" THEN "CONFIRMED" ELSE "CANCELLED"
            /\ orderLock' = orderLock + 1
            /\ IF c = "confirm"
                 THEN /\ pub' = "PUBLISHED"
                      /\ launched' = 1
                      /\ dpc' = [dpc EXCEPT ![1] = "start"]
                 ELSE UNCHANGED <<pub, launched, dpc>>
       ELSE UNCHANGED <<orderStatus, orderLock, pub, launched, dpc>>
  /\ UNCHANGED <<cmdLock, payments, gateway, dres, rpc>>

-----------------------------------------------------------------------------
(* payment: OrderConfirmedListener.on (@ApplicationModuleListener) wrapped  *)
(* by Spring Modulith's CompletionRegisteringMethodInterceptor, which runs  *)
(* outside the listener's REQUIRES_NEW transaction.                        *)

\* CompletionRegisteringMethodInterceptor -> markProcessing:
\* UPDATE ... SET status = 'PROCESSING' WHERE id = ? AND status != 'PROCESSING'.
\* No row (already archived) means no update.
DStart(d) ==
  /\ dpc[d] = "start"
  /\ pub' = IF RowExists THEN "PROCESSING" ELSE pub
  /\ dpc' = [dpc EXCEPT ![d] = "readPayment"]
  /\ UNCHANGED <<orderStatus, orderLock, cmdPc, cmdLock, payments, gateway,
                 dres, launched, rpc>>

\* ChargeOrderCommandHandler.handle: paymentRepository.findByOrderId.
\* A record means return without charging; the listener ends normally.
DReadPayment(d) ==
  /\ dpc[d] = "readPayment"
  /\ dpc' = [dpc EXCEPT ![d] = IF Len(payments) > 0 THEN "markCompleted"
                                                    ELSE "readOrder"]
  /\ UNCHANGED <<orderStatus, orderLock, cmdPc, cmdLock, pub, payments,
                 gateway, dres, launched, rpc>>

\* ChargeOrderCommandHandler.handle: orderQueries.findDetails, then
\* details.confirmed(). Not confirmed means log and return normally.
DReadOrder(d) ==
  /\ dpc[d] = "readOrder"
  /\ dpc' = [dpc EXCEPT ![d] = IF orderStatus = "CONFIRMED" THEN "charge"
                                                            ELSE "markCompleted"]
  /\ UNCHANGED <<orderStatus, orderLock, cmdPc, cmdLock, pub, payments,
                 gateway, dres, launched, rpc>>

\* PaymentGatewayClient.charge: POST /v1/charges with Idempotency-Key.
\* gateway is the gateway's record for the key. "LOCKED" means a request
\* holds the key and the gateway has not decided yet.
\*  - No record: this request takes the key, or fails before the gateway
\*    sees it (connect failure) and the client throws.
\*  - Decided: the gateway returns the first decision (the Javadoc contract).
\*  - Locked: the request waits and can time out (the client throws). Only
\*    when GatewayRejectsConcurrentKey is TRUE, the gateway may instead
\*    answer at once with a 4xx such as 409 ("CONCURRENT").
DSend(d) ==
  /\ dpc[d] = "charge"
  /\ \/ /\ gateway = "NONE"
        /\ gateway' = "LOCKED"
        /\ dres' = [dres EXCEPT ![d] = "PENDING"]
        /\ dpc' = [dpc EXCEPT ![d] = "atGateway"]
     \/ /\ gateway = "NONE"
        /\ dres' = [dres EXCEPT ![d] = "TRANSIENT"]
        /\ dpc' = [dpc EXCEPT ![d] = "awaitReply"]
        /\ UNCHANGED gateway
     \/ /\ gateway \in {"PAID", "DECLINED", "REJECTED"}
        /\ dres' = [dres EXCEPT ![d] = gateway]
        /\ dpc' = [dpc EXCEPT ![d] = "awaitReply"]
        /\ UNCHANGED gateway
     \/ /\ gateway = "LOCKED"
        /\ dres' = [dres EXCEPT ![d] = "TRANSIENT"]
        /\ dpc' = [dpc EXCEPT ![d] = "awaitReply"]
        /\ UNCHANGED gateway
     \/ /\ gateway = "LOCKED"
        /\ GatewayRejectsConcurrentKey
        /\ dres' = [dres EXCEPT ![d] = "CONCURRENT"]
        /\ dpc' = [dpc EXCEPT ![d] = "awaitReply"]
        /\ UNCHANGED gateway
  /\ UNCHANGED <<orderStatus, orderLock, cmdPc, cmdLock, pub, payments,
                 launched, rpc>>

\* The gateway decides the locked key: PAID, DECLINED, REJECTED (a 4xx
\* other than 401, 403, 429), or fails itself (5xx) and releases the key.
\* It decides even when the client that sent the request has died.
GatewayDecide ==
  /\ gateway = "LOCKED"
  /\ \E r \in {"PAID", "DECLINED", "REJECTED", "NONE"}:
       /\ gateway' = r
       /\ dres' = [d \in D |-> IF dpc[d] = "atGateway"
                                THEN (IF r = "NONE" THEN "TRANSIENT" ELSE r)
                                ELSE dres[d]]
  /\ dpc' = [d \in D |-> IF dpc[d] = "atGateway" THEN "awaitReply" ELSE dpc[d]]
  /\ UNCHANGED <<orderStatus, orderLock, cmdPc, cmdLock, pub, payments,
                 launched, rpc>>

\* PaymentGatewayClient.charge returns or throws. A reply can be lost, or
\* the read timeout can pass while the gateway still holds the key; then
\* the client throws. PAID -> ChargeOutcome.paid, DECLINED -> declined,
\* REJECTED and CONCURRENT -> contractError -> ChargeOutcome.failed.
DReply(d) ==
  /\ dpc[d] \in {"atGateway", "awaitReply"}
  /\ \/ /\ dpc[d] = "awaitReply"
        /\ dres[d] \in {"PAID", "DECLINED", "REJECTED", "CONCURRENT"}
        /\ dpc' = [dpc EXCEPT ![d] = "insert"]
     \/ dpc' = [dpc EXCEPT ![d] = "markFailed"]
  /\ UNCHANGED <<orderStatus, orderLock, cmdPc, cmdLock, pub, payments,
                 gateway, dres, launched, rpc>>

RecordOf(r) == IF r \in {"PAID", "DECLINED"} THEN r ELSE "FAILED"

\* Payment.record, JooqPaymentRepository.add -> TableWriter.insert, then the
\* listener transaction commits. The unique index uk_2_t_payment
\* (tenant_public_id, order_public_id) rejects a second row of the order:
\* ConflictException, rollback. The model has one tenant.
\* PostgreSQL makes a concurrent INSERT wait for the other commit, so
\* INSERT and COMMIT act as one step.
DInsert(d) ==
  /\ dpc[d] = "insert"
  /\ IF Len(payments) = 0
       THEN /\ payments' = Append(payments, RecordOf(dres[d]))
            /\ dpc' = [dpc EXCEPT ![d] = "markCompleted"]
       ELSE /\ dpc' = [dpc EXCEPT ![d] = "markFailed"]
            /\ UNCHANGED payments
  /\ UNCHANGED <<orderStatus, orderLock, cmdPc, cmdLock, pub, gateway, dres,
                 launched, rpc>>

\* DefaultEventPublicationRegistry.markCompleted (REQUIRES_NEW): archive
\* mode copies the row to the archive and deletes it.
DMarkCompleted(d) ==
  /\ dpc[d] = "markCompleted"
  /\ pub' = IF RowExists THEN Archived ELSE pub
  /\ dpc' = [dpc EXCEPT ![d] = "done"]
  /\ UNCHANGED <<orderStatus, orderLock, cmdPc, cmdLock, payments, gateway,
                 dres, launched, rpc>>

\* DefaultEventPublicationRegistry.markFailed finds this delivery's entry in
\* PublicationsInProgress and runs markFailedOrAbandoned. The default
\* AbandonPolicy compares getCompletionAttempts() of the publication it was
\* handed with abandon-after-attempts (MaxDeliveries - 1): delivery d hands
\* d - 1, the value RPick read before RClaim, and the first delivery hands 1
\* (ResubmissionOptionsLearningTest). So only the last delivery d =
\* MaxDeliveries is abandoned, and JdbcEventPublicationRepositoryV2
\* .markAbandoned moves the row to the archive. Otherwise JdbcEventPublicationRepositoryV2
\* .markFailed: UPDATE ... SET status = 'FAILED' WHERE id = ? AND
\* completion_date IS NULL AND status != 'FAILED'. The entry is always
\* there: PublicationsInProgress.Key compares events by identity, the first
\* delivery uses the event that store registered, and each resubmission
\* registers the event it has just deserialized, so one delivery never
\* removes another delivery's entry.
DMarkFailed(d) ==
  /\ dpc[d] = "markFailed"
  /\ pub' = IF ~RowExists THEN pub
            ELSE IF d = MaxDeliveries THEN Abandoned
            ELSE "FAILED"
  /\ dpc' = [dpc EXCEPT ![d] = "done"]
  /\ UNCHANGED <<orderStatus, orderLock, cmdPc, cmdLock, payments, gateway,
                 dres, launched, rpc>>

\* The process dies mid-delivery. The open listener transaction rolls back
\* (DInsert is its only write), and no mark runs.
DCrash(d) ==
  /\ dpc[d] \in {"start", "readPayment", "readOrder", "charge", "atGateway",
                 "awaitReply",
                 "insert", "markCompleted", "markFailed"}
  /\ dpc' = [dpc EXCEPT ![d] = "done"]
  /\ UNCHANGED <<orderStatus, orderLock, cmdPc, cmdLock, pub, payments,
                 gateway, dres, launched, rpc>>

-----------------------------------------------------------------------------
(* Recovery: Spring Modulith's staleness monitor and                       *)
(* shared/eventresubmission/EventPublicationResubmitter.          *)

\* DefaultEventPublicationRegistry.markStalePublicationsFailed: findByStatus
\* for PUBLISHED, PROCESSING, RESUBMITTED, keeps the rows whose
\* last_resubmission_date (getLastPublicationDate) is older than
\* spring.modulith.events.staleness.*, then markFailed by id without
\* checking the status again. The model has no time, and the row can change
\* between the find and the update, so any existing row that is not FAILED
\* may become FAILED at any time.
Stale ==
  /\ pub \in {"PUBLISHED", "PROCESSING", "RESUBMITTED"}
  /\ pub' = "FAILED"
  /\ UNCHANGED <<orderStatus, orderLock, cmdPc, cmdLock, payments, gateway,
                 dpc, dres, launched, rpc>>

\* EventPublicationResubmitter.resubmit -> FailedEventPublications.resubmit
\* reads FAILED rows and ResubmissionPolicies.isDue accepts this one.
\* MaxDeliveries stands in for abandon-after-attempts + 1 (the first delivery).
RPick ==
  /\ rpc = "idle"
  /\ pub = "FAILED"
  /\ launched < MaxDeliveries
  /\ rpc' = "picked"
  /\ UNCHANGED <<orderStatus, orderLock, cmdPc, cmdLock, pub, payments,
                 gateway, dpc, dres, launched>>

\* JdbcEventPublicationRepositoryV2.markResubmitted:
\* UPDATE ... SET status = 'RESUBMITTED' WHERE id = ? AND status != 'RESUBMITTED'.
\* One row updated means the listener is invoked again asynchronously.
RClaim ==
  /\ rpc = "picked"
  /\ rpc' = "idle"
  /\ IF pub \in {"PUBLISHED", "PROCESSING", "FAILED"}
       THEN /\ pub' = "RESUBMITTED"
            /\ launched' = launched + 1
            /\ dpc' = [dpc EXCEPT ![launched + 1] = "start"]
       ELSE UNCHANGED <<pub, launched, dpc>>
  /\ UNCHANGED <<orderStatus, orderLock, cmdPc, cmdLock, payments, gateway,
                 dres>>

-----------------------------------------------------------------------------

\* Nothing can happen any more. An ABANDONED publication, and a FAILED row
\* past the resubmission bound after a crash, are the dead letters that
\* operators handle by Runbook.
Settled ==
  /\ \A c \in Cmds: cmdPc[c] = "done"
  /\ \A d \in D: dpc[d] \in {"idle", "done"}
  /\ rpc = "idle"
  /\ gateway # "LOCKED"
  /\ pub \in {NoPub, Archived, Abandoned, "FAILED"}
  /\ pub = "FAILED" => launched = MaxDeliveries

Done == Settled /\ UNCHANGED vars

Next ==
  \/ \E c \in Cmds: CmdRead(c) \/ CmdWrite(c)
  \/ \E d \in D: \/ DStart(d) \/ DReadPayment(d) \/ DReadOrder(d)
                 \/ DSend(d) \/ DReply(d) \/ DInsert(d)
                 \/ DMarkCompleted(d) \/ DMarkFailed(d) \/ DCrash(d)
  \/ GatewayDecide \/ Stale \/ RPick \/ RClaim
  \/ Done

Spec == Init /\ [][Next]_vars

-----------------------------------------------------------------------------
(* Properties. Each one is a promise to the customer or to operators.      *)

\* An order has at most one payment record.
AtMostOnePaymentRecord == Len(payments) <= 1

\* The payment record says what the gateway did with the order's money.
\* PAID or DECLINED matches the gateway's decision; FAILED means the gateway
\* did not and will not take the money (no decision pending).
RecordMatchesGateway ==
  \A i \in 1..Len(payments):
    IF payments[i] \in {"PAID", "DECLINED"}
      THEN gateway = payments[i]
      ELSE gateway \in {"NONE", "REJECTED"}

\* The gateway only sees a charge for a confirmed order.
ChargedOnlyIfConfirmed == gateway # "NONE" => orderStatus = "CONFIRMED"

\* The publication is marked done only when the payment is recorded.
CompletedOnlyWithRecord == pub = Archived => Len(payments) = 1

\* When everything has stopped, a customer whose money the gateway took
\* has a PAID record, or operators see a FAILED or ABANDONED dead letter.
ChargeNeverSilentlyLost ==
  (Settled /\ gateway = "PAID") =>
    (payments = <<"PAID">> \/ pub \in {"FAILED", Abandoned})

=============================================================================
