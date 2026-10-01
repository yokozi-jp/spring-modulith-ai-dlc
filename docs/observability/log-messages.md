---
type: Convention
title: ログメッセージと属性の書き方
description: アプリケーションログの event 名の書き方、言語、可変値を載せる属性の形と命名、ログと利用者向けメッセージの分離を定める規約。ログ出力を書くとき、ログに属性を追加するとき、エラーを利用者へ表示するときに読む。
tags: [convention, observability, logging, future-arch-guidelines]
---

# ログメッセージと属性の書き方

ログの message は、何が起きたかを特定できる英語の静的な event 名にする。
可変値は1属性に1つのスカラー値として key-value 属性へ置き、入れ子にしない。
ログの message と利用者向けメッセージは別々に管理し、ログの内容を利用者へ返さない。

## 前提となる規約

message を静的な event 名にし、可変値を SLF4J の key-value 属性へ置く規則は[可観測性データの規約](conventions.md)に従う。
ログへ渡せる値の allowlist、禁止する値、属性名に OpenTelemetry semantic conventions を優先する規則も同じ文書に従う。
logger の宣言方法は[バックエンドの Java 実装規約](../backend/java-coding.md)に従う。

## event 名

event 名は英語だけで書き、日本語訳などの別言語の message を併記しない。
event 名だけで、どの処理で何が起きたかを一意に解釈できるようにする。
「Database error」のように原因の種類を絞れない名前にしない。
接続失敗、タイムアウト、制約違反のように区別して調査する事象には、別々の event 名を付ける。

event 名に絵文字、ANSI エスケープシーケンス、改行を含めない。

## 可変値を載せる属性

可変値は message に埋め込まず、属性へ置く。

```java
log.atWarn()
    .addKeyValue("order.id", orderId)
    .log("Order allocation failed");
```

属性の値は文字列、数値、真偽値のいずれかにする。
オブジェクト、Map、配列を値にして入れ子の構造を作らない。
`details` のような入れ子の属性へ可変値をまとめない。

DTO やエンティティを logger へ渡さず、`toString()` の結果にも依存しない。
allowlist で許可した値だけを、個別の属性として取り出して渡す。

## 属性名

OpenTelemetry semantic conventions に該当する名前がない属性は、業務上の対象を表す名前空間を付けた小文字の dot.case にする（例：`order.id`）。
特定のクラウド製品や SaaS に固有のキー名をアプリケーションコードで使わない。
保存先の形式への変換が必要な場合は、collector などの収集基盤で行う。

新しい属性を追加する変更では、OpenTelemetry appender の allowlist を同じ変更でレビューする。

## 利用者向けメッセージとの分離

ログの message と属性を、API の応答や画面の表示に流用しない。
利用者向けのエラーは Problem Details で返し、表示文言は message catalog で管理する（[ADR-013](../adr/ADR-013-standardize-http-api-contracts.md)、[ADR-016](../adr/ADR-016-localize-api-and-spa-messages.md)）。

利用者の問い合わせとログを結び付ける必要がある場合は、Problem Details の `traceId` 拡張で trace ID を返す。
ログには trace ID が付くため、問い合わせで受け取った trace ID からログを検索できる。
独自の request ID やエラー ID を発行しない。

## 出典

- フューチャー株式会社「ログ設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forLog/log_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
