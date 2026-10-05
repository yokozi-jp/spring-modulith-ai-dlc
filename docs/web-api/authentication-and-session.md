---
type: Convention
title: APIの認証、セッション、権限
description: ブラウザとシステム間連携のAPI認証、ログアウト、セッションCookieの属性、ロールの管理場所を定める規約。APIの認証方式やセッション設定を変更するとき、対向システムへAPIを公開するとき、権限の判定を実装するときに読む。
tags: [convention, web-api, security, auth, future-arch-guidelines]
---

# APIの認証、セッション、権限

ブラウザからのAPIは、サーバー側セッションのCookieで認証する。
システム間連携でAPIを公開するときは、OAuth 2.0のClient Credentials Flowで発行したアクセストークンで認証し、可能なら送信元のネットワークも制限する。
ロールとユーザーの紐づけはIdPではなくアプリケーションで管理する。

## ブラウザからの認証

ブラウザからの認証は[ADR-007](../adr/ADR-007-session-based-auth-with-oidc-pkce.md)に従い、OIDCのAuthorization Code Flow with PKCEでログインし、以降はセッションIDのCookieで認証する。
アクセストークンはブラウザに渡さない。

未認証のAPIリクエストには、401のProblem Detailsに`WWW-Authenticate: Session realm="demo"`を付けて返す。
`Session`は登録されていない独自のschemeであり、選んだ理由は[ADR-059](../adr/ADR-059-send-a-session-challenge-in-www-authenticate-on-401.md)にある。

## ログアウト

ログアウトでは、ADR-007に従ってアプリケーションのセッションを破棄し、IdPのSSOセッションを終了する。
IdPがトークンの失効エンドポイント（RFC 7009）を提供する場合は、セッションに保持したトークンを失効させてからセッションを破棄する。
IdPはログアウト後に、利用者をSPAの`/logged-out`へ戻す。
本番のIdPには`https://<origin>/logged-out`を、post logout redirect URI（Cognitoではsign-out URL）として登録する。

## セッションCookie

Cookieは、セッションIDとADR-007のCSRFトークン以外の用途に使わない。
Cookieの名前、`HttpOnly`、`Secure`、`SameSite`の設定はADR-007と[application.yaml](../../backend/src/main/resources/application.yaml)に従い、そのほかの属性と扱いは次のとおりにする。

- Cookieの値にユーザーIDや権限などの情報を含めない。
- セッションIDは、フレームワークが暗号論的に安全な乱数で生成した、十分に長く推測困難な値だけにする。
- `SameSite`を`None`にしない。要件を満たせる場合に限り`Strict`にしてよい。
- `Path`は`/`にする。
- `Domain`は省略し、サブドメイン間でCookieを共有しない。
- 有効期間は`Expires`ではなく`Max-Age`で指定し、合意したセッションの有効期間に合わせる。
- ログインのたびにセッションIDを再生成し、ログイン前の値を引き継がない。
- ログアウトでは、サーバーのセッションを破棄し、Cookieを`Max-Age=0`で削除する。

## システム間連携の認証

対向システムへAPIを公開する場合も、認証のないAPIは作らない。

- 認証は、OAuth 2.0のClient Credentials Flowで認可サーバーから取得したアクセストークンを`Authorization`ヘッダーで受け取る方式にする。
- 対向システムが認可サーバーへ接続できない場合に限り、APIアクセスキーを検討する。
- 可能であれば、送信元IPでネットワークの到達範囲も制限する。

ADR-007は未認証で許可する経路をヘルスチェックに限り、セッション以外の認証方式を定めていない。
システム間連携の認証を初めて実装するときは、ADRを起こす。

同じアプリケーション内のバッチ処理は、自分のWeb APIを呼ばず、アプリケーション層を直接呼ぶ。

## ロールの管理

IdPはユーザーの認証だけを担い、ユーザーとロールの紐づけはアプリケーションで管理する。
部署異動の反映時期のように、ロールの変更にはIdPとは別の業務固有の要件があるためである。

権限がない場合のステータスコードは[HTTPステータスコードの選択](status-codes.md)に従う。

## 出典

- フューチャー株式会社「Web API設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forWebAPI/web_api_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
