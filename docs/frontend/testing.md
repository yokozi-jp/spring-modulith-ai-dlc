---
type: Convention
title: フロントエンドのテストと検証
description: フロントエンドテストの配置、実行環境、利用者から見える期待、API境界、状態遷移、coverage、生成物の再生成と検査を定める規約であり、テストを書くとき、生成物を更新するときに読む。
tags: [convention, frontend, testing]
---

# フロントエンドのテストと検証

テストは対象ファイルへ併置し、利用者から見える結果をTesting Libraryとuser-eventで検証する。
APIを使うcomponent testはMSWでHTTP境界を置き換える。
画面が提供する状態遷移を検証し、手書きproduction source全体のbranch coverage 85%を維持する。

## 配置と実行環境

テストは検証対象のファイルへ併置する。

複数のテストで使う準備のコードは、[フロントエンドアーキテクチャ](architecture.md#テストの置き場所)に従い `src/testing/` に置く。

純粋関数はNode環境で検証し、DOMを必要とするcomponent testだけにjsdomを使う。

## 利用者視点の期待

利用者操作ではTesting Libraryのroleとlabelから対象を見つけ、user-eventでclick、keyboard入力、focusを実行する。

componentの内部state、Hookの呼出回数、CSS classだけを主要な期待値にせず、利用者から見える結果を検証する。

## API境界

APIを使うcomponent testではOrval生成関数をmodule mockで置き換えず、MSWでHTTP境界を置き換える。

Orvalが生成するMSW handlerで契約を表現できる場合は、それを使って手書きhandlerとの重複を避ける。

## 状態遷移

route loaderとTanStack Queryを組み合わせた画面では、loading、success、empty、error、再試行のうち、その画面が利用者へ提供する状態を検証する。

## coverage

起動処理、test、型宣言、自動生成されたroute tree、Orval生成物を除く手書きproduction source全体をcoverage対象にする。

branch coverage 85%をファイル単位ではなく全体で維持する。

生成物そのものではなく、生成物を利用する手書きコードの挙動を検証する。

coverageの対象と基準を含むテスト基盤の判断理由は[ADR-027](../adr/ADR-027-adopt-frontend-testing-stack.md)を参照する。

## 生成物

生成物は生成元を変更して再生成し、手で書き換えない。

| 生成物                          | 生成元                              | 再生成                                                     |
| ------------------------------- | ----------------------------------- | ---------------------------------------------------------- |
| `frontend/src/routeTree.gen.ts` | `frontend/src/routes/` のroute file | `cd frontend && vp build`、または `vp dev` を起動しておく  |
| `frontend/src/api/generated/**` | OpenAPI snapshotとOrval設定         | Orvalで再生成する（[OrvalとAPI境界](api-client-orval.md)） |

Kiroのagentが生成物へ書き込もうとすると、PreToolUse hookの `.kiro/hooks/block-generated-writes.json` が `exit 2` で拒否し、STDERRに再生成の手順を示す。
判定は `.kiro/hooks/block-generated-writes.sh` が行い、生成物のpathはこのscriptだけが持つ。
STDINを読めないときと `jq` が無いときは書き込みを許可する。
scriptのテストは `bash .kiro/hooks/block-generated-writes.test.sh` で実行する。

shellのredirectなどhookを通らない書き換えは、`task fe-route-tree-check` が最終的に検出する。
このTaskは `vp build` で `routeTree.gen.ts` を再生成し、コミット済みの内容と差分があれば失敗する。
Frontend CIもこのTaskを実行する。

## 検査

実行するTaskと検査範囲は[Lintとテストのリファレンス](../tooling/lint-and-test.md#フロントエンド)を参照する。

## 関連資料

- [フロントエンドのルーティングと状態管理](routing-and-state.md)
- [OrvalとAPI境界](api-client-orval.md)
- [ADR-027: Frontendのテスト基盤を標準化する](../adr/ADR-027-adopt-frontend-testing-stack.md)
