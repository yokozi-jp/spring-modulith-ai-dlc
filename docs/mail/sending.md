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

| 場合                                            | 例                                                                                                                                       | `send`                | circuit breaker の失敗 | イベント出版 |
| ----------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- | --------------------- | ---------------------- | ------------ |
| 送信済み                                        | SES が `MessageId` を返した                                                                                                              | `Sent(MailMessageId)` | 数えない               | `COMPLETED`  |
| 回復しない拒否                                  | 許可の外の宛先と、1 つのメールアドレスの形でない宛先（SES を呼ばない）、`MessageRejected`、`BadRequestException`、`MessageId` のない応答 | `Rejected`            | 数えない               | `COMPLETED`  |
| 一時障害                                        | 接続の失敗、タイムアウト（`ApiCallTimeoutException`、読み取りの `SdkClientException`）、`TooManyRequestsException`、5xx                  | SDK の例外を投げる    | 数える                 | `FAILED`     |
| 資格情報と権限の不備、運用者が直せる SES の不備 | `AccessDeniedException` などの 403、送信の停止、From のドメインの未検証、設定セットがない、送信の上限                                    | SDK の例外を投げる    | 数えない               | `FAILED`     |

- 呼び出し元は、`Sent` と `Rejected` を業務の状態に記録して正常に返し、Listener を正常終了させる。
  `Rejected` は、同じ要求を再投入しても受け付けられないためである。
- 資格情報を SDK が取れない失敗（タスクのロールがなく `Unable to load credentials` になる場合など）は、SES へ届く前の `SdkClientException` なので、一時障害と同じく circuit breaker の失敗に数え、イベント出版は `FAILED` になる。
  SES が資格情報を断る 403 は数えない。
- 呼び出し元は SDK の例外を捕まえない。
  例外が Listener の外へ出ると、イベント出版が `FAILED` に残り、再投入を待つ（[非同期処理の失敗時の再試行と回復](../integration/async-failure-recovery.md)）。
- `MailOutcome` は `switch` で網羅して分ける（[バックエンドの型の設計規約](../backend/java-type-modeling.md#switch-を網羅させる)）。
- SES のサンドボックスで確かめていない宛先と、検証していない From の ID も `MessageRejected` になる。
  どちらも `Rejected` として記録されるので、本番で送り始める前に[本番で SES から送る設定](production-setup.md)の疎通の確認をする。

## 重複の防ぎ方

- SES の `SendEmail` には冪等キーがない。
  同じ引数で 2 回呼ぶと 2 通届く。
- `MailSenderClient` は、SDK の再試行を止め、Resilience4j の retry も使わない（[外部システムの Client](../backend/class-roles/external-client.md)）。
  呼び出し元は、自分で再試行しない。
- 送ってから commit の前に落ちると、再投入で 2 通目が出る。
  呼び出し元は、送った時刻を業務の状態に記録し、記録があれば送らない。
  2 回届いたイベントで 2 回目が行を変えない形にする（[順序保証と冪等性](../integration/async-ordering-and-idempotency.md)）。
- タイムアウトと接続の切断（`ApiCallTimeoutException`、`SdkClientException`）では、SES が受け付けた後に応答を受け取れなかった場合がある。
  このときは何も記録されず、再投入で同じメールが 2 通届く。
  送った時刻の記録では防げないので、メールは少なくとも 1 回届く（at-least-once）ものとして、1 通多く届いても困らない本文にする。
- SDK の最初の呼び出しはクラスの読み込みで遅く、タスクの起動直後の 1 通が、2 秒の上限を超えやすい。
  本番は SES へ温め用の呼び出しをしない（権限と送信の実績を増やさないため）。
  起動直後のタイムアウトは、上の at-least-once の場合に当たり、再投入で 2 通目が届きうる。

## 設定

設定は `application.yaml` の `mail-sender` にあり、次の環境変数で注入する。
どれも既定値がなく、未設定なら起動に失敗する。

| 環境変数                         | 値                                                                  |
| -------------------------------- | ------------------------------------------------------------------- |
| `MAIL_SENDER_FROM_ADDRESS`       | From のアドレス。SES で検証した ID のアドレスにする                 |
| `MAIL_SENDER_FROM_NAME`          | From の表示名。UTF-8 で 45 バイト以下にする（日本語で 15 文字まで） |
| `MAIL_SENDER_CONFIGURATION_SET`  | 送るメールに付ける SES の設定セットの名前                           |
| `MAIL_SENDER_ALLOWED_RECIPIENTS` | 送ってよい宛先。カンマ区切りで、`*`、`@ドメイン`、アドレスのどれか  |

- 表示名は、Client が RFC 2047 の encoded-word（UTF-8 の Base64）にして送る。
  1 つの encoded-word に収まらない表示名は、起動の時点で失敗する。
- 送ってよい宛先は、許可リスト（`MAIL_SENDER_ALLOWED_RECIPIENTS`）で絞る。
  項目は `*`（すべて）、`@example.com`（そのドメイン。サブドメインを含まない）、`user@example.com`（そのアドレス）のどれかで、大文字と小文字は区別しない。
  許可の外の宛先と、1 つのメールアドレスの形（`MailAddress.PATTERN`）でない宛先には SES を呼ばず `Rejected` を返し、ログには宛先を出さず設定の名前だけを出す。
  本番は `*` にし、ローカル、テスト、ステージングは自分たちのドメインや検証済みのアドレスにする。
  空の値と、形の誤った項目は、起動の時点で失敗する。
  接続先の書き忘れで本物の SES へ向かっても、許可の外の宛先へは送らない。
- 形式の誤った From のアドレスと、空白だけの表示名と設定セットも、起動の時点で失敗する。
  起動を通すと、SES が要求ごとに `BadRequestException` を返し、すべてのメールが `Rejected` として記録されるためである。
- 接続先、リージョン、資格情報は `application.yaml` に書かない。
  `SesV2ClientConfig` は、リージョンを既定値のない `AWS_REGION` から読む（本番は ECS が入れる）。
  Spring の設定に `AWS_ENDPOINT_URL_SESV2` があるときだけ、接続先と、`AWS_ACCESS_KEY_ID`、`AWS_SECRET_ACCESS_KEY` の固定の資格情報を Client に渡す（ローカルとテスト）。
  ないときは、接続先と資格情報を AWS の SDK の既定の取得に任せる（本番）。
  本番の値は[本番で SES から送る設定](production-setup.md)に、ローカルの値は `.env.example` にある。
- Client は環境変数のプロキシ（`HTTP_PROXY` など）を読まない。
- タイムアウトは接続 1 秒、呼び出し全体 2 秒にする（[ADR-019](../adr/ADR-019-define-resilience-and-capacity-guardrails.md)）。
  Listener はトランザクションの中で外部システムを呼ぶので、最悪の時間（2 秒）は `DB_IDLE_IN_TRANSACTION_TIMEOUT_MS` より短くする。
  `DemoApplicationTest` が、`payment-gateway` と同じ式で `mail-sender` の最悪の時間を確かめる。

## ログ

- `MailSenderClient` は、拒否したときだけ WARN のログに例外の型、SES のエラーコード、SES の要求の ID を出す。
  要求の ID は、SES の記録と突き合わせる鍵になる。
- 宛先のアドレス、件名、本文、SES のエラーの文面をログに出さない（[ADR-015](../adr/ADR-015-structure-and-protect-observability-data.md)）。
  `MessageRejected` の文面は、確かめられなかった宛先を含みうる。
- `MailSenderClient` が投げる SES の例外は、SES のエラーの文面を落とし、型、状態のコード、エラーコード、要求の ID だけを持つ。
  例外のメッセージは、Spring Modulith がリスナーの失敗を記録するログに出るためである。
  接続の失敗とタイムアウトの例外は SDK のメッセージのままで、SES の文面を含まない。
- 送ったメールを SES の記録と突き合わせるときは、`Sent` の `MailMessageId` を使う。
