---
type: Convention
title: メールアドレスの扱い
description: メールアドレスの正の置き場所、招待の受け入れでのアドレスの確認、形式の検査、アドレスどうしの比べ方、イベントとログでの扱いを定める規約。メールアドレスを受け取る、検査する、保存する、比べるコードを書くときに読む。
tags: [convention, mail, validation, security, future-arch-guidelines]
---

# メールアドレスの扱い

利用者のメールアドレスの正は IdP に置き、アプリの利用者は ID トークンの `iss` と `sub` の組で引く。
形式の検査は、ありえない形だけを断る寛容な検査にし、正規表現を自作せず、フレームワークの検査とクライアントの Zod の式に合わせる。
アドレスどうしは大文字と小文字を区別せずに比べ、エイリアスを正規化しない。

## 正の置き場所

- 利用者のメールアドレスの正は IdP に置き、アプリの利用者をメールアドレスで引かない。
  アプリは ID トークンの `iss` と `sub` の組から利用者を引く（[ADR-101](../adr/ADR-101-keep-tenants-and-roles-in-app-database.md)）。
- 招待を受け入れるまでは、招待した相手のアドレスをアプリの DB の招待の表に持つ（[#380](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/380)）。
  IdP のアドレスが正になるのは、招待された人が登録してログインした後である。
- 招待の受け入れは、ID トークンの `email_verified` が `true` で、ID トークンのメールアドレスが招待のアドレスと一致するときだけ許す（#380）。
  claim があるだけでは許さない。
  Keycloak と Cognito は、確認の済んでいないアドレスに `email_verified` を `false` で入れて発行しうるためである。
  両方の IdP の ID トークンに `email_verified` が入ることは、#380 で確かめる（[ADR-102](../adr/ADR-102-use-amazon-cognito-as-production-idp.md#neutral)）。
- アプリから確認のメールを送るかは、まだ決まっていない。

## 形式の検査

- 入力の前後の空白は、フォームの component が、生成した schema で検査する前に除く（`EmailInput`）。
  Orval が生成する Zod の schema は値を変えずに検査するので、空白を除けないためである。
- サーバーは空白を除かずに検査し、前後に空白のある値を断る。
- 検査は、ありえない形（`@` がない、空白を含む、`@` が全角であるなど）だけを断る。
  アドレスに届くかどうかは、形式の検査では確かめない。
- 正規表現を自作しない（[ADR-120](../adr/ADR-120-align-server-email-validation-with-zod.md)）。
  - バックエンドは、`shared.validation` の `@MailAddress` を使う。
    `@Email` に、Zod の `z.regexes.email` と同じ式を `regexp` として足した制約で、OpenAPI には `format: email` が出る。
    式は Zod から写したもので、`MailAddress.PATTERN` の 1 か所に置く。
    検査の置き場所は [APIの入力検証の配置](../web-api/validation.md) に従う。
  - フロントエンドは、OpenAPI の `format: email` から Orval が生成する Zod の検査を使い、`z.email()` を手書きしない（[フォームの入力検証](../frontend/form-validation.md#検証条件の同期)）。
- サーバーは、クライアントが通す形の部分集合だけを通す。
  `@MailAddress` は Hibernate の `@Email` の検査も行うので、`a@x-.com` のようにクライアントが通してサーバーが断る形が少しある。
- 式が Zod と同じ文字列であることと、`openapi/mail-address-cases.json` の入力の判定を、バックエンドの `MailAddressTest` とフロントエンドの `mail-address-cases.test.ts` が検査する。
  Zod の更新で式が変わると、フロントエンドのテストが失敗する。
- ASCII 以外のローカルパートと IDN のドメインは、クライアントもサーバーも断る。
  ブラウザの `type="email"` は IDN のドメインを punycode に変えて渡すので、画面からは通る。
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

## 出典

- フューチャー株式会社「メール設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forMail/mail_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
- 原文は、確認のメールの送信を必須とする。
  この規約は、本人がアドレスを持つことを IdP の `email_verified` で判断し、アプリから確認のメールを送るかを定めない。
- 原文は、RFC に合わない古い携帯電話のアドレスを誤って断らないよう、ありえない形だけを断る検査を勧める。
  この規約は、`@MailAddress` と `z.email()` がそうしたアドレスを断ることを受け入れる。
- 原文が勧めるドメインの存在の確認（MX と A のレコードの問い合わせ）を、この規約は定めない。
- 原文が推奨するエイリアスの正規化は、「アドレスどうしの比べ方」の理由で採らない。
