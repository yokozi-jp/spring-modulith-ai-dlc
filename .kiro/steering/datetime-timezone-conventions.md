---
inclusion: always
name: datetime-timezone-conventions
description: 日時を扱うときの基本規約。バックエンドの絶対時刻をUTCへ統一し、フロントエンドで表示用タイムゾーンへ変換する方針を定める。日時の保存、API、表示、テストを変更するときに使用する。
---

# 日時とタイムゾーンの規約

## 基本方針

バックエンドの実行環境、絶対時刻、DBセッション、ログはUTCへ統一する。
APIは絶対時刻をUTCで返し、フロントエンドが画面の要件に応じたタイムゾーンへ変換して表示する。
表示用タイムゾーンをバックエンドの保存形式へ混ぜない。

## バックエンド

- イベント発生日時、作成日時、更新日時には `Instant` を使う。
- 現在時刻を使うクラスは `Clock` をコンストラクタ引数で受け取り、`Instant.now(clock)` などの `now(Clock)` オーバーロードを使う。
- システム時刻を供給する `Clock` は `com.example.demo.DemoApplication.clock()` の `@Bean` メソッドだけで生成し、このメソッドが `Clock.systemUTC()` を返す。
- JVMには `-Duser.timezone=UTC` を設定する。
- `ZoneId.systemDefault()` や `LocalDateTime.now()` に業務処理を依存させない。
- APIの絶対時刻は RFC 3339形式のUTC表現で返し、UTCオフセットには常に `Z` を使う。`+09:00` のようなオフセット表記では返さない。これはRFC 3339の要件ではなく（RFC 3339は `Z` もオフセットも許容する）、このAPIが加える制約である。
- Jacksonの `spring.jackson.time-zone: UTC` を維持する。ただしAPI日時表現の正しさを Jackson 設定だけに依存させず、シリアライズ結果を契約テストで検証する。
- PostgreSQLの絶対時刻には `TIMESTAMP WITH TIME ZONE` を使う。
- `TIMESTAMP WITH TIME ZONE` は入力を絶対時刻（UTC）へ変換して保持し、元のタイムゾーンIDは保存しない。地域タイムゾーン自体が業務データのときは、IANA タイムゾーン ID を別カラムに保存する。
- PostgreSQLのアプリケーション接続とログはUTCへ固定する。これは主に表示と、タイムゾーンなし入力の解釈に効く防御策であり、`TIMESTAMP WITH TIME ZONE` の保存がUTCになること自体はセッション設定に依存しない。

基準となる設定は次を参照する。

- Spring Boot：#[[file:backend/src/main/resources/application.yaml]]
- JVM：#[[file:backend/Dockerfile]]
- PostgreSQL：#[[file:docker/compose.yml]]
- DBマイグレーション：#[[file:backend/src/main/resources/db/changelog/changesets/001-create-event-publication-tables.yaml]]

## フロントエンド

APIから受け取ったUTCの日時は `Date` として解析し、`Intl.DateTimeFormat` で表示する。
画面で特定地域の時刻を表示する場合は `timeZone` を明示する。
利用者のブラウザ設定で表示する場合だけ `timeZone` を省略する。
表示用に整形した文字列を計算やAPI送信へ再利用しない。

```typescript
const occurredAt = new Date(response.occurredAt);

const label = new Intl.DateTimeFormat("ja-JP", {
  dateStyle: "medium",
  timeStyle: "medium",
  timeZone: "Asia/Tokyo",
}).format(occurredAt);
```

## UTCへ変換しない値

日付だけの値と時刻だけの値は絶対時刻ではないため、UTCへ変換しない。
Javaでは `LocalDate` または `LocalTime`、PostgreSQLでは `DATE` または `TIME` を使う。
原則として `TIME WITH TIME ZONE` は使わない。PostgreSQL自身も有用性に疑問があるとし、他の日時型の組み合わせを推奨している。

将来の一度きりの予定で、地域の時計が指す時刻そのものに意味がある場合は、`LocalDateTime` と IANA タイムゾーン ID を組で保存する。
固定オフセットでは夏時間や法改正を表せないため、`+09:00` ではなく `Asia/Tokyo` や `America/New_York` のような地域 ID を使う。
この `LocalDateTime`（タイムゾーンを持たない日付と時刻）は、PostgreSQLでは `TIMESTAMP WITHOUT TIME ZONE` に保存する。この型は入力をUTCへ変換せず、日付と時刻をそのまま保持する。
逆に、絶対時刻には `TIMESTAMP WITHOUT TIME ZONE` を使わない。ゾーン情報を持たず、解釈が読み手のセッションタイムゾーンに委ねられるため、絶対時刻には `TIMESTAMP WITH TIME ZONE` を使う。

繰り返す営業時間や定期実行は、単一の日時ではなく繰り返しルールなので、`LocalDateTime` ではなく `DayOfWeek`（または繰り返し規則）と `LocalTime`、`ZoneId` の組でモデル化する。

地域時刻を `Instant` へ解決するときは、DSTによる存在しない時刻（spring forward の gap）と二重に存在する時刻（fall back の overlap）の扱いを、業務仕様として明示する。
Javaの `LocalDateTime.atZone()` は、gap では時刻を後ろへずらし、overlap では早い方のオフセットを選ぶため、何も決めなければライブラリのデフォルトがそのまま業務仕様になる。

## 精度

`Instant`、PostgreSQLの `timestamp`/`timestamptz`、JavaScriptの `Date` は、表現できる精度が異なる。
`Instant` はナノ秒、PostgreSQL はマイクロ秒、`Date` はミリ秒までしか保持しない。
`Instant → PostgreSQL → API → Date` と流すと各層で精度が変わるため、DBとAPIが保持する精度を要件として明示し、それを超える精度へ業務処理を依存させない。

## テスト

- 通常のバックエンドテストはUTCのJVMで一回だけ実行する。
- 現在時刻を使うテストには `Clock.fixed(...)` を渡す。
- JSONの絶対時刻は `Z` 付き文字列との完全一致で検証する。
- DB統合テストは `SHOW TIME ZONE` と `Instant` の保存往復を検証する。
- テストデータの `Instant` は、DBとAPIが保持する精度（PostgreSQLはマイクロ秒）に合わせ、ナノ秒に依存させない。ナノ秒を含めると、保存往復や文字列の完全一致が丸めで失敗する。
- 地域時刻を扱う機能を追加したときは、対象の `ZoneId` に加え、DSTの gap と overlap を入力した境界値テストを追加する。

## 自動強制

規約のうち機械的に判定できる項目は、レビュー任せにせずビルドとテストで強制する。

- Error Prone（`build.gradle` の errorprone 設定、全 JavaCompile に適用）
  - `JavaTimeDefaultTimeZone` を error に昇格し、既定タイムゾーンに依存する `LocalDate.now()` や `LocalDateTime.now()` などをコンパイル時に弾く。
  - `JavaUtilDate` を error に昇格し、`java.util.Date` の使用をコンパイル時に弾く。
- ArchUnit（`DateTimeConventionsArchTest`、プロダクションコードを対象、生成コードは除外）
  - レガシー日時型（`java.util.Date`、`java.sql.Date`/`Time`/`Timestamp`、`Calendar`、`TimeZone`、`SimpleDateFormat` など）への依存を遮断する。
  - `Clock` を迂回する時刻取得（引数なしの `now()`、`System.currentTimeMillis()`、`ZoneId.systemDefault()`）を遮断する。
  - システム時刻を直接供給する `Clock` ファクトリ（`systemUTC()`、`systemDefaultZone()`、`system(ZoneId)`、`tickMillis(ZoneId)`、`tickSeconds(ZoneId)`、`tickMinutes(ZoneId)`）は、`com.example.demo.DemoApplication.clock()` 以外で遮断する。違反したクラスは `Clock` をコンストラクタ引数で受け取り、`Instant.now(clock)` などへ置き換える。
- JVM のタイムゾーンは `-Duser.timezone=UTC` を `test` タスクと `bootRun`、コンテナの `JAVA_TOOL_OPTIONS` で固定する。
- Jackson の絶対時刻表現（`Z` 付き）と、DBセッションのUTC固定・`Instant` の保存往復は、契約テストと統合テストで検証する。

型の使い分け（`timestamptz` か `timestamp` か、`Instant` か `LocalDateTime`+`ZoneId` か）は、その値が絶対時刻かどうかという意味の判断を伴うため機械判定になじまない。ここはレビューで担保する。

## 出典

- IETF RFC 3339: <https://datatracker.ietf.org/doc/html/rfc3339>
- OpenJDK 25, `Instant`: <https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/java.base/share/classes/java/time/Instant.java>
- Oracle Java SE 25, `LocalDateTime`（`atZone` の gap/overlap 解決）: <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/LocalDateTime.html>
- PostgreSQL 18, Date/Time Types: <https://www.postgresql.org/docs/18/datatype-datetime.html>
- MDN, `Date`（ミリ秒精度）: <https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Date>
- Spring Boot 4.1.1, Common Application Properties: <https://docs.spring.io/spring-boot/appendix/application-properties/index.html>
