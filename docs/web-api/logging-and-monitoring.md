---
type: Convention
title: APIの失敗のログレベルと監視
description: APIが返す4xxと5xxのログレベル、通知の対象、エラーバジェットへの切り替え、外形監視とヘルスチェックの扱いを定める規約。APIのエラー処理でログを出すとき、APIの監視と通知の条件を決めるときに読む。
tags: [convention, web-api, observability, logging, future-arch-guidelines]
---

# APIの失敗のログレベルと監視

4xxはINFOで記録して通知しない。
ただしSPAと一体で開発するAPIの入力検証エラーは、実装の不備を示すためWARNで記録する。
5xxはERRORで記録し、WARN以上を通知の対象にする。

## 前提

ログの記録形式、相関情報、記録してはいけない値は[可観測性データの規約](../observability/conventions.md)に従う。
この文書は、APIの応答の種類ごとのログレベルと監視を定める。

## ログレベルと通知

WARN以上を通知の対象にする。
WARNとERRORは、復旧の緊急度で使い分ける。

- **WARN**：数営業日以内に解決すればよい事象。
- **ERROR**：当日中に復旧が必要な事象。

## 4xx

4xxはクライアントに起因するため、INFOで記録し、通知しない。

このリポジトリのAPIはSPAと一体で開発し、SPAでも入力を検証する。
そのため、サーバーで発生した入力検証エラー（400）は、SPAの検証漏れなどの実装不備を示す可能性が高い。
この入力検証エラーはWARNで記録し、発生状況を把握する。

## 5xx

5xxは次の原因を含め、ERRORで記録する。

- SQLとスキーマの不一致のような実装の不備。
- 再試行しても回復しないDBや外部サービスとの通信エラー。

利用者へエラーを返したら、開発者も状況を把握して対処する。
入口のタイムアウトより長く処理が続いた場合は、アプリケーションもWARNで記録する。

## エラーバジェットへの切り替え

常時数百TPS以上のリクエストを受けるようになり、個々の5xxを調査すると開発が滞る規模になったら、SLIとSLOを定める。
許容する5xxの割合をエラーバジェットとして決め、個々の通知ではなく割合で監視する。

## 外形監視とヘルスチェック

APIの稼働は、ヘルスチェックを使った外形監視で確認する。
オートスケールするAPIサーバーでは、CPU、メモリ、ストレージの使用率の個別監視より外形監視を優先する。

ヘルスチェックはSpring Boot Actuatorのlivenessとreadinessのグループを使う。
エンドポイントと公開範囲は[application.yaml](../../backend/src/main/resources/application.yaml)の`management`に従う。
ヘルスチェックへのアクセスはアクセスログに出力しない。

未認証で許可するのはlivenessとreadinessだけで、`/actuator/health`のルートはログイン済みでも拒否する。
本番の公開originは`/actuator/**`を振り分けず、ロードバランサーは内部の経路でreadinessかlivenessを呼ぶ。
理由は[ADR-061](../adr/ADR-061-allow-only-health-probes-unauthenticated.md)に記録する。

## 出典

- フューチャー株式会社「Web API設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forWebAPI/web_api_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
