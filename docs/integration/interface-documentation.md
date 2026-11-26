---
type: Convention
title: 連携の一覧と定義書
description: システムが持つ連携の一覧に書く項目と、連携ファイルの定義書を ODCS の YAML で書くときの置き場所、項目ごとの属性、連携ごとの事項と ODCS のフィールドの対応を定める規約。連携を追加または変更するとき、連携先とファイルの仕様を合意するときに読む。
tags: [convention, integration, interface, documentation, odcs, future-arch-guidelines]
---

# 連携の一覧と定義書

システムが持つすべての連携を I/F 一覧にまとめ、連携を変えるたびに更新する。
連携ファイルの定義書は ODCS v3.2.0 の YAML で書き、`interfaces/<機能 ID>.odcs.yaml` に置く。
項目ごとの属性と連携ごとの事項は、下の対応のとおり ODCS のフィールドに書く。

## I/F 一覧

- システムのすべての連携を I/F 一覧にまとめ、常に最新に保つ。
- 各連携に次の事項を書く。
  - 機能 ID と機能名
  - 連携先のシステム
  - 配信か集信か
  - 方式（オブジェクトストレージ、キューなど）
  - 頻度と時刻（ファイル連携では定義書へのリンク）
  - 門限チェックの有無（集信の場合）
  - 冪等かどうか
  - 置き場所（バケットとキーの接頭辞。ファイル連携では定義書へのリンク）
  - 機密情報の有無（ファイル連携では定義書へのリンク）
  - 説明

## ファイル定義書

- 連携ファイルの定義書（ファイル定義書）は、Open Data Contract Standard（ODCS）の YAML で書き、連携先と合意する仕様の正本にする（[ADR-139](../adr/ADR-139-write-interface-file-definitions-in-odcs.md)）。
- 連携ごとに 1 ファイルを、リポジトリのルートの `interfaces/<機能 ID>.odcs.yaml` に置き、`id` に機能 ID を書く。
- 1 つの連携が複数のファイルを渡すときは、`schema` にファイルごとの要素を並べ、`physicalType` を `file` にする。
- ODCS のフィールドで表せない事項は、下の「`customProperties` の名前」に挙げた名前で `customProperties` に書き、Markdown の別の定義書を作らない。
- 可能なら、機微な情報を除いた実ファイルをサンプルとして連携先と共有する。

### 項目ごとの属性

`schema[].properties[]` の各要素に、次の属性を書く。

- **物理名**：`name`。CSV のヘッダー行に書く名前と同じにする。
- **論理名**：`businessName`
- **型**：`logicalType`（`string`、`integer`、`number`、`date`、`timestamp` など）
- **桁**：文字列は `logicalTypeOptions.maxLength`、小数は `physicalType`（`decimal(9,2)` の `9`）
- **精度**：`physicalType`（`decimal(9,2)` の `2`）
- **必須**：`required`
- **区分値**：`enum`。値を `value` に、名称を `label` に書く。
- **形式**：`logicalTypeOptions.pattern`（正規表現）か `logicalTypeOptions.format`
- **範囲**：`logicalTypeOptions.minimum` と `logicalTypeOptions.maximum`
- **機密**：`classification`。全項目に書き、機密情報（[機密情報を含む連携ファイル](interface-file-security.md)）の項目は `confidential`、それ以外は `internal` にする。
  このリポジトリで使う値はこの 2 つだけである。
  ODCS は値を決めず `confidential`、`restricted`、`public` を例に挙げるだけで、`internal` はこのリポジトリが決めた語である。
  ファイルが機密情報を含むかどうかは、いずれかの項目の `classification` が `confidential` かどうかで表す。
- **例**：`examples`
- **主キー**：`primaryKey`。複合キーは `primaryKeyPosition` で順序を書く。

### 連携ごとの事項

ファイル全体にかかる事項は、次のフィールドに書く。
「必須」の事項はすべての定義書に書き、条件を添えた事項はその条件に当たるときだけ書く。

- **置き場所とファイル形式**（必須）：`servers[]`（`type: s3`、`location`、`format`）
- **頻度と時刻**（必須）：`slaProperties` の `frequency`（`value`、`unit`、`scheduler`、`schedule`）
- **配信の最大遅延時刻**（必須、[ファイル連携の頻度と起動方式](interface-file-scheduling.md)）：`slaProperties` の `timeOfAvailability`
- **保持期間**（必須、[連携ファイルの保持と削除](interface-file-retention.md)）：`slaProperties` の `retention`
- **未着のときの問い合わせ先、エスカレーション、手動の再送の手順**（必須、[連携ファイルの門限チェック](interface-arrival-deadline.md)）：`support`。`scope` を `issues` にし、手順を `description` に書くか `url` でリンクする。
- **文字コード**（規約から外れるときだけ）：`servers[].encoding`

文字コード、改行コード、ヘッダー行、圧縮は[連携ファイルの形式と CSV の書き方](interface-file-format.md)に従うため、固定長やレガシーな文字コードのように規約から外れるときだけ書く。

### customProperties の名前

ODCS に当てるフィールドがない事項は、次の名前で `customProperties` に書く。

- **`timeZone`**（必須）：`schedule` と `timeOfAvailability` の時刻を読むタイムゾーン。値は `Asia/Tokyo` のような IANA の ID。
- **`syncMode`**（必須）：全件か差分か（[全件連携と差分連携](interface-full-and-delta.md)）。値は `full` か `delta`。
- **`sortOrder`**（必須）：ソート条件と、集信側が並び順に依存してはならないこと（[連携ファイルのレイアウト](interface-file-layout.md)）。値は文章。
- **`emptyFileAgreement`**（相手が空ファイルを配信できないときだけ）：合意した、停止の検知の分担（[連携ファイルのレイアウト](interface-file-layout.md)）。値は文章。
- **`cancellationDeadline`**（取り消しを認めない期限を業務で決めるときだけ）：その期限（[連携ファイルのレイアウト](interface-file-layout.md)）。値は文章。
- **`nullValue`**（NULL を空文字と区別するときだけ）：NULL の表し方（[連携ファイルの形式と CSV の書き方](interface-file-format.md)）。値は `#NULL` のような文字列。
- **`unconvertibleCharacters`**（UTF-8 からほかの文字コードへ変換するときだけ）：変換できない文字の扱い（[連携ファイルの形式と CSV の書き方](interface-file-format.md)）。値は `abort`、`replace`、`remove` のいずれか。
- **`fixedLength`**（固定長で連携するときだけ）：固定長で合意する事項（[連携ファイルの形式と CSV の書き方](interface-file-format.md)）。値は文章。
- **`lineSeparator`**（規約から外れるときだけ）：改行コード。値は `LF` のような文字列。
- **`header`**（規約から外れるときだけ）：ヘッダー行を置くか。値は真偽値。
- **`compression`**（規約から外れるときだけ）：圧縮の方式。値は `gzip` のような文字列。

### 定義書の例

CSV を 1 ファイル配信する連携の例を示す。

```yaml
apiVersion: v3.2.0
kind: DataContract
id: IF-CRM-001
name: 顧客マスタの配信
version: 1.0.0
servers:
  - server: stg
    type: s3
    environment: stg
    location: s3://stg-sales-outbound-crm/m_customer/
    format: csv
schema:
  - name: m_customer
    physicalType: file
    properties:
      - name: customer_id
        businessName: 顧客 ID
        logicalType: string
        logicalTypeOptions:
          maxLength: 10
          pattern: ^C[0-9]{9}$
        required: true
        primaryKey: true
        classification: internal
        examples: [C000000001]
      - name: customer_name
        businessName: 顧客名
        logicalType: string
        logicalTypeOptions:
          maxLength: 100
        required: true
        classification: confidential
      - name: rank_code
        businessName: 会員ランク
        logicalType: string
        required: true
        classification: internal
        enum:
          - value: "01"
            label: 一般
          - value: "02"
            label: ゴールド
slaProperties:
  - property: frequency
    value: 1
    unit: d
    scheduler: cron
    schedule: 0 5 * * *
  - property: timeOfAvailability
    value: "06:00"
  - property: retention
    value: 7
    unit: d
support:
  - channel: 顧客マスタの未着
    tool: email
    url: mailto:sales-ops@example.com
    scope: issues
    description: 06:30 までに返信がなければ CRM の運用窓口へ電話し、再送を依頼する。
customProperties:
  - property: timeZone
    value: Asia/Tokyo
  - property: syncMode
    value: full
  - property: sortOrder
    value: customer_id の昇順。集信側は並び順に依存しない。
```

定義書は Data Contract CLI の `datacontract lint` で検査できる。
`datacontract test` に実ファイルの置き場所を渡すと、実ファイルが `maxLength`、`minLength`、`pattern`、`minimum`、`maximum`、`required`、`primaryKey`、`enum` を満たすかも検査できる。
桁と精度（`physicalType`）と `format` は人が読む記述で、`test` は検査しないため、日付と時刻の形は `pattern` で縛る。
`lint` は `datacontract-cli` だけで動き、`test` には `datacontract-cli[duckdb]` を入れる。

## 出典

- フューチャー株式会社「I/F設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forIF/if_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
- [Open Data Contract Standard](https://github.com/bitol-io/open-data-contract-standard/tree/v3.2.0)
- [Data Contract CLI](https://cli.datacontract.com/)
