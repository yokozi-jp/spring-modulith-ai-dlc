---
type: ADR
title: 'ADR-045: AWS の規約と本番 CD のひな型を削除する'
description: 古くなった docs/aws の規約、本番 CD のワークフローのひな型、AWS の steering を削除し、AWS の規約は AI 活用を含めて新しい領域として書き直し、CloudWatch Logs のアプリケーションのロググループの規約は docs/observability へ移す決定。
tags: [adr, aws, documentation, ci-cd]
---

# ADR-045: AWS の規約と本番 CD のひな型を削除する

## Status

Proposed

## Date

2026-10-03

## Context

[ADR-040](ADR-040-import-future-architecture-guidelines.md) は、Future のアーキテクチャ設計ガイドラインの AWS の章を書き直し、`docs/aws/` の12文書として取り込んだ。
2026年10月の調査で、これらの文書は2024年から2026年の AWS の変更を反映していないと分かった。
該当するのは、Security Hub の再編、App Runner の新規受付の停止、ECS Managed Instances、ECS の組み込みの blue/green デプロイ、CloudFront VPC オリジン、RCP と宣言型ポリシー、Database Savings Plans、GitHub OIDC の immutable な `sub` クレームである。
また、AI の活用に関する指針を含まない。

本番 CD のひな型（`.github/workflows/production-cd.yml.example`）にもアンチパターンがある。
GitHub ホストのランナーから RDS の Writer へ JDBC で直接接続するため、RDS をインターネットへ公開しないと動かない。
マイグレーションの DB の資格情報を `GITHUB_ENV` に書くため、同じジョブの後続のサードパーティのステップにも資格情報が渡る。

`infrastructure/` はまだ空であり、このひな型を使う構成はない。

## Decision

`docs/aws/`、`.github/workflows/production-cd.yml.example`、`.kiro/steering/aws.md` を削除する。
これにより、ADR-040 のうち AWS の取り込みを取り消す。
ADR-040 のほかの領域の取り込みは変えない。

ただし、`docs/aws/cloudwatch-logs.md` のうち、OpenTelemetry の経路で送るアプリケーションのログのロググループの規約は、[可観測性データの規約](../observability/conventions.md)の「本番のロググループ」の節へ移す。
この規約は ADR-043 の本番の保存先の設定であり、AWS の規約の書き直しを待たずに必要になるためである。
ロググループをサービスごとに分け、他のサービスと共有しない理由は次のとおりである。

- サブスクリプションフィルターは、ロググループごとに二つまでしか置けない。
- 保持期間、メトリクスフィルター、アラーム、アクセス制御、タグはロググループの単位で設定するため、共有するとサービスごとにコストを按分できない。
- 一つのサービスが大量に出力すると、同じロググループを使う他のサービスの検索に影響する。
- 機微な情報が混入したときに、削除とアクセス制御の範囲が広がる。

AWS のサービスのロググループ（VPC フローログ、RDS のログ）と監査ログの保管先の規約は、OpenTelemetry の経路ではないため移さない。

AWS の規約は、AI の活用を含め、必要になった時点で新しい領域として ADR-040 と同じ規約に従って書き直す。
[ADR-041](ADR-041-run-ecs-tasks-on-fargate.md)、[ADR-042](ADR-042-use-iam-identity-center-for-human-access.md)、[ADR-043](ADR-043-send-production-telemetry-to-cloudwatch-via-otel-collector.md) の決定は維持する。

## Consequences

### Positive

- 古い情報とアンチパターンを含む規約を、エージェントと人が参照しなくなる。
- 使われていないひな型を保守する必要がなくなる。

### Negative

- 新しい文書ができるまで、AWS の規約がない。
- CloudWatch Logs のロググループの規約のうち、AWS のサービスのロググループの分け方はなくなる。アプリケーションのロググループの分け方は[可観測性データの規約](../observability/conventions.md)へ移した。
- 長期に保管する監査ログを、情報の機密性の分類と取り扱い要件に従って S3 に置くという条件が、分類の文書とともになくなる。ADR-043 は S3 に置くことだけを残しているため、AWS の規約を書き直すときにこの条件を戻す。
- 本番 CD を作るときは、ひな型なしで設計する。

### Neutral

- ADR-041 と ADR-042 は削除した文書へのリンクを除き、ADR-043 はロググループの規約へのリンクを可観測性データの規約へ付け替えたうえで、決定を保つ。
- AWS の規約を書き直すときに、ADR-041、ADR-042、ADR-043 を見直すかを判断する。

## Alternatives Considered

### 選択肢1: 既存の文書をその場で直す

- **Description**：調査で見つかった問題を `docs/aws/` の各文書で修正する。
- **Pros**：規約がない期間が生じない。
- **Cons**：古い箇所が多くの文書にわたり、AI の活用の指針は今の構成に収まらない。

### 選択肢2: CD のひな型だけを直して残す

- **Description**：RDS への接続と資格情報の渡し方を直したひな型を残す。
- **Pros**：本番 CD を作るときの出発点が残る。
- **Cons**：`infrastructure/` が空で使う構成がなく、YAGNI に反する。

## References

- [ADR-040: Future のアーキテクチャ設計ガイドラインを書き直して docs に取り込む](ADR-040-import-future-architecture-guidelines.md)
- [ADR-041: ECS のタスクを Fargate で動かす](ADR-041-run-ecs-tasks-on-fargate.md)
- [ADR-042: 人の利用者の AWS アクセスを IAM Identity Center で管理する](ADR-042-use-iam-identity-center-for-human-access.md)
- [ADR-043: 本番の可観測性データを OpenTelemetry Collector で CloudWatch へ送る](ADR-043-send-production-telemetry-to-cloudwatch-via-otel-collector.md)
- [可観測性データの規約](../observability/conventions.md)
- [Subscriptions](https://docs.aws.amazon.com/AmazonCloudWatch/latest/DeveloperGuide/Subscriptions.html)
- [AWS Well-Architected Framework, Security Pillar](https://docs.aws.amazon.com/wellarchitected/latest/security-pillar/welcome.html)
- [App Runner availability change](https://docs.aws.amazon.com/apprunner/latest/dg/apprunner-availability-change.html)
- [Immutable subject claims for GitHub Actions OIDC tokens](https://github.blog/changelog/2026-04-23-immutable-subject-claims-for-github-actions-oidc-tokens/)
