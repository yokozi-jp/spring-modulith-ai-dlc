# 前回レポートの「推測」「要検証」を実行で確かめた結果

対象は `spring-modulith-ai-dlc` の `main` の `frontend/` である。
前回レポートは同じディレクトリの [report.md](report.md) である。
「確認済み」は実行結果かファイルの行で確かめた事実、「推測」はそれ以外を指す。

## 要約

- 依存の導入と lint の実行はできた。現 `main` の `vp check` は通る。
- `unicorn/filename-case` は既定の kebab-case で有効である。docs が定める `OrderListPage.tsx` や、ディレクトリ形式の route `$orderId.tsx` は `task fe-check` で失敗する（C5 は確認済みになった）。
- event handler から Promise を起動する書き方は、確かめた 7 通りがすべて違反になる。設定を変えるか抑制コメントを書かない限り、`navigate()` も `invalidateQueries()` も handler から呼べない（C11 は前回の見込みより強い形で確認済み）。
- `radix`、`no-new-wrappers`、`typescript/consistent-type-definitions`、`import/max-dependencies`（閾値 10）はすでに有効である。`../` の import も `import/no-relative-parent-imports` ですでに禁止されている。
- `no-restricted-imports` は有効だが設定が無く、何も制限していない。feature 間の import と feature 単位の循環は検出されない。
- 業務 API はまだ無く、frontend は API を呼んでいない。CSRF は効いており、認証済みでも token 無しの POST は 403 になる（backend のテストで確認済み）。
- Orval 8.36.0 の fetch client で、要求ごとに Cookie を読んで header に載せる文書化された手段は mutator だけである。mutator 無しでは、非 2xx の応答は既定で例外にならず、TanStack Query の error として扱われない。

## 1. 依存の導入

**確認済み**：

- 環境に `npm`、`pnpm`、`vp`、`task` が無かった。
- mise で Node.js 24.21.0（`frontend/.node-version`）と pnpm 11.21.0 を入れたが、pnpm の単体バイナリは `libatomic.so.1` が無く起動しなかった。
- `npx pnpm@11.21.0` は `package.json` の `devEngines`（pnpm 指定）により npm 側が `EBADDEVENGINES` で拒否した。
- そこで、リポジトリ外の `/root/pnpm-tool` に npm で `pnpm@11.21.0` を入れ、`frontend/` で `pnpm install --frozen-lockfile` を実行した。lockfile どおりに 9.8 秒で完了した。
- CI と同じく `frontend/node_modules/.bin` を PATH に入れ、`vp check` を実行した。format、lint、型検査がすべて通った（27 files、17 files）。
- `task` が無いので、`task fe-check` の中身（`Taskfile.yml` 321 から 325 行目の `vp check`）と `vp lint` を直接実行した。

## 2. lint 規則の有効状態

有効な設定は `vp lint --print-config` で出力した。
ただしこの出力には jsPlugin（`better-tailwindcss`、`shadcn` など）の規則が含まれないため、jsPlugin は違反コードで確かめた。
違反コードは `frontend/src/features/zz-probe/`、`zz-a/`、`zz-b/` に一時的に置き、確認後に `src/features` ごと削除した（`src/features` は元々存在しない）。

### 一覧

| 規則 | 状態 | 根拠 |
| --- | --- | --- |
| `unicorn/filename-case` | error、option 無し（既定の kebab-case） | print-config、probe |
| `no-void` | error、option 無し（`allowAsStatement: false`） | print-config、probe |
| `typescript/no-floating-promises` | error、option 無し | print-config、probe |
| `typescript/no-misused-promises` | error | print-config、probe |
| `typescript/strict-void-return` | error | probe |
| `import/no-cycle` | error | print-config、probe |
| `import/max-dependencies` | error、閾値 10 | probe のメッセージ「Maximum allowed is 10」 |
| `import/no-relative-parent-imports` | error | print-config、probe |
| `no-restricted-imports` | error だが option 無し（制限対象なし） | print-config、`vite.config.ts` に記述なし |
| `radix` | error | probe（`Number.parseInt("10")` が違反） |
| `no-new-wrappers` | error | probe（`new String("x")`、`unicorn/new-for-builtins` も同時に違反） |
| `typescript/consistent-type-definitions` | error、既定の `interface` | probe（`type Shape = { ... }` に「Use `interface` instead of `type`」） |
| `better-tailwindcss/*` | 5 規則だけ有効 | `vite.config.ts` 94、160 から 164 行目、probe |

いずれも確認済みである。
カテゴリはすべて `deny` で、組み込み規則は 694 個が有効だった。

### unicorn/filename-case

**確認済み**（probe の結果）：

| ファイル名 | 結果 |
| --- | --- |
| `OrderListPage.tsx` | 違反（Filename should be in kebab-case） |
| `OrderListPage.test.ts` | 違反 |
| `orderList.ts` | 違反 |
| `$orderId.ts` | 違反 |
| `-ProbeDash.ts`、`__ProbeUnder.ts` | 違反 |
| `order-list-page.test.ts` | 通過 |
| `_layout.ts` | 通過 |
| `orders_.$orderId.edit.ts` | 通過 |
| `orders.index.ts` | 通過 |

既存のファイルが通る理由は次のとおりである。

- `button.tsx`、`resolve-locale.ts`、`utils.ts`、`main.tsx` は kebab-case である（確認済み）。
- `src/components/ui/**` に filename-case の override は無く、shadcn CLI が生成する名前がもともと kebab-case なので通っている（確認済み。`vite.config.ts` 180 から 187 行目の override は shadcn 規則だけを止めている）。
- `src/i18n/index.ts` は規則の対象外である（[Oxlint のドキュメント](https://oxc.rs/docs/guide/usage/linter/rules/unicorn/filename-case.html)が index ファイルを除外すると明記）。
- `i18next.d.ts` と `orders_.$orderId.edit.ts` が通るのは、既定の `multipleFileExtensions: true` で最初の `.` 以降を拡張子として扱うためである（ドキュメントで確認、probe と整合）。
- `__root.tsx` と `-index.test.tsx` は通る（確認済み）。先頭の `_` と `-` を判定から外しているためと考えている（推測。`_layout` が通り、`__ProbeUnder` が後半の PascalCase だけで違反したことと整合する）。
- `src/routeTree.gen.ts` は `lint.ignorePatterns`（`vite.config.ts` 35、70 行目）で lint 対象から外れている（確認済み）。

docs との食い違いは次のとおりである（確認済み）。

- `docs/frontend/component-design.md` 24 行目は feature の component file を PascalCase（`OrderListItem.tsx`）にすると定めるが、lint で違反になる。
- ADR-032 の図（49 行目）の `<Feature>Page.tsx` も違反になる。
- `docs/frontend/url-design.md` 28 行目は path parameter を `$orderId` と書くとする。route をディレクトリ形式（`routes/orders/$orderId.tsx`）で作ると違反になり、flat 形式（`routes/orders.$orderId.tsx`）なら通る。

### no-void と floating promise

`useNavigate()` と `useQueryClient()` を使う component で、button の `onClick` に次の書き方を置いた。
結果はすべて違反であり、確認済みである。

| 書き方 | 違反した規則 |
| --- | --- |
| `() => { navigate({ to: "/" }); }` | `typescript/no-floating-promises` |
| `() => { queryClient.invalidateQueries({ queryKey: ["x"] }); }` | `typescript/no-floating-promises` |
| `() => { void navigate({ to: "/" }); }` | `no-void` |
| `async () => { await navigate({ to: "/" }); }` | `typescript/no-misused-promises`、`typescript/strict-void-return` |
| `() => navigate({ to: "/" })` | `typescript/no-misused-promises`、`typescript/strict-void-return` |
| `() => { navigate(...).catch((error: unknown) => { reportError(error); }); }` | `promise/prefer-await-to-then`、`promise/prefer-await-to-callbacks` |
| `() => { navigate(...).then(() => undefined, (error) => { ... }); }` | `promise/catch-or-return`、`promise/prefer-catch`、`promise/prefer-await-to-then` ほか |

`no-floating-promises` のメッセージは「add void operator to ignore」と案内するが、その `void` は `no-void` が禁じている。
したがって、現設定では同期の event handler から Promise を起動する書き方が残っていない。
同じことは、Promise を返す他の API（TanStack Form の `form.handleSubmit()` など）にも当てはまると考えている（推測。`handleSubmit` の戻り値の型は確かめていない）。
TanStack Query の `mutation.mutate()` は `void` を返すので、この問題に当たらないはずである（推測）。

### import/no-cycle と feature 境界

**確認済み**：

- `@/features/zz-probe/cx` と `@/features/zz-probe/cy` を alias で相互参照させると、両方に「Dependency cycle detected」が出た。前回の probe では相対 import の循環も検出された。
- `zz-a/a.ts` から `@/features/zz-b/b`、`zz-b/b2.ts` から `@/features/zz-a/a2` を import すると、feature 単位では循環しているが、どの規則も報告しなかった。feature 間の import そのものも報告されなかった。
- `import { cn } from "../../lib/utils"` は `import/no-relative-parent-imports` だけが報告し、`no-restricted-imports` は報告しなかった。

`../` が禁止されているので、feature 内の `components/` から同じ feature 直下の `queries.ts` を参照するにも `@/features/<自分>/queries` と書くことになる（`no-relative-parent-imports` の違反から導いた推測）。
そのため、`src/features/**` で `@/features/**` を一律に禁じる `no-restricted-imports` の override は、自 feature 内の import まで禁じてしまう。
C1 を実装するには、feature ごとの override を列挙するか、import 元と import 先の feature 名を比べる小さな jsPlugin rule が要る。

### better-tailwindcss

**確認済み**：

- `eslint-plugin-better-tailwindcss` は jsPlugin として読み込まれている（`vite.config.ts` 94 行目）。
- 有効な規則は `enforce-consistent-class-order`、`no-deprecated-classes`、`no-duplicate-classes`、`no-unnecessary-whitespace`、`no-conflicting-classes` の 5 個である（160 から 164 行目）。
- `className="h-10 w-10 p-2 p-2"` は `no-duplicate-classes` だけが報告し、`h-10 w-10` は報告されなかった。`enforce-shorthand-classes` は無効である。
- jsPlugin の規則はカテゴリ指定では有効にならず、明示した規則だけが効いていると読める（print-config に jsPlugin の規則が出ないことと probe の結果からの推測）。

## 3. API と CSRF

### backend と frontend の API

**確認済み**：

- backend の Controller は `ApiErrorController`（`@Hidden`、`/error` だけ）と `ApiExceptionHandler`（`@RestControllerAdvice`）だけである。更新系も参照系も業務 API は無い。
- `frontend/src` に `fetch(` と `/api/` の参照は無い。
- `frontend/orval.config.ts` は存在しない。

### CSRF

**確認済み**：

- `backend/src/main/java/com/example/demo/SecurityConfig.java` 99 から 100 行目が `.csrf(csrf -> csrf.spa())` を設定し、「SPA が XSRF-TOKEN Cookie を読み、更新系リクエストの X-XSRF-TOKEN Header で送り返す」とコメントしている。
- 同 106 から 107 行目は、logout も Spring Security の既定どおり POST と CSRF token を要求するとしている。
- `backend/src/test/java/com/example/demo/ApiContractTest.java` 53 から 59 行目は、認証済み利用者が token 無しで `POST /api/missing` を送ると 403 Problem Details になることを検査している。
- ADR-014 の 49 行目は `X-XSRF-TOKEN` を許可すると定めている。

したがって、SPA から更新系 API を呼ぶときは、`XSRF-TOKEN` Cookie の値を `X-XSRF-TOKEN` header に載せる必要がある。
Cookie 名と header 名が Spring Security の既定値であることは、[Spring Security の CSRF の文書](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)とリポジトリのコメントで確かめた。
ライブラリのコードは Gradle cache が無く確かめていない。
文書によれば、認証成功時と logout 成功時に token が作り直されるため、SPA は要求ごとに最新の Cookie を読む必要がある。

### Orval 8.36.0 の fetch client

`frontend/node_modules` の Orval（8.36.0）で、POST と GET を一つずつ持つ小さな OpenAPI から、リポジトリ外の一時ディレクトリへ client を生成した（`client: "react-query"`、`httpClient: "fetch"`）。
生成後に一時ディレクトリは削除した。

#### (a) custom mutator のシグネチャと戻り値

**確認済み**：

- 生成コードは `customFetch<createOrderResponse>(getCreateOrderUrl(), { ...options, method: 'POST', headers: {...}, body: JSON.stringify(order) })` を呼び、その戻り値をそのまま返す。mutator は `(url: string, options: RequestInit) => Promise<T>` の形になる。[Orval の Fetch ガイド](https://orval.dev/docs/guides/fetch/)の例とも一致する。
- 既定（`fetch.includeHttpResponseReturnType: true`）では、`T` は `{ data, status, headers }` の envelope であり、成功と宣言済みエラー status（例では 201 と 409）の union である。mutator は自分でこの形を返す必要がある。
- `fetch.includeHttpResponseReturnType: false` にすると、`T` は body の型（例では `Order`）になり、mutator は body だけを返せばよい。
- mutator を指定すると、生成 Hook の追加 option の名前が `fetch` から `request` に変わり、型は `Parameters<typeof customFetch>[1]` になる。
- `fetch.includeHttpErrorResponse` は、envelope、`forceSuccessResponse`、3 引数の mutator を前提にする（`@orval/core` の型定義 1330 から 1337 行目付近の JSDoc）。

#### (b) mutator 無しで要求ごとに header を付ける手段

**確認済み**：

- `override.requestOptions` の object は、各 fetch 呼出しへ静的なリテラルとして埋め込まれる（例：`credentials: 'same-origin'`）。値は生成時に固定され、要求ごとに Cookie を読めない。
- 例外として、`requestOptions.headers` の値だけは template literal の中へ無加工で埋め込まれる（`@orval/fetch/dist/index.mjs` 262 行目）。`"${readXsrf()}"` を指定すると `'X-XSRF-TOKEN': \`${readXsrf()}\`` が生成され、要求ごとに評価される。ただし import を生成させる手段が無いので、参照できるのは global の式だけであり、GET にも付く。[Output の文書](https://orval.dev/docs/reference/configuration/output/)は `requestOptions` を「request option を設定または削除する」としか書いておらず、この展開は文書化されていない。
- 生成 Hook は呼出しごとに `fetch?: RequestInit` を受けるので、呼出し側で header を渡すことはできる。全ての mutation の呼出し箇所に同じ処理を書くことになる。
- `fetch.useRuntimeFetcher` は、生成関数に `fetchFn` 引数を足すだけで、全体の fetch を差し替える設定ではない（`index.mjs` 255 から 256 行目）。

エラー処理についての事実も見つかった（確認済み）。

- mutator も `forceSuccessResponse` も無い既定の生成コードは、非 2xx の応答でも例外を投げず、`{ data, status, headers }` を返す。そのため 403 や 409 が TanStack Query の `error` や `onError` に入らない。
- `fetch.forceSuccessResponse: true` にすると、非 2xx で `Error` を投げ、`info` に解析済みの body、`status` に status を入れる（`index.mjs` 318 から 325 行目）。Problem Details を TanStack Query の error に載せるだけなら mutator は要らない。`ApiError` のような独自の型にするなら mutator が要る。
- 生成された query key は `getListOrdersQueryKey = () => [\`/api/orders\`]` のように URL を基にしている。前回の C3 の前提（手書き key とずれる）を裏付ける。

## 4. Proposed の ADR を改訂してよいか

`docs/adr/conventions.md` の規則は次のとおりである（確認済み）。

- ADR は判断を提案または実装する時点で Proposed として起こす（13 行目）。
- 新しい決定は実装前に Proposed として起こし、Pull Request でコードと一緒にレビューする（54 行目）。
- 置き換えるときは新旧の ADR を `Superseded by ADR-NNN` で相互リンクする（55 行目）。
- ADR が Accepted または Superseded になったら、対応する規約文書を同じ変更で更新する（16、61 行目）。
- 過去のアーキテクチャ方針を変更する判断は ADR を作る基準に当たる（26 行目）。

Proposed の ADR の本文を改訂してよいかは、明文で定めていない（確認済み）。
Git の履歴では、ADR-024、ADR-032、ADR-034 の作成後の変更はリンク先と front matter の修正だけで、決定の中身を改訂した前例は無い（`git log` と `git show` で確認済み）。

規則から読める扱いは次のとおりである（推測）。

- Proposed は「コードと一緒にレビュー中」の状態なので、実装する PR の中で Proposed の ADR を改訂することは規則に反しない。ADR-032 自身も 106 行目で lint rule の追加判断を予告しているので、C1 を ADR-032 の改訂として書くのは自然である。
- Superseded の手順は、すでに決まった ADR を別の決定で置き換えるときのものであり、Accepted になった後の変更に使う。
- ADR-034 は 42 から 47 行目で「次のものに限って個別規則を無効化または調整する」と調整の範囲を列挙している。`no-void` の option や `unicorn/filename-case` の `cases` の変更はこの列挙に無いので、ADR-034 の改訂か新しい ADR が要る。
- 利用者は「ADR の更新は実装とあわせて行いたい」としているので、ADR-032 と ADR-034 を実装 PR の中で改訂し、独立した新しい判断（例：feature 境界を検査する jsPlugin の採用）だけを新 ADR にする形が規則と両立する。

## 前回レポートの前提の変化

- **C5（ファイル名表記）**：要検証から確認済みに変わった。docs どおり PascalCase の file やディレクトリ形式の `$orderId.tsx` を作ると `fe-check` が失敗するので、最初の feature の前に docs か lint のどちらかを直す必要がある。
- **C11（floating promise）**：推測から確認済みに変わり、問題は前回の見込みより大きい。`void`、async handler、`.catch` のどれも違反になるので、最初の画面遷移か cache 無効化の時点で設定の変更が避けられない。
- **C14（追加の lint rule）**：`radix`、`no-new-wrappers`、`consistent-type-definitions`、`../` の禁止はすでに有効で、追加の候補は `better-tailwindcss/enforce-shorthand-classes` だけになった。`no-restricted-imports` は有効だが空なので、C1 の受け皿として使える。
- **C16（import 数）**：確認済みになった。`import/max-dependencies` は閾値 10 の error である。
- **C2（mutator の要否）**：CSRF のためには mutator が実質的に必要である（mutator 以外は未文書の template 展開か呼出しごとの指定しかない）。エラーを例外にするだけなら `forceSuccessResponse` で足りる。業務 API がまだ無いので、導入時期は「最初の更新系 API」のまま変わらない。

## 推奨

実装はしていない。

1. C5 と C11 は、最初の feature を作る前に一つの PR で決める。どちらも ADR-034 の調整範囲の外なので、ADR-034 の改訂を同じ PR に含める。
2. C11 の候補は、`no-void: ["error", { allowAsStatement: true }]` にして `void navigate()` を許す形が最小の変更である。async handler を許すなら `no-misused-promises` の `checksVoidReturn.attributes` と `strict-void-return` の両方を調整する必要がある。
3. C1 は `no-restricted-imports` の共有層向け override と、feature 名を比べる jsPlugin rule の組合せで設計する。`../` 禁止のため、一律の pattern では自 feature 内の import まで禁じる点に注意する。
4. C2 は最初の更新系 API で mutator を入れ、`includeHttpResponseReturnType` を true のまま使うか false にするかを同時に決める。true のままなら mutator は `{ data, status, headers }` を返す必要がある。

## 後片付け

- probe 用の `frontend/src/features/` は削除した。Orval の一時ディレクトリと設定の出力ファイルも削除した。
- `git status --short` は調査前と同じ `?? .agents/tasks/` だけである（このレポートもその下にある）。
- `frontend/node_modules` と、リポジトリ外の `/root/pnpm-tool`（pnpm 11.21.0）は残している。
