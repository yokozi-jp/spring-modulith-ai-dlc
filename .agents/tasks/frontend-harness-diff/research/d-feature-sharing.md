# D. feature 間で機能を共有する形

対象は [report.md](../report.md) の Q-e の 3 と、それを前提にする C1（feature 境界の lint）である。
旧リポジトリで起きた次の三つの場面を、現リポジトリの構成でどう解くかを調べた。

- **場面 1**：product の form と filter が category の選択肢を使う（旧 `features/product/components/product-form.tsx` の 14 行目、`product-filter.tsx` の 8 行目）。
  pricing の form が product の選択肢を使う場面（旧 `features/pricing/hooks/use-product-options.ts` の 1 行目）も同じ形である。
- **場面 2**：category の詳細が product の一覧を出す（旧 `features/category/components/category-product-list.tsx` の 13 行目と 14 から 17 行目）。
- **場面 3**：dashboard が product と pricing の最近の一覧を使う（旧 `features/dashboard/hooks/use-recent-products.ts`、`use-recent-pricings.ts`、`components/recent-pricings-list.tsx` の 5 行目）。

照合した版は、TanStack Query 5.103.2、TanStack Router 1.170.38、Orval 8.36.0、Oxlint 1.83.0（vite-plus 0.3.3 が固定する版、`node_modules/.pnpm/vite-plus@0.3.3*/node_modules/vite-plus/package.json` の 374 行目）、eslint-plugin-boundaries 7.2.0 である。
lint の挙動は、現 `frontend/` の `package.json`、`vite.config.ts`、`tsconfig.json`、`lint/` をリポジトリ外の作業ディレクトリに複製し、`node_modules` を symlink で参照して `vp lint` と `vp test` で確かめた。
現リポジトリのファイルは変更していない。
以下で「確認済み」は文書かコードを読んで確かめた事実、または上の方法で実行して確かめた事実を指す。
「推測」はそれ以外を指す。

## 標準

feature 間の依存を定める標準の仕様は無い。
仕様に近い形で規則を明文化しているのは、方法論としての Feature-Sliced Design（FSD）と、各ライブラリの公式文書である。

**FSD の import 規則**（確認済み）：

- slice 内のファイルは、厳密に下の層にある slice だけを import できる（[Layers の Import rule on layers](https://github.com/feature-sliced/documentation/blob/075e028ba002c32b6f00d70c2dd996ff7f66a530/src/content/docs/docs/reference/layers.mdx)）。
  同じ層の別 slice（`features/aaa` から `features/bbb`）は import できない。
- slice の外からは、その slice の public API だけを参照し、内部のファイル構造を参照しない（[Slices and segments の Public API rule on slices](https://github.com/feature-sliced/documentation/blob/075e028ba002c32b6f00d70c2dd996ff7f66a530/src/content/docs/docs/reference/slices-segments.mdx)）。
  public API は通常 `index.ts` の re-export で表し、wildcard の re-export は内部を漏らすので避ける（[Public API](https://github.com/feature-sliced/documentation/blob/075e028ba002c32b6f00d70c2dd996ff7f66a530/src/content/docs/docs/reference/public-api.mdx)）。
- 同じ層の slice 間の import を **cross-import** と呼び、code smell として扱う（[Cross-imports](https://github.com/feature-sliced/documentation/blob/075e028ba002c32b6f00d70c2dd996ff7f66a530/src/content/docs/docs/guides/issues/cross-imports.mdx)）。
  理由として、所有者の不明確化、隔離とテストのしやすさの低下、変更の影響範囲の拡大、双方向依存への発展を挙げる。
- `@x` 記法は、entity A が entity B のためだけに `entities/A/@x/B.ts` という専用の public API を宣言する仕組みである（Public API）。
  同文書は、この記法を Entities 層だけで使い、cross-import を最小にするよう注記している。
  Cross-imports の文書も、`@x` を最後の手段であり推奨ではないと位置付ける。
- 生成した API client は `shared/api/openapi` のような shared 層に置き、slice 間で共有する型、cache key、query と mutation の options も shared 層に置く（[API Requests の Using Client Generators と Integrating with Server State Libraries](https://github.com/feature-sliced/documentation/blob/075e028ba002c32b6f00d70c2dd996ff7f66a530/src/content/docs/docs/guides/examples/api-requests.mdx)）。
  同文書は、API の呼出しと応答型を entities へ早まって置かないよう注意している。

**TanStack Query**（確認済み）：

- `queryOptions` は、queryKey と queryFn を一か所にまとめたまま複数の場所で共有する手段として文書化されている（[Query Options](https://github.com/TanStack/query/blob/%40tanstack%2Freact-query%405.103.2/docs/framework/react/guides/query-options.md)）。
  同じ options を `useQuery`、`useSuspenseQuery`、`useQueries`、`queryClient` の命令的な API へ渡す例を示す。
- 使う側の component で `select` などを上書きでき、component ごとの `select` 関数をよくある有用な形として紹介している（同文書）。

**TanStack Router**（確認済み）：

- loader で `queryClient.ensureQueryData(options)` を呼び、component で同じ options の `useSuspenseQuery` を使う例を示す（[External Data Loading](https://github.com/TanStack/router/blob/%40tanstack%2Freact-router%401.170.38/docs/router/guide/external-data-loading.md)）。
- component を route と別のファイルに置くときは、route を import せずに型付きの route API を得る `getRouteApi` を使う（[Code Splitting の getRouteApi](https://github.com/TanStack/router/blob/%40tanstack%2Freact-router%401.170.38/docs/router/guide/code-splitting.md)）。

**Orval**（確認済み）：

- `tags-split` は tag ごとの directory を作り、schema は一つの共有 directory に置いて tag ごとに複製しない（[output の tags-split](https://github.com/orval-labs/orval/blob/v8.36.0/docs/content/docs/reference/configuration/output.mdx)）。
- query key の取得関数は `shouldExportKeys` の既定 `true` で export される（同文書の shouldExportKeys）。
  旧リポジトリの生成物も `getListCategoryQueryOptions`、`getListProductQueryOptions` などを export している（旧 `frontend/src/api/category/category.ts` の 422 行目、`product/product.ts` の 418 行目）。
- `mutationInvalidates` は、ある mutation の成功時に別ファイル（別 tag）の query を無効化する設定を書ける（同文書の mutationInvalidates、`{ query: 'adminPets', file: './admin' }` の例）。
- Orval の文書は、生成物を複数の feature から使ってよいかを直接は述べていない。
  ただし、生成物は OpenAPI だけから作られ、key と options を export し、tag をまたぐ参照を設定で書けるので、利用者の feature 構成に依存しない共有物として設計されていると読める（推測）。

**Spring Modulith**（確認済み）：

- application module の base package が API package であり、既定では他 module からの依存を受けるのはここだけである（[Fundamentals の Named Interfaces](https://docs.spring.io/spring-modulith/reference/fundamentals.html)）。
  それ以外の package を公開するときに `@NamedInterface` を使い、`allowedDependencies` で依存先を明示できる（同文書の Explicit Application Module Dependencies）。
- 現 [docs/backend/architecture.md](../../../../docs/backend/architecture.md) の 91 行目、103 行目、118 行目がこの形を採っている。
  [architecture-tests.md](../../../../docs/backend/architecture-tests.md) の 27 行目によれば、`ApplicationModules.verify()` が module 間の循環と内部 package の参照を検出する。

## ベストプラクティス

**bulletproof-react**（commit `9506629`、確認済み）：

- feature 間の import は良い考えではないとし、application の層で feature を合成するよう勧める（[docs/project-structure.md](https://github.com/alan2207/bulletproof-react/blob/9506629ed003a561c6627735480cce4994244bb4/docs/project-structure.md)）。
  禁止の手段として `import/no-restricted-paths` の zone を feature ごとに一つずつ書く例を示し、実装の [.eslintrc.cjs](https://github.com/alan2207/bulletproof-react/blob/9506629ed003a561c6627735480cce4994244bb4/apps/react-vite/.eslintrc.cjs) の 46 から 76 行目も同じ形である。
- 同文書は、feature の barrel が Vite の tree shaking と性能を損なうので、ファイルを直接 import するよう勧める。
  feature 間で共有する API 呼出しが多いときは、API を feature の外の専用 `api` directory に置く方が実用的な場合があると述べる。
- 合成の実例は route の [discussion.tsx](https://github.com/alan2207/bulletproof-react/blob/9506629ed003a561c6627735480cce4994244bb4/apps/react-vite/src/app/routes/app/discussions/discussion.tsx) である。
  二つの feature の query options と component を import し（7 から 13 行目）、loader（15 から 36 行目）で両方の query を取得し、component（57 から 70 行目）で `DiscussionView` と `Comments` を並べる。
  route は各 feature の内部ファイルを直接 import しており、feature 同士は互いを知らない。

**FSD の解消策**（[Cross-imports](https://github.com/feature-sliced/documentation/blob/075e028ba002c32b6f00d70c2dd996ff7f66a530/src/content/docs/docs/guides/issues/cross-imports.mdx)、確認済み）：

- features と widgets の cross-import には、状況に応じて四つの戦略を選ぶ。
  - **A. slice の統合**：常に一緒に変わる二つの slice は一つにまとめる。
  - **B. domain の流れを entities へ下ろす**：entities には domain の型と logic だけを置き、UI は features に残す。
  - **C. 上の層での合成**：pages か app で組み立てる。
    render props、slot、props や context による依存注入で依存を逆転させる。
  - **D. public API だけを通す再利用**：A から C が合わないときに限り、明示的に export した Hook や component だけを使わせる。
- どこまで厳しくするかはチームとプロジェクトが決め、cross-import を入れるなら意図した設計判断として理由を記録し、定期的に見直す。

**Nx**（確認済み）：

- application は entry point、設定、feature の合成だけを持つ薄い殻にし、feature は library に置く（[Folder Structure の Keep applications thin](https://github.com/nrwl/nx/blob/ead04276f840b920ffab97eb3f809fef2baf130b/astro-docs/src/content/docs/kb/folder-structure.mdoc)）。
- library を feature、ui、data-access、util の型に分け、data-access（API client、状態管理）は data-access と util だけに依存させる（同文書の Name libraries by type）。
- shared への昇格は、二つ目の domain が必要としたときに行う（同文書の Share code deliberately）。
- 境界は project の tag と lint rule で宣言的に強制する（[Enforce Module Boundaries](https://github.com/nrwl/nx/blob/ead04276f840b920ffab97eb3f809fef2baf130b/astro-docs/src/content/docs/features/enforce-module-boundaries.mdoc)）。
  rule は project 間の循環も報告する（[enforce-module-boundaries.ts](https://github.com/nrwl/nx/blob/ead04276f840b920ffab97eb3f809fef2baf130b/packages/eslint-plugin/src/rules/enforce-module-boundaries.ts) の 183 行目の `noCircularDependencies`）。

**TkDodo（TanStack Query の保守者）**（確認済み）：

- query の抽象化には custom Hook ではなく `queryOptions` を使う（[Creating Query Abstractions](https://tkdodo.eu/blog/creating-query-abstractions)）。
  理由として、custom Hook は loader や event handler の prefetch で使えないこと、共有しているのは logic ではなく設定であること、`useSuspenseQuery` や `useQueries` に切り替えられないことを挙げる。
- 共有する options には全利用箇所で共通の設定だけを入れ、利用箇所ごとの設定は使う側で足す（同記事の Composing QueryOptions）。

**Martin Fowler**（確認済み）：

- 大きくなった system では、presentation、domain、data の層を最上位にせず、domain 単位の module を最上位にしてその内部を層に分ける（[PresentationDomainDataLayering](https://martinfowler.com/bliki/PresentationDomainDataLayering.html)）。
- 定義した code base の外で使う **published interface** と、同じ code base の中だけで使う public な interface を区別する（[PublishedInterface](https://martinfowler.com/bliki/PublishedInterface.html)）。
  同じ code base の中なら、呼出し側ごと名前を変えるなどの変更が容易だからである。

**Kent C. Dodds の colocation**（確認済み）：

- 関係するコードはできるだけ近くに置き、一緒に変わるものは近くに置く（[Colocation の The principle](https://kentcdodds.com/blog/colocation)）。
- 再利用を見込んで `utils/` へ移したコードは、使う側が消えた後も残りやすいと指摘する（同記事の "Reusable" utility files）。

## アンチパターン

- **別 feature の内部ファイルへの deep import**：FSD は、別 slice の model や logic への直接依存と内部ファイルへの deep import を、cross-import を問題として扱うべき兆候に挙げる（Cross-imports の When should cross-imports be treated as a problem?）。
  旧リポジトリの 6 箇所はすべて `@/features/<他>/hooks/...` か `@/features/<他>/types/...` への deep import である（report.md の Q-b、確認済み）。
- **双方向の依存**：同文書は、一方向の cross-import が双方向へ育ち slice を固着させると述べる。
  旧では category と product、product と pricing が feature 単位で相互参照している（report.md の Q-b）。
- **生成 Hook を包んだ feature の Hook を他の feature へ配る**：旧の `useProductList` は key を `["products", { filter, page, size }]` と手書きし（旧 `features/product/hooks/use-product-list.ts` の 20 行目）、dashboard と pricing と category がこれを import する。
  key と引数の形が product feature の内部判断に固定され、他の feature はその変更に巻き込まれる（推測）。
  生成された `getListProductQueryOptions` を直接使えば、key は生成物が決め、包む Hook も要らない。
  TkDodo の custom Hook への批判と、現 `routing-and-state.md` の 49 行目の wrapper Hook 禁止がこれに当たる。
- **wildcard の barrel で公開する**：FSD は `export *` を内部を漏らす悪い例とし（Public API）、bulletproof-react は barrel が tree shaking を損なうとする（project-structure.md）。
- **二つの feature が使うという理由だけで業務語彙のコードを共有層へ移す**：現 `architecture.md` の 136 行目は、特定 feature の業務語彙に属さないことを共有層へ移す条件にしている。
  Nx も、二つ目の domain が必要としたときに初めて shared へ昇格させる（Folder Structure）。
- **file 単位の循環検査だけに頼る**：`import/no-cycle` はファイル単位であり、feature 単位の循環を通す（report.md の Q-b）。
- **一つの `no-restricted-imports` に複数の目的を重ねる**：Oxlint の `overrides` で同じ rule を設定すると、後の override の option が前の option を置き換える（確認済み、下の「C1 の書き方」の検証 3）。
  feature 境界と、たとえば test 用の import 禁止を別々の override で書くと、両方が当たるファイルでは片方が黙って消える。
- **feature から route file を import する**：TanStack Router は、別ファイルの component から route の API を使うときに `getRouteApi` を使い route を import しない形を示している（Code Splitting）。
  route は feature を import するので、feature が route を import すると循環になる（推測。現 `architecture.md` の依存方向の図に feature から routes への矢印は無い）。

## デファクトスタンダード

| 手法 | feature 間の import | 共有の窓口 | 強制手段 |
| --- | --- | --- | --- |
| bulletproof-react | 禁止。app（route）で合成する | 無し。route は feature の内部ファイルを直接 import する | `import/no-restricted-paths` の zone を feature ごとに書く |
| FSD | 同じ層では原則禁止。features では戦略 A から D を選ぶ | slice の `index.ts`。entities に限り `@x` | Steiger（[Public API の No real protection](https://github.com/feature-sliced/documentation/blob/075e028ba002c32b6f00d70c2dd996ff7f66a530/src/content/docs/docs/reference/public-api.mdx)） |
| Nx | tag の制約で許可した型の間だけ可。feature 型は全種に依存できる | library の public API | `@nx/enforce-module-boundaries`（ESLint 版と Oxlint 版） |
| eslint-plugin-boundaries | element と policy で宣言した組合せだけ可 | `boundaries/dependencies` の selector（`entry-point` rule は非推奨） | ESLint、または Oxlint の `jsPlugins` |
| Spring Modulith（現 backend） | module の base package だけ可 | base package と `@NamedInterface` | `ApplicationModules.verify()` |

各ツールを現 frontend の Oxlint で使えるかを確かめた（すべて確認済み）。

- `import/no-restricted-paths` は Oxlint 1.83.0 に無い。
  [import rule の一覧](https://github.com/oxc-project/oxc/tree/oxlint_v1.83.0/crates/oxc_linter/src/rules/import)に該当するファイルが無い。
  bulletproof-react の書き方はそのまま移せない。
- `no-restricted-imports` は gitignore 形式の `group` と正規表現の `regex` を持つ（[no_restricted_imports.rs の文書コメント](https://github.com/oxc-project/oxc/blob/oxlint_v1.83.0/crates/oxc_linter/src/rules/eslint/no_restricted_imports.rs)）。
  `regex` は Rust の regex なので先読みと後読みが使えないと同じ文書が警告している。
  「自分以外の feature」を一つの静的な pattern では書けないのはこのためである。
- eslint-plugin-boundaries は Oxlint の `jsPlugins` で動く（[Oxlint integration](https://github.com/javierbrea/eslint-plugin-boundaries/blob/v7.2.0/packages/website/docs/guides/oxlint-integration.mdx)）。
  ただし Oxlint は `import/resolver` を既定で有効にしないため、`eslint-import-resolver-typescript` などの resolver を追加で入れる必要がある。
  設定しないと tsconfig の path alias（現リポジトリの `@/*`）の import が external と分類され、境界の検査を黙って通過すると同文書が述べる。
- Nx の Oxlint 版は Nx の project graph（project 単位）を前提にし、Oxlint の JS plugin API が semver の対象外なので experimental と明記されている（Enforce Module Boundaries の Oxlint の注意書き）。
  単一 package の中の directory を境界にする現 frontend には合わない。
- Oxlint の JS plugin は alpha である（[JS Plugins](https://oxc.rs/docs/guide/usage/linter/js-plugins.html)）。
  現 `frontend/lint/local-security.js` の 1 行目も同じ理由で `ponytail:` comment を付けている。

## 現リポジトリへの当てはめ

### 既存の規約が決めていること

現の規約は、候補 (a) の大部分をすでに決めている（確認済み）。

- ADR-032 の 66 行目と `architecture.md` の 120 行目は、一つの画面が複数のバックエンド module を使うとき、一つの feature から複数の API を組み合わせると定める。
  これは feature が別 tag の生成 API を直接使うことを許している。
- ADR-032 の 70 行目と `routing-and-state.md` の 25 行目は、生成 query options で足りるならそれを直接使うと定め、`api-client-orval.md` の 55 行目は生成物を正本にする。
- ADR-032 の 79 行目と `architecture.md` の依存方向は、複数 feature をまたぐ画面の合成を route で行うと定める。

未決なのは、別 feature のコードを import してよい場合があるかである。
ADR-032 の 78 行目と `architecture.md` の 124 行目は「別 feature の**内部**ファイル」を禁じるだけで、内部でないファイルを定義していない。
`architecture.md` の 126 行目は、二つの feature が同じ公開能力を必要としたら、所有 feature の公開境界を定めるか、独立した feature として切り出すとしている。
候補 (b) はこの前者の具体形に当たる。

backend との対応でいえば、frontend の feature が別 tag の生成 API を使うことは、backend module の公開契約（OpenAPI に出した HTTP API）を使うことに当たる。
frontend の feature の内部を他の feature へ公開する仕組みは、Spring Modulith の base package や `@NamedInterface` に当たる。
backend の module が互いの公開契約を呼ぶ必要があるのは業務の整合を保つためである。
frontend で他の feature から欲しいものの多くは data であり、data の窓口は `api/generated` が backend の契約としてすでに持っている（推測。三つの場面がすべてこれに当てはまることは下で確かめる）。

### 三つの場面を (a) で解く形

**場面 1（product の form が category の選択肢を使う）**：

- product feature が `@/api/generated/endpoints/category/category` の `getListCategoryQueryOptions` を直接使い、選択肢への変換は使う側の `select` で書く。
  TanStack Query の Query Options 文書が示す、component ごとの `select` の形である。
- 初期描画に要るなら、product の新規と編集の route の loader で同じ options を `ensureQueryData` する（`routing-and-state.md` の 25 行目）。
- 生成 key を使うので、category feature の一覧と product の form は同じ cache を共有し、要求は一つにまとまる。
  旧は category が `["categories", "root"]`（旧 `features/category/hooks/use-category-list.ts` の 12 行目）を手書きしていたため、共有は手書き key の一致に頼っていた。
- 旧の `use-category-name-resolver.ts` のような id から名前への解決も、同じ query の `select` で書ける。
  一覧の応答に category 名を含めるかは API 設計の問題であり、ここでは決めない。
- pricing の form が product の選択肢を使う場面も同じ形になる。

**場面 2（category の詳細が product の一覧を出す）**：

- category 詳細の route が、loader で `getFindCategoryByIdQueryOptions(id)` と `getListProductQueryOptions({ categoryId })` を取得し、component で category feature の詳細と product feature の一覧を並べる。
  bulletproof-react の discussion route と同じ形である。
- product の一覧を category の画面の内側の決まった位置に置きたいときは、category の component が `children` か名前付きの slot prop を受け取り、route が product の一覧を渡す。
  FSD の戦略 C の render props と slot の例に当たる。
- 一覧の状態表示（Badge）が要るのは product の component の中だけになる。
  表示名は i18n の catalog の key（`productStatus.<値>`）に置けば、どの feature からも import 無しで引ける（`i18n.md` の 40 行目の型付き catalog、b-routing-ui.md の C15）。

**場面 3（dashboard が product と pricing の最近の一覧を使う）**：

- dashboard を feature にするなら、dashboard feature が `getListProductQueryOptions` と `getListPricingQueryOptions` を件数と並び順の引数付きで直接使う。
  `architecture.md` の 120 行目のとおり、複数 module を使う画面を一つの feature として扱う形である。
- dashboard の一覧は旧でも dashboard 固有の component だった（旧 `features/dashboard/components/recent-products-list.tsx`）ので、product や pricing の component を借りる必要は無い。
- 旧で pricing から借りていた `getPricingLevelLabel` は、catalog の key（`pricingLevel.<値>`）で置き換わる。
- dashboard を feature にせず、route だけで product と pricing の component を並べる形もある。
  画面に固有の component が無いなら、こちらの方がファイルは少ない。

三つの場面とも、他の feature のファイルを import せずに書ける。
旧の feature 間 import の行き先は、生成 Hook を包んだ Hook が 3 種類（`useProductList`、`useCategoryOptions`、`usePricingList`）と、区分値の表示が 2 種類（`product-status`、`pricing-level`）だけである（report.md の Q-b の表、旧の `grep` で確認済み）。
component を別 feature から import した箇所は無く、旧の `CategoryProductList` は category feature が product の Hook を使って自前で描いた一覧である。
Hook は生成 query options に、区分値の表示名は catalog に、別 feature の data を描く自前の一覧は route の合成に置き換わる。

### 案の比較

| 案 | 中身 | 既存規約との整合 | lint | 弱点 |
| --- | --- | --- | --- | --- |
| (a) feature 間の import を例外なく禁じる | data は生成 query options を直接使い、画面は route で合成し、埋め込みは `children` か slot prop で渡す | ADR-032 の 66、70、79 行目どおり。78 行目の「内部」を「すべて」に締める | 例外の無い一つの rule で済む | 同じ `select` の変換や Badge の variant の対応表が二つの feature に重複し得る。jscpd（ADR-035）が大きな重複を検出する |
| (b) 所有 feature に公開ファイルを一つ決める | 例：`features/category/public.ts` だけを他の feature から import できる | `architecture.md` の 126 行目の前者に当たる。barrel を作らない 128 行目とは、公開ファイルを barrel にしない限り両立する | 許可する path を一つ足す（下の検証 4） | 公開物が増えると feature 単位の循環が再び起き得る。窓口の中身を何にするか（Hook、component、純粋関数）の規則が別に要る |
| FSD の戦略 A（統合） | 常に一緒に変わる二つの feature を一つにする | `architecture.md` の 126 行目の後者の逆向き。feature 名を業務語彙にする 122 行目と合わせて判断する | 変更無し | 業務能力の単位が粗くなる |
| FSD の戦略 B（entities 層） | domain の型と logic を feature より下の層に置く | ADR-032 が完全な FSD を不採用にしている。型は `api/generated/models` がすでに共有している | 層を一つ足す | 今の三つの場面には、共有すべき frontend 固有の domain logic が無い |
| 共有層へ移す | `lib` か `components` に置く | `architecture.md` の 136 行目の「業務語彙に属さない」に反する | 変更無し | 所有者が消える |

### C1 の書き方と検証の結果

現 `vite.config.ts` の `jsPlugins`（90 から 95 行目）、rule（153 行目）、`overrides`（169 行目以降）に足す形を確かめた。
現設定では `import/no-relative-parent-imports` が `../` を禁じている（c-lint-agent.md の表）。
そのため、他の feature へ届く import は必ず `@/features/<名前>/...` の alias になり、rule は alias だけを見れば足りる。
自分の feature の中でも、親 directory を経由するファイルは `@/features/<自分>/...` と書くことになり、rule はこれを許す必要がある。

**(a) の推奨形**：自分以外の feature への import を禁じる jsPlugin を `local-security.js` と同じ形で置く。

```js
// frontend/lint/feature-boundaries.js
// ponytail: alias の `@/features/<name>` だけを見る。`../` は import/no-relative-parent-imports が禁じている。
const featurePattern = /^(?:.*\/)?src\/features\/(?<name>[^/]+)\//u;
const importPattern = /^@\/features\/(?<name>[^/]+)/u;

function featureName(pattern, path) {
  return String(pattern.exec(String(path).replaceAll("\\", "/"))?.groups?.name ?? "");
}

export const noCrossFeatureImport = {
  meta: {
    messages: {
      forbidden: "features/{{source}} must not import features/{{target}}. Compose them in a route.",
    },
    schema: [],
  },
  create(context) {
    const source = featureName(featurePattern, context.filename);
    if (source === "") {
      return {};
    }
    const check = (node) => {
      const target = featureName(importPattern, node.source?.value ?? "");
      if (target !== "" && target !== source) {
        context.report({ node: node.source, messageId: "forbidden", data: { source, target } });
      }
    };
    return {
      ImportDeclaration: check,
      ExportNamedDeclaration: check,
      ExportAllDeclaration: check,
      ImportExpression: check,
    };
  },
};

const featureBoundaries = {
  meta: { name: "feature-boundaries" },
  rules: { "no-cross-feature-import": noCrossFeatureImport },
};

export default featureBoundaries;
```

共有層と feature から逆向きに参照するのを禁じる部分は、静的な `no-restricted-imports` の override で書ける。

```ts
// frontend/vite.config.ts の lint に足す差分
jsPlugins: [
  // 既存の 4 個に続けて
  { name: "feature-boundaries", specifier: "./lint/feature-boundaries.js" },
],
rules: {
  // 既存の rule に続けて
  "feature-boundaries/no-cross-feature-import": "error",
},
overrides: [
  {
    files: ["src/{api,components,lib,i18n}/**"],
    rules: {
      "no-restricted-imports": [
        "error",
        { patterns: [{ group: ["@/features/**", "@/routes/**", "@/routeTree.gen"], message: "Shared code must not depend on features or routes." }] },
      ],
    },
  },
  {
    files: ["src/features/**"],
    rules: {
      "no-restricted-imports": [
        "error",
        { patterns: [{ group: ["@/routes/**", "@/routeTree.gen"], message: "Use getRouteApi instead of importing a route." }] },
      ],
    },
  },
  // 既存の override
],
```

検証の結果は次のとおりである（すべて確認済み）。

1. jsPlugin は `context.filename` から自分の feature を判定できた。
   別 feature への静的 import、`export ... from`、`import()`、空の `export type {} from`、feature root への import（`@/features/category`）をすべて報告した。
   自分の feature への alias import、`@/api/generated/...`、`src/routes/` からの feature の import は報告しなかった。
2. jsPlugin のファイルは現設定の lint（`lint/**` の override を含む）を error 0 件で通った。
   `local-security.test.js` と同じ形の unit test（`create({ filename, report })` に偽の node を渡す）2 件が通った。
3. `no-restricted-imports` の override は、`src/lib/` から `@/features/...` への import と、`src/features/` から `@/routes/...` と `@/routeTree.gen` への import を報告した。
   同じファイルに二つの override が当たると、後の override の option だけが効いた。
4. 静的な代替として、feature ごとに override を一つ書く形も動いた。
   `group: ["@/features/**", "!@/features/product/**"]` を `src/features/product/**` に当てると、別 feature への import だけを報告した。
   その前に `src/features/**` へ `group: ["@/features/**"]` の override を置くと、override を書き忘れた feature は自分の alias import まで報告され、閉じた側に倒れた。
   ただし空の `export type {} from` は報告しなかった。
   最初に試した `["@/features/*", "!@/features/product"]` は、別 feature の `@/features/category/category-options` を報告しなかった。
   一階層の `*` が下位の path に当たらないためと考えられる（推測）。
5. (b) を採る場合、静的な形なら `"!@/features/*/public"` を group に足すと、他の feature の `public.ts` だけが通った。
   jsPlugin なら、`target !== source` の条件に「path が `@/features/<target>/public` でない」を足せば同じになる（推測、未実行）。

jsPlugin と静的な override の比較は次のとおりである。

| 観点 | jsPlugin | feature ごとの静的 override |
| --- | --- | --- |
| 新しいコード | 約 40 行と test | 無し |
| feature の追加時 | 設定の変更が要らない | override を一つ足す。足し忘れは catch-all で失敗する |
| `no-restricted-imports` の衝突 | feature 境界に使わないので、他の目的に空けておける | feature のファイルに当たる他の override と option を共有する必要がある |
| 依存の安定性 | Oxlint の JS plugin API が alpha | 組み込みの rule だけで済む |

### 推奨案

1. **(a) を採る。**
   feature から別 feature のファイルを import することを例外なく禁じる。
   他の feature の data は `api/generated` の生成 query options を直接使い、変換は使う側の `select` で書く。
   画面の合成は route で行い、他の feature の component を自分の画面の内側に置くときは `children` か slot prop で route から受け取る。
   区分値の表示名は catalog の key で引く。
   根拠：bulletproof-react の project-structure.md と discussion route、FSD の Cross-imports の戦略 C、TanStack Query の Query Options、ADR-032 の 66、70、79 行目。
   三つの場面がすべてこの形で書けることは上で確かめた。
2. **公開ファイル（(b)）は今は作らない。**
   (a) で書けない場面が実際に出たときに、`architecture.md` の 126 行目に従って、公開境界を定めるか feature を切り出すかを決める。
   根拠：FSD は cross-import を入れるなら意図した判断として記録するよう求めている（Cross-imports の How strict you are）。
   Nx は二つ目の利用者が出てから共有へ昇格させる（Folder Structure）。
   lint は検証 5 のとおり、後から許可 path を一つ足す変更で済む。
3. **C1 は jsPlugin と二つの静的 override で書く。**
   feature を足すたびに設定を変えずに済み、`no-restricted-imports` を他の目的に空けておけるためである（検証 1 から 3）。
   jsPlugin は `local-security.js` と同じ置き場所、登録方法、test の形にする（現 `vite.config.ts` の 92 行目と 153 行目、`lint/local-security.test.js`）。
   報告文は `local-security.js` の 4 行目と同じく、代わりにすべきこと（route で合成する、`getRouteApi` を使う）を書く。
   旧リポジトリでは AI エージェントが feature 間 import を作ったので、報告文が直し方を示すことに意味がある（推測）。
4. **feature から route への import も禁じる。**
   根拠：TanStack Router の Code Splitting の `getRouteApi`、`architecture.md` の依存方向の図。
5. **docs を合わせて直す。**
   ADR-032 の 78 行目と `architecture.md` の 124 行目の「別 feature の内部ファイル」を「別 feature のファイル」に改める。
   `architecture.md` の 171 から 173 行目の「lint rule を追加する」を、追加した rule の説明に置き換える。
   ADR-032 の Negative の 106 行目も同様である。
   `routing-and-state.md` の 25 行目付近に、別 tag の生成 query options を他の feature から使ってよいことを一文足す。

### 利用者が決める論点

1. (a) を採るか、(b) を最初から許すか。
   (b) を許すなら、公開ファイルの名前（`public.ts` など）と、置いてよい中身（Hook、component、純粋関数のどれか）を決める必要がある。
2. C1 を jsPlugin で書くか、feature ごとの静的 override で書くか。
   違いは上の比較の表のとおりである。
3. dashboard を feature にするか、route だけで合成するか。
   画面に固有の component があるかで決まる。
4. 一覧の応答に関連 resource の表示名（product の一覧に category 名など）を含めるか。
   含めれば場面 1 の id から名前への解決が frontend から消えるが、`docs/web-api/` の応答設計の判断になる。
5. ADR の扱い。
   C1 の追加と「内部」の定義の変更は ADR-032 の決定を変えるので、ADR-032（Proposed）を改訂するか新しい ADR を起こすかを、実装と同じ変更で `docs/adr/conventions.md` に従って決める。
