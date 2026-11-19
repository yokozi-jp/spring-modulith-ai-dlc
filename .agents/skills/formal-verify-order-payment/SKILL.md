---
name: formal-verify-order-payment
description: 注文の確定から決済記録までの流れ（OrderConfirmed のイベント出版、OrderConfirmedListener、ChargeOrderCommandHandler、PaymentGatewayClient、再投入と Staleness Monitor）を TLA+ のモデルで全順序検査する。注文の確定と取消、決済の請求と記録、イベント出版の再投入、決済代行の Client、t_payment の制約を変えるときと、二重請求や記録漏れを疑うときに使う。
---

# 注文と決済の形式検証

一つの注文について、確定と取消の画面操作、イベント出版の配信と再投入、配信の重なり、プロセスの停止、決済代行の応答の消失を、すべての順序で検査する。
道具は TLA+ の TLC である。
問いが「出来事がどの順で起きても約束が守られるか」であり、Java のコードを全入力で検証する道具ではこの並行の問いに答えられないためである。

## 約束（不変条件）

| 名前                      | 約束                                                                                 | 破れたときの害                                     |
| ------------------------- | ------------------------------------------------------------------------------------ | -------------------------------------------------- |
| `AtMostOnePaymentRecord`  | 一つの注文の決済記録は 1 件まで。                                                    | 決済の一覧と問い合わせの答えが矛盾する。           |
| `RecordMatchesGateway`    | 決済記録が `PAID` か `DECLINED` なら決済代行の判定と同じ。`FAILED` なら決済代行は代金を取っておらず、判定の途中でもない。 | 代金を取ったのに失敗と記録し、客に二度目の支払いを求める。 |
| `ChargedOnlyIfConfirmed`  | 決済代行に請求が届くのは確定した注文だけ。                                           | 取り消した注文の代金を取る。                       |
| `CompletedOnlyWithRecord` | イベント出版が完了（archive）になるのは決済記録があるときだけ。                      | 再投入されず、請求の結果がどこにも残らない。       |
| `ChargeNeverSilentlyLost` | すべてが止まったとき、決済代行が代金を取った注文には `PAID` の記録があるか、`FAILED` の出版（デッドレター）が残る。 | 代金を取ったことを誰も知らない。                   |

## 実行する

リポジトリのルートで実行する。

```bash
.agents/skills/formal-verify-order-payment/scripts/check.sh
.agents/skills/formal-verify-order-payment/scripts/mutants.py
```

- `check.sh` は二つの設定を検査し、どちらも期待どおりなら 0 で終わる。数秒で終わる。
  初回だけ TLC（`tla2tools.jar` v1.7.4、SHA-256 で確かめる）を `~/.cache/formal-verify-tla/` に取得し、Java（`temurin-25.0.5+8.0.LTS`）を mise で入れるため、ネットワークが要る。
  Kiro Web では先に `source docs/local-env-setup/kiro-web.sh` を実行する。
- `mutants.py` は、モデルの写しにコードの退行を一つずつ入れ、それぞれが守る不変条件を TLC が破ると報告することを確かめる。
  一つでも見逃すと 1 で終わる。モデルを変えたら必ず実行する。

設定と期待する結果は次のとおり。

| 設定                    | `GatewayRejectsConcurrentKey`                                                     | 期待する結果                   |
| ----------------------- | --------------------------------------------------------------------------------- | ------------------------------ |
| `contract.cfg`          | `FALSE`：決済代行は `PaymentGateway.charge` の Javadoc の契約どおり、冪等性キーの最初の判定を後の要求にも返す | 全不変条件が成り立つ           |
| `concurrent-reject.cfg` | `TRUE`：判定中のキーへの要求に 409 などの 4xx を返すことがある（契約の外。ADR-072 の未決事項 3） | `RecordMatchesGateway` が破れる（既知の発見） |

`concurrent-reject` の結果が変わったら、既知の発見が直ったか、別の破れに変わったかである。
直したなら `concurrent-reject.cfg` の 1 行目を `\* expect: pass` に変え、この節と「既知の発見」を更新する。

## 結果を読む

TLC の結果は必ず `/statechart` スキルで読む。
不変条件の違反は最短の経路として平易な言葉で示され、成り立つときは状態の遷移図を描く。
`/statechart` はリポジトリの外の作業用のフォルダで動かす。
`model/` を写し、状態と遷移の言葉を決める view を書いて渡す（書き方は `/statechart` にある）。

違反が出たら、本物の不具合かモデルの誤りかを次の順に切り分ける。

1. 経路の各ステップを、下の「対応表」でコードの場所に置き換える。
2. 各ステップがコードで一つの不可分な操作か、その順序がコードで起こりうるかを確かめる。
   時間はモデルにないため、タイムアウトや待ち時間の大小で実際には起きない順序もある。
   その場合は経路を書き換えて、時間の上でも起こる順序があるかを探す。
3. 起こりうるなら本物の不具合である。不変条件やモデルを緩めて通さない。
   経路をコードの言葉の手順で利用者に報告し、製品のコードは利用者が頼んだときだけ直す。
4. 起こりえないなら、モデルのどの行がコードと違うかを直し、`check.sh` と `mutants.py` を流し直す。

## 変更がこの検証に関わるか

次のファイルの差分があれば関わる（パスは `backend/src/main/java/com/example/demo/` から）。

```bash
git diff --stat <同期コミット> -- \
  backend/src/main/java/com/example/demo/ordering/application/ConfirmOrderCommandHandler.java \
  backend/src/main/java/com/example/demo/ordering/application/CancelOrderCommandHandler.java \
  backend/src/main/java/com/example/demo/ordering/domain/model/Order.java \
  backend/src/main/java/com/example/demo/ordering/domain/model/OrderStatus.java \
  backend/src/main/java/com/example/demo/ordering/application/OrderQueryService.java \
  backend/src/main/java/com/example/demo/payment/application/OrderConfirmedListener.java \
  backend/src/main/java/com/example/demo/payment/application/ChargeOrderCommandHandler.java \
  backend/src/main/java/com/example/demo/payment/infrastructure/client/PaymentGatewayClient.java \
  backend/src/main/java/com/example/demo/payment/infrastructure/persistence/JooqPaymentRepository.java \
  backend/src/main/java/com/example/demo/payment/domain/model/PaymentGateway.java \
  backend/src/main/java/com/example/demo/shared/infrastructure/persistence/EventPublicationResubmitter.java \
  backend/src/main/java/com/example/demo/shared/infrastructure/persistence/TableWriter.java \
  backend/src/main/resources/db/changelog/changesets/006-create-payment-tables.yaml \
  backend/src/main/resources/application.yaml \
  backend/build.gradle
```

`application.yaml` は `spring.modulith.events`（`completion-mode`、`staleness`）と `event-publication.resubmission` の節だけ、`build.gradle` は `springModulithVersion` だけが関わる。
Spring Modulith の版が上がったら、下の対応表の Spring Modulith の行を新しい版のソースで読み直す。

## 対応表

モデルは `model/OrderPayment.tla` にある。
各アクションはコードの一つの不可分な操作（SQL の一文か、コミットまで他から見えないトランザクション）である。

| アクション         | コード                                                                                                                                      |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------- |
| `CmdRead`          | `ConfirmOrderCommandHandler.handle`、`CancelOrderCommandHandler.handle` の `findById` と `Order.ensureLockNo`                                |
| `CmdWrite`         | `Order.confirm` / `Order.cancel`（`ensureDraft`）、`JooqOrderRepository.update` → `TableWriter.updateCheckingVersion`、確定なら `OrderConfirmed` の発行と出版の行の INSERT（同じトランザクション） |
| `DStart`           | Spring Modulith 2.1.2 の `CompletionRegisteringMethodInterceptor` → `markProcessing`（`status != 'PROCESSING'` の UPDATE）                   |
| `DReadPayment`     | `ChargeOrderCommandHandler.handle` の `paymentRepository.findByOrderId`                                                                     |
| `DReadOrder`       | `ChargeOrderCommandHandler.handle` の `orderQueries.findDetails` と `details.confirmed()`                                                   |
| `DSend`            | `PaymentGatewayClient.charge` の `POST /v1/charges`（`Idempotency-Key` は注文 ID）                                                          |
| `GatewayDecide`    | 決済代行が冪等性キーの判定を決める（モデルの外の決済代行。ADR-072 の契約）                                                                  |
| `DReply`           | `PaymentGatewayClient.charge` の戻り値（`toOutcome`、`contractError`）か例外（タイムアウト、5xx、401、403、429）                             |
| `DInsert`          | `Payment.record`、`JooqPaymentRepository.add` → `TableWriter.insert` とリスナーのトランザクションのコミット。`uk_2_t_payment` が二件目を拒む |
| `DMarkCompleted`   | `DefaultEventPublicationRegistry.markCompleted`（archive への写しと削除）                                                                    |
| `DMarkFailed`      | `DefaultEventPublicationRegistry.markFailed`。同じ出版の別の配信が進行中の登録を外していれば何もしない                                      |
| `DCrash`           | 配信の途中のプロセスの停止。リスナーのトランザクションはロールバックする                                                                    |
| `Stale`            | `DefaultEventPublicationRegistry` の Staleness Monitor（`spring.modulith.events.staleness.*`）                                              |
| `RPick`、`RClaim`  | `EventPublicationResubmitter.resubmit` → `FailedEventPublications.resubmit`、`JdbcEventPublicationRepositoryV2.markResubmitted`（`status != 'RESUBMITTED'` の UPDATE） |

変異（`scripts/mutants.py` の `MUTANTS`）と、それが写すコードの退行は次のとおり。

| 変異                               | コードの退行                                                        | 破れる不変条件            |
| ---------------------------------- | ------------------------------------------------------------------- | ------------------------- |
| `no-unique-index`                  | `uk_2_t_payment` を消す                                             | `AtMostOnePaymentRecord`  |
| `timeout-recorded-as-failed`       | `PaymentGatewayClient.charge` がタイムアウトや 5xx も `ChargeOutcome.failed()` にする | `RecordMatchesGateway`    |
| `cancel-confirmed-order`           | `Order.cancel` から `ensureDraft()` を消す                          | `ChargedOnlyIfConfirmed`  |
| `listener-swallows-gateway-error`  | `OrderConfirmedListener.on` が例外を握りつぶして正常に返す          | `CompletedOnlyWithRecord`、`ChargeNeverSilentlyLost` |

## 意図した変更でモデルを直す

1. 変えたコードを対応表で引き、対応するアクションをコードの新しい振る舞いに合わせて書き換える。
   新しい不可分な操作が増えたらアクションを足し、`Next` と対応表に足す。
2. 新しい約束が増えたら不変条件を足し、それを破る変異を `MUTANTS` と上の表に足す。
3. `check.sh` と `mutants.py` を流し、違反が出たら「結果を読む」で切り分ける。
4. コードとモデルが一致することを確かめてから、下の同期コミットを新しいコミットに進める。
   確かめていないコミットへ進めない。

## 範囲と前提

検査の範囲は次のとおりで、これを超える保証はない。

- 注文は一つ、画面の操作は確定と取消の各一回、配信は最初の一回と再投入を合わせて 3 回まで（`MaxDeliveries = 3`）。
- 時間を持たない。待ち時間、`in-flight-staleness`、タイムアウトの大小で起きない順序も含めて検査する（安全側に広い）。
- 決済代行は冪等性キーごとに判定を一度だけ決めると仮定する（`contract.cfg`）。判定はモデルの外の約束である。
- 明細の変更（`ChangeOrderLinesCommandHandler`）、金額の正しさ、HTTP の層、認証、複数の注文の間の干渉は扱わない。
- `ResubmissionTracker` の上限到達の記録とメトリクス、`EventPublicationResubmitOnce`（手での再投入）は扱わない。手での再投入は `RClaim` と同じ UPDATE を通るため、同じ経路に含まれる。

## 既知の発見

`concurrent-reject.cfg` で `RecordMatchesGateway` が破れる。
決済代行が判定中のキーへの要求に 409 を返すと、代金を取ったのに決済記録が `FAILED` になり、出版は完了して再投入もされない。
経路は次のとおり。

1. 注文を確定し、最初の配信が `POST /v1/charges` を送り、決済代行が判定を始める。
2. Staleness Monitor が出版を `FAILED` にし、`EventPublicationResubmitter` が二度目の配信を始める（最初の配信が `concurrency-limit` の空きを待つ間に `published` の期限を過ぎた場合など。`application.yaml` の注記）。
3. 二度目の配信が同じ `Idempotency-Key` で請求し、決済代行が 409 を返す。
4. `PaymentGatewayClient.contractError` が 409 を契約の不備として `ChargeOutcome.failed()` にする（`PaymentGatewayClientTest.contractErrorReturnsFailed` が 409 でこれを確かめている）。
5. `FAILED` の決済記録をコミットし、出版を完了にする。
6. 決済代行が最初の請求を受け付け、代金を取る。最初の配信の INSERT は `uk_2_t_payment` で拒まれ、次の再投入は記録を見つけて完了する。

今の契約（`PaymentGateway.charge` の Javadoc）の下では起きないが、実際の決済代行の契約を決めるとき（ADR-072 の未決事項 3）に、判定中のキーへの応答を一時障害に分類するかを決める必要がある。

## 同期の記録

同期コミット：`cf79ec53c58d561e357b3e6c8dcd5af02852e058`（ブランチ `fix/modulith-staleness-from-last-resubmission`。main に入ったら、そのマージのコミットで確かめ直して進める）

このコミットで、「変更がこの検証に関わるか」のファイルと対応表がモデルと一致することを確かめた。
関わるファイルがこのコミットより後に変わっていたら、モデルを読み直してから検査の結果を信じる。
読み直しには `/maintain-formal-verification-skill` を使う。
