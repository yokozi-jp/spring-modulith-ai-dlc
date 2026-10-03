# グループ C の調査（Lint、型、エージェント）

[report.md](../report.md) の C9、C10、C11、C12、C14、C17 について、標準、ベストプラクティス、アンチパターン、デファクトスタンダードを調べた結果である。
「確認済み」はファイル、一次情報、実行結果で確かめた事実、「推測」と「要検証」はそれ以外を指す。

## 調査の前提

対象の版は `frontend/package.json` と `frontend/pnpm-workspace.yaml` に合わせた。

| 対象 | 版 |
| --- | --- |
| vite-plus | 0.3.3（依存する Oxlint は 1.83.0、oxlint-tsgolint は 7.0.2001） |
| TypeScript | 7.0.2 |
| @tanstack/react-query | 5.103.2 |
| @tanstack/react-router | 1.170.38（router-core 1.171.32） |
| @tanstack/react-form | 1.33.5 |
| zod | 4.6.5 |
| orval | 8.36.0 |
| eslint-plugin-better-tailwindcss | 4.7.0 |
| tailwindcss | 4.3.3 |

lint の挙動は実行して確かめた。
リポジトリの外（`/projects/sandbox/.c-scratch`）に `frontend/` の追跡ファイルを複製し、`node_modules` をリンクして、検査用のファイルと設定の変更を加えて `vp lint` と `tsc` を走らせた。
複製は調査後に削除した。
リポジトリ内のファイルは変更していない。

規則のカテゴリは、vite-plus が使う Oxlint 1.83.0 の `oxlint --rules` の出力で確かめた。

## 推奨案の要約

- **C11**：`no-void` を `allowAsStatement: true` に調整し、`void` は reject しない Promise か、内部で失敗を処理した Promise にだけ使う。
  async 関数を event handler に直接渡さない。
- **C12**：生成型には条件付き spread で値を渡す。
  そのために、現設定で互いに矛盾している `no-undefined` を off にする。
  Orval を入れるときは `override.zod.exactOptional: true` を検討する。
- **C14**：`radix`、`no-new-wrappers`、`consistent-type-definitions`、親ディレクトリへの相対 import の禁止は、カテゴリ指定ですでに有効である。
  足すのは Tailwind の規則一つだけでよく、保守者は `enforce-shorthand-classes` より `enforce-canonical-classes` を推奨構成に入れている。
- **C9、C10、C17**：生成物（`routeTree.gen.ts` と将来の `src/api/generated/**`）への書込みを PreToolUse hook で拒否し、最終の検出は CI の再生成差分に置く。
  「API が無いときは止まって確認する」は runbook に書く。
  Stop と SessionStart の hook は今は入れない。

## C11. floating promise の扱い

### 標準

ECMAScript に、処理されない Promise を禁じる規範は無い。
ブラウザは、ハンドラの無い reject を `unhandledrejection` イベントとして通知する（[MDN: unhandledrejection](https://developer.mozilla.org/en-US/docs/Web/API/Window/unhandledrejection_event)）。
`void` 演算子は式を評価して `undefined` を返すだけであり、Promise の reject を処理しない（[ESLint: no-void](https://eslint.org/docs/latest/rules/no-void)）。

各ライブラリが返す Promise の契約は、ソースで確かめた（確認済み）。

- TanStack Query の `invalidateQueries` と `refetchQueries` は、`throwOnError` を指定しない限り個々の query の失敗で reject しない（[query-core の queryClient.ts](https://github.com/TanStack/query/blob/%40tanstack/react-query%405.103.2/packages/query-core/src/queryClient.ts)）。
  `useQuery` が返す `refetch` も、同じ条件で `catch(noop)` を付けている（[queryObserver.ts](https://github.com/TanStack/query/blob/%40tanstack/react-query%405.103.2/packages/query-core/src/queryObserver.ts)）。
- TanStack Form の `handleSubmit()` は `Promise<void>` を返し、`onSubmit` が投げた例外を catch したあと再び投げる（[form-core の FormApi.ts](https://github.com/TanStack/form/blob/%40tanstack/react-form%401.33.5/packages/form-core/src/FormApi.ts)）。
- TanStack Router の `navigate` は async 関数である（[router-core の router.ts](https://github.com/TanStack/router/blob/%40tanstack/router-core%401.171.32/packages/router-core/src/router.ts)）。
  どの条件で reject するかは確かめていない（要検証）。

### ベストプラクティス

typescript-eslint の [no-floating-promises](https://typescript-eslint.io/rules/no-floating-promises/) は、Promise の文を `await`、`return`、二引数の `.then()`、`.catch()`、`void` のいずれかで扱うよう求める。
`ignoreVoid` は既定で `true` である。
同じ文書は、`void` は reject を処理せず無視するだけであり、無効化コメントと同じ効果だと警告している。
async IIFE は既定（`ignoreIIFE: false`）では対象になる。

[no-misused-promises](https://typescript-eslint.io/rules/no-misused-promises/) の `checksVoidReturn` は、void を期待する位置に Promise を返す関数を渡すことを検出する。
JSX 属性だけを外す `checksVoidReturn: { attributes: false }` の設定例は、公式文書に載っている。
[strict-void-return](https://typescript-eslint.io/rules/strict-void-return/) はその上位集合であり、Promise に限らず値を返す関数を void 位置へ渡すことを禁じる。
同文書の推奨形は、callback の中で async IIFE を呼び、その場で `.catch` を付ける書き方である。

ESLint の [no-void](https://eslint.org/docs/latest/rules/no-void) は `allowAsStatement` を持ち、`true` なら文としての `void` を許し、式の位置（代入や return）の `void` だけを報告する。

TanStack Query の文書は、mutation 後の無効化を `onSuccess: async () => { await queryClient.invalidateQueries(...) }` の形で示す（[Invalidations from Mutations](https://tanstack.com/query/v5/docs/framework/react/guides/invalidations-from-mutations)）。
`onSuccess` から Promise を返すと、それが解決するまで `isPending` が `true` のまま保たれる、と同じ文書にある。
mutation の callback が返す Promise は、次の callback の前に待たれる（[Mutations](https://tanstack.com/query/v5/docs/framework/react/guides/mutations)）。

TanStack Router の文書は、利用者が操作する link や button には `useNavigate` より `Link` を使うよう勧め、`navigate` は非同期処理の成功後のような副作用の遷移に限っている（[Navigation](https://tanstack.com/router/v1/docs/framework/react/guide/navigation)）。
文書の例は `navigate(...)` を `await` も `void` もせずに呼んでいる。

TanStack Form の quick start は、`onSubmit` の中で `form.handleSubmit()` を処理せずに呼ぶ（[Quick Start](https://tanstack.com/form/v1/docs/framework/react/quick-start)）。

### アンチパターン

- `void` を、reject しうる Promise に付ける。
  typescript-eslint の警告どおり、reject は未処理のまま残る。
  `onSubmit` が `mutateAsync` の失敗を投げる場合の `void form.handleSubmit()` がこれに当たる（ソースからの推論）。
- async 関数を `onClick` に直接渡し、その検出を消すために `no-misused-promises` と `strict-void-return` を丸ごと off にする。
- `.catch(console.error)` で握りつぶす。
  現設定では `promise/prefer-await-to-then`、`promise/prefer-await-to-callbacks`、`no-console` に違反する（確認済み、次節の表）。
- 式の位置の `void`（`onClick={() => void refetch()}`）。
  `allowAsStatement: true` でも違反のまま残る（確認済み）。
- 画面遷移だけの button を `navigate` で書く。
  `Link` で書けば Promise 自体が出てこない（TanStack Router の推奨）。

### デファクトスタンダード

- typescript-eslint の `recommended-type-checked` は、`no-floating-promises` と `no-misused-promises` を既定の option で有効にする（各規則の文書の冒頭）。
- create-t3-app は `no-misused-promises` に `checksVoidReturn: { attributes: false }` を設定している（[cli/template/extras/config/_eslint.base.js](https://github.com/t3-oss/create-t3-app/blob/main/cli/template/extras/config/_eslint.base.js)）。
- oxlint-config-raccoon は `no-void: ["error", { allowAsStatement: true }]` を設定している（[src/base.ts](https://github.com/sapegin/oxlint-config-raccoon/blob/main/src/base.ts)）。
  旧リポジトリの ADR-0010 はこれを出典にしている。
- 旧リポジトリは、`onSuccess` の中で `void queryClient.invalidateQueries(...)` と `void navigate(...)` を並べ、JSX では `() => { void refetch(); }` のブロック文にしている（`frontend/src/features/category/hooks/use-create-category.ts` など）。

### 現リポジトリへの当てはめ

現設定では次の結果になった（確認済み）。
`no-void` は `restriction`、`no-floating-promises` は `correctness`、`no-misused-promises` と `strict-void-return` は `pedantic`、`promise/prefer-await-to-then` は `style` に属し、どれもカテゴリ指定で error になっている。

| 書き方 | 現設定 | `no-void` を `allowAsStatement: true` にした場合 |
| --- | --- | --- |
| 関数本体の `void navigate(...)` | `no-void` | 通る |
| `navigate(...)` を処理せずに呼ぶ | `no-floating-promises` | 同じ |
| `onClick={asyncHandler}` | `no-misused-promises`、`strict-void-return` | 同じ |
| `.catch((error) => { console.error(error); })` | `prefer-await-to-then`、`prefer-await-to-callbacks`、`no-console` | 同じ |
| `onClick={() => void navigate(...)}` | `no-void` | `no-void` |
| `onClick={() => { void navigate(...); }}` | `no-void` | 通る |
| `onSuccess: async () => { await invalidateQueries(...); await navigate(...); }` | 通る | 通る |

現設定のままでは、同期の event handler から Promise を返す関数（`refetch` など）を呼ぶ書き方が一つも通らない。
`void` は `no-void` が、`.catch` は promise plugin が、async handler は `no-misused-promises` と `strict-void-return` が、async IIFE は `no-floating-promises` が止めるからである（IIFE は文書の既定値からの推論）。
これは ADR-034 が個別調整を認める「相互に矛盾する規則」に当たると考える。

推奨案は次のとおりである。

1. `vite.config.ts` に `"no-void": ["error", { allowAsStatement: true }]` を足し、理由をコメントに書く。
   旧リポジトリと同じ設定である。
2. `no-misused-promises` と `strict-void-return` は既定のまま残し、async 関数を JSX 属性に渡さない。
   create-t3-app の `attributes: false` は採らない。
   `strict-void-return` も同じ箇所を報告するため、二つの規則を緩める必要が生じ、緩める範囲が広がるからである（確認済み）。
3. `void` を付けてよいのは、reject しない Promise（`invalidateQueries`、`refetchQueries`、`refetch`）と、内部で失敗を処理した Promise に限る、と docs に書く。
4. mutation は `mutate` と `onSuccess`、`onError` で書き、`onSuccess` では TanStack Query の文書どおり `await` で無効化してから遷移する。
5. 画面遷移だけの操作は `Link` で書く。
6. `void form.handleSubmit()` は、`onSubmit` が例外を投げない（`mutate` を使うか、内部で catch する）ことを条件にする。

既存の docs との関係は次のとおりである。

- ADR-034 の Neutral は「Lint 設定と例外の一覧は `docs/tooling/lint-and-test.md` に記述」とするが、現状の同文書に例外の一覧は無い（grep で確認済み）。
  例外を一つ足すなら、一覧の置き場所も同時に決める必要がある。
- `routing-and-state.md` は mutation 後の書き方を決めていない（report.md の C3）。
  上の 3 から 6 は、C3 と同じ節に置くのが自然である（推測）。

利用者が決める論点は次の三つである。

- `void` を許す範囲を、上の 3 のように docs の規約で絞るか、lint に任せて文としての `void` を一律に許すか。
- `handleSubmit` の扱いを、form の共通の書き方として `form-validation.md` に置くか。
- TanStack Router の `navigate` が reject する条件を確かめてから `void` の対象に含めるか（要検証）。

## C12. `exactOptionalPropertyTypes` と生成型

### 標準

TypeScript の [exactOptionalPropertyTypes](https://www.typescriptlang.org/tsconfig/#exactOptionalPropertyTypes) は、`?` 付きのプロパティを書いたとおりに解釈させ、「プロパティが無い」と「値が `undefined`」を区別する。
`in` 演算子や `Object.keys`、object spread は二つを区別するため、型でも区別する、というのが導入の理由である（[TypeScript 4.4 リリースノート](https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-4.html#exact-optional-property-types)）。
同じリリースノートによれば、この flag は `strict` 系に含まれず明示的に有効にする必要があり、DefinitelyTyped などの型定義も対応を進めている。

Zod 4 は、この flag に合わせた `.exactOptional()` を持つ（[Zod API: Optionals](https://zod.dev/api#optionals)）。
`.optional()` の推論型は `{ x?: T | undefined }`、`.exactOptional()` は `{ x?: T }` である。

### ベストプラクティス

TypeScript のエラーメッセージ自体が、代入先の型に `undefined` を足すよう提案する（TS2375、上記リリースノートの例）。
自分で持つ型で、`undefined` の受け渡しを許したい場合はこれに従う。
DefinitelyTyped の `@types/react` は、DOM 属性を `className?: string | undefined` のように宣言している（[types/react/index.d.ts](https://github.com/DefinitelyTyped/DefinitelyTyped/blob/master/types/react/index.d.ts)、手元の版でも確認済み）。

自分で変えられない型（生成型）に渡すときは、値が無いときにプロパティ自体を作らない。
条件付き spread（`...(x !== undefined && { x })` または `...(x === undefined ? {} : { x })`）が、そのための標準の構文の組合せである。
どちらも `tsc` と現設定の lint で報告されない（`no-undefined` を除く、後述、確認済み）。

Orval 8.36 は、Zod の生成に `override.zod.exactOptional` を持つ（[Orval: output 設定](https://github.com/orval-labs/orval/blob/v8.36.0/docs/content/docs/reference/configuration/output.mdx)）。
有効にすると optional なプロパティを `.exactOptional()` で生成し、`exactOptionalPropertyTypes` の下で `{ x?: T }` と推論させる。
既定は `false` である。
Orval は `tsconfig.json` の `exactOptionalPropertyTypes` も読み、生成コードの一部（mutator の config など）をそれに合わせる（同文書の `tsconfig` 節）。

### アンチパターン

- optional なプロパティへ `undefined` を明示的に代入する。
  この flag が検出する対象そのものである。
- エラーを避けるために、同じ JSX を if で二つ書き分ける。
  旧リポジトリの `frontend-lint-fix-guide.md` が、片方だけ直し忘れる危険を理由に禁じている。
- `as` で型を合わせる、生成型を手で書き換える、flag を無効にする。
  ADR-031 は、flag 全体の無効化を別の ADR の判断としている。
- 文字列の truthy 判定で spread する（`...(x && { x })`）。
  空文字列が消える。
  現設定では `typescript/strict-boolean-expressions` が報告する（確認済み）。

### デファクトスタンダード

- `@types/react` の `| undefined` 付きの宣言（前述）。
- Orval 8.36 は `exactOptionalPropertyTypes` 向けの生成を opt-in の option で持ち、TanStack Query 向けの生成でも flag を前提にした回避を入れている（`@orval/query` の生成器のソースのコメントが issue #4163 を参照している、`node_modules` で確認済み）。
- 旧リポジトリは `...(input.parentCategoryId !== undefined && { parentCategoryId: input.parentCategoryId })` の形を 5 箇所で使っている（`frontend/src/features/category/hooks/use-create-category.ts` など）。
  旧の Orval 生成型は optional を `parentCategoryId?: string` と出力している（`frontend/src/api/openAPIDefinition.schemas.ts`）。

### 現リポジトリへの当てはめ

現設定には、旧リポジトリに無い衝突がある（確認済み）。

- `eslint/no-undefined`（`restriction`）が `x !== undefined` を報告する。
  旧リポジトリはこの規則を off にしていた。
- `unicorn/no-typeof-undefined`（`pedantic`）が `typeof x !== "undefined"` を報告し、`undefined` との直接比較に直すよう求める。
- `x != null` は `eqeqeq`、`no-eq-null`、`unicorn/no-null` が、`void 0` は `no-void` が報告する。

この組合せで通る `undefined` の判定は、`typeof x === "string"` のように具体的な型名と比べる形だけだった。
`noUncheckedIndexedAccess` も有効なので、添字アクセスの結果の判定でも同じ制約に当たる（推測）。
ESLint の [no-undefined](https://eslint.org/docs/latest/rules/no-undefined) は、`undefined` の上書きや shadowing を防ぐ代替として `no-global-assign` と `no-shadow-restricted-names` を挙げている。
この二つは Oxlint の `correctness` に属し、現設定で有効である（確認済み）。

さらに、`form-validation.md` は Orval が生成する request の Zod schema を送信時の検証に使うと定めている。
Zod の `.optional()` の出力型 `{ parentId?: string | undefined }` は、Orval の生成型 `{ parentId?: string }` に代入できない（`tsc` で確認済み、TS2375）。
`.exactOptional()` の出力型なら代入できる（同）。

推奨案は次のとおりである。

1. `no-undefined` を off にし、`unicorn/no-typeof-undefined` を残す。
   比較は `x === undefined` に統一される。
   ADR-034 の「相互に矛盾する規則」の調整に当たる。
2. 生成型へ渡すときは条件付き spread を使う。
   `&&` 形と三項演算子形のどちらかに揃える。
   旧リポジトリの実績がある `&&` 形でよいと考える。
3. 自分で定義する component の props で、親から `undefined` を素通しする設計なら、`prop?: T | undefined` と宣言する。
   `@types/react` と同じ形である。
4. Orval を導入するときは `override.zod.exactOptional: true` を有効にし、生成 Zod の出力をそのまま生成 request 型へ渡せるか確かめる（要検証）。
   TanStack Form の既定値の型との組合せも同時に確かめる。

置き場所は、report.md の Q-e 11（lint 修正の手引きを docs に置くか）とは独立に決められる。
上の 2 と 3 は数行で済むため、`api-client-orval.md` か `form-validation.md` に一項目足す形で足りる（推測）。

利用者が決める論点は次の三つである。

- `no-undefined` を off にするか、残して `typeof x === "string"` の形を規約にするか。
  後者は値の型ごとに書き方が変わる。
- 条件付き spread を `&&` 形と三項演算子形のどちらに揃えるか。
- `override.zod.exactOptional` を、最初の業務 API で Orval 設定を作るときの初期値にするか。

## C14. 追加の lint 規則

### 標準

- **parseInt の基数**：ES5 で先頭 `0` の八進数解釈は無くなったが、`0x` の十六進数は今も自動判定される（[ESLint: radix](https://eslint.org/docs/latest/rules/radix)）。
- **プリミティブのラッパー**：`new String()` などは object を作り、プリミティブとは異なる振る舞いをする（[ESLint: no-new-wrappers](https://eslint.org/docs/latest/rules/no-new-wrappers)）。
- **type と interface**：TypeScript の handbook は、ほとんどの場合は好みで選んでよく、目安としては type の機能が要るまで interface を使う、としている（[Everyday Types](https://www.typescriptlang.org/docs/handbook/2/everyday-types.html#differences-between-type-aliases-and-interfaces)）。
  TypeScript の性能 wiki は、型を合成するときは交差型より `interface` の `extends` を勧める（[Performance](https://github.com/microsoft/TypeScript/wiki/Performance#preferring-interfaces-over-intersections)）。
- **size utility**：Tailwind CSS は幅と高さを同時に指定する `size-*` を持つ（[Tailwind: width](https://tailwindcss.com/docs/width)）。

### ベストプラクティス

- ESLint の `radix` は、`"always"` と `"as-needed"` の option を廃止し、常に基数を要求する挙動に一本化した（[ESLint: radix](https://eslint.org/docs/latest/rules/radix)）。
- typescript-eslint の [consistent-type-definitions](https://typescript-eslint.io/rules/consistent-type-definitions/) は既定が `interface` であり、`stylistic` 構成に含まれる。
  一つの書き方に揃えることの利益が、どちらの書き方の利益よりも大きい、と同文書は述べる。
- [Google TypeScript Style Guide](https://google.github.io/styleguide/tsguide.html#prefer-interfaces) は、object の型には type literal の alias ではなく interface を使う、と定める。
- better-tailwindcss の保守者は、Tailwind 4 向けの推奨構成に `enforce-canonical-classes` を入れ、`enforce-shorthand-classes` を入れていない（[README の rule 表](https://github.com/schoero/eslint-plugin-better-tailwindcss/blob/v4.7.0/README.md)）。
- `enforce-canonical-classes` は Tailwind CSS 4.1.15 以降の canonical 提案を実装し、shorthand への集約（`collapse`、既定 `true`）も含む。
  二つを同時に有効にすると重複して報告するので、片方だけを使うよう両方の文書が勧めている（[enforce-canonical-classes](https://github.com/schoero/eslint-plugin-better-tailwindcss/blob/v4.7.0/docs/rules/enforce-canonical-classes.md)、[enforce-shorthand-classes](https://github.com/schoero/eslint-plugin-better-tailwindcss/blob/v4.7.0/docs/rules/enforce-shorthand-classes.md)）。
  canonical 側には約 1 秒の起動コストがある、と同文書にある。
- bulletproof-react は、`../../../` のような import を避けるため絶対 import を常に設定して使うよう勧める（[docs/project-standards.md](https://github.com/alan2207/bulletproof-react/blob/master/docs/project-standards.md)）。

### アンチパターン

- カテゴリ指定ですでに有効な規則を、個別にも列挙する。
  旧リポジトリの ADR-0010 は「カテゴリを error にしても既定で無効な規則は有効にならない」と書いたが、Oxlint 1.83 では成り立たない（下の表、確認済み）。
  重複した列挙は、ADR-034 の「カテゴリで有効にし例外だけを列挙する」方針と食い違う。
- 専用の規則がある制約を、`no-restricted-imports` の正規表現で書く。
  親ディレクトリへの相対 import には `import/no-relative-parent-imports` がある。
- `enforce-shorthand-classes` と `enforce-canonical-classes` を両方有効にする（保守者の文書）。

### デファクトスタンダード

- oxlint-config-raccoon は `typescript/consistent-type-definitions` を有効にしている（[src/typescript.ts](https://github.com/sapegin/oxlint-config-raccoon/blob/main/src/typescript.ts)）。
- shadcn は `components.json` の `aliases` で `@/components`、`@/lib/utils` などの alias を前提にする。
  現リポジトリの `frontend/components.json` も同じである（確認済み）。
- bulletproof-react は絶対 import に加え、`import/no-restricted-paths` で層の境界を、`import/no-cycle` で循環を検査している（[apps/react-vite/.eslintrc.cjs](https://github.com/alan2207/bulletproof-react/blob/master/apps/react-vite/.eslintrc.cjs)）。

### 現リポジトリへの当てはめ

各規則のカテゴリと現設定での状態は次のとおりである（確認済み）。
検査用ファイルに違反を書き、`vp lint` で報告されるかを見た。

| 規則 | Oxlint のカテゴリ | 現設定での状態 |
| --- | --- | --- |
| `eslint/radix` | pedantic | 有効。`unicorn/prefer-number-properties` も同じ箇所を報告する |
| `eslint/no-new-wrappers` | pedantic | 有効。`unicorn/new-for-builtins` も同じ箇所を報告する |
| `typescript/consistent-type-definitions` | style | 有効。既定の `interface` で `type Props = { ... }` を報告する |
| `import/no-relative-parent-imports` | restriction | 有効。`../../../lib/...` を報告する |
| `eslint/no-restricted-imports` | restriction | 有効だが、option が無いので何も報告しない |
| `unicorn/filename-case` | style | 有効。`ProbeCard.tsx` を kebab-case でないと報告する |
| `better-tailwindcss/enforce-shorthand-classes` | jsPlugin（カテゴリ外） | 無効。名前で有効にすると `h-10 w-10` を `size-10` へ直すよう報告する |
| `better-tailwindcss/enforce-canonical-classes` | jsPlugin（カテゴリ外） | 無効。名前で有効にすると `h-10 w-10` と `px-4 py-4` に加え、既存の `text-sm leading-6` も報告する |

Oxlint の設定文書は、カテゴリを同じ意図の規則の集まりとして有効にする仕組みと説明している（[Oxlint: Configuration](https://oxc.rs/docs/guide/usage/linter/config.html)）。
jsPlugin の規則がカテゴリで有効にならないことは、上の実行結果から確かめた。

推奨案は次のとおりである。

1. `radix`、`no-new-wrappers`、`consistent-type-definitions`、相対 import の禁止は追加しない。
   すでに有効である。
2. Tailwind の規則は、保守者の推奨に合わせて `better-tailwindcss/enforce-canonical-classes` を一つだけ足す。
   既存コードの違反は `src/routes/index.tsx` の 3 箇所で、`vp lint --fix` で `text-lg/8`、`text-sm/6` に直る（2 回の実行で whitespace まで直り、`shadcn/no-unknown-classes` も通った、確認済み）。
3. `no-restricted-imports` は C1（feature 境界）のために空けておく。

`unicorn/filename-case` が kebab-case を強制していることは、report.md の C5 で要検証としていた事項の答えになる。
`component-design.md` のとおり `OrderListItem.tsx` を作ると、現設定の lint は失敗する。

利用者が決める論点は次の二つである。

- `enforce-canonical-classes` と `enforce-shorthand-classes` のどちらを採るか。
  canonical は範囲が広く既存コードに 3 箇所の修正が要り、起動コストもある。
  shorthand は既存コードに違反が無い。
- 重複して報告される規則の組（`radix` と `unicorn/prefer-number-properties` など）を放置するか。
  どちらも同じ修正で消えるので、放置しても害は小さい（推測）。

## C9、C10、C17. AI コーディングエージェントのガードレール

### 標準

エージェントの hook に、ベンダーをまたぐ仕様は無い（確認できた範囲）。
エージェント向けの指示ファイルには、オープンな形式として [AGENTS.md](https://agents.md/) がある。
必須の項目は無く、ただの Markdown である。
階層に複数あるときは編集対象に最も近いファイルが優先され、利用者のチャットでの指示がすべてに優先する、と FAQ にある。

生成物の印には、言語やホスティングの慣行がある。

- Go は、生成コードの先頭のコメントと空行以外の文より前に、`^// Code generated .* DO NOT EDIT\.$` に一致する行を置くと定める（[cmd/go: Generate Go files](https://pkg.go.dev/cmd/go#hdr-Generate_Go_files_by_processing_source)）。
- GitHub は、`.gitattributes` で `linguist-generated` を付けたパスを、言語統計から除き差分で既定で畳む（[GitHub Docs](https://docs.github.com/en/repositories/working-with-files/managing-files/customizing-how-changed-files-appear-on-github)、[Linguist overrides](https://github.com/github-linguist/linguist/blob/main/docs/overrides.md)）。
  Linguist が `// Code generated` の行だけで自動判定するのは `.go` の file であり、他の言語は生成器ごとの固有の文字列で判定する（[generated.rb](https://github.com/github-linguist/linguist/blob/main/lib/linguist/generated.rb)）。
その一覧に Orval と TanStack Router は無い（grep で確認済み）。

### ベストプラクティス

Kiro の hook は、`.kiro/hooks/<id>.json` に `version: "v1"` の schema で置く（[Kiro: Hooks](https://kiro.dev/docs/hooks/)）。
PreToolUse の matcher は tool 名の正規表現であり、command は STDIN で JSON を受け取る。
exit code 2 で PreToolUse の実行を止め、STDERR が agent に返る（[Kiro IDE 1.0: Hooks](https://kiro.dev/docs/ide/whats-new-v1/hooks/)、[Hook actions](https://kiro.dev/docs/hooks/actions/)）。
2 以外の非 0 の扱いは文書の間で食い違う。
IDE 1.0 の文書と Hook actions の CLI の説明はエラーとして扱い実行を続けるとし、Hook actions の IDE の説明は PreToolUse を止めるとしている（要検証）。
exit 2 を使えば、どちらの記述でも拒否になる。

Kiro の Stop（Agent Stop）は、CLI では STDOUT に `"decision": "block"` の JSON を返すと、`reason` を新しい利用者メッセージとして会話を続けさせられる（[Hook types](https://kiro.dev/docs/hooks/types/)）。
一方、[CLI 3.0 の移行ガイド](https://kiro.dev/docs/cli/v3/hooks-migration/)の表は Stop を block できないとしており、文書の間で食い違っている（要検証）。
SessionStart の command は、exit 0 なら STDOUT が agent の文脈に入る（Hook types）。
Kiro の best practices は、対象を狭い pattern に絞り、実行頻度と待ち時間に注意し、想定外の入力を安全に扱うよう求める（[Best practices](https://kiro.dev/docs/hooks/best-practices/)）。

Kiro の permissions は、`fs_write` などを glob で `deny` できる（[Kiro: Permissions](https://kiro.dev/docs/permissions/)）。
ただし workspace 単位の設定はリポジトリの外（`~/.kiro/workspace-roots/<hash>/`）に置かれ、clone したリポジトリから規則を注入できない、と同文書は明記している。
チームで共有するガードには、リポジトリに置ける hook が向いている。

Claude Code の文書も同じ構造を持つ。

- PreToolUse で exit 2 を返すか、`permissionDecision: "deny"` を返すと tool の実行を止められる（[Claude Code: Hooks reference](https://code.claude.com/docs/en/hooks)）。
- 公式ガイドに、`.env`、`package-lock.json`、`.git/` への編集を拒否する `protect-files.sh` の例がある（[Hooks guide](https://code.claude.com/docs/en/hooks-guide)）。
- 指示ファイル（CLAUDE.md）は助言にとどまり、hook は決定的に実行される、と best practices は対比している（[Best practices](https://code.claude.com/docs/en/best-practices)）。
  同文書は、指示ファイルを短く保ち、コードを読めば分かることや file ごとの説明を書かないよう勧める。
- Stop hook は、検査が通るまで turn を終わらせない決定的な関門として使える。
  無限に続かないよう、`stop_hook_active` を見て早めに抜けること、連続 8 回で打ち切られることが文書にある（Hooks reference の Stop 節）。

「API が無いときは止まって確認する」に相当する指示は、Anthropic の prompt の文書に例がある。
課題が実現できないときやテストが誤っているときは、回避策を作らず知らせるよう指示する例である（[Claude prompting best practices](https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/claude-prompting-best-practices)）。
同じ文書は、新しいモデルは system prompt によく従うため、「CRITICAL」「MUST」のような強い言い回しを弱めるよう勧めている。

### アンチパターン

- 書込み tool の hook だけを生成物の保護と見なす。
  shell の `sed -i` やリダイレクトは write tool を通らない（推測、Kiro の matcher は tool 名で判定するため）。
  最終の検出は、再生成して差分を見る検査に置く必要がある。
- `components/ui` を書込み禁止にする。
  現リポジトリの ADR-025 は `components/ui` を app code として扱う。
- Stop hook で毎回すべての検査を走らせ、しかも `--fix` でファイルを書き換える。
  旧リポジトリの `frontend-lint-check.sh` は、agent が知らないうちに差分が増えることを自ら注記している。
- SessionStart（旧 agentSpawn）でディレクトリの一覧を毎回注入する。
  Claude Code の best practices が除外を勧める「file ごとの説明」に近く、agent は必要なときに自分で一覧を取れる。
- hook の入力の JSON を `grep -oP` で切り出す。
  旧リポジトリの全 hook がこの形であり、Kiro の best practices が求める想定外の入力への耐性が弱い。
  現リポジトリの `block-iwe-normalize.sh` は `jq` を優先し、失敗時は許可する（fail-open）形にしている。
- CLI 2.x の埋め込み形式（`.kiro/agents/default.json` の `hooks`）で書く。
  旧リポジトリはこの形式であり、CLI 3.0 の移行ガイドは 3.0 では使わないよう明記している。
- 指示を「🛑」「必ず」のような強い言い回しで書く。
  Anthropic の文書は、強い言い回しが過剰な反応を招きうるとしている。

### デファクトスタンダード

- 生成物の先頭の注記は、使っている生成器がすでに出力している（確認済み）。
  TanStack Router の `routeTree.gen.ts` は「自動生成であり変更しない」旨の注記と `/* eslint-disable */` を持つ。
  Orval は `Generated by orval` と `Do not edit manually.` の注記を出す（旧リポジトリの `frontend/src/api/category/category.ts`）。
  どちらも Go の正規表現の形ではない。
- TanStack Router は `routeTree.gen.ts` を Git に commit するよう勧める（[FAQ](https://tanstack.com/router/v1/docs/framework/react/faq)）。
- 現リポジトリには `.gitattributes` が無い（確認済み）。
- OpenAI のリポジトリは 88 個の AGENTS.md を持つ、と agents.md のサイトにある。
  現リポジトリにも `frontend/AGENTS.md`（Vite+ が生成した区画）がある。
- 現リポジトリの `block-iwe-normalize` は、docs の規約（`documentation-authoring.md`）と、それを機械で強制する PreToolUse hook を対で持つ前例である。
  fail-open と、環境変数による明示的な迂回を備えている。
- PreToolUse で生成物を守る OSS の実例は、公式文書の例（Claude Code の `protect-files.sh`）以外に確認できなかった。

### 現リポジトリへの当てはめ

C9（生成物への書込み拒否）の推奨案は次のとおりである。

1. `.kiro/hooks/` に v1 形式の PreToolUse hook を一組置き、`frontend/src/routeTree.gen.ts` と `frontend/src/api/generated/**` への書込みを exit 2 で拒否する。
   STDERR には再生成の手順を書く。
2. 実装は `block-iwe-normalize.sh` にならい、`jq` で path を取り出し、取り出せないときは許可する。
3. 最終の検出は CI に置く。
   `routeTree.gen.ts` は現状どの CI でも再生成差分を検査していない（`frontend-ci.yml`、`Taskfile.yml`、`lefthook.yml` を grep で確認済み）。
   build で再生成してから `git diff --exit-code` を見る形が考えられる（build が再生成するかは推測、要検証）。
   Orval の生成物は ADR-024 の未決事項と組で決める。
4. `.gitattributes` に `linguist-generated` を付けるかは、生成物を commit する方針が決まってから決める。
   現時点で commit されているのは `routeTree.gen.ts` だけである。

hook の入力の JSON で、書込み tool の path がどの field に入るかは文書で確認できなかった（要検証）。
matcher に Kiro の UI が示す `write` のような分類名を書けるか、tool 名の正規表現で書く必要があるかも要検証である。

C10（止まる条件）の推奨案は、report.md の案どおり `docs/frontend/runbook-add-feature.md` に数行足すことである。
ADR-038 は規約の正文を docs に置き steering を案内に限るので、AGENTS.md や steering には書かない。
書き方は Anthropic の例にならい、「API が OpenAPI に無いときは、手書きの fetch や仮実装で回避せず、何が足りないかを示して確認を取る」のように、理由と取るべき行動を平易に書く。
`frontend/AGENTS.md` は Vite+ の生成区画を持つため、手で書き足すなら区画の外に置く必要がある（推測）。

C17（SessionStart と Stop）は今は入れない。

- SessionStart での構造の注入は、Claude Code の best practices が除外を勧める内容に近く、`architecture.md` が構成をすでに定めている。
- Stop での検査は、lefthook の pre-commit（`fe-check`）、pre-push、CI と重なる。
  毎 turn の待ち時間が増え、block の挙動は Kiro の文書の間で食い違っている（要検証）。
- 入れるなら、変更が `frontend/` にあるときだけ非破壊の検査（`--fix` なし）を走らせ、Claude Code の `stop_hook_active` に相当する連続実行の歯止めを確かめてからにする。

利用者が決める論点は次の三つである。

- C9 の hook を、生成物の Git 管理（ADR-024 の未決事項）より先に `routeTree.gen.ts` だけで入れるか、Orval 導入と同時に入れるか。
- 生成物の再生成差分の検査を CI のどの job に置くか。
- Stop hook を将来入れる条件（たとえば lefthook を通さない agent の運用が増えたとき）を記録しておくか。
