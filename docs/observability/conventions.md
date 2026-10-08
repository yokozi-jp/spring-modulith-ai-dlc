---
type: Convention
title: 可観測性データの規約
description: OpenTelemetry へ記録するデータ、例外の記録、ログとトレースの相関、発生源で渡さない値、Collector の allowlist、本番のロググループ、保持、アクセス、本番の受け入れ条件を定め、可観測性データの実装、Collector の設定、本番収集基盤を変更するとき、フロントエンドのテレメトリに値を足すときに読む規約。
tags: [convention, observability, opentelemetry, security]
---

# 可観測性データの規約

バックエンドはログ、トレース、メトリクスを OpenTelemetry で Collector へ送ってコンソールにも ECS JSON を出し、フロントエンドは Faro Web SDK でブラウザの例外、画面遷移、Core Web Vitals、同一オリジンの `/api/**` の要求の trace を Collector へ送る。
例外は logger へ渡して標準どおりに記録し、禁止値はアプリケーションから渡さない。
ログの属性は Collector の allowlist で絞り、保存先で閲覧の制限と表示時のマスクを行う。
設計判断は [ADR-015](../adr/ADR-015-structure-and-protect-observability-data.md)、[ADR-043](../adr/ADR-043-send-production-telemetry-to-cloudwatch-via-otel-collector.md)、[ADR-045](../adr/ADR-045-remove-aws-docs-and-production-cd-example.md)、[ADR-068](../adr/ADR-068-collect-browser-telemetry-with-faro-via-collector.md)、[ADR-070](../adr/ADR-070-record-sql-spans-with-jooq-execute-listener.md) に記録している。

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

- Authorization、Cookie、認証 session ID、利用者へ結び付く ID、token、password、secret、接続文字列
- request body、response body、フォーム入力、DOM text
- query string、URL fragment
- SQL、認可判断で存在確認に使える値
- 氏名、メールアドレス、login ID、住所、電話番号

ログの属性で許可するのは、例外の属性（`exception.type`、`exception.message`、`exception.stacktrace`）、HTTP status、409 の種類（`conflict.kind`）、trace と span の相関情報、業務上必要な内部 ID である。
内部 ID と氏名などの表示値を同じ event へ載せない。
HTTP の URL パスは span に入るため、API のパスに個人データと秘密情報を置かない。
ブラウザの例外は画面の route template を `view.name` に持つため、画面の path にも個人データと秘密情報を置かず、識別子は内部 ID にする（[フロントエンドのURL設計](../frontend/url-design.md)）。
DTO やエンティティを logger へ渡さず、許可した値だけを個別の属性として渡す。

SQL の span（jOOQ の `jooq.query`）は、SQL の種類だけを名前に持ち、SQL の文、バインドの値、例外のメッセージを持たない（[ADR-070](../adr/ADR-070-record-sql-spans-with-jooq-execute-listener.md)）。

ブラウザでは、TanStack Router が解決した route template だけを画面の `view.name` に使う。
例外のメッセージと stacktrace に含まれる絶対 URL と `/` で始まる相対 URL は、scheme、path、query、fragment を含む token 全体を `[redacted-url]` に置き換える。
ただし、同一オリジンのビルドのファイル名（`/assets/<名前>.js`）に行と列が続く stack frame は、origin を落とした path と行と列だけを残す。
hash 付きのファイル名は秘密を含まず、source map で元の位置に戻すのに要るためである。
query か fragment を持つ URL、別オリジンの URL、`/assets/` 以外の path は、ファイル名が `.js` で終わっても token 全体を置き換える。
ブラウザ API trace の `url.*` 属性はすべて削除し、trace ID、span ID、親 span ID は維持する。
Session、View、Errors、WebVitals、Tracing だけを明示的に有効にし、Console、Performance、UserAction、Frustration、Navigation、CSP は有効にしない。
Web Vitals は LCP、INP、CLS の主値を一つだけ残し、付加情報を送らない。
匿名の Faro session ID は同じタブの signal の相関だけに使い、`sessionStorage` に保持する。
ログアウトの送信前に session ID を切り替え、`APP_SESSION` と利用者情報へ結び付けない。
`traceparent` は同一オリジンの `/api/**` への fetch と XHR だけに付け、IdP を含む別オリジン、`/collect`、`/api/**` 以外のパスには付けない。
Collector へは Cookie を送らない（`credentials: "omit"`）。

ブラウザの例外と trace を送るかはビルド時の `FRONTEND_OTEL_ENABLED` で決め、既定は無効にする（[ADR-068](../adr/ADR-068-collect-browser-telemetry-with-faro-via-collector.md)）。
環境ごとの値は次のとおり。

- 開発（`.env`）：既定は `false`。ローカルで例外を送って確かめるときだけ `true` にし、`task compose-up` と `vp dev` の再起動で有効にする（README の手順）。
- E2E（`.env.test`）：`true`。`task e2e` が Faro を有効にしてビルドし、`/collect` は `page.route` で止める。
- CI の本番ビルド検査（`task fe-test-build`）：`false` を強制し、無効のビルドに SDK と OpenTelemetry JS が入らないことを grep で確かめる。
- 本番と STG：faro receiver を公開する gateway を作るまで無効にする（親 Issue #149）。gateway が整うまで有効にしても受け口がないため届かない。

例外メッセージに個人データが混ざることは、例外を記録する以上避けられない。
これは発生源で禁じず、保存先の閲覧の制限と表示時のマスクで扱う。
例外メッセージに秘密情報が入る例外をアプリケーションで投げない。

渡さないと決めた値の混入を検知した場合は、[可観測性データ混入対応](runbook-data-contamination.md)に従う。

## Collector の allowlist

OTLP のデータはすべて Collector を通す。
Collector の設定（`docker/otel-collector/config.yaml`）は、ローカルと本番で共有する。

- **ログ**：transform processor の `keep_keys` で、許可した属性だけを残す。
- **バックエンドのトレースとメトリクス**：加工しない。計装を追加して禁止値が入る属性が見つかった場合は、transform で消す。

ログに属性を追加するときは、Collector の `keep_keys` とこの文書の許可する値を同じ変更で直す。
現在の `keep_keys` は、使っている `exception.type`、`exception.message`、`exception.stacktrace`、`http.response.status_code`、`conflict.kind` だけを持つ。

フロントエンドのログは、バックエンドと別の pipeline（`logs/frontend`）で絞る。
検証、filter、正規化の三段で faro receiver の logfmt 本文を処理し、検証済みの record だけを保存先へ送る。

許可する record は次のとおりである。

- 例外は本文を `Browser exception` に固定し、`telemetry.signal`、`exception.type`、`exception.message`、任意の `exception.stacktrace`、`session.id`、検証済み View がある場合の `view.name` だけを残す。
- 画面遷移は本文を `Browser view` に固定し、`telemetry.signal`、`view.name`、`session.id` だけを残す。
- Web Vitals は本文を `Browser web vital` に固定し、`telemetry.signal`、`measurement.type`、`measurement.name`、`measurement.value`、`view.name`、`session.id` だけを残す。

route template の allowlist は `/` と `/logged-out` で、`transform/frontend_validate` の 1 か所だけに書く。
`task fe-route-tree-check` と `task otel-collector-check` は、この allowlist が `routeTree.gen.ts` の `fullPaths` と一致しなければ失敗する。
route を追加するときは、Collector の allowlist とこの文書を同じ変更で更新する。
allowlist は環境変数にしない。
Collector は環境変数の既定値に `$` を書けず、本番で上書きして広げられる値にもしないためである。
session ID は `^[a-km-zA-HJ-NP-Z0-9]{10}$` に一致する匿名の Faro ID だけを許可し、UUID の `APP_SESSION` を拒否する。
この形式も `transform/frontend_validate` の 1 か所だけに書く。
resource 属性は `service.name`、`service.namespace`、`service.version`、`deployment.environment.name` だけを残し、`service.name` は Collector の `FRONTEND_OTEL_SERVICE_NAME` で上書きする。

フロントエンドのトレースは、バックエンドの `traces` と別の pipeline（`traces/frontend`）で絞る。
span の event と link を捨て、属性は検証済みの `http.request.method` と `http.response.status_code` だけを残す。
すべての `url.*` 属性を削除するが、trace ID、span ID、親 span ID は変更しない。
resource 属性はフロントエンドのログと同じ 4 つに絞り、span の名前を `Browser request`、scope の名前を `browser` に固定する。

自由入力を正規表現でマスクする処理は Collector に置かない。
表記ゆれによる取りこぼしと誤マスクが起きるため、検知は保存先のデータ保護ポリシーで行う。
URL の query と fragment の除去はこの規則の対象外とし、フロントエンドの pipeline に限って置く（[ADR-068](../adr/ADR-068-collect-browser-telemetry-with-faro-via-collector.md)）。

faro receiver は処理に失敗すると、payload の全体を Collector 自身のログに ERROR で出す（[ADR-068](../adr/ADR-068-collect-browser-telemetry-with-faro-via-collector.md)）。

## 検証

- **`task otel-collector-check`**：許可していない属性を含む OTLP のログを Collector に流し、出口に残らないことと、許可した属性が残ることを確かめる。
  Faro の fixture もフロントエンドの pipeline に流し、同じ session ID の例外、View、LCP、INP、CLS だけが残ることを確かめる。
  URL token、UUID の session ID、利用者情報、DOM 情報、追加の measurement 値が出口に残らないことも確かめる。
  同一オリジンの `/assets/<名前>.js` の stack frame が path と行と列だけで残り、query か fragment を持つ URL と別の path が置き換わることも確かめる。
  最初に、route の allowlist と `routeTree.gen.ts` の一致も確かめる。
  同じ fixture の trace は `traces/frontend` の出口を別のファイルに分けて検査し、URL 属性がなく、許可した HTTP 属性と resource 属性だけが残り、trace ID、span ID、親 span ID が保たれることを確かめる。
  faro receiver の受け口が、GET に 405、`text/plain` に 415、別のパスに 202、1 MiB を超える本文に 400 を返すことも確かめる。
  Collector の設定を変えたら実行する。
- **`ObservabilityContractTest`**：key-value と例外が LogRecord の属性になることを確かめる。

## 本番の保存先

本番の可観測性データは CloudWatch だけに保存する（[ADR-043](../adr/ADR-043-send-production-telemetry-to-cloudwatch-via-otel-collector.md)）。

- アプリケーションは OTLP を Fargate タスクのサイドカーの OpenTelemetry Collector（contrib）へ送る。Collector はログを CloudWatch Logs、トレースを X-Ray、メトリクスを CloudWatch へ送る。
- 本番の Collector は `docker/otel-collector/config.yaml` に、exporter と拡張と各 pipeline の exporters だけを定める上書きファイルを重ねる。processors は上書きしない。
  上書きファイルは `logs/frontend` の exporters も定める。
  gateway を作るまで、サイドカーの faro receiver は `localhost` で待ち受け、何も受けない（[ADR-068](../adr/ADR-068-collect-browser-telemetry-with-faro-via-collector.md)）。
- アプリケーションのロググループ、標準出力のロググループ、`aws/spans` ロググループに CloudWatch Logs のデータ保護ポリシーを設定し、個人データと秘密情報を検知して表示時にマスクする。日本の氏名と電話番号は custom data identifier で補う。
- 標準出力は WARN 以上だけを別のロググループへ送り、起動時と Collector の障害時の調査に使う。

## 本番のロググループ

本番のアプリケーションのログは、サービスごとに二つのロググループへ保存する。
ロググループを他のサービスと共有しない理由は [ADR-045](../adr/ADR-045-remove-aws-docs-and-production-cd-example.md) に記録している。

- 一つのサービスに、Collector から OTLP で送るアプリケーションのロググループと、WARN 以上の標準出力のロググループを一つずつ作る。他のサービスと共有しない。
- この二つ以外に、一つのサービスのログを複数のロググループへ分けない。セキュリティ監査ログと通常のログのように保持期間が異なる場合に限り、分けてよい。
- 複数のサービスを横断して調べるときは、CloudWatch Logs Insights で複数のロググループを指定して検索する。
- 二つのロググループの保持期間は、次節の通常のアプリケーションログの保持期間に設定し、無期限のまま残さない。
- 標準出力のロググループのログストリームはタスクごとに分ける。`awslogs` ドライバーに `awslogs-stream-prefix` を設定すると、ストリーム名にタスク ID が入る。CloudWatch Logs の緊急削除はログストリームかロググループの単位になる（[ADR-043](../adr/ADR-043-send-production-telemetry-to-cloudwatch-via-otel-collector.md)）。
- アプリケーションのロググループのログストリームの単位は未定である。ストリーム名は Collector が送る `x-aws-log-stream` ヘッダーで決まり、本番の上書きファイルはまだない。上書きファイルを作るときに、タスクごとの値を渡す方法を決める。

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
8. ブラウザの例外が `logs/frontend` から本番の保存先へ届き、ローカル向けの `otlp_http/lgtm` へ送られない。

## 出典

- フューチャー株式会社「AWS設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forAWS/aws_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- 「本番のロググループ」の節は、この原文をこのリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
