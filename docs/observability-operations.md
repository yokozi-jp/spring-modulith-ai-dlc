# 可観測性の運用

バックエンドはログ、トレース、メトリクスを OpenTelemetry で送信する。
ローカルでは Grafana OpenTelemetry LGTM を確認用に使い、本番の保存先として扱わない。
設計判断は [`ADR-015`](adr/ADR-015-structure-and-protect-observability-data.md) に記録している。

## ログとトレースの相関

コンソールログは一行一 JSON の Elastic Common Schema（ECS）で出力する。
OpenTelemetry appender は同じ Logback event を OTLP の LogRecord として送る。

アクティブな span 内のログには次の相関情報が付く。

- **コンソール**：Spring Boot が MDC から ECS JSON へ加える `traceId` と `spanId`
- **OTLP**：LogRecord の TraceId と SpanId

独自の request ID は追加しない。
Grafana では trace ID を使ってログとトレースを相互に検索する。
起動時やバッチの span 外で発生したログには相関情報がないため、空文字の ID を補わない。

アプリケーションログの message は検索可能な静的 event 名にし、変動する値は SLF4J の key-value 属性へ置く。
属性名には OpenTelemetry の semantic conventions が定義する名前を優先する。

## 例外の診断情報

予期しない 5xx 例外は `ERROR` とし、`exception.type`、`exception.stacktrace`、trace ID、span ID を記録する。
`exception.stacktrace` は root、cause、suppressed exception の class 名、method 名、ファイル名、行番号から作る。
例外メッセージは含めない。
循環する cause または suppressed exception は、型を持つ有限の循環参照表現へ変換する。

予期した 4xx は Problem Details と HTTP status で処理し、同じ失敗を stack trace 付き `ERROR` として重複記録しない。
プロセス停止につながる構成不備などは、起動処理を担当する logger の severity と終了結果で区別する。

## PII と秘密情報

ログへ渡せる値は allowlist で管理する。
現時点で許可するのは、`exception.type`、メッセージを除いた `exception.stacktrace`、HTTP status、trace と span の相関情報、業務上必要な内部 ID である。
OpenTelemetry appender が SLF4J key-value から転送する名前も `exception.type` と `exception.stacktrace` に限定する。
内部 ID と氏名などの表示値を同じ event へ載せない。

次の値は message、属性、例外、URL のいずれにも含めない。

- Authorization、Cookie、session ID、token、password、secret
- request body、response body、フォーム入力、DOM text
- 氏名、メールアドレス、login ID、住所、電話番号
- query string、URL fragment
- 例外メッセージ、SQL、認可判断で存在確認に使える値

例外オブジェクトそのものは logger へ渡さない。
アプリケーションは `StackTraceElement` から message を含まない診断用 stack trace を生成する。

本番 collector または保存先にも denylist を置くが、これは第二防御であり、アプリケーションから禁止値を送ってよい理由にはならない。
自由入力を正規表現だけでマスクする方式は、表記ゆれによる漏えいを防げないため採用しない。

## 保持とアクセス

通常のアプリケーションログは本番保存先で 30 日後に自動削除する。
閲覧権限は運用担当者と障害対応者に限定し、閲覧操作を監査する。
セキュリティ監査ログが必要になった場合は、通常ログと別のデータセット、権限、保持期間を定める。

ローカル LGTM の named volume には本番データを入れない。
ローカルデータが不要になった場合は、他サービスのデータも削除されることを確認したうえで、既存の `task compose-reset CONFIRM_RESET=yes` を使う。

本番の収集基盤をリリースする前に、次を実環境で確認する。

1. 30 日の retention が保存先で強制される。
2. 対象期間または対象 stream を緊急削除できる。
3. 閲覧権限と監査ログが有効である。
4. trace ID からログを、ログから trace を検索できる。
5. token、Cookie、メールアドレスを含む検査用 event が保存前に拒否または除去される。
6. `exception.type` と `exception.stacktrace` が OTLP 属性として検索できる。

## 混入時の対応

PII または秘密情報を検知した場合は、該当ログの生成を止め、保存先の対象データを削除する。
秘密情報なら同時にローテーションし、アクセス履歴を確認する。
対象期間、データ種別、削除結果、再発防止をインシデント記録へ残す。
