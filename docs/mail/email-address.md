---
type: Convention
title: メールアドレスの扱い
description: メールアドレスの正の置き場所、形式の検査、アドレスどうしの比べ方、イベントとログでの扱い、メールを送る機能の回数の上限の置き場所を定める規約。メールアドレスを受け取る、保存する、比べるコードを書くとき、メールを送る機能を設計するときに読む。
tags: [convention, mail, validation, security, future-arch-guidelines]
---

# メールアドレスの扱い

メールアドレスの正は IdP に置き、アプリの利用者は ID トークンの `iss` と `sub` の組で引く。
形式の検査は、ありえない形だけを断る寛容な検査にし、正規表現を自作せずフレームワークの検査を使う。
アドレスどうしは大文字と小文字を区別せずに比べ、エイリアスを正規化しない。

## 正の置き場所

- メールアドレスの正は IdP に置き、アプリの利用者をメールアドレスで引かない。
  アプリは ID トークンの `iss` と `sub` の組から利用者を引く（[ADR-101](../adr/ADR-101-keep-tenants-and-roles-in-app-database.md)）。
- アドレスに届くことと本人がそのアドレスを持つことは、IdP が確かめた ID トークンの `email_verified` で判断し、アプリから確認のメールを送らない（[ADR-102](../adr/ADR-102-use-amazon-cognito-as-production-idp.md)）。
- 原文も、認証を IDaaS に任せてアドレスの正をそこに置き、ソーシャルログインの紐付けを ID トークンの `sub` で区別しており、同じ決定である。

## 形式の検査

- 入力の前後の空白を除いてから検査する。
- 検査は、ありえない形（`@` がない、空白を含む、`@` が全角であるなど）だけを断る。
  届くかどうかの最終の確認は、検査ではなく `email_verified` に任せる。
- 正規表現を自作しない。
  - バックエンドは、Bean Validation の `@Email`（`jakarta.validation.constraints.Email`）を使う。
    検査の置き場所は [APIの入力検証の配置](../web-api/validation.md) に従う。
  - フロントエンドは、Zod の `z.email()` を使う。
    バックエンドとの条件のそろえ方は [フォームの入力検証](../frontend/form-validation.md#検証条件の同期) に従う。
- 2 つの検査は同じではない。
  `@Email` は、ドメインにドットのないアドレス（`user@localhost`）と、ASCII 以外の文字を含むローカルパートを通し、`z.email()` はどちらも断る（Hibernate Validator 9.1.5 と Zod 4.6.6 で確かめた）。
  この 2 つの形では、サーバーの検査がクライアントより緩く、[フォームの入力検証](../frontend/form-validation.md#クライアントとサーバーの役割)の「サーバーはクライアントと同じかそれ以上の検証を行う」に合わない。
  このずれの埋め方は、まだ決めていない。
- どちらの検査も、ドットが続くなど RFC に合わない古い携帯電話のアドレスを断る。
  取引先の業務のアドレスが相手なので、この差を受け入れる。

## アドレスどうしの比べ方

- 2 つのアドレスが同じかは、大文字と小文字を区別せずに比べる。
- `+` 以降を除く、Gmail のローカルパートの `.` を除くといったエイリアスの正規化をしない。
  取引先の独自ドメインでは、`+` を含むアドレスが別の人の宛先でありうる。
  正規化すると、別の人のアドレス宛ての招待を受け入れられてしまう。

## イベントとログ

- メールアドレスをイベントのペイロードに載せず、内部 ID だけを渡す（[非同期メッセージのセキュリティ](../integration/async-message-security.md#メッセージに載せる値)）。
- メールアドレスをログ、トレース、メトリクスに渡さない（[可観測性データの規約](../observability/conventions.md#発生源で渡さない値)、[ADR-015](../adr/ADR-015-structure-and-protect-observability-data.md)）。

## メールを送る機能の回数の上限

- メールを送る機能に回数の上限が要るときは、[入口とAPIの機能配置](../web-api/edge-responsibilities.md) のレート制限に従って置き場所を決める。

## 出典

- フューチャー株式会社「メール設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forMail/mail_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
- 原文が推奨するエイリアスの正規化は、上の理由で採らない。
