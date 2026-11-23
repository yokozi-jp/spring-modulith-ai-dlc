# 外部システムの応答を自分の型に写す層を比べる（#341 の調査）

この文書は #341 の調査の記録で、merge しない。
結論は #341 のコメントと同じで、作業は #659 と #660 で行う。
向こうは `yokozi-jp/agent-software-factory`（`origin/main`、commit `3ba2640`）、こちらは `yokozi-jp/spring-modulith-ai-dlc`（`origin/main`、commit `a24fcf3`）である。
調べた日は 2026-11-23 である。

## 結論

一部採用とする。

- 同じ決定：外部システムの応答を業務の型に写す層は置く。こちらの `PaymentGatewayClient` は、すでにその層になっている。
- 採用：外部の型（SDK の型、OpenAPI から生成した型、HTTP の型）を閉じ込める境界を、構造で止める。向こうの lint `driver-in-module` と、domain の外部 import をすべて止める規則に当たるもので、こちらでは `domain.model` の依存を ArchUnit の許可の一覧にする（#659）。
- 採用：決済代行の応答の項目の型が契約と違うときの扱いを、偶然でなく決めて試験で固定する（#660）。
- 取り込まない：生成した型をそのまま業務の型として公開する形、応答を検査しない `ky` の `json<T>()`、決済代行の OpenAPI の契約ファイル、OpenAPI から Java の型や Client を生成する道具。

理由は 3 つある。

1. 向こうの `hr-system` は、openapi-typescript が作った型（`components["schemas"]["Employee"]`）を、そのまま `Employee` として公開し、`ky` の `json<Employee>()` が返す値に当てている（`employee.ts:4`、`client.ts:33-38`）。
   写す層は package の中になく、ADR 0054 の M5（Port の公開の型にドライバの型を使わない）は、人がレビューで見る規則である。
   しかも、`hr-system` を呼ぶモジュールは、向こうにまだ 1 つもない。
   こちらは、手書きの内向きの record（`ChargeReply`）で応答を受け、null を許す項目を検査してから `domain.model` の型へ写す。
   写す層の厚みは、こちらが向こうより上である。
2. 写す層の置き場は、こちらでは ArchUnit が一部止める（interface は `domain.model`、実装は `infrastructure.client`、`infrastructure` は同じモジュールの `domain.model` だけに依存する）。
   ただし `domain.model` の依存の規則は、Spring、jOOQ、JPA、Jackson の拒否の一覧で、SDK や生成した型は止まらない。
   実際に、SDK の代わりに resilience4j の型を戻り値にした interface を `domain.model` と `application` に置いても、ArchUnit の規則はすべて通った（後の「確かめたこと」）。
   docs の「依存してよい型」は許可の一覧である。#571 が SES v2 の SDK を `infrastructure.client` に入れる前に、規則を docs と同じ形にする。
3. 決済代行の契約は、ADR-072 が「実際の契約が決まるまでは、このリポジトリが決めた契約」とする。
   契約の実体は、WireMock のスタブと Client の結合テストで、Client とスタブの食い違いは結合テストが落ちて見つかる。
   向こうの `openapi.yaml` と、スタブを契約に照らす unit test の目的は、こちらでは結合テストが別の形で満たしている。
   一方、本物の応答の項目の型が違うときの扱いは決まっていなかった。数値の `chargeId` が `PAID` になる（後の「確かめたこと」）。

## 比べたもの

### 向こう

- `packages/hr-system/openapi.yaml`、`src/domain/schema.gen.ts`、`src/domain/employee.ts`、`src/infrastructure/client.ts`、`src/infrastructure/client.test.ts`、`src/infrastructure/fake.unit.test.ts`、`package.json`
- `docs/adr/0052-hr-system-package-with-openapi-ky-and-wiremock.md`、`docs/adr/0054-drivers-with-domain-and-infrastructure-layers.md`、`docs/adr/0023-ky-for-external-http-and-sdk-retries-for-aws.md`
- `docs/domain-model/package-layout.md`（M5、D7）、`docs/tooling/hr-system-fake.md`、`docs/architecture-principles/thin-wrapper.md`
- `lint-restrictions.json`（`driver-in-module`）、`drivers.json`
- `packages/approval/src/application/mail-sender.ts`（SES の Port）
- Issue #562（型の生成と古さの検査）

### こちら

- `backend/src/main/java/com/example/demo/payment/infrastructure/client/PaymentGatewayClient.java`、`payment/domain/model/PaymentGateway.java`、`ChargeOutcome`、`GatewayPaymentCode`
- `backend/src/test/java/.../PaymentGatewayClientTest.java`、`PaymentGatewayClientIntegrationTest.java`、`architecture/PackageByFeatureOnionArchitectureTest.java`、`ClassRoleArchTest.java`、`ClassRoleFixtureRows.java`
- `docs/backend/class-roles/external-client.md`、`external-system-interface.md`、`value-object.md`、`aggregate.md`、`command-handler.md`、`docs/backend/layers.md`、`architecture-tests.md`
- `docs/adr/ADR-002`、`ADR-019`、`ADR-050`、`ADR-072`、`ADR-052`、`ADR-024`
- `tenancy/presentation/web/SignInOidcUserService.java`、`AppOidcUser.java`、`OidcDiscoveryLocationConfig.java`（IdP）
- Issue #571（SES v2 の Client）、`.agents/skills/formal-verify-order-payment/SKILL.md`

## 項目ごとの比較

| 論点                             | 向こう                                                                                      | こちら                                                                                                                                                                      | 判定                                 |
| -------------------------------- | ------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------ |
| 外部の契約の正                   | `openapi.yaml`（OpenAPI 3.1）。向こうのリポジトリが持つ人事システムの契約                   | 決済代行の契約は ADR-072 の文と WireMock のスタブ。契約のファイルはない                                                                                                     | 取り込まない（後述）                 |
| 応答の型                         | 生成した型（`schema.gen.ts`）を、そのまま `Employee` として公開する                         | 手書きの private record `ChargeReply`。項目は `@Nullable`                                                                                                                   | 同じ決定にならない。こちらの形を残す |
| 応答を業務の型へ写す             | しない。生成した型が domain の型を兼ねる。M5 は人が見る                                     | `toOutcome` が検査して `ChargeOutcome`、`GatewayPaymentCode` に写す                                                                                                         | こちらの形を残す                     |
| 応答の実行時の検査               | なし。`ky` の `json<T>()` は型引数を渡すだけ                                                | 状態のコード、本文、`chargeId`、`status` を検査し、合わなければ契約の不備の結果にする                                                                                       | こちらの形を残す                     |
| 外部の型の置き場の機械検査       | lint。ドライバは `infrastructure` だけが import でき、`domain` は外部 import をすべて止める | ArchUnit。interface は `domain.model`、実装は `infrastructure.client`、`infrastructure` は同じモジュールの `domain.model` だけに依存。`domain.model` の外部依存は拒否の一覧 | 一部採用（許可の一覧にする。#659）   |
| 外部システムの失敗の分け方       | 404 は `null`、止まったときは `ky` のエラーを投げる                                         | 一時障害、業務上の拒否、資格情報の不備、契約の不備の 4 つ（ADR-072）                                                                                                        | こちらの形を残す                     |
| 契約の型とスタブの照合           | スタブの本文を ajv で OpenAPI の schema に当てる unit test                                  | スタブは Client の結合テストが読む。食い違うとテストが落ちる                                                                                                                | 別の形で同じ目的を満たす             |
| 生成型の古さの検査               | なし（Issue #562 が open）。生成は `pnpm dlx` で lockfile の外                              | 生成する型がない                                                                                                                                                            | 該当しない                           |
| SDK を使う外部システム（メール） | Port は `application`、SDK は apps の入口の `Layer` で渡す（`mail-sender.ts`）              | #571 で `infrastructure.client` に SES v2 の SDK を入れる予定                                                                                                               | docs に規則を足す（#659）            |
| IdP                              | 読んでいない（Better Auth の扱いは調べていない）                                            | Spring Security の `OidcUser` から `iss` と `sub` だけを読み、検査して `SignInUserCommand` に写す                                                                           | こちらは実装済み                     |

## 向こうの実際

### 契約から型まで

`openapi.yaml` が人事システムの契約の正で、操作は 2 つ（メールアドレスで社員を引く、社員番号で上長を引く）、schema は `Employee` の 1 つである（`additionalProperties: false`、4 項目が必須）。
`pnpm --filter … generate` が `pnpm dlx` で openapi-typescript 7.13.0 と TypeScript 5.9.3 を一時的に入れて `src/domain/schema.gen.ts` を作る。
この `dlx` は lockfile の外にあり、生成した型が `openapi.yaml` と合うことを CI は確かめない（ADR 0052 の Consequences、Issue #562 は open）。

### 生成した型がそのまま業務の型になる

`src/domain/employee.ts:4` は `export type Employee = components["schemas"]["Employee"];` である。
`HrSystemClient` の戻り値も `Employee` で、`src/index.ts` が `.` の入口としてこの型を公開する。
`active` と `hasAccountingRole` のように、呼ぶ側が業務の判断に使う項目も、人事システムの schema のままである。
契約が変わると、公開の型が変わる。

### 応答を検査しない

`src/infrastructure/client.ts:33-38` は `http.get<Employee>(path, …).json()` で応答を読む。
`ky` の README は、型引数を `.json()` の戻り値の型へ渡すだけだと書く。
同じ README は、Standard Schema（Zod など）で応答を検査する `.json(schema)` と `SchemaValidationError` も載せているが、`client.ts` は使っていない。
`zod` は `hr-system` の `devDependencies` にあり、`fake.unit.test.ts` のスタブの検査にだけ使う。
本物の応答が契約と違っても、`Employee` として呼ぶ側へ渡る。

照合の検査は、スタブの側にある。
`fake.unit.test.ts` が、全スタブの path と状態のコードを OpenAPI に当て、200 の本文を `Employee` の schema に ajv で当てる。
本物の人事システムの応答は照合しない。

### 写す層は規則としてだけある

ADR 0054 は、モジュールの `application` と `http` は Port に依存し、`infrastructure` がドライバを `Effect.tryPromise` で包んで Port を実装すると決める。
`package-layout.md` の M5 は、Port が業務の言葉で書かれ、ドライバの型（`Employee` のような外部システムの形）を Port の公開の型に使っていないかを、レビューで見るとする。
機械で止めるのは、ドライバの import の位置である。
`lint-restrictions.json` の `driver-in-module` が、ドライバの package を `application` と `http` から import することを止め、`withoutLayers.domain` が `domain` の外部 import をすべて止める。

`git grep` で `hr-system` を探すと、`packages/hr-system` の外では、docs、設定ファイル（`drivers.json`、`lint-restrictions.json`、`vite.config.ts` など）、lint の fixture、検査のスクリプトだけに当たった。
`packages/claim`、`approval`、`closing` と `apps/` に、`hr-system` を import するコードはない。
そのため、M5 に従う写す層のコードは、向こうにまだない。

### 薄いラッパーへの見方

`docs/architecture-principles/thin-wrapper.md` は、腐敗防止層の名で外部ライブラリに薄いラッパーを被せることを、費用に見合わないことが多いとする。
ただし「不安定な外部依存で、防波堤が真に機能しそうな場合」は検討の余地があるとし、ラッパーを入れるなら、規約、lint による強制、既存コードの是正を揃えるとする。
こちらの `PaymentGateway` は、ライブラリの薄いラッパーでなく、業務の語彙で結果を分類する interface である。
この文書の対象外で、同じ決定の側にある。

## こちらの実際

### 決済代行：写す層がすでにある

- `PaymentGateway`（`domain.model`）は `charge(OrderId, Money)` を持ち、`ChargeOutcome` を返す。引数と戻り値は値オブジェクトで、HTTP や製品の型は出ない。
- `PaymentGatewayClient`（`infrastructure.client`、package-private）は、`RestClient` で呼び、`ChargeBody` と `ChargeReply` を private の record として持つ（`PaymentGatewayClient.java:188`、`:196`）。`ChargeReply` の項目は `@Nullable` である。
- `toOutcome`（`:143`）は、状態のコードが 201 であること、本文があること、`chargeId` があり `GatewayPaymentCode`（空白だけでなく 64 文字以下）の条件を満たすこと、`status` が `SUCCEEDED` か `DECLINED` であることを確かめ、合わなければ `contractViolation` で `ChargeOutcome.failed()` を返す。
- 4xx は `contractError`（`:127`）が、401、403、429 を例外のまま投げ、ほかを契約の不備にする。
- 置き場は ArchUnit が止める：`externalSystemImplementationsAreClients`（実装は `infrastructure.client` の `<ExternalSystem>Client`）、`domainInterfacesAreImplementedInInfrastructure`、`infrastructureDependsOnlyOnDomainModel`（`application`、`domain.service`、モジュールルートに依存しない）、`domainModelDoesNotDependOnFrameworks`（Spring、jOOQ、JPA、Jackson）、`requestsAndResponsesArePresentationWebRecords`（名前を `Request`、`Response` で終えない）。

契約の文書は、ADR-072（要求、成功、拒否、`chargeId` は空でない 64 文字以下の文字列）と `external-client.md` の「契約に合わない応答」の列挙である。
OpenAPI のファイルも、OpenAPI から生成した型もない。
こちらの OpenAPI は、自分の API の契約（`openapi/openapi.yaml`、ADR-052）で、Orval でフロントエンドの型を作る（ADR-024）。外部システムの契約ではない。

### IdP

`SignInOidcUserService` は、Spring Security の `OidcUserService` が返す `OidcUser` から、`iss` と `sub` だけを読む。
空か 255 文字を超えると `OAuth2AuthenticationException` にし、通れば `SignInUserCommand(issuer, subject)` に写して `SignInUserCommandHandler` を呼ぶ。
返す `AppOidcUser` は、IdP の ID トークンを持つが、名前と `AppUserPrincipal.userId()` はアプリの利用者の ID である。
`backend/src/main` で `OidcUser`、`getClaim`、`IdTokenClaimNames` を使うのは、この 2 つのクラスだけだった。
IdP の claim は、`presentation.web` の入口で業務の型へ写り、それより内側に出ない。

`OidcDiscoveryLocationConfig` は、discovery の JSON を `Map` で受け、Spring Security の `ClientRegistrations.fromOidcConfiguration` に渡す。応答の型を自分で持たない。

### SES

まだない。#571 が `SesV2Client` を `infrastructure.client` に入れる。
SDK の型（`SendEmailRequest` と、`MessageRejectedException` などの例外）は、Client の中で使い、domain の型（宛先、件名、本文、結果）へ写す設計である（#571 の本文）。
`external-client.md` の「依存してよい型」に SDK の型は載っていない。この節は #571 の作業の前に直す必要がある。

### 型の置き場の規則が許可の一覧になっていない

`value-object.md:57`、`aggregate.md:76`、`external-system-interface.md`、`command-handler.md:79` は、`domain.model` と `application` が依存してよい型を許可の一覧で書く。
ArchUnit の `domainModelDoesNotDependOnFrameworks` は、`org.springframework..`、`org.jooq..`、`com.example.demo.jooq..`、`jakarta.persistence..`、`com.fasterxml.jackson..`、`tools.jackson..` だけを止める。`docs/backend/layers.md:27` も同じ一覧である。
`application` には外部の型を止める規則がない。

## 確かめたこと

### 1. 数値と真偽値の `chargeId` が `PAID` になる

`PaymentGatewayClientIntegrationTest`（Boot の自動構成の `RestClient.Builder` と、`spring.jackson.default-leniency: false` を通る側）の契約の不備の表に、2 行を足して流した。

| 応答の本文                                   | 結果                                 |
| -------------------------------------------- | ------------------------------------ |
| `201 {"chargeId":123,"status":"SUCCEEDED"}`  | `expected: "FAILED" but was: "PAID"` |
| `201 {"chargeId":true,"status":"SUCCEEDED"}` | `expected: "FAILED" but was: "PAID"` |

`PaymentGatewayClientTest`（`RestClient.builder()` で作る単体の側）でも、同じ 2 つが `PAID` になった。
次の応答は、単体の側で契約の不備になった：`chargeId` が配列、オブジェクト、`null`、`status` が数値、小文字の `succeeded`、本文が `[]`、`null`、文字列だけ。

原因は Jackson 3.3.0 の `String` への変換である。
`spring.jackson.mapper.allow-coercion-of-scalars: false` を `application.yaml` に足して同じ表を流したが、結果は同じで、2 行は `PAID` のままだった。
`CoercionConfigs.findCoercion`（3.3.0 のソース）は、`MapperFeature.ALLOW_COERCION_OF_SCALARS` が効く対象を `_isScalarType(targetType)` の型に限る。`String` の論理型は `Textual` で、この対象に入らない。
`JsonMapper.rebuild().withCoercionConfig(String.class, …)` で `CoercionInputShape.Integer`、`Float`、`Boolean` を `Fail` にすると、`123`、`1.5`、`true` が `InvalidFormatException` になり、文字列は読めた（Jackson の単体で確かめた）。
Spring の `RestClient` は、本文の読み取りの失敗を `HttpMessageNotReadableException` に包むので、今の `catch` が契約の不備にする（応答が `{"chargeId":` の形で壊れたときの既存の試験が確かめている）。

次の 2 つも確かめた。

- 未知の項目（`"extra":1`、入れ子の `"nested":{"a":[1]}`）は無視され、`PAID` になる。
- 同じ項目が 2 つ（`"chargeId":"ch_1","chargeId":"ch_2"`）あると、後の値（`ch_2`）が採られる。

どちらが正しいかは、自明でない。
厳密にして契約の不備にすると、決済代行が `SUCCEEDED` を返して代金を取ったのに、`FAILED` と記録しうる。
`formal-verify-order-payment` の `RecordMatchesGateway` は、この害（代金を取ったのに失敗と記録し、客に二度目の支払いを求める）を避ける約束である。
一方、寛容のままなら、識別子 `"123"` で `PAID` と記録できる。
ADR-072 の未決事項 3（契約の分類）は、実際の決済代行の契約を選ぶときに決めるとする。
その前の暫定の挙動を、偶然でなく決めて固定するのが #660 である。

試験の後、`application.yaml` と試験の変更はすべて戻した（作業の worktree で、commit していない）。

### 2. SDK や生成型の代わりの型が `domain.model` と `application` に入っても、ArchUnit が通る

`payment.domain.model` と `payment.application` に、resilience4j の型（`CircuitBreaker`、`Retry`）を戻り値にした public interface を置き、`PackageByFeatureOnionArchitectureTest`（18 件）と `ClassRoleArchTest`（24 件）を流した。失敗は 0 件だった。
対照として、`payment.domain.model` に `org.springframework.http.ResponseEntity` を戻り値にした interface を足すと、`domainModelDoesNotDependOnFrameworks` だけが落ちた。
どちらの interface も、確かめた後に消した。

今の `domain.model` と `application` が使う外部の型を、`jdeps -verbose:class` で数えた。

- `domain.model`（37 クラス）：`java..`、`org.jspecify.annotations`、`com.example.demo.shared.concurrency`、`shared.failure`、`shared.tenant`。
- `application`（36 クラス）：`org.springframework.stereotype`、`org.springframework.transaction.annotation`、`org.springframework.modulith.events`、`org.springframework.context`、`org.slf4j`、`org.jspecify.annotations`、`shared.concurrency`、`shared.events`、`shared.failure`、`shared.tenant`。

`domain.model` を許可の一覧にしても、今は違反が出ない見込みである。ただし Lombok のように実行時に残らない注釈は、`jdeps` に出ないので、作業の中で確かめる。

### 3. OpenAPI から Java を生成する道具と、Spring の HTTP Service

- OpenAPI Generator の `spring` ジェネレータは、`library` に `spring-http-interface`（Spring の HTTP interface。説明は「testing」）を持ち、`useSpringBoot4`、`useJackson3`、`useRecords`、`useJspecify`、`enumUnknownDefaultCase` の設定がある（公式の generators/spring のページ）。最新は 7.27.0（Maven Central）である。動かしてはいない。
- Spring Boot の公式の参照は、`@HttpExchange` の interface を `@ImportHttpServices` で取り込み、グループごとに URL とタイムアウトを設定する形を挙げる。動かしてはいない。
- どちらも、`PaymentGatewayClient` の仕事の中心（応答の 4 つの分類、`chargeId` の検査、冪等性キー、トランザクションの中の最悪の時間）を代わりに持たない。操作が `POST /v1/charges` の 1 つで、生成する型は項目が 2 つの record で足りるので、道具を入れる理由がない。

### 4. 版

こちらは Spring Boot 4.1.2、Spring Framework 7.0.10、Jackson 3.3.0 である。
最新は Spring Boot 4.2.0、Spring Framework 7.1.0、Jackson 3.3.0（Maven Central）である。

- Spring Boot 4.2 のリリースノートに、`RestClient` の応答の検査や JSON の変換に関する変更はない（全文を読んだ。`RestTemplate` の非推奨が載る）。
- Spring Framework 7.1 のリリースノート（プレビュー）に、`RestClient.Builder#configureMessageConverters` の変更が載る。7.0 は、これを呼ぶと既定のコンバーターの自動検出を止める。7.1 は、既定から始めて 1 つだけ差し替えられる。#660 で `RestClient.Builder` に Client だけの JSON の変換を渡すなら、この違いを踏まえて書く。
- `ky` の README に、Standard Schema で応答を検査する `.json(schema)` がある。向こうの `hr-system` は使っていない。

## 取り込むもの

### 外部の型を閉じ込める境界を、許可の一覧で止める（#659）

向こうは、ドライバの型（外部の道具）を import できる層を `infrastructure` だけにし、`domain` は外部 import をすべて止める。どちらも lint で止める。
こちらは同じ考えを、Spring、jOOQ、JPA、Jackson に対してだけ ArchUnit で止める。
SDK や生成した型は、止める一覧に載せるたびに追う形では、次の外部システム（#571 の SES、実際の決済代行の SDK）ごとに漏れる。
`domain.model` の依存を、docs の「依存してよい型」と同じ許可の一覧にすれば、一覧を足さずに、新しい外部の型が `domain.model` に入ることを止められる。
`application` にも同じ形を使えるかは、作業の中で調べる。

あわせて、`external-client.md` に、SDK と生成した型を使うときの規則を足す。Client の中に閉じ、引数と戻り値に出さず、domain の型へ写し、必須の項目も null や型違いを許す前提で検査する。
向こうの `Employee` は、この規則を破る例（生成した型が公開の型）である。

### 応答の項目の型が違うときの扱いを決めて固定する（#660）

向こうの、スタブを契約の型に照らす考え方を、こちらでは「本物の応答の項目の型」まで広げる。
決め方、試験、`PaymentGatewayClient` を変えるときの形式検証の再検査は #660 に書く。

## 取り込まないもの

- **生成した型を業務の型として公開する形**：契約の変更が、呼ぶ側の型へそのまま届く。向こうの M5 は人が見る規則で、機械が止めない。こちらは interface が `domain.model` の値オブジェクトだけを使い、ArchUnit が置き場を止める。
- **応答を検査しない `json<T>()` の形**：決済では、契約と違う応答を `PAID` や `FAILED` に写す害が大きい。こちらは `toOutcome` で検査する。
- **決済代行の OpenAPI の契約ファイル**：契約を決めているのはこちらで、実体はスタブと Client の結合テストである。ファイルを足すと、ADR-072 の文、スタブ、Client に続く 4 つ目の表現になり、照合の相手はスタブだけである。本物の決済代行を選び、その事業者が OpenAPI を公開するなら、事業者の契約を取り込む形を、ADR-072 の未決事項 3 とあわせて決める。
- **OpenAPI から Java の型や Client を生成する道具（OpenAPI Generator、Spring の HTTP interface）**：操作が 1 つで、応答の項目が 2 つの今は、手書きの record のほうが小さい。生成した型は、項目を必須の型で作るので、`@Nullable` で受けて検査する今の形と逆になる。
- **packages に分けた外部システムの契約、Client、フェイクの置き場**（向こうの ADR 0052）：こちらは Gradle の 1 つのモジュールで、Client は機能モジュールの `infrastructure.client` に置く（ADR-050）。フェイクは WireMock のコンテナで、スタブは `docker/wiremock` の 1 か所である（ADR-072）。
- **Client の戻り値を `Promise` にし、呼ぶ側が包む形**：Java に当たらない。

## 確かめられなかったこと

- 本物の決済代行の契約と、その事業者の SDK や OpenAPI の有無。決済代行の事業者は選ばれていない（ADR-072 の未決事項 3）。
- `domain.model` の許可の一覧で、Lombok のように実行時に残らない注釈が通るか。`jdeps` はクラスファイルの依存だけを見る。
- `application` の許可の一覧が、`@PreAuthorize`（#568 で入る予定）や今後の Spring の型を含めて、保守できる大きさに収まるか。
- Jackson の `withCoercionConfig` を、`RestClient.Builder#configureMessageConverters` で Client だけに効かせる配線は、動かしていない。Spring の API があることだけを確かめた（`spring-web` 7.0.10 の `javap`）。
- `spring.jackson` でグローバルに文字列への変換を止めたときの、自前の API の入力への影響。
- OpenAPI Generator の `spring-http-interface` と Spring の `@ImportHttpServices` を、こちらの Spring Boot 4.1.2 で動かしたこと。
- Spring Framework 7.1 のリリースノートはプレビューの版を読んだ。7.1.0 の正式なリリースノートは読んでいない。
- 向こうで、`hr-system` を呼ぶモジュールができたときの写す層の形。今はコードがない。
- 向こうのほかの外部システム（S3、SES、Better Auth）の応答の扱い。SES の Port（`mail-sender.ts`）だけ読んだ。

## 出典

- 向こう：`packages/hr-system/` の上のファイル、`docs/adr/0052`、`0054`、`0023`、`docs/domain-model/package-layout.md`、`docs/architecture-principles/thin-wrapper.md`、`lint-restrictions.json`、`drivers.json`、Issue #562
- こちら：上の「こちら」のファイル、ADR-002、ADR-019、ADR-050、ADR-072、ADR-052、ADR-024、`.agents/skills/formal-verify-order-payment/SKILL.md`、`docs/agents/formal-verification.md`
- [Jackson 3.3.0 の `CoercionConfigs`（ソース）](https://github.com/FasterXML/jackson-databind/blob/3.x/src/main/java/tools/jackson/databind/cfg/CoercionConfigs.java)（`sources.jar` の 3.3.0 を読んだ）
- [ky の README（型引数、`.json(schema)`）](https://github.com/sindresorhus/ky#readme)
- [OpenAPI Generator：spring ジェネレータ](https://openapi-generator.tech/docs/generators/spring/)
- [Spring Boot：Calling REST Services（HTTP Service clients）](https://docs.spring.io/spring-boot/reference/io/rest-client.html)
- [Spring Boot 4.2 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.2-Release-Notes)
- [Spring Framework 7.1 Release Notes](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.1-Release-Notes)

## 作業の Issue

- #659：`domain.model` の依存を許可した型だけにし、外部システムの SDK と生成型の扱いを docs に書く。
- #660：決済代行の応答の項目の型が契約と違うときの扱いを決めて固定する。
