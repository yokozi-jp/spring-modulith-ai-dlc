---
type: ADR
title: 'ADR-060: UUID の採番をアプリケーションで行い、DB の DEFAULT で採番しない'
description: UUID の採番をアプリケーションで行い、uuid カラムの DEFAULT を禁止して機械で検査する決定。永続化用の主キーは Repository の nextId() が、Domain 上の公開用 ID の public_id は Domain のファクトリが採番する。public_id は UUID v4、UUID v7 はシャーディングを前提にする主キーだけに使い、v7 の生成の実装は最初のテーブルを作るときに決める。
tags: [adr, database, postgresql, backend]
---

# ADR-060: UUID の採番をアプリケーションで行い、DB の DEFAULT で採番しない

## Status

Proposed

## Date

2026-10-05

## Context

[PostgreSQLの主キー](../database/postgresql-primary-keys.md)は、単一の DB では bigint の IDENTITY を使い、シャーディングを前提にする場合は UUID v7 を使うと定めている。
現在、UUID を主キーにするテーブルはない。
テーブルができてから採番の責務を決めると、Repository、Domain、テストの形が後から変わるため、先に方針を決める。

[RFC 9562](https://www.rfc-editor.org/rfc/rfc9562.html) の第 8 節は、UUID を推測されにくい値やセキュリティ上の capability として扱ってはならないとする。
UUID v7 はミリ秒の時刻を含むため、値から作成の順序と時刻が見える。
セキュリティに関わる用途には v4 を使うよう、同じ節は求めている。
したがって、外部に出す `public_id` は v4 にし、v7 は外部に出さない内部の主キーに限る。

このリポジトリの PostgreSQL は 18.6 であり（`docker/compose.yml`）、[`uuidv7()`](https://www.postgresql.org/docs/18/functions-uuid.html) が使える。
Java は 25 で、UUID v7 を作る標準の API がない。

## Decision

UUID の採番はアプリケーションで行い、DB では行わない。
`uuid` カラムに `DEFAULT` を付けず、`uuidv7()` と `gen_random_uuid()` を schema に書かない。
理由は、ID のライフサイクルを INSERT の前に持ってくるためである。
ID が INSERT の前に決まれば、イベントの発行や子テーブルの外部キーに、保存を待たずに ID を使える。

採番の責務は、ID の役割で分ける。
永続化用の主キーは Repository が発行する。
Domain 上の外部公開識別子である `public_id` は Domain が発行する。
どちらも DB の `DEFAULT` には任せない。

Java の ID の値型は `java.util.UUID` にする。
jOOQ は PostgreSQL の `uuid` を `UUID` に直接対応づける。

主キーの次の値は Repository の `XxxId nextId()` から得る。
UUID v7 の生成には時刻などインフラ側の要素が関わる見込みであり、Domain は `shared` に依存できないため、主キーの採番は Repository の背後に置く。
インタフェースは `domain.model` に、実装は `infrastructure.persistence` の `Jooq*Repository` に置く。
static メソッドの `OrderId.newId()` は廃止する。

`public_id` は UUID v4 とする。
集約を作る Domain のファクトリが `UUID.randomUUID()` で採番し、値オブジェクトで包む。
テストは値を固定せず、null でないことと `version()` が 4 であることを確かめる。
値を外から渡すオーバーロードは作らない。

UUID v7 は、シャーディングを前提にするテーブルの主キーだけに使う。
そのようなテーブルはまだない。

`uuid` カラムの `DEFAULT` を禁止する検査を、`SchemaTableConventions.columns` の規則 T13 として加える。
Spring Modulith のイベント出版テーブルは `modulith` スキーマにあり、フレームワークが定めるため対象にしない。

UUID v7 の生成の実装は決めていない。
UUID v7 を主キーにする最初のテーブルを作るときに決める。

## Consequences

### Positive

- ID が永続化の前に分かるため、イベントや子テーブルの外部キーの値に使える。
- 主キーの採番の経路が Repository の `nextId()` 一つになる。
- 結果として、採番に DB が要らず、DB なしでテストできる。
- `DEFAULT` による DB 採番の混入を、機械で防げる。

### Negative

- 集約ごとに Repository が `nextId()` を実装する。
- `uuid` カラムの `DEFAULT` を使う手軽さを失う。
- 境界の `String` を `UUID.fromString` で変換するため、不正な値は 500 になる。
  400 への対応づけは [ADR-062](ADR-062-map-business-exceptions-to-404-409-422.md) の範囲外とし、別に決める。
- UUID v7 の生成の実装が決まるまで、v7 の主キーの `nextId()` は実装できない。

### Neutral

- UUID v7 を主キーにする最初のテーブルを作るときに、生成の方法を決める後続の判断が要る。
  その時点で ADR を追加するか、この ADR を更新する。

## Alternatives Considered

### 選択肢1: DB の `uuidv7()` を DEFAULT にする

- **Description**：`uuid` カラムに `DEFAULT uuidv7()` を付け、INSERT 時に DB が採番する。
- **Pros**：アプリケーションに生成の実装が要らない。
- **Cons**：ID が INSERT の後まで分からず、イベントや子の外部キーに使えない。DB なしのテストで ID を作れない。

### 選択肢2: アプリケーションと DB の両方で採番する

- **Description**：アプリケーションが値を渡し、渡さない場合は DB の DEFAULT が採番する。
- **Pros**：採番し忘れても INSERT が失敗しない。
- **Cons**：採番の経路が二つになり、どちらで採番されたか分からない。

### 選択肢3: ID の型を `String` にする

- **Description**：`uuid` カラムの値を Java の `String` で持つ。
- **Pros**：境界の `String` とそのまま対応する。
- **Cons**：型で UUID の形を表せず、不正な値を DB の手前で防げない。

### 選択肢4: `public_id` にも UUID v7 を使う

- **Description**：外部に出す ID も v7 にそろえる。
- **Pros**：生成の方法が一つで済む。
- **Cons**：作成の順序と時刻が外部から見える。RFC 9562 がセキュリティ用途に v4 を求めることに反する。

## References

- [RFC 9562: Universally Unique IDentifiers (UUIDs)](https://www.rfc-editor.org/rfc/rfc9562.html)
- [PostgreSQL 18: UUID Functions](https://www.postgresql.org/docs/18/functions-uuid.html)
- [PostgreSQLの主キー](../database/postgresql-primary-keys.md)
- [ADR-040: Future のアーキテクチャ設計ガイドラインを書き直して docs に取り込む](ADR-040-import-future-architecture-guidelines.md)
- [ADR-050: バックエンドのクラスの役割と命名を定める](ADR-050-define-backend-class-roles-and-naming.md)
- [issue #107](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/107)
