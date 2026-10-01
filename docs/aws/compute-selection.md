---
type: Convention
title: AWSの実行基盤とジョブキューの選択
description: Web APIとバッチのタスクを動かすAWSのコンピューティングサービスの選び方と、外部のジョブキューを導入するときのサービスの選び方を定める規約。Web APIやバッチの実行基盤を構築するとき、Fargateのタスクを設定するとき、外部のメッセージブローカーを選ぶときに読む。
tags: [convention, aws, compute, async, future-arch-guidelines]
---

# AWSの実行基盤とジョブキューの選択

Web APIとバッチのタスクはECS on Fargateで動かす（[ADR-041](../adr/ADR-041-run-ecs-tasks-on-fargate.md)）。
ECS on EC2、Lambda、EKS、App Runner、EC2への直接のデプロイは使わない。
外部のジョブキューを導入する場合は、SQSを第一候補にする。

## Web API

- ECS on Fargateで動かす。
- ECS on EC2は使わない。Fargateの性能や費用が要件を満たさないと分かった場合は、計測結果を添えてADR-041を見直す。
- Fargateのタスクサイズ（vCPUとメモリ）は、[性能テスト](../performance-test/index.md)の結果から決める。
- 費用は[AWSのコストの可視化と削減](cost-optimization.md)のCompute Savings Plansで抑える。

次の基盤は使わない。

- **Lambda**：常時リクエストのある負荷では、呼び出し数の上振れで費用が見積もりを超えやすく、Javaは起動が遅いためである。
- **EKS**：Kubernetesの運用が要り、このリポジトリにKubernetesで動かす技術方針がないためである。
- **EC2への直接のデプロイ**：CI/CDの作り込みとOSの保守が要るためである。
- **App Runner**：ロードバランサーから取れるログの項目や、オートスケールの条件など、制御できる項目が少ないためである。

## バッチのタスク

**バッチのタスク**：ワークフローエンジンなどから起動され、複数の処理をまとめて実行するコンテナのタスク。

- ECS on Fargateで動かす。
- AWS Batchは、バッチウィンドウの厳しい定時の処理には使わない。ジョブのキューイングで数分の遅延が起こり得るためである。

## ジョブキュー

外部のメッセージブローカーの導入と製品の選定は、[メッセージングの設計](../integration/async-messaging-design.md)に従いADRで決める。
AWSでジョブキューを選ぶときは、次に従う。

- SQSを第一候補にする。一つのメッセージを一つのコンシューマーが処理して削除するモデルが、ジョブキューに合うためである。順序が要る場合はFIFOキューを使う。
- SQSのDLQの保持期間は最大の14日にする。より長く残す必要がある場合の扱いは[非同期処理の失敗時の再試行と回復](../integration/async-failure-recovery.md)に従う。
- Kinesis Data Streamsは複数のコンシューマーが同じデータを読むストリーミングに、EventBridgeは複数の宛先へのイベントのルーティングに使い、ジョブキューには使わない。
- SNSは多数の購読者へのファンアウトの通知に使い、ジョブキューには使わない。
- Amazon MQは、既存のシステムが使っているブローカーをそのまま移す場合に限って使う。

## 出典

- フューチャー株式会社「AWS設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forAWS/aws_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
