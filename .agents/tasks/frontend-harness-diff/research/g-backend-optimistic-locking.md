# T4 の調査：楽観ロックと 409 の backend の現状

[optimistic-locking.md](../../../../docs/web-api/optimistic-locking.md) の方式が backend にどこまで実装されているかを、source、changeset、テストを読んで確かめた結果である。
[a-data-api.md](a-data-api.md) の C7 が前提にした規約と未決事項（problem type の URI の基点、文言の出し方）を、backend の側から確認する。

確認は commit `783fc15` の作業ツリーで行った。
Gradle のテストは実行していない。
`ApiContractTest` などは `@SpringBootTest` で DB と Redis の環境変数を要し、この調査の判定はテストの実行結果ではなく source の記述で決まるためである。
外部の資料は Spring Framework の Error Responses の一頁だけを読んだ。

## 確認した事実

### 規約が定める方式

規約は四点を定めている。

- **バージョン番号**：業務テーブルの共通カラム `lock_no` を使う（[optimistic-locking.md](../../../../docs/web-api/optimistic-locking.md) 16 行、[postgresql-common-columns.md](../../../../docs/database/postgresql-common-columns.md) 26 行）。
- **受け渡し**：一覧と単件の応答に含め、更新ではリクエストボディで送る（optimistic-locking.md 20 行から 22 行）。
- **競合の応答**：不一致と悲観ロックの取得失敗はどちらも 409 にする（同 29 行から 30 行）。[status-codes.md](../../../../docs/web-api/status-codes.md) 30 行は、一意制約違反も 409 に含める。
- **DELETE**：`?lockNo=6192` の query parameter で渡す（optimistic-locking.md 42 行から 46 行）。

DB 側の手順は [postgresql-concurrency-control.md](../../../../docs/database/postgresql-concurrency-control.md) 41 行から 47 行が定める。
更新の直前に `SELECT ... FOR UPDATE` で行を取り、`lock_no` を比較し、一致すれば 1 加算して更新する。
UPDATE の条件にバージョンを入れて更新件数で判定する方式は使わない。

リクエストボディのバージョン番号の項目名は、規約に書かれていない。
`lockNo` という名前が出てくるのは DELETE の例（optimistic-locking.md 45 行）だけである。
body でも `lockNo` にするのが自然だと推測できるが、確定した記述はない。

### backend の実装

楽観ロックに関わる実装は見つからなかった。

- **DB**：changeset は `001-create-event-publication-tables.yaml` と `002-tag-schema-v1.yaml` の二つだけで、どちらも Spring Modulith のイベント出版テーブルとスキーマのタグを扱う。`lock_no` 列を持つテーブルはない。共通カラムの規約自体も、イベント出版テーブルを適用対象から外している（postgresql-common-columns.md 17 行）。
- **jOOQ**：生成コードは `EventPublication` と `EventPublicationArchive` の二表だけである。`backend/gradle/database.gradle` の jOOQ 設定（107 行から 152 行）は `forcedType` で `timestamptz` を `Instant` にするだけで、`recordVersionFields` も実行時の `Settings` の `executeWithOptimisticLocking` も設定していない。`backend/src/main` に `Settings` や `DefaultConfigurationCustomizer` の記述はない。
- **共通の例外**：`backend/src/main/java` に業務例外の型はない。`ErrorResponseException` を継承した型、`lockNo` を扱う型、競合を表す型のいずれもない。
- **例外の変換**：[ApiExceptionHandler.java](../../../../backend/src/main/java/com/example/demo/error/presentation/ApiExceptionHandler.java) が持つ handler は `AuthenticationException`（38 行）、`AccessDeniedException`（51 行）、`Exception`（64 行）の三つだけである。`DuplicateKeyException`、`CannotAcquireLockException`、`OptimisticLockingFailureException` のような Spring の `DataAccessException` の派生型を個別に扱う handler はない。
- **OpenAPI**：[OpenApiConfig.java](../../../../backend/src/main/java/com/example/demo/OpenApiConfig.java) 27 行から 30 行の共通 response は 400、401、403、500 の四つで、409 の component はない。
- **テスト**：409 を扱うのは [ApiProblemDetailsTest.java](../../../../backend/src/test/java/com/example/demo/error/presentation/ApiProblemDetailsTest.java) 34 行から 43 行の一件だけである。`HttpStatus.CONFLICT` と仮の type `https://api.example.test/problems/conflict` を使い、業務固有の type なら `detail` を残すことを検証している。楽観ロックの比較や 409 の HTTP 応答を検証するテストはない。

`Exception` の handler は 500 を返してエラーログを出す（ApiExceptionHandler.java 64 行から 75 行）。
jOOQ の実行時例外は Spring Boot の自動構成で Spring の `DataAccessException` に変換されると理解しているが、この調査では確認していない。
そうだとすると、今の構成で一意制約違反やロックの取得失敗が起きれば、409 ではなく 500 になる（推測）。

### 409 の Problem Details の現状

409 に固有の処理はなく、全 status に共通の正規化だけがある。
[ApiProblemDetails.java](../../../../backend/src/main/java/com/example/demo/error/presentation/ApiProblemDetails.java) の `normalize`（44 行から 55 行）は次のように振る舞う。

- **type**：未設定なら `about:blank` にする（46 行から 47 行）。業務固有の type を設定する箇所は main のコードにない。
- **detail**：type が `about:blank` なら消し、それ以外なら残す（48 行から 50 行）。
- **title**：type に関係なく `problem.title.<status>` の message で上書きする（51 行から 54 行）。409 の message は、英語が `Conflict`、日本語が `競合が発生しました` である（`messages.properties` 7 行、`messages_ja.properties` 7 行）。

title を status 単位で上書きするので、409 の title は原因にかかわらず一つの文言になる。
Spring の `ResponseEntityExceptionHandler` は、`ErrorResponseException` の title を例外 class ごとの message code（`problemDetail.title.<class 名>`）で `MessageSource` から解決できる（[Spring Framework 7.0.9 の Error Responses](https://docs.spring.io/spring-framework/reference/7.0.9/web/webmvc/mvc-ann-rest-exceptions.html)）。
しかし、その後に `createResponseEntity`（ApiExceptionHandler.java 78 行から 89 行）が `normalize` を呼ぶので、class ごとに解決した title も status の文言で上書きされる（source からの帰結）。
ApiProblemDetailsTest.java の 409 のテストは title を検証していない。

### problem type の URI の基点

URI の基点は決まっていない。
ADR-013 は Accepted（12 行）で、業務固有の問題を初めて公開するときは管理下の安定した HTTPS URI を type に使うと定める（49 行）。
一方で、同じ ADR の Neutral（119 行）が「カスタム problem type の URI 基点は、公開 API の管理ドメインが決まるまで確定しない」と書いている。
docs、ADR、main のコードのどこにも、基点の URI や楽観ロックの type の名前はない。
テストの `https://api.example.test/problems/conflict` は仮の値である。

### 409 の原因ごとの type

悲観ロックの取得失敗、一意制約違反、楽観ロックの競合で type を分ける規約も実装もない。
status-codes.md 30 行は三つを 409 にまとめているが、type には触れていない。
ADR-013 は client が type で問題種別を識別すると定める（45 行）だけで、409 の内訳を決めていない。
入力検証の 400 には業務固有の type と `errors` 拡張を使うと決めている（54 行）が、これも未実装である。

### ADR-016 と 409 の文言

ADR-016 は Proposed（12 行）であり、Accepted ではない。
ただし、Problem Details の文言に関わる部分（`messages*.properties`、`LocaleSupport`、`Content-Language` と `Vary`）はすでに実装され、`ApiContractTest` で検証されている。

409 の文言をどこで出すかに関わる記述は三つある。

- `about:blank` の HTTP エラーは title だけを翻訳し、framework の detail は公開しない（44 行）。
- 業務固有の安全な detail と検証エラーの説明は翻訳する（45 行）。
- SPA は既知の type をローカルの message key へ写像し、未知の type は一般エラーとして扱い、サーバーの detail をそのまま HTML へ入れない（63 行から 64 行）。

API client が無いので、この写像の実装は最初の API client と同時に行うと決めている（65 行）。
業務固有の problem type は、最初の業務 API と管理ドメインが確定した時点で追加すると書いている（87 行）。

## 判定

楽観ロックの仕組みは「規約だけ」に当たる。

規約は API（optimistic-locking.md、status-codes.md）と DB（postgresql-common-columns.md、postgresql-concurrency-control.md）の両方にそろっている。
しかし、`lock_no` 列、jOOQ の設定、`lock_no` を比較する部品、競合を表す例外、409 への変換、409 の OpenAPI component、テストのどれもない。

「共通部品まで」に当たらない理由は、409 で使える共通部品が Problem Details の汎用の正規化と、`problem.title.409` の message 一行だけだからである。
これは全 status に共通の部品で、楽観ロックのための部品ではない。
しかも title を status 単位で上書きするので、原因ごとに type を分けても文言は分けられない。

backend で 409 を規約どおりに返すには、少なくとも次の決定と実装が残っている。

1. problem type の URI の基点（ADR-013 の未決事項）。
2. 楽観ロックの競合、悲観ロックの取得失敗、一意制約違反の type の名前と、分けるかどうか。
3. 競合を表す例外の型と、`DataAccessException` の派生型を 409 へ変換する handler。
4. 業務固有の type の title を、type ごとにするか status ごとのままにするか（`normalize` の振る舞い）。
5. リクエストボディのバージョン番号の項目名。
6. OpenAPI の 409 の共通 response と、MockMvc の契約テスト。

jOOQ には `executeWithOptimisticLocking` と `recordVersionFields` による楽観ロックの機能がある。
しかし、postgresql-concurrency-control.md 46 行は更新件数で判定する方式を禁じているので、この機能を使えるかどうかは規約との照合が要る。
この照合は今回の範囲外として、jOOQ の資料は読んでいない。

## frontend の 409 の規約に与える影響

### type で分岐する規約は、backend の type が決まるまで書き切れない

a-data-api.md の C7 の推奨案（backend が業務固有の type を割り当て、frontend はその type で分岐する）は、ADR-013 と ADR-016 に合う。
しかし、分岐に使う type の URI は一つも決まっていない。
frontend の規約に今書けるのは「409 は type で分岐する」「未知の type は一般エラーにする」という形までで、type の値は書けない。
type の値を frontend で先に仮置きすると、backend の命名が決まったときに frontend だけが別の値を持つおそれがある。

### 今の backend が返す 409 は原因を区別できない

backend が今 409 を返すとすれば、type は `about:blank`、detail は無く、title は `競合が発生しました` になる（normalize の 46 行から 54 行）。
一意制約違反とロックの取得失敗は、409 にならず 500 になる可能性がある（前節の推測）。
したがって、frontend の規約で原因ごとの次の行動（再取得して選ばせる、入力欄のエラーにする、時間をおいて再試行する）を書いても、backend の変更が入るまでは実際の応答で分岐できない。
`about:blank` の 409 を受けたときの扱い（一般の競合として再取得を促すか、一般エラーにするか）は、frontend の規約で別に決める必要がある。

### 文言は frontend の catalog で出す前提になる

ADR-016 の 63 行は、既知の type を SPA のローカルの message key へ写像すると決めている。
backend の title は status 単位の一文で、原因ごとの文言を持てない（normalize の 51 行から 54 行）。
この二つから、原因ごとの通知文言は frontend の catalog で出すことになる。
backend の title を表示に使う案は、normalize を type ごとの title に変えない限り、全 409 で同じ文言になる。

ADR-016 は Proposed なので、この前提は ADR-016 が Accepted になることに依存する。
a-data-api.md が論点に挙げた「backend の title で出すか、frontend の文言で出すか」は、ADR-016 の 63 行がすでに frontend の文言の側を選んでいる。
新たに決めるのは、ADR-016 を Accepted にするかどうかである。

### 規約の書き分け

frontend の 409 の規約に書ける内容と、backend の決定を待つ内容は次のように分かれる。

- **今書ける**：非 2xx を例外にして status と Problem Details を持たせる（C2 の mutator）。409 で同じ要求を自動で再送しない。入力を捨てない。最新の `lockNo` を黙って差し込んで再送しない。DELETE の `lockNo` は query parameter で送る。
- **backend の決定を待つ**：分岐に使う type の値。原因ごとの画面の振る舞いの対応表。body の `lockNo` の項目名。
- **frontend で決める**：`about:blank` の 409 の扱い。type ごとの catalog の key の命名。

backend の楽観ロックは業務 API と同時に作られる見込みなので、frontend の規約にも同じ時期に type の値を加えることになる。
ADR の更新を実装とあわせて行う方針（利用者の回答）とも、この順序は合う。
