---
type: ADR
title: 'ADR-043: 本番の可観測性データを ADOT Collector で CloudWatch へ送る'
description: 本番のログ、トレース、メトリクスを Fargate タスクのサイドカーの ADOT Collector から CloudWatch の OTLP エンドポイントへ送り、CloudWatch を本番の唯一の保存先にする決定。
tags: [adr, observability, opentelemetry, aws, cloudwatch]
---

# ADR-043: 本番の可観測性データを ADOT Collector で CloudWatch へ送る

## Status

Proposed

## Date

2026-10-01

## Context

[ADR-015](ADR-015-structure-and-protect-observability-data.md) は、本番の保存先を導入するときの受け入れ条件（30日保持、緊急削除、閲覧の監査、ログとトレースの相関、保存前の denylist、例外属性の検索）を定めたが、保存先は決めていない。
バックエンドはログ、トレース、メトリクスを OTLP で送り、コンソールにも ECS JSON を出す。
本番は ECS on Fargate で動かす（[ADR-041](ADR-041-run-ecs-tasks-on-fargate.md)）。

保存先に求めるのは、運用と管理の負荷が最も小さいことと、OpenTelemetry のトレースとログを確認できることである。
ローカルの Grafana OpenTelemetry LGTM と同じ画面を本番で使うことは求めない。
CloudWatch は OTLP でログ、トレース、メトリクスを受け付けるエンドポイントを提供している。
ただし、このエンドポイントは SigV4 の署名を求めるため、アプリケーションの標準の OTLP exporter から直接は送れない。

標準出力と OTLP の両方を保存すると、同じログが二重に残り、PII が混入したときに両方から削除する必要がある。
一方、OTel の SDK が起動する前の失敗や Collector の障害は、OTLP の経路には残らない。

## Decision

本番の可観測性データは CloudWatch だけに保存する。

- 各 Fargate タスクに ADOT Collector をサイドカーとして置く。アプリケーションは今の OTLP exporter のまま、送信先を localhost の Collector にする。
- Collector は、タスクロールの資格情報で SigV4 署名し、ログを CloudWatch Logs、トレースを X-Ray、メトリクスを CloudWatch の OTLP エンドポイントへ送る。
- Collector の processor で、ADR-015 の禁止値に当たる属性を保存前に削除する。
- アプリケーションのロググループに CloudWatch Logs のデータ保護ポリシーを設定し、混入の検知と表示時のマスクに使う。
- 標準出力は WARN 以上だけを `awslogs` ドライバーで別のロググループへ送り、起動時と Collector の障害時の調査に使う。このロググループにも同じデータ保護ポリシーと保持期間を設定する。
- ログ、トレース、メトリクスは CloudWatch のコンソール（Logs Insights、X-Ray のトレース、CloudWatch のメトリクス）で確認する。
- CloudWatch のコンソールで調査やダッシュボードに支障が出た場合は、Amazon Managed Grafana を追加し、CloudWatch と X-Ray をデータソースにする。保存先と Collector は変えない。

## Consequences

### Positive

- 保存先、監視基盤、閲覧の権限がすべて AWS のマネージドサービスになり、自前で運用する基盤がない。
- アプリケーションのコードと設定は、OTLP の送信先を変えるだけで済む。
- 閲覧の権限を IAM と Identity Center（[ADR-042](ADR-042-use-iam-identity-center-for-human-access.md)）でまとめて管理し、CloudTrail で監査できる。
- ログの正本が OTLP の経路の一つになり、保持と削除の対象が明確になる。

### Negative

- タスクごとにサイドカーの CPU とメモリが要り、Fargate の費用が増える。
- CloudWatch のエンドポイントの上限（属性の長さ、イベントの大きさ）が `exception.stacktrace` を収められるかを実装前に確かめる必要がある。
- CloudWatch Logs はログイベントを個別に削除できず、緊急削除はログストリームかロググループの単位になる。
- ローカルの LGTM と本番で画面と問い合わせの書き方が異なる。

### Neutral

- WARN 以上の標準出力だけは OTLP の経路と重複して保存される。
- Amazon Managed Grafana を追加するかは、見る画面だけの判断であり、この決定を置き換えない。追加する場合は、利用者ごとのライセンス料と、Grafana からの問い合わせによる CloudWatch の料金が増える。
- 監査ログを長期に保管する場合は、[情報の機密性の分類と取り扱い要件](../aws/data-classification.md)に従い S3 に置く。

## Alternatives Considered

### 選択肢1: 標準出力を CloudWatch Logs に送るだけにする

- **Description**：OTLP のログを使わず、ECS JSON の標準出力を `awslogs` で送る。
- **Pros**：サイドカーが要らず、構成が最も単純である。
- **Cons**：保存前の denylist をかけられず、トレースの保存先が別に要る。

### 選択肢2: 最初から Amazon Managed Grafana で見る

- **Description**：保存先は CloudWatch のまま、閲覧の画面に Amazon Managed Grafana を使う。
- **Pros**：ローカルの LGTM と同じ Grafana の画面で、ログ、トレース、メトリクスを行き来できる。
- **Cons**：ワークスペース、利用者、ダッシュボードの管理とライセンス料が増える。CloudWatch のコンソールで足りるかを確かめる前に入れる理由がないため、問題が出た時点で追加する。

### 選択肢3: Grafana Cloud へ送る

- **Description**：OTLP を Grafana Cloud のエンドポイントへ送る。
- **Pros**：ローカルと同じ Grafana の画面で確認できる。
- **Cons**：外部の SaaS に本番データを置き、契約、資格情報、閲覧権限を AWS と別に管理する。

### 選択肢4: AWS 上に LGTM を自前で構築する

- **Description**：Loki、Tempo、Mimir、Grafana を ECS で動かす。
- **Pros**：ローカルと同じ構成になり、製品を選び直さずに済む。
- **Cons**：保存先の可用性、容量、更新を自前で運用し、Fargate を選んで減らした運用の負担が戻る。

### 選択肢5: ADOT SDK から Collector を介さず送る

- **Description**：アプリケーションに ADOT の SDK を入れ、SigV4 で CloudWatch へ直接送る。
- **Pros**：サイドカーが要らない。
- **Cons**：Spring Boot の OTLP の設定を ADOT の SDK に置き換える必要があり、保存前に属性を削除する場所がアプリケーションの中だけになる。

## References

- [ADR-015: 可観測性データを構造化し保護する](ADR-015-structure-and-protect-observability-data.md)
- [Sending logs using the OTLP endpoint](https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/CWL_HTTP_Endpoints_OTLP.html)
- [Help protect sensitive log data with masking](https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/mask-sensitive-log-data.html)
- [CloudWatch Logsのロググループ](../aws/cloudwatch-logs.md)
