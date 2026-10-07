---
type: Convention
title: ブラウザに保持するデータとキャッシュ
description: 画面間で受け渡す値と、memory、Web Storage、IndexedDBの使い分け、Web Storageの扱い、API responseのcacheの方針を定める規約。ブラウザに値を保存するとき、TanStack Queryのcache設定を変えるときに読む。
tags: [convention, frontend, state, storage, cache, future-arch-guidelines]
---

# ブラウザに保持するデータとキャッシュ

共有したい識別子と条件はURL、URLに出せない一時的な値はmemory、ブラウザを閉じても残す軽い設定値はWeb Storageに置く。
API responseはTanStack Queryのmemory上のcacheだけで保持し、ブラウザのstorageへ永続化しない。
認証情報と機密情報をWeb Storageに保存しない。

## 置き場所の選び方

- **path parameter**：リソースの識別子。reloadと履歴で維持され、URLで共有できる。
- **search parameter**：検索条件など、画面の前提となる条件。reloadと履歴で維持され、URLで共有できる。
- **memory**：URLに出せない値、構造が複雑な一時的な値、API responseのcache。React stateとTanStack Queryで保持し、reloadで消える。
- **sessionStorage**：tabの中だけで有効な一時的な値。tabを閉じると消える。
- **localStorage**：利用者の表示設定のように、ブラウザを閉じても残す軽い値。同じoriginの全tabで共有される。
- **IndexedDB**：オフライン対応で使う構造化データ。使う条件は[オフライン対応](offline-pwa.md)に従う。

URLの構成は[フロントエンドのURL設計](url-design.md)、状態の置き場所は[フロントエンドのルーティングと状態管理](routing-and-state.md)に従う。

## Web Storageの扱い

- Web Storageの読み書きは用途名を持つ関数またはcustom Hookに閉じ込め、componentから `localStorage` と `sessionStorage` を直接呼ばない。
- storageが使えない環境（Cookieを無効にしたbrowserなど）では例外を捕捉し、memory上の値へfallbackする。
- 認証情報、個人情報、業務上秘匿するデータをWeb Storageに保存しない。
- 認証はsession cookieだけで行い、tokenをブラウザのstorageへ保存しない（[ADR-007](../adr/ADR-007-session-based-auth-with-oidc-pkce.md)）。
- logoutの際は、アプリケーションがWeb Storageへ保存した利用者ごとの値を削除する。

## API responseのcache

- API responseはTanStack Queryのmemory上のcacheだけで保持し、Web StorageとIndexedDBへ永続化しない。
- queryの `staleTime` は既定値のまま使い、画面の表示ごとに再取得させる。
- 古い値を許容でき、呼び出し回数が多いデータ（マスタデータなど）に限り、そのquery optionsで `staleTime` を延ばす。
- loaderの `preloadQuery` が指定する `staleTime` はloaderで取得するかどうかだけを決め、componentの `useSuspenseQuery` の再取得は上の `staleTime` に従う（[状態表示の分担](routing-and-state.md#状態表示の分担)）。
- バックエンドのAPI応答では、Spring Securityの既定の `Cache-Control`（`no-store` を含む）を無効化せず、機密データをブラウザのHTTP cacheに残さない（[ADR-014](../adr/ADR-014-use-same-origin-spa-security-boundary.md)）。

静的ファイルのcacheは[フロントエンドのビルドと配信](build-and-delivery.md)に従う。

## 出典

- フューチャー株式会社「Webフロントエンド設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forWebFrontend/web_frontend_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
