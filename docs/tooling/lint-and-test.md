---
type: Reference
title: Lintとテストのリファレンス
description: 静的解析、スキャン、テストのTask仕様、フロントエンドのLintの例外と範囲ごとの制限と書き方、GitフックおよびCIでの自動実行の対応をまとめ、各検査の内容、成否条件、実行場所を確認するとき、Lintの規則を緩めるか判断するときに読むリファレンス。
tags: [reference, testing, lint, ci]
---

# Lintとテストのリファレンス

検査は[`Taskfile.yml`](../../Taskfile.yml)の公開Taskから実行する。
場面ごとの実行順は[開発ワークフロー](dev-workflow.md)を参照する。
Gitフックの中でDockerを使う検査は、`docker info`が失敗するローカル環境ではスキップし、CIで検査する。
`docker`コマンドがあってもデーモンに接続できなければ、Dockerを使えないとみなす。

## 入口Task

- **`task check`**：`lint-session-sql`とバックエンドの静的解析を実行する。
- **`task verify`**：`lint-session-sql`、バックエンドの静的解析、マイグレーション検証、テストを実行する。
- **`task test`**：確定済みマイグレーションを使い捨てのPostgreSQLで検証し、バックエンドテスト、OpenAPI契約検査の後に片付ける。
- **`task test-dev`**：`be-test-dev`の別名。
- **`task mutation-test`**：`be-mutation-test`の別名。
- **`task fe-verify`**：フロントエンドの静的解析、未使用コード検査、テスト、テレメトリを無効にした本番ビルドを実行する。
- **`task e2e`**：compose-testでbackendを起動し、Vite previewに対してPlaywrightのE2Eを実行して後片付けする（[E2Eテストの方針と書き方](../e2e/testing-strategy.md)）。

## バックエンド

- **`task be-lint`**：Spotless、PMD、SpotBugsでmainとtestを検査する。
  Spotlessは`ratchetFrom 'origin/main'`で、mainから変えたファイルを全体で検査する。変えていない既存の行の書式も違反になるので、`task be-format`の出力をその変更に含める。
- **`task lint-session-sql`**：`backend/src/main/java`のJavaと`backend/src/main/resources/db`のSQLとYAMLから、接続を多重化するプロキシ（PgBouncerのトランザクションモード、RDS Proxy）の後ろで壊れる、セッションに状態を残すSQLを検出し、あれば失敗する。
  対象は、[PgBouncerの機能の表](https://www.pgbouncer.org/features.html)でTransaction poolingがNeverの機能のうち、セッションのadvisory lock（`pg_advisory_lock`、`pg_try_advisory_lock`、`pg_advisory_unlock`、`pg_advisory_unlock_all`と`_shared`の版）、`LISTEN`、`WITH HOLD`のカーソル、`PREPARE`と`DEALLOCATE`の文、`ON COMMIT PRESERVE ROWS`と`ON COMMIT DELETE ROWS`の一時表、`LOAD`である。
  大文字と小文字を区別せず、Javadoc、行コメント、YAMLとSQLのコメントの行は除く。
  トランザクションのadvisory lock（`pg_advisory_xact_lock`、`pg_try_advisory_xact_lock`）、`ON COMMIT DROP`、`LISTENER_ID`のような識別子は検出しない。
  検査の本体は`tooling/session-sql-lint/session-sql-lint.sh`で、検出の前に`tooling/session-sql-lint/test.sh`が、検出すべき文と検出しない文のfixtureで終了コードと出力を確かめる。
  `SET`と`RESET`も同じ表でNeverだが、`spring.datasource.hikari.connection-init-sql`がセッションの`SET`を使うため検出しない（[ADR-079](../adr/ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)）。
  設計の理由は[設計の作り込みの程度と段階的な再構築](../principles/right-sizing.md)の「テンプレートの利用先で変わる前提」にある。
- **`task be-openapi-lint`**：`OpenApiContractTest`だけを実行してOpenAPI 3.1契約を`openapi/openapi.yaml`へ生成し、Spectralで検査する。
- **`task be-openapi-check`**：直前の`be-test`が書き出した契約をSpectralで検査し、コミット済みの`openapi/openapi.yaml`と差分があれば失敗する。
  テストを実行しないため、`be-test`の後に実行する。
  単体で実行すると、コミット済みの契約を自分自身と比べるので、差分を検出できない。
- **`task be-verify-migrations`**：現在タグが最後のchangelogに宣言されていることと、使い捨てDBでのchangesetの適用、rollback、再適用、現在タグの存在を検証する。
- **`task be-test`**：確定済みchangesetを適用したテスト用のDBで、テストとcoverage検証を実行する。
  JaCoCoの下限は、全体のbranch 88%とline 95%、クラスごとのbranch 65%とline 70%で、下回ると失敗する（[ADR-076](../adr/ADR-076-run-mutation-tests-per-change-and-tighten-coverage-gates.md)）。
  `-PopenapiExport=true`を渡すので、`OpenApiContractTest`が`openapi/openapi.yaml`を書き出す。
  素の`./gradlew test`は契約に触れない。
- **`task be-test-dev`**：作りかけのchangesetを含めて適用したテスト用のDBで、テストを実行する。
  カバレッジは検証しない。
- **`task be-mutation-test`**：PITを実行する。
  `PIT_CHANGED_SINCE`にgitの参照（`origin/main`など）を渡すと、そこからの差分の`src/main/java`のクラスと内部クラスだけを変異させる。
  差分のテスト`FooTest`は、同じパッケージの`Foo`を変異させる対象に加える。
  テストは対象のクラスのパッケージ以下のものだけを使う。
  対象のクラスがないときはPITを実行せず、差分を取れないときは失敗する。
  minionは1つにし、Springの起動を待てるようにtimeoutを既定より広げる。
- **`task test`**：compose-testのPostgreSQLの起動、`be-verify-migrations`、PostgreSQLの停止、`be-test`、`api-lint`を実行する。
  前の回が残したcompose-testは、起動の前に片付ける。

テストが使うPostgreSQL、Redis、WireMockは、テストのJVMごとにTestcontainersが起動して片付け、Gradleのforkを並列にする（[バックエンドのテストの実行基盤](../backend/testing-infrastructure.md)）。
`docker/compose-test.yml`のPostgreSQL 5433は、`be-verify-migrations`のrollbackの検証だけが使う。
`.env.test`は、Gitで管理する`.env.test.example`からコピーして作るローカルファイルで、無ければTaskが作る。
GradleのテストとPITは、接続先以外の設定を`.env.test`から読む。
PITのHTMLとXMLのレポートは、変異対象がある場合に`backend/build/reports/pitest/`へ出力する。
プロパティベーステストとミューテーションテストの採用理由は[ADR-012](../adr/ADR-012-adopt-property-based-and-mutation-testing.md)を参照する。

## フロントエンド

- **`task fe-check`**：workspaceのルートの`frontend`で`vp check`を実行し、Oxfmt、Oxlint、TypeScriptの型を非破壊で検査する。
- **`task fe-knip`**：未参照ファイル、未使用export、未使用依存をKnipで検査する。
  `packages/ui`は`includeEntryExports: true`にし、`exports`で公開した部品の未使用exportも報告する。
- **`task fe-doctor`**：`apps/web`のReact Doctorでwarningとerrorを検出し、検出または15分超過で失敗する。`packages/ui`は対象にしない。
  `packages/ui`を対象に加えるときは、`react-doctor/only-export-components`の例外が要る。`buttonVariants`のようなvariantをcomponentと同じファイルからexportするためである。
- **`task fe-coverage`**：React Compilerを有効にして、`vp run --recursive test`でルートのlint設定のテストと各packageのテストを実行する。
  そのあと、Compilerを外した状態で、ルートを除くpackageごとにVitestのV8 providerのcoverageを測り、下限もpackageごとに検証する。
  `apps/web`の全体の下限はbranch 88%とline 95%、`packages/api`の全体の下限は切り出した時点の実測の少し下のbranch 90%とline 98%、`packages/ui`の全体の下限はbranch 95%とline 95%である。ファイルごとの下限は全packageで共通のbranch 60%とline 65%である。
  `packages/ui`のテストは、jsdomとTesting Libraryを使わず、server renderの出力とresolveだけを確かめる。操作が要る部品を足すときに、DOMの環境を足す。
  `packages/ui`のテストはReact Compilerなしで走る。`apps/web`のようにCompilerを有効にしたテストは、この package では行わない。
  全体の下限は各packageの`vite.config.ts`の`thresholds`が、ファイルごとの下限はTaskが各packageの`coverage/coverage-summary.json`から検査する（[ADR-076](../adr/ADR-076-run-mutation-tests-per-change-and-tighten-coverage-gates.md)）。
  Compilerを外すのは環境変数`FRONTEND_REACT_COMPILER=false`で、理由は[ADR-078](../adr/ADR-078-measure-frontend-coverage-without-react-compiler.md)にある。
  `FE_TEST_ARGS`に渡した値は、ルートのlint設定のテスト、各packageのテスト、coverageの測定のVitestの実行すべてに足す。ファイルのパスは渡さず、reporterなどのoptionだけを渡す（例：`task fe-verify FE_TEST_ARGS=--reporter=dot`）。
  テストのtimeoutは`apps/web/vite.config.ts`の`testTimeout`で30秒にする。
- **`task fe-test-build`**：`fe-coverage`と、`FRONTEND_OTEL_ENABLED=false`を強制した本番ビルドを実行する。
  ビルドの後に`apps/web/dist/assets`を`faro|grafana|opentelemetry|web.?vitals`でgrepし、bundleにFaro、OpenTelemetry JS、Web Vitalsのコードが入っていれば失敗する（[ADR-068](../adr/ADR-068-collect-browser-telemetry-with-faro-via-collector.md)）。
- **`task fe-verify`**：`fe-check`、`fe-knip`、`fe-test-build`（テレメトリを無効にした本番ビルドとgrepを含む）を実行する。
  テストのうち`frontend/apps/web/vite-config.test.ts`は、ZAPの10055-6の除外の`evidence`（`docker/zap/passive.yaml`と`active.yaml`）が配信するCSPと一致することも検査する。
- **`task fe-route-tree-check`**：ビルドで`routeTree.gen.ts`を再生成し、コミット済みの内容と差分があれば失敗する。
  再生成した`fullPaths`がCollectorのroute allowlist（`transform/frontend_validate`の1か所）と一致しなければ、差分の検査より前に失敗する。
- **`task api-client-check`**：Orvalで`packages/api/src/generated`を再生成し、コミット済みの内容と差分があれば失敗する（後述の「API契約」）。

## フロントエンドのLint設定

Oxlintの設定の正本は[`frontend/vite.config.ts`](../../frontend/vite.config.ts)の`lint`である。
カテゴリをすべてエラーにし、次の規則だけを無効化または調整する（[ADR-034](../adr/ADR-034-pin-frontend-runtime-and-enforce-oxlint-categories.md)）。
`radix`、`no-new-wrappers`、`typescript/consistent-type-definitions`、`import/no-relative-parent-imports`はカテゴリ指定で有効なので、個別に列挙しない。

OxlintとOxfmtの設定は、workspaceのルートのこのファイルの`lint`と`fmt`だけに置き、`apps/web/vite.config.ts`には置かない（[ADR-092](../adr/ADR-092-split-frontend-into-vite-plus-workspace.md)）。
packageの側に`lint`がないため、`vp lint`はルートと`apps/web`のどちらで実行してもルートの設定を使う。
`better-tailwindcss/*`が読むCSSは、packageごとのoverrideが、設定ファイルの場所から作った絶対パスで渡す。
`apps/web/**`は`apps/web/src/style.css`を、`packages/ui/**`は`packages/ui/src/style.css`を指すので、実行する場所によらず同じく解決する。
`apps/web/src/style.css`は`@frontend/ui/style.css`を読み込むため、アプリのCSSからもテーマの変数を解決できる。

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
- **一律のstyle規則**：`capitalized-comments`、`max-lines-per-function`、`no-magic-numbers`、`no-ternary`、`one-var`、`sort-imports`、`sort-keys`をoffにする。
  Oxfmtと役割が重複するか、可読性を下げるためである。
- **関数の書き方**：`func-style`を`"declaration"`にする。
  名前付き関数は関数宣言にし、関数の型注釈から引数を推論させるアロー関数だけを理由付きの行単位の抑制で許す。
- **短い識別子**：`id-length`で`t`だけを例外にする。
  react-i18nextの翻訳関数の慣用名であるためである。
- **ビルド時の定数**：`no-underscore-dangle`で`__TELEMETRY_ENABLED__`と`__TELEMETRY_APP__`だけを許可する。
  `apps/web/vite.config.ts`の`define`が置き換える定数を、ほかの識別子と衝突しない名前にするためである（[ADR-068](../adr/ADR-068-collect-browser-telemetry-with-faro-via-collector.md)）。
- **型のimport**：`no-duplicate-imports`を`allowSeparateTypeImports: true`にし、型のimportを分けて書けるようにする。
- **Vitestの書き方**：`vitest/no-conditional-in-test`、`vitest/no-hooks`、`vitest/no-importing-vitest-globals`、`vitest/prefer-called-times`、`vitest/prefer-describe-function-title`、`vitest/prefer-expect-assertions`、`vitest/prefer-lowercase-title`、`vitest/prefer-strict-boolean-matchers`、`vitest/prefer-to-be-truthy`、`vitest/require-hook`、`vitest/require-test-timeout`をoffにする。
  Vitestの標準APIと競合するか、互いに矛盾するためである。
- **module mock**：`vitest/no-restricted-vi-methods`に`mock`と`doMock`を指定し、`vi.mock`と`vi.doMock`を禁止する。
  module mockはテストを実装の詳細に依存させるため、HTTPはMSW、globalは`vi.spyOn`か`vi.stubGlobal`で置き換える（[フロントエンドのテストと検証](../frontend/testing.md#書き方)）。
  この規則はrestrictionカテゴリで有効だが、optionを指定しないと何も禁止しない。
- **上限が固定の待ち**：`vitest/no-restricted-vi-methods`に`waitFor`と`waitUntil`も指定し、`vi.waitFor`と`vi.waitUntil`を禁止する。
  どちらも既定の上限の1秒を一括で変えられず、負荷の高いマシンで失敗するからである。
  代わりに、`findBy`か`waitFor`（Testing Library）、`expect.poll`、`createGate`のいずれかを使う。
  `findBy`、`waitFor`、`expect.poll`の上限は設定で一括して決まり、`createGate`は上限を持たない（[フロントエンドのテストと検証](../frontend/testing.md#書き方)）。
- **Tailwindのclass**：`better-tailwindcss/enforce-canonical-classes`を有効にし、classを正規形にそろえる（`--fix`で直せる）。
  `better-tailwindcss/enforce-shorthand-classes`は役割が重なるため有効にしない。
  jsPluginの`tailwind-classes/no-dynamic-class-name`は、`className`の中で値からclass名を組み立てるテンプレートリテラル（`` `pl-${depth}` ``、`` `${tone}${depth}` ``）を禁じる（[フロントエンドのUIとスタイル](../frontend/ui-and-style.md#uiの書き方)）。
  objectや配列からclassを選ぶkey（`` tones[`${tone}-strong`] ``）は対象にしない。
  関数の引数に渡すkey（`` tones.get(`${tone}-strong`) ``）は報告するため、classはobjectか配列に並べて選ぶ。
  `className`の外で組み立てた文字列と、`cn()`に渡す変数は検出しない。
- **`lint/**`**：`import/no-nodejs-modules`、`new-cap`、`typescript/no-unsafe-assignment`、`typescript/no-unsafe-call`、`typescript/no-unsafe-member-access`をoffにする。
  Node側のLintツールであり、OxlintのJS plugin APIに型が無いためである。
  `lint/**`はtsconfigの対象外で型検査が未定義の識別子を止めないため、Nodeのglobalを許した`no-undef`を有効にする。
- **`packages/ui/**`**：shadcnの生成物の書き方に合わせて`shadcn/no-restyle`、`shadcn/no-arbitrary-values`、`shadcn/require-static-classes`をoffにする。
  `react/only-export-components`もoffにする。
  shadcnの生成物は`buttonVariants`のようなvariantをcomponentと同じファイルからexportするためである。
  Base UIをimportできるのはこのpackageだけで、`apps/**`では禁止する。
- **shadcnの部品の認識**：`@shadcn/lint`は、アプリの`components.json`の`aliases.ui`（`@frontend/ui`）と、ルートの`settings.shadcn.componentImports`（`^@frontend/ui(/|$)`）の両方で、`@frontend/ui`からimportした部品を認識する。
  認識した部品の`className`に、部品が持つ見た目のclass（`p-4`など）を渡すと`shadcn/no-restyle`が報告し、`mt-2`のような配置のclassは許す。
- **`apps/web/src/routes/**`**：`react/only-export-components`をoffにする。
  TanStack Routerのroute fileは`Route`をexportするためである。
- **テストファイル**：`react/jsx-no-literals`をoffにし、テストの中の固定文言をcatalogに通さずに書けるようにする。
  `import/max-dependencies`もoffにする。
  テストのimportの数は検証対象に合わせて増え、テスト自身の責務の大きさを表さないからである。

生成物の`apps/*/src/routeTree.gen.ts`と`packages/api/src/generated/**`は、Lint、整形、coverageから外す。
`packages/api/src/generated/**`はjscpdとKnipの対象からも外す。
違反を含むfixtureの`lint/fixtures/**`は、Lint、整形、Knipから外し、ルートのテストの`--exclude`でテストの収集からも外す。

### ファイルの範囲ごとの制限

`no-restricted-imports`、`no-restricted-globals`、`no-restricted-properties`は、overrideごとにoptionが置き換わり、マージされない。
そのため禁止の一覧をルートの`vite.config.ts`の定数に分け、overrideごとに組み合わせている。
同じファイルに複数のoverrideが一致すると、後のoverrideのoptionだけが効くので、overrideの並び順を変えるときは組み合わせ結果を確かめる。

| 対象のファイル                          | `no-restricted-imports`                       | `no-restricted-globals`と`no-restricted-properties` |
| --------------------------------------- | --------------------------------------------- | --------------------------------------------------- |
| すべて（top-level）                     | Base UI、テスト用部品、テレメトリSDK          | HTML sink、network                                  |
| `apps/web/src/{api,components,i18n}/**` | 共有層、Base UI、テスト用部品、テレメトリSDK  | top-levelを引き継ぐ                                 |
| `apps/web/src/lib/**`                   | lib、Base UI、テスト用部品、テレメトリSDK     | top-levelを引き継ぐ                                 |
| `apps/web/src/lib/telemetry.ts`         | lib、Base UI、テスト用部品                    | top-levelを引き継ぐ                                 |
| `apps/web/src/features/**`              | feature、Base UI、テスト用部品、テレメトリSDK | top-levelを引き継ぐ                                 |
| `packages/ui/**`                        | テスト用部品、テレメトリSDK                   | top-levelを引き継ぐ                                 |
| `packages/api/**`                       | Base UI、テスト用部品、テレメトリSDK          | HTML sinkだけ                                       |
| テストファイルと`src/testing/**`        | Base UI、テレメトリSDK                        | top-levelを引き継ぐ                                 |

各禁止の中身は次のとおりである。

- **共有層**：`@/features/**`、`@/routes/**`、`@/routeTree.gen`。
- **lib**：共有層の禁止に加えて`@/components/**`。
  `lib`は依存の最下層であり、UIを参照しない。
- **feature**：`@/routes/**`、`@/routeTree.gen`。
  routeの情報は`getRouteApi`で取る。
- **Base UI**：`@base-ui/**`。
  `@frontend/ui`からimportする。`packages/ui/**`のproduction sourceだけが許す。
- **テスト用部品**：`msw`、`msw/**`、`@frontend/api/mocks/**`、`@/testing/**`、`@testing-library/**`。
- **テレメトリSDK**：`@grafana/*`。
  `src/lib/telemetry.ts`を通して呼ぶ（[ADR-068](../adr/ADR-068-collect-browser-telemetry-with-faro-via-collector.md)）。
  静的importと`import()`の両方を検出する。
- **HTML sink**：globalの`DOMParser`、`innerHTML`などのHTML系property、`document.write`、`document.writeln`。
- **network**：globalの`fetch`と`XMLHttpRequest`、`window.fetch`、`globalThis.fetch`。
  `@frontend/api`の生成clientを使う。`packages/api/**`だけが許可される。

テストファイルでは層のimport制限も外れる。
`packages/ui`のテストファイルもこのoverrideに当たるため、Base UIのimportは禁止のままである。
テストにも層の制限が要るようになったら、dependency-cruiserへ移る。

`apps/web/e2e/**`では、Playwright TestのAPIにvitest pluginの規則が当たるため、`vitest/consistent-test-filename`と`vitest/prefer-importing-vitest-globals`を外す。

feature間のimportは、これとは別にjsPluginの`feature-boundaries/no-cross-feature-import`が`apps/web/src/features/**`のファイルで禁じる（[フロントエンドアーキテクチャ](../frontend/architecture.md#境界の検査)）。

linkとbuttonの入れ子は、jsPluginの`ui-composition/no-button-in-link`がすべてのファイルで禁じる（[フロントエンドのUIとスタイル](../frontend/ui-and-style.md#uiの書き方)）。
`Link`か`a`の内側の`Button`か`button`と、`Button`の`render`に渡した`Link`か`a`を報告する。
`render`に関数を渡す場合は、関数が直接返す`Link`か`a`を報告する。
要素は名前だけで判定するので、別名でimportしたcomponentは検出しない。
変数に入れてから返す要素と、条件演算子で返す要素も検出しない。

`useEffect`、`useLayoutEffect`、`useInsertionEffect`、`useEffectEvent`は、jsPluginの`react-rules/no-effect`がすべてのファイルで禁じる（[フロントエンドのルーティングと状態管理](../frontend/routing-and-state.md#effectの禁止)）。
`react`からの名前付きimportと再export、default importの名前（`React.useEffect`）の参照、分割代入を報告する。
自前の関数で包んだEffectは検出しない。

組み合わせた結果は、`frontend/lint/lint-config.test.js`が`frontend/lint/fixtures/apps/web/src/**`と`frontend/lint/fixtures/packages/ui/src/**`の違反例と正しい例を実際の設定でLintして確かめる。
このテストは、`frontend/lint/lint-project.js`が、ルートの設定と、`apps/web`と`packages/ui`の`vite.config.ts`、`tsconfig.json`、`package.json`、`components.json`、`style.css`、fixtureを一時ディレクトリへ複製して`vp lint`を実行した結果を読む。
fixtureが`@frontend/ui`からimportする部品は、複製せず、実物の`packages/ui`のものを使う。
`frontend/lint/tailwind-entry-point.test.js`は、ルート、`apps/web`、`packages/ui`のどこから実行しても、そのpackageのCSSを解決できることを確かめる。
規則のoptionを追加または変更するときは、optionが許す範囲のすぐ外側の書き方を拒否するfixtureも置く。
optionの名前から許す範囲を推測せず、規則の文書とfixtureで確かめる。
jsPluginやoverrideの挙動を試すときも、一時ディレクトリへ設定を手で複製せず、fixtureを足して`frontend`で`vp test run --dir lint lint-config`を実行する。

### Lintを通る書き方

名前を付ける関数は、トップレベルでもcomponentやHookの中でも関数宣言で書く。
その場で渡すコールバックはアロー関数で書く（`prefer-arrow-callback`）。
関数の型注釈から引数を推論させる必要がある場合だけ、理由を付けて`func-style`を行単位で抑制し、アロー関数を変数に代入してよい。
`allowTypeAnnotation`は型注釈付きの関数式も許すため、使わない。

```tsx
function OrderRow({ order }: OrderRowProps) {
  function handleSelect() {
    onSelect(order.id);
  }
  return <Button onClick={handleSelect}>{order.code}</Button>;
}

// oxlint-disable-next-line func-style -- ChangeEventHandlerからeventの型を推論させる。
const onChange: ChangeEventHandler<HTMLInputElement> = (event) => {
  setCode(event.target.value);
};
```

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

## API契約

契約と生成物の扱いは[ADR-052](../adr/ADR-052-commit-openapi-contract-and-check-generated-client.md)、手順は[APIを変更する](../web-api/runbook-api-change.md)を参照する。

- **`task api-gen`**：契約の生成、Spectralの検査、Orvalの再生成を実行する。
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

- **`task lint-duplicates`**：生成コードを除く手書きのバックエンドとフロントエンドをjscpdで検査する。
- **`task scan-secrets`**：ステージ済み変更をbetterleaksでスキャンする。
- **`task scan-secrets-all`**：現在のブランチから辿れる履歴をbetterleaksでスキャンする。環境変数`BETTERLEAKS_LOG_OPTS`でgit logの範囲を変えられる（既定は`HEAD`）。
- **`task lint-semgrep`**：Semgrep OSSで静的解析する。
- **`task be-sbom`**：バックエンドのCycloneDX SBOMを生成する。
- **`task scan-vulns`**：バックエンドとフロントエンドの依存関係をTrivyで検査する。
- **`task scan-vulns-backend`**：バックエンドのCycloneDX SBOMをTrivyで検査する。
- **`task scan-vulns-frontend`**：フロントエンドの解決済み依存関係をTrivyで検査する。
- **`task scan-dast`**：隔離した依存、backend、`vp preview`を起動し、OWASP ZAPで認証付きのpassive scanを実行して片付ける。
- **`task scan-dast-active`**：同じ環境でactive scanを実行する。
- **`task lint-actions`**：GitHub Actionsワークフローをactionlintで検査する。
- **`task lint-actions-security`**：GitHub Actionsのワークフローとcomposite action（`.github/actions/`）をzizmorで検査する。
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
  Viteのproxyのhop（`/csp-report`と`/collect`の転送と資格情報のheaderの削除）は、`frontend/apps/web/vite-config.test.ts`が偽の受け口で検査する。
  ブラウザがHTTPSの配信から報告を送り、Grafanaに届くまでの経路全体は手動で実測する。
  同じfixtureのtraceは`traces/frontend`の出口を別のファイルに分け、URL属性がなく、許可したHTTP属性とresource属性だけが残り、trace ID、span ID、親span IDが保たれることを確かめる。
- **`task lint-md`**：`.markdownlint-cli2.yaml`の除外設定に従いMarkdownを検査する。
- **`task lint-md-fix`**：markdownlint-cli2で安全に修正できるMarkdownの問題を修正する。
- **`task ruleset-diff`**：GitHub上のrulesetと`.github/rulesets/main.json`の差分を表示する。認証済みの`gh`が要るため、CIでは動かさず、管理者が手元で使う。
- **`task ruleset-apply`**：`main.json`をGitHub上のrulesetへ反映する。`CONFIRM_RULESET=yes`が要る。手順は[mainブランチの保護設定](../repository/branch-protection.md)にある。
- **`task okf-check`**：OKF適合、内部リンク、孤立文書、変更した文書の責務の見直し合図（250行超の警告）、steering境界、ADRの整合（`adr-lint`）、rulesetの必須チェックとdocsの一覧とworkflowの名前の一致（`ruleset-check`）、iweのMCPサーバーの起動コマンドが選ぶ`iwe-mcp.sh`（`iwe-mcp-check`）、Taskfile文書同期候補を検査する。
  `adr-lint`は内部タスクで、`okf-check`の中でだけ動く。
  `tooling/adr-lint/test.sh`が壊れたfixtureで期待の終了コードを確かめてから、`tooling/adr-lint/adr-lint.mjs`が実際のツリーを検査する。
  失敗にするのは、ADR番号の重複、ADRファイルごとに索引の行がちょうど1つでない状態、索引と本文の状態と日付の不一致、`[ADR-NNN](…ADR-MMM-….md)`のラベルと宛先の番号の不一致である。
  状態は`Superseded by ...`を先頭の語に揃えて比べる。
  番号の欠けは警告にとどめる。
  リンクの検査は`docs/`、`README.md`、`CONTRIBUTING.md`、`.kiro/steering/`、`backend/src`のJavadocとMarkdownを対象にする。
  既知の限界として、`okf-validate.yml`が変更を判定する対象は`backend/src/**`を含まないため、バックエンドのJavadocだけを変えるPull Requestでは検査が動かない。
  ADRの番号を振り直す変更はdocsの変更を伴うため、そこで検出する。
  `ruleset-check`も内部タスクで、`tooling/ruleset-check/test.sh`と`tooling/ruleset-sync/test.sh`の後に`ruleset-check.mjs`が動く。
  `.github/rulesets/main.json`の必須チェックを、`branch-protection.md`の一覧とworkflowの`name`に突き合わせる。
  GitHub上のrulesetとの一致は検査しない。
- **`task ci-gate-check`**：`ci.yml`の`CI gate`が、`pull_request`で動く検査を取りこぼしていないかを検査する。
  `tooling/ci-gate-check/test.sh`と、PRの変更を判定する`tooling/ci-changes/test.sh`を先に実行してから、`ci-gate-check.py`が実際の`.github/workflows/`を検査する。
  `changes`ジョブは`tooling/ci-changes/changed.sh`にglobを渡して判定する。globは`paths:`と同じ書き方で、`ci.yml`の変更でも`true`になる。
  `ci.yml`にジョブを足したときは`CI gate`の`needs`にも足し、`ci.yml`から呼ばない`pull_request`の workflow は`tooling/ci-gate-check/excluded.tsv`に理由を書く。
  失敗にする状態は`test.sh`のケースが正で、この文書には写さない。
  Python 3とPyYAMLが要る（GitHubのランナーには入っている。ローカルでは`sudo apt install python3-yaml`）。CIだけで実行し、Lefthookには入れない。
- **`task skills-check`**：`skills-lock.json`と`.kiro/.kiro-plugins-installed`の記録が、`.agents/skills`の実体、`.kiro/skills`のリンク、`kiro/install.sh`が入れたファイルと合っているかを検査する。
  入れたファイルへの書き込みを止めるフックの自己検査（`.kiro/hooks/block-generated-writes.test.sh`）と`tooling/skills-check/test.sh`を先に実行してから、`tooling/skills-check/skills-check.mjs`が実際の木を検査する。
  失敗にする状態は`tooling/skills-check/test.sh`のケースが正で、この文書には写さない。
  手順の正文は[スキルの取得元と更新](../agents/skill-sources.md)である。
- **`task release-check`**：release-pleaseのmanifest、版ファイル、Gradle版、設定の一致を検証する。
- **`task agent-git-test`**：`task agent-commit`、`task agent-push`、`task agent-wait`の本体を、`tooling/agent-git/test.sh`のケースで検査する。
- **`task lint-taskfile`**：Task本体でTaskfileのYAML構文とスキーマ構造を検証する。
  `tooling/taskfile-cleanup/`のfixtureで、後片付けの定型が本体の終了コードを優先し、前段が失敗しても後段の後片付けを走らせることを検査する。
  `tooling/task-calls/task-calls.sh`は、フックとワークフローが呼ぶタスク名を検査する。
  対象は、`lefthook.yml`と`.github/workflows/`のYAMLにある`task <名前>`である。
  名前が`task --list-all`のタスクにも別名にも無いと失敗する。
  失敗にする状態は`tooling/task-calls/test.sh`のケースが正で、この文書には写さない。
  読み違える書き方と見落とす書き方は、`task-calls.sh`の`ponytail:`の注記にある。
- **`task lint-lefthook`**：`lefthook.yml`の`run`を`echo`に置き換えた設定で本物のlefthookを動かし、glob付きのjobが意図したファイルで選ばれることを検査する。
  `docs/**/*.md`のような`a/**/b`の形の`**`は0階層に一致しないため、ディレクトリ直下のファイル（`docs/index.md`、`backend/build.gradle`）を中心に少数だけ期待を置く。
  見るのはglobだけで、jobの`run`内の`{files}`の参照は見ない。期待は`tooling/lefthook-glob/test.sh`が正で、全jobには広げない。
- **`task lint-scratch-git`**：自己検査の`tooling/*/test.sh`と`.kiro/hooks/*.test.sh`が、gitを実行する前に[`tooling/scratch-git/scratch-git.sh`](../../tooling/scratch-git/scratch-git.sh)を`source`しているかを検査する（`source`しないファイルはgitを実行しない）。
  失敗にする書き方は`tooling/scratch-git/test.sh`のケースが正で、この文書には写さない。
  見落とす書き方は、`tooling/scratch-git/scratch-git-check.sh`の`ponytail:`の注記にある。
- **`task adr-check`**：判断が絡む変更にADRが伴うかを確認する。
  `frontend/`の変更のうち、テストファイル（`*.test.*`、`*.spec.*`）、`frontend/apps/*/e2e/`、`frontend/lint/fixtures/`、`frontend/apps/*/src/testing/`は対象から外す。
  警告は止めず、`ADR_STRICT=1`で失敗にし、`ADR_ACK=1`で抑制する。

Knipとjscpdの採用理由は[ADR-035](../adr/ADR-035-adopt-jscpd-and-knip-quality-gates.md)を、jscpdの閾値を4.0%にする理由は[ADR-074](../adr/ADR-074-raise-jscpd-threshold-for-class-role-boilerplate.md)を参照する。
DASTの検出ではタスクを失敗させず、起動、ログイン、CSRFの前提確認の失敗だけで失敗させる（[ADR-056](../adr/ADR-056-run-authenticated-dast-with-zap-in-ci.md)）。
OKF検査の採用理由は[ADR-036](../adr/ADR-036-adopt-okf-for-docs-knowledge-bundle.md)と[ADR-038](../adr/ADR-038-route-steering-to-docs-knowledge.md)を参照する。

## Gitフック対応

- **commit-msg（すべて）**：commitlintを実行する。
- **pre-commit（すべて）**：`scan-secrets`を実行する。
- **pre-commit（OpenAPI契約、`.spectral.yaml`、Orvalの設定、`frontend/apps/web/package.json`、生成物の変更）**：`api-lint`（`docker info`が失敗すればスキップ）と`api-client-check`を実行する。
- **pre-commit（フロントエンドの変更）**：`fe-check`を実行する。
  前項と同じgroupで順に実行し、Orvalの再生成と型検査を同時に走らせない。
- **pre-commit（Dockerfileの変更）**：`lint-docker`相当と`lint-docker-check`相当を実行する。
- **pre-commit（Composeファイルの変更）**：`lint-compose`相当を実行する。
- **pre-commit（Markdownの変更）**：変更ファイルへ`lint-md`相当を実行する。
- **pre-commit（docs、steering、Taskfile、README、`tooling/adr-lint/`、`.kiro/settings/mcp.json`、`tooling/iwe-mcp/`、`.github/rulesets/`、`.github/workflows/`、`tooling/ruleset-check/`、`tooling/ruleset-sync/`の変更）**：`okf-check`を実行する。
- **pre-commit（`.agents/skills/`、`.kiro/`、`skills-lock.json`、`tooling/skills-check/`の変更）**：`skills-check`を実行する。
- **pre-commit（Taskfile、`lefthook.yml`、GitHub Actionsワークフロー、`tooling/taskfile-cleanup/`、`tooling/task-calls/`の変更）**：`lint-taskfile`を実行する。
- **pre-commit（`tooling/agent-git/`の変更）**：`agent-git-test`を実行する。
- **pre-commit（`lefthook.yml`か`tooling/lefthook-glob/`の変更）**：`lint-lefthook`を実行する。
- **pre-commit（`tooling/*/test.sh`、`.kiro/hooks/*.test.sh`、`tooling/scratch-git/`の変更）**：`lint-scratch-git`を実行する。
- **pre-push（すべて）**：`adr-check`と`scan-secrets-all`を実行する。
- **pre-push（バックエンドのJavaまたはGradle変更）**：`be-lint`相当と`test`を実行する。
- **pre-push（フロントエンドの変更）**：`fe-test-build`を、Vitestのdot reporterとjson-summaryだけのcoverageで実行する。
- **pre-push（GitHub Actionsのワークフローかcomposite actionの変更）**：`lint-actions`相当と`lint-actions-security`相当を実行する。

Gitフックの条件とコマンドは[`lefthook.yml`](../../lefthook.yml)を正とする。
ブランチがglobに当たるファイルを変えていなくても、`lefthook run <フック> --job <job> --file <globに当たるパス> --force`で一つのjobだけを実行できる。
変更前の設定と比べるときは、`LEFTHOOK_CONFIG=<別のlefthook.yml>`を付けて同じコマンドを実行する。
Lefthookは版の表示、skipの行、`EXECUTE`の行を省き、jobの出力と要約を出す（`output`）。
成功したjobの出力も残すのは、`adr-check`の注意喚起が成功の扱いで出るためである。
すべてを出すときは、`LEFTHOOK_OUTPUT`に`meta,summary,success,failure,execution,execution_out,execution_info,skips`を渡す。
pre-pushのjobは順番に実行し、失敗した時点で止める。
バックエンドイメージのビルドはpre-pushでは実行せず、Docker CI（`hadolint.yml`）の`backend-image`がPull Requestごとに実行する。
pre-pushの「変更」は、push済みかどうかやrebaseの有無によらず、`origin/main`とのmerge-baseからの差分で判定する。
分岐後にmainへ入った変更では、触っていない領域の検査は走らない。
2回目以降のpushでも、ブランチ全体の変更に対して検査が走る。
一度backendを変えたブランチでは、以降docsだけを直してpushしてもbackendの検査が毎回走る。
直前のpushからの差分だけを見る方式は、rebase後にmainの変更を含んでしまうため採らず、この重複を受け入れる。
`origin/main`が無くmerge-baseを取れないときは、全ファイルを対象にして全検査を走らせる。

## CI対応

- **`backend-ci.yml`**：`be-lint`相当、`lint-session-sql`、`be-verify-migrations`、`be-test`、`be-openapi-check`、手動実行時の`mutation-test`を実行する。
  compose-testを止めた後に`test-deps-leftover-check`を実行する。
  契約は`be-test`が書き出し、`be-openapi-check`は`OpenApiContractTest`を再実行しない（[ADR-064](../adr/ADR-064-write-openapi-contract-from-test-task-in-ci.md)）。
  Gradle User Homeは`setup-gradle`の`cache-provider: external`にして、`actions/cache`のrestoreとsaveで扱う。
  keyはビルドファイルのhashで、restore-keysにより直近のmainのcacheを復元する。
  saveはmainのpushで、restoreが完全一致でなかったときだけ行う（[ADR-063](../adr/ADR-063-restore-gradle-cache-with-restore-keys-on-mit-caching.md)）。
- **`mutation-test.yml`**：Backend CIと同じパスの変更を含むPR、mainへのpush、手動実行で`mutation-test`を実行し、PITのレポートをartifactにする。
  PRでは`PIT_CHANGED_SINCE=origin/<base>`を渡して差分のクラスだけを、pushと手動実行では全体を変異させる。
  ジョブのtimeoutはPRで60分、pushと手動実行で180分とし、mainの実行は後のpushで取り消さない。
  スコアの閾値を持たず、PITが完走しないときだけ失敗する。
  Backend CIのtestと同じGradle cacheをread-onlyで復元する。
  必須チェックにしない（[ADR-076](../adr/ADR-076-run-mutation-tests-per-change-and-tighten-coverage-gates.md)）。
- **`frontend-ci.yml`**：`fe-verify`、`fe-route-tree-check`、`api-client-check`、`fe-doctor`を実行する。
- **`api-contract.yml`**：`api-lint`、`api-lint-rules-test`、`api-breaking`、`api-docs`を実行し、設計書をartifactにする。
  mainへのpushでは設計書をGitHub Pagesに公開する。
- **`betterleaks.yml`**：`scan-secrets-all`を実行する。PRでは`BETTERLEAKS_LOG_OPTS`にbaseからheadまでの範囲を渡す。
- **`static-analysis.yml`**：`lint-semgrep`相当、`lint-duplicates`、`lint-taskfile`、`lint-lefthook`、`agent-git-test`、`lint-scratch-git`を実行する。
  Code ScanningはSARIFの`suppressions`を読まないため、`nosemgrep`で抑止した検出をSARIFから除いてから送る。
- **`trivy.yml`**：`scan-vulns`相当を実行する。
- **`dast.yml`**：PRで`scan-dast`、週1回のscheduleと手動実行で`scan-dast-active`を実行し、Informationalを除いたSARIFをCode Scanningに送る。
- **`actionlint.yml`**：`lint-actions`相当を実行する。
- **`ci.yml`**：`pull_request`で動く検査を呼び出し、集約ジョブ`CI gate`が全ジョブの`success`を確かめる。`ci-gate-check`も実行する（[ADR-087](../adr/ADR-087-gate-required-checks-with-single-aggregate-job.md)）。
- **`zizmor.yml`**：`lint-actions-security`相当を実行する。
- **`hadolint.yml`**：`lint-docker`相当、`lint-docker-check`相当、バックエンドイメージのビルドと起動確認を実行する。
- **`compose-config.yml`**：`lint-compose`相当を実行する。
- **`otel-collector.yml`**：`otel-collector-check`を実行する。
- **`markdownlint.yml`**：`lint-md`相当を実行する。
- **`okf-validate.yml`**：`okf-check`を実行する。
- **`skills-check.yml`**：`skills-check`を実行する。
- **`release-please.yml`**：`release-check`を実行する。
- **`e2e.yml`**：`task e2e`を実行し、`test-deps-leftover-check`でcompose-testのコンテナとvolumeが残っていないことを確かめる。
  Backend CIのtestと同じGradle cacheをread-onlyで復元する。
  必須チェックにしない（[ブランチ保護](../repository/branch-protection.md)）。

各ワークフローの実装は[`.github/workflows/`](../../.github/workflows/)を正とする。
