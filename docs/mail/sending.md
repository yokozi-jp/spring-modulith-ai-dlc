---
type: Convention
title: メールを送る Client
description: メールを送る Client（mail モジュールの MailSender と MailSenderClient）の呼び方、結果と失敗の扱い、重複の防ぎ方、設定、ログの規則を定める規約。メールを送る機能を Listener と CommandHandler で作るとき、送信の失敗の扱いを決めるとき、メールの設定を足すときに読む。
tags: [convention, mail, backend, ses, external-system]
---

# メールを送る Client

メールは `mail` モジュールの `MailSender` で送り、実装の `MailSenderClient` が SES v2 の `SendEmail` を呼ぶ。
同じ要求で回復しない拒否は結果の `Rejected` で返り、呼び出し元が業務の状態に記録する。
一時障害、資格情報と権限の不備、運用者が直せる SES の不備は例外で返るので、捕まえずにイベント出版を `FAILED` に残す。
送り方の決定の理由は [ADR-125](../adr/ADR-125-send-mail-with-ses-v2-api-and-fake-it-with-wiremock.md) に示す。

## 呼び方

- メールは、イベントを受けた Listener から呼ばれる CommandHandler で送る。
  外部システムのインタフェースを呼んでよい場所は[外部システムのインタフェース](../backend/class-roles/external-system-interface.md)に従う。
- `MailSender.send` には、宛先（`MailRecipient`）、件名（`MailSubject`）、プレーンテキストの本文（`MailBody`）だけを渡す。
  宛先は TO の 1 人にし、本文の書き方は[送るメールの形と宛先](message-format.md)に従う。
- 件名は改行を含められない。
  空白だけの宛先、件名、本文は `IllegalArgumentException` になる。
- 宛先のアドレスはイベントに載せず、Listener が DB から読む（[メールアドレスの扱い](email-address.md#イベントとログ)）。
- `MailSender` は `mail` モジュールの `domain.model` にあるので、ほかのモジュールからは呼べない（[モジュール間の連携](../backend/architecture.md#モジュール間の連携)）。
  今は `MailSender` を呼ぶ機能がない。
  送る機能を足すときは、送る契機のイベントを `mail` の Listener で受ける形を、その機能の変更で決める。

## 結果と失敗の扱い

`MailSender.send` の結果は、次の場合に分かれる。

| 場合                                            | 例                                                                                                    | `send`                | circuit breaker の失敗 | イベント出版 |
| ----------------------------------------------- | ----------------------------------------------------------------------------------------------------- | --------------------- | ---------------------- | ------------ |
| 送信済み                                        | SES が `MessageId` を返した                                                                           | `Sent(MailMessageId)` | 数えない               | `COMPLETED`  |
| 回復しない拒否                                  | `MessageRejected`、`BadRequestException`、`MessageId` のない応答                                      | `Rejected`            | 数えない               | `COMPLETED`  |
| 一時障害                                        | 接続の失敗、タイムアウト（`ApiCallTimeoutException`）、`TooManyRequestsException`、5xx                | SDK の例外を投げる    | 数える                 | `FAILED`     |
| 資格情報と権限の不備、運用者が直せる SES の不備 | `AccessDeniedException` などの 403、送信の停止、From のドメインの未検証、設定セットがない、送信の上限 | SDK の例外を投げる    | 数えない               | `FAILED`     |

- 呼び出し元は、`Sent` と `Rejected` を業務の状態に記録して正常に返し、Listener を正常終了させる。
  `Rejected` は、同じ要求を再投入しても受け付けられないためである。
- 呼び出し元は SDK の例外を捕まえない。
  例外が Listener の外へ出ると、イベント出版が `FAILED` に残り、再投入を待つ（[非同期処理の失敗時の再試行と回復](../integration/async-failure-recovery.md)）。
- `MailOutcome` は `switch` で網羅して分ける（[バックエンドの型の設計規約](../backend/java-type-modeling.md#switch-を網羅させる)）。
- SES のサンドボックスで確かめていない宛先と、検証していない From の ID も `MessageRejected` になる。
  どちらも `Rejected` として記録されるので、本番で送り始める前に[本番で SES から送る設定](production-setup.md)の疎通の確認をする。

## 重複の防ぎ方

- SES の `SendEmail` には冪等キーがない。
  同じ引数で 2 回呼ぶと 2 通届く。
- `MailSenderClient` は、SDK の再試行を止め、Resilience4j の `mail-sender` の retry も試行 1 回にしている。
  呼び出し元は、自分で再試行しない。
- 送ってから commit の前に落ちると、再投入で 2 通目が出る。
  呼び出し元は、送った時刻を業務の状態に記録し、記録があれば送らない。
  2 回届いたイベントで 2 回目が行を変えない形にする（[順序保証と冪等性](../integration/async-ordering-and-idempotency.md)）。

## 設定

設定は `application.yaml` の `mail-sender` にあり、次の環境変数で注入する。
どれも既定値がなく、未設定なら起動に失敗する。

| 環境変数                        | 値                                                                  |
| ------------------------------- | ------------------------------------------------------------------- |
| `MAIL_SENDER_FROM_ADDRESS`      | From のアドレス。SES で検証した ID のアドレスにする                 |
| `MAIL_SENDER_FROM_NAME`         | From の表示名。UTF-8 で 45 バイト以下にする（日本語で 15 文字まで） |
| `MAIL_SENDER_CONFIGURATION_SET` | 送るメールに付ける SES の設定セットの名前                           |

- 表示名は、Client が RFC 2047 の encoded-word（UTF-8 の Base64）にして送る。
  1 つの encoded-word に収まらない表示名は、起動の時点で失敗する。
- 接続先、リージョン、資格情報は AWS の SDK の既定の取得に任せ、`application.yaml` に書かない。
  本番の値は[本番で SES から送る設定](production-setup.md)に、ローカルの値は `.env.example` にある。
- タイムアウトは接続 1 秒、呼び出し全体 2 秒にする（[ADR-019](../adr/ADR-019-define-resilience-and-capacity-guardrails.md)）。
  Listener はトランザクションの中で外部システムを呼ぶので、最悪の時間（2 秒）は `DB_IDLE_IN_TRANSACTION_TIMEOUT_MS` より短くする。

## ログ

- `MailSenderClient` は、拒否したときだけ WARN のログに例外の型と SES のエラーコードを出す。
- 宛先のアドレス、件名、本文、SES のエラーの文面をログに出さない（[ADR-015](../adr/ADR-015-structure-and-protect-observability-data.md)）。
  `MessageRejected` の文面は、確かめられなかった宛先を含みうる。
- 送ったメールを SES の記録と突き合わせるときは、`Sent` の `MailMessageId` を使う。

## テスト

- テストの書き方は[メールのテスト](testing.md)に従う。
- `MailSenderClientTest` は JDK の `HttpServer` で、成功、拒否、スロットリング、5xx、403、タイムアウトの扱いを確かめる。
- `MailSenderClientIntegrationTest` は、テストの JVM の WireMock に送り、要求の記録から宛先、件名、本文を読む。
  スロットリング、5xx、`MessageRejected`、403 を返させて、結果、例外、要求の数、circuit breaker の失敗の数を確かめる。
