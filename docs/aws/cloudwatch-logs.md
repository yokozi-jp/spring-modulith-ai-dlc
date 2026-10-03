---
type: Convention
title: CloudWatch Logsのロググループ
description: CloudWatch Logsをログの保存先にする場合の、アプリケーションとAWSのサービスごとのロググループの分け方、保持期間、データ保護ポリシー、ログストリームの単位を定める規約。CloudWatch Logsのロググループを作るとき、ログの保存先や保持期間を設定するときに読む。
tags: [convention, aws, observability, logging, future-arch-guidelines]
---

# CloudWatch Logsのロググループ

アプリケーションのログは、OpenTelemetry CollectorからCloudWatch Logsへ送るOTLPのロググループと、WARN以上の標準出力のロググループに分ける。
AWSのサービスが出すログは、サービスの種類ごとにロググループを分ける。
ログの内容、相関、保持期間は可観測性データの規約に従う。

## 前提

アプリケーションはログ、トレース、メトリクスをOpenTelemetryで送り、本番ではCloudWatchだけに保存する（[ADR-043](../adr/ADR-043-send-production-telemetry-to-cloudwatch-via-otel-collector.md)）。
記録する値、相関、禁止する値、保持とアクセス、本番の受け入れ条件は[可観測性データの規約](../observability/conventions.md)に従う。
この文書は、CloudWatch Logsのロググループの分け方と設定を定める。

## 用語

- **ログイベント**：アプリケーションやリソースが出力した個々のログのレコード。
- **ログストリーム**：同じ出力元から生成された一連のログイベント。
- **ロググループ**：複数のログストリームをまとめる単位。保持期間、メトリクスフィルター、アラーム、アクセス制御、タグはロググループの単位で設定する。

## アプリケーションのロググループ

- 一つのサービスに、OTLPのログのロググループと、WARN以上の標準出力のロググループを一つずつ作る。他のサービスと共有しない。
- 共有しない理由は次のとおりである。サブスクリプションフィルターはロググループごとに二つまでしか置けない。タグはロググループの単位でしか付かず、コストを按分できない。一つのサービスが大量に出力すると他のサービスの検索に影響する。機微な情報が混入したときに、削除とアクセス制御の範囲が広がる。
- 複数のサービスを横断して調べる場合は、CloudWatch Logs Insightsのクロスロググループ検索を使う。
- 上の二つ以外に、一つのサービスのログを複数のロググループへ分けない。保持期間を分ける必要がある場合（セキュリティ監査ログと通常のログなど）に限り、分けてよい。
- アプリケーションのロググループの保持期間は、可観測性データの規約が定める通常のログの保持期間に設定し、無期限のまま残さない。
- アプリケーションのロググループには、どちらもデータ保護ポリシーを設定する。
- ログストリームはタスクごとに分ける。CloudWatch Logsはログイベントを個別に削除できず、緊急削除はログストリームの単位になるためである。

## AWSのサービスのロググループ

- VPCフローログ、RDSのログなど、AWSのサービスの種類ごとにロググループを分ける。
- 長期の保管が要る監査ログは、[情報の機密性の分類と取り扱い要件](data-classification.md)に従いS3に置く。

## 出典

- フューチャー株式会社「AWS設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forAWS/aws_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
