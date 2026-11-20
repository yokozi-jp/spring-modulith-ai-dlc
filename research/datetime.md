# 日時の扱いの差分（#322 の調査）

この文書は #322 の調査の記録で、merge しない。
結論は #322 のコメントと同じで、作業は #584、#585、#586 で行う。
向こうは `yokozi-jp/agent-software-factory`（main、commit `29ba897`）、こちらは `yokozi-jp/spring-modulith-ai-dlc`（`origin/main`、commit `63e5c9f`）である。

## 結論

一部採用とする。
瞬間を UTC で持ち、暦日を変換しない値で持ち、業務の暦を業務タイムゾーンで明示して求める方針は、両方で同じである。
こちらに足りない点のうち、次の 3 つを取り込む。

- 現在時刻を、1 つのユースケースの入口で 1 回だけ読み、業務の時刻と共通カラムの `*_at` に同じ値を使う（yokozi-jp/agent-software-factory#506、yokozi-jp/agent-software-factory#737）。
- バックエンドのテストを、JVM の既定のタイムゾーンを `America/New_York` にして流す（向こうの ADR 0043 の「テスト」）。
- 暦日を API で `full-date`（`2023-09-30`）で受け渡す規則と、SQL で `timestamptz` から暦日や境界を作らない規則を docs に書き、OpenAPI と ArchUnit の検査で止める（向こうの `docs/web-api/response-body.md:117`、ADR 0043 の「プロセスと DB の既定」）。

Temporal と Effect による実装、業務タイムゾーンを定数で持つこと、画面を業務タイムゾーンで表示すること、ミリ秒 3 桁、オフセット付きの入力を 400 にすることは取り込まない。
作業の Issue を 3 つ起こした（#584、#585、#586）。

## 取り込むもの

### 現在時刻を入口で 1 回だけ読む

向こうでは、`inTenantTransaction` がトランザクションの開始のたびに時計を読み、1 つのメッセージの処理の中で時刻が最大 3 つになった（yokozi-jp/agent-software-factory#506）。
入口（route の handler、ワーカーの 1 件、バッチの 1 回の実行）が 1 回読んだ `now` を、`inTenantTransaction` が必須の引数で受ける形に直した（commit `592a25a`、yokozi-jp/agent-software-factory#737）。
`now` を省略できる形にしなかったのは、入口の渡し忘れが型に出ないためである。
共通カラムの時刻は「処理を受けた時刻」になり、書き込みの直前の時刻ではなくなる、と PR に書いている。

こちらにも同じ形の誤りがある。

- `CommonColumns.audit()` は、`forInsert` と `forUpdate` の呼び出しのたびに `Instant.now(clock)` を読む（`backend/src/main/java/com/example/demo/shared/infrastructure/persistence/CommonColumns.java:119-123`）。
- `DraftOrderCommandHandler` は `Order.draft(..., Instant.now(clock))` で `ordered_at` を決め（`ordering/application/DraftOrderCommandHandler.java:43-48`）、Repository は `t_order` と `t_order_line` の行ごとに `commonColumns.forInsert` を呼ぶ（`ordering/infrastructure/persistence/JooqOrderRepository.java:84`、`:143`）。
  1 つの注文の下書きで、`ordered_at`、`t_order.created_at`、明細の行ごとの `created_at` が、それぞれ別に時計を読む。
- `ConfirmOrderCommandHandler` は、`t_order.updated_at` とは別に `OrderConfirmed` の時刻を読む（`ordering/application/ConfirmOrderCommandHandler.java:49`）。
- システムの `Clock` は `Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000))` である（`docs/datetime/timezone-conventions.md:30`）。
  JDK 25.0.5 の jshell で、この時計を 2 回読む間に軽い処理を挟むと、1000 回のうち 993 回で値が違った。
- テストは `Clock.fixed(...)` を渡す（`docs/datetime/timezone-conventions.md:148`）ので、何度読んでも同じ値になり、この誤りはテストで見えない。

フレームワークには、1 つの処理の現在時刻を固定する仕組みがない。
Spring Framework 7 の `Clock` の扱いは注入だけで、Spring Boot 4.2.0 のリリースノートにも日時の変更はない。
PostgreSQL の `transaction_timestamp()` はトランザクションの中で同じ値を返すが、`Clock.fixed` で固定できず、トランザクションを 2 つ開く処理でそろわないので使わない（`docs/database/postgresql-common-columns.md:59`、`:66`）。

こちらには、ユースケースの呼び出しを囲んで `*_pgm_cd` を `ScopedValue` に束縛する `PgmCdAspect` がある（`shared/infrastructure/persistence/PgmCdAspect.java:36-57`、ADR-051）。
入口で 1 回読む値をどこで決め、どう渡すかは、作業の Issue で決める。
選択肢は、向こうと同じく Command か引数で `now` を渡す形と、`PgmCdAspect` が一番外のユースケースで `now` を束縛して `CommonColumns` と CommandHandler が読む形である。
後者は呼び出し側の変更が少ないが、時刻への依存が型に出ない。
どちらの形でも、経過時間を測る読み取り（`EventPublicationResubmitter.java:175`、`:231` の予算の判定）は対象から外す。
`Payment.record(..., Instant.now(clock))` は決済代行の応答の後に読む（`payment/application/ChargeOrderCommandHandler.java:88-89`）。
入口の値にすると、`recordedAt` は「記録した時刻」から「処理を始めた時刻」に変わるので、意味を決め直す。

### テストを America/New_York で流す

向こうは Vitest の node の project を `TZ=America/New_York` で流し（`apps/api/vite.config.ts:26-28`、`apps/worker/vite.config.ts:12-14`）、効いていることをテストで確かめる（`apps/api/src/timezone.unit.test.ts`、`packages/shared-kernel/src/time/time.test.ts` の「テストのタイムゾーン」）。
業務のタイムゾーン（東京）とも本番（UTC）とも違い、夏時間があるので、プロセスのタイムゾーンに頼る誤りが、どちらを前提にしていても出る、という理由である（ADR 0043 の「テスト」）。
`apps/worker/src/handle-notification.test.ts:78-90` は、プロセスが東京でなくてもメールの日時が東京の時刻で書かれることを確かめる。

こちらは、通常のバックエンドテストを UTC の JVM で 1 回だけ流す（`docs/datetime/timezone-conventions.md:147`、`:170`、`backend/build.gradle:206`）。
UTC にしている理由は、docs にも ADR-006 にも書かれていない（ADR-006:32 は JVM を UTC にするとだけ書く）。
こちらのコードがタイムゾーンに頼る誤りは、Error Prone の `JavaTimeDefaultTimeZone` と ArchUnit の `DateTimeConventionsArchTest` が静的に止める。
静的な検査が見ないのは、ライブラリと設定の経路である。

確かめた結果は次のとおりである。

- `backend/build.gradle` の `test` の `user.timezone` を `America/New_York` にして全テストを流すと、99 のテストクラスの 1153 件がすべて通った。
  使い捨てのテストで `TimeZone.getDefault().getID()` が `America/New_York` であることも確かめた。
- `application.yaml:80-81` の `connection-init-sql` から `SET TIME ZONE 'UTC'` を消すと、America/New_York の JVM では `DemoApplicationTest` の「DB セッションのタイムゾーンは UTC である」が `expected: <UTC> but was: <America/New_York>` で落ちた。
  UTC の JVM では、同じ変更で `DemoApplicationTest` と `ShedLockSessionTimeZoneTest` がすべて通った。
  PgJDBC は JVM の既定のタイムゾーンを接続の `TimeZone` として送る（`docs/datetime/timezone-conventions.md:47-48`）ので、UTC の JVM では `connection-init-sql` が消えても気付けない。
  America/New_York にすると、この検査が `connection-init-sql` を確かめるようになる。

本番（コンテナの `JAVA_TOOL_OPTIONS`）、`bootRun`、`seedLocalData` は UTC のままにする。
PIT の `jvmArgs`（`backend/build.gradle:353`）はテストを流すので、`test` と合わせる。
ADR-006 の「JVM は `-Duser.timezone=UTC`」をテストについて改めるので、ADR を改める。

フロントエンドには取り込まない（下の「取り込まないもの」）。

### 暦日を full-date で受け渡し、SQL で暦日と境界を作らない

向こうは、暦日を UTC の 0 時の瞬間（`2023-09-30T00:00:00Z`）ではなく `full-date`（`2023-09-30`）で返す（`docs/web-api/response-body.md:117`、ADR 0043 の「暦日を瞬間で返さない」）。
UTC より西のタイムゾーンで前日に見えるためである。
こちらの画面は、ブラウザの既定のタイムゾーンで表示する（`docs/datetime/timezone-conventions.md:70-71`、`frontend/apps/web/src/features/orders/components/payment-status.tsx:64-69`）ので、暦日を瞬間で返すと同じ誤りが起きる。

こちらの docs は、日付だけの値を Java の `LocalDate` と PostgreSQL の `DATE` にする（`docs/datetime/timezone-conventions.md:111-112`）が、API での形を書いていない。
`docs/web-api/` にも暦日の記述はない。
仕組みはフレームワークにある。

- Jackson 3 は `WRITE_DATES_AS_TIMESTAMPS` の既定を false にした（JSTEP-2）。
  jackson-databind 3.3.0（こちらが使う版で、最新版）の jshell で、`LocalDate.of(2023, 9, 30)` は `"2023-09-30"` に、`Instant` は `"2026-04-01T02:00:00.123456Z"` になった。
- ただし、既定の `LocalDate` の読み取りは寛容で、`"2023-09-29T15:00:00Z"`（東京の 9 月 30 日）を `2023-09-29` として受けた。
  `JsonMapper.builder().defaultLeniency(false)` では、この入力が `InvalidFormatException` になり、`"2023-09-30"` は受けた。
  同じ設定で、`Instant` のオフセット付きの入力と `Z` の入力は、これまでどおり受けた。
  Spring Boot 4.1.2 には、この設定のプロパティ `spring.jackson.default-leniency` がある（`spring-boot-jackson-4.1.2.jar` の設定のメタデータ）。

検査は、既存の仕組みを使う。

- OpenAPI：`.spectral.yaml:142-160` の `public-id-uuid` は、名前が `Id` で終わる項目に `format: uuid` を求める。
  同じ形で、名前が `At` で終わる項目に `format: date-time`、`Date` で終わる項目に `format: date` を求める。
  DB の列の名前と型の矛盾の検査（ADR-049）を API に広げる形である。
  今の `openapi/openapi.yaml` の日時は、`orderedAt` と `recordedAt` の `format: date-time` の 3 か所だけである。

SQL の側にも同じ穴がある。
PostgreSQL の `date_trunc` は、`timestamptz` をセッションの `TimeZone` で切り捨て、`date` と `timestamptz` の比較は `date` をセッションの `TimeZone` の 0 時として扱う（PostgreSQL 18 の 9.9、9.9.2）。
`CURRENT_DATE` と `now()` はトランザクションの開始時刻に基づく（9.9.5）。
こちらのセッションは UTC なので、SQL で暦日を作ると、Java の `LocalDate.now(clock)` を止めた理由（ADR-046）と同じく、日本の 0 時から 9 時に前日になる。
向こうは、SQL の中で `::date`、`date_trunc`、`AT TIME ZONE` を使わず、境界を純粋な関数で求めて引数で渡す（ADR 0043 の「プロセスと DB の既定」）。
こちらの docs には、この規則がない。
`docs/datetime/timezone-conventions.md` に「暦日と期間の境界は Java で業務タイムゾーンから求めて bind し、SQL で作らない」と書く。
jOOQ の DB の時計と暦を読む関数（`DSL.currentDate()`、`DSL.currentTimestamp()` など）は、`DateTimeConventionsArchTest` で止める。
今のプロダクションコードには、これらの呼び出しがない。

## 取り込まないもの

- **Temporal、temporal-polyfill、Effect の `Clock`、zod の `apiInstant`**：TypeScript のバックエンドの実装である。こちらの Java の型は `Instant` と `LocalDate`、時計は注入する `Clock` で、同じ役割を持つ。フロントエンドの Temporal は ADR-047 で決めてある。
- **業務タイムゾーンを定数で持つ**：向こうは、CDK、API、バッチの 3 か所で値をそろえる危険を理由に、環境変数をやめて定数にした。こちらはバッチも同じアプリで動かす（#558）ので、`application.yaml` の 1 項目を読む ADR-046:41-42 の形でずれは起きない。ADR-046 のままにする。
- **画面と通知を業務タイムゾーンで表示する**：向こうは日本の会社の経費の業務で、表示も東京にそろえる。こちらはブラウザの既定で表示し、画面の要件があるときだけ `timeZone` を渡す（ADR-047、`docs/datetime/timezone-conventions.md:70-71`）。業務の要件が違う。
- **フロントエンドのテストを America/New_York で流す**：こちらの画面はブラウザの既定のタイムゾーンで表示する仕様なので、表示がプロセスのタイムゾーンに従うのは誤りではない。`order-detail-payment.test.tsx:80-83` は期待する文字列を同じ環境の `toLocaleString` で作るので、タイムゾーンに依らない。暦日を表示する画面を作るときに、その画面のテストで決める。
- **瞬間をミリ秒 3 桁にする**：こちらは DB、API、イベントをマイクロ秒にそろえた（ADR-044）。
- **オフセット付きの瞬間の入力を 400 にする**：Jackson は `"2026-04-01T11:00:00+09:00"` を同じ瞬間の `Instant` に変換し、情報は失われない（jshell で確認）。タイムゾーンのない `"2026-04-01T11:00:00"` は `InvalidFormatException` で受けない。書き方を 1 つにする利点は、拒否の検査と試験を足す費用に見合わない。
- **ログと CSV を UTC の `Z` にそろえる**：こちらのログは ECS の `@timestamp` で、すでに `Z` で出る（全テストのログで確認）。CSV はまだない。

## ほかの Issue との関係

- #558（バッチ）：1 回のジョブの実行で現在時刻を 1 回読む形は、向こうの `runPurgeJob` と同じく、期限の基準をそろえてループを必ず終わらせる。期間の境界を Java で求めて JobParameter に渡す形は、「SQL で境界を作らない」規則と合う。
- #542（archive を保持期限で消す）：期限の基準を 1 回の実行で 1 回読む。
- #363（tenant_id と RLS）：共通カラムを書く `CommonColumns` を変える点で重なる。
- ADR-051（`PgmCdAspect`）：入口の時刻を束縛する形を選ぶなら、ADR-051 の pointcut をそのまま使う。

## 確かめたこと

- `backend/build.gradle:206` を `America/New_York` にして、`./gradlew test -PtestEnvFile=../.env.test --continue`：99 クラス、1153 件、失敗 0。`TimeZone.getDefault().getID()` が `America/New_York` になる使い捨てのテストも通った（確認の後に消した）。
- `application.yaml:81` の `SET TIME ZONE 'UTC';` を消して `DemoApplicationTest` と `ShedLockSessionTimeZoneTest`：America/New_York の JVM では 21 件のうち 1 件が落ち、UTC の JVM では全件が通った。変更は戻した。
- JDK 25.0.5 の jshell：`Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000))` を、間に軽い処理を挟んで 2 回読むと、1000 回のうち 993 回で値が違った。
- jackson-databind 3.3.0 の jshell：`LocalDate` は `"2023-09-30"`、`Instant` はマイクロ秒の `Z` で書く。`Instant` はオフセット付きを受け、タイムゾーンのない日時と暦日を受けない。既定の `LocalDate` は `"2023-09-29T15:00:00Z"` を `2023-09-29` として受け、`defaultLeniency(false)` では受けない。
- Maven Central の最新版：Spring Boot 4.2.0（こちらは 4.1.2）、jackson-databind 3.3.0（こちらと同じ）。Spring Boot 4.2.0 のリリースノートに、判断を変える日時の変更はない。

## 作業の Issue

- #584：fix(backend): ユースケースの中で現在時刻を 1 回だけ読み、業務の時刻と共通カラムの時刻をそろえる
- #585：test(backend): バックエンドのテストを America/New_York の JVM で流し、UTC の既定に頼る箇所を落とす
- #586：docs(datetime): 暦日を full-date で受け渡し、SQL で日時から暦日と境界を作らない規則を足して検査で止める

## 出典

- 向こう：`docs/adr/0043-instants-in-utc-business-calendar-in-tokyo-with-temporal.md`、`packages/shared-kernel/src/time/`、`docs/web-api/response-body.md:104-117`、`apps/api/vite.config.ts`、`apps/worker/vite.config.ts`、`apps/api/src/timezone.unit.test.ts`、`apps/worker/src/handle-notification.test.ts`、yokozi-jp/agent-software-factory#506、commit `592a25a`（yokozi-jp/agent-software-factory#737）。
- こちら：ADR-006、ADR-044、ADR-046、ADR-047、ADR-049、ADR-051、`docs/datetime/timezone-conventions.md`、`docs/database/postgresql-common-columns.md`、`.spectral.yaml`。
- Jackson JSTEP-2（Jackson 3 の既定の変更）：<https://github.com/FasterXML/jackson-future-ideas/wiki/JSTEP-2>
- Spring Boot 4.2 Release Notes：<https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.2-Release-Notes>
- PostgreSQL 18, 9.9 Date/Time Functions and Operators：<https://www.postgresql.org/docs/18/functions-datetime.html>
- PostgreSQL 18, Client Connection Defaults（`TimeZone`）：<https://www.postgresql.org/docs/18/runtime-config-client.html#GUC-TIMEZONE>
- Oracle Java SE 25, `Clock.tick`：<https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/Clock.html#tick(java.time.Clock,java.time.Duration)>
