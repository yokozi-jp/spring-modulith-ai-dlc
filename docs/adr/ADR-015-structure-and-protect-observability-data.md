---
type: ADR
title: 'ADR-015: 可観測性データを構造化し保護する'
description: ログとトレースを結合し、例外の詳細を標準どおり記録したうえで、禁止値を発生源で渡さず、ログ属性を Collector の allowlist で絞り、保存先で保護する決定。
tags: [adr, observability, logging, security]
---

# ADR-015: 可観測性データを構造化し保護する

## Status

Proposed

## Date

2026-09-15

## Context

バックエンドは OpenTelemetry でログ、トレース、メトリクスを送信し、コンソールにも ECS JSON を出す。
障害を調べるには、例外の型、メッセージ、発生行、cause chain が要る。
一方、例外メッセージには利用者のデータが入ることがある。
たとえば PostgreSQL の一意制約違反の詳細には、重複したキーの値が入る。

最初の実装は、例外メッセージを一律に記録しない方針を取り、アプリケーションで独自にスタックトレースを整形し、Collector と標準出力でも例外の詳細を消していた。
この方式では、ライブラリの例外と起動時の失敗で発生行まで失われ、調査できない場面が出た。
しかも、使っていた appender の属性 allowlist の設定項目は版に存在せず、Logback が無視していた。

標準とベストプラクティスは、次の形を示している。

- 利用者には汎用の応答を返し、エラーの詳細は調査のためにサーバー側のログへ残す（OWASP Error Handling Cheat Sheet、ASVS 5.0 16.5.1、16.5.4）。
- 例外はインスタンスを渡して記録し、`exception.message` と言語の自然な表現の `exception.stacktrace` を付ける（OpenTelemetry semantic conventions）。
- credential、token、session ID、接続文字列、機微な個人データは記録しない。氏名やメールアドレスのような機微でない個人データは、記録する前か表示する前に特別な扱いを検討する（OWASP Logging Cheat Sheet、ASVS 5.0 16.2.5）。
- 機微なデータはまず集めず、制御できないデータは Collector の processor で除く（OpenTelemetry: Handling sensitive data）。
- ログは機微な資産として、不正なアクセスと改ざんから守る（ASVS 5.0 16.4）。

このリポジトリは複数のプロジェクトへ転用する開発基盤であり、標準から外れる独自の作り込みは、転用先の理解と保守の負担になる。

## Decision

### 出力の形式と相関

バックエンドのコンソールログには Spring Boot 標準の Elastic Common Schema（ECS）JSON 形式を使い、独自の JSON encoder は追加しない。
OpenTelemetry へ送るログは、OpenTelemetry Logback appender が生成する型付き LogRecord を使い、SLF4J の key-value をすべて属性にする。

ログとトレースの相関には、Micrometer Tracing が MDC へ設定する trace ID と span ID を使う。
コンソールでは Spring Boot 標準 MDC の `traceId` と `spanId`、OTLP では LogRecord の TraceId と SpanId を正本とし、独自 request ID を重ねない。
アクティブな span がないログでは、これらのフィールドを要求しない。

### 例外の記録

例外は、例外オブジェクトを logger へ渡して記録する。
OTLP では SDK が `exception.type`、`exception.message`、`exception.stacktrace` を付け、コンソールでは Spring Boot が `error.type`、`error.message`、`error.stack_trace` を出す。
例外の詳細を独自に整形したり、出口で消したりしない。
利用者への応答には例外の詳細を含めない（[ADR-013](ADR-013-standardize-http-api-contracts.md)）。

### 発生源で渡さない値

次の値は、アプリケーションのコードから message、属性、URL のいずれにも渡さない。

- Authorization、Cookie、session ID、token、password、secret、接続文字列
- request body、response body、フォーム入力、DOM text
- query string と URL fragment
- SQL、認可判断で存在確認に使える値
- 氏名、メールアドレス、login ID、住所、電話番号

アプリケーションのログは静的な event 名と、許可した構造化属性だけを出す。
内部 ID を記録する場合は、業務上必要な非公開 ID に限定し、表示名を併記しない。

例外メッセージに個人データが混ざることは、例外を記録する以上避けられない。
これは発生源で禁じず、保存先の保護と表示時のマスクで扱う。

### 出口の絞り込み

OTLP のデータはすべて OpenTelemetry Collector を通す。
Collector の transform processor で、ログの属性を allowlist（`keep_keys`）で絞る。
allowlist は Collector の設定だけに置き、ローカルと本番で共有する。

span 属性とメトリクスの tag には allowlist をかけない。
Spring Boot が既定で付ける属性と tag に禁止値が入る経路は確認されておらず、allowlist にすると計装を足すたびに設定が壊れるためである。
禁止値が入る経路が見つかった場合は、その属性を Collector で消す。

### 保存、保持、閲覧

ローカル LGTM のデータは開発用の一時データとし、本番データを投入しない。
本番の保存先では、データ保護ポリシーで個人データと秘密情報を検知し、表示時にマスクする。
通常のアプリケーションログは 30 日で自動削除する。
セキュリティ監査ログが必要になった場合は、通常ログと別のデータセット、権限、保持期間をその要件の ADR で決める。

可観測性データの閲覧権限は運用担当者と障害対応者に限定し、閲覧を監査する。
アプリケーションが渡さないと決めた値の混入を検知した場合は、該当ログの生成を止め、保存先から削除し、秘密ならローテーションし、インシデントとして記録する。
本番保存先は、保持期限による削除と対象期間または対象 stream の緊急削除を検証できなければリリースしない。

### 検証

Collector の設定は、許可していない属性を含む OTLP のログを流して出口に残らないことを確かめるテストで検証する。
アプリケーション側は、key-value と例外が LogRecord の属性になることをテストで確かめる。

## Consequences

### Positive

- ライブラリの例外と起動時の失敗も、通常のスタックトレースで調べられる。
- 例外の記録は SLF4J と appender の標準の書き方のままで済み、独自の整形処理を保守しない。
- ログを JSON と OTLP の型付き属性で検索でき、トレースから対応するログへ移動できる。
- ログ属性の allowlist が Collector の設定ファイル一つにまとまり、転用先が見直す箇所が少ない。
- 新しい encoder 依存と独自 correlation ID を持たずに済む。

### Negative

- 例外メッセージに含まれる個人データ（一意制約違反のキーの値など）が、ログの保存先に残る。閲覧権限を持つ運用担当者はこれを読める。
- 保存先のデータ保護ポリシーは日本の氏名と電話番号を標準では検知できず、custom data identifier を加える必要がある。
- ローカルの LGTM と標準出力では、例外メッセージがマスクされずに表示される。
- ローカルでも Collector のコンテナが必要になる。

### Neutral

- ローカル LGTM の named volume は開発者が明示的に初期化するまで残るが、本番の保持保証には使わない。
- トレースの sampling とログの保持は別の制御であり、同じ値にはしない。
- 監査証跡は通常のデバッグログと目的が異なるため、必要になった時点で別契約にする。

## Alternatives Considered

### Alternative 1: Logstash encoder を追加する

- Description：Logback 用の外部 JSON encoder で独自 schema とマスキング処理を実装する。
- Pros：フィールド名と変換処理を細かく制御できる。
- Cons：Spring Boot が ECS JSON を標準提供しており、依存と保守箇所だけが増える。OTLP の経路には効かない。

### Alternative 2: すべての文字列へ正規表現マスキングを適用する

- Description：メールアドレスや token に見える文字列を、Logback filter や Collector で保存前に置換する。
- Pros：例外メッセージに混ざった個人データも保存前に隠せる。
- Cons：表記ゆれで漏えいし、通常値の誤マスクも起きる。保存先のデータ保護ポリシーが同じ検知を表示時に行える。

### Alternative 3: 例外メッセージを記録しない

- Description：例外の型と、メッセージを除いた stack frame だけを独自に整形して記録し、ライブラリの例外の詳細は Collector と標準出力で消す。
- Pros：例外メッセージ由来の個人データが保存先に残らない。
- Cons：標準が推奨する記録方法から外れ、独自の整形処理と出口の処理を保守する必要がある。ライブラリの例外と起動時の失敗で発生行まで失われ、調査できない場面が出る。

### Alternative 4: アプリケーションの中で属性を絞る

- Description：appender の属性 allowlist や SDK の processor で、アプリケーションから出る前に属性を絞る。
- Pros：Collector がない環境でも効く。
- Cons：appender の属性 allowlist は使っている版に存在しない。ログ、トレース、標準出力で別々の仕組みが要る。

### Alternative 5: Collector の redaction processor を使う

- Description：redaction processor の `allowed_keys` で、ログ、span、メトリクスの属性を allowlist にする。
- Pros：allowlist と値のマスクを一つの processor で書ける。
- Cons：ログへの対応が alpha で、span 属性とメトリクスまで allowlist になる。transform processor なら、ログへの対応が beta で、ログだけを絞れる。

## References

- [可観測性データの規約](../observability/conventions.md)
- [可観測性データ混入対応](../observability/runbook-data-contamination.md)
- [ADR-043](ADR-043-send-production-telemetry-to-cloudwatch-via-otel-collector.md)
- [OWASP Error Handling Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Error_Handling_Cheat_Sheet.html)
- [OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html)
- [OWASP ASVS 5.0 V16 Security Logging and Error Handling](https://github.com/OWASP/ASVS/blob/v5.0.0/5.0/en/0x25-V16-Security-Logging-and-Error-Handling.md)
- [OpenTelemetry: Exceptions in Logs](https://opentelemetry.io/docs/specs/semconv/exceptions/exceptions-logs/)
- [OpenTelemetry: Handling sensitive data](https://opentelemetry.io/docs/security/handling-sensitive-data/)
- [OpenTelemetry: Collector configuration best practices](https://opentelemetry.io/docs/security/config-best-practices/)
- [Transform processor](https://github.com/open-telemetry/opentelemetry-collector-contrib/blob/v0.161.0/processor/transformprocessor/README.md)
- [OpenTelemetry Logback Appender](https://github.com/open-telemetry/opentelemetry-java-instrumentation/tree/main/instrumentation/logback/logback-appender-1.0/library)
- [Spring Boot: Structured Logging](https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.structured)
- [Spring Boot: Logging Correlation IDs](https://docs.spring.io/spring-boot/reference/actuator/tracing.html#actuator.micrometer-tracing.tracer-implementations.logging-correlation-ids)
- [Help protect sensitive log data with masking](https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/mask-sensitive-log-data.html)
- `docker/otel-collector/config.yaml`
- `backend/src/main/resources/logback-spring.xml`
