---
type: Convention
title: 運用者が対応するログイベントの管理
description: ログからの通知の判定方法と、WARN 以上のログイベントの識別、運用定義に書く項目を定める規約。WARN 以上のログを追加または変更するとき、ログの通知を設計するときに読む。
tags: [convention, observability, logging, operations, future-arch-guidelines]
---

# 運用者が対応するログイベントの管理

通知はログレベルだけで判定し、`WARN` 以上を通知対象とする。
`WARN` 以上の event 名はリポジトリ内で一意にし、event ごとに発生原因と運用者の対応を書いた運用定義を持つ。

## 通知の判定

`WARN` と `ERROR` のログを一律に通知する。
通知の要否をログレベルとは別の属性（通知フラグ）で制御しない。
通知が不要な事象は `INFO` 以下で出力する。
レベルの選び方は[ログレベルの使い分けと切り替え](log-levels.md)に従う。

`INFO` の事象を通知する要件が生じた場合に限り、通知フラグの導入を検討する。

## event 名による識別

運用手順と結び付ける識別子には、ログの message に置く静的な event 名を使う。
`WARN` 以上の event 名は、同じ名前を別の発生箇所や別の意味で使わない。
識別子のための属性（メッセージコード）を別に追加しない。
event 名の書き方は[ログメッセージと属性の書き方](log-messages.md)に従う。

`INFO` 以下の event には運用定義を要求しない。

## 運用定義

`WARN` 以上の event を追加または変更する変更では、同じ変更で運用定義を追加または更新する。
運用定義は Runbook として `docs/observability/` に置く。
運用定義には次の項目を書く。

- **event 名**：ログの message に出力する静的な文字列。
- **レベル**：`WARN` または `ERROR`。
- **事象**：何が起きたかを一文で書く。
- **属性**：event に付ける key-value 属性の名前。値は書かない。
- **利用者への表示**：利用者に返す Problem Details の `type` と message key。利用者へ返さない場合は省く。
- **発生原因**：この event が出る主な原因と条件。
- **対応**：運用者が行う調査と対処。

## 出典

- フューチャー株式会社「ログ設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forLog/log_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
