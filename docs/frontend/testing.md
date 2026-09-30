---
type: Convention
title: フロントエンドのテストと検証
description: フロントエンドのテストの置き場所、実行環境、利用者から見える結果の検証、MSW による API の置き換え、生成物の扱いと検証コマンドを定める。テストを書くとき、生成物を更新するとき、変更を検証するときに読む。
tags: [convention, frontend, testing, codegen]
---

# フロントエンドのテストと検証

テストは対象ファイルへ併置し、利用者から見える結果を Testing Library と user-event で検証する。
API を使う component test は MSW で HTTP 境界を置き換える。
生成ファイルは直接修正せず、生成元を変えて再生成する。
変更の検証は `task fe-verify`（短時間なら `task fe-check`）で行う。

## テスト

テストは検証対象のファイルへ併置する。

純粋関数はNode環境で検証し、DOMを必要とするcomponent testだけにjsdomを使う。

利用者操作はTesting Libraryのroleとlabelから対象を見つけ、user-eventでclick、keyboard入力、focusを実行する。

componentの内部state、Hookの呼出回数、CSS classだけを主要な期待値にせず、利用者から見える結果を検証する。

APIを使うcomponent testではOrval生成関数をmodule mockで置き換えず、MSWでHTTP境界を置き換える。

Orvalが生成するMSW handlerで契約を表現できる場合は、それを使って手書きhandlerとの重複を避ける。

生成された `routeTree.gen.ts` とOrval生成物はcoverage対象から除外し、それを利用する手書きコードの挙動を検証する。

route loaderとTanStack Queryを組み合わせた画面では、loading、success、empty、error、再試行のうち、その画面が利用者へ提供する状態を検証する。

## 自動生成と検証

TanStack Router pluginが生成する `routeTree.gen.ts` とOrval生成物は、生成元を変更して再生成する。

生成ファイルへ直接修正を加えない。

最初の業務APIを追加するときは、バックエンドが出力してSpectral検査を通したOpenAPI snapshotからOrvalを実行するTaskを追加する。

同じ変更で、OpenAPI snapshotと生成物のGit管理方針、生成差分の検出方法、coverage除外を確定する。

フロントエンド全体の型検査、lint、test、buildは、ワークスペースルートで次のコマンドから実行する。

``` bash
task fe-verify
```

変更範囲を短時間で確認するときは、次のcommandを使う。

``` bash
task fe-check
```

## 関連資料

- [OrvalとAPI境界](api-client-orval.md)
- [ADR-027: Frontendのテスト基盤を標準化する](../adr/ADR-027-adopt-frontend-testing-stack.md)
- [ADR-031: Frontendの型検査を厳格化し、tsconfigを正本にする](../adr/ADR-031-tighten-frontend-typescript-checks.md)
