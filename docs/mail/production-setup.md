---
type: Runbook
title: 本番で SES から送る設定
description: 本番の backend が SES v2 の API でメールを送るために、ECS のタスク定義に入れる環境変数、タスクのロールに付ける ses:SendEmail のポリシー、送り始める前の疎通の確認を示す手順。本番の環境を作るとき、From や設定セットを変えるとき、本番でメールが送れないときに読む。
tags: [runbook, mail, ses, aws, ecs, iam]
---

# 本番で SES から送る設定

本番の backend は、ECS のタスクのロールの一時的な資格情報で SES v2 の `SendEmail` を呼ぶ。
タスク定義には From、表示名、設定セットの 3 つの環境変数だけを入れ、アクセスキーと接続先を入れない。
タスクのロールには、From の ID と設定セットに絞った `ses:SendEmail` だけを許す。
決定の理由は [ADR-124](../adr/ADR-124-send-mail-with-ses-v2-api-and-fake-it-with-wiremock.md) に示す。

## 前提

- SES の From のドメインの ID を作り、DKIM、カスタムの MAIL FROM、DMARC を[送信ドメインの認証と評判](sending-domain.md)のとおりに設定してある。
  手順は、本番の構築のチェックリスト（[#665](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/665)）に置く。
- SES の設定セットを作ってある。
  抑制リストと到達性の監視は[到達性の監視](deliverability-monitoring.md)に従う。
- SES のアカウントがサンドボックスを出ている。
  サンドボックスでは、確かめていない宛先へのメールが `MessageRejected` になり、`Rejected` として記録される。

## 1. タスク定義に環境変数を入れる

backend のコンテナの `environment` に次の 3 つを入れる。
秘密ではないので、`secrets` にしなくてよい。

| 環境変数                        | 値の例                      |
| ------------------------------- | --------------------------- |
| `MAIL_SENDER_FROM_ADDRESS`      | `no-reply@mail.example.com` |
| `MAIL_SENDER_FROM_NAME`         | `サービス名 招待`           |
| `MAIL_SENDER_CONFIGURATION_SET` | `app-transactional`         |

- 表示名は「サービス名と送る目的」にし（[送るメールの形と宛先](message-format.md#from)）、UTF-8 で 45 バイト以下にする。
  超えると backend が起動しない。
- `AWS_REGION` は ECS がタスクに入れるので、タスク定義に書かない（[Amazon ECS environment variables](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/ecs-environment-variables.html)）。
  SES のリージョンはタスクのリージョンになるので、From の ID と設定セットは同じリージョンに作る。
- `AWS_ACCESS_KEY_ID` と `AWS_SECRET_ACCESS_KEY` を入れない。
  SDK は、ECS がタスクに入れるコンテナの資格情報の URL から、タスクのロールの一時的な資格情報を取る。
- 接続先を上書きする環境変数（`AWS_ENDPOINT_URL` と、`AWS_ENDPOINT_URL_` で始まるもの）を入れない。
  入れると、SES 以外へ送る。

## 2. タスクのロールにポリシーを付ける

タスクのロール（`taskRoleArn`）に、次のポリシーを付ける。
タスクの実行のロール（`executionRoleArn`）ではない。
`<region>`、`<account-id>`、ドメイン、From のアドレス、設定セットの名前は本番の値に置き換える。

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "SendMailFromAppIdentity",
      "Effect": "Allow",
      "Action": "ses:SendEmail",
      "Resource": [
        "arn:aws:ses:<region>:<account-id>:identity/mail.example.com",
        "arn:aws:ses:<region>:<account-id>:configuration-set/app-transactional"
      ],
      "Condition": {
        "StringEquals": {
          "ses:FromAddress": "no-reply@mail.example.com",
          "ses:ApiVersion": "2"
        }
      }
    }
  ]
}
```

- SES v2 の `SendEmail` は、From の ID と、指定した設定セットの両方を認可の対象にする（[Actions, resources, and condition keys for Amazon Simple Email Service v2](https://docs.aws.amazon.com/service-authorization/latest/reference/list_amazonsimpleemailservicev2.html)）。
  どちらかが `Resource` にないと、`AccessDeniedException` になる。
- `ses:FromAddress` で From のアドレスを 1 つに絞る（[Identity and access management in Amazon SES](https://docs.aws.amazon.com/ses/latest/dg/control-user-access.html)）。
- `ses:SendRawEmail` と管理の操作を許さない。
  backend は `SendEmail` の Simple の形だけで送る。

## 3. 送り始める前に疎通を確かめる

ローカルとテストの偽物は、SES が要求を断る条件（宛先の形式、サンドボックスの宛先の確認、From の ID の検証、IAM の認可）を確かめない（[メールのテスト](testing.md#本番)）。
本番の環境を作ったとき、From、設定セット、ポリシーを変えたときは、次を確かめる。

1. 本番の backend から、運用者が受け取れる宛先へメールを 1 通送る。
   今はメールを送る機能がないので、送る機能を足すときにこの手順の送り方を決める。
2. backend のログに `SES rejected the mail` の WARN と、送信の失敗の例外がないことを確かめる。
   `AccessDeniedException` ならポリシーの `Resource` と `Condition` を、`MessageRejected` なら From の ID の検証とサンドボックスを見直す。
3. 届いたメールの From の表示名とアドレス、件名、本文を確かめる。
4. 届いたメールのヘッダーで、SPF、DKIM、DMARC が `pass` であることを確かめる。

## 出典

- [Amazon ECS environment variables](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/ecs-environment-variables.html)
- [Identity and access management in Amazon SES](https://docs.aws.amazon.com/ses/latest/dg/control-user-access.html)
- [Actions, resources, and condition keys for Amazon Simple Email Service v2](https://docs.aws.amazon.com/service-authorization/latest/reference/list_amazonsimpleemailservicev2.html)
- [Amazon SES API v2 Reference: SendEmail](https://docs.aws.amazon.com/ses/latest/APIReference-V2/API_SendEmail.html)
