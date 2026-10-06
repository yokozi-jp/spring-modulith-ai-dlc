---
type: Convention
title: フロントエンドのテストと検証
description: フロントエンドテストの配置、実行環境、利用者から見える期待、クエリと待ち方と差し替えてよい境界などの書き方、API境界、状態遷移、coverage、生成物の再生成と検査を定める規約であり、テストを書くとき、生成物を更新するときに読む。
tags: [convention, frontend, testing]
---

# フロントエンドのテストと検証

テストは対象ファイルへ併置し、利用者から見える結果をTesting Libraryとuser-eventで検証する。
APIを使うcomponent testはMSWでHTTP境界を置き換え、`vi.mock`でmoduleを差し替えない。
画面が提供する状態遷移を検証し、手書きproduction source全体のbranch coverage 85%を維持する。

## 配置と実行環境

テストは検証対象のファイルへ併置する。

複数のテストで使う準備のコードは、[フロントエンドアーキテクチャ](architecture.md#テストの置き場所)に従い `src/testing/` に置く。

Vitestの`setupFiles`（`src/testing/setup.ts`）は、全テストでMSWのserver（`src/testing/msw.ts`）を起動し、各テストの後に`cleanup`、handlerの初期化、mockとglobalのstubの復元、localeの`ja`への初期化を行うので、テストに同じ`afterEach`を書かない。
HTTPはOrvalが生成したMSW handlerを使い、生成handlerがない境界（`apiFetch`のtransportの検査など）だけ、テストの中で`server.use()`に手書きのhandlerを渡す。
routeの描画は`src/testing/render-route.tsx`の`renderRoute`を使い、`main.tsx`と同じproviderとrouterの既定値で、最初のloaderを終えてから描画する。

純粋関数はNode環境で検証し、DOMを必要とするcomponent testだけにjsdomを使う。

## 利用者視点の期待

利用者操作ではTesting Libraryのroleとlabelから対象を見つけ、user-eventでclick、keyboard入力、focusを実行する。

componentの内部state、Hookの呼出回数、CSS classだけを主要な期待値にせず、利用者から見える結果を検証する。

## 書き方

- **実行環境の指定**：jsdomを使うテストは、ファイルの先頭に`/* @vitest-environment jsdom */`を書く。
- **クエリ**：role、label、placeholder、text、display valueの順に選び、`data-testid`は他のクエリで取れない場合だけ使い、`container.querySelector`は使わない。
- **user-event**：renderの前に`userEvent.setup()`を呼び、返ったinstanceで操作する。
- **非同期の待ち**：`findBy`か`waitFor`で待ち、`setTimeout`などの固定時間で待たない。
  `waitFor`のcallbackにはassertionを一つだけ置き、clickなどの副作用を置かない。
- **差し替えてよい境界**：HTTPはMSW、globalは`vi.spyOn`か`vi.stubGlobal`、時刻はfake timerで置き換え、`vi.mock`と`vi.doMock`でmoduleを差し替えない。
  `vi.mock`と`vi.doMock`はOxlintの`vitest/no-restricted-vi-methods`が禁止する。
- **テスト名**：利用者から見える振る舞いを書き、関数名やstate名を書かない。
- **不安定なテスト**：Vitestの`retry`で隠さず、同じ変更で直すか削除し、`skip`で残さない。
  `skip`と`only`の残置はOxlintの`vitest/no-disabled-tests`と`vitest/no-focused-tests`が禁止する。

## API境界

APIを使うcomponent testではOrval生成関数をmodule mockで置き換えず、MSWでHTTP境界を置き換える。

Orvalが生成するMSW handlerで契約を表現できる場合は、それを使って手書きhandlerとの重複を避ける。

## 状態遷移

route loaderとTanStack Queryを組み合わせた画面では、loading、success、empty、error、再試行のうち、その画面が利用者へ提供する状態を検証する。

## coverage

起動処理、test、型宣言、自動生成されたroute tree、Orval生成物を除く手書きproduction source全体をcoverage対象にする。

branch coverage 85%をファイル単位ではなく全体で維持する。

coverageは検証していないコードを見つけるために使い、数値そのものを目標にしない。
coverageを上げるためだけのテストは書かない。
テストで到達しない分岐は、削除するか、利用者から見える振る舞いとして検証する。
`/* v8 ignore */`は生成物に相当するコードだけに使い、理由をコメントで添える。

生成物そのものではなく、生成物を利用する手書きコードの挙動を検証する。

coverageの対象と基準を含むテスト基盤の判断理由は[ADR-027](../adr/ADR-027-adopt-frontend-testing-stack.md)を参照する。

## 生成物

生成物は生成元を変更して再生成し、手で書き換えない。

| 生成物                          | 生成元                                           | 再生成                                                              |
| ------------------------------- | ------------------------------------------------ | ------------------------------------------------------------------- |
| `frontend/src/routeTree.gen.ts` | `frontend/src/routes/` のroute file              | `cd frontend && vp build`、または `vp dev` を起動しておく           |
| `openapi/openapi.yaml`          | ControllerとDTOのJavadoc、`OpenApiConfig`        | `task api-gen`（[APIを変更する](../web-api/runbook-api-change.md)） |
| `frontend/src/api/generated/**` | `openapi/openapi.yaml` とOrval設定               | `task api-gen`（契約から）、`task api-client-gen`（Orval設定だけ）  |

Kiroのagentが生成物へ書き込もうとすると、PreToolUse hookの `.kiro/hooks/block-generated-writes.json` が `exit 2` で拒否し、STDERRに再生成の手順を示す。
判定は `.kiro/hooks/block-generated-writes.sh` が行い、生成物のpathはこのscriptだけが持つ。
hookは`routeTree.gen.ts`、`src/api/generated/**`に加えて`openapi/openapi.yaml`への書き込みも拒否する。
STDINを読めないときと `jq` が無いときは書き込みを許可する。
scriptのテストは `bash .kiro/hooks/block-generated-writes.test.sh` で実行する。

shellのredirectなどhookを通らない書き換えは、`task fe-route-tree-check` が最終的に検出する。
このTaskは `vp build` で `routeTree.gen.ts` を再生成し、コミット済みの内容と差分があれば失敗する。
Frontend CIもこのTaskを実行する。
契約と生成clientでは、`task be-openapi-check`と`task api-client-check`が同じ役割を持ち、Backend CIとFrontend CIが実行する。

## 検査

実行するTaskと検査範囲は[Lintとテストのリファレンス](../tooling/lint-and-test.md#フロントエンド)を参照する。

## 関連資料

- [フロントエンドのルーティングと状態管理](routing-and-state.md)
- [OrvalとAPI境界](api-client-orval.md)
- [ADR-027: Frontendのテスト基盤を標準化する](../adr/ADR-027-adopt-frontend-testing-stack.md)
- [Testing Library: Guiding Principles](https://testing-library.com/docs/guiding-principles)
- [Testing Library: Queries priority](https://testing-library.com/docs/queries/about#priority)
- [user-event: Intro](https://testing-library.com/docs/user-event/intro)
- [Kent C. Dodds: Common mistakes with React Testing Library](https://kentcdodds.com/blog/common-mistakes-with-react-testing-library)
- [Vitest: Mocking Requests](https://vitest.dev/guide/mocking/requests)
- [Google Testing Blog: Code Coverage Best Practices](https://testing.googleblog.com/2020/08/code-coverage-best-practices.html)
