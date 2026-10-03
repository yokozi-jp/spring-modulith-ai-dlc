---
type: Convention
title: Web APIの方式とURLの設計
description: HTTP APIの方式の選択、名前の表記、operationId、リソースのネストとフラット、カスタムメソッド、バッチ操作のURLを定める規約。新しいAPIのエンドポイントやパスを設計するとき、operationIdを付けるとき、RESTで表しにくい操作を追加するときに読む。
tags: [convention, web-api, rest, future-arch-guidelines]
---

# Web APIの方式とURLの設計

HTTP APIはRESTで設計し、パスはkebab-caseの複数形のリソース名で表す。
operationIdは、状態を変える操作をユースケース名、参照を`list<Resource>`と`find<Resource>ById`の形で明示する。
子リソースをネストするかは、親との一覧取得、作成、削除の関係で決める。
HTTPメソッドで表せない操作だけを、パスの末尾に置くPOSTのカスタムメソッドにする。

## 適用範囲

この規約は、バックエンドが`/api`配下に公開するJSON APIに適用する。
APIの配置先は[ADR-013](../adr/ADR-013-standardize-http-api-contracts.md)と[ADR-014](../adr/ADR-014-use-same-origin-spa-security-boundary.md)に従い、SPAと同じオリジンの`/api`配下に置く。
エラー表現、ページング、バージョニング、冪等性キー、OpenAPIの契約はADR-013が定める。

## API方式の選択

APIは原則としてRESTで設計する。
次の条件を満たす場合に限り、RESTに代わる方式を検討し、採用するときはADRを起こす。

- **GraphQL**：リソース間の関連が強くN+1問題が深刻であり、かつクライアントが取得項目を動的に決める必要がある。
- **gRPC**：サーバー間で双方向の常時接続や即時通知が必要で、数秒間隔のポーリングでは代替できない。またはマイクロ秒単位の遅延要件がある。

JSON-RPCは採用しない。
全操作をPOSTに統一するRPC型の設計へ切り替える場合も、ADRを起こす。
この切り替えを検討してよいのは、権限モデルの違いでGETのキャッシュが効かず、複数リソースを同一トランザクションで更新する画面操作が中心となり、HTTPメソッドと業務操作の対応が付けにくい場合である。

## 名前の表記

- **パスのリソース名**：kebab-caseの複数形にする（`/api/delivery-schedules`）。
- **カスタムメソッド名**：camelCaseにする（後述）。
- **独自のヘッダー名**：kebab-caseとし、`x-`接頭辞を付ける（`x-debug-enabled`）。標準化済みまたは標準化中のヘッダーは、その名前をそのまま使う（`Idempotency-Key`）。
- **JSONの項目名**：ADR-013が定める`nextCursor`や`traceId`と同じcamelCaseにする。

独自ヘッダーは用途を決めてから追加し、数を必要最小限にする。

## operationId

operationIdは、Orvalが生成する関数名とHook名の元になる。
各operationに次の形のoperationIdを付ける。

- **状態を変える操作**：Commandと同じ`<UseCase>`をlowerCamelCaseにした名前（`placeOrder`、`confirmOrder`、`cancelOrder`）。
  作成も`create<Resource>`ではなくユースケース名にする。
  `<UseCase>`の付け方は[クラスの役割：Command](../backend/class-roles/command.md)に従う。
- **一覧の取得**：`list<Resource>`（`listOrders`）。
- **1件の取得**：`find<Resource>ById`（`findOrderById`）。

operationIdはControllerのハンドラメソッドに`@Operation(operationId = "...")`で明示し、springdocにメソッド名から作らせない。
ハンドラメソッドの名前は操作の動詞（`place`、`details`、`search`）であり（[クラスの役割：Controller](../backend/class-roles/controller.md)）、そのままでは1語のoperationIdになるためである。
また、springdocはメソッド名が重なると`findById_1`のような接尾辞を付け、生成Hookが`useFindById1`のような名前になる。

名前は小文字の動詞で始まるlowerCamelCaseにし、数字と`_`を含めない。
略語も先頭の文字だけを大文字にする（`findUrlById`）。
この形は`.spectral.yaml`の`operation-id-naming`が`task be-openapi-lint`で検査する。
先頭の動詞の語彙は検査しないため、レビューで確かめる。

## リソースのネストとフラット

次のいずれかに当てはまる子リソースは、親リソースの配下にネストする（`/articles/{articleId}/comments`）。

- 親リソースに紐づく子リソースを一覧で取得する。
- 親リソースの配下に子リソースを作成する。
- 親リソースを削除したら子リソースも削除すべきである。

親子関係を持たず独立したリソースは、フラットに表す（`/comments/{commentId}`）。

同じリソースでも、操作ごとにネストとフラットを使い分けてよい。
たとえば受注の作成は取引先が必須なので`POST /customers/{customerId}/orders`とし、取引先との取引が止まっても残る受注の編集と取り消しは`PUT /orders/{orderId}`と`DELETE /orders/{orderId}`にする。
同じ操作をネストとフラットの両方で提供するのは、利用者のユースケースが見えない場合に限り、最小限にする。

## カスタムメソッド

操作はできる限りリソースとHTTPメソッドで表す。
`POST /users/{userId}/upgradePlan`のような操作は、`PUT /users/{userId}/subscription`のようにリソースへ言い換えられないかを先に検討する。

次の場合に限り、カスタムメソッドを使う。

- `copy`、`move`、`cancel`、`undelete`、`batch`のように、HTTPメソッドで表しにくい操作である。
- 既存の機能とパスが重複する（単件登録の`POST /orders`の後に一括登録を追加する場合など）。

カスタムメソッドは次の形式にする。

- パスの最後の要素にだけ置く（`POST /drafts/{draftId}/copy`）。
- 名前はcamelCaseにする。
- HTTPメソッドはPOSTにする。

コロン区切り（`/drafts/{draftId}:copy`）とクエリパラメータ（`/drafts/{draftId}?action=copy`）では表さない。

## バッチ操作

複数のリソースを一度に登録または更新するときは、`POST /users/batch`のようにバッチ処理用のエンドポイントを作る。
リクエストボディは`items`属性に配列を持つ。

```json
{
  "items": [
    { "id": 1, "name": "商品1", "price": 100 },
    { "id": 2, "name": "商品2", "price": 200 }
  ]
}
```

エラー応答には、失敗したリソースの識別子とエラーだけを含め、成功したリソースは返さない。
複数のリソースを一度に参照する方法は[クエリパラメータ](query-parameters.md)に従う。

## 出典

- フューチャー株式会社「Web API設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forWebAPI/web_api_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
