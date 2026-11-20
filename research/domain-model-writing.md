# ドメインモデルの書き方の docs を比べる（#325 の調査）

この文書は #325 の調査の記録で、merge しない。
結論は #325 のコメントと同じで、作業は #576、#577、#578 で行う。
向こうは `yokozi-jp/agent-software-factory`（`origin/main`、commit `29ba897`）、こちらは `yokozi-jp/spring-modulith-ai-dlc`（`origin/main`、commit `63e5c9f8`）である。

## 結論

一部採用とする。

向こうの `docs/domain-model/` のうち、この問いの対象は `types.md`（状態と値の型）、`glossary-mapping.md`（識別子の英語名）、`testing.md`（型述語と遷移の関数のテスト）である。
`failures.md`（失敗の表し方）は #317 で、`package-layout.md` と `add-module.md` は #340 で、`authorization.md` は #315 で、`application-effect.md` は #317 と #319 で扱う。

型の書き方の大半は、こちらの `docs/backend/java-type-modeling.md` と同じ決定になっている。
どちらも pstack の `typescript-best-practices` と `java-best-practices` を元にしているためである。

次の 3 つを取り込む。

- **用語集の語と英語の識別子の対応表**（#576）：こちらの `postgresql-naming.md:20-26` が「論物変換辞書」を置くと決めたまま、辞書がない。
  辞書を今作り、`GLOSSARY.md` の語ごとに英語の語を 1 つ決め、Java、DB、API の名前をそれに揃える。
  `GLOSSARY.md` の語と辞書の行が一致することを `task okf-check` で確かめる。
- **switch の `default` を機械で止める**（#577）：向こうは、union の一部の場合だけを書いて `default` で残りを受ける `switch` を lint で止める。
  こちらは同じ規則を docs に書き、「機械では検査していない」としている（`java-type-modeling.md:155-159`）。
  PMD の XPath の規則を 1 つ足し、enum と型パターンの `switch` の `default` を止める。
- **値オブジェクトの不変条件を境界の両側で確かめる**（#578）：向こうは、型述語ごとに境界の内側、ちょうど境界、すぐ外側をテストに書く。
  こちらの `value-object.md` の例は拒否する側の範囲だけを確かめ、PIT の境界の変異が生き残る。
  規約と例を直し、テストのない値オブジェクトにテストを足す。

`domain` が ID の乱数を持たない形、`domain` で `throw` しない形、判別のフィールドの `_tag`、`satisfies`、引数の object、キャストを lint で止める形は取り込まない。

## 比べたもの

### 向こう

- `docs/domain-model/index.md`、`types.md`、`glossary-mapping.md`、`testing.md`、`failures.md`
- `docs/adr/0027-domain-model-as-pure-functions-and-unions.md`（以下 ADR 0027）
- `vite.config.ts:660-662`（`switch-exhaustiveness-check`、`no-non-null-assertion`、`no-unsafe-type-assertion`）
- `GLOSSARY.md`（40 語）

### こちら

- `docs/backend/java-type-modeling.md`、`docs/backend/java-coding.md`
- `docs/backend/class-roles/value-object.md`、`aggregate.md`
- `docs/agents/domain.md`、`GLOSSARY.md`（4 語）
- `docs/database/postgresql-naming.md`
- ADR-012、ADR-050、ADR-060、ADR-062、ADR-069、ADR-089
- `backend/config/pmd/ruleset.xml`、`backend/build.gradle`
- `backend/src/main/java/com/example/demo/*/domain/model/`、対応するテスト

## 項目ごとの比較

| 向こうの規則                                            | 向こうの場所                       | こちらの扱い                                                                                                                   | 判定                             |
| ------------------------------------------------------- | ---------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ | -------------------------------- |
| 状態は `_tag` の判別可能な union                        | `types.md:13-25`、ADR 0027:14      | 集約の `status` は変わるフィールド（`java-type-modeling.md:27-28`）。#317 で取り込まないと決めた                               | 同じ決定（#317）                 |
| 遷移の関数は時刻を引数で受ける                          | `types.md:27`                      | `Order.place` が `orderedAt` を受け、`Instant.now()` は ArchUnit が止める（`DateTimeConventionsArchTest`）                     | 同じ決定                         |
| 遷移の関数は ID を引数で受け、`domain` は乱数を持たない | `types.md:27`、ADR 0027:15         | ADR-060 が「`public_id` は Domain のファクトリが `UUID.randomUUID()` で採番し、値を外から渡すオーバーロードは作らない」        | 取り込まない                     |
| 判別のフィールドは `_tag`                               | `types.md:40-42`                   | Java は sealed interface の許可された型で判別する                                                                              | 当たらない                       |
| 値の型は `__brand` と型述語、日時は `Temporal`          | `types.md:44-59`                   | 値オブジェクトの record とコンパクトコンストラクタ（`value-object.md:10-11`）、日時は `Instant`                                | 同じ決定                         |
| `throw` は `infrastructure` の `parseX` だけ            | `types.md:56`、ADR 0027:24-28      | 業務の失敗は例外（ADR-062）                                                                                                    | 同じ決定（#317）                 |
| 外から来たデータは `unknown` にして検証する             | `types.md:58`                      | 外部のデータは境界で型に変換する（`java-type-modeling.md:96-105`）                                                             | 同じ決定                         |
| 構成で表す型、全域な型                                  | `types.md:61-71`                   | `java-type-modeling.md:43-88`（`NonEmptyList`、`Period`、`Optional`）                                                          | 同じ決定                         |
| 絞り込みの順序、`as` は最後                             | `types.md:73-81`                   | 型パターンの `instanceof` か sealed の switch で受ける（`java-type-modeling.md:114-127`）                                      | 同じ決定                         |
| 網羅を lint と `never` の両方で確かめる                 | `types.md:83-100`、ADR 0027:19     | sealed の switch に `default` を書かない（`java-type-modeling.md:129-147`）。機械では検査していない（:158）                    | 取り込む（PMD）                  |
| `satisfies`                                             | `types.md:102-108`                 | Java に当たる構文がない                                                                                                        | 当たらない                       |
| 引数が 2 つ以上なら object で受ける                     | `types.md:110-112`                 | 同じ型の component は別の値オブジェクトに分ける（`java-type-modeling.md:39-41`）、CommandHandler は Command の record で受ける | 取り込まない                     |
| 生成された型から導く                                    | `types.md:114-116`                 | 形の正本から導く（`java-type-modeling.md:107-112`）                                                                            | 同じ決定                         |
| 識別子は英語にし、用語集との対応表を置く                | `glossary-mapping.md`、ADR 0027:37 | 論物変換辞書を置くと決めたが、辞書がない（`postgresql-naming.md:20-26`）                                                       | 取り込む                         |
| 型述語の境界値のテスト                                  | `testing.md:12-31`                 | QuickTheories で範囲全体を確かめる（`value-object.md:161-183`）。例は拒否する側だけ                                            | 取り込む                         |
| 遷移の関数は状態ごと、失敗ごとに最小の入力で確かめる    | `testing.md:33-39`                 | 許される遷移と許されない遷移を操作ごとに確かめる（`aggregate.md:231-235`）                                                     | 同じ決定                         |
| `domain` の変異テストのゲート                           | `testing.md:10`、ADR 0027:36       | PIT はスコアの閾値を持たない（ADR-076）                                                                                        | #299 で決めた                    |
| `as` と `!` を lint で止める                            | `vite.config.ts:661-662`           | null は NullAway、キャストはレビュー（`java-type-modeling.md:158`）                                                            | 一部同じ。キャストは取り込まない |

## 取り込むもの

### 用語集の語と英語の識別子の対応表

向こうは、`domain` と `application` の識別子を英語にし、`GLOSSARY.md` の語との対応表を `docs/domain-model/glossary-mapping.md` に置く。
理由は、DB の列、URL、OpenAPI、ライブラリの名前と揃うためである（向こうの `glossary-mapping.md:10`、ADR 0027:37）。
規則は、用語集に語を足したら同じ変更で表に行を足す、1 つの語に識別子を 1 つだけにする、決まっていない語は「未定」とする、の 3 つである（`glossary-mapping.md:14-17`）。
用語集には実装の詳細を書かない。

こちらは、同じものを「論物変換辞書」として置くと決めている。

- `postgresql-naming.md:24`：業務用語を英訳するかローマ字にするかはチームで一つに決め、論理名と物理名の対応を論物変換辞書で管理する。
- `postgresql-naming.md:25-26`：辞書の論理名は `GLOSSARY.md` の用語と一致させる。一致の機械検査は、辞書がまだないため行わず、辞書を作るときに要否を決める。

辞書はまだなく、英訳かローマ字かも docs に書いていない。
コードはすでに英訳で揃っている。
次の食い違いは、辞書がないと読み手が判断できない。

- `GLOSSARY.md:15-17` は「所属」の避ける語に「メンバーシップ」を、`:19-21` は「役割」の避ける語に「ロール」を挙げる。
  コードの名前は `Membership`（`tenancy/domain/model/Membership.java:10`）と `Role`（`Role.java:4`）で、表は `tenancy.m_membership` である。
  `_Avoid_` は日本語の同義語を避ける指示で、英語の識別子を禁じるものではない。
  これを書いた場所がないので、エージェントは `Membership` を避ける語と読みうる。
- 注文は、モジュールが `ordering`（ADR-069 で `order` が SQL の予約語のため）、集約が `Order`、表が `ordering.t_order`、API が `/api/orders` である。
  意図した例外だが、記録がない。
- IdP の識別は、クラスが `ExternalIdentity`、表が `tenancy.m_user_identity` で、語が揃っていない。
- `GLOSSARY.md:3` は「参照業務機能（商品、注文、決済）で使う用語を定める」と書くが、語はテナント、利用者、所属、役割の 4 つだけで、商品、注文、明細、決済がない。

取り込む形は次のとおりである。

- 業務用語は英訳すると決め、ADR に残す。DB、API の契約に効き、戻しにくいので ADR の基準（`docs/adr/conventions.md` の「ADR を作る基準」）に当たる。
- 論物変換辞書を作る。行は `GLOSSARY.md` の語ごとにし、英語の語と、意図した例外（`ordering`、`m_user_identity` を直すか残すか）を備考に書く。
  クラスの名前を全部は並べない。`postgresql-naming.md:28-30` の「分解できない単語から登録する」に従い、合成語は語の組から作る。
- コードと docs にすでにある語（商品、注文、明細、決済）を、`domain-modeling` の形式で `GLOSSARY.md` に足す。
- `GLOSSARY.md` の太字の語と辞書の行が 1 対 1 で一致することを、`task okf-check` の小さな検査で止める。
  一致の検査は `postgresql-naming.md:26` が「辞書を作るときに要否を決める」とした項目で、向こうは検査を持たない。
  対応表は語を足すたびに忘れやすく、検査は 2 つの Markdown の見出しを比べるだけで書ける。
- `docs/agents/domain.md` の「用語」の節から辞書を参照する。

### switch の `default` を機械で止める

向こうは `switch-exhaustiveness-check` を error にしている（`vite.config.ts:660`）。
既定の `considerDefaultExhaustiveForUnions: false` では、union の一部の場合だけを書いて `default` を置いた `switch` も、網羅していないとして止まる（[oxlint の規則の説明](https://oxc.rs/docs/guide/usage/linter/rules/typescript/switch-exhaustiveness-check.html)）。

こちらの規則は `java-type-modeling.md:131-132` の「sealed 型の switch に `default` を書かない。`default` があると、バリアントを足してもコンパイルが通り、新しい場合を黙って受ける」である。
`:158` は「`default` の禁止は機械では検査していない」とする。

調べると、半分はすでに PMD が止めている。
`ruleset.xml:8-11` は `category/java/bestpractices.xml` を全部有効にしており、次の 2 つの規則が入る。

- `ExhaustiveSwitchHasDefault`（PMD 7.10.0 から）：すべての場合を書いたうえで `default` を置いた enum と sealed の `switch` を止める。
- `NonExhaustiveSwitch`：一部の enum の定数だけを書き、`default` のない `switch` 文を止める。

止まらないのは、一部の場合だけを書いて `default` で残りを受ける形である。
これは `:131-132` が止めたい形そのものである。
PMD 7.23.0 の `ASTSwitchLike` は、XPath の属性に `@DefaultCase`、`@EnumSwitch`、`@Exhaustive` を出す。
次の XPath で、enum と型パターンと record パターンの `switch` の `default` を止められる（下の「確かめたこと」）。

```xpath
//(SwitchStatement|SwitchExpression)
  [@DefaultCase = true()]
  [@EnumSwitch = true() or */SwitchLabel/(TypePattern|RecordPattern)]
```

XPath からは選んだ式の型が sealed かを読めないので、型パターンの `switch` を sealed に限れない。
`Object` を型パターンで振り分ける `switch` の `default` も止まる。
こちらは `Object` を内部へ渡さない規約（`java-type-modeling.md:98-99`）なので、この形は規約の外にあり、要るときは理由を書いて `@SuppressWarnings("PMD.<規則名>")` で抑える。

Error Prone の `UnnecessaryDefaultInEnumSwitch` と `MissingCasesInEnumSwitch` も enum の `switch` を扱うが、一部の定数と `default` の組は止めず、sealed を扱わない（[UnnecessaryDefaultInEnumSwitch](https://errorprone.info/bugpattern/UnnecessaryDefaultInEnumSwitch)）。
Error Prone 2.52.0（こちらの版）と 2.53.0（最新）に、sealed の `switch` の `default` を扱う検査はない。
PMD 7.24.0 から 7.29.0（最新）のリリースノートにも、この形を止める新しい規則はない（7.27.0 と 7.29.0 は `ExhaustiveSwitchHasDefault` の誤検知とメッセージを直しただけ）。
そのため、既存の `UseLombokSlf4j` と同じ形のカスタムの XPath の規則を 1 つ足す。

今の `backend/src/main` に `switch` はなく、`backend/src/test` の 7 つはすべて `String` の `switch` で、この規則に当たらない。
この規則は、#564 が `ChargeOrderResult` と `ChargeOutcome` を sealed interface にすると、その `switch` から効く。

### 値オブジェクトの不変条件を境界の両側で確かめる

向こうは、型述語ごとに次を書く（`testing.md:14-20`）。

- 通る値の境界の内側と、ちょうど境界の値（最小、最大）
- 通らない値の、境界のすぐ外（最小の 1 つ下、最大の 1 つ上）
- 型は合うが中身が違う値（空文字、前後の空白、違う長さ）

理由は、比較演算子の取り違え（`<` と `<=`）は変異テストが見つけるが、境界のテストがないと変異が生き残るからである（`testing.md:20`）。

こちらの `value-object.md:163-164` は「不変条件は QuickTheories のプロパティベーステストで、入力の範囲全体について確かめる」とし、例（`:166-181`）は `integers().between(Integer.MIN_VALUE, 0)` が拒否されることだけを確かめる。
この例では、`value < 1` を PIT の `CONDITIONALS_BOUNDARY` が `value <= 1` に変えた変異が生き残る（下の「確かめたこと」）。
1 が通ることを確かめていないからである。
QuickTheories 0.26 は範囲の両端を必ず生成するので、通る側の範囲（`between(1, Integer.MAX_VALUE)`）を足せば変異は止まる。

規約と実際のテストも食い違っている。

- QuickTheories を使うテストは `ApiErrorControllerTest` と `JacksonConfigTest` だけで、値オブジェクトのテストにはない。
- `ordering` の `Quantity`、`Money` と、`payment` の `GatewayPaymentCode` には、不変条件を直接確かめるテストがない。
- `UserTest` は `ExternalIdentity` を、255 文字で作れる、256 文字で失敗する、空白だけで失敗する、の具体例で確かめている（`UserTest.java:47-65`）。向こうの形と同じである。

取り込む形は次のとおりである。

- `value-object.md` の「対応するテスト」に、不変条件ごとに通る側と通らない側の両方を確かめることを書く。
  境界の値は、範囲の両端を生成する QuickTheories の 2 つの性質か、JUnit の `@ParameterizedTest` の具体例で書く。
  文字列は、空、空白だけ、上限の長さ、上限を 1 つ超える長さを書く。
- 例の `QuantityTest` に、1 以上の数量を作れる性質を足す。
- チェックリストの「不変条件を QuickTheories のプロパティベーステストで確かめる」を、境界の両側を確かめる、に変える。
- テストのない `Quantity`、`ordering` の `Money`、`GatewayPaymentCode` に、この形のテストを足す。

## 取り込まないもの

- **ID を引数で受け、`domain` が乱数を持たない形**（向こうの `types.md:27`、ADR 0027:15）：向こうの理由は、`domain` を純粋にし、テストで固定の値を渡せることである（`testing.md:37`）。
  こちらは ADR-060 が、`public_id` は Domain のファクトリが `UUID.randomUUID()` で採番し、値を外から渡すオーバーロードは作らず、テストは値を固定せず `version()` が 4 であることを確かめる、と決めている。
  時刻と違い、UUID v4 の値に業務の意味はなく、値で振る舞いが変わる処理もない。
  作った集約が ID を返すので、テストは返った値を読めばよい。
  引数で受けると、集約を作るすべての CommandHandler が ID の生成器（Spring の `IdGenerator` など）を受け取ることになり、得るものがない。
  ADR-060 を改める理由にならない。
- **`domain` で `throw` しない形と、失敗の `_tag`**：#317 で取り込まないと決めた。
- **判別のフィールドの `_tag` と `satisfies`**：Java は sealed interface の許可された型で判別し、`satisfies` に当たる構文がない。
- **引数が 2 つ以上なら object で受ける形**（`types.md:110-112`）：Java に名前付きの引数がない。
  こちらは、取り違えると業務の意味が変わる同じ型の値を別の値オブジェクトに分け（`java-type-modeling.md:39-41`）、CommandHandler は Command の record を受ける。
  record も位置で受けるので、引数を record に包んでも取り違えは防げない。
- **キャストを lint で止める形**（`no-unsafe-type-assertion`、`vite.config.ts:662`）：TypeScript の `as` は実行時に何も確かめないが、Java の参照型のキャストは実行時に型を確かめ、合わなければその場で `ClassCastException` を投げる。
  Java で `as` に当たる確かめないキャストは、型引数への非検査のキャストで、javac が `-Xlint:unchecked` で警告し（`build.gradle:440`）、黙らせるには `@SuppressWarnings("unchecked")` が要る。
  今の `backend/src/main` の参照型のキャストは 4 つで、3 つはラムダの型を決めるキャスト、1 つは型を確かめた後の `CommonColumns.java:113-114` だけである。
  PMD で参照型のキャストを止めると、正しい 3 つを抑えることになる。
  警告をエラーにする `-Werror` は、Error Prone の警告もすべてエラーにするので、この問いの範囲を超える。
- **`domain` の変異テストのゲート**（`testing.md:10`）：mutation test の運用は #299 で比べた。

## コメントへの回答

この Issue にコメントはない。

## ほかの Issue との関係

- **#317、#564、#541（失敗の表し方）**：失敗を例外にするか値にするかは #317 の決定のまま変えない。
  #564 が足す sealed の結果の `switch` には、PMD の規則が効く。
- **#541 と値オブジェクトの例外**：`value-object.md:11` と `:50-52` は、値オブジェクトの不変条件の違反を `IllegalArgumentException`（500）にすると決めている。
  ところが `ordering` の `Quantity`（`Quantity.java:18`）と `Money`（`Money.java:19`）、`payment` の `Money`（`Money.java:16`）は `BusinessRuleViolationException`（422）を投げ、#541 はこれらに 422 の理由を付けるとしている。
  どちらが正しいかは、この Issue では決めない。値オブジェクトのテストを足す作業の Issue は、#541 の後に、その時点の例外の型で書く。
- **ADR-060、ADR-089（ID の採番）**：変えない。
- **ADR-069（モジュール名の予約語）**：辞書の備考に `ordering` と `t_order` の違いの理由として参照する。
- **#299（mutation test の運用）**：`domain` の変異テストのゲートは #299 の範囲である。
- **#327（共通の領域の docs の差分）**：向こうの `docs/db/postgresql-naming-physical.md:20` も、英訳かローマ字かを「チームごとに決める」のままで、辞書の中身は持たない。#327 の比較とは重ならない。

## 限界

- PMD の規則は、PMD 7.23.0 のライブラリを直接呼んで、fixture と今の `backend/src/main`、`backend/src/test` に当てて確かめた。
  `./gradlew pmdMain` では確かめていない。
  enum の判定（`@EnumSwitch`）は型の解決に依るので、Gradle の PMD のタスクが渡す補助のクラスパスで同じに動くことは作業で確かめる。
- 型パターンの `switch` の判定は sealed に限れず、`Object` を振り分ける `switch` の `default` も止める。
- 辞書と `GLOSSARY.md` の一致の検査は、語が両方にあることだけを確かめる。英語の語がコードで使われているかは確かめない。
- 用語集に足す語の定義は、コードと docs から書ける範囲にとどまる。定義の文は利用者が Pull Request で確かめる。

## 確かめたこと

- PMD 7.23.0（こちらの版）の `bestpractices.xml` に `ExhaustiveSwitchHasDefault`（since 7.10.0、XPath は `[@Exhaustive = true()][@DefaultCase = true()]`）がある。
- 同じ版で、14 の fixture（sealed、record パターン、enum の式と文、`String`、`Object`、`case null, default`）に規則を当てた。
  - `ExhaustiveSwitchHasDefault` は、すべての場合と `default` を書いた sealed と enum の 2 つだけを止めた。
  - `NonExhaustiveSwitch` は、一部の定数だけで `default` のない enum の `switch` 文を止めた。
  - 上の XPath は、`default` を持つ sealed、record パターン、enum の式と文、`Object`、`case null, default` の 9 つを止め、`default` のない sealed と enum、`String` の `switch` を通した。
- 上の XPath は、今の `backend/src/main` と `backend/src/test` に違反を出さなかった。
- QuickTheories 0.26 の `integers().between(Integer.MIN_VALUE, 0)` は、5 回の実行のすべてで 999 の例の中に 0 と `Integer.MIN_VALUE` を含めた。
- 同じ版で、拒否の条件 `v < 1` と変異 `v <= 1` に 2 つの性質を当てた。
  拒否する側の性質（`between(Integer.MIN_VALUE, 0)`）は両方で通り、変異は生き残った。
  通る側の性質（`between(1, Integer.MAX_VALUE)`）は変異で失敗した。
- 今の `backend/src/main` の参照型のキャストは、`CommonColumns.java:114`、`EventPublicationResubmitOnce.java:170`、`EventPublicationResubmitter.java:180`、`ResubmissionPolicies.java:72` の 4 つである（PMD の `//CastExpression[not(PrimitiveType)]` で数えた）。
- Maven Central の最新は、QuickTheories 0.26（2019-03 から更新がない）、jqwik 1.10.2、Error Prone 2.53.0、PMD 7.29.0、PIT 1.30.2 である。
- 表の名前は `tenancy.m_tenant`、`m_user`、`m_user_identity`、`m_membership`、`product.m_product`、`ordering.t_order`、`t_order_line`、`payment.t_payment` で、API は `/api/products`、`/api/orders`、`/api/payments` である。
- 向こうの `glossary-mapping.md` と `GLOSSARY.md` の一致を確かめる検査は、向こうのリポジトリにない（`git grep glossary-mapping` は docs だけを返す）。

## 出典

- [oxlint: typescript/switch-exhaustiveness-check](https://oxc.rs/docs/guide/usage/linter/rules/typescript/switch-exhaustiveness-check.html)
- [PMD: ExhaustiveSwitchHasDefault、NonExhaustiveSwitch](https://docs.pmd-code.org/latest/pmd_rules_java_bestpractices.html)、PMD 7.24.0 から 7.29.0 のリリースノート（pmd/pmd の GitHub Releases）
- [Error Prone: UnnecessaryDefaultInEnumSwitch](https://errorprone.info/bugpattern/UnnecessaryDefaultInEnumSwitch)、Error Prone 2.53.0 のリリースノート
- [PIT: Mutators（CONDITIONALS_BOUNDARY）](https://pitest.org/quickstart/mutators/)
- 向こう：`docs/domain-model/index.md`、`types.md`、`glossary-mapping.md`、`testing.md`、`failures.md`、`docs/adr/0027-domain-model-as-pure-functions-and-unions.md`、`vite.config.ts`、`GLOSSARY.md`、`docs/db/postgresql-naming-physical.md`
- こちら：`docs/backend/java-type-modeling.md`、`docs/backend/java-coding.md`、`docs/backend/class-roles/value-object.md`、`aggregate.md`、`docs/agents/domain.md`、`docs/database/postgresql-naming.md`、`GLOSSARY.md`、ADR-012、ADR-060、ADR-062、ADR-069、ADR-076、ADR-089、`backend/config/pmd/ruleset.xml`、`backend/build.gradle`

## 作業の Issue

- #576：用語集の語と英語の識別子の対応を論物変換辞書に置き、用語集との一致を検査する。
- #577：enum と型パターンの switch の default を PMD で止める。
- #578：値オブジェクトの不変条件を境界の両側で確かめ、規約の例を直す。
