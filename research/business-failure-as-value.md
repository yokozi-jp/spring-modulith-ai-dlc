# 業務の失敗を例外ではなく値で返すか（#317 の調査）

この文書は、#317（#305 の P1）の調査の記録である。
一時的な成果物で、main に入れない。
決定と作業は #317 のコメントと作業の Issue にある。

- 向こう：`yokozi-jp/agent-software-factory` の `origin/main`（commit `29ba897`）
- こちら：`yokozi-jp/spring-modulith-ai-dlc` の `origin/main`（commit `e29ab2a3`）
- 版：こちらは Java 25、Spring Boot 4.1.2（Spring Framework 7.0.10）、Spring Modulith 2.1.2 である。
  調べた時点（2026-11-20）の最新は Spring Boot 4.2.0、Spring Framework 7.1.0、Spring Modulith 2.2.0 で、Java 25 は最新の LTS である。

## 結論

一部採用とする。
要求を断る業務の失敗は、今のまま `shared.failure` と `shared.concurrency` の例外で投げる。
向こうの ADR 0027 の「`domain` で `throw` しない」「自作の `Result` で返す」は取り込まない。
Spring では、トランザクションの rollback、イベント出版の取り消し、`@ExceptionHandler` による Problem Details への変換、Spring Modulith の出版の `FAILED` と `AbandonPolicy` が、すべて例外を合図にするからである。
向こうも、`domain` の `Result` を `application` ですぐ Effect の失敗に変え、失敗したトランザクションを rollback している。
トランザクションの境界での振る舞いは、こちらの例外と同じである。

次の 3 つを取り込む。

- 向こうの「呼び出し側が失敗を見分けて処理を変えるか」の基準を、こちらのトランザクションに合わせて規約に書く。
  要求を断り、書いたものを取り消す失敗は例外にする。
  業務の状態に記録して正常に終える結果（決済の拒否、契約の不備、請求しない、処理済み）は値にし、場合が閉じていれば sealed interface と record で表す。
- 向こうの失敗の `_tag` に当たるものとして、422 の例外に失敗の理由を型の値（モジュールごとの enum）で持たせる。
  文字列だけのコンストラクタを消し、理由のない 422 を投げられないようにする。
  #541 が #317 に任せた「識別子の運び方」は、この形にする。
- 今ある値の結果のうち、`@Nullable` の component で場合を表している `ChargeOrderResult` と `ChargeOutcome` を sealed interface にし、`*Result` の record に `@Nullable` の component を置かないことをテストで検査する。

最新版で判断は変わらない。
Spring Framework 7.1 は、`@ExceptionHandler` で扱わない例外を Problem Details にする仕組み（spring-projects/spring-framework#37398、#37416 は同じリポジトリ）と、`ErrorResponse.Interceptor` が要求を読めること（spring-projects/spring-framework#37410）を足した。
どれも例外から Problem Details への経路を強める変更で、値で返した失敗を扱う仕組みは足していない。
この問いのために版を上げる必要はない（Spring Modulith 2.2 と Spring Boot 4.2 への更新は #562 で扱う）。

## 取り込むもの

- **例外と値の使い分けの基準**：向こうは「呼び出し側が失敗を見分けて処理を変えるなら `Result`、変えないなら `throw`」と決めている（向こうの `docs/domain-model/failures.md:18`）。
  こちらでこの基準を当てると、次のようになる。
  - Controller は失敗を見分けず、`ApiExceptionHandler` が型から 404、409、422 にする（`ApiExceptionHandler.java:96-114`）。
    呼び出し側が処理を変えないので、例外が合う。
  - `SignInOidcUserService.signIn` は、一意制約の `ConflictException` を受けて呼び直す（`SignInOidcUserService.java:89-98`）。
    処理は変えるが、`23505` の後は PostgreSQL のトランザクションが中断状態になり、続けられない（`business-exception.md:140-141`）。
    `handle` のトランザクションを例外で終わらせ、新しいトランザクションで呼び直すので、例外が合う。
  - `ChargeOrderCommandHandler` は、決済代行の拒否と契約の不備を決済記録に保存して正常に終え、確定していない注文には請求せずに返す（`ChargeOrderCommandHandler.java:63-92`、`async-failure-recovery.md:75-76`、`:84`）。
    結果を commit し、出版を完了にするので、値が合う。
  基準は「失敗を見分けるか」だけでは足りず、「書いたものを取り消すか」で分かれる。
  取り消すなら例外、記録して commit するなら値にする。
  今の docs はこれを `async-failure-recovery.md:72-84` でリスナーにだけ書き、`business-exception.md` と `command-handler.md:72` は例外だけを書いている。
  CommandHandler の規約として、両方を 1 か所に書く。
- **失敗の理由を型の値で持つ**：向こうは、失敗を `_tag` の union にし、`type` を `_tag` から機械的に作る（向こうの `failures.md:32`、`:63`）。
  こちらでは、`BusinessRuleViolationException` に理由を渡す。
  理由は、`shared.failure` に置く interface を、モジュールごとの enum が実装する形にする（例：`ordering` の `NOT_DRAFT`、`PRODUCT_NOT_FOUND`、`PRODUCT_DISCONTINUED`、`NO_LINES`）。
  sealed interface にはできない。
  こちらは名前のないモジュール（`module-info.java` がない）で、sealed の型の permits は同じパッケージに限られる（JLS 8.1.6）。
  `shared.failure` から各モジュールの enum を permits に書くと、`javac` が「class Reason in unnamed module cannot extend a sealed class in a different package」で止める（下の「確かめたこと」）。
  網羅は要らない。
  `type` と `title` のキーは enum の定数から機械的に作り、変換の箇所に理由ごとの分岐を書かないからである。
  足し忘れは、すべての定数に日本語と英語の `title` があることをテストで止める（#541 の受け入れ条件）。
  文字列だけのコンストラクタを消すと、理由のない 422 はコンパイルで止まる。
  向こうの「4xx への対応の書き忘れを止めるセンサー」（向こうの `docs/adr/0027-domain-model-as-pure-functions-and-unions.md:61`）に当たるのは、このコンパイルとテストである。
- **値の結果は sealed interface と record にする**：こちらの規約はすでに、閉じた場合分けを sealed interface と record で表し、「`@Nullable` の component を並べて、どれかが入ることを約束にする record」を禁じている（`docs/backend/java-type-modeling.md:15-28`）。
  ところが、値で返している業務の結果の 2 つが、この形になっている。
  - `ChargeOrderResult(@Nullable String paymentId)` は、請求しなかったことを `null` で表す（`ChargeOrderResult.java:10`、`ChargeOrderCommandHandler.java:85`）。
  - `ChargeOutcome(PaymentStatus status, @Nullable GatewayPaymentCode gatewayPaymentCode)` は、契約の不備だけ識別子を持たないことを、コンパクトコンストラクタの実行時の検査で守る（`ChargeOutcome.java:13-20`）。
  どちらも sealed interface にし、場合ごとの record に必要な値だけを持たせる。
  ただし、`ClassRoleArchTest.commandsAndResultsAreApplicationRecordsRule` は `*Result` を record に限る（`ClassRoleArchTest.java:399-419`）。
  `*Result` を「record か、許可された型がすべて record の sealed interface」にする。
  `*Result` の record に `@Nullable` の component がないことは、反射のテストで検査する。
  JSpecify の `@Nullable` は実行時に残る型の注釈で、`RecordComponent.getAnnotatedType()` から読める（下の「確かめたこと」）。

## 取り込まないもの

- **`domain` で `throw` しない規則と、自作の `Result`**：次の理由で、こちらには合わない。
  - Spring の宣言的なトランザクションは、`RuntimeException` と `Error` が出たときだけ rollback し、値を返せば commit する（[Rolling Back a Declarative Transaction](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html)）。
    値を返して rollback させる仕組みは、Vavr の `Try` の `Failure`（新しい依存で、中身も例外）と、例外で完了した `CompletableFuture` だけである。
    ほかは `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()` を書くことになり、リファレンスは宣言的な方法を強く勧めている。
  - 値で返すと、失敗の前に書いたものが commit される。
    `LockedRoot.updateChild` は、集約ルートのロックと子の行の更新の途中で 422 を投げる（`LockedRoot.java:39-54`）。
    `ConfirmOrderCommandHandler` は、更新の後に `OrderConfirmed` を出版する（`ConfirmOrderCommandHandler.java:48-49`）。
    出版の行は業務と同じトランザクションに書かれ、commit すると決済のリスナーが動く（[Spring Modulith の Event Publication Registry](https://docs.spring.io/spring-modulith/reference/events.html)）。
    例外なら、業務の行も出版の行も一緒に取り消される。
  - Spring Modulith は、リスナーが正常に戻れば出版を完了にし、例外を投げたときだけ `FAILED` にする（2.2.0 の `CompletionRegisteringAdvisor.java:153-181`）。
    2.2 の `AbandonPolicy` も、リスナーが例外を投げたときにだけ呼ばれる（同リファレンスの「Abandoning failed publications」）。
    内部の不変条件の違反を値で返すと、出版が完了になり、ADR-072 と ADR-079 のデッドレターに残らない。
  - 向こうの理由は「`throw` では 4xx への対応の書き忘れを止めるセンサーがない」である（向こうの `docs/adr/0027-domain-model-as-pure-functions-and-unions.md:61`、`failures.md:19`）。
    向こうは route ごとに `_tag` を応答へ変えるので、書き忘れが起きる（向こうの `failures.md:58-64`）。
    こちらは `ApiExceptionHandler` が型だけで変換し（ADR-062）、route ごとの対応がない。
    理由ごとの `type` も、上の「取り込むもの」のとおり enum の定数から機械的に作る。
    書き忘れの起きる箇所がないので、この理由はこちらに当たらない。
  - 向こうも、トランザクションの境界では失敗の経路を使っている。
    `domain` の `Result` は `application` が `fromResult` で `Effect.fail` に変え（向こうの `docs/domain-model/application-effect.md:37-52`）、`inTenantTransaction` は成功以外の `Exit` で rollback する（向こうの `packages/shared-kernel/src/tenant-db/tenant-transaction.ts:94-95`、`:128-130`）。
    Effect の型付きの失敗に当たるものは、Java では非検査例外である。
    違いは `domain` の中だけで、TypeScript に型付きの例外がないことを `Result` で補っている。
  - Java の標準ライブラリに `Result` はなく、Spring もそれを扱わない。
    自作の `Result` を足すと、上の rollback と出版の取り消しを手で書くことになり、フレームワークの仕組みから離れる。
  - 向こうでも使われていない。
    `Result` と `fromResult` を使うのは `shared-kernel` のテストと fixture だけで、モジュールの `domain` に `Result` を返す関数はない（`git grep` で確かめた）。
    `claim` の `domain` は `createDraft` だけで、失敗しない（向こうの `packages/claim/src/domain/claim.ts:35`）。
- **検査例外を失敗のセンサーにする案**：Java で「呼び出し側に処理を強いる」型の仕組みは検査例外である。
  ただし、Spring は既定で検査例外を rollback せず、`@EnableTransactionManagement(rollbackOn = RollbackOn.ALL_EXCEPTIONS)` か各 `@Transactional` の `rollbackFor` が要る（Spring Framework 7.0.10 の `RollbackOn` で確かめた）。
  ラムダと `Stream` の中で投げられず、`Optional.orElseThrow` の形も崩れる。
  こちらでは呼び出し側が失敗を処理しない（`ApiExceptionHandler` が受ける）ので、強いる相手がいない。
- **`ErrorResponseException` か `ErrorResponse` を例外に実装させる案**：Spring の `ErrorResponse` は、`problemDetail.type.<クラスの完全名>` と `problemDetail.title.<クラスの完全名>` で、クラスごとに `type` と `title` を解決する（Spring Framework 7.0.10 の `ErrorResponse.getDefaultTypeMessageCode`）。
  理由ごとに `type` を変えるには、クラスを理由の数だけ作ることになり、ADR-062 の「集約ごとの型を作らない」とぶつかる。
  `shared.failure` が Spring Web に依存し、`sharedModuleDoesNotDependOnHttp` にも反する（ADR-062 の選択肢 5）。
  `type` と `title` は今の `ApiProblemDetails.businessProblem`（`ApiProblemDetails.java:125`）が同じ `MessageSource` で作るので、これを使う。
- **集約の状態を判別可能な union にすること**（向こうの ADR 0027 の Q1、`docs/adr/0027-domain-model-as-pure-functions-and-unions.md:14`）：こちらは、集約の `status` を変わるフィールドで持つと決めている（`java-type-modeling.md:27`）。
  こちらの遷移は、DB から読んだ状態に対する要求である。
  状態ごとの型にしても、CommandHandler は読んだ集約を型で振り分け、合わなければ 422 を返すことになる。
  実行時の検査が集約から CommandHandler へ移るだけで、コンパイラが止める誤りは増えない。
  向こうの `claim` も、6 つの状態のうち下書きしか書いていない（向こうの `claim.ts:3-4`）。
- **`domain` の純粋さ（時刻と ID を引数で受ける）**：時刻はすでに引数で受けている（`Order.draft` の `orderedAt`、`docs/datetime/` の `Clock` の規約）。
  ID は ADR-060 で集約が UUID を採番すると決めており、この問いとは別である。
- **`_tag` という判別のフィールド**：Java は sealed interface の許可された型で判別し、フィールドが要らない。

## コメントへの回答

この Issue にコメントはない。
ほかの Issue から渡された問いに答える。

- **#309 の「#317（P1）との関係」**：「例外のままなら、`BusinessRuleViolationException` と `ConflictException` にモジュールごとの識別子（enum など）を渡す形」になる。
  `BusinessRuleViolationException` には、モジュールごとの enum を渡す。
  `ConflictException` は、今の `Kind`（`VERSION`、`LOCK`、`UNIQUE`）から種類ごとの共通の `type` を作り、モジュールごとの識別子を足さない（#309 の決定のまま）。
  `NotFoundException` は 404 の `about:blank` のままで、識別子を足さない。
- **#310 の「保存するのは CommandHandler の Result」**：変わらない。
  要求を断る失敗は例外のままなので、#540 の「2xx 以外は rollback し、キーの行を残さない」は、例外による rollback でそのまま成り立つ。
  値で返すと、失敗の Result がキーの行と一緒に commit され、#540 で保存しない処理を別に書くことになっていた。

## ほかの Issue との関係

- **#541（422 と 409 の失敗ごとの problem type）**：識別子の運び方を「例外に渡す enum」に決める。
  #541 は #317 を待たずに進められる。
  #541 に足す項目は、下の「#541 に足す項目」にある。
- **#540（Idempotency-Key）**：影響しない。
  HTTP の CommandHandler の Result は record のままで、保存の形は変わらない。
  sealed にする `ChargeOrderResult` はリスナーから呼ばれる CommandHandler の Result で、#540 の対象（HTTP の要求）に入らない。
- **#551（すべてのリスナーに同じイベントを 2 回届ける）**：`ChargeOrderCommandHandler` を、決済記録の `INSERT ... ON CONFLICT DO NOTHING` に直す。
  この Issue の作業と同じクラスを変えるので、後に行う方が先の形に合わせる。
  処理済みを Result で表すことは、#339 の「重複かどうかは CommandHandler の Result とログで表せば足りる」と合う。
- **#562（Spring Modulith 2.2 と `AbandonPolicy`）**：#562 は「直らない例外（内部の不変条件の違反、`NotFoundException` など）を、上限を待たずに諦める `AbandonPolicy` を足す」としている。
  ただし、2.2.0 の `AbandonPolicy.shouldAbandon` が受け取るのは `EventPublication` だけで、例外を受け取らない（`AbandonPolicy.java:35`）。
  `CompletionRegisteringAdvisor.handleFailure` も、例外を `markFailed` に渡さない（2.2.0 の `CompletionRegisteringAdvisor.java:179-181`）。
  例外の型で諦めるかを決めるには、2.2 の API だけでは足りない。
  #562 の作業で、例外の型をどう `AbandonPolicy` へ届けるか（または届けないか）を決める必要がある。
  この Issue の決定（失敗は例外のまま）は、#562 の前提を変えない。
- **ADR-062**：決定の中心（業務の失敗を 3 つの型の例外で投げる）は変えない。
  Proposed なので、作業の Issue で Decision に例外と値の使い分けを足し、#541 で 422 の理由と `type` を足す。
  どちらも ADR-062 を書き直すので、同じ節を 2 度書かないよう、後の Issue が先の版に足す。

## 限界

- 値で返す結果は、今は決済の 1 か所だけである。
  #369 の招待（期限切れ、メールアドレスの不一致）のように、拒否を記録して正常に終えるか、要求を断るかで迷う業務が出たら、この基準で判断する。
  基準で決まらない場合は、ここでは確かめていない。
- `@Nullable` の component の検査は `*Result` の record だけを対象にする。
  Domain の値（`ChargeOutcome` など）に同じ形が出ても、機械では止まらず、`java-type-modeling.md` の規約とレビューで守る。
- sealed の `*Result` を JSON に保存する場合の形（Jackson の型の情報）は確かめていない。
  #540 が保存する HTTP の Result は record のままなので、今は要らない。
- `ChargeOutcome` を sealed にすると、`JooqPaymentRepository` の復元（`JooqPaymentRepository.java:92`）と `PaymentGatewayClient` の変換も変わる。
  決済の振る舞いは変えないが、`formal-verify-order-payment` のモデルとの対応は作業で確かめる。

## 確かめたこと

- Java 25（25.0.5）で、`a.Reason` を sealed にし、別のパッケージ `b` の enum を permits に書くと、`javac` が `class Reason in unnamed module cannot extend a sealed class in a different package` で止まった。
- 同じ Java 25 と JSpecify 1.0.1 で、`record ChargeOrderResult(@Nullable String paymentId)` の `getRecordComponents()[0].getAnnotatedType().isAnnotationPresent(Nullable.class)` は `true`、注釈のない component は `false` を返した。
- Spring Framework 7.0.10（こちらの版）の `spring-tx` に `RollbackOn`（`RUNTIME_EXCEPTIONS`、`ALL_EXCEPTIONS`）と `EnableTransactionManagement.rollbackOn` があり、`spring-web` の `ErrorResponse` に `getDefaultTypeMessageCode(Class)` と `getDefaultTitleMessageCode(Class)` がある（`javap` で確かめた）。
- Spring Modulith 2.2.0 のソース（タグ `2.2.0`）で、`CompletionRegisteringAdvisor` は `invocation.proceed()` が例外を投げたときだけ `handleFailure` で `markFailed` を呼び、正常に戻れば `completeInNewTransaction` で完了にする（`:153-181`）。
  `AbandonPolicy.shouldAbandon(EventPublication)` は例外を受け取らず、`EventPublication` にも例外を読むメソッドはない。
- Maven Central の最新は、Spring Boot 4.2.0、Spring Framework（`spring-web`）7.1.0、Spring Modulith 2.2.0 である。
  Spring Framework 7.1.0 の M1 から RC2 までのリリースノートで、トランザクションと Problem Details に関わる変更は、#37398、#37400、#37410、#37416、#35969（すべて spring-projects/spring-framework）で、値で返す失敗の扱いはない。
- こちらの `backend/src/main` に sealed の型、`setRollbackOnly`、Vavr はない。
  `@Nullable` の component を持つ `*Result` と `*Outcome` は、`ChargeOrderResult` と `ChargeOutcome` だけである。
- `BusinessRuleViolationException` を投げる箇所は、`Order`（4 つの理由、`Order.java:174`、`:186`、`:190`、`:204`）、`ordering` と `payment` の `Money`、`Quantity`、`LockedRoot`（`:50`）、`CurrentTenantController`（2 つの理由、`:88`、`:94`）である（#309 の数え方と同じ）。
- 向こうの `origin/main` で、`Result<` と `fromResult` を使うのは `packages/shared-kernel` の中（`result/result.ts`、`result/from-result.test.ts`、`__fixtures__/result.ts`）だけである。

## 出典

- [Spring Framework: Rolling Back a Declarative Transaction](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html)
- [Spring Modulith: Working with Application Events](https://docs.spring.io/spring-modulith/reference/events.html)（The Event Publication Registry、Event Publication Lifecycle、Abandoning failed publications）
- [CompletionRegisteringAdvisor.java（2.2.0）](https://github.com/spring-projects/spring-modulith/blob/2.2.0/spring-modulith-events/spring-modulith-events-core/src/main/java/org/springframework/modulith/events/support/CompletionRegisteringAdvisor.java)、[AbandonPolicy.java（2.2.0）](https://github.com/spring-projects/spring-modulith/blob/2.2.0/spring-modulith-events/spring-modulith-events-api/src/main/java/org/springframework/modulith/events/AbandonPolicy.java)
- [JLS 8.1.6 Permitted Direct Subclasses](https://docs.oracle.com/javase/specs/jls/se25/html/jls-8.html#jls-8.1.6)
- spring-projects/spring-framework#37398、spring-projects/spring-framework#37410、spring-projects/spring-framework#37416
- 向こう：`docs/adr/0027-domain-model-as-pure-functions-and-unions.md`、`docs/adr/0028-effect-in-application-and-infrastructure.md`、`docs/domain-model/failures.md`、`docs/domain-model/application-effect.md`、`docs/domain-model/types.md`、`packages/shared-kernel/src/result/result.ts`、`packages/shared-kernel/src/tenant-db/tenant-transaction.ts`、`packages/claim/src/domain/claim.ts`
- こちら：ADR-050、ADR-060、ADR-062、ADR-072、ADR-079、`docs/backend/java-coding.md`（52 から 64 行）、`docs/backend/java-type-modeling.md`、`docs/backend/class-roles/business-exception.md`、`docs/backend/class-roles/command-handler.md`、`docs/backend/class-roles/result.md`、`docs/integration/async-failure-recovery.md`
- こちら：`ApiExceptionHandler.java`、`ApiProblemDetails.java`、`ConfirmOrderCommandHandler.java`、`ChargeOrderCommandHandler.java`、`ChargeOrderResult.java`、`ChargeOutcome.java`、`Order.java`、`LockedRoot.java`、`SignInOidcUserService.java`、`CurrentTenantController.java`、`ClassRoleArchTest.java`

## #541 に足す項目

- `shared.failure` に、422 の理由を表す interface を置き、モジュールごとの enum に実装させる。
  `BusinessRuleViolationException` は理由とメッセージを受け取り、文字列だけのコンストラクタを消す。
- `type` は `/problems/<module>/<定数の kebab-case>`、`title` のキーは `problem.title.<module>.<定数の kebab-case>` にし、enum の定数から機械的に作る。
  モジュールの名前を enum のパッケージから取るか、interface のメソッドで持つかは作業で決める。
- `ApiExceptionHandler` は理由を `ApiProblemDetails.businessProblem` に渡し、理由ごとの分岐を書かない。
- すべての理由の定数に日本語と英語の `problem.title` があることを確かめるテストは、interface を実装する enum を全件集めて回す。
