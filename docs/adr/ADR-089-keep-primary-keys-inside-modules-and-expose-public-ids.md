---
type: ADR
title: 'ADR-089: 主キーをモジュールのスキーマと Repository の中に閉じ、外に出る値には例外なく public_id を使う'
description: 主キーを同じモジュールのスキーマと Repository の中だけで使い、API、イベント、別のモジュールの列、外部のシステム、ログとトレース、キャッシュのキーには public_id を使う決定。使い分けをカタログの検査、ArchUnit、Spectral で確かめる。
tags: [adr, database, postgresql, backend, web-api]
---

# ADR-089: 主キーをモジュールのスキーマと Repository の中に閉じ、外に出る値には例外なく public_id を使う

## Status

Proposed

## Date

2026-11-17

## Context

[PostgreSQLの主キー](../database/postgresql-primary-keys.md)は、URL と API に連番を出さず、`public_id` の UUID を出すと定めている。
一方で、イベント、別のモジュールの行を指す列、外部のシステム、ログとトレース、キャッシュのキーにどちらの ID を使うかは書いていなかった。
今のコードは、どれにも `public_id` を使っている（`payment.t_payment.order_public_id`、`OrderConfirmed.orderId` など）。
しかし、これはコードの慣習で守られているだけで、検査はない。

[issue #369](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/369) は、テナントで業務のデータを分け、モジュールごとの DB のロールでスキーマの境界を分ける。
連番の主キーがモジュールの外に出ると、次のことが起きる。

- 別のモジュールの表が主キーで行を指すと、主キーの採番の方式や値を変えるときに、そのモジュールの表も直すことになる。
- スキーマをまたぐ外部キーは、参照先のモジュールの行の削除と変更を、参照元のモジュールの制約が止める。モジュールの境界を越えて、二つのモジュールのスキーマが結び付く。
- 連番は、API やログに出た値から件数と作成の順がわかる。

Proposed の間に Decision の検査を書き直した。
ArchUnit と Spectral の初版は型引数と配列の要素を見ず、`List<Long> orderIds` や整数の配列の `orderIds` を通していた。
一度に複数の行を引く引数は、この形をとることが多い。
そのため、名前が `ids` か `Ids` で終わるものと、型引数と配列の要素の数値も検査の対象にした。

## Decision

主キーは、同じモジュールのスキーマの中（テーブルどうしの参照、外部キー、JOIN、インデックス）と、そのモジュールの Repository の中だけで使う。
モジュールの外に出る値（API の引数、本文、URL、モジュール間のイベント、別のモジュールの行を指す列、外部のシステムに渡す値、ログとトレース、キャッシュのキー）には `public_id` を使う。
この使い分けに例外を作らない。
テナントも同じ使い分けに従う。

別のモジュールの行を指す列は、`<対象>_public_id` という名前の `uuid` にする。
スキーマをまたぐ外部キーは作らない。
[ADR-011](ADR-011-use-module-owned-database-schemas.md) は、モジュール間の外部キーを許可するかどうかを、モジュール間のデータの整合性の要件が生じた時点で決めるとしていた。
この決定がその点を決める。

使い分けを、次の三つの検査で確かめる。

- カタログの検査（`SchemaTableConventions`）の規則 P3 は、名前が `_id` で終わる列を、同じスキーマのテーブルの主キーと同じ名前の列か、`uuid` の `public_id` か `<対象>_public_id` に限る。
  規則 P4 は、別のスキーマのテーブルを参照する外部キーを拒否する。
- ArchUnit の `numericIdsDoNotLeaveModules` は、モジュールルート、`domain`、`application`、`infrastructure.client`、`shared`（`shared.infrastructure` を除く）の型に、名前が `id`、`ids` か、`Id`、`Ids` で終わり、型が `long`、`Long`、`int`、`Integer` か、それを型引数や配列の要素に含む（`List<Long>`、`Optional<Long>`、`long[]`）フィールド、引数、戻り値を置かせない。
- Spectral の `public-id-uuid` は、API のパスの引数と、名前が `id` か `Id` で終わるクエリの引数と property を `type: string`、`format: uuid` に限る。
  名前が `ids` か `Ids` で終わるクエリの引数と property は、配列の要素を同じ型に限る。
  トレース ID とスパン ID は行を指す識別子ではなく、UUID でもないため、`traceId` と `spanId` の property とヘッダーの引数は対象にしない。

外部のシステムが採番した識別子を持つ列は行を指さないため、名前を `_code` で終える（`payment.t_payment.gateway_payment_code`）。

ログとトレース、キャッシュのキーは機械で検査せず、レビューで確かめる。

検査を入れた時点で、API の応答の 6 つの property（`OrderDetailsResponse.orderId`、`OrderLineResponse.productId`、`OrderSummaryResponse.orderId`、`PaymentSummaryResponse.orderId` と `paymentId`、`ProductSummaryResponse.productId`）が `format: uuid` を持たず、Spectral の規則に反していた。
値はどれも `public_id` の UUID の文字列であり、Response の record の型を `String` から `UUID` に変えて直した。
JSON の値は変わらない。

## Consequences

### Positive

- 主キーの採番の方式を変えても、変更がモジュールのスキーマと Repository の中で終わる。
- あるモジュールの行の削除と変更を、別のモジュールの外部キーが止めない。
- 主キーの数値を API やイベントに置くと、CI の検査が失敗する。

### Negative

- 別のモジュールの行は、外部キーで参照の整合性を保てない。参照先が消えた行は、アプリケーションで扱う。
- 検査は名前で ID を見分ける。名前が `id`、`ids`、`Id`、`Ids` で終わらない数値の識別子と、名前が `_id` で終わらない列は検査から漏れる。
- ArchUnit は引数の名前を読まないため、規則はリフレクションで引数の名前を読み、コンパイラの `-parameters` に頼る。

### Neutral

- [ADR-060](ADR-060-generate-uuid-primary-keys-in-application.md) は、UUID v7 の主キーを Repository の `nextId()` が発行し、そのインタフェースを `domain.model` に置くと定める。
  主キーを Repository の外に出さないこの決定と合わせるため、UUID v7 を主キーにする最初のテーブルを作るときに、`nextId()` の戻り値を Repository の外で使わない形を決める。
- 同じモジュールの中の外部キーの扱いは、#369 の別の判断で決める。

## Alternatives Considered

### 選択肢1: 別のモジュールの行を主キーで指す

- **Description**：別のモジュールの行を指す列に、その行の bigint の主キーを入れる。
- **Pros**：列が 8 バイトで済み、JOIN の索引が小さい。
- **Cons**：主キーの採番を変えると別のモジュールの表も直すことになる。スキーマをまたぐ外部キーを作る誘因になる。

### 選択肢2: 外部のシステムとログだけ例外にして主キーを出す

- **Description**：調査で引きやすいよう、ログ、トレース、外部のシステムに主キーを出す。
- **Pros**：DB の行を主キーで直接引ける。
- **Cons**：例外の範囲の判断が場所ごとに要り、検査で守れない。ログから漏れた連番で件数や行を推測できる。

### 選択肢3: 規約だけを書き、検査を入れない

- **Description**：使い分けを文書にし、レビューで守る。
- **Pros**：検査の実装と保守が要らない。
- **Cons**：今のように慣習でしか守れない。実際に、API の応答の ID が `format: uuid` を持たないことに、検査を入れるまで気付かなかった。

## References

- [PostgreSQLの主キー](../database/postgresql-primary-keys.md)
- [ADR-011: モジュール所有のデータベーススキーマを使う](ADR-011-use-module-owned-database-schemas.md)
- [ADR-060: UUID の採番をアプリケーションで行い、DB の DEFAULT で採番しない](ADR-060-generate-uuid-primary-keys-in-application.md)
- [issue #369](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/369)
- [issue #372](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/372)
