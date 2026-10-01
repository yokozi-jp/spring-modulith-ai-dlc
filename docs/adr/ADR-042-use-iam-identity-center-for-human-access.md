---
type: ADR
title: 'ADR-042: 人の利用者の AWS アクセスを IAM Identity Center で管理する'
description: 人の利用者の AWS へのアクセスを IAM Identity Center の許可セットで管理し、人のための IAM ユーザーと長期のアクセスキーを作らない決定。
tags: [adr, aws, security, iam, identity]
---

# ADR-042: 人の利用者の AWS アクセスを IAM Identity Center で管理する

## Status

Proposed

## Date

2026-10-01

## Context

取り込んだ AWS 設計ガイドラインは、将来の環境の移管に備えて IAM Identity Center を使わず、ID 管理のアカウントに IAM ユーザーを作ってスイッチロールで各環境へ入る構成を勧めている。
Identity Center の構成を保ったまま移管するには、管理アカウントごと移す必要があるためである。

一方、AWS は人の利用者に IAM ユーザーではなく、Identity Center などのフェデレーションで短期の資格情報を使うことを推奨している。
IAM ユーザーを使うと、パスワード、MFA デバイス、アクセスキーという長期の資格情報を利用者ごとに管理し、ローテーションと棚卸しを続ける必要がある。
このリポジトリでは、CI/CD からの接続もすでに GitHub OIDC の短期の資格情報にしている。

## Decision

人の利用者の AWS へのアクセスは IAM Identity Center で管理する。

- Identity Center は Organizations の管理アカウントで有効にし、管理を ID 管理のアカウントへ委任する。
- 権限は職務ごとの許可セットにし、グループへ割り当てる。
- 人の利用者のために IAM ユーザーと長期のアクセスキーを作らず、SCP で作成を禁止する。
- メンバーアカウントのルートユーザーには、ルートアクセスの一元管理で認証情報を持たせない。

## Consequences

### Positive

- 人の利用者が長期の資格情報を持たなくなり、漏えいしたときの影響が短期の資格情報の有効期間に限られる。
- 利用者の追加と削除、権限の変更をグループと許可セットの一か所で行える。
- 外部 IdP があれば、Identity Center の ID ソースにして入退社と連動させられる。

### Negative

- Identity Center の構成は管理アカウントに結び付くため、環境を別の組織へ移管するときは、移管先で許可セットと割り当てを作り直す必要がある。
- Identity Center 自体の障害や設定誤りで、全員がコンソールと CLI に入れなくなる可能性がある。緊急時は管理アカウントのルートユーザーで回復する。

### Neutral

- 許可セットの定義は IaC で管理し、移管時の作り直しをコードの再適用で済ませる。
- アプリケーションの利用者を認証する IdP（ADR-007）と、AWS の運用者を認証する Identity Center は別の仕組みとして扱う。

## Alternatives Considered

### 選択肢1: ID 管理のアカウントの IAM ユーザーとスイッチロール

- **Description**：取り込んだガイドラインのとおり、IAM ユーザーを一つのアカウントに集約し、各環境のロールへスイッチする。
- **Pros**：Identity Center に依存せず、アカウント単位で移管しやすい。
- **Cons**：利用者ごとに長期の資格情報が残り、AWS の現在の推奨に反する。

### 選択肢2: 各アカウントに IAM ユーザーを作る

- **Description**：環境ごとのアカウントに利用者ごとの IAM ユーザーを作る。
- **Pros**：構成が単純である。
- **Cons**：資格情報がアカウントの数だけ増え、棚卸しと削除が漏れやすい。

## References

- [AWSアカウントの分離と構成](../aws/account-structure.md)
- [AWS IAM のセキュリティのベストプラクティス](https://docs.aws.amazon.com/IAM/latest/UserGuide/best-practices.html)
- [IAM Identity Center の委任管理](https://docs.aws.amazon.com/singlesignon/latest/userguide/delegated-admin.html)
