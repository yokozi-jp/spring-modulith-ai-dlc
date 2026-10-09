---
type: Reference
title: Lintとテストのリファレンス
description: 静的解析、スキャン、テストのTask仕様、フロントエンドのLintの例外と範囲ごとの制限と書き方、GitフックおよびCIでの自動実行の対応をまとめ、各検査の内容、成否条件、実行場所を確認するとき、Lintの規則を緩めるか判断するときに読むリファレンス。
tags: [reference, testing, lint, ci]
---

# Lintとテストのリファレンス

検査は[`Taskfile.yml`](../../Taskfile.yml)の公開Taskから実行する。
場面ごとの実行順は[開発ワークフロー](dev-workflow.md)を参照する。
Dockerを使うTaskは、Dockerがないローカル環境ではスキップし、CIで検査する。

## 入口Task

- **`task check`**：バックエンドの静的解析を実行する。
- **`task verify`**：バックエンドの静的解析、マイグレーション検証、テストを実行する。
- **`task fe-verify`**：フロントエンドの静的解析、未使用コード検査、テスト、テレメトリを無効にした本番ビルドを実行する。
- **`task test`**：隔離した依存を起動し、確定済みマイグレーションの検証、バックエンドテスト、OpenAPI契約検査後に片付ける。
- **`task test-dev`**：隔離した依存を起動し、作りかけのchangesetを含むバックエンドテスト後に片付ける。
- **`task mutation-test`**：隔離した依存を使ってバックエンドのPITミューテーションテストを実行する。
- **`task e2e`**：compose-testでbackendを起動し、Vite previewに対してPlaywrightのE2Eを実行して後片付けする（[E2Eテストの方針と書き方](../e2e/testing-strategy.md)）。

## フロントエンド

- **`task fe-check`**：Oxfmt、Oxlint、TypeScriptの型を非破壊で検査する。
- **`task fe-knip`**：未参照ファイル、未使用export、未使用依存をKnipで検査する。
- **`task fe-doctor`**：React Doctorでwarningとerrorを検出し、検出または15分超過で失敗する。
- **`task fe-coverage`**：VitestのV8 providerで全体branch coverage 85%を検証する。
- **`task fe-test-build`**：coverage付きテストと、`FRONTEND_OTEL_ENABLED=false`を強制した本番ビルドを実行する。
  ビルドの後に`dist/assets`を`faro|grafana|opentelemetry|web.?vitals`でgrepし、bundleにFaro、OpenTelemetry JS、Web Vitalsのコードが入っていれば失敗する（[ADR-068](../adr/ADR-068-collect-browser-telemetry-with-faro-via-collector.md)）。
- **`task fe-verify`**：`fe-check`、`fe-knip`、`fe-test-build`（テレメトリを無効にした本番ビルドとgrepを含む）を実行する。
  テストのうち`frontend/vite-config.test.ts`は、ZAPの10055-6の除外の`evidence`（`docker/zap/passive.yaml`と`active.yaml`）が配信するCSPと一致することも検査する。
- **`task fe-route-tree-check`**：ビルドで`routeTree.gen.ts`を再生成し、コミット済みの内容と差分があれば失敗する。
  再生成した`fullPaths`がCollectorのroute allowlist（`transform/frontend_validate`の1か所）と一致しなければ、差分の検査より前に失敗する。
- **`task api-client-check`**：Orvalで`src/api/generated`を再生成し、コミット済みの内容と差分があれば失敗する（後述の「API契約」）。

## フロントエンドのLint設定

Oxlintの設定の正本は[`frontend/vite.config.ts`](../../frontend/vite.config.ts)の`lint`である。
カテゴリをすべてエラーにし、次の規則だけを無効化または調整する（[ADR-034](../adr/ADR-034-pin-frontend-runtime-and-enforce-oxlint-categories.md)）。
`radix`、`no-new-wrappers`、`typescript/consistent-type-definitions`、`import/no-relative-parent-imports`はカテゴリ指定で有効なので、個別に列挙しない。

### 例外と調整

- **automatic JSX runtimeとReactの書き方**：`react/react-in-jsx-scope`、`react/forbid-component-props`、`react/jsx-max-depth`、`react/jsx-props-no-spreading`をoffにする。
  automatic JSX runtimeと、`className`とprops spreadの書き方と両立しないためである。
- **JSXのファイル**：`react/jsx-filename-extension`で`.jsx`と`.tsx`を許可する。
  既定は`.jsx`だけを許すためである。
- **exportの形**：`import/no-default-export`、`import/no-named-export`、`import/prefer-default-export`、`import/exports-last`、`import/group-exports`をoffにする。
  互いに矛盾し、Vite設定のdefault exportとTanStack Routerのnamed exportを禁じるためである。
- **CSSの読込**：`import/no-unassigned-import`で`**/*.css`を許可する。
  CSSは副作用importで読むためである。
- **modernな構文**：`oxc/no-async-await`、`oxc/no-optional-chaining`、`oxc/no-rest-spread-properties`をoffにする。
  modernなTypeScriptの構文を一律に禁じるためである。
- **型の明示**：`typescript/explicit-function-return-type`、`typescript/explicit-module-boundary-types`、`typescript/prefer-readonly-parameter-types`、`typescript/promise-function-async`をoffにする。
  型推論と役割が重複するためである。
- **`void`**：`no-void`を`allowAsStatement: true`にする。
  event handlerでrejectしないPromiseを捨てる文を書けるようにするためである（後述）。
- **`undefined`**：`no-undefined`をoffにし、`x === undefined`で比較する。
  上書きとshadowingは、有効なままの`no-global-assign`と`no-shadow-restricted-names`が防ぐ。
- **一律のstyle規則**：`capitalized-comments`、`func-style`、`max-lines-per-function`、`no-magic-numbers`、`no-ternary`、`one-var`、`sort-imports`、`sort-keys`をoffにする。
  Oxfmtと役割が重複するか、可読性を下げるためである。
- **短い識別子**：`id-length`で`t`だけを例外にする。
  react-i18nextの翻訳関数の慣用名であるためである。
- **ビルド時の定数**：`no-underscore-dangle`で`__TELEMETRY_ENABLED__`と`__TELEMETRY_APP__`だけを許可する。
  `vite.config.ts`の`define`が置き換える定数を、ほかの識別子と衝突しない名前にするためである（[ADR-068](../adr/ADR-068-collect-browser-telemetry-with-faro-via-collector.md)）。
- **型のimport**：`no-duplicate-imports`を`allowSeparateTypeImports: true`にし、型のimportを分けて書けるようにする。
- **Vitestの書き方**：`vitest/no-conditional-in-test`、`vitest/no-hooks`、`vitest/no-importing-vitest-globals`、`vitest/prefer-called-times`、`vitest/prefer-describe-function-title`、`vitest/prefer-expect-assertions`、`vitest/prefer-lowercase-title`、`vitest/prefer-strict-boolean-matchers`、`vitest/prefer-to-be-truthy`、`vitest/require-hook`、`vitest/require-test-timeout`をoffにする。
  Vitestの標準APIと競合するか、互いに矛盾するためである。
- **module mock**：`vitest/no-restricted-vi-methods`に`mock`と`doMock`を指定し、`vi.mock`と`vi.doMock`を禁止する。
  module mockはテストを実装の詳細に依存させるため、HTTPはMSW、globalは`vi.spyOn`か`vi.stubGlobal`で置き換える（[フロントエンドのテストと検証](../frontend/testing.md#書き方)）。
  この規則はrestrictionカテゴリで有効だが、optionを指定しないと何も禁止しない。
- **Tailwindのclass**：`better-tailwindcss/enforce-canonical-classes`を有効にし、classを正規形にそろえる（`--fix`で直せる）。
  `better-tailwindcss/enforce-shorthand-classes`は役割が重なるため有効にしない。
- **`lint/**`**：`import/no-nodejs-modules`、`new-cap`、`typescript/no-unsafe-assignment`、`typescript/no-unsafe-call`、`typescript/no-unsafe-member-access`をoffにする。
  Node側のLintツールであり、OxlintのJS plugin APIに型が無いためである。
- **`src/components/ui/**`**：shadcnの生成物の書き方に合わせて`shadcn/no-restyle`、`shadcn/no-arbitrary-values`、`shadcn/require-static-classes`をoffにする。
  `react/only-export-components`もoffにし、React Doctorの同じruleも`frontend/doctor.config.json`で外す。
  shadcnの生成物は`buttonVariants`のようなvariantをcomponentと同じファイルからexportするためである。
- **`src/routes/**`**：`react/only-export-components`をoffにする。
  TanStack Routerのroute fileは`Route`をexportするためである。
- **テストファイル**：`react/jsx-no-literals`をoffにし、テストの中の固定文言をcatalogに通さずに書けるようにする。

生成物の`src/routeTree.gen.ts`と`src/api/generated/**`は、Lint、整形、coverageから外す。
`src/api/generated/**`はKnipとjscpdの対象からも外す。
違反を含むfixtureの`lint/fixtures/**`は、Lint、整形、テストの収集、Knipから外す。

### ファイルの範囲ごとの制限

`no-restricted-imports`、`no-restricted-globals`、`no-restricted-properties`は、overrideごとにoptionが置き換わり、マージされない。
そのため禁止の一覧を`vite.config.ts`の定数に分け、overrideごとに組み合わせている。
同じファイルに複数のoverrideが一致すると、後のoverrideのoptionだけが効くので、overrideの並び順を変えるときは組み合わせ結果を確かめる。

| 対象のファイル                     | `no-restricted-imports`                       | `no-restricted-globals`と`no-restricted-properties` |
| ---------------------------------- | --------------------------------------------- | --------------------------------------------------- |
| すべて（top-level）                | Base UI、テスト用部品、テレメトリSDK          | HTML sink、network                                  |
| `src/{api,components,lib,i18n}/**` | 共有層、Base UI、テスト用部品、テレメトリSDK  | top-levelを引き継ぐ                                 |
| `src/lib/telemetry.ts`             | 共有層、Base UI、テスト用部品                 | top-levelを引き継ぐ                                 |
| `src/features/**`                  | feature、Base UI、テスト用部品、テレメトリSDK | top-levelを引き継ぐ                                 |
| `src/components/ui/**`             | 共有層、テスト用部品、テレメトリSDK           | top-levelを引き継ぐ                                 |
| `src/api/**`                       | 共有層の設定を引き継ぐ                        | HTML sinkだけ                                       |
| テストファイルと`src/testing/**`   | Base UI、テレメトリSDK                        | top-levelを引き継ぐ                                 |

各禁止の中身は次のとおりである。

- **共有層**：`@/features/**`、`@/routes/**`、`@/routeTree.gen`。
- **feature**：`@/routes/**`、`@/routeTree.gen`。
  routeの情報は`getRouteApi`で取る。
- **Base UI**：`@base-ui/**`。
  `@/components/ui`からimportする。
- **テスト用部品**：`msw`、`msw/**`、`@/api/generated/mocks/**`、`@/testing/**`、`@testing-library/**`。
- **テレメトリSDK**：`@grafana/*`。
  `src/lib/telemetry.ts`を通して呼ぶ（[ADR-068](../adr/ADR-068-collect-browser-telemetry-with-faro-via-collector.md)）。
  静的importだけを検出し、`import()`は検出しない。
- **HTML sink**：globalの`DOMParser`、`innerHTML`などのHTML系property、`document.write`、`document.writeln`。
- **network**：globalの`fetch`と`XMLHttpRequest`、`window.fetch`、`globalThis.fetch`。
  `src/api`の生成clientを使う。

テストファイルでは層のimport制限も外れる。
テストにも層の制限が要るようになったら、dependency-cruiserへ移る。

`e2e/**`では、Playwright TestのAPIにvitest pluginの規則が当たるため、`vitest/consistent-test-filename`と`vitest/prefer-importing-vitest-globals`を外す。

feature間のimportは、これとは別にjsPluginの`feature-boundaries/no-cross-feature-import`が`src/features/**`のファイルで禁じる（[フロントエンドアーキテクチャ](../frontend/architecture.md#境界の検査)）。

組み合わせた結果は、`frontend/lint/lint-config.test.js`が`frontend/lint/fixtures/src/**`の違反例と正しい例を実際の設定でLintして確かめる。

### Lintを通る書き方

event handlerからPromiseを返す関数を呼ぶときは、handlerの中で`void`を付けた文にする。

```tsx
<Button
  onClick={() => {
    void queryClient.invalidateQueries({ queryKey: getListOrdersQueryKey() });
  }}
/>
```

`void`を付けてよいのは、次のPromiseに限る。

- rejectしないPromise（`invalidateQueries`、`refetchQueries`、`refetch`）。
- 失敗を内部で処理したPromise。
- `onSubmit`が例外を投げない（`mutate`を使う）場合の`form.handleSubmit()`。

`typescript/no-misused-promises`と`typescript/strict-void-return`は既定のまま有効であり、async関数をJSXのevent handlerに直接渡さない。

`undefined`の判定は`x === undefined`と`x !== undefined`で書く。

`exactOptionalPropertyTypes`の下で、optionalな値を生成されたrequest型へ渡すときは、`&&`形の条件付きspreadで書く。

```ts
const params = { ...(cursor !== undefined && { cursor }) };
```

## バックエンド

- **`task be-lint`**：Spotless、PMD、SpotBugsでmainとtestを検査する。
- **`task be-openapi-lint`**：起動済みのテスト用依存でOpenAPI 3.1契約を`openapi/openapi.yaml`へ生成し、Spectralで検査する。
- **`task be-openapi-check`**：直前の`be-test`が書き出した契約をSpectralで検査し、コミット済みの`openapi/openapi.yaml`と差分があれば失敗する。
  テストを実行しないため、`be-test`の後に実行する。
  単体で実行すると、コミット済みの契約を自分自身と比べるので、差分を検出できない。
- **`task be-verify-migrations`**：使い捨てDBでchangesetの適用、rollback、再適用、現在タグを検証する。
- **`task be-test`**：起動済みのテスト用依存に確定済みchangesetを適用し、テストとcoverage検証を実行する。
  `-PopenapiExport=true`を渡すので、`OpenApiContractTest`が`openapi/openapi.yaml`を書き出す。
  素の`./gradlew test`は契約に触れない。
- **`task be-test-dev`**：起動済みのテスト用依存に作りかけのchangesetを含めて適用し、テストを実行する。
- **`task be-mutation-test`**：起動済みのテスト用依存でPITを実行する。
- **`task test`**：テスト用依存の起動、`be-verify-migrations`、`be-test`、`api-lint`、後片付けを実行する。
- **`task test-dev`**：テスト用依存の起動、`be-test-dev`、後片付けを実行する。
- **`task mutation-test`**：テスト用依存の起動、マイグレーション検証、`be-mutation-test`、後片付けを実行する。

`task test`と`task mutation-test`は、`docker/compose-test.yml`のPostgreSQL 5433とRedis 6380を`.env.test`で起動する。
`.env.test`は、Gitで管理する`.env.test.example`からコピーして作るローカルファイルで、無ければTaskが作る。
PITのHTMLとXMLのレポートは、変異対象がある場合に`backend/build/reports/pitest/`へ出力する。
プロパティベーステストとミューテーションテストの採用理由は[ADR-012](../adr/ADR-012-adopt-property-based-and-mutation-testing.md)を参照する。

## API契約

契約と生成物の扱いは[ADR-052](../adr/ADR-052-commit-openapi-contract-and-check-generated-client.md)、手順は[APIを変更する](../web-api/runbook-api-change.md)を参照する。

- **`task api-gen`**：テスト用依存を起動し、契約の生成、Spectralの検査、Orvalの再生成を実行して片付ける。
- **`task api-check`**：`api-gen`で再生成した契約と生成物を、コミット済みの内容と比べる。
- **`task api-lint`**：コミット済みの`openapi/openapi.yaml`を`.spectral.yaml`で検査する（DB不要）。
- **`task api-lint-rules-test`**：Spectralのルールを`openapi/spectral-fixtures/`の合格例と違反例で検査する。
- **`task api-client-gen`**：コミット済みの契約からOrvalでAPI clientとMSW handlerを再生成する（DB不要）。
- **`task api-client-check`**：`api-client-gen`で再生成した生成物を、コミット済みの内容と比べる（DB不要）。
- **`task api-breaking`**：`origin/<BASE_REF>`（既定は`main`）の契約とoasdiffで比べ、破壊的変更があれば失敗する。
  baseに契約がなければ比較をとばす。
  環境変数`API_BREAKING_APPROVED=true`（CIではPull Requestのラベル`api-breaking-approved`）のときは、結果をログに残して失敗にしない。
- **`task api-docs`**：契約からRedocly CLIで静的HTMLの設計書`build/api-docs/index.html`を作る。

差分の比較は作業ツリーをindexと比べ、未追跡の生成物も差分として扱う。

## リポジトリ全体

- **`task lint-duplicates`**：生成コードを除く手書きのフロントエンドとバックエンドをjscpdで検査する。
- **`task scan-secrets`**：ステージ済み変更をbetterleaksでスキャンする。
- **`task scan-secrets-all`**：リポジトリ全体と、現在のブランチから辿れる履歴をbetterleaksでスキャンする。
- **`task lint-semgrep`**：Semgrep OSSで静的解析する。
- **`task be-sbom`**：バックエンドのCycloneDX SBOMを生成する。
- **`task scan-vulns`**：バックエンドとフロントエンドの依存関係をTrivyで検査する。
- **`task scan-vulns-backend`**：バックエンドのCycloneDX SBOMをTrivyで検査する。
- **`task scan-vulns-frontend`**：フロントエンドの解決済み依存関係をTrivyで検査する。
- **`task scan-dast`**：隔離した依存、backend、`vp preview`を起動し、OWASP ZAPで認証付きのpassive scanを実行して片付ける。
- **`task scan-dast-active`**：同じ環境でactive scanを実行する。
- **`task lint-actions`**：GitHub Actionsワークフローをactionlintで検査する。
- **`task lint-actions-security`**：GitHub Actionsワークフローをzizmorで検査する。
- **`task lint-docker`**：Dockerfileをhadolintで検査する。
- **`task lint-docker-check`**：Dockerfileを`docker build --check`で検査する。
- **`task lint-compose`**：Composeファイルの構文、参照、変数展開を検証する。
- **`task otel-collector-check`**：許可していない属性を含むOTLPのログをCollectorに流し、その属性が除かれ、許可した属性が残ることを確かめる。
  最初に、Collectorのroute allowlistが`routeTree.gen.ts`の`fullPaths`と一致することを確かめる。
  Faroのfixtureもフロントエンドのpipelineに流し、例外、View、LCP、INP、CLSが同じ匿名session IDを持つことを確かめる。
  許可していない属性、URL token、UUIDのsession IDが出口に残らないことも検査する。
  同一オリジンの`/assets/<名前>.js`のstack frameだけが、pathと行と列で残ることも検査する。
  CSP違反の報告のfixtureを`logs/frontend_csp`に流し、許可した7つの属性だけが残り、CSP以外の報告の型、`disposition`のない報告、64 KiBを超える本文が捨てられることも検査する。
  数値でないportと`@`を重ねたuserinfoの`blockedURL`が`csp.blocked`に残らず、別オリジンの`sourceFile`が`code.file.path`に残らないことも検査する。
  受け口が`application/reports+json`以外の`Content-Type`に401を返し、`text/plain`で送った有効な本文が出口に届かないことも検査する。
  このタスクは`webhook_event`のportを直接叩き、Viteのproxyを通らない。
  Viteのproxyのhop（`/csp-report`と`/collect`の転送と資格情報のheaderの削除）は、`frontend/vite-config.test.ts`が偽の受け口で検査する。
  ブラウザがHTTPSの配信から報告を送り、Grafanaに届くまでの経路全体は手動で実測する。
  同じfixtureのtraceは`traces/frontend`の出口を別のファイルに分け、URL属性がなく、許可したHTTP属性とresource属性だけが残り、trace ID、span ID、親span IDが保たれることを確かめる。
- **`task lint-md`**：`.markdownlint-cli2.yaml`の除外設定に従いMarkdownを検査する。
- **`task lint-md-fix`**：markdownlint-cli2で安全に修正できるMarkdownの問題を修正する。
- **`task okf-check`**：OKF適合、内部リンク、孤立文書、文書責務の見直し合図、steering境界、Taskfile文書同期候補を検査する。
- **`task release-check`**：release-pleaseのmanifest、版ファイル、Gradle版、設定の一致を検証する。
- **`task lint-taskfile`**：Task本体でTaskfileのYAML構文とスキーマ構造を検証する。
- **`task adr-check`**：判断が絡む変更にADRが伴うかを確認する。

Knipとjscpdの採用理由は[ADR-035](../adr/ADR-035-adopt-jscpd-and-knip-quality-gates.md)を、jscpdの閾値を4.0%にする理由は[ADR-074](../adr/ADR-074-raise-jscpd-threshold-for-class-role-boilerplate.md)を参照する。
DASTの検出ではタスクを失敗させず、起動、ログイン、CSRFの前提確認の失敗だけで失敗させる（[ADR-056](../adr/ADR-056-run-authenticated-dast-with-zap-in-ci.md)）。
OKF検査の採用理由は[ADR-036](../adr/ADR-036-adopt-okf-for-docs-knowledge-bundle.md)と[ADR-038](../adr/ADR-038-route-steering-to-docs-knowledge.md)を参照する。

## Gitフック対応

- **commit-msg（すべて）**：commitlintを実行する。
- **pre-commit（すべて）**：`scan-secrets`相当を実行する。
- **pre-commit（OpenAPI契約、`.spectral.yaml`、Orvalの設定、`frontend/package.json`、生成物の変更）**：`api-lint`（Dockerがなければスキップ）と`api-client-check`を実行する。
- **pre-commit（フロントエンドまたはTaskfileの変更）**：`fe-check`を実行する。
  前項と同じgroupで順に実行し、Orvalの再生成と型検査を同時に走らせない。
- **pre-commit（Dockerfileの変更）**：`lint-docker`相当と`lint-docker-check`相当を実行する。
- **pre-commit（Composeファイルの変更）**：`lint-compose`相当を実行する。
- **pre-commit（Markdownの変更）**：変更ファイルへ`lint-md`相当を実行する。
- **pre-commit（docs、steering、Taskfileの変更）**：`okf-check`を実行する。
- **pre-push（すべて）**：`adr-check`と`scan-secrets-all`相当を実行する。
- **pre-push（フロントエンドまたはTaskfileの変更）**：`fe-test-build`を実行する。
- **pre-push（バックエンドのJavaまたはGradle変更）**：`be-lint`相当と`test`を実行する。
- **pre-push（バックエンドまたはDockerfileの変更）**：バックエンドイメージのbuild stageをビルドする。
- **pre-push（GitHub Actionsワークフローの変更）**：`lint-actions`相当と`lint-actions-security`相当を実行する。

Gitフックの条件とコマンドは[`lefthook.yml`](../../lefthook.yml)を正とする。

## CI対応

- **`frontend-ci.yml`**：`fe-verify`、`fe-route-tree-check`、`api-client-check`、`fe-doctor`を実行する。
- **`backend-ci.yml`**：`be-lint`相当、`be-verify-migrations`、`be-test`、`be-openapi-check`、手動実行時の`mutation-test`を実行する。
  compose-testを止めた後に`test-deps-leftover-check`を実行する。
  契約は`be-test`が書き出し、`be-openapi-check`は`OpenApiContractTest`を再実行しない（[ADR-064](../adr/ADR-064-write-openapi-contract-from-test-task-in-ci.md)）。
  Gradle User Homeは`setup-gradle`の`cache-provider: external`にして、`actions/cache`のrestoreとsaveで扱う。
  keyはビルドファイルのhashで、restore-keysにより直近のmainのcacheを復元する。
  saveはmainのpushで、restoreが完全一致でなかったときだけ行う（[ADR-063](../adr/ADR-063-restore-gradle-cache-with-restore-keys-on-mit-caching.md)）。
- **`api-contract.yml`**：`api-lint`、`api-lint-rules-test`、`api-breaking`、`api-docs`を実行し、設計書をartifactにする。
  mainへのpushでは設計書をGitHub Pagesに公開する。
- **`betterleaks.yml`**：`scan-secrets-all`相当を実行する。
- **`static-analysis.yml`**：`lint-semgrep`相当と`lint-duplicates`を実行する。
- **`trivy.yml`**：`scan-vulns`相当を実行する。
- **`dast.yml`**：PRで`scan-dast`、週1回のscheduleと手動実行で`scan-dast-active`を実行し、Informationalを除いたSARIFをCode Scanningに送る。
- **`actionlint.yml`**：`lint-actions`相当を実行する。
- **`zizmor.yml`**：`lint-actions-security`相当を実行する。
- **`hadolint.yml`**：`lint-docker`相当、`lint-docker-check`相当、バックエンドイメージのビルドと起動確認を実行する。
- **`compose-config.yml`**：`lint-compose`相当を実行する。
- **`otel-collector.yml`**：`otel-collector-check`を実行する。
- **`markdownlint.yml`**：`lint-md`相当を実行する。
- **`okf-validate.yml`**：`okf-check`を実行する。
- **`release-please.yml`**：`release-check`を実行する。
- **`e2e.yml`**：`task e2e`を実行し、`test-deps-leftover-check`でcompose-testのコンテナとvolumeが残っていないことを確かめる。
  Backend CIのtestと同じGradle cacheをread-onlyで復元する。
  必須チェックにしない（[ブランチ保護](../repository/branch-protection.md)）。

各ワークフローの実装は[`.github/workflows/`](../../.github/workflows/)を正とする。
