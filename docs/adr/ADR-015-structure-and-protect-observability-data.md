# ADR-015: 可観測性データを構造化し保護する

## Status

Proposed

## Date

2026-09-15

## Context

バックエンドは OpenTelemetry でログ、トレース、メトリクスを送信するが、コンソールログは非構造化であり、ログとトレースを結ぶフィールドの契約がない。
アプリケーション固有のログは未処理例外の一箇所だけだが、例外メッセージと通常の stack trace の先頭行には利用者入力、メールアドレス、SQL、秘密情報が混入し得る。
一方、stack frame まで捨てると、例外が発生した class、method、ファイル、行番号をログから調査できない。

ローカル開発では Grafana OpenTelemetry LGTM を使う一方、本番の収集基盤、アクセス制御、削除機能はまだ実装していない。
保持期間をアプリケーションコードだけに書いても保存先では強制できないため、保存先を導入するときの受け入れ条件まで定める必要がある。

## Decision

バックエンドのコンソールログには Spring Boot 標準の Elastic Common Schema（ECS）JSON 形式を使う。
独自の JSON encoder は追加しない。
OpenTelemetry へ送るログは、既存 appender が生成する型付き LogRecord を維持する。

ログとトレースの相関には、Micrometer Tracing が MDC へ設定する trace ID と span ID を使う。
コンソールでは Spring Boot 標準 MDC の `traceId` と `spanId`、OTLP では LogRecord の TraceId と SpanId を正本とし、独自 request ID を重ねない。
アクティブな span がないログでは、これらのフィールドを要求しない。

アプリケーションログは静的な event message と許可した構造化属性だけを出す。
次の値はログへ出さない。

- Authorization、Cookie、session ID、token、password、secret
- request body、response body、フォーム入力、DOM text
- 氏名、メールアドレス、login ID、住所、電話番号
- query string と URL fragment
- 例外メッセージ、SQL、認可判断で存在確認に使える値

未処理例外には OpenTelemetry semantic conventions の `exception.type` と `exception.stacktrace` を使う。
`exception.stacktrace` は root、cause、suppressed exception の型と `StackTraceElement` だけから構成し、各例外の message を含めない。
循環する例外参照は有限の表現に変換する。
例外オブジェクトそのものは logger へ渡さない。

OpenTelemetry appender が SLF4J key-value から OTLP 属性へ転送できる名前は、`exception.type` と `exception.stacktrace` の allowlist で制限する。
全 key-value の capture は有効にしない。
内部 ID を記録する場合は、業務上必要な非公開 ID に限定し、表示名を併記しない。
値を追加する変更では allowlist をレビューし、自由入力を正規表現だけでマスクする方式へ依存しない。

ローカル LGTM のデータは開発用の一時データとし、本番データを投入しない。
本番の保存先を導入する際は、collector または保存先で denylist を第二防御として適用し、通常のアプリケーションログを 30 日で自動削除する。
セキュリティ監査ログが必要になった場合は、通常ログと別のデータセット、権限、保持期間をその要件の ADR で決める。

可観測性データの閲覧権限は運用担当者と障害対応者に限定し、閲覧を監査する。
PII または秘密情報の混入を検知した場合は、該当ログの生成を止め、保存先から削除し、秘密ならローテーションし、インシデントとして記録する。
本番保存先は、保持期限による削除と対象期間または対象 stream の緊急削除を検証できなければリリースしない。

## Consequences

### Positive

- ログを JSON と OTLP の型付き属性で検索でき、トレースから対応するログへ移動できる。
- 新しい encoder 依存と独自 correlation ID を持たずに済む。
- 例外メッセージに混入した PII と秘密情報がアプリケーション固有ログへ流れる経路を閉じられる。
- 例外の発生 class、method、ファイル、行番号と cause chain をログから調査できる。
- 本番基盤の導入時に、保持と削除を実装後へ先送りしにくくなる。

### Negative

- 例外メッセージを記録しないため、メッセージだけに診断情報を持つ第三者例外では trace と再現情報を併用する必要がある。
- message を除去した stack trace は JDK が出力する完全な自然表現とは異なり、小さな整形処理を保守する必要がある。
- ログへ追加できる属性が allowlist に制約され、調査用の自由な値をその場で増やせない。
- 本番保存先が未実装のため、30 日保持の自動削除は現時点では実行環境で検証できない。

### Neutral

- ローカル LGTM の named volume は開発者が明示的に初期化するまで残るが、本番の保持保証には使わない。
- トレースの sampling とログの保持は別の制御であり、同じ値にはしない。
- 監査証跡は通常のデバッグログと目的が異なるため、必要になった時点で別契約にする。

## Alternatives Considered

### Alternative 1: Logstash encoder を追加する

- Description：Logback 用の外部 JSON encoder で独自 schema とマスキング処理を実装する。
- Pros：フィールド名と変換処理を細かく制御できる。
- Cons：Spring Boot が ECS JSON を標準提供しており、現状の要件では依存と保守箇所だけが増える。

### Alternative 2: すべての文字列へ正規表現マスキングを適用する

- Description：メールアドレスや token に見える文字列を Logback filter で置換する。
- Pros：既存の自由形式ログを残しやすい。
- Cons：表記ゆれで漏えいし、通常値の誤マスクも起きるため、禁止値を最初から渡さない設計の代わりにならない。

### Alternative 3: 例外オブジェクトをそのまま logger へ渡す

- Description：未処理例外を message、stack trace、cause chain ごと console と OTLP へ保存する。
- Pros：JDK の完全な stack trace を追加実装なしで利用できる。
- Cons：例外メッセージに含まれる利用者入力と秘密情報を、保存前に確実には分離できない。

### Alternative 4: 例外型だけを記録する

- Description：`exception.type` と trace 相関だけを記録し、stack frame を捨てる。
- Pros：例外メッセージ由来の漏えい経路を最小化できる。
- Cons：トレースだけでは Java の発生行を特定できず、障害調査に必要な情報まで失う。

## References

- [Spring Boot: Structured Logging](https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.structured)
- [Spring Boot: Logging Correlation IDs](https://docs.spring.io/spring-boot/reference/actuator/tracing.html#actuator.micrometer-tracing.tracer-implementations.logging-correlation-ids)
- [OpenTelemetry: Exceptions in Logs](https://opentelemetry.io/docs/specs/semconv/exceptions/exceptions-logs/)
- [OpenTelemetry Logback Appender](https://github.com/open-telemetry/opentelemetry-java-instrumentation/tree/main/instrumentation/logback/logback-appender-1.0/library)
- [OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html)
- [`ADR-013`](./ADR-013-standardize-http-api-contracts.md)
- `backend/src/main/resources/logback-spring.xml`
