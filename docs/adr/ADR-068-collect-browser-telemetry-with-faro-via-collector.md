---
type: ADR
title: 'ADR-068: ブラウザのテレメトリを Faro Web SDK で集め、同一オリジンの /collect から Collector へ送る'
description: ブラウザの例外などのテレメトリを Faro Web SDK で集め、同一オリジンの /collect から既存の OpenTelemetry Collector の faro receiver へ送り、フロントエンド用の pipeline で許可した値だけを残す決定。本番の方針と、ua-parser-js 1.0.41 を pnpm trust policy の例外にすることも含む。
tags: [adr, observability, frontend, opentelemetry, security]
---

# ADR-068: ブラウザのテレメトリを Faro Web SDK で集め、同一オリジンの /collect から Collector へ送る

## Status

Proposed

## Date

2026-10-06

## Context

フロントエンドの開発者と運用担当者は、利用者のブラウザで起きた問題を観測できない。
バックエンドは OpenTelemetry でログ、トレース、メトリクスを Collector へ送り、ローカルでは otel-lgtm（Grafana）で見られる。
一方、フロントエンドは何も送っていないので、ブラウザだけで起きる例外、画面の遅さ、CSP 違反に気付けない。

このリポジトリは、複数のプロジェクトへ転用する開発基盤である（[ADR-015](ADR-015-structure-and-protect-observability-data.md)）。
転用先が本番の保存先を選び直せる形で、収集と保護の仕組みを用意する必要がある。
既存の判断として、次の制約がある。

- 可観測性データの出口は Collector の一つに保ち、Collector の allowlist で値を絞る（[ADR-015](ADR-015-structure-and-protect-observability-data.md)）。
- SPA と API は同一オリジンに置き、CSP は `connect-src 'self'` である（[ADR-014](ADR-014-use-same-origin-spa-security-boundary.md)）。
- フロントエンドにトップレベルの汎用の `shared` を作らない（[ADR-032](ADR-032-organize-frontend-by-business-feature.md)）。
- 本番の保存先は CloudWatch である（[ADR-043](ADR-043-send-production-telemetry-to-cloudwatch-via-otel-collector.md)）。

ブラウザの計装には成熟した標準がない。
OpenTelemetry 公式は、ブラウザの計装を experimental で、ほぼ仕様がないとしている。
Faro の形式を受ける contrib の `faroreceiver` は、Collector 0.161.0 で alpha（logs、traces）である。

過去に Grafana Faro を採る ADR 案を ADR-009 の番号で起票したことがある。
その案は、次の点で現在の構成と合わないので、この ADR で置き換える。

- 本番の保存先を未決としている（ADR-043 と矛盾する）。
- Grafana Alloy を追加している（ADR-015 の出口の一本化と矛盾する）。
- `shared/observability` に置いている（ADR-032 と矛盾する）。
- `@grafana/faro-react` のルーター計装を前提にしている。
- ADR-009 の番号がすでに使われている。

## Decision

ブラウザのテレメトリを Grafana Faro Web SDK で集め、同一オリジンの `/collect` から既存の Collector の `faro` receiver へ送る。
方針の全体は #149 に、その最初の段階は #150 にある。

### 経路と Collector

- ローカルでは、ブラウザの `POST /collect` を Vite の proxy が Collector の faro receiver（ホストの `127.0.0.1` に公開）へ転送する。
- Spring Boot はテレメトリの経路に入らない。
  認証のない利用者のブラウザから届く大量の入力を業務アプリで受けると、アプリの負荷と攻撃面が増えるためである。
- 受け口を同一オリジンに置くので、CORS の設定も CSP の `connect-src` の変更も要らない。
- Grafana Alloy は入れず、出口を既存の Collector の一つに保つ。
- フロントエンド用の pipeline（`logs/frontend`、processor は `transform/frontend_logs`）を、バックエンド用と分ける。
  バックエンドのログ属性の allowlist はそのまま残す。
- faro receiver の traces は、フロントエンド用の `traces/frontend`（processor は `filter/frontend_span_events` と `transform/frontend_traces`）で受ける。
  バックエンドの `traces` pipeline は加工しないまま残す。

faroreceiver はブラウザ、ページ、セッションなどのメタデータをログの本文（logfmt）に入れるので、属性の `keep_keys` だけでは保護できない。
そのため、フロントエンドの pipeline は本文を OTTL の `ParseKeyValue` で解析し、`exception.type`、`exception.message`、`exception.stacktrace`、`url.path` の 4 属性だけを残す。
本文は固定の文字列 `Browser exception` に置き換える。
例外でない種類の項目は、計装を足す段階で本文の文字列を決めるまで捨てる。

URL の query と fragment は、ブラウザの `beforeSend` と Collector の両方で消す。
Collector の除去は、絶対 URL の `?` か `#` から空白かエスケープまでを多めに消す正規表現の処理である。
これは「自由入力を正規表現でマスクする処理を Collector に置かない」規則の例外であり、フロントエンドの pipeline に限る。
例外にするのは、自由入力を推測して探すのではなく、URL という構文の決まった部分を消すためである。
消し過ぎて表示が崩れることは、秘密が残ることより害が小さい。

フロントエンドの span は、送り手が書ける値を信頼せず、許可したものだけを残す。
resource 属性はログと同じ 4 つに絞り、`service.name` は Collector の値で上書きする。
span の属性は `http.request.method`、`http.response.status_code`、`url.path` の 3 つだけにする。
`url.path` は `url.full` を OTTL の `URL()` で解析して取り出し、`url.full` は残さない。
正規表現で query を消すより、URL の構文で path だけを取り出すほうが漏れがないためである。
span の名前は固定の `Browser request` にし、status の message、`trace_state`、links、instrumentation scope の名前と版と属性も固定の値か空にする。
span の event は例外のメッセージなどを持ちうるので、すべて捨てる。
trace ID、span ID、親の span ID は属性ではないので残り、バックエンドの span とつながる。

### SDK とセッション

- 入れるのは `@grafana/faro-web-sdk`（後の段階で `@grafana/faro-web-tracing`）だけにし、exact version に固定する。
  `@grafana/faro-react` は入れない。
- `getWebInstrumentations()` の既定は使わず、有効にする計装を列挙する。
  最終的に有効にするのは Errors、WebVitals、Session、View、Tracing で、Console、Performance、UserAction、Frustration、Navigation、CSP は無効のままにする。
- SDK を呼ぶコードは `frontend/src/lib/telemetry.ts` の 1 ファイルにまとめ、初期化は composition root の `main.tsx` で行う。
  feature から SDK を呼ばず、route のエラー表示はこのファイルの関数を通して例外を送る。
- 画面遷移は、TanStack Router の遷移の完了時に route の template を View として通知する。
- `traceparent` は同一オリジンの `/api/**` だけに付ける。
  範囲は Faro の設定の `ignoreUrls` に「同一オリジンの `/api/**` 以外」に一致する正規表現を 1 つ渡して決める。
  IdP を含む別オリジンには `propagateTraceHeaderCorsUrls` を指定しないので付かず、`/collect` は Faro の既定の除外で付かない。
  `TracingInstrumentation` の `fetchInstrumentationOptions.ignoreUrls` に渡すと、transport の既定の `/collect` の除外を上書きして消すので使わない。
- ブラウザでは sampling しない。
  web-tracing の sampler は、Faro の session の meta が `isSampled` を持つときだけ記録する。
  Session の計装（#152）を入れるまでは、`sessionTracking.session` の初期値で `isSampled` を渡し、sampled flag が 0 の `traceparent` を送らないようにする。
- 送信の失敗や受け口の停止でアプリを止めず、SDK の初期化を待たずに描画を始める。
- Cookie を Collector へ送らない（`credentials: "omit"`）。
- セッションは Faro の既定（sessionStorage、15 分の非操作か 4 時間で切り替え）を使い、ログアウトで作り直し、利用者の ID や `APP_SESSION` と結び付けない。

有効にするかはビルド時の定数（ルートの `.env` の `FRONTEND_OTEL_ENABLED`）で決め、既定は無効にする。
無効のビルドでは `import()` の分岐ごと消え、bundle に SDK のコードが入らない。

### 名前と resource 属性

- バックエンドとフロントエンドの両方に `service.namespace=demo` を付け、`service.name` を `demo-api` と `demo-web` にする。
  namespace を見ない保存先（X-Ray は `service.name` で表示する）でも区別できるよう、名前にも namespace を含める。
- 値の正本はルートの `.env` に置き、namespace は両方が同じ `OTEL_SERVICE_NAMESPACE` を読む。
- `service.version`（フロントエンドは `version.txt`、バックエンドは `spring.application.version`）と `deployment.environment.name` を付ける。

### CSP 違反の報告

- W3C の Reporting API（`Reporting-Endpoints` と CSP の `report-to`）で、ブラウザ自身が `/csp-report` へ送り、Collector の `webhook_event` receiver で受ける。
- 対象は CSP だけにする。
- `webhook_event` で受けられなければ Faro の CSP の計装に切り替え、その理由をこの ADR に追記する。
  二重に送らないよう、どちらか一方だけにする。

### 段階

最初の段階（#150）で入れるのは Errors だけである。
2 つ目の段階（#151）で、`@grafana/faro-web-tracing` の Tracing（fetch と XHR）と `traces/frontend` を入れる。
SQL の span は、ブラウザと関係なく効くバックエンドの判断なので、[ADR-070](ADR-070-record-sql-spans-with-jooq-execute-listener.md) に分ける。
WebVitals、Session、View、CSP 違反の報告は、#149 の 3/4 と 4/4 のチケットで入れる。

### 本番の方針

- 本番の受け口は、アプリのサイドカーと分けた受信専用の Collector（gateway）に置く。
  ADR-043 のサイドカーは `localhost` で待ち受けるので、ブラウザから届かない。
  また、tail sampling は同じ trace の span を 1 か所に集めないと動かない。
- 入口（CDN か ALB と WAF）で、サイズ、レート、Origin を制限する。
- sampling は gateway の tail sampling で決める（エラーと遅い trace を残し、通常の trace は一定割合にする）。
- 本番の保存先は CloudWatch（ADR-043）とし、exporter の上書きで変えられる。
- ソースマップは hidden で生成し、配信物から除き、非公開の場所に release ごとに保存する。
  最初は必要なときに手で元に戻す。
  件数が増えたら Collector で戻す方式に移る。
- 本番で有効にする前の条件として、本番の受け口、法務の確認（EU の ePrivacy 指令、日本の電気通信事業法の外部送信規律）、ADR-015 の受け入れ条件（保持期限による削除と緊急削除の検証）を満たす。

### 依存の信頼の例外

`frontend/pnpm-workspace.yaml` の `trustPolicyExclude` に `ua-parser-js@1.0.41` を、[ADR-022](ADR-022-exclude-semver-from-pnpm-trust-policy.md) の `semver@6.3.1` と並べて足す。
版を固定して例外にし、別の版へ例外が広がらないようにする。
`trustPolicy: no-downgrade`、`minimumReleaseAge`、`blockExoticSubdeps` は変えない。

`@grafana/faro-web-sdk` 2.12.1 は、`ua-parser-js` を 1.0.41 に exact で固定している。
`ua-parser-js` の 1.x の系列は npm provenance を一度も持たず、2.x の系列は 2.0.0-alpha.3（2023 年）から持つ。
pnpm は公開日で比べるので、2025-08-19 に公開した 1.0.41 を信頼の低下とみなし、`ERR_PNPM_TRUST_DOWNGRADE` で導入を止める。

registry の tarball には integrity と署名がある。
ただし、integrity が示すのは内容の一致だけで、誰が公開したかは示さない。
この依存が実行時の bundle に入るのは `FRONTEND_OTEL_ENABLED=true` でビルドしたときだけで、既定の無効のビルドには SDK のコードが入らない。

Faro が 1.0.41 を固定しなくなったら、この例外を外す。
Faro を更新するたびに、lockfile で `ua-parser-js` の版を確かめる。

## Consequences

### Positive

- ブラウザの例外を、バックエンドと同じ Grafana で `service.name=demo-web` として見られる。
- 出口が Collector の一つのままなので、本番の保存先は exporter の上書きで選び直せる。
- 許可した 4 属性と固定の本文だけが保存先に届き、ブラウザのメタデータ（user agent、セッション、URL の query など）は残らない。
- 既定のビルドには SDK が入らないので、転用先は明示的に有効にするまで利用者のデータを集めない。
- SDK を差し替えるときの変更が `lib/telemetry.ts` の 1 ファイルに収まる。

### Negative

- faroreceiver は処理に失敗すると、payload の全体を Collector の標準出力に ERROR で出す。
  利用者のデータ（URL、user agent など）が Collector のログに残りうる。
  本番では、Collector のログの保持と閲覧をテレメトリと同じ扱いにする必要がある。
- faroreceiver は alpha で、Collector の更新で壊れうる。
  `task otel-collector-check` の Faro の fixture で検出する。
- 本文の解析は、faroreceiver が書く logfmt（go-logfmt）と OTTL の `ParseKeyValue` の差に依存する（`'` を解析の間だけ U+0001 に置き換えている）。
- ブラウザの `beforeSend` が消す相対 URL の query は、文字列の先頭、空白、`(`、`"`、`'`、`=` の直後の `/` から始まるものに限る（`:` の直後などは残る）。
  Collector はログの相対 URL の query を消さない。
  span の URL は Collector で `url.path` だけを残すので、query は保存先に届かない。
- Faro の chunk は使わない計装も含むので、有効のときの chunk が大きい（約 112.5 kB、gzip で約 38.3 kB）。
- Tracing を入れると、有効のビルドの JavaScript が合わせて約 89.3 kB（gzip で約 28.7 kB）増える。
  web-tracing と OpenTelemetry JS の chunk（84.1 kB、gzip で 26.3 kB）と、SDK と共有する chunk（3.0 kB、gzip で 1.3 kB）が増え、SDK の chunk が約 1.6 kB 増える。
  無効のビルドの bundle は変わらない。
- `ignoreUrls` は Tracing だけでなく Faro の全計装の除外にも効く。
  Performance や UserAction を有効にするときは、範囲の指定を Tracing の option に移す必要がある。
- `ua-parser-js@1.0.41` を provenance のないまま受け入れる。

### Neutral

- バックエンドの名前が `demo` から `demo-api` に変わるので、既存の Grafana の検索を直す必要がある。
- `docker/otel-collector/config.yaml` は本番と共有する（ADR-043）ので、本番のサイドカーの Collector も faro receiver を起動する。
  gateway を作るまでは、サイドカーの faro receiver は `localhost` で待ち受け、ブラウザから届かないので何も受けない。
- 本番の上書きファイルは `logs/frontend` と `traces/frontend` の exporters も定めなければならず、定めなければ `otlp_http/lgtm` のままになる。
- gateway を作るときに、faro receiver と `logs/frontend` と `traces/frontend` を共有の設定から gateway の設定へ移す。
- ルートの `.env` と `.env.test` を、新しい `.env.example` と `.env.test.example` から作り直す必要がある。

## Alternatives Considered

### 選択肢1: Grafana Alloy を追加する

- **Description**：Faro の受け口として Grafana Alloy を Collector と別に置く。
- **Pros**：Faro の公式の受け口で、設定例が多い。
- **Cons**：出口が二つになる。contrib の Collector に同じ upstream の receiver がある。

### 選択肢2: Spring Boot で受ける

- **Description**：`/collect` を Spring Boot の endpoint で受け、OTLP で Collector へ送る。
- **Pros**：受け口を増やさず、既存の API と同じ経路で受けられる。
- **Cons**：認証のない大量の入力を業務アプリで受けることになる。Collector の役割と重なる。

### 選択肢3: Sentry（または互換の GlitchTip）

- **Description**：ブラウザの例外を Sentry の SDK と保存先で扱う。
- **Pros**：エラーの画面が優れている。
- **Cons**：OTel と別の製品と保存先になり、バックエンドのトレースとつなぎにくい。

### 選択肢4: OpenTelemetry JS をブラウザで直接組み立てる

- **Description**：OpenTelemetry JS の SDK と計装をブラウザで組み合わせ、OTLP で送る。
- **Pros**：Faro の形式を介さず、OTel の形式のまま送れる。
- **Cons**：公式に experimental で、エラー、Web Vitals、セッション、送信を自前で組む範囲が広い。

### 選択肢5: CloudWatch RUM

- **Description**：本番の保存先の AWS が提供するブラウザの監視を使う。
- **Pros**：本番の保存先（ADR-043）と同じ AWS の中で完結する。
- **Cons**：ブラウザから AWS のエンドポイントへ直接送るので、`connect-src 'self'` を緩める必要があり、Collector の allowlist（ADR-015）も通らない。

### 選択肢6: `@grafana/faro-react`

- **Description**：Faro の React 向けパッケージのエラー境界とルーター計装を使う。
- **Pros**：React の component のエラーを Faro の部品で捕捉できる。
- **Cons**：ルーター計装が React Router 向けで、TanStack Router に使えない。エラーの捕捉は既存の route のエラー表示で足りる。

### 選択肢7: `getWebInstrumentations()` の既定

- **Description**：Faro の既定の計装の一覧をそのまま有効にする。
- **Pros**：設定が短い。
- **Cons**：Console、リソースの URL、クリックとキー入力まで集める。

### 選択肢8: ブラウザで head sampling する

- **Description**：Faro のセッションの samplingRate を 1 未満にして、ブラウザで送る量を減らす。
- **Pros**：ブラウザの送信量と保存先の量が減る。
- **Cons**：sampled flag が 0 の `traceparent` が届くと、バックエンドの trace も消える。

### 選択肢9: localStorage の persistent session

- **Description**：Faro のセッションを localStorage に保存し、タブとブラウザの再起動をまたいで続ける。
- **Pros**：複数のタブと日をまたぐ利用者の操作をつなげられる。
- **Cons**：日をまたいで同じ利用者を追える ID になり、プライバシーの判断が要る。

### 選択肢10: 本番のビルドで常に有効にする

- **Description**：ビルド時の切り替えを置かず、すべてのビルドに SDK を含めて送る。
- **Pros**：設定の漏れで収集が止まることがない。
- **Cons**：本番の受け口を誰かが足した時点で、法務の確認と ADR-015 の受け入れ条件を経ずに収集が始まる。転用先もそのまま引き継ぐ。

### 選択肢11: ソースマップを公開する

- **Description**：ソースマップを配信物に含め、ブラウザや保存先から読めるようにする。
- **Pros**：スタックトレースを元のコードの位置で読む手間がない。
- **Cons**：元のコード、コメント、内部の構造が見える。

### 選択肢12: 本文を `replace_pattern` で書き換える

- **Description**：faroreceiver が作る本文の logfmt から、`user_*`、`session_*`、`browser_*` などの組を正規表現で消し、本文を残す。
- **Pros**：本文の解析が要らず、例外の文脈を本文のまま読める。
- **Cons**：消す組を列挙する denylist になる。Faro の更新でメタデータの項目が増えると、そのまま保存先へ届く。

### 選択肢13: `OTEL_RESOURCE_ATTRIBUTES` で namespace を渡す

- **Description**：#149 の例のとおり、バックエンドの `service.namespace` を標準の環境変数 `OTEL_RESOURCE_ATTRIBUTES` で渡す。
- **Pros**：OpenTelemetry の標準の変数で、`application.yaml` を変えずに済む。
- **Cons**：フロントエンドの `vite.config.ts` が同じ値を読むには、組の並びを解析する必要があり、値を共有しにくい。`application.yaml` が各属性を個別の変数で必須にする方針とも合わない。

### 選択肢14: `ua-parser-js` を 2.x へ override する

- **Description**：pnpm の `overrides` で、Faro の依存の `ua-parser-js` を provenance のある 2.x に差し替え、trust policy の例外を置かない。
- **Pros**：`trustPolicyExclude` を増やさずに済む。
- **Cons**：2.x は AGPL-3.0-or-later で、1.x の MIT から license が変わる。Faro は 2.x で試されていない。

## References

- [#149: ブラウザのテレメトリをバックエンドと同じ Grafana で見られるようにする](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/149)
- [#150: 1/4 ブラウザの例外を Grafana で見られるようにする](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/150)
- [faroreceiver v0.161.0 の receiver.go](https://github.com/open-telemetry/opentelemetry-collector-contrib/blob/v0.161.0/receiver/faroreceiver/receiver.go)
- [faro_to_logs.go v0.161.0](https://github.com/open-telemetry/opentelemetry-collector-contrib/blob/v0.161.0/pkg/translator/faro/faro_to_logs.go)
- [OTTL の ParseKeyValue](https://github.com/open-telemetry/opentelemetry-collector-contrib/blob/v0.161.0/pkg/ottl/ottlfuncs/README.md#parsekeyvalue)
- [#151: 2/4 画面の操作から DB までを一本のトレースで見られるようにする](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/151)
- [Grafana Faro Web SDK 2.12.1](https://www.npmjs.com/package/@grafana/faro-web-sdk/v/2.12.1)
- [Grafana Faro Web Tracing 2.12.1](https://www.npmjs.com/package/@grafana/faro-web-tracing/v/2.12.1)
- [OpenTelemetry の instrumentation-fetch 0.222.0](https://www.npmjs.com/package/@opentelemetry/instrumentation-fetch/v/0.222.0)
- [OpenTelemetry JavaScript](https://opentelemetry.io/docs/languages/js/)
- [ADR-014: SPA とバックエンドを同一オリジンで公開する](ADR-014-use-same-origin-spa-security-boundary.md)
- [ADR-015: 可観測性データを構造化し保護する](ADR-015-structure-and-protect-observability-data.md)
- [ADR-022: semver 6.3.1 を pnpm trust policy の例外にする](ADR-022-exclude-semver-from-pnpm-trust-policy.md)
- [ADR-032: Frontend を業務機能単位で構成する](ADR-032-organize-frontend-by-business-feature.md)
- [ADR-043: 本番の可観測性データを OpenTelemetry Collector で CloudWatch へ送る](ADR-043-send-production-telemetry-to-cloudwatch-via-otel-collector.md)
- [ADR-070: SQL の span を jOOQ の ExecuteListener で作り、SQL の文と値を入れない](ADR-070-record-sql-spans-with-jooq-execute-listener.md)
