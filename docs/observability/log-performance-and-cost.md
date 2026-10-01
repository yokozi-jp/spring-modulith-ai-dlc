---
type: Convention
title: ログの出力コストと量の抑制
description: ログ出力による性能劣化を避ける書き方、appender の設定、ログ量と保存費用を抑える規則を定める規約。ログを出力するコードを書くとき、Logback の appender を変更するとき、性能問題やログ費用を調べるときに読む。
tags: [convention, observability, logging, performance, future-arch-guidelines]
---

# ログの出力コストと量の抑制

出力しないレベルのログでは、値の計算と文字列化を行わない。
ループ内で1件ごとにログを出さず、呼び出し元のファイル名と行番号をログ項目にしない。
appender はログを破棄せず、停止時にフラッシュする設定にする。

## 遅延評価

値の計算や文字列化をアプリケーションコードで先に行わず、logger に任せる。
文字列連結や `toString()` で作った値を logger へ渡さない。

計算に費用がかかる値は、SLF4J の fluent API へ `Supplier` として渡す。

```java
log.atDebug()
    .addKeyValue("cart.item_count", () -> cart.countItems())
    .log("Cart loaded");
```

ログのためだけに文字列や集計値を加工する場合は、`log.isDebugEnabled()` などでレベルを判定してから加工する。

## ログ量

ループ内で1件ごとにログを出力しない。
進捗は一定件数ごとに記録する（[処理ごとに記録するログ](log-output-points.md)）。

用途を説明できないログを出力しない。
ただし、障害調査に必要なログを、量を減らす目的で削らない。

Base64 にしたバイナリのような大きな値を属性にしない。

## 呼び出し元の情報

ログを出力したファイル名、行番号、メソッド名をログ項目にしない。
これらはログごとに stack trace から求めるため、出力が遅くなる。
出力元は logger 名で特定する。

例外の `exception.stacktrace` に含まれるファイル名と行番号はこの規則の対象外とし、[可観測性データの規約](conventions.md)に従う。

## appender の設定

コンソール appender の `immediateFlush` を無効にしない。
無効にすると、プロセスが異常終了したときに直前のログを失う。

appender を非同期化する場合は、キューが満ちてもログを破棄しない設定にする。
Logback の `AsyncAppender` では `discardingThreshold` を `0`、`neverBlock` を `false` にする。
アプリケーションの停止時には、キューに残ったログをフラッシュしてから終了する。

JSON の encoder は Spring Boot 標準の ECS 形式を使い、外部の encoder を追加しない（[ADR-015](../adr/ADR-015-structure-and-protect-observability-data.md)）。

## 性能問題の調査

性能問題を調べるときは、ログの出力量も原因の候補に含める。
特に、フレームワークが SQL やリクエストの詳細を大量に出力していないかを確認する。

## 保存費用

アプリケーションログは発生源で一行一 JSON にし、保存先でパースしなくても検索できる形にする。
通常のアプリケーションログを安価なアーカイブ層へ移す仕組みを設けず、保持期限で削除する。
保持期間は[可観測性データの規約](conventions.md)に従う。

## 出典

- フューチャー株式会社「ログ設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forLog/log_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
