---
type: ADR
title: 'ADR-139: I/F のファイル定義書を ODCS v3.2.0 の YAML で書き、リポジトリのルートの interfaces/ に置く'
description: 連携ファイルの定義書を Open Data Contract Standard v3.2.0 の YAML で書いて連携先と合意する仕様の正本にし、連携ごとに interfaces/<機能 ID>.odcs.yaml に置く判断と、Markdown の表、JSON Schema、Data Contract Specification、Excel を採らない理由。
tags: [adr, integration, interface, documentation, future-arch-guidelines]
---

# ADR-139: I/F のファイル定義書を ODCS v3.2.0 の YAML で書き、リポジトリのルートの interfaces/ に置く

## Status

Proposed

## Date

2026-11-26

## Context

[連携の一覧と定義書](../integration/interface-documentation.md)の「ファイル定義書」は、項目定義にどの属性を書くかを決めていなかった。
連携ごとの事項も、全件か差分か、保持期間など、ほかの I/F の規約（[I/F の規約の一覧](../integration/index.md)）が決めると定めた事項の一部を挙げていなかった。

[Issue #324](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/324) の調査で、連携ごとの事項と項目定義の属性を定義書に取り込むと決めた（[Issue #570](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/570)）。
項目定義の属性（物理名、論理名、型、桁、精度、必須、区分値、形式、範囲、機密、例、主キー）を Markdown の表に並べると、12 列の表になる。
この表は、列の多い表を Markdown で書かない規則（[設計書と正本の分担](../writing/design-doc-sources-of-truth.md)の「列の多い表」）に当たる。

定義書は、連携先へ渡すスキーマに JSON Schema か Data Contract Specification を使ってよいとしていた。
Data Contract Specification は、提供元が Open Data Contract Standard（ODCS）v3.1.0 の公開に合わせて非推奨にした。
Data Contract CLI での対応が 2026 年の末までであることは、Data Contract Specification の README の Deprecation Notice が書いている。

ODCS の最新版は 2026-09-08 に公開した v3.2.0 である。
v3.2.0 の JSON Schema で、項目の属性と連携ごとの事項を表せることを確かめた（フィールドの対応は[連携の一覧と定義書](../integration/interface-documentation.md)の「ファイル定義書」）。
区分値は、v3.2.0 で足された `enum` で書ける。
`quality` の `invalidValues` か `validValues` でも書けるが、`enum` は値ごとに名称（`label`）を持てる。

Data Contract CLI の v1.2.11 は、ODCS の `lint`、`test`、`breaking`、`changelog`、`export` を持つ。
`interface-documentation.md` の例を写したファイルは `datacontract lint` を通り、`logicalType` を `varchar` にすると失敗した。
同じ例を手元の CSV に向けた `datacontract test` は、`maxLength`、`minLength`、`pattern`、`minimum`、`maximum`、`required`、`primaryKey`、`enum` を検査し、`enum` にない値を失敗にした。
一方で、`physicalType` の小数の桁と精度と、`logicalTypeOptions.format` は検査しなかった。
`decimal(9,2)` の項目の `1234.567`、`format: email` の項目の `notanemail`、`timestamp` の項目の `+09:00` 付きの値は、どれも通った。
`lint` は `datacontract-cli` だけで動くが、`test` は `datacontract-cli[duckdb]` を入れないと「The 'local' backend is not installed」で失敗した。

## Decision

連携ファイルの定義書（ファイル定義書）を ODCS v3.2.0 の YAML で書き、連携先と合意する仕様の正本にする。
ODCS のフィールドで表せない事項も `customProperties` に書き、Markdown の定義書を別に作らない。
定義書は、連携ごとに 1 ファイルをリポジトリのルートの `interfaces/<機能 ID>.odcs.yaml` に置き、ODCS の `id` に I/F 一覧の機能 ID を書く。
そのほかの書き方は[連携の一覧と定義書](../integration/interface-documentation.md)の「ファイル定義書」に従う。

ルートに置くのは、`openapi/` と同じく、システムの外と交わす契約だからである。
定義書は配信側と集信側の両方の実装と、連携先と、CI が読む。
`backend/` のリソースに置くと、実行時に読まないファイルが特定のアプリケーションに属して見える。
`docs/` は iwe と OKF の検査が対象にする Markdown の置き場で、YAML の契約を置く場所ではない。
拡張子の `.odcs.yaml` は、ODCS の公式の例のファイル名に合わせる。

## Consequences

### Positive

- 項目定義の属性を YAML のフィールドに分けて書くため、12 列の表を Markdown で保守せずに済む。
  1 つの属性の変更が `git diff` の 1 行になる。
- 連携ごとの事項を決まったフィールドに書くため、事項の書き漏らしを定義書のレビューで見つけやすい。
- 連携先へ同じファイルを渡せ、連携先は Data Contract CLI などの道具で読める。
- `datacontract test` を使えば、実ファイルが文字数、パターン、範囲、必須、主キー、区分値を満たすかを定義書から検査できる。
  集信側のスキーマレベルの検証（[連携ファイルの検証と取り込み範囲](../integration/interface-receiving-validation.md)）のテストのうち、これらの検査に使える。

### Negative

- ODCS と Data Contract CLI の版を追う手間が増える。
  ODCS は、2024 年 10 月の v3.0.0 から 2026 年 9 月の v3.2.0 までに minor の版を 2 回出した。
- `datacontract test` は小数の桁と精度と `format` を検査しないため、これらは定義書から実ファイルを検査できない。
- v1.2.11 の `datacontract lint` は、スキーマにないフィールドを、`servers[]` と `schema[].properties[]` の中では警告にとどめて終了コード 0 で終わる。
  たとえば `classification` を `clasification` と書き誤っても通る。
  トップレベル、`slaProperties[]`、`logicalTypeOptions` の中では、同じ誤りを失敗（終了コード 1）にする。
  CLI の警告の文面は、次の major 版（2027 年 1 月）で誤りにすると告げている。
  CI を足す Pull Request で、警告を失敗として扱うかを決める。
- 日付ごとのファイルを複数持つ `location` に `datacontract test` を向けると、全部のファイルを 1 つの表として読むため、ファイルをまたいで同じ主キーを持つ行を重複として誤って失敗にする。
  ファイルごとに検査する方法は、CI を足す Pull Request で設計する。
- `customProperties` の名前は ODCS が決めないため、このリポジトリで名前を決めて守る。
  名前を間違えても `lint` は見つけない。
- YAML を読めない連携先には、`datacontract export` で HTML などに変えて渡す手間がかかる。

### Neutral

- 連携先が ODCS を使わない場合も、正本は定義書のままにし、定義書から作った文書を渡す。
- 最初の定義書を足す Pull Request で、`interfaces/` を変えたときに `datacontract lint` を実行する CI を足す。
  今は検査する定義書がないため、この ADR では CI を足さない。

## Alternatives Considered

### 選択肢1: Markdown の表を責務ごとに分ける

- **Description**：項目定義を、型と桁の表、区分値と形式の表、機密と例の表のように分け、1 つの表の列を減らす。
- **Pros**：新しい形式と道具を覚えずに済み、iwe と OKF の検査の対象に入る。
- **Cons**：1 つの項目の属性が複数の表に散り、項目の追加と削除のたびに全部の表を直す。
  機械で読めないため、実ファイルの検査に使えず、連携先の道具にも渡せない。

### 選択肢2: JSON Schema

- **Description**：1 レコードを JSON Schema のオブジェクトで書き、項目をプロパティにする。
- **Pros**：道具が多く、型、桁、パターン、範囲、列挙を表せる。
- **Cons**：CSV の列の順序、置き場所、頻度、保持期間を表すフィールドがない。
  連携ごとの事項を独自のキーワードで足すことになり、それを読む道具がない。

### 選択肢3: Data Contract Specification

- **Description**：Data Contract CLI が先に対応した形式で書く。
- **Pros**：ODCS と同じく、項目定義、置き場所、SLA を 1 ファイルに書ける。
- **Cons**：提供元が非推奨にし、Data Contract CLI での対応は 2026 年の末までである。

### 選択肢4: Excel

- **Description**：連携先と Excel の定義書をやり取りする。
- **Pros**：多くの連携先が読み書きでき、広い表も見やすい。
- **Cons**：`git diff` で変更を読めず、Pull Request でレビューできない。
  機械で読むには、シートの形を決めて読み取る道具を作ることになる。

## References

- [Issue #570](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/570)
- [Issue #324](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/324)
- [連携の一覧と定義書](../integration/interface-documentation.md)
- [設計書と正本の分担](../writing/design-doc-sources-of-truth.md)
- [ADR-040: Future のアーキテクチャ設計ガイドラインを書き直して docs に取り込む](ADR-040-import-future-architecture-guidelines.md)
- [Open Data Contract Standard v3.2.0](https://github.com/bitol-io/open-data-contract-standard/tree/v3.2.0)
- [Data Contract Specification の非推奨の告知](https://github.com/datacontract/datacontract-specification#readme)
- [Data Contract CLI](https://cli.datacontract.com/)
