---
type: ADR
title: 'ADR-041: ECS のタスクを Fargate で動かす'
description: AWS 上の Web API とバッチのタスクを ECS on Fargate だけで動かし、ECS on EC2、EKS、Lambda を使わない決定。
tags: [adr, aws, compute, ecs, fargate]
---

# ADR-041: ECS のタスクを Fargate で動かす

## Status

Proposed

## Date

2026-10-01

## Context

本番デプロイのワークフロー例（`.github/workflows/production-cd.yml.example`）は、ECR に置いたイメージを ECS サービスへデプロイする構成を仮定しているが、起動タイプは決めていない。
取り込んだ AWS 設計ガイドラインは、費用とイメージキャッシュによる起動の速さを理由に、運用負荷を許容できれば ECS on EC2 を勧めている。
一方、ECS on EC2 では、OS のパッチ、AMI の更新、ホストの脆弱性管理、クラスターの容量管理をこのチームが担う。
このリポジトリにはインフラ専任の運用者がおらず、`infrastructure/` もまだ空である。

## Decision

AWS 上の Web API とバッチのタスクは、ECS on Fargate だけで動かす。
ECS on EC2、EKS、Lambda、App Runner、EC2 への直接のデプロイは使わない。
費用は Compute Savings Plans とタスクサイズの調整で抑える。

## Consequences

### Positive

- OS、AMI、ホストの脆弱性管理とクラスターの容量管理が不要になる。
- タスクごとに分離された実行環境になり、ホストを共有する影響を考えなくてよい。
- 脆弱性管理の範囲が ECR のイメージに絞られ、ADR-020 の署名と provenance の検証と同じ対象を扱える。

### Negative

- 同じ処理量では ECS on EC2 より費用が高くなりやすい。
- イメージのキャッシュがないため、タスクの起動とスケールアウトが EC2 より遅い。
- GPU、特定のインスタンスファミリー、ホストへの特権的なアクセスを要する処理は動かせない。

### Neutral

- タスクサイズは性能テストの結果から決める。
- 性能や費用が要件を満たさないと計測で分かった場合は、この ADR を見直す。

## Alternatives Considered

### 選択肢1: ECS on EC2

- **Description**：EC2 のキャパシティプロバイダーで ECS のタスクを動かす。
- **Pros**：費用が安く、イメージのキャッシュで起動が速い。
- **Cons**：OS と AMI の保守、ホストの脆弱性管理、容量管理の運用が増える。

### 選択肢2: EKS

- **Description**：Kubernetes でコンテナを動かす。
- **Pros**：クラウド間の可搬性があり、Kubernetes のエコシステムを使える。
- **Cons**：コントロールプレーンの費用と Kubernetes の運用が増え、このリポジトリに Kubernetes を使う方針がない。

### 選択肢3: 条件に応じて EC2 と Fargate を選ぶ

- **Description**：取り込んだガイドラインのとおり、運用負荷を許容できるかでサービスごとに選ぶ。
- **Pros**：処理ごとに費用を最適化できる。
- **Cons**：IaC、監視、脆弱性管理が二系統になり、判断がサービスごとに分かれる。

## References

- [AWSの実行基盤とジョブキューの選択](../aws/compute-selection.md)
- [ADR-020: コンテナイメージを署名し provenance を検証する](ADR-020-sign-and-attest-container-images.md)
