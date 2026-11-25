---
name: formal-verify-order-payment
description: 注文の確定から決済記録までの流れを TLA+ のモデルで全順序検査する。注文の確定と取消、決済の請求と記録、イベント出版の再投入、決済代行の Client、t_payment の制約を変えるときと、二重請求や記録漏れを疑うときに使う。
---

# 注文と決済の形式検証

一つの注文について、確定と取消の画面操作、イベント出版の配信と再投入、配信の重なり、プロセスの停止、決済代行の応答の消失を、すべての順序で検査する。
道具は TLA+ の TLC である。
問いが「出来事がどの順で起きても約束が守られるか」であり、Java のコードを全入力で検証する道具ではこの並行の問いに答えられないためである。
検査の実行、結果の読み方、切り分け、モデルの直し方、同期コミットの進め方は `docs/agents/formal-verification.md` に従う。

## 実行する

```bash
tooling/formal-verify/check.sh .agents/skills/formal-verify-order-payment
tooling/formal-verify/mutants.py .agents/skills/formal-verify-order-payment
```

## 約束（不変条件）

| 名前                      | 約束                                                                                                                       | 破れたときの害                                             |
| ------------------------- | -------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------- |
| `AtMostOnePaymentRecord`  | 一つの注文の決済記録は 1 件まで。                                                                                          | 決済の一覧と問い合わせの答えが矛盾する。                   |
| `RecordMatchesGateway`    | 決済記録が `PAID` か `DECLINED` なら決済代行の判定と同じ。`FAILED` なら決済代行は代金を取っておらず、判定の途中でもない。 | 代金を取ったのに失敗と記録し、客に二度目の支払いを求める。 |
| `ChargedOnlyIfConfirmed`  | 決済代行に請求が届くのは確定した注文だけ。                                                                                 | 取り消した注文の代金を取る。                               |
| `CompletedOnlyWithRecord` | イベント出版が完了（archive）になるのは決済記録があるときだけ。                                                            | 再投入されず、請求の結果がどこにも残らない。               |
| `FailedOnlyOnGatewayError` | 配信が出版を `FAILED` か `ABANDONED` にするのは、決済代行の呼び出しが例外を投げたときだけ。決済代行の判定を受け取った配信は、INSERT が 1 回目の決済記録とぶつかる同時の 2 回目も、完了する。 | 記録済みの決済が再投入の回数を使い、上限の配信なら記録があるのに `ABANDONED` のデッドレターが残る。 |
| `ChargeNeverSilentlyLost` | すべてが止まったとき、決済代行が代金を取った注文には `PAID` の記録があるか、`FAILED` か `ABANDONED` の出版（デッドレター）が残る。 | 代金を取ったことを誰も知らない。                           |

## 設定と期待する結果

| 設定                    | `GatewayRejectsConcurrentKey`                                                                                   | 期待する結果                                  |
| ----------------------- | --------------------------------------------------------------------------------------------------------------- | --------------------------------------------- |
| `contract.cfg`          | `FALSE`：決済代行は `PaymentGateway.charge` の Javadoc の契約どおり、冪等性キーの最初の判定を後の要求にも返す | pass（全不変条件）                            |
| `concurrent-reject.cfg` | `TRUE`：判定中のキーへの要求に 409 などの 4xx を返すことがある（契約の外。ADR-072 の未決事項 3）               | `RecordMatchesGateway` が破れる（既知の発見） |

`concurrent-reject.cfg` では `ChargeNeverSilentlyLost` も破れる。
TLC は最初に見つけた違反で止まるため、この設定はほかの不変条件が成り立つかを示さない。
既知の発見が直ったら、`concurrent-reject.cfg` の 1 行目を `\* expect: pass` に変え、この節と「既知の発見」を更新する。

## 切り分けの手がかり

- 時間はモデルにないため、タイムアウト、`wait-age`、`in-flight-staleness` の大小で実際には起きない順序も経路に出る（安全側に広い）。
  その場合は経路を書き換えて、時間の上でも起こる順序があるかを探す。
- 停止（`DCrash`）は、どの配信のどのステップの後にも起きる。
  印を付けずに終わる配信は、すべて停止と同じ状態になる。

## 変更がこの検証に関わるか

次のコマンドが差分を出せば関わる。

```bash
skill=.agents/skills/formal-verify-order-payment/SKILL.md
git diff --stat "$(sed -n 's/^同期コミット：`\([0-9a-f]*\)`.*/\1/p' "$skill")" -- \
  backend/src/main/java/com/example/demo/ordering/application/ConfirmOrderCommandHandler.java \
  backend/src/main/java/com/example/demo/ordering/application/CancelOrderCommandHandler.java \
  backend/src/main/java/com/example/demo/ordering/domain/model/Order.java \
  backend/src/main/java/com/example/demo/ordering/domain/model/OrderStatus.java \
  backend/src/main/java/com/example/demo/ordering/application/OrderQueryService.java \
  backend/src/main/java/com/example/demo/payment/application/OrderConfirmedListener.java \
  backend/src/main/java/com/example/demo/payment/application/ChargeOrderCommandHandler.java \
  backend/src/main/java/com/example/demo/payment/domain/model/Payment.java \
  backend/src/main/java/com/example/demo/payment/domain/model/ChargeOutcome.java \
  backend/src/main/java/com/example/demo/payment/domain/model/PaymentGateway.java \
  backend/src/main/java/com/example/demo/payment/domain/model/PaymentRepository.java \
  backend/src/main/java/com/example/demo/payment/infrastructure/client/PaymentGatewayClient.java \
  backend/src/main/java/com/example/demo/payment/infrastructure/persistence/JooqPaymentRepository.java \
  backend/src/main/java/com/example/demo/shared/eventresubmission/EventPublicationResubmitter.java \
  backend/src/main/java/com/example/demo/shared/eventresubmission/FailedPublicationSweep.java \
  backend/src/main/java/com/example/demo/shared/eventresubmission/EventPublicationResubmitOnce.java \
  backend/src/main/java/com/example/demo/shared/persistence/TableWriter.java \
  backend/src/main/java/com/example/demo/shared/tenancy/TenantListenerAspect.java \
  backend/src/main/java/com/example/demo/shared/persistence/ModuleRoleTransactionManager.java \
  backend/src/main/java/com/example/demo/shared/persistence/ModuleRoles.java \
  backend/src/main/resources/db/changelog/changesets/006-create-payment-tables.yaml \
  backend/src/main/resources/db/changelog/changesets/020-rebuild-payment-tables-for-tenants.yaml \
  backend/src/main/resources/application.yaml \
  backend/build.gradle
```

`TenantListenerAspect` は、リスナーの呼び出しにイベントのテナントを束縛することと、束縛できないときの拒否が関わる。
`ModuleRoleTransactionManager` と `ModuleRoles` は、トランザクションの開始前の拒否と、その対象（どのトランザクションがテナントを要するか）が関わる。
どれも、範囲と前提の「テナントは一つ」の項目に結びつく。
`application.yaml` は `spring.modulith.events`（`completion-mode`、`staleness`）、`event-publication.resubmission`、`resilience4j.retry.instances.payment-gateway` の節だけ、`build.gradle` は `springModulithVersion` だけが関わる。
モデルは 1 回の配信で決済代行に 1 回だけ請求する。
`resilience4j.retry.instances.payment-gateway` が `default`（`max-attempts: 1`）を継承しているためで、再試行を足したら、1 回の配信が同じ冪等性キーで重ねて請求する経路をモデルに足す。

changeset は `includeAll` で読み込まれるため、新しい changeset は上の差分に出ない。
同期コミット以降に足した changeset が `t_order` か `t_payment` を変えていないかを、次のコマンドで確かめる。
出たら、その changeset の索引、外部キー、ポリシーが `AtMostOnePaymentRecord` などの前提を変えないかを読む。

```bash
git diff --name-only --diff-filter=A "$(sed -n 's/^同期コミット：`\([0-9a-f]*\)`.*/\1/p' .agents/skills/formal-verify-order-payment/SKILL.md)" -- \
  backend/src/main/resources/db/changelog/changesets | xargs -r grep -l "t_order\|t_payment"
```

## 対応表

モデルは `model/OrderPayment.tla` にある。
各アクションはコードの一つの不可分な操作（SQL の一文か、コミットまで他から見えないトランザクション）である。

| アクション        | コード                                                                                                                                                                                             |
| ----------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `CmdRead`         | `ConfirmOrderCommandHandler.handle`、`CancelOrderCommandHandler.handle` の `findById` と `Order.ensureLockNo`                                                                                       |
| `CmdWrite`        | `Order.confirm` / `Order.cancel`（`ensureDraft`）、`JooqOrderRepository.update` → `TableWriter.update`、確定なら `OrderConfirmed` の発行と出版の行の INSERT（同じトランザクション） |
| `DStart`          | Spring Modulith 2.2.0 の `CompletionRegisteringMethodInterceptor` → `markProcessing`（`status != 'PROCESSING'` の UPDATE）                                                                          |
| `DReadPayment`    | `ChargeOrderCommandHandler.handle` の `paymentRepository.findByOrderId`（ロックのない SELECT）。後の配信で決済代行を呼ばないための先読みで、重複の判定ではない（判定は `DInsert`）                  |
| `DReadOrder`      | `ChargeOrderCommandHandler.handle` の `orderQueries.findDetails` と `details.confirmed()`                                                                                                          |
| `DSend`           | `PaymentGatewayClient.charge` の `POST /v1/charges`（`Idempotency-Key` は注文 ID）                                                                                                                 |
| `GatewayDecide`   | 決済代行が冪等性キーの判定を決める（モデルの外の決済代行。ADR-072 の契約）                                                                                                                         |
| `DReply`          | `PaymentGatewayClient.charge` の戻り値（`toOutcome`、`contractError`）か例外（タイムアウト、5xx、401、403、429）。例外なら、決済代行の判定によらず配信の見た結果を `TRANSIENT` にする             |
| `DInsert`         | `Payment.record`、`JooqPaymentRepository.add` の `INSERT ... ON CONFLICT (tenant_public_id, order_public_id) DO NOTHING`（`uk_2_t_payment`）とリスナーのトランザクションのコミット。開いたままのトランザクションの行とぶつかる INSERT はその終わりを待つため、INSERT とコミットを一つのステップにする。0 件なら `ChargeOrderCommandHandler.handle` が 1 回目の決済記録を読んで `AlreadyRecorded` を返し、リスナーは正常に終わる |
| `DMarkCompleted`  | `DefaultEventPublicationRegistry.markCompleted`（archive への写しと削除）                                                                                                                          |
| `DMarkFailed`     | `DefaultEventPublicationRegistry.markFailed` → `markFailedOrAbandoned`。`PublicationsInProgress` のこの配信の登録は、ほかの配信が外さないため、必ず判断する。`AbandonPolicies` の既定が、渡された出版の `getCompletionAttempts()`（配信 `d` では `d - 1`、最初の配信では 1）を `abandon-after-attempts` と比べ、それ以上なら（`d = MaxDeliveries`）archive へ `ABANDONED` で移し、未満なら `status != 'FAILED'` の UPDATE を実行する |
| `DCrash`          | 配信の途中のプロセスの停止。リスナーのトランザクションはロールバックする                                                                                                                           |
| `Stale`           | 再投入のロックの中で `FailedPublicationSweep` が呼ぶ `DefaultEventPublicationRegistry.markStalePublicationsFailed`（閾値は `Properties.staleness`、`last_resubmission_date` から測る）。モデルは時間もロックも持たないため、いつでも起きる |
| `RPick`、`RClaim` | `FailedPublicationSweep.tryOnce`（`EventPublicationResubmitter`）か `FailedPublicationSweep.waitUpTo`（`EventPublicationResubmitOnce.run`）→ `FailedEventPublications.resubmit`、`JdbcEventPublicationRepositoryV2.markResubmitted`（`status != 'RESUBMITTED'` の UPDATE） |

## 変異

変異は `model/mutants.toml` にあり、各項目の `change` が写すコードの退行である。

## 範囲と前提

検査の範囲は次のとおりで、これを超える保証はない。

- 注文は一つ、画面の操作は確定と取消の各一回、配信は最初の一回と再投入を合わせて 3 回まで（`MaxDeliveries = 3`）。
- 時間を持たない。
  待ち時間、`in-flight-staleness`、タイムアウトの大小で起きない順序も含めて検査する（安全側に広い）。
- 決済代行は冪等性キーごとに判定を一度だけ決めると仮定する（`contract.cfg`）。
  判定はモデルの外の約束である。
- 明細の変更（`ChangeOrderLinesCommandHandler`）、金額の正しさ、HTTP の層、認証、複数の注文の間の干渉は扱わない。
- 重複の配信の判定は、決済記録の INSERT の `ON CONFLICT DO NOTHING` が行う。
  先読みの `findByOrderId` は決済代行の呼び出しを省くだけで、モデルの約束はこれに頼らない。
  先読みを消す退行の変異は置かない。
  決済代行は冪等性キーで同じ判定を返すため（`contract.cfg`）約束は破れず、決済代行を呼ぶ回数はモデルの外である。
- 1 回目の決済記録が消える経路はコードにないため、INSERT が 0 件のあとの読み出しは必ず記録を見つけるとする。
- 手での再投入（`EventPublicationResubmitOnce`）は、定期の再投入と同じ `markResubmitted` を通るため、`RPick` と `RClaim` に含まれる。
  配信の数の上限は `MaxDeliveries`（`abandon-after-attempts` + 1）で、上限の配信が失敗すると出版は `ABANDONED` になる。
- `ResubmissionTracker` の上限到達の記録とメトリクスは扱わない。
- テナントは一つとし、行のセキュリティ（RLS）のポリシーは扱わない。
  リスナーは `TenantListenerAspect` がイベントの `tenantId` を束縛してから動く。
  `ConfirmOrderCommandHandler` が発行する `OrderConfirmed` は必ず `tenantId` を持ち、配信は同じテナントで `t_order` を読み、`t_payment` に書く。
  `ModuleRoleTransactionManager` は、束縛のない payment のトランザクションを SQL の前に拒否するが、この経路では起きない。
  Spring Modulith の出版の更新と再投入はモジュールの外の名前なので、テナントなしでも始まる。
- `tenantId` のない古い出版（`TenantListenerAspect` の Javadoc の「`tenantId` の項目がない古い出版」）の経路は、モデルの動きにしない。
  `DStart`（`markProcessing`）の後に `TenantListenerAspect` が `IllegalStateException` を投げ、書き込みも決済代行への請求もなしに `markFailed` へ進む。
  モデルに `DReadPayment` から `markFailed` への動きはないが、同じ状態（出版が `FAILED`、決済記録も請求もない）は、`DStart` の後の `DCrash` と `Stale` に入っている。

## 既知の発見

`concurrent-reject.cfg` で `RecordMatchesGateway` と `ChargeNeverSilentlyLost` が破れる。
決済代行が判定中のキーへの要求に 409 を返すと、代金を取るのに決済記録は `FAILED` になり、出版は完了して再投入もされない。
`RecordMatchesGateway` の TLC の経路は次のとおり。

1. 注文を確定し、最初の配信が始まる（`markProcessing`）。
2. 再投入のジョブが、古い出版として出版を `FAILED` にする（最初の配信が `concurrency-limit` の空きを待つ間に `wait-age` を過ぎた場合など、`application.yaml` の注記にある場合）。
3. 最初の配信が `POST /v1/charges` を送り、決済代行が判定を始める。
4. `EventPublicationResubmitter` が出版を `markResubmitted` で `RESUBMITTED` にし、二度目の配信が同じ `Idempotency-Key` で請求し、決済代行が 409 を返す。
5. `PaymentGatewayClient.contractError` が 409 を契約の不備として `ChargeOutcome.failed()` にし（`PaymentGatewayClientTest.contractErrorReturnsFailed` が 409 でこれを確かめている）、`FAILED` の決済記録をコミットする。
   決済代行は最初の請求をまだ判定している。

`ChargeNeverSilentlyLost` の経路では、手順 2 の代わりに、手順 3 の最初の配信が読み取りのタイムアウトで失敗して `markFailed` で出版が `FAILED` になる。
手順 5 のあと出版が完了し、それから決済代行が最初の請求で代金を取る。

害は、代金を取ったのに失敗と記録し、デッドレターも残らないことである。
今の契約（`PaymentGateway.charge` の Javadoc）の下では起きない。
契約の外でも、二度目の請求は出版から少なくとも方針の待ち時間（`wait-age`）の後に届くため、決済代行がその間ずっと最初の請求を判定し続けている必要があり、まれである。
実際の決済代行の契約を決めるとき（ADR-072 の未決事項 3）に、判定中のキーへの応答を一時障害に分類するかを決める必要がある。

## 同期の記録

同期コミット：`62d0618f603f33861f01c57de9bbf1b1f1b29281`（main。#551 の Pull Request の土台）

#551 の Pull Request のコード（決済記録を `INSERT ... ON CONFLICT DO NOTHING` で入れ、0 件なら 1 回目の決済記録を返すようにした後）で、`ChargeOrderCommandHandler`、`PaymentRepository`、`JooqPaymentRepository`、`TableWriter.insertIfAbsent` を読み、対応表の `DReadPayment`、`DReply`、`DInsert` がモデルと一致することを確かめた。
同時の 2 回目の INSERT は 1 回目のトランザクションの終わりを待ち、例外にならずに完了する。

#562 の Pull Request のコード（回数の上限を `abandon-after-attempts` に任せた後）で、「変更がこの検証に関わるか」のファイルと対応表がモデルと一致することを確かめた。
読んだライブラリは、Spring Modulith 2.2.0 の `DefaultEventPublicationRegistry`（`PublicationsInProgress`、`markFailedOrAbandoned`）、`AbandonPolicies`、`ResubmissionProperties`、`JdbcEventPublicationRepositoryV2`（`markAbandoned`）、`CompletionRegisteringAdvisor`（`CompletionRegisteringMethodInterceptor`、`spring.modulith.events.completion: in-interceptor`）、`PersistentApplicationEventMulticaster`、`DefaultFailedEventPublications` である。

#556 の Pull Request のコード（古い出版を `FAILED` に戻す処理を再投入のロックの中に移した後）で、`FailedPublicationSweep` と `EventPublicationResubmitter` の変更を読んだ。
`Stale` はモデルに時間もロックもなく、既存の `FAILED` でない行ならいつでも `FAILED` にするため、呼び出しの場所が変わってもモデルは変わらない。
