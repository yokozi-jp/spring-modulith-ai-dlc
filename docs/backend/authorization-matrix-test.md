---
type: Reference
title: 認可の表のテスト
description: 操作と操作する人の組ごとに認可の応答を確かめる AuthorizationMatrixTest の、操作、操作する人、期待の結果、期待の表、判定、例外の一覧、判定の検出力、操作を足すときに直す場所を示すリファレンス。認可の表のテストが失敗したとき、API の operation を足すとき、例外の一覧を足すか消すときに読む。
tags: [reference, backend, testing, security, authorization]
---

# 認可の表のテスト

`AuthorizationMatrixTest` は、commit した OpenAPI の operation と操作する人の組ごとに、応答が期待の表どおりかを確かめる。
Spring と DB を起動し、MockMvc で実際の Security filter chain を通す結合テストである。
期待と違ってよい組は、理由と Issue の番号を付けた例外の一覧にだけ置ける。
この形にした理由は [ADR-129](../adr/ADR-129-place-authorization-in-application-and-test-it-per-operation-and-actor.md) にある。

## 置き場所

テストと補助のクラスは、テストのソースの `com.example.demo.authorization` パッケージにある。

- `AuthorizationMatrixTest`：組ごとに要求を送り、判定する。
- `AuthorizationScene`：テナント、利用者、対象を作り、組の観測を集める。
- `AuthorizationRequests`：操作ごとの要求を作る。
- `BusinessRows`：業務のスキーマの行を読む。
- `AuthorizationOperation`：操作の enum。
- `AuthorizationActor`：操作する人の enum。
- `AuthorizationOutcome`：期待の結果の enum。
- `AuthorizationExpectations`：期待の表と例外の一覧。
- `AuthorizationJudgment`：応答と業務の行から違反を判定する。
- `AuthorizationJudgmentTest`：判定が 4 つの結果それぞれで違反を検出することと、fixture の Controller の違反を検出することを確かめる。

spec の operation を読む `OpenApiOperations` は `com.example.demo.testkit` にあり、`ApiContractCoverageTest` と共有する。

## 操作

操作は `AuthorizationOperation` の定数で、HTTP のメソッドとパスを持つ。
`AuthorizationMatrixTest` は、`openapi/openapi.yaml` を `ApiContractCoverageTest` と同じく swagger-core で読み、定数と spec の operation が過不足なく一致することを確かめる。
spec にない定数と、定数のない operation があると失敗する。

## 操作する人

操作する人は `AuthorizationActor` の 5 つの定数である。
どの人も、持ち主のテナントか別のテナントの 1 つに所属する。

| 定数                  | 所属するテナント   | 役割   | 説明                                                   |
| --------------------- | ------------------ | ------ | ------------------------------------------------------ |
| `OWNER`               | 持ち主のテナント   | MEMBER | 対象の注文を作った人。所属の操作では対象の所属の本人。 |
| `SAME_TENANT_MEMBER`  | 持ち主のテナント   | MEMBER | 持ち主でない member。                                  |
| `SAME_TENANT_ADMIN`   | 持ち主のテナント   | ADMIN  | 持ち主でない admin。                                   |
| `OTHER_TENANT_MEMBER` | 別のテナント       | MEMBER | 別のテナントの member。                                |
| `OTHER_TENANT_ADMIN`  | 別のテナント       | ADMIN  | 別のテナントの admin。                                 |

`OTHER_TENANT_ADMIN` は、所属の管理の API（`/api/current-tenant/members/{userId}`）で、別のテナントの利用者の ID が見えないことを確かめる。
所属の管理の API は ADMIN だけが呼べるため、`OTHER_TENANT_MEMBER` では、対象が見えるかを確かめる前に 403 になる。

## 期待の結果

期待の結果は `AuthorizationOutcome` の 4 つの定数である。

- `ALLOWED`：許す。2xx を返す。
- `HIDDEN`：見えない。対象が存在しないときと同じ status と本文を返す。
- `FORBIDDEN`：見えるが許さない、またはエンドポイントを呼ぶ権限がない。403 を返す。
- `UNPROCESSABLE`：本文で指定した対象が見えず、業務の条件を満たさない。422 を返す。

status の選び方は [HTTPステータスコードの選択](../web-api/status-codes.md) と [APIの認証、セッション、権限](../web-api/authentication-and-session.md#認可) に従う。

## 期待の表

期待の表は `AuthorizationExpectations` にあり、`AuthorizationOperation` の `switch` 式で操作ごとに 1 行を書く。
`switch` 式は `default` を持たず、各行は操作する人ごとの期待を持つ。
操作か操作する人の定数を足すと、期待を足すまでコンパイルが失敗する。
期待は docs の規約から手で書き、本番のコードの判断から作らない。

## 判定

`AuthorizationJudgment` は、組ごとに期待の結果に応じて次を確かめる。

- `ALLOWED` の組では、status のほかに本文も確かめる。操作ごとに、本文に含むべき id と、含んではならない別のテナントの id を `AuthorizationExpectations` が持つ。含むべき id がなければ過剰な拒否、含んではならない id があれば漏れとして失敗する。
- `HIDDEN` の組では、応答の status と本文が、対象が存在しないときの応答と同じになる。比べるときは本文の `traceId` を除く。
- `HIDDEN` の組の比べる相手は、id を持つ操作では 404、id を持たない一覧では 200 である。比べる相手がこの status でなければ、組は失敗する。
- 書き込みの操作で `ALLOWED` 以外の組では、両方のテナントの業務のスキーマの行の中身が、要求の前後で変わらない。

対象が存在しないときの応答は、次のとおり作る。

- id を持つ操作では、対象を作った後に、ランダムな id で同じ要求を送る。
- id を持たない一覧では、対象を作る前に同じ要求を送る。

id を持つ操作は、パスに id を持つ操作と、名前が `id` か `Id` で終わる query の引数を持つ操作である。
選び方は Spectral の `public-id-uuid` と同じで、`GET /api/payments` の `orderId` が該当する。

## 例外の一覧

期待と違ってよい組は、`AuthorizationExpectations` の例外の一覧に書く。
例外は、操作、操作する人、今の結果（`current`）、理由、Issue の番号を持つ。

- 例外の組は、`current` を期待として判定する。`current` と違う形で壊れると、失敗する。
- 例外の組が期待の表どおりになると、失敗する。理由の Issue で直したら、その組を一覧から消す。
- 理由が空の例外と、spec にない operation の例外があると、失敗する。

今の一覧の組は、同じテナントの `SAME_TENANT_MEMBER` と `SAME_TENANT_ADMIN` が、持ち主でない注文と決済記録に触れる組である。
`current` は `ALLOWED`、Issue は [#188](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/188) である。

別のテナントの組は、RLS が別のテナントの業務の行を読ませないため（[ADR-107](../adr/ADR-107-isolate-tenants-with-rls-set-per-transaction.md)）、期待の表どおりになる。
このため、[#363](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/363) の例外は一覧にない。
別のテナントの組が期待と違ったときだけ、その組を理由と Issue の番号を付けて一覧に足す。

## 判定の検出力

`AuthorizationJudgmentTest` は、`authorizationfixture.NoteFixtureController` を Spring の context を起動しない MockMvc で呼び、判定が違反を検出することを確かめる。
fixture は `com.example.demo` の外にあり、component scan、ArchUnit、OpenAPI の契約の書き出しに含まれない。

- 持ち主で絞らない GET が他人のメモを返すと、存在しないメモと応答が違うので違反になる。
- 404 を返すが他人のメモを書き換える PUT は、応答が同じでも業務の行が変わったので違反になる。
- 持ち主で絞る GET と PUT は、違反にならない。

## 操作を足すときに直す場所

API の operation を足したら、同じ変更で次を直す。

- `AuthorizationOperation` に定数を足す。足し忘れると、spec との一致の検査が失敗する。
- `AuthorizationExpectations` の `switch` 式に行を足す。足し忘れると、コンパイルが失敗する。
- 新しい操作の要求の作り方を `AuthorizationRequests` に足す。
- 期待と違う組を残すときは、例外の一覧に `current`、理由、Issue の番号を付けて足す。
