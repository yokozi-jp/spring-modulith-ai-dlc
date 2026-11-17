---
type: Convention
title: PostgreSQLの主キー
description: サロゲートキーとナチュラルキーの選び方、複合主キーの扱い、連番とUUID v7の使い分け、UUIDの採番、公開用IDの持ち方、主キーと公開用IDの使い分けを定める規約。テーブルの主キーを決めるとき、IDをURLやAPI、イベント、別のモジュールの列に出すときに読む。
tags: [convention, database, postgresql, primary-key, uuid, future-arch-guidelines]
---

# PostgreSQLの主キー

トランの主キーはサロゲートキーにし、マスタは業務キーが変わらないと確信できる場合だけナチュラルキーにする。
複合主キーは使わない。
単一DBでは連番を使い、シャーディングを前提にする場合はUUID v7を使う。
主キーはモジュールのスキーマとRepositoryの中だけで使い、モジュールの外に出る値には例外なく`public_id`を使う。

## 対象

この規約は、モジュールが所有する業務テーブルに適用する。
Spring Modulithのイベント出版テーブルのように、フレームワークがスキーマを定めるテーブルには適用しない。

## サロゲートキーとナチュラルキー

- **ナチュラルキー**：業務上一意になる項目の組み合わせを主キーにしたもの。ビジネスキーとも呼ぶ。
- **サロゲートキー**：連番やUUIDを採番して主キーにしたもの。

トランの主キーはサロゲートキーにする。
「得意先コードと連番」で一意にできる受注でも、受注IDを採番する。

マスタの主キーは、確信が持てなければサロゲートキーにする。
次のどれかに当てはまる場合はサロゲートキーにする。

- 合併やブランド再編で業務キーのコード体系が変わる可能性がある。
- 仮説検証を繰り返す開発で、業務キーの構成が変わる可能性がある。
- 複数の事業部がそれぞれナチュラルキーを採番しており、意図しない重複が起こりうる。

社員番号を再利用しない会社の社員マスタのように、業務キーが変わらないと確信できる場合だけナチュラルキーを主キーにする。
サロゲートキーにした場合も、ナチュラルキーにはユニークインデックスを張る。

## 複合主キー

複合主キーは使わない。
適用期間付きのマスタも、別のサロゲートキーを主キーにし、業務キーと適用終了日の組にユニークインデックスを張る。
たとえば商品マスタは商品IDを主キーにし、商品コードと適用終了日をユニークにする。

## 連番とUUID

- **連番**：`bigint`のIDENTITY列でDBが採番する。64ビットで済むが、採番がDBに集中し、値から事業規模を推測されうる。
- **UUID v7**：時刻順に並ぶUUID。アプリケーションで採番でき、DBへアクセスせずにキーを確定できる。128ビットになる。PostgreSQL 18には`uuidv7()`があるが、このリポジトリでは使わない。

単一のDBまたはDBクラスタを前提にする場合は、連番を使う。
将来シャーディングを前提にする場合は、UUID v7を使う。
生成順とソート順が一致せずB-treeへの挿入効率が下がるため、UUID v1とv4は主キーに使わない。

IDENTITY列の定義は[PostgreSQLのデータ型](postgresql-data-types.md#identity列)に従う。

## UUIDの採番

UUIDの採番はアプリケーションで行い、DBでは行わない。
`uuid`カラムに`DEFAULT`を付けず、`uuidv7()`と`gen_random_uuid()`を使わない。
IDをINSERTの前に確定し、イベントや子テーブルの外部キーに保存を待たず使えるようにするためである。
永続化用の主キーはRepositoryが発行し、Domain上の外部公開識別子である`public_id`はDomainが発行する。
主キーの次の値はRepositoryの`nextId()`から得る。
インタフェースは`domain.model`に、実装は`infrastructure.persistence`の`Jooq<Aggregate>Repository`に置く。
Javaの型は`java.util.UUID`にし、`String`にしない。
jOOQは`uuid`を`UUID`に対応づける。

`uuid`カラムの`DEFAULT`は、`SchemaConventionsTest`と`SchemaInspectionTest`が使う`SchemaTableConventions.columns`（規則T13）が検査する。
Spring Modulithのイベント出版テーブルは対象にしない。

UUID v7の生成方法は決めていない。
UUID v7を主キーにする最初のテーブルを作るときに決める。
理由は[ADR-060](../adr/ADR-060-generate-uuid-primary-keys-in-application.md)に示す。

## 公開用ID

インターネットに公開するURLやAPIに連番を出さない。
連番の主キーとは別に`public_id`のカラムを作ってUUIDを格納し、ユニークインデックスを張る。
URLにはUUIDをBase64などで短く変換した値を使ってよい。

`public_id`はUUID v4とし、集約を作るDomainのファクトリで`UUID.randomUUID()`により採番して、値オブジェクト（たとえば[値オブジェクト](../backend/class-roles/value-object.md)の`OrderId(UUID value)`）で包む。
`public_id`は主キーではないカラムなので、v4でよい。
UUID v7は作成時刻が値に含まれて見えるため、公開用IDに使わない（[RFC 9562](https://www.rfc-editor.org/rfc/rfc9562.html)）。
テストは値を固定せず、nullでないことと`version()`が4であることを確かめる。
値を外から渡すオーバーロードは作らない。

## 主キーと公開用IDの使い分け

主キーは、同じモジュールのスキーマの中（テーブルどうしの参照、外部キー、JOIN、インデックス）と、そのモジュールのRepositoryの中だけで使う。
モジュールの外に出る次の値には、`public_id`を使う。

- APIの引数、本文、URL
- モジュール間のイベント
- 別のモジュールの行を指す列
- 外部のシステムに渡す値
- ログとトレース
- キャッシュのキー

この使い分けに例外を作らない。
今後加えるテナントのような横断の概念も、テーブル、イベント、ログ、トレース、キャッシュのキー、APIのすべてで`public_id`を使う。
理由は[ADR-089](../adr/ADR-089-keep-primary-keys-inside-modules-and-expose-public-ids.md)に示す。

同じモジュールのテーブルどうしは、主キーの列で参照する（`ordering.t_order_line.order_id`）。
別のモジュールの行を指す列は、`<対象>_public_id`という名前の`uuid`にする（`payment.t_payment.order_public_id`）。
スキーマをまたぐ外部キーは作らない。
外部のシステムが採番した識別子を持つ列は、行を指さないため、名前を`_id`ではなく`_code`で終える（`payment.t_payment.gateway_payment_code`）。

トレースIDとスパンID（`traceId`、`spanId`）は、行を指す識別子ではないため、この使い分けの対象にしない。
トレースIDは32桁の16進数であり、UUIDではない。
Problem Detailsの`traceId`拡張は[ADR-013](../adr/ADR-013-standardize-http-api-contracts.md)が、ログの`traceId`と`spanId`は[ADR-015](../adr/ADR-015-structure-and-protect-observability-data.md)が定める。
ログとトレースに行の識別子を載せるときは、`public_id`を使う。

Javaでは、主キーの値をRepositoryの外に返さない。
モジュールルートのイベントと公開API、Domain、Application、`infrastructure.client`の型は、`public_id`のUUIDか、それを包む値オブジェクト（`OrderId(UUID value)`）でIDを持つ。

### 使い分けの検査

使い分けは次の検査が確かめる。
ログとトレース、キャッシュのキーは機械で検査せず、レビューで確かめる。

- **カタログの検査**：`SchemaConventionsTest`と`SchemaInspectionTest`が使う`SchemaTableConventions`が、業務スキーマを検査する。
  規則P3は、名前が`_id`で終わる列を、同じスキーマのテーブルの主キーと同じ名前の列か、`uuid`の`public_id`か`<対象>_public_id`に限る。
  共通カラムの`created_tx_id`、`updated_tx_id`、`patched_id`は行を指さないため対象にしない。
  外部のシステムの識別子を`_id`で終わる列に入れるとP3が拒否するため、`_code`で終わる名前にする。
  規則P4は、別のスキーマのテーブルを参照する外部キーを拒否する。
- **ArchUnit**：`PublicIdArchTest`の`numericIdsDoNotLeaveModules`が、モジュールルート、`domain`、`application`、`infrastructure.client`、`shared`（`shared.infrastructure`を除く）の型に、名前が`id`か`Id`で終わる`long`、`Long`、`int`、`Integer`のフィールド、引数、戻り値がないことを確かめる。
- **Spectral**：`.spectral.yaml`の`public-id-uuid`が、パスの引数と、名前が`id`か`Id`で終わるクエリの引数とpropertyを`type: string`、`format: uuid`に限る。
  ヘッダーの引数と、`traceId`と`spanId`のpropertyは対象にしない。

## 出典

- フューチャー株式会社「PostgreSQL設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forDB/postgresql_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
