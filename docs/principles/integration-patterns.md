---
type: Convention
title: 名前の付いた連携パターンの利用
description: システム間やモジュール間の連携方式を独自に考案せず、Enterprise Integration Patterns などの名前の付いたパターンから選んで設計し記述する方針を定める規約。連携方式を新しく考えるとき、連携の設計文書を書くときに読む。
tags: [convention, principles, integration, future-arch-guidelines]
---

# 名前の付いた連携パターンの利用

連携方式は独自に考案せず、Enterprise Integration Patterns（EIP）などで名前の付いたパターンから選ぶ。
設計文書では、採用した方式をパターン名で書き、そのパターンの既知の制約が当てはまるかを確かめる。
思いついた方式は、カタログから該当するパターンを逆引きして、検討事項と代替案を洗い出す。

## ルール

- 連携の設計文書には、Publish-Subscribe、Message Router、Dead Letter Channel のようなパターン名を書く。
- パターン名を書いたら、そのパターンの既知のトレードオフと制約が設計に当てはまるかを確かめる。
- パターンをすべて覚える必要はない。新しい方式を考えたときに、[EIP のカタログ](https://www.enterpriseintegrationpatterns.com/)から逆引きする。
- 該当するパターンが見つからない方式は独自の構成として扱い、[技術選定の原則](technology-selection.md)の独自の構成を採るかの判断に従う。

## このリポジトリでの適用先

- システム間のデータ連携の方式は[システム間連携の方式選択](../integration/interface-integration-patterns.md)に従う。
- 非同期の連携は[メッセージングの設計](../integration/async-messaging-design.md)に従う。
- 配信保証、再送、冪等性は[順序保証と冪等性](../integration/async-ordering-and-idempotency.md)に従う。ネットワーク越しの通信は失敗して再送され得るため、受信側は常に冪等にする。

## 出典

- フューチャー株式会社「アーキテクチャ原則ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forPrinciple/principle_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
