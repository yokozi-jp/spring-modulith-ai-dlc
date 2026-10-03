---
type: Convention
title: 可観測性データの規約
description: OpenTelemetry へ記録するデータ、例外の記録、ログとトレースの相関、発生源で渡さない値、Collector の allowlist、保持、アクセス、本番の受け入れ条件を定め、可観測性データの実装、Collector の設定、本番収集基盤を変更するときに読む規約。
tags: [convention, observability, opentelemetry, security]
---

# 可観測性データの規約

バックエンドはログ、トレース、メトリクスを OpenTelemetry で Collector へ送り、コンソールにも ECS JSON を出す。
例外は logger へ渡して標準どおりに記録し、禁止値はアプリケーションから渡さない。
ログの属性は Collector の allowlist で絞り、保存先で閲覧の制限と表示時のマスクを行う。
設計判断は [ADR-015](../adr/ADR-015-structure-and-protect-observability-data.md) と [ADR-043](../adr/ADR-043-send-production-telemetry-to-cloudwatch-via-otel-collector.md) に記録している。

## 記録

コンソールログは一行一 JSON の Elastic Common Schema（ECS）で出力する。
OpenTelemetry appender は同じ Logback event を OTLP の LogRecord として送り、SLF4J の key-value をすべて属性にする。

アプリケーションログの message は検索可能な静的 event 名にし、変動する値は SLF4J の key-value 属性へ置く。
属性名には OpenTelemetry の semantic conventions が定義する名前を優先する。

## 例外の記録

例外は、例外オブジェクトを logger へ渡して記録する。

```java
log.atError().setCause(exception).log("Unhandled API exception");
```

OTLP では SDK が `exception.type`、`exception.message`、`exception.stacktrace` を付け、コンソールでは Spring Boot が `error.type`、`error.message`、`error.stack_trace` を出す。
`exception.*` の属性を key-value で手書きせず、例外の詳細を独自に整形しない。

予期しない 5xx 例外は `ERROR` で記録し、利用者には Problem Details の汎用の応答だけを返す。
予期した 4xx は Problem Details と HTTP status で処理し、同じ失敗を stack trace 付き `ERROR` として重複記録しない。
プロセス停止につながる構成不備などは、起動処理を担当する logger の severity と終了結果で区別する。

## ログとトレースの相関

アクティブな span 内のログには次の相関情報が付く。

- **コンソール**：Spring Boot が MDC から ECS JSON へ加える `traceId` と `spanId`
- **OTLP**：LogRecord の TraceId と SpanId

独自の request ID は追加しない。
Grafana では trace ID を使ってログとトレースを相互に検索する。
起動時やバッチの span 外で発生したログには相関情報がないため、空文字の ID を補わない。

## 発生源で渡さない値

次の値は、アプリケーションのコードから message、属性、URL、span のいずれにも渡さない。

- Authorization、Cookie、session ID、token、password、secret、接続文字列
- request body、response body、フォーム入力、DOM text
- query string、URL fragment
- SQL、認可判断で存在確認に使える値
- 氏名、メールアドレス、login ID、住所、電話番号

ログの属性で許可するのは、例外の属性（`exception.type`、`exception.message`、`exception.stacktrace`）、HTTP status、trace と span の相関情報、業務上必要な内部 ID である。
内部 ID と氏名などの表示値を同じ event へ載せない。
HTTP の URL パスは span に入るため、API のパスに個人データと秘密情報を置かない。
DTO やエンティティを logger へ渡さず、許可した値だけを個別の属性として渡す。

例外メッセージに個人データが混ざることは、例外を記録する以上避けられない。
これは発生源で禁じず、保存先の閲覧の制限と表示時のマスクで扱う。
例外メッセージに秘密情報が入る例外をアプリケーションで投げない。

渡さないと決めた値の混入を検知した場合は、[可観測性データ混入対応](runbook-data-contamination.md)に従う。

## Collector の allowlist

OTLP のデータはすべて Collector を通す。
Collector の設定（`docker/otel-collector/config.yaml`）は、ローカルと本番で共有する。

- **ログ**：transform processor の `keep_keys` で、許可した属性だけを残す。
- **トレースとメトリクス**：加工しない。計装を追加して禁止値が入る属性が見つかった場合は、transform で消す。

ログに属性を追加するときは、Collector の `keep_keys` とこの文書の許可する値を同じ変更で直す。
現在の `keep_keys` は、使っている `exception.type`、`exception.message`、`exception.stacktrace` だけを持つ。

自由入力を正規表現でマスクする処理は Collector に置かない。
表記ゆれによる取りこぼしと誤マスクが起きるため、検知は保存先のデータ保護ポリシーで行う。

## 検証

- **`task otel-collector-check`**：許可していない属性を含む OTLP のログを Collector に流し、出口に残らないことと、許可した属性が残ることを確かめる。Collector の設定を変えたら実行する。
- **`ObservabilityContractTest`**：key-value と例外が LogRecord の属性になることを確かめる。

## 本番の保存先

本番の可観測性データは CloudWatch だけに保存する（[ADR-043](../adr/ADR-043-send-production-telemetry-to-cloudwatch-via-otel-collector.md)）。

- アプリケーションは OTLP を Fargate タスクのサイドカーの OpenTelemetry Collector（contrib）へ送る。Collector はログを CloudWatch Logs、トレースを X-Ray、メトリクスを CloudWatch へ送る。
- 本番の Collector は `docker/otel-collector/config.yaml` に、exporter と拡張と各 pipeline の exporters だけを定める上書きファイルを重ねる。processors は上書きしない。
- アプリケーションのロググループ、標準出力のロググループ、`aws/spans` ロググループに CloudWatch Logs のデータ保護ポリシーを設定し、個人データと秘密情報を検知して表示時にマスクする。日本の氏名と電話番号は custom data identifier で補う。
- 標準出力は WARN 以上だけを別のロググループへ送り、起動時と Collector の障害時の調査に使う。
- ロググループの分け方は [CloudWatch Logsのロググループ](../aws/cloudwatch-logs.md) に従う。

## 保持とアクセス

通常のアプリケーションログは本番保存先で 30 日後に自動削除する。
閲覧権限は運用担当者と障害対応者に限定し、閲覧操作を監査する。
マスクを外して読む権限（`logs:Unmask`）は障害対応者に限る。
セキュリティ監査ログが必要になった場合は、通常ログと別のデータセット、権限、保持期間を定める。
ローカル LGTM は開発用の一時データに限定し、本番データを投入しない。

## 本番の受け入れ条件

本番の収集基盤は、次の条件を実環境で確認できるまでリリースしない。

1. 30 日の retention が保存先で強制される。
2. 対象期間または対象 stream を緊急削除できる。
3. 閲覧権限と監査ログが有効である。
4. trace ID からログを、ログから trace を検索できる。
5. 許可していない属性を持つ検査用 event が、保存前に属性を除かれる。
6. メールアドレスを含む例外メッセージが、表示時にマスクされる。
7. `exception.type`、`exception.message`、`exception.stacktrace` が OTLP 属性として検索できる。
