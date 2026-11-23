---
type: Convention
title: メールアドレスの扱い
description: メールアドレスの正の置き場所、形式の検査、アドレスどうしの比べ方、イベントとログでの扱い、メールを送る機能の回数の上限の置き場所を定める規約。メールアドレスを受け取る、保存する、比べるコードを書くとき、メールを送る機能を設計するときに読む。
tags: [convention, mail, validation, security, future-arch-guidelines]
---

# メールアドレスの扱い

利用者のメールアドレスの正は IdP に置き、アプリの利用者は ID トークンの `iss` と `sub` の組で引く。
形式の検査は、ありえない形だけを断る寛容な検査にし、正規表現を自作せずフレームワークの検査を使う。
アドレスどうしは大文字と小文字を区別せずに比べ、エイリアスを正規化しない。

## 正の置き場所

- 利用者のメールアドレスの正は IdP に置き、アプリの利用者をメールアドレスで引かない。
  アプリは ID トークンの `iss` と `sub` の組から利用者を引く（[ADR-101](../adr/ADR-101-keep-tenants-and-roles-in-app-database.md)）。
- 招待を受け入れるまでは、招待した相手のアドレスをアプリの DB の招待の表に持つ（[#380](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/380)）。
  IdP のアドレスが正になるのは、招待された人が登録してログインした後である。
- 本人がアドレスを持つことを ID トークンの `email_verified` で判断するかは、まだ決まっていない。
  [ADR-102](../adr/ADR-102-use-amazon-cognito-as-production-idp.md#neutral) は、`email_verified` を招待の受け入れに使うときは Keycloak と Cognito の両方で claim が入ることを #380 で確かめる、とだけ定めている。
  #380 で両方の IdP に claim が入ると確かめたら、`email_verified` で判断し、アプリから確認のメールを送らない。

## 形式の検査

- 入力の前後の空白は、フロントエンドのフォームが送る前に除く。
  サーバーは空白を除かずに検査し、前後に空白のある値を断る（`@Email` も `z.email()` も断る）。
- 検査は、ありえない形（`@` がない、空白を含む、`@` が全角であるなど）だけを断る。
  アドレスに届くかどうかは、形式の検査では確かめない。
- 正規表現を自作しない。
  - バックエンドは、Bean Validation の `@Email`（`jakarta.validation.constraints.Email`）を使う。
    検査の置き場所は [APIの入力検証の配置](../web-api/validation.md) に従う。
  - フロントエンドは、OpenAPI の `format: email` から Orval が生成する Zod の検査を使い、`z.email()` を手書きしない（[フォームの入力検証](../frontend/form-validation.md#検証条件の同期)）。
- 2 つの検査が通す形は同じではなく、`user@localhost` のようにサーバーが通してクライアントが断る形がある。
  この形では、[フォームの入力検証](../frontend/form-validation.md#クライアントとサーバーの役割)の「サーバーは、クライアントと同じかそれ以上の検証を必ず行う」に合わない。
  ずれの埋め方は [#664](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/664) で決める。
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

- メールを送る機能に回数の上限が要るときは、[入口とAPIの機能配置](../web-api/edge-responsibilities.md#利用者単位のレート制限)に従って置き場所を決める。

## 出典

- フューチャー株式会社「メール設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forMail/mail_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
- 原文も、認証を IDaaS に任せてアドレスの正をそこに置き、ソーシャルログインの紐付けを ID トークンの `sub` で区別している。
- 原文が推奨するエイリアスの正規化は、「アドレスどうしの比べ方」の理由で採らない。
