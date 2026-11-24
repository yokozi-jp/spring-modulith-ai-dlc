---
type: ADR
title: 'ADR-125: メールを SES v2 の API で送り、ローカルとテストは接続先を WireMock に向ける'
description: 本番、ローカル、テストのどれも SES v2 の SendEmail を呼ぶ同じ Client でメールを送り、ローカルとテストは AWS の SDK の接続先の環境変数だけで既存の WireMock に向ける決定。ADR-072 の形を SES に広げる。
tags: [adr, backend, mail, ses, wiremock, external-system]
---

# ADR-125: メールを SES v2 の API で送り、ローカルとテストは接続先を WireMock に向ける

## Status

Proposed

## Date

2026-11-24

## Context

このリポジトリには、メールを送る仕組み（依存、設定、compose、テストのコンテナ）がなかった。
[#323](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/323) の調査で送り方を決め、[#571](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/571) で実装する。
本番は AWS の ECS on Fargate で動かし（[ADR-041](ADR-041-run-ecs-tasks-on-fargate.md)）、メールの規約は ESP を Amazon SES とする前提で書いている（[送信ドメインの認証と評判](../mail/sending-domain.md)）。
ESP を選んだ理由は [#675](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/675) で別の ADR に残す。
この ADR は、SES を前提にして送り方だけを決める。

送り方は、次の 2 つの制約を満たす必要がある。

- 本番は長期の資格情報を持たない。
  SES の SMTP インタフェースは、IAM ユーザーの長期の資格情報から作る SMTP の資格情報でしか認証できない（[Obtaining Amazon SES SMTP credentials](https://docs.aws.amazon.com/ses/latest/dg/smtp-credentials.html)）。
  AWS は、長期のアクセスキーより IAM ロールの一時的な資格情報を勧める（[Managing access keys for IAM users](https://docs.aws.amazon.com/IAM/latest/UserGuide/securing_access-keys.html)）。
- 本番のコードを環境で切り替えない。
  [ADR-072](ADR-072-fake-external-systems-with-wiremock.md) は、外部システムの偽物を本番のコードに置かず、接続先だけを WireMock に向けると決めた。

SES v2 の `SendEmail` は JSON の REST の API（`POST /v2/email/outbound-emails`）なので、決済代行と同じく WireMock で偽れる。
AWS の SDK は、サービスごとの接続先を環境変数 `AWS_ENDPOINT_URL_SESV2` で変えられる（[Service-specific endpoints](https://docs.aws.amazon.com/sdkref/latest/guide/feature-ss-endpoints.html)）。
AWS SDK for Java v2 は、同じ値をシステムプロパティ `aws.endpointUrlSesV2` からも読み、環境変数より優先する（2.59.2 の `AwsClientEndpointProvider` と `DefaultSesV2BaseClientBuilder`）。
そのため、アプリのコードが接続先の設定を持たなくても、ローカルとテストでは WireMock を呼べる。

[#323](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/323) のコメントは、本番を SES の API、ローカルとテストを Mailpit への SMTP にし、設定で実装を選ぶ形を一度決め、その後この ADR の形へ改めた。
前の形では、本番のコードが環境で実装を選び、本番の実装がローカルとテストの経路を通らなかったためである。

## Decision

メールは、本番、ローカル、テストのどれも、AWS SDK for Java v2 の `SesV2Client` で SES v2 の `SendEmail` を呼ぶ同じ Client で送る。
具体的には次のようにする。

- 本番のコードは、`SendEmail` を呼ぶ Client（`tenancy` の `MailSenderClient`）だけを持つ。
  実装の切り替え、偽物の分岐、mode の設定を持たない。
- Client は、宛先、件名、プレーンテキストの本文だけを受ける。
  From のアドレスと表示名、設定セットの名前は既定値のない設定値にし、未設定なら起動に失敗させる（[ADR-008](ADR-008-single-application-yaml-external-config.md)）。
- 接続先、リージョン、資格情報は SDK の既定の取得に任せ、アプリの設定に書かない。
  本番は、ECS のタスクのロールの一時的な資格情報と、ECS がタスクに入れる `AWS_REGION` を使う（[Amazon ECS environment variables](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/ecs-environment-variables.html)）。
  リージョンが取れなければ、SDK の Client を作れず、起動に失敗する。
- ローカルとテストは、`AWS_ENDPOINT_URL_SESV2` で既存の WireMock に向ける。
  決済代行と同じ WireMock のコンテナを使い、コンテナを増やさない。
  `AWS_ENDPOINT_URL_SESV2`、`AWS_REGION`、ダミーの `AWS_ACCESS_KEY_ID` と `AWS_SECRET_ACCESS_KEY` は、env の例と compose ファイルにだけ書く。
  Gradle のテストは、テストの JVM ごとに起動する WireMock（[ADR-085](ADR-085-start-test-dependencies-per-fork-with-testcontainers.md)）を、同じ意味のシステムプロパティで指す。
- 接続先を設定しないと、SDK は本物の SES へ送る。
  ADR-072 は接続先の URL に既定値を置かず、未設定なら起動に失敗させると決めたが、SES はこの点が決済代行と違う。
  ローカルで `AWS_ENDPOINT_URL_SESV2` を書き忘れても、SES はダミーの資格情報の署名を断るので、本物の宛先へは届かない。
  本番の設定には `AWS_ENDPOINT_URL_SESV2` を書かない。
- タイムアウトは [ADR-019](ADR-019-define-resilience-and-capacity-guardrails.md) の値にする。
  HTTP の Client の接続を 1 秒、読み取りを 2 秒、SDK の `apiCallTimeout`（呼び出し全体）を 2 秒にする。
  HTTP の Client は、JDK の `HttpURLConnection` を使う `url-connection-client` にし、SDK が既定で入れる Apache と Netty の Client を依存から外す。
- SES の `SendEmail` には冪等キーがないので、SDK の再試行を止めて試行を 1 回にする。
  Resilience4j の `mail-sender` の retry も `default`（試行 1 回）を継承する。
  再投入で同じメールを 2 回送らない扱いは、Client を呼ぶ Listener が決める。
- 呼び出しの結果を、ADR-072 の分け方に合わせて次の四つに分ける。
  1. 一時障害（接続の失敗、タイムアウト、スロットリングの `TooManyRequestsException`、5xx）：例外を投げ、イベント出版を `FAILED` に残して再投入を待つ。circuit breaker の失敗に数える。
  2. 回復しない拒否（`MessageRejected`、`BadRequestException`）：例外を投げずに拒否の結果を返し、呼び出し元が業務の状態に記録する。同じ要求を再投入しても受け付けられないためである。
  3. 資格情報と権限の不備（`AccessDeniedException` などの 403）と、運用者が直せる SES の不備（送信の停止、From のドメインの未検証、設定セットがない、送信の上限）：例外を投げ、`FAILED` に残す。直したあとの再投入で回復できるためである。スロットリングを除く 4xx は、SES の不調ではないので circuit breaker の失敗に数えない。
  4. 契約に合わない応答（2xx で `MessageId` がない）：拒否の結果を返す。送り直すと 2 通目が出うるためである。
- Client のログには、例外の型と SES のエラーコードだけを出し、宛先のアドレスと SES のエラーの文面を出さない（[ADR-015](ADR-015-structure-and-protect-observability-data.md)）。
  `MessageRejected` の文面は、確かめられなかった宛先のアドレスを含みうるためである。

## Consequences

### Positive

- 本番は長期の資格情報を持たず、タスクのロールの一時的な資格情報で送る。
- 本番と同じ Client と同じ SDK の要求の組み立てを、ローカル、結合テスト、E2E で通せる。
- 送ったメールの宛先、件名、本文を WireMock の要求の記録（`/__admin/requests`）から読めるので、テストと E2E がメールの中のリンクを取り出せる。
- WireMock のコンテナを決済代行と共有するので、コンテナとホストのポートが増えない。
- 失敗の分け方が決済代行と同じになり、Client を呼ぶ側の書き方がそろう。

### Negative

- WireMock は SES の要求の中身（宛先の形式、サンドボックスの宛先の確認、From の ID の検証）を検証しない。
  決済代行の WireMock と同じ割り切りで、本番での手による疎通の確認で補う。
- 手元でメールを読む画面がなく、送ったメールは WireMock の要求の記録を JSON で読む。
- 接続先の書き忘れは、起動の時点で止まらない。
  ダミーの資格情報も書き忘れると、SDK は手元の `~/.aws` の profile の資格情報を使い、本物の SES へ送りうる。
- From の ID が検証されていないときも SES は `MessageRejected` を返すので、設定の誤りが回復しない拒否として業務の状態に記録される。
  Client の WARN のログ（エラーコード）と、本番での疎通の確認で見つける。
- SDK の呼び出しは `RestClient` を通らないため、Boot の計測（`http.client.requests` のメトリクスとトレースの span）が付かない。
- SDK は Spring の `spring.config.import` で読んだ `.env` を見ない。
  ホストで起動する `task be-run` は、`.env` を環境変数にしてから起動する必要がある。

### Neutral

- 今はこの Client を呼ぶ機能がない。
  [#380](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/380)（招待）が使う予定だったが、招待は別のシステムが担当することになり、#380 は実装しない。
  置き場所は #571 の想定どおり `tenancy` の `infrastructure.client` にし、ほかのモジュールが使うことになったら見直す。
- Keycloak のメール（realm の `resetPasswordAllowed` とメールアドレスの確認）の経路は [#593](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/593) で決め、Keycloak のメールだけを受ける Mailpit を別に足す。
  アプリのメールはこの ADR の経路のままにし、アプリは Mailpit に依存しない。
- 規約文書（[メールを送る Client](../mail/sending.md)、[本番で SES から送る設定](../mail/production-setup.md)、[メールのテスト](../mail/testing.md)、[Compose の作り方](../container/compose.md)、[E2E テストの方針と書き方](../e2e/testing-strategy.md)、[バックエンドのテストの実行基盤](../backend/testing-infrastructure.md)）は、この ADR を起こす #571 の変更で更新した。

## Alternatives Considered

### 選択肢1: 本番も SMTP で送り、ローカルとテストは Mailpit にする

- **Description**：Spring の `JavaMailSender` で、本番は SES の SMTP インタフェースへ、ローカルとテストは Mailpit へ送る。
- **Pros**：経路を 1 つにでき、Mailpit の画面と API でメールを読める。Keycloak のメールも同じ Mailpit で受けられる。
- **Cons**：SES の SMTP は IAM ユーザーの長期の資格情報でしか認証できず、その保管と入れ替えが要る。

### 選択肢2: 本番は SES の API、ローカルとテストは Mailpit への SMTP にし、設定で実装を選ぶ

- **Description**：メールの送信のインタフェースに SES の API の実装と SMTP の実装を置き、既定値のない設定で選ぶ。
- **Pros**：本番は一時的な資格情報で送り、ローカルとテストは Mailpit の画面でメールを読める。
- **Cons**：本番のコードが環境で実装を切り替え、ADR-072 の例外になる。本番の実装がローカルとテストの経路を通らない。

### 選択肢3: Spring Cloud AWS の spring-cloud-aws-starter-ses を使う

- **Description**：Spring Cloud AWS 4.2.2 の `SimpleEmailServiceJavaMailSender` で、`JavaMailSender` として SES へ送る。
- **Pros**：`JavaMailSender` の API で書け、SES の Client の自動構成が付く。
- **Cons**：SES v1 の `SendRawEmail` を呼ぶ。README の対応表は Spring Boot 4.0.x までで、このリポジトリの 4.1.2 が載っていない。

### 選択肢4: AWS Emulator で SES を偽る

- **Description**：2026-11-11 に一般提供された AWS Emulator（v1.0.553 で確かめた）を、ローカルとテストの SES の偽物にする。
- **Pros**：AWS が提供する偽物で、SES の API の形に追従することが見込める。
- **Cons**：送ったメールを読む口がなく（管理の口は `/__admin/health` だけ）、`SendEmail` の要求の検証が未実装で、公開の Docker イメージがない。
  送ったメールを読めるようになり、イメージが公開されたら見直す。

### 選択肢5: LocalStack で SES を偽る

- **Description**：LocalStack の SES の偽物を使い、送ったメールを LocalStack の API で読む。
- **Pros**：SES の API を偽り、送ったメールを読む API を持つ。
- **Cons**：Community 版でも 2026 年 3 月から auth token が要る。

## References

- [#323 メールと SMS の設計の docs を取り込むか](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/323)（送り方の決定の改訂のコメント）
- [#571 メールを SES v2 の API で送り、ローカルとテストは接続先を WireMock に向ける](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/571)
- [#675 ESP の選定を ADR に残す](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/675)
- [#593 招待された人が IdP のアカウントを作る経路](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/593)
- [ADR-008: application.yaml を単一にし、設定を外部から注入する](ADR-008-single-application-yaml-external-config.md)
- [ADR-015: 可観測性データを構造化し保護する](ADR-015-structure-and-protect-observability-data.md)
- [ADR-019: 外部連携の耐障害性と容量制御を標準化する](ADR-019-define-resilience-and-capacity-guardrails.md)
- [ADR-041: ECS のタスクを Fargate で動かす](ADR-041-run-ecs-tasks-on-fargate.md)
- [ADR-072: 外部システムを本番のコードで偽らず、WireMock のコンテナで偽る](ADR-072-fake-external-systems-with-wiremock.md)
- [ADR-085: バックエンドのテストの依存を Testcontainers で fork ごとに起動し、テストを並列に実行する](ADR-085-start-test-dependencies-per-fork-with-testcontainers.md)
- [メールを送る Client](../mail/sending.md)
- [本番で SES から送る設定](../mail/production-setup.md)
- [Obtaining Amazon SES SMTP credentials](https://docs.aws.amazon.com/ses/latest/dg/smtp-credentials.html)
- [Managing access keys for IAM users](https://docs.aws.amazon.com/IAM/latest/UserGuide/securing_access-keys.html)
- [Service-specific endpoints](https://docs.aws.amazon.com/sdkref/latest/guide/feature-ss-endpoints.html)
- [Amazon ECS environment variables](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/ecs-environment-variables.html)
- [Amazon SES API v2 Reference: SendEmail](https://docs.aws.amazon.com/ses/latest/APIReference-V2/API_SendEmail.html)
- WireMock Docs, Admin API Reference（<https://wiremock.org/docs/standalone/admin-api-reference/>）
