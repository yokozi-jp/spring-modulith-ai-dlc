---
type: Convention
title: ログレベルの使い分けと切り替え
description: アプリケーションログの各レベルの意味、アプリケーションコードで使うレベル、環境ごとの出力レベル、レベルの変更方法を定める規約。ログを出力するコードを書くとき、環境のログレベルを変えるときに読む。
tags: [convention, observability, logging, future-arch-guidelines]
---

# ログレベルの使い分けと切り替え

アプリケーションコードは `DEBUG`、`INFO`、`WARN`、`ERROR` の4レベルだけを使う。
デプロイ環境は `INFO` 以上を出力し、レベルは環境変数を変えて再デプロイすることで切り替える。
Actuator の `loggers` endpoint と JMX からレベルを動的に変更しない。

## 各レベルの意味

- **ERROR**：処理が失敗し、運用者の対応が必要な状態。予期しない 5xx 例外はこのレベルにする。
- **WARN**：直ちに失敗ではないが、期限内に対応が必要な状態。証明書の有効期限が近いことなどが該当する。
- **INFO**：運用者がシステムの正常性を判断するための節目。起動完了、一括処理の開始と終了などが該当する。
- **DEBUG**：開発者が不具合を調査するための詳細。

予期した 4xx の扱い、5xx 例外に記録する属性、プロセス停止につながる構成不備の扱いは[可観測性データの規約](conventions.md)に従う。

## レベルの選び方

通知の要否はレベルで決まるため、運用者の対応が不要な事象を `WARN` 以上にしない。
通知の判定は[運用者が対応するログイベントの管理](log-operational-events.md)に従う。

リトライで回復する一時的な失敗は `INFO` にする。
リトライを使い切って処理が失敗した時点で `ERROR` にする。
リトライを行う条件は [ADR-019](../adr/ADR-019-define-resilience-and-capacity-guardrails.md) に従う。

`TRACE` はアプリケーションコードで使わない。
SLF4J に `FATAL` はないため、プロセスの停止は起動処理を担当する logger の severity と終了結果で区別する。
フレームワークやライブラリが `TRACE` を出力することは制限しない。

## 環境ごとの出力レベル

- **ローカル**：`INFO` 以上。調査中だけ `DEBUG` にしてよい。
- **STG**：`INFO` 以上。
- **本番**：`INFO` 以上。

`DEBUG` では、フレームワークが SQL やリクエストの詳細を出力することがある。
これらはログへ出さない値に当たるため、デプロイ環境で `DEBUG` を有効にしない。
ローカルで `DEBUG` にする変更はコミットしない。

## レベルの変更方法

レベルは Spring Boot の `LOGGING_LEVEL_<logger 名>` 環境変数で変える（例：`LOGGING_LEVEL_ROOT=WARN`）。
`application.yaml` に環境別のレベルを書かず、設定は外部から注入する（[ADR-008](../adr/ADR-008-single-application-yaml-external-config.md)）。

実行中のレベル変更には、環境変数を変えて再デプロイする手段だけを使う。
Actuator の Web 公開は `health` だけに限り、`loggers` endpoint を公開しない。
JMX も有効にしない。

## 出典

- フューチャー株式会社「ログ設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forLog/log_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
