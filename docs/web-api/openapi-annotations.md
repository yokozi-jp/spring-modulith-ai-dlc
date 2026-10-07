---
type: Convention
title: OpenAPIのアノテーションとJavadoc
description: springdocが生成するOpenAPI契約の説明をJavadocに書く方法、Controllerと要求と応答のrecordに付けるアノテーション（tag、operationId、個別のエラー応答、201と204、example）、検査するルールを定める規約。HTTP APIのControllerやDTOを作る、または変えるときに読む。
tags: [convention, web-api, openapi, springdoc, javadoc]
---

# OpenAPIのアノテーションとJavadoc

OpenAPI契約の説明はJavadocに書き、springdocがtherapi-runtime-javadoc経由で読む。
アノテーションは`@Tag(name, description)`、`@Operation(operationId)`、個別の`@ApiResponse`、`@Schema(example)`に限る。
401、403、500と入力のあるoperationの400は`OpenApiConfig`のcustomizerが付けるため、Controllerに書かない。
欠落は`task api-lint`のSpectralが失敗にし、springdocの出力の形は`OpenApiAnnotationConventionTest`が固定する。

決定の理由は[ADR-053](../adr/ADR-053-document-openapi-from-javadoc-with-minimal-annotations.md)を参照する。
契約を変えたあとの再生成と確認の手順は[APIを変更する](runbook-api-change.md)に示す。

## 説明文

- 説明はJavadocに書き、`@Operation(summary, description)`と`@Schema(description)`に書かない。
- summaryとdescriptionは日本語で書き、operationId、tag、スキーマ名は英語にする。
- ハンドラのJavadocは1行目をsummaryにし、詳細は`<p>`の後に書く。
  springdocは`<p>`で最初の文を切り、`。`では切らない。
- ハンドラの`@param`はparameterとrequestBodyの説明に、`@return`は成功応答の説明になる。
- recordのDTOは全componentに`@param`を書く。
  クラスのJavadocはschemaの説明に、`@param`はpropertyの説明になる。
- MarkdownのJavadoc（`///`）を使わない。
  therapiが読めず、説明が空になる。

`OpenApiConfig`は、springdocがdescriptionへ入れたsummaryの重複と先頭の`<p>`を除く。
生成されるdescriptionには`<p>`より後の詳細だけが入る。

## tagとoperationId

- Controllerのクラスに`@Tag(name, description)`を付け、tagの説明は`description`に書く。
  `@Tag`を明示すると、クラスのJavadocはtagの説明に使われない。
- tagの名前は業務機能のパッケージ名をkebab-caseにし（`ordering`、`delivery-schedule`）、`-controller`で終わらせない。
- 1つのoperationにtagを1つだけ付ける。
- operationIdは`@Operation(operationId = "...")`で明示し、名前は[Web APIの方式とURLの設計](api-style.md#operationid)に従う。

## パラメータ

クエリパラメータは`@RequestParam`で項目ごとに受け、`@ParameterObject`と`@ModelAttribute`でまとめて受けない。
受け方は[クエリパラメータ](query-parameters.md#controllerでの受け方)に従う。

## component schemaの名前

swagger-coreはcomponent schemaをJavaの単純クラス名で識別するため、API全体のDTOで単純名を重複させない。
異なるDTOのネスト型に`Line`のような汎用名を再利用せず、`PlaceOrderLineRequest`や`OrderLineResponse`のように役割を含む名前を付ける。
FQNやcustom resolverで衝突を回避せず、Javaの型名自体を一意にする。

## example

- 要求と応答のrecordの、文字列と数値のpropertyに`@Schema(example = "...")`を付ける。
- enum、boolean、ネストしたrecord（`$ref`）、object、配列のpropertyには付けない。
- recordのpropertyでは、`@Schema`を`example`だけに使い、`description`、`requiredMode`、`type`を書かない。
  必須と形式はBean Validationの制約（`@NotBlank`、`@Positive`）から生成される。

## エラー応答

- 400、401、403、500は`OpenApiConfig`のcustomizerが付ける。
  401、403、500は全operationに、400はparameterかrequestBodyがあるoperationにだけ付く。
- 400の`BadRequestProblem`は`ValidationProblem`（`ProblemDetail`と、typeが`/problems/validation-error`のときの`errors`）を参照する。
- 404、409、422は、起きるoperationにだけ`@ApiResponse(responseCode, ref = "#/components/responses/<Name>Problem")`で書く。
  `<Name>`は`NotFound`、`Conflict`、`UnprocessableContent`のいずれかである。
- `springdoc.override-with-generic-response`は`false`であり、`ApiExceptionHandler`の応答は自動では付かない。

ステータスコードの選び方は[HTTPステータスコードの選択](status-codes.md)に従う。

## 成功応答

ハンドラに`@ApiResponse`を1つでも書くと、springdocは成功応答を自動で足さない。
次の形で成功応答も書く。

- **200**：`@ApiResponse(responseCode = "200")`と書く。
  説明は`@return`から、schemaは戻り値の型から生成される。
- **201**：`ResponseEntity<Void>`を返し、`Location`ヘッダーを持つ`@ApiResponse(responseCode = "201", headers = @Header(...))`を書く。
- **204**：`ResponseEntity<Void>`を返し、`@ApiResponse(responseCode = "204")`を書く。

この形では`200`が残らず、`@ResponseStatus`は要らない。
`@ApiResponse`を書かないハンドラには、springdocが200を付ける。

## 例

注文のControllerの例を示す（依存とメソッドの本体は省く）。

```java
/** 注文の HTTP API。 */
@RestController
@RequestMapping("/api/orders")
@Tag(name = "ordering", description = "注文の API")
class OrderController {

  /**
   * 注文を受け付ける。
   *
   * <p>受け付けた注文の URI を Location に入れて返す。
   *
   * @param request 受け付ける注文の内容
   * @return 本文のない 201 の応答
   */
  @Operation(operationId = "placeOrder")
  @ApiResponse(
      responseCode = "201",
      headers =
          @Header(
              name = "Location",
              description = "受け付けた注文の URI",
              schema = @Schema(type = "string", format = "uri")))
  @ApiResponse(responseCode = "422", ref = "#/components/responses/UnprocessableContentProblem")
  @PostMapping
  /* package */ ResponseEntity<Void> place(@Valid @RequestBody final PlaceOrderRequest request) {
    // CommandHandler を呼び、ResponseEntity.created(location).build() を返す。
  }

  /**
   * 注文の詳細を返す。
   *
   * <p>注文がなければ 404 を返す。
   *
   * @param orderId 注文の ID
   * @return 注文の詳細
   */
  @Operation(operationId = "findOrderById")
  @ApiResponse(responseCode = "200")
  @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFoundProblem")
  @GetMapping("/{orderId}")
  /* package */ OrderDetailsResponse details(@PathVariable final String orderId) {
    // OrderQueries を呼び、OrderDetailsResponse.from(...) を返す。
  }

  /**
   * 注文を取り消す。
   *
   * <p>取り消せない状態なら 409 を返す。
   *
   * @param orderId 取り消す注文の ID
   * @param request 画面が読んだ注文のロック番号
   * @return 本文のない 204 の応答
   */
  @Operation(operationId = "cancelOrder")
  @ApiResponse(responseCode = "204")
  @ApiResponse(responseCode = "409", ref = "#/components/responses/ConflictProblem")
  @PostMapping("/{orderId}/cancel")
  /* package */ ResponseEntity<Void> cancel(
      @PathVariable final String orderId, @Valid @RequestBody final CancelOrderRequest request) {
    // CommandHandler を呼び、ResponseEntity.noContent().build() を返す。
  }
}
```

要求と応答のrecordは、全componentの`@param`と、対象のpropertyの`example`を持つ。

```java
/**
 * 注文を受け付ける API の本文。
 *
 * @param customerId 注文する顧客の ID
 * @param quantity 注文する数量
 * @param giftWrap ギフト包装をするかどうか
 */
public record PlaceOrderRequest(
    @NotBlank @Schema(example = "C-0001") String customerId,
    @Positive @Schema(example = "2") int quantity,
    boolean giftWrap) {}

/**
 * 注文の詳細を返す API の本文。
 *
 * @param orderId 注文の ID
 * @param status 注文の状態の区分値
 * @param lines 注文の明細
 */
public record OrderDetailsResponse(
    @Schema(example = "O-0001") String orderId,
    @Schema(example = "PLACED") String status,
    List<OrderDetailsResponse.OrderLineResponse> lines) {}
```

## 旧リポジトリの方式を採らない理由

spring-modulith-ai-harnessの方式は次の理由で採らない。

- summaryを`@Operation`に書かないのは、Javadocと同じ説明を二重に保守することになるためである。
- `@ApiResponse`をoperationごとに全部書かないのは、401、403、500は全operationで同じで、customizerで付ければ書き忘れが起きないためである。

## 検査

`task api-lint`は、コミット済みの`openapi/openapi.yaml`を`.spectral.yaml`のルールで検査する。
ルール自体は`task api-lint-rules-test`が合格例と違反例のfixtureで検査する。
springdocの出力の形（Javadocの反映、descriptionからのsummaryと`<p>`の除去、customizer、tag、201と204、example）は、`OpenApiAnnotationConventionTest`がサンプルのControllerで固定する。
springdocやtherapiの版を上げてこのテストが失敗したら、この文書も直す。

## 作成時のチェックリスト

- [ ] ハンドラのJavadocの1行目をsummaryにし、詳細を`<p>`の後に書く。［Spectralで検査：operation-description（説明の有無だけ。summaryの切れ目は自分で点検）］
- [ ] ハンドラの引数ごとに`@param`を書く。［Spectralで検査：oas3-parameter-description（requestBodyの説明は自分で点検）］
- [ ] recordの全componentに`@param`を書く。［Spectralで検査：schema-property-description］
- [ ] 文字列と数値のpropertyに`@Schema(example = "...")`を付ける。［Spectralで検査：schema-property-example］
- [ ] `@Tag(name, description)`をクラスに付ける。［Spectralで検査：operation-tags、tag-description、operation-tag-defined］
- [ ] tagを業務機能のkebab-caseにし、operationに1つだけ付ける。［Spectralで検査：tag-name-format、operation-singular-tag］
- [ ] `@Operation(operationId = "...")`を明示する。［Spectralで検査：operation-id-naming（形だけ。明示は自分で点検）］
- [ ] 404、409、422を起きるoperationにだけ`ref`で書く。［Spectralで検査：error-response-problem-detail（参照の形だけ。付け忘れは自分で点検）］
- [ ] `@ApiResponse`を書いたハンドラに成功応答（200、201、204）も書く。［Spectralで検査：operation-success-response］
- [ ] 201は`Location`ヘッダー、204は本文なしにし、200を残さない。［自分で点検］
- [ ] `@Operation(summary, description)`、`@Schema(description)`、`///`を使わない。［自分で点検］
- [ ] クエリパラメータを`@ParameterObject`や`@ModelAttribute`でまとめて受けない。［自分で点検］
