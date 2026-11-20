# #312 イベントのスキーマの置き場所と、届く順序の守り方

[#312](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/312) の調査の記録である。
地図は [#305](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/305) で、結論は #312 のコメントに書く。
この文書は一時的な成果物で、採用したものは作業の Issue で docs と ADR に反映する。

- 向こう：`yokozi-jp/agent-software-factory` の `origin/main`（commit `29ba897`）
- こちら：`yokozi-jp/spring-modulith-ai-dlc` の `origin/main`（commit `e29ab2a3`）、Spring Boot 4.1.2、Spring Modulith 2.1.2、Jackson 3

## 結論

一部採用とする。

- 取り込む：イベントの互換の規則（変更は省略できる項目の追加だけ。項目の削除、名前の変更、型の変更、型の名前とパッケージの変更はしない）と、過去の形の JSON を今の型で読めることを確かめる fixture のテスト。
- 取り込む：届く順序に頼らない書き方を docs の規則にする。受け手は処理のときに発行側の `<Feature>Queries` で今の状態を読んで判定し、発行側の状態が先に進んだ後に届いたイベントのテストを置く。
- 取り込まない：送る側の events の package を別に置く形、全イベントに共通の `id`、`version`、`occurredAt`、版の比較で状態の写しを更新する形。

## 向こうの方針

### スキーマの置き場所

- モジュールをまたぐイベントは、依存の向きに関係なく送る側の `packages/<module>/events` に置く（`docs/adr/0046-review-split-from-claim-and-events-in-sender-package.md:22`-`26`）。
- events の package は `zod` と `shared-kernel` にだけ依存し、どのモジュールもどの events の package に依存してよい（同 `:24`）。
- 理由は、受け手が依存の向きに逆らうたびに送り手を直すことを避けるためである（同 `:37`）。
  kgrzybek/modular-monolith-with-ddd の `IntegrationEvents` と、Spring Modulith が循環を許さないことを根拠に挙げる。

### スキーマの書き方と互換

- `defineEvent(kind, version, shape)` が `id`、`tenantId`、`kind`、`version`、`occurredAt` を足した zod のスキーマを作る（`packages/shared-kernel/src/event.ts:17`-`32`）。
- 変更は省略可能な項目の追加だけで、`version` を 1 上げる。削除、名前の変更、型の変更はせず、新しい種類のイベントを足す（`docs/async/event-contract.md:18`）。
- 受け手は `version` で分岐せず、今のスキーマで読む（同 `:17`）。
- events の package ごとに `test/fixtures/<種類>.v<版>.json` を置き、`eventFixtureProblems` が次を確かめる（同 `:20`-`31`、`event.ts:50`-`102`）。
  - 今のスキーマがすべての fixture を読める。
  - fixture の名前の版とメッセージの `version` が同じ。
  - 1 から今の版までの各版に fixture がある。
  - 同じ種類を 2 度公開していない。
- 理由は、Outbox の行をデプロイの前のコードが書き、後のコードが読むためである（`event-contract.md:10`）。
- 検査の検出力は `event.test.ts` が、項目の削除と型の変更で確かめる（`packages/shared-kernel/src/event.test.ts:52`-`71`）。
- 入れた PR は yokozi-jp/agent-software-factory#443（Issue yokozi-jp/agent-software-factory#299）。

### 届く順序

- 標準キューのままにし、FIFO にしない（ADR 0046 `:27`）。
- 申請のモジュールが最後に反映した `(回, 審査の版)` を持ち、それより新しいイベントだけを反映し、古いものは何もせずに受け取り済みにする（同 `:28`-`30`）。
- 理由は、FIFO が同じ送り手のキューに入った後の順序しか守らず、DLQ から戻したメッセージと別の送り手の間の順序は守れないためである（同 `:39`、`docs/async/ordering.md:72`）。
- テストには、順序を逆にして届けるケースと DLQ から戻すケースを入れる、としている（ADR 0046 `:31`）。
- 実装はまだない。`approvalEvents` の `reviewVersion` はスキーマにあるが（`packages/approval/events/src/index.ts:7`-`11`）、`packages/claim/src` に承認のイベントを受ける処理はない（`packages/claim/src/index.ts:2` のとおり claim はほかのモジュールを import しない）。

## こちらの今の形

### スキーマの置き場所

- イベントは発行するモジュールのルートの record である（`docs/backend/class-roles/event.md:31`、`backend/src/main/java/com/example/demo/ordering/OrderConfirmed.java:11`）。
- component は Java の標準型と同じルートの型だけで、ArchUnit が検査する（`event.md:40`、`:126`-`127`）。
- 受信は `@ApplicationModuleListener` だけで、ArchUnit が検査する（`event.md:133`）。
- つまり、送る側に置く方針はすでに同じである。

### 直列化と互換

- Spring Modulith はイベントを Jackson で JSON にし、`event_publication.serialized_event` に、型の名前を `event_type` に書く（`JdbcEventPublicationRepositoryV2.java:281`、2.1.2）。
- 直列化に使う `JsonMapper` は、Spring Boot が自動構成したもの、つまり HTTP API と同じものである（`JacksonEventSerializationConfiguration.java:63`-`66`）。
  `spring.jackson` の設定（`backend/src/main/resources/application.yaml:130`-`147`）を変えると、保存するイベントの形も変わる。
- 読むときは `event_type` の名前でクラスを探し、見つからなければ WARN を出してその出版を結果から落とす（`JdbcEventPublicationRepositoryV2.java:594`-`598`、`:632`-`638`）。
  ADR-079 はこれを既知の限界として書き（`docs/adr/ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md:198`）、runbook は警報で後から見つける手順を書く（`docs/observability/runbook-event-publication-resubmission.md:235`-`260`）。
  防ぐ検査はない。
- イベントの変更の規則は docs にない。`event.md:105` は「専用のテストは作らない」とする。
- 本番にはまだ出していない（ADR-079 の Neutral：本番と STG の環境変数は ECS のタスク定義を作るときに足す）。

### 届く順序

- `docs/integration/async-ordering-and-idempotency.md:39`-`41` は、処理順序に依存しない設計にし、Spring Modulith の非同期リスナーも発行順を前提にしない、とする。
- 同 `:43`-`44` は、厳密な順序が要るときは FIFO を使い、シーケンス番号は FIFO を使えずコンシューマーを冪等にできないときに限る、とする。
  プロセスの中のイベントには FIFO がない。
  非同期のリスナーは同時に 2 つまで動き（`application.yaml:59` の `concurrency-limit: 2`）、失敗した出版は ADR-079 のジョブが後から再投入する。
  Spring Modulith のリファレンスも、再投入のときに後のイベントが前のイベントを追い越しうると書く（Externalization の「Serializing Event Externalization」）。
- 実際の受け手は、処理のときに発行側の今の状態を読む。
  `ChargeOrderCommandHandler.handle` は `OrderQueries.findDetails` で注文を読み、確定していなければ請求しない（`backend/src/main/java/com/example/demo/payment/application/ChargeOrderCommandHandler.java:71`-`86`）。
  `OrderConfirmedListenerTest.cancelledOrderIsNotCharged`（`:253`）が、取り消した後に届いた確定のイベントで請求しないことを確かめる。
  ただし、この書き方は docs の規則になっていない。
- イベントに集約の全項目を詰めて受け手に複製を作らせることはアンチパターンで（`event.md:121`）、ADR-050 は Event-Carried State Transfer を「同じプロセスと同じ DB のモジュラーモノリスでは複製の費用だけが残る」として退けている（`docs/adr/ADR-050-define-backend-class-roles-and-naming.md:200`）。

## 判断

### 取り込むもの

- **互換の規則**：向こうの規則（省略可能な項目の追加だけ）を取り込み、Java と Spring Modulith に合わせて足す。
  - 足す component は `@Nullable` にする。古い出版の JSON にはその項目がなく、Jackson は `null` を入れて失敗しない（下の「確かめたこと」）。
  - プリミティブの component を足さない。古い JSON では `0` になり、失敗しない。
  - 型の名前とパッケージを変えない。Spring Modulith が出版を落とし、ERROR も出ない。
  - `spring.jackson` の設定の変更も、保存したイベントの形を変える変更として扱う。
  - 削除、名前の変更、型の変更をしたいときは、新しい型のイベントを足す。
- **過去の形の fixture のテスト**：向こうの `eventFixtureProblems` の考え方を取り込む。
  こちらでは版の番号をイベントに持たせず、fixture のファイル名だけに持たせる（`<型の完全な名前>.v<n>.json`）。
  確かめることは次のとおりである。
  - `@ApplicationModuleListener` が受けるすべての型に、`v1` から連続した fixture がある。出版を保存するのはリスナーのある型だけである。
  - fixture の名前の型が、今のコードで読み込め、リスナーが受ける型である（型の名前とパッケージの変更を止める）。
  - Spring Modulith の `EventSerializer` で読み、もう一度書いた JSON が fixture と同じである（項目の削除と名前の変更、`spring.jackson` の変更を止める）。
  - 読んだ値の、`@Nullable` でない component が `null` でない（必須の項目の追加を止める）。
  - 最も新しい版の fixture は、すべての component が `null` でない（今の形の fixture を足し忘れることを止める。向こうの「今の版の fixture がある」に当たる）。
  - Spring Modulith 2.1.2 の `ApplicationModule.getPublishedEvents()` は、こちらのコードではどのモジュールも空を返す（下の「確かめたこと」）。型はリスナーの引数から集める。#551 と同じ集め方で、ArchUnit がほかの受け方を禁じているので漏れない。
- **順序に頼らない書き方を規則にする**：向こうの「FIFO でも再投入と別の送り手の間の順序は守れない」という理由は、こちらの再投入にもそのまま当てはまる。
  こちらの今の書き方（処理のときに発行側の `<Feature>Queries` で今の状態を読み、イベントは処理を始める合図として使う）を `async-ordering-and-idempotency.md` の規則にする。
  `:43`-`44` の FIFO の記述は、外部のブローカーを入れた後の話に限る。
  受け手には、発行側の状態が先に進んだ後に届いたイベントのテストを求める（`cancelledOrderIsNotCharged` の形）。

### 取り込まないもの

- **送る側の events の package を別に置く形**：こちらはすでに送る側のモジュールのルートに置いている。
  向こうが別の package にするのは、依存の向きに逆らう受け手を許すためである。
  Spring Modulith は型の依存で循環を検出するので、リスナーが相手のイベントの型を受けるだけで循環になる（下の「確かめたこと」）。
  逆向きの受け手を許すには、モジュールごとにイベントだけのモジュールを足し、`ClassRoleArchTest.moduleRootTypesAreRecordsEnumsOrQueries` などのルートの規則を作り直すことになる。
  今のモジュールの間のイベントは `OrderConfirmed`（ordering から payment）だけで、逆向きの受け手はない。
  逆向きの受け手が要るときは、依存の向きそのものの判断なので ADR で決める。
- **全イベントに共通の `id`**：向こうでは `processed_message` のキーである。#339 で処理済みの表を一律にしないと決め、業務のキーで重複を除く。イベントの ID を使う受け手はない。
- **全イベントに共通の `version`**：向こうでも受け手は `version` で分岐しない。こちらは fixture のファイル名に版を持たせれば足り、本文に持たせると、すべてのイベントの record に同じ component が増える。
- **共通の `occurredAt`**：こちらはイベントごとに出来事の時刻（`confirmedAt`）を必須にしている（`event.md:41`-`42`）。
- **共通の `tenantId`**：#363 の Q7 で `TenantScoped` として決めた。
- **版の比較で状態の写しを更新する形**：こちらは受け手に発行側の状態の写しを持たせない（ADR-050、`event.md:121`）。
  写しがなければ、比べる版もいらない。
  処理のときに `<Feature>Queries` で今の状態を読めば、届く順序が逆でも今の状態で判定する。
  写しを持つ受け手が要るときは、ADR-050 を改める判断になり、そのときに版の比較を一緒に決める。
- **AsyncAPI を使わないこと**：こちらも同じプロセスの中のイベントには使わず、外部のブローカーへ送るメッセージだけ AsyncAPI で書く（`docs/integration/async-design-documents.md:40`-`41`）。方針は同じである。

## 確かめたこと

### Jackson と Spring Modulith の直列化

使い捨てのテストで、`ConfigDataApplicationContextInitializer` で `application.yaml` を読み、`JacksonAutoConfiguration` と Spring Modulith の `JacksonEventSerializationConfiguration` だけを載せた文脈から `EventSerializer` を取り出して確かめた。
テストは commit していない。

| 入力                                                                   | 結果                                                     |
| ---------------------------------------------------------------------- | -------------------------------------------------------- |
| `OrderConfirmed("o-1", 2026-11-16T01:02:03.123456Z)` を書く            | `{"orderId":"o-1","confirmedAt":"2026-11-16T01:02:03.123456Z"}` |
| その JSON を、component を 1 つ足した record で読む                    | 足した component は `null`、例外なし                     |
| 同じ JSON を、`orderId` を `orderPublicId` に変えた record で読む      | `orderPublicId` は `null`、例外なし                      |
| 同じ JSON を、`int amount` を足した record で読む                      | `amount` は `0`、例外なし                                |
| 同じ JSON を、`confirmedAt` を `long` にした record で読む             | `InvalidFormatException`                                 |
| `{"orderId":"o-1","x":1}` を `OrderConfirmed` で読む                   | `x` を捨て、`confirmedAt` は `null`、例外なし            |
| 読んで書き直した JSON と元の JSON の比較（`OrderConfirmed`）           | 同じ                                                     |
| 同（component を足した record。足した値は `null`）                     | 同じ（`default-property-inclusion: non_null` のため）    |
| 同（名前を変えた record）                                              | `{"confirmedAt":"…"}` で違う                             |
| 同（`int` を足した record）                                            | `"amount":0` が増えて違う                                |

書き直しの比較だけでは必須の項目の追加を見逃すので、`null` の検査と組み合わせる。

### Spring Modulith のモジュールの循環

使い捨てのテストで、テストのソースに 2 つのモジュール `a` と `b` を作り、`a` が `b` のイベントを、`b` が `a` のイベントを `@ApplicationModuleListener` で受けるようにした。
`ApplicationModules.of(App.class, new ImportOption.OnlyIncludeTests()).detectViolations()` は `Cycle detected: Slice a -> Slice b -> Slice a` を返した。
Spring Modulith 2.1.2 の `ApplicationModules.assertNoCyclesFor` は、モジュールの間のすべての型の依存に `beFreeOfCycles()` を掛ける（`ApplicationModules.java:611`-`618`）。

同じテストで、こちらの `ApplicationModules.of(DemoApplication.class)` の各モジュールの `getPublishedEvents()` は、すべて空だった。

### 向こうのリポジトリ

- `packages/claim/src` に承認のイベントを受ける処理も、`reviewVersion` を比べる処理もない。
- `handleNotification` は `notificationRequested.schema.parse(message)` で検証してから処理する（`packages/approval/src/application/handle-notification.ts:44`）。

## 限界

- fixture のテストは、過去の fixture を書き換えたり消したりすることを止めない。向こうも同じで、レビューに頼る。
- fixture のテストは、受け手が読めることだけを確かめ、読んだ値の意味が変わらないこと（同じ名前で単位を変えるなど）は確かめない。
- 発行側の今の状態を読む書き方は、読んだ時点の状態で判定するので、前のイベントの処理が後の状態を見て動くことがある。受け手を冪等にする規則（#551）と組み合わせて成り立つ。
- 外部のブローカーへイベントを出すときの順序は、#313 で扱う。

## 出典

- [Spring Modulith Working with Application Events](https://docs.spring.io/spring-modulith/reference/events.html)（Event Serializer、Serializing Event Externalization）
- [JdbcEventPublicationRepositoryV2.java（2.1.2）](https://github.com/spring-projects/spring-modulith/blob/2.1.2/spring-modulith-events/spring-modulith-events-jdbc/src/main/java/org/springframework/modulith/events/jdbc/JdbcEventPublicationRepositoryV2.java)
- [JacksonEventSerializationConfiguration.java（2.1.2）](https://github.com/spring-projects/spring-modulith/blob/2.1.2/spring-modulith-events/spring-modulith-events-jackson/src/main/java/org/springframework/modulith/events/jackson/JacksonEventSerializationConfiguration.java)
- [ApplicationModules.java（2.1.2）](https://github.com/spring-projects/spring-modulith/blob/2.1.2/spring-modulith-core/src/main/java/org/springframework/modulith/core/ApplicationModules.java)
- 向こう：`docs/adr/0046-review-split-from-claim-and-events-in-sender-package.md`、`docs/async/event-contract.md`、`docs/async/ordering.md`、`packages/shared-kernel/src/event.ts`、`packages/shared-kernel/src/event.test.ts`、`packages/approval/events/src/index.ts`、`packages/approval/events/test/versions.test.ts`、`packages/approval/events/test/fixtures/*.v1.json`、`packages/approval/src/application/handle-notification.ts`、yokozi-jp/agent-software-factory#443、yokozi-jp/agent-software-factory#299、yokozi-jp/agent-software-factory#387、yokozi-jp/agent-software-factory#409
- こちら：ADR-050、ADR-079、`docs/backend/class-roles/event.md`、`docs/integration/async-ordering-and-idempotency.md`、`docs/integration/async-design-documents.md`、`docs/observability/runbook-event-publication-resubmission.md`、`OrderConfirmed.java`、`ChargeOrderCommandHandler.java`、`OrderConfirmedListenerTest.java`、`application.yaml`
