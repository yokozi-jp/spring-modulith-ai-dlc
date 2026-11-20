# Markdown の設計書の規約を取り込むか（#324 の調査）

この文書は #324 の調査の記録で、merge しない。
結論は #324 のコメントと同じで、作業は #570 と #558 で行う。
向こうは `yokozi-jp/agent-software-factory`（`origin/main`、commit `29ba897`）、こちらは `yokozi-jp/spring-modulith-ai-dlc`（main、commit `63e5c9f`）である。

## 結論

一部採用とする。

向こうの `docs/markdown-design-docs/` とこちらの `docs/writing/design-doc-*.md` は、どちらもフューチャー株式会社の「Markdown設計ドキュメント規約」を元にしている。
向こうは原文をほぼそのまま 13 の文書に分け、記入例をそのまま残した。
こちらは ADR-040 に従って書き直し、情報ごとに正本を決めて、正本にある内容を設計書へ写さない形にした（`docs/writing/design-doc-sources-of-truth.md:10-12`）。
そのため、設計書の構成（処理概要、処理シーケンス、ビジネスロジック、DB 項目）、画面の見た目をデザインツールに任せること、Web API の設計書で OpenAPI と重なる項目を書かないことは、同じ決定になっている。

向こうの記入例のうち、次の 2 つを取り込む。

- **I/F 定義書の連携ごとの決めごとと項目定義の属性**：向こうの I/F 定義書の記入例（`interface-definition.md:58-96`）は、全件か差分か、起動の時刻、処理完了の期限、保持期限と、項目ごとの型、桁、精度、区分値、形式、機密、例を書く。
  こちらのファイル定義書（`docs/integration/interface-documentation.md:28-37`）は、ほかの I/F の規約が「決める」「合意する」と定めた事項の一部しか挙げず、項目定義に書く属性を決めていない。
  こちらは列の多い表を Markdown で書かない（`design-doc-sources-of-truth.md:51-57`）ので、ファイル定義書は Open Data Contract Standard（ODCS）v3.2.0 の YAML で書く。
  あわせて、`interface-documentation.md:37` が挙げる Data Contract Specification は提供元が非推奨にしたので、ODCS に置き換える。
- **バッチの設計書の単位**：向こうはバッチと非同期処理のプログラム設計書を機能 ID の単位で作る（`program-design-batch.md:14`）。
  こちらの設計書の単位（`design-doc-feature-specs.md:17-22`）にはバッチがない。
  #558 で業務のバッチを Spring Batch の Job にすると決めたので、単位に「バッチ」を足し、Job の名前を識別子にする。

テーブル定義書を A5:SQL Mk-2 の `erd.a5er` で持つこと、区分値設計書とメッセージ定義書を表で作ること、`openapi.yaml` を手で書くこと、PlantUML を使うこと、列の多い表に Excel を使うこと、`docs` の下を `01_` の番号で並べることは、取り込まない。

## 比べたもの

| 向こう                      | こちら                                                                                     |
| --------------------------- | ------------------------------------------------------------------------------------------ |
| `folder-structure.md`       | `docs/index.md`、ADR-036（OKF のバンドル）                                                 |
| `text-based-caution.md`     | `docs/writing/design-doc-sources-of-truth.md` の「列の多い表」                             |
| `diagrams.md`               | `docs/writing/design-doc-diagrams.md`                                                      |
| `frontend.md`               | `design-doc-sources-of-truth.md:30`、`design-doc-feature-specs.md:24-32`                   |
| `table-definition.md`       | `design-doc-sources-of-truth.md:25,40-41`、`docs/database/migrations.md`                   |
| `enum-values.md`            | `design-doc-sources-of-truth.md:26-27`、`docs/database/postgresql-logical-design.md:42-53` |
| `message-definition.md`     | `design-doc-sources-of-truth.md:28`、ADR-016                                               |
| `web-api-definition.md`     | `design-doc-sources-of-truth.md:23`、ADR-013、ADR-052、`openapi/openapi.yaml`              |
| `program-design-web-api.md` | `design-doc-feature-specs.md:49-57`                                                        |
| `program-design-batch.md`   | `design-doc-feature-specs.md:34-47`                                                        |
| `interface-definition.md`   | `docs/integration/interface-documentation.md`、`design-doc-feature-specs.md:59-63`         |

## 原文の版

こちらが取り込んだ原文は commit `e309a6d`（2026-09-30）に固定している（ADR-040:44）。
原文のリポジトリの `documents/forMarkdown` を最後に変えた commit は `e912afc`（2026-08-22、表記の統一）で、`e309a6d` より前である。
`e309a6d` から原文の `main` までの差分に、`forMarkdown` と `forIF` のファイルはない（`gh api repos/future-architect/arch-guidelines/compare/e309a6d...main`）。
そのため、原文の更新でこの比較が変わることはない。

向こうは、I/F 定義書の記入例の CSV のレイアウトの参照を RFC 8259 から RFC 4180 に直している（向こうの commit `060e33e`、`about.md:30`）。
こちらは記入例を写さず、CSV の形式を `docs/integration/interface-file-format.md:10` で RFC 4180 と決めているので、同じ誤りはない。

## 同じ決定になっているもの

- **正本にある内容を設計書へ写さない**：向こうの Web API のプログラム設計書は、`openapi.yaml` と重なる自明な内容を省く（`program-design-web-api.md:22`）。
  こちらは、これを API、テーブル、区分値、メッセージ、画面の見た目、連携ファイルに広げ、情報ごとの正本を決めている（`design-doc-sources-of-truth.md:21-31`）。
- **処理の設計書の構成**：向こうのバッチのプログラム設計書（`program-design-batch.md:14,23-89`）と I/F 定義書（`interface-definition.md:120-178`）は、処理概要、処理シーケンス、ビジネスロジック、DB 項目（取得、登録、更新）を書く。
  こちらは同じ 4 節を Web API、非同期処理、I/F に使い、削除と「なし」の書き方を足している（`design-doc-feature-specs.md:36-47`）。
- **Web API の応答項目の由来**：向こうの「Web API応答例」の表（`program-design-web-api.md:24-41`）は、こちらの「応答項目の由来」（`design-doc-feature-specs.md:49-57`）と同じ列と同じ省略の規則を持つ。
- **画面の見た目はデザインツールに任せる**：向こう（`frontend.md:22-24`）とこちら（`design-doc-feature-specs.md:24-32`）は同じである。
  こちらは Figma と名指さず「デザインツールのファイル」と書く。
- **I/F のエラー処理**：向こうの I/F 定義書の「エラー処理」の表（`interface-definition.md:174-178`）は、こちらの「エラー処理」の節（`design-doc-feature-specs.md:63`）と同じである。
- **diagrams.net の図**：向こう（`diagrams.md:46-52`）とこちら（`design-doc-diagrams.md:30-36`）は、同じ 3 つの拡張子を使う。
  こちらは、テキストで差分を読める `.drawio.svg` を既定にしている。
- **保持期限と個人情報の有無をテーブル定義とは別に持つ**：向こう（`table-definition.md:22-25`）とこちら（`design-doc-sources-of-truth.md:40-41`）は同じである。
  ただし、こちらの置き場所には食い違いがある（下の「限界」）。

## 取り込むもの

### I/F のファイル定義書

向こうの I/F 定義書の記入例は、連携ごとの決めごとを「連携元定義」と「連携先定義」の表に、項目ごとの属性を「項目定義」の表に書く（`interface-definition.md:58-96`）。
こちらのファイル定義書が挙げる事項（`interface-documentation.md:30-37`）と比べると、次の差がある。

- **全件か差分か**：こちらは連携ごとに選ぶ（`interface-full-and-delta.md:20-22`）が、ファイル定義書の書く事項にない。
- **起動時間と処理完了期限**：こちらは配信の最大遅延時刻を定める（`interface-file-scheduling.md:36`）。I/F 一覧の「頻度と時刻」に当たるが、最大遅延時刻とは書いていない。
- **未着チェック**：こちらは問い合わせ先、エスカレーション、手動の再送の手順を連携先と決める（`interface-arrival-deadline.md:34`）。I/F 一覧は門限チェックの有無だけを書く。
- **0 件時連携**：こちらは、相手が空ファイルを配信できないときの扱いを連携先と合意する（`interface-file-layout.md:26`）が、ファイル定義書の書く事項にない。
- **保持期限**：こちらは保持期間をファイルごとに決める（`interface-file-retention.md:24`）が、ファイル定義書の書く事項にない。
- **項目定義の型、桁、精度、区分値、形式**：こちらの集信側は型、桁、必須、区分値、形式、範囲を検証する（`interface-receiving-validation.md:16`）。ファイル定義書は「項目定義」とだけ書き、どの属性を書くかを決めていない。

文字コード、改行コード、ヘッダー行、圧縮は、こちらは連携ごとに選ばず、`interface-file-format.md` で一律に決めている。
これらはファイル定義書に書かず、固定長やレガシーな文字コードのように規約から外れるときだけ書けばよい。

項目定義の属性を全部並べると、向こうの記入例は 10 列の表になる（`interface-definition.md:82`）。
こちらは 1 行が画面の幅に収まらない表を Markdown で書かず、機械で読めるファイルを正本にする（`design-doc-sources-of-truth.md:51-57`）。
`interface-documentation.md:37` は、そのファイルとして JSON Schema と Data Contract Specification を「渡してもよい」と書いている。

Data Contract Specification の提供元は、Open Data Contract Standard（ODCS）v3.1.0 の公開に合わせてこの仕様を非推奨にし、ODCS への移行を勧めている。
Data Contract CLI での対応は 2026 年の末までである（datacontract/datacontract-specification の README の Deprecation Notice）。
ODCS の最新版は v3.2.0（2026-09-08）である。

ODCS v3.2.0 は、向こうの記入例の項目とこちらの検証の観点を、次のフィールドで表せる。

- 型、桁、精度：`logicalType`、`physicalType`（`varchar(18)`、`numeric(10,2)` など）、`logicalTypeOptions.maxLength`
- 必須：`required`（向こうの記入例にはない）
- 区分値：`quality` の `invalidValues` と `validValues`
- 形式と範囲：`logicalTypeOptions.pattern`、`format`、`minimum`、`maximum`
- 機密：`classification`
- 例：`examples`
- 主キー：`primaryKey`、`primaryKeyPosition`（`interface-file-layout.md` の「調査用の項目」）
- 置き場所と形式：`servers` の `s3` と `format`
- 頻度、門限、保持期限：`slaProperties` の `frequency`、`timeOfAvailability`、`retention`

Data Contract CLI（v1.2.11）は ODCS を読み、`lint`、`test`、`breaking`、`changelog`、`export html` を持つ。
`breaking` は、こちらが OpenAPI の契約で oasdiff に任せている破壊的変更の検査（ADR-052）と同じ役割を、連携ファイルの契約で持てる。
JSON Schema は JSON の構造を表す仕様で、CSV の列、置き場所、頻度、保持期限を表すフィールドを持たない。

ファイル定義書を ODCS の YAML で書くと、向こうの記入例の項目を落とさずに、列の多い表を Markdown で書かない規則を守れる。
自作の表の形を決めるより、公開された標準とその道具に寄せられる。
ファイル定義書は連携先に渡す契約で、渡した後は形を変えにくいので、#570 で新しい ADR を起こして決める。

今のこちらには、ファイル連携の I/F が 1 つもない（`docs/` に I/F 一覧もファイル定義書もない）。
そのため、`datacontract lint` を CI に足すのは最初のファイル定義書を足すときになる。
検査する対象が今はないので、今 CI に足しても確かめられることがない（判断）。

### バッチの設計書の単位

向こうのプログラム設計書は「BAT01」のような機能 ID を見出しに付け、バッチと非同期処理を同じ形で書く（`program-design-batch.md:23`）。
こちらの設計書の単位は、画面、Web API、非同期処理、I/F の 4 つで、どれも正本にある識別子を見出しに付ける（`design-doc-feature-specs.md:17-22`）。
業務のバッチ（締め、集計、ファイルの配信と集信）は、#558 で Spring Batch の Job にすると決めた。
Job の名前は JobRepository の `BATCH_JOB_INSTANCE.JOB_NAME` に残り、起動と再開と実行の履歴で使う識別子になる。
そこで、設計書の単位に「バッチ」を足し、識別子を Job の名前にする。
処理の設計書の 4 節（`design-doc-feature-specs.md:36`）の対象にもバッチを足す。

この変更は #558 が `docs/batch/` を作るときに一緒に行うのが小さい。
新しい Issue は起こさず、#558 にコメントで足した。

## 取り込まないもの

- **テーブル定義書を A5:SQL Mk-2 の `erd.a5er` で持つ**（`table-definition.md:20`）：こちらは Liquibase の changeset を正本にし、テーブル定義書を別に作らない（`design-doc-sources-of-truth.md:25`）。
  ER 図が要るときは、changeset を正本とする補足として Mermaid で書く（`design-doc-sources-of-truth.md:43-49`）。
  `erd.a5er` を足すと、changeset と同じ情報を二重に持ち、食い違ったときにどちらが正しいかが決まらない。
- **区分値設計書**（`enum-values.md:24-46`）：向こうの記入例は区分値を `01` のようなコード値にし、論理名と物理名と値の表を書く。
  こちらは enum の定数名を DB と API にそのまま持ち（ADR-065、`postgresql-logical-design.md:46`）、対応はソースコードを正本にする（`postgresql-logical-design.md:49`）。
  設計書には、区分値を入れた目的と区分値で分岐する業務ルールだけを書く（`design-doc-sources-of-truth.md:39`）。
- **メッセージ定義書**（`message-definition.md:22-31`）：こちらは API の文言を `MessageSource` と Problem Details の `type` で持ち（ADR-016、ADR-013）、画面の文言をフロントエンドの message catalog で持つ（`design-doc-sources-of-truth.md:27-28`）。
  向こうの記入例の「識別子とステータス」の組は、こちらでは Problem Details の `type` と HTTP の状態コードに当たり、#309 と #541 で失敗ごとの `type` を決めている。
- **`openapi.yaml` を手で書く**（`web-api-definition.md:19-21`）：こちらは springdoc-openapi がコードから契約を生成し、`openapi/openapi.yaml` を生成物としてコミットする（ADR-013、ADR-052）。
  使っている springdoc-openapi 3.2.0 は、Maven Central の最新版と同じである。
  向こうが参照する「OpenAPI Specification 3.0.3 規約」は、こちらの契約の版（`openapi/openapi.yaml:1` の 3.1.0）と合わない。
- **PlantUML**（`diagrams.md:20-44`）：GitHub が Markdown の中で描画する図の記法は Mermaid、GeoJSON、TopoJSON、ASCII STL で、PlantUML は入っていない（GitHub Docs の「Creating diagrams」）。
  こちらは図を Mermaid に揃えており（`design-doc-diagrams.md:10-11`）、`docs/` に PlantUML の図はない。
  PlantUML を許すと、Pull Request の画面で図を読めなくなる。
- **列の多い表に Excel を使う**（`text-based-caution.md:26`）：こちらは、列の多い表を機械で読めるファイルへのリンクに変えるか、表を分ける（`design-doc-sources-of-truth.md:53-57`）。
  Excel は `git diff` で変更を読めず、こちらの検査（`task lint-md`、`task okf-check`）の対象にもならない。
- **`docs` の下を `01_` の番号で並べる**（`folder-structure.md:32-55`）：こちらは `docs/` を OKF のバンドルにし、領域ごとの `index.md` に「いつ読むか」を書いて入口にしている（ADR-036、ADR-038）。
  番号で並べる目的（新しく入った人が先に読むものを先頭に置く）は、`docs/index.md` と各 `index.md` の行が持つ。
- **向こうの記入例の値**：I/F 定義書の記入例の改行コード LF（`interface-definition.md:73`）は、こちらの CRLF の規約（`interface-file-format.md:57`）と違う。
  これは記入例の値で、方針の違いではない。

## ほかの Issue との関係

- **#558（業務のバッチを Spring Batch で作る）**：上の「バッチの設計書の単位」を #558 にコメントで足した。
  Job の名前の付け方は #558 の ADR で決まるので、設計書の識別子はそれに従う。
- **#309、#541（Problem Details の type）**：メッセージ定義書を取り込まない理由の一部で、`type` が失敗の識別子の正本になる。
- **#312、#560、#561（イベントのスキーマ）**：非同期処理の設計書の単位はイベント型名のままで、変えない（`design-doc-feature-specs.md:21`）。
  モジュールの間のイベントはアプリの中の契約で、ファイル定義書を ODCS で書く対象ではない（`interface-integration-patterns.md` の「対象」が、同じアプリケーションの中のモジュール間の連携を外している）。
- **#542（完了した出版の archive を保持期限で消す）**：テーブルの保持期限の一覧（下の「限界」）に、`event_publication_archive` の保持期限が入る。

## 限界

- こちらには、ファイル連携の I/F がまだない。
  ODCS で項目定義、置き場所、頻度、保持期限を書けることは仕様のフィールドで確かめたが、実際のファイル定義書を書いて `datacontract lint` と `datacontract test` を流してはいない。
- `design-doc-sources-of-truth.md:40-41` は、テーブルの保持期限と個人情報の有無を「PostgreSQL のパーティションと改廃の保持期限の一覧」に書くとしている。
  しかし、その文書が一覧に書く項目（`postgresql-partitioning-and-retention.md:74`）に個人情報の有無はなく、一覧そのものもリポジトリにない。
  向こうにもこの一覧はない。
  これは向こうから取り込むものではなく、こちらの docs の食い違いなので、この Issue の作業には入れない。
- 向こうの `docs/markdown-design-docs/` を使って書いた設計書は、向こうにもない（`# IF01`、`# BAT01`、`# ENUM01` で始まる文書がない）。
  向こうの文書は、ほかの文書から記入例として参照されるだけである（向こうの `docs/interface/documentation.md:60`、`docs/async/documentation.md:222`）。
  そのため、比べたのは向こうの規約と記入例で、運用の結果ではない。

## 確かめたこと

向こうのリポジトリ（commit `29ba897`）で、次を確かめた。

- `docs/markdown-design-docs/` は 13 文書、670 行で、ADR から参照されていない。
- 最後の変更は commit `060e33e`（RFC 8259 を RFC 4180 に直した）である。

原文のリポジトリ（future-architect/arch-guidelines）で、次を確かめた。

- `e309a6d` の日付は 2026-09-30 で、`documents/forMarkdown` の最後の変更 `e912afc`（2026-08-22）より後である。
- `e309a6d...main` の差分に `forMarkdown` と `forIF` のファイルはない。

こちらのリポジトリ（main、commit `63e5c9f`）で、次を確かめた。

- `docs/` に PlantUML の図はなく、Mermaid の図を持つ文書はある。
- `docs/` にファイル連携の I/F 一覧とファイル定義書はない。
- `postgresql-partitioning-and-retention.md:74` の一覧の項目に個人情報の有無はなく、その一覧の文書もない。
- `backend/build.gradle:80` の springdoc-openapi は 3.2.0 で、Maven Central の `maven-metadata.xml` の `release` も 3.2.0 である。

外部の仕様と道具で、次を確かめた。

- datacontract/datacontract-specification の README に、ODCS v3.1.0 の公開に合わせた非推奨の告知と、2026 年の末までの対応がある。最後のリリースは v1.2.0（2025-07-05）である。
- bitol-io/open-data-contract-standard の最新のリリースは v3.2.0（2026-09-08）である。
  `docs/schema.md` に `required`、`classification`、`examples`、`logicalTypeOptions`（`maxLength`、`pattern`、`minimum`）、`primaryKey` があり、`docs/data-quality.md` に `invalidValues` と `validValues` があり、`docs/service-level-agreement.md` に `retention`、`frequency`、`timeOfAvailability` があり、`docs/infrastructure-servers.md` に S3 のサーバーと `format` がある。
- datacontract/datacontract-cli の最新のリリースは v1.2.11（2026-11-17）で、README に ODCS の `lint`、`test`、`breaking`、`changelog`、`export html` がある。
- GitHub Docs の「Creating diagrams」は、描画できる記法を Mermaid、GeoJSON、TopoJSON、ASCII STL としている。

## 出典

- フューチャー株式会社「Markdown設計ドキュメント規約」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forMarkdown/markdown_design_document.html)）、[future-architect/arch-guidelines](https://github.com/future-architect/arch-guidelines)
- [Open Data Contract Standard](https://github.com/bitol-io/open-data-contract-standard)（v3.2.0）
- [Data Contract Specification](https://github.com/datacontract/datacontract-specification)（Deprecation Notice）
- [Data Contract CLI](https://github.com/datacontract/datacontract-cli)（v1.2.11）
- [Creating diagrams（GitHub Docs）](https://docs.github.com/en/get-started/writing-on-github/working-with-advanced-formatting/creating-diagrams)
- [springdoc-openapi-starter-webmvc-ui の maven-metadata.xml](https://repo1.maven.org/maven2/org/springdoc/springdoc-openapi-starter-webmvc-ui/maven-metadata.xml)
- 向こう：`docs/markdown-design-docs/`、`docs/interface/documentation.md`、`docs/async/documentation.md`、commit `060e33e`
- こちら：ADR-013、ADR-016、ADR-036、ADR-038、ADR-040、ADR-052、ADR-065、`docs/writing/design-doc-sources-of-truth.md`、`docs/writing/design-doc-feature-specs.md`、`docs/writing/design-doc-diagrams.md`、`docs/integration/interface-documentation.md`、`docs/integration/interface-*.md`、`docs/database/postgresql-logical-design.md`、`docs/database/postgresql-partitioning-and-retention.md`、`openapi/openapi.yaml`

## 作業の Issue

- #570：I/F のファイル定義書を ODCS で書き、連携ごとに決める事項を足す。
- #558：設計書の単位にバッチ（Spring Batch の Job の名前）を足す（コメントで足した）。
