# キューと DLQ の方式と、DLQ から戻す手順（#313 の調査）

この文書は、#313（#305 の N3）の調査の記録である。
一時的な成果物で、main に入れない。
決定と作業は #313 のコメントと作業の Issue にある。

- 向こう：`yokozi-jp/agent-software-factory` の `origin/main`（commit `29ba897`）
- こちら：`yokozi-jp/spring-modulith-ai-dlc` の `origin/main`（commit `e29ab2a3`）

## 結論

一部採用とする。
キューと DLQ の置き場所は、こちらの Spring Modulith のイベント出版のレジストリ（`modulith.event_publication`）が同じ役割を持つので、SQS のキュー、DLQ、redrive は取り込まない。
向こうの ADR 0041 から、DLQ へはアプリから送らず、基盤の受信回数の上限だけで移す方針を取り込む。
こちらの `docs/integration/async-failure-recovery.md` は、取り込んだガイドラインのまま、アプリによる即時の退避と基盤の上限の二段構えを定めている。
しかし、こちらの実装（ADR-079）にはアプリから退避する経路がなく、すでに向こうと同じ形で動いている。
規約を実装に合わせ、理由を向こうの ADR 0041 から取る。
向こうの `docs/tooling/dlq-redrive.md` からは、失敗の原因ごとの直し方と戻すかどうかの表と、戻すと外への副作用が繰り返されるかの節を、こちらの Runbook に取り込む。
作業の Issue を 1 つ起こす。

## 取り込むもの

- **DLQ へは基盤の上限だけで移し、アプリから送らない**：向こうは、回復しないエラー（メッセージの形の違反、宛先がない）でも、アプリは例外を投げるだけにし、DLQ へは `maxReceiveCount` だけで移す（向こうの `docs/adr/0041-standard-queue-per-task-with-dlq.md:18`）。
  理由は二つある（同 `:38`）。
  二段構えの利点は FIFO のヘッドオブラインブロッキングを早く解くことで、標準キューでは後ろが止まらない。
  アプリから DLQ へ送ると、送信と削除が原子的でなく、メタデータの継承も要る。
  向こうの規約（`docs/async/dlq.md:58-65`）は二段構えを推奨しており、向こうはそれを ADR で外している（同 `:127-129`）。
  - こちらの規約は二段構えを定める（`async-failure-recovery.md:11`、`:103-115`）。
    出典は向こうと同じフューチャーの非同期設計ガイドラインで（同 `:147`）、ADR-040 は既存の ADR と規約を原文より優先する（ADR-040 の 40 行目）。
  - こちらのキューであるレジストリには順序もグループもなく、ヘッドオブラインブロッキングが起きない（`async-ordering-and-idempotency.md:41`）。
  - Spring Modulith 2.1.2 の出版の状態は `PUBLISHED`、`PROCESSING`、`COMPLETED`、`FAILED`、`RESUBMITTED` の 5 つで、`FailedEventPublications` の公開の操作は `resubmit` だけである（下の「確かめたこと」）。
    アプリが出版を「上限に達した」扱いへ直接移す手段がない。
  - 実装も、アプリから送る経路を持たない。
    業務上の拒否と契約の不備は業務の状態に記録して正常に終え（`async-failure-recovery.md:75-76`）、内部の不変条件の違反は例外にして、方針の上限まで再投入する（同 `:79-83`、ADR-079 の 200 行目）。
    決済のリスナーの 3 つの道（再投入で完了、デッドレターの `FAILED`、取り消した注文で完了）は、`OrderConfirmedListenerTest` が通している（下の「確かめたこと」）。
  - したがって、こちらの DLQ は方針の上限に達した `FAILED` の出版（Runbook の `state=exhausted`）で、`maxReceiveCount` に当たるのは方針の `max-resubmissions` である。
    今の規約は「`FAILED` の状態で残り、これが DLQ の役割を兼ねる」（`async-failure-recovery.md:35`）とし、再投入を待つ `FAILED` と上限に達した `FAILED` を分けていない。
  - 規約の 10 行から 12 行、31 行、101 行から 115 行を、この対応と「アプリは例外を投げるか業務の状態に記録するだけで、DLQ へ送らない」に書き直す。
    外部ブローカーを入れた後も、順序の要らないキューは同じ方針にする。
    順序の要るキューは `async-ordering-and-idempotency.md:52-63` のままにする。
  - ADR-079 は Proposed なので、Decision を直接書き直し、理由を Context に残す（`docs/adr/conventions.md:66`）。
- **Runbook に、リスナーごとの失敗の原因、直し方、再投入するかの表を足す**：向こうの Runbook は、ログのエラーごとに、原因、直し方、DLQ のメッセージを戻すかどうかを表にする（`docs/tooling/dlq-redrive.md:18-23`）。
  値が足りないメッセージのように、戻しても直らないものには「戻さない」と書く。
  こちらの Runbook の「Event publication resubmission exhausted」の対応は、SQL で特定し、ERROR のログと trace で原因を確かめ、再投入するか片付ける、と書くだけである（`runbook-event-publication-resubmission.md:42-43`）。
  決済のリスナーでは、例外の種類で直し方がコードに決まっている。
  - 接続の失敗、タイムアウト、429、5xx（一時障害）：決済代行の回復を待つ。自動の再投入か、手での再投入で戻す（`PaymentGatewayClient.java:28-41`）。
  - `CallNotPermittedException`（circuit breaker が open）：同じく待って戻す。外部を呼ばずに回数を 1 消費する（ADR-079 の 216 行目から 223 行目）。
  - 401、403（資格情報の不備）：資格情報を直してから戻す（`PaymentGatewayClient.java:130-133`）。
  - `NotFoundException`（注文がない。内部の不変条件の違反）：不具合を調べる。注文が本当にないなら戻さず、片付ける（`ChargeOrderCommandHandler.java:72-75`）。
  - `ConflictException`（決済記録の一意制約。同時の 2 回目）：1 回目が決済記録を作っているので、戻すと既存の記録を返して完了する（#551 で `INSERT ... ON CONFLICT DO NOTHING` に直るまでの行）。
  - DB の接続や権限のエラー：DB とモジュールのロールを直してから戻す。
- **戻すと外への副作用が繰り返されるかを Runbook に書く**：向こうは「戻すと 2 通目が出ることがある」の節で、どの失敗のときに 2 通目が出て、どのときに出ないかを書く（`dlq-redrive.md:39-47`）。
  こちらの Runbook には、再投入でリスナーが外部の呼び出しを繰り返すかの記述がない（Runbook と `event-publication-resubmission.md` に「冪等」の語がない）。
  決済では、決済代行の請求の後、決済記録の commit の前に失敗すると、再投入で決済代行をもう一度呼ぶ（請求は `ChargeOrderCommandHandler.java:88`、記録は `:90`、どちらも `:63` の `@Transactional` の中）。
  注文 ID を冪等性キーにするので（`PaymentGatewayClient.java:47`、`:116`、`PaymentGateway.java:11`）、決済代行が契約どおりなら二重に請求しない。
  決済代行が請求していない失敗（接続の失敗、circuit breaker の open、401、403）では、戻して初めて請求する。
  実際の決済代行の冪等性キーの扱いは、ADR-072 の未決事項 3 で契約を選ぶときに確かめる。
- **新しいリスナーで表に行を足す項目**：`docs/backend/class-roles/listener.md` の作成時のチェックリスト（227 行目から）に、Runbook の表に行を足すことを［自分で点検］で足す。

## 取り込まないもの

- **SQS の標準キュー、キューと DLQ の組、`StartMessageMoveTask` の redrive**：こちらはレジストリがキューと DLQ を兼ね（`async-messaging-design.md:20`、`async-failure-recovery.md:35`）、手での 1 回の再投入（`EventPublicationResubmitOnce`）が redrive に当たる（ADR-079 の 133 行目から 139 行目）。
  外に送るイベントも外の受け手もないので、キューを足しても運ぶものがない。
  外部ブローカーの導入は ADR で決める（`async-messaging-design.md:29`）。
- **1 つの業務タスクに 1 つのキューと 1 つの DLQ**（向こうの ADR 0041 の 15 行目）：既にある。
  レジストリはリスナーごとに出版と完了を持ち（`async-messaging-design.md:51`）、再投入の方針もリスナーごとに選ぶ（ADR-079 の 89 行目から 94 行目）。
  手での再投入の対象は `all`、`policy:<名前>`、`event-type:<クラス>` で、リスナーの ID では選べない（`EventPublicationResubmitOnce.java:45-51`）。
  リスナーごとに方針を分ければ同じ単位で戻せるので、対象の種類は足さない。
- **キューの値**（向こうの `apps/worker/src/queues.ts:10-19`）：
  - 可視性タイムアウトを関数のタイムアウトの 6 倍にする規則は、既にある形で持つ。
    処理中の出版を `FAILED` に戻すまでの `in-flight-staleness` を `lock-at-most-for` の 2 倍より長くし、短いと起動に失敗する（`EventPublicationResubmissionConfig.java:290-295`、ADR-079 の 146 行目から 150 行目）。
    `lock-at-most-for` は、executor の空きの待ちとリスナーの処理の上限を兼ねる（ADR-079 の 149 行目）。
  - `maxReceiveCount` の 5 は、方針の `max-resubmissions` の 5 と同じである（`application.yaml:210`、`:214`）。
  - 保持期間（元のキュー 4 日、DLQ 14 日）：こちらの `FAILED` の出版は期限で消えない。
    #542 が消すのは archive の完了した出版だけである。
    向こうの「14 日を過ぎた DLQ のメッセージは戻せない」（`dlq-redrive.md:51`）制約は、こちらにない。
    向こうの規約の「失敗時に DB へ永続化」（`docs/async/dlq.md:112-125` の (2)）に当たる。
- **`batchItemFailures`、Lambda の `handler(event)`、ワーカーを Hono にしないこと**（`apps/worker/src/lambda-handler.ts:5-28`、ADR 0041 の 19 行目と 24 行目）：Lambda と SQS のイベントの形の話で、こちらに当たるものがない。
  ワーカーを別のプロセスに分けるかは #342 で扱う。
- **Outbox を送る処理が、イベントの種類で送り先のキューを選ぶこと**（ADR 0041 の 16 行目）：こちらに送る処理がない（#311）。
- **戻す速度と途中で止める操作**（`dlq-redrive.md:27-37`）：既にある。
  手での再投入は、1 回の起動を `max-per-run`（100 件）で打ち切り、`concurrency-limit`（2）で同時の実行を絞る（Runbook の 152 行目、`application.yaml:59`、`:202`）。
- **戻す人の権限の一覧**（`dlq-redrive.md:54`）：こちらの手での再投入は、同じイメージを同じ設定で 1 回起動するだけで（Runbook の 157 行目から 165 行目）、運用者に別の権限は要らない。
  片付けの `DELETE` に要る DB の権限は、本番の運用の権限と一緒に決める（このリポジトリにはまだ IaC がない。ADR-072 の 118 行目）。
- **Spring Modulith 2.1 の Namastack Outbox と JobRunr**（#311 から渡された比較）：どちらも externalization（外部ブローカーへ送るイベント）の送り方を差し替えるもので、モジュール間の `@ApplicationModuleListener` の配信と再投入を変えない。
  2.1.2 のソースでは、どちらも `EventExternalizationConfiguration.supports` を通ったイベントだけを outbox や job に入れる（下の「確かめたこと」）。
  こちらは externalization を使わない（ADR-001 の 33 行目）ので、入れても動くものがない。
  外部ブローカーを入れる ADR で、Spring Modulith の非同期リスナーによる externalization と比べる。
  リファレンスは、非同期リスナーによる externalization を「outbox pattern の実装に期待される重要な機能を欠く」と書いている。

## コメントへの回答

- **`apps/worker/src/queues.ts` と `lambda-handler.ts`、可視性タイムアウトは関数のタイムアウトの 6 倍**：上の「キューの値」のとおり、`in-flight-staleness` を `lock-at-most-for` の 2 倍より長くする起動時の検査が同じ役割を持つ。
  向こうの 6 倍は AWS の推奨で（ADR 0041 の 41 行目、71 行目）、Lambda の event source mapping の再試行に合わせた値である。
  こちらの 2 倍は、再投入の時刻から executor の空きの待ちとリスナーの処理が、それぞれ最長で `lock-at-most-for` まで続くことから導いた（`application.yaml:197`）。
  理由のある値なので、6 倍にそろえない。
- **アプリは DLQ へ送らず `maxReceiveCount` に任せる**：取り込む（上の「取り込むもの」の 1 つ目）。
  こちらの実装はすでにこの形で、規約だけが二段構えのまま残っている。
- **SES のモックは作らず、検証環境で本物を確かめる（`src/mail/ses.ts`）**：取り込まない。
  向こうは、ローカルと CI では SMTP（Mailpit）の別の実装に差し替え、SES の層は検証環境の本物だけで確かめる（`apps/worker/src/mail/ses.ts:10-11`）。
  こちらは、本番の Client を WireMock のコンテナに向けて確かめる（ADR-072）。
  本番のコード（結果の分類、タイムアウト、冪等性キーのヘッダー）を CI で通すので、こちらの形を残す。
  本物の決済代行で確かめる場所は、契約を選ぶときに決める（ADR-072 の未決事項）。
  メールを送る仕組みそのものは #323 で扱う。
- **yokozi-jp/agent-software-factory#347（ElasticMQ が本物の SQS と同じに動くか）**：今は取り込むものがない。
  こちらには外部ブローカーもエミュレータもなく、レジストリは Testcontainers の本物の PostgreSQL で動く。
  外部ブローカーを入れた後は、Testcontainers などで起動した実物のキューを使うと定めている（`async-testing.md:29`）。
  エミュレータと本物の違い（向こうの調査では、ElasticMQ 1.7.1 で FIFO の `StartMessageMoveTask` が何も移さず、`MessageRetentionPeriod` が効かない）は、外部ブローカーの ADR で版を固定して調べ、確かめられないことを ADR に書く。
  向こうの ADR 0041 の「確かめられなかったこと」（64 行目から 66 行目）が、その書き方の例になる。
- **#311 から渡された「Outbox をキューにしないこと、送る処理、外部のブローカー」**：レジストリをキューとして使い続け、送る処理と外部のブローカーは置かない。
  Namastack Outbox と JobRunr は上の「取り込まないもの」のとおりである。

## ほかの Issue との関係

- **#311（Outbox）**：Outbox を Spring Modulith のレジストリのまま使う決定と同じ前提に立つ。
- **#339、#551（重複の除去）**：Runbook の `ConflictException` の行は、#551 が `ChargeOrderCommandHandler` を `INSERT ... ON CONFLICT DO NOTHING` に直すと要らなくなる。
  #551 が先に入れば、その行は書かない。
  戻すと外部の呼び出しを繰り返すことは、#551 の「外部システムを 2 回目も同じ引数で呼ぶ」検査と同じ前提である。
- **#542（archive を保持期限で消す）**：`FAILED` の出版は消す対象でないことを、こちらの DLQ に保持の期限がない根拠にする。
- **#543（外部システムを呼ぶ CommandHandler を Listener からだけ呼ばせる）**：外への副作用がすべて再投入の経路に載るので、Runbook の「戻すと外への副作用が繰り返されるか」をリスナーごとに書けば漏れない。
- **#342（ワーカーを別のプロセスに分けるか）、#557（executor の枠を分けるか）**：キューの置き場所や実行の場所が変わっても、「DLQ へはアプリから送らず、上限だけで移す」方針は変わらない。
- **#312（イベントの順序）**：順序の要るイベントを足すときの DLQ の扱いは、`async-ordering-and-idempotency.md:52-63` のままで、#312 の結論に従う。
- **#320（トレース）**：再投入したリスナーのトレースが切れる問題は #320 で扱う（#311 の「限界」）。

## 限界

- Runbook の表に全リスナーの行があることは、検査しない（［自分で点検］）。
  リスナーは今 1 つである。
  #551 の全件のテストと同じ集め方で検査できるが、Markdown を読むテストは作らない。
- 決済代行が冪等性キーで二重の請求を止めることは、WireMock の暫定の契約でしか確かめていない（ADR-072 の未決事項 3）。
- 内部の不変条件の違反も方針の上限まで再投入し、そのたびにリスナーの ERROR が出る（ADR-079 の 200 行目）。
  向こうも、回復しないエラーを `maxReceiveCount` まで繰り返す（ADR 0041 の 18 行目、60 行目）。
  原因を見分けて早く止めるには二段構えが要るので、この費用を受け入れる。
- 外部ブローカーを入れるときの Namastack Outbox と JobRunr の比較は、その ADR まで残る。

## 確かめたこと

Spring Modulith 2.1.2 のソース（Maven Central の sources の jar）で、次を確かめた。

- `EventPublication.Status` は `PUBLISHED`、`PROCESSING`、`COMPLETED`、`FAILED`、`RESUBMITTED` の 5 つで、`ABANDONED` のような終わりの状態はない。
- `FailedEventPublications` の操作は `resubmit(ResubmissionOptions)` だけで、`IncompleteEventPublications` も再投入の操作だけを持つ。
- `NamastackOutboxEventRecorder.onApplicationEvent` と `JobRunrEventExternalizer` は、`EventExternalizationConfiguration.supports(payload)` を通ったイベントだけを、それぞれ `Outbox.schedule` と `JobScheduler.enqueue` に渡す。
  どちらも `spring.modulith.events.externalization.mode=outbox` のときだけ有効になる。
- `spring-modulith-starter-namastack` と `spring-modulith-starter-jobrunr` は 2.1.2 で公開されている。
  `spring-modulith-events-namastack` は `namastack-outbox-api` 1.7.3 に依存する。

こちらのリポジトリ（`e29ab2a3`）で、次を確かめた。

- `OrderConfirmedListenerTest`（5 件）と `EventPublicationResubmitterExhaustedTest`（4 件）が通る。
  前者は、決済代行の失敗で `FAILED` に残り再投入で試行の回数 2 で完了すること、注文がないとデッドレターの `FAILED` に残ること、取り消した注文では完了にすることを確かめる。
- `backend/src/main` に DLQ やデッドレターへ送るコードはない（`dlq`、`dead letter` の検索で 0 件）。
- docs で二段構えとアプリからの退避を定めるのは `async-failure-recovery.md` だけである。
- `ChargeOrderCommandHandler.handle` は、決済記録を先に読み、なければ決済代行を呼んでから記録を入れる（`:67`、`:88`、`:90`）。
  請求は記録と同じトランザクションの中で、記録の commit の前に落ちると請求だけが残る。

向こうのリポジトリ（`29ba897`）で、次を確かめた。

- `apps/worker/src/queues.ts` は、DLQ を先に作り、元のキューの `RedrivePolicy` に `maxReceiveCount` を書くだけで、アプリから DLQ へ送る処理がない。
- `apps/worker/src/lambda-handler.ts` は、失敗したメッセージを `batchItemFailures` に入れるだけで、DLQ へ送らない。

## 出典

- [Spring Modulith Working with Application Events](https://docs.spring.io/spring-modulith/reference/events.html)（Externalizing Events、Namastack Outbox Support、JobRunr Outbox Support）
- Spring Modulith 2.1.2 の sources の jar：`spring-modulith-events-api`、`spring-modulith-events-core`、`spring-modulith-events-namastack`、`spring-modulith-events-jobrunr`（Maven Central）
- [Using dead-letter queues in Amazon SQS](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-dead-letter-queues.html)（向こうの ADR 0041 の出典）
- 向こう：`docs/adr/0041-standard-queue-per-task-with-dlq.md`、`docs/tooling/dlq-redrive.md`、`docs/async/dlq.md`、`apps/worker/src/queues.ts`、`apps/worker/src/lambda-handler.ts`、`apps/worker/src/mail/ses.ts`、yokozi-jp/agent-software-factory#347
- こちら：ADR-001、ADR-040、ADR-072、ADR-079、`docs/integration/async-failure-recovery.md`、`docs/integration/async-ordering-and-idempotency.md`、`docs/integration/async-messaging-design.md`、`docs/integration/async-observability.md`、`docs/integration/async-testing.md`、`docs/integration/event-publication-resubmission.md`、`docs/observability/runbook-event-publication-resubmission.md`、`docs/backend/class-roles/listener.md`、`OrderConfirmedListener.java`、`ChargeOrderCommandHandler.java`、`PaymentGatewayClient.java`、`PaymentGateway.java`、`EventPublicationResubmitOnce.java`、`EventPublicationResubmissionConfig.java`、`application.yaml`
