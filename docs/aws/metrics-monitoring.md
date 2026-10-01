---
type: Convention
title: AWSのサービスのメトリクス監視
description: AWS Health、API Gateway、ALB、ECS、SQS、Kinesis Data Streams、S3について、CloudWatchのメトリクスで監視する項目、しきい値の目安、監視しない項目を定める規約。AWSのサービスの監視とアラートを設定するとき、どのメトリクスを監視するか迷ったときに読む。
tags: [convention, aws, observability, monitoring, future-arch-guidelines]
---

# AWSのサービスのメトリクス監視

AWSのサービスのメトリクスは、外形監視とアプリケーションのログで検知できない事象に絞って監視する。
ALBは正常なホスト数を監視し、ECSのCPUとメモリの使用率は監視の対象にしない。
AWS自体の障害は、AWS Healthのイベントから通知する。

## 前提

WARNとERRORの使い分け、外形監視とヘルスチェックは[APIの失敗のログレベルと監視](../web-api/logging-and-monitoring.md)に従う。
メトリクスは、対象の状態を定期的に観察するモニタリングにも使えるが、この文書は異常を検知する監視の対象だけを定める。

## AWS Health

EventBridgeでAWS Healthのイベントを契機にするルールを作り、運用者へ通知する。

## RDSとAurora

[PostgreSQLの監視](../database/postgresql-monitoring.md)に従う。

## API Gateway

- 可用性がSLOや機能要件で決まっている場合は、`5XXError`の`Count`に対する比率にしきい値を設け、WARN以下で通知する。
- 性能が決まっている場合は、`Latency`を90パーセンタイルで1秒以内などの条件で監視し、WARN以下で通知する。
- どちらも決まっていなければ、外形監視で代替し、API Gatewayのメトリクスは監視しない。

## ALB

- ALBのメトリクスだけに頼らず、エンドツーエンドで依存先まで確かめるヘルスチェックの外形監視を別に行う。
- `HealthyHostCount`で、ターゲットグループが必要な冗長性を保っているかを監視する。
- `HTTPCode_ELB_5XX_Count`は、リクエストの総数に対する割合で監視してよい。外形監視で担保できる場合は省いてよい。
- 常時稼働するサービスでは、`RequestCount`の極端な増減（ゼロなど）を監視すると、異常を早く見つけられる。
- 可用性と性能のSLOがある場合は、API Gatewayと同じ考え方で監視する。

## ECS

- 監視はロードバランサーのヘルスチェックに寄せ、CPUとメモリの使用率は監視の対象にしない。
- メトリクスは、キャパシティの計画、コストの最適化、オートスケールの条件のために収集してよい。

## SQS

キューとDLQの監視の考え方は[非同期処理のトレースと監視](../integration/async-observability.md)に従う。
SQSでは次のメトリクスを使う。

- **`ApproximateAgeOfOldestMessage`**：キューの保持期間に対して、残りが3割になった時点などでWARNかERRORにする。
- **`ApproximateNumberOfMessagesVisible`**：DLQへの退避を把握したい場合と、未処理のメッセージ数のしきい値（5分間に一度でも100を超えたらなど）を決められる場合に監視する。

登録から削除までの処理時間は、SQSの標準のメトリクスでは監視できない。
処理時間を監視する必要がある場合は、非同期処理のトレースと監視が定めるアプリケーションのメトリクスを検討する。

## Kinesis Data Streams

- `GetRecords.IteratorAgeMilliseconds`は、保持期間の50%を超えたらWARN、80%以上でERRORにする。
- `ReadProvisionedThroughputExceeded`と`WriteProvisionedThroughputExceeded`は、平均0.8以上などでWARNかERRORにする。検知したら流量を確かめ、プロビジョンドのモードならシャードを追加する。

## S3

- `BucketSizeBytes`は、費用の異常を早く見つけるため、想定の容量に余裕の係数を掛けた値をしきい値にしてよい。
- リクエストメトリクスは、有効にすると費用がかかり、呼び出し元のAPIのレイテンシを先に監視するため、通常は監視しない。
- レプリケーションメトリクスは通常は監視しない。BCPで定めたRPOが厳しい（15分以内など）場合は、`ReplicationLatency`がRPOを超えていないかを監視する。

## 出典

- フューチャー株式会社「AWS設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forAWS/aws_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
