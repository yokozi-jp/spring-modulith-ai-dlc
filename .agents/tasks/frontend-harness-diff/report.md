# 旧 harness（yaguchi/frontend-setup）と現リポジトリの frontend 比較

調査対象は次の二つである。

- **旧**：`yokozi-jp/spring-modulith-ai-harness` の `yaguchi/frontend-setup`（HEAD `a880890`）。`origin/main` からの差分は 122 commit、232 file。
- **現**：`spring-modulith-ai-dlc` の `main`（HEAD `783fc15`）の `frontend/`、`docs/frontend/`、関連 ADR、Taskfile、CI、lefthook。

旧リポジトリは `/projects/sandbox/.harness-ref` に clone した（`/tmp` はコマンド間で保持されなかったため）。
現リポジトリのコードは変更していない。
`frontend/node_modules` が無く `vp` も入っていないため、lint を実際に走らせた検証はしていない。
以下で「確認済み」はファイルを読んで確かめた事実、「推測」「要検証」はそれ以外を指す。

## 要約

Q-a の答えは「決めている」である。
ディレクトリ構成と依存方向は [ADR-032](../../../docs/adr/ADR-032-organize-frontend-by-business-feature.md)（Proposed）と `docs/frontend/architecture.md` で定めている。
ただし feature 境界を機械的に強制する手段は意図的に置いておらず、「実在する違反例を基に lint rule を追加する」と先送りしている（`architecture.md` の「依存方向」節、ADR-032 の Negative）。
実際の `src/` は「現在の構成」に沿っている。

旧リポジトリは 4 feature（category、product、pricing、dashboard）を AI エージェントに実装させ、その過程で得た知見を steering 6 本、ADR 8 本、oxlint の自作 rule 5 個、shell 検査 5 本、Kiro CLI hook 4 本に落としている。
多くは現リポジトリの決定（Base UI、TanStack Form、MSW、loader preload、wrapper Hook 禁止、i18n）と衝突するため、そのままは取り込めない。

取り込む価値が高いのは、実装してみて初めて分かった落とし穴の知見である。

1. feature 境界の lint が無いと、AI は feature 間の内部 import と feature 単位の循環を作る（旧で実際に 6 箇所、循環 2 組）。現リポジトリが先送りしている「実在する違反例」は旧リポジトリにすでにある。
2. 更新系 API には CSRF header を付ける custom mutator が要る。現 backend は `csrf.spa()` を使っており、現 docs の「必要と確認できたら追加」の条件はすでに満たされている見込みが高い。
3. mutation 後の cache 無効化で、手書きの query key と Orval が生成する key がずれると一覧が更新されない。現 docs は無効化の書き方を定めていない。
4. `operationId` の命名パターンを決めないと、Orval の Hook 名が `useFindById1` のようになる。現 Spectral は存在だけを検査している。
5. ファイル名の大文字小文字（旧は kebab-case を lint で強制、現 docs は feature component を PascalCase）は、最初の feature を作る前に決める必要がある。

## Q-a 現リポジトリのディレクトリ構成

### 決めている文書

- [ADR-032: Frontend を業務機能単位で構成する](../../../docs/adr/ADR-032-organize-frontend-by-business-feature.md)（Proposed、2026-09-30）が決定の記録である。
- `docs/frontend/architecture.md` が規約の正文であり、「現在の構成」「目標のディレクトリ構成」「業務機能の境界」「共有コード」「依存方向」を持つ。
- `docs/frontend/routing-and-state.md`、`api-client-orval.md`、`ui-and-style.md`、`component-design.md` が各層の中身を定める。
- `.kiro/steering/frontend.md` は ADR-038 に従い、上記への案内だけを持つ。

### 構成の要点（確認済み）

```text
frontend/src/
├── main.tsx              # composition root（QueryClient、Router、i18n Provider）
├── routes/               # TanStack Router の file-based route。URL と loader と画面の接続だけ
├── features/<business>/  # <Feature>Page.tsx、必要時のみ components/ hooks/ queries.ts schemas.ts
├── api/generated/        # Orval 生成物専用（endpoints/<tag>, models/<tag|shared>, mocks/）
├── api/<custom-mutator>.ts  # 共通 transport 要件があるときだけ
├── components/ui/        # feature 非依存の UI primitive（shadcn Base UI 版）
├── lib/                  # 小さな純粋関数
└── i18n/
```

- 軽量な Package by feature であり、FSD やバックエンドのオニオン層の複製は不採用（ADR-032 の Alternatives）。
- トップレベル `shared` は作らない。共有物は `components/ui`、`lib`、`api/generated`、`i18n` で表す（`architecture.md` の「共有コード」）。
- `app`、`services`、`stores`、トップレベル `hooks` は禁止しないが今は作らない。
- 空ディレクトリを先に作らない。
- feature root の barrel は公開境界が定まるまで作らない。

### 依存方向（確認済み）

`routes → features → api/generated, components/ui → lib, i18n` の一方向である。
`api/generated`、`components/ui`、`lib`、`i18n` から feature を参照しない。
別 feature の内部ファイルを直接 import しない。
複数 feature をまたぐ合成は route で行う。

### 強制手段（確認済み）

- feature 境界と依存方向を検査する lint は無い。`frontend/vite.config.ts` の `lint` に `no-restricted-imports` や boundaries 系 plugin は無い。
- eslint-plugin-boundaries は使っていない。ADR-034 により Lint は Oxlint に一本化している。
- 間接的に効くものは次のとおり。
  - Knip（`frontend/knip.json`、`task fe-knip`）が未参照ファイルと未使用 export を検出する。
  - jscpd（`task lint-duplicates`）が重複を検出する。
  - Oxlint の `restriction` カテゴリを error にしており、そこに属する `import/no-cycle` はファイル単位の循環を検出する（推測。カテゴリ指定で有効になる前提で、実行して確かめてはいない）。
- 生成物の手編集禁止は steering の行動指針（`.kiro/steering/frontend.md`）とレビューに依存している。

### 実際の `src/` との一致

`git ls-files frontend/src` の結果は `architecture.md` の「現在の構成」とほぼ一致する。
ずれは次の二点である。

- `src/i18n/index.test.ts` が実在するが、「現在の構成」の図に無い（軽微な文書のずれ）。
- `src/routes/index.tsx` が `HomePage` の実装を route file に持つ。`routing-and-state.md` は route file を page 実装の置き場にしないと定めるが、ADR-032 が「既存の小さな構成は維持」としているため、違反ではなく初期画面の例外と読める。

ADR-032 の図は `i18n.ts`、実物は `i18n/` ディレクトリである。
ADR は判断時点の記録なので、`architecture.md` 側が正しく更新されていれば問題ない。

## Q-b 旧リポジトリのディレクトリ構成と強制手段

### 構成（`frontend/README.md`、`frontend-dev-environment.md`）

```text
src/
├── routes/              # page（ファイル構造 = URL 構造）
├── features/<f>/        # components/ hooks/ types/ のみ。直下にファイルを置かない
├── components/
│   ├── layout/          # app-layout, header, sidebar
│   ├── ui/              # shadcn（Radix 版）。編集禁止
│   └── *.tsx            # error-message, empty-state, confirm-dialog などの共通 component
├── api/                 # Orval 生成物（編集禁止）
├── hooks/               # 汎用 Hook
├── lib/                 # api-client.ts, api-error.ts, query-client.ts, utils.ts
├── types/
└── styles/globals.css
```

ファイル名はすべて kebab-case（ADR-0012）、Hook は `hooks/use-*.ts`、component は `components/<name>.tsx` である。
route は `products_.$id_.edit.tsx` のような非ネストの命名を標準にしている。

### 強制手段

旧リポジトリは強制を四層に分けている（`frontend/README.md` の「コード品質の仕組み」）。

| 層 | 手段 | 中身 |
| --- | --- | --- |
| Oxlint 自作 rule | `frontend/oxlint-plugins/project-rules.js` | `no-direct-api-client`（features の hooks で apiClient と fetch を禁止）、`hook-in-dedicated-file`、`no-arrow-function-hook`、`no-props-object-param`、`no-button-inside-link` |
| Oxlint 設定 | `frontend/vite.config.ts` | `unicorn/filename-case: kebabCase`、`import/no-cycle`、`no-restricted-imports`（`../` 禁止、barrel 禁止、routes から `@/api/*` 禁止、test から `msw` と `vitest` 禁止）、`react/forbid-dom-props`（style 禁止）、`React.FC` 禁止 |
| shell 検査 | `frontend/scripts/checks/*.sh`、`scripts/verify.sh` | features 構造、Hook 配置、`components/ui` の既存ファイル変更、`src/api` への未追跡ファイル追加、Hook と lib のテストファイル存在 |
| hook | `.kiro/agents/default.json`、`.kiro/hooks/*.sh`、`frontend/.vite-hooks/pre-commit`（`vp staged`） | agentSpawn で構造を注入、preToolUse で書込みを拒否、stop で `verify.sh --fix`、postToolUse で Orval 再生成を促す、pre-commit で全検査 |

旧リポジトリに frontend の CI は無い（`.github/workflows/backend-ci.yml` だけ）。
強制の最終点は pre-commit である。

feature 間の import を禁じる rule は無い。
`import/no-cycle` はファイル単位の循環しか見ないため、feature 単位の循環は通る。
実際に次の feature 間 import が生じている（確認済み）。

| import 元 | import 先 |
| --- | --- |
| `features/category/components/category-product-list.tsx` | `features/product/hooks/use-product-list`、`features/product/types/product-status` |
| `features/product/components/product-filter.tsx`、`product-form.tsx`、`hooks/use-category-name-resolver.ts` | `features/category/hooks/use-category-options` |
| `features/product/components/product-pricing-list.tsx` | `features/pricing/hooks/use-pricing-list`、`features/pricing/types/pricing-level` |
| `features/pricing/hooks/use-product-options.ts` | `features/product/hooks/use-product-list` |
| `features/dashboard/hooks/use-recent-products.ts`、`use-recent-pricings.ts`、`components/recent-pricings-list.tsx` | product と pricing の hooks、types |

category と product、product と pricing は feature 単位で相互参照している。

## Q-c 観点別の差分

凡例は次のとおりである。

- **旧のみ**：旧にあって現に無い。
- **現のみ**：現にあって旧に無い。
- **方針差**：両方にあるが方針が違う。

| 観点 | 旧のみ | 現のみ | 方針差 |
| --- | --- | --- | --- |
| ディレクトリ構成と依存境界 | `components/layout/`、`src/types/`、`src/hooks/`、`features/<f>/types/`（`check-features-structure.sh`） | `api/generated/` と手書き mutator の分離、shared 導入条件、依存方向図（`architecture.md`） | 旧は feature 直下にファイル禁止、現は `<Feature>Page.tsx`、`queries.ts`、`schemas.ts` を直下に置く。どちらも feature 間 import の機械的禁止は無い |
| ファイル命名 | kebab-case の lint 強制と根拠（ADR-0012、`unicorn/filename-case`） | なし | 現 `component-design.md` は feature component を PascalCase（`OrderListItem.tsx`）、ADR-032 も `<Feature>Page.tsx` |
| ルーティング | 非ネスト route の命名表と `<Outlet />` の落とし穴（`frontend-dev-environment.md` の「TanStack Router ルート命名規則」） | URL 設計（`url-design.md`）、`defaultPreload: "intent"`、`scrollRestoration`（`src/main.tsx`） | 旧は loader を使わず component の Hook で取得。現は loader から preload（`routing-and-state.md`） |
| 状態管理とデータ取得 | queryKey の明示上書き、mutation 後の invalidate と navigate、楽観ロック 409 の画面処理（`frontend-data-patterns.md`） | state 種別ごとの置き場所、URL state、wrapper Hook 禁止（`routing-and-state.md`）、cache 方針（`browser-storage-and-cache.md`） | 旧は全 Orval Hook を feature の Hook で包む。現は生成物を直接使い、包むだけの Hook を禁止。旧は filter を `useState`、現は search parameter |
| API client 生成 | `orval.config.ts`、`openapi.json`、生成物 `src/api/*`、CSRF 付き mutator `src/lib/api-client.ts`、`ApiError`（`src/lib/api-error.ts`）、ADR-0008 | Spectral 検査済み snapshot を入力にする方針、tag と operationId の所有規則、生成物の Git 管理を後で決める方針（ADR-024、`api-client-orval.md`） | 旧は生成先 `src/api/`、現は `src/api/generated/`。旧は mutator 必須、現は必要時のみ |
| UI component とスタイル | `ErrorMessage`、`EmptyState`、`ConfirmDialog`、layout、Loading→Error→Empty→Content の順、Skeleton、Dialog の Title 必須、Link と Button の組合せ（`frontend-ui-patterns.md`） | Base UI、semantic token、`shadcn/*` lint、CSP と inline style 許可（ADR-025、ADR-030、`vite.config.ts`） | 旧は Radix と `asChild`、`components/ui` 編集禁止。現は Base UI、`components/ui` は app code として編集可 |
| フォームとバリデーション | なし | TanStack Form と Zod、インライン検証の時機、Orval 生成 Zod の利用（`form-validation.md`、ADR-023） | 旧はフォームライブラリ不使用、HTML 属性で検証。disabled にしない規則は両方にある |
| i18n | なし | i18next、型付き catalog、`react/jsx-no-literals` 有効（`i18n.md`、ADR-016） | 旧は日本語文言を JSX に直書き（`react/jsx-no-literals: off`） |
| エラー処理 | `ApiError extends Error`（status、title、detail）、`isApiError`、409 の分岐 | Problem Details の文言はバックエンドが解決したものとして扱う（`i18n.md`） | なし |
| 認証と認可 UI | `/api/v1/me` を必要時に作るという方針メモのみ | 認可は親 route の `beforeLoad`、権限は API から取得（`authorization-ui.md`） | なし |
| テスト | Hook と lib のテスト必須（`check-test-exists.sh`）、`act()` で mutate を包む、jest-dom なしの書き方 | Testing Trophy、MSW、coverage 85%（`testing.md`、`test-strategy.md`、ADR-027）、実 router を使う route test（`src/routes/-index.test.tsx`） | 旧は `vi.mock("@/lib/api-client")` と Hook の `vi.mock`、MSW を lint で禁止。現は MSW で HTTP 境界を置換し module mock を避ける |
| Lint、format、型、knip | 自作 rule 5 個、`better-tailwindcss/enforce-shorthand-classes`、`no-void` の `allowAsStatement`、`radix`、`no-new-wrappers`、`typescript/consistent-type-definitions` など（ADR-0007、ADR-0010） | Knip、jscpd、React Doctor、`denyWarnings`、`reportUnusedDisableDirectives`、`local-security` rule、HTML 系 API 禁止、厳格 tsconfig（ADR-031、ADR-034、ADR-035） | 全カテゴリ error と個別 off の方針は同じ。旧は `import/no-default-export` を error、現は off |
| ビルド、開発サーバ、proxy | なし | 開発 origin 固定、`SERVER_PORT` 検証、複数 path の proxy、CSP header、`vite-config.test.ts`（ADR-033） | 旧は `/api` だけ、`changeOrigin: true`、port 18080 固定。React Compiler は旧が babel、現が OXC（ADR-026） |
| Git hook と CI | `vp staged` による pre-commit | lefthook の pre-commit `fe-check` と pre-push `fe-test-build`、`frontend-ci.yml`（`fe-verify` と `fe-doctor`） | なし |
| エージェント向けガイド | Kiro CLI hook 4 本、`frontend-feature` skill、lint 修正ガイド、調査フロー、API 不在時に止める規則、Context7 の使い方 | docs と steering の分離（ADR-038）、Matt Pocock skill 群（ADR-039）、Future ガイドライン由来の規約（ADR-040） | 旧は steering に規約本文を持つ。現は steering を案内だけにしている |
| その他 | `@ParameterObject` 必須と operationId 命名表（旧 `rest-api-standards.md` の差分）、vite-plus の更新手順、`routeTree.gen.ts` の fmt 不具合メモ | ダークモード、レスポンシブ、オフライン、配信、対応ブラウザの規約 | なし |

## Q-d 取り込み候補

推奨度は、取り込む価値と、最初の業務 feature を作る前に決めないと手戻りが出る度合いで付けた。

### 高

#### C1. feature 境界の import 制約を lint にする

- **内容**：`src/features/<a>/**` から `@/features/<b>/**` への import を禁止し、`api/generated`、`components/ui`、`lib`、`i18n` から `@/features/**` への import を禁止する。
- **根拠**：旧の feature 間 import と feature 単位の循環（Q-b の表）。旧は file 単位の `import/no-cycle` しか持たず、これを防げなかった。
- **衝突**：無い。`architecture.md` は「実在する違反例を基に lint rule を追加する」と定め、ADR-032 の Negative も lint の追加判断を予告している。旧の事例がその違反例に当たる。
- **影響範囲**：`frontend/vite.config.ts` の `lint.overrides`。共有層側の禁止は `no-restricted-imports` の静的な override で書ける。「自 feature 以外」の判定は静的設定では書きにくいので、`frontend/lint/local-security.js` と同じ形の小さな jsPlugin rule と、そのテスト（`lint/local-security.test.js` と同じ形式）が要る（推測）。ADR-032 は Proposed なので、改訂するか新 ADR を起こすかを `docs/adr/conventions.md` に従って決める。
- **理由**：AI 実装で再現性の高い違反であり、feature が増えた後に解くほど手戻りが大きい。

#### C2. CSRF と Problem Details を扱う custom mutator を最初の更新系 API で入れる

- **内容**：Orval の `override.mutator` に手書きの fetch wrapper を指定する。`XSRF-TOKEN` cookie を読んで `X-XSRF-TOKEN` header に載せ、非 2xx の Problem Details を `ApiError`（`Error` を継承し `status`、`title`、`detail` を持つ）に変換する。
- **根拠**：旧 `frontend/src/lib/api-client.ts`、`frontend/src/lib/api-error.ts`、`api-error.test.ts`。現 `backend/src/main/java/com/example/demo/SecurityConfig.java` の 99 から 100 行目が `csrf.spa()` を使い、SPA が header で送り返す前提をコメントで述べている。ADR-014 も `X-XSRF-TOKEN` を許可している。
- **衝突**：`api-client-orval.md` の「組み込み Fetch だけで表現できないと確認した場合に限り」に該当するかを確認する必要がある。Orval の native fetch だけでは要求ごとに cookie を読んで header を付けられないと考えている（推測、要確認）。配置は旧の `src/lib/` ではなく、現の規約どおり `src/api/<custom-mutator>.ts` にする。
- **影響範囲**：`frontend/orval.config.ts`（新規）、`src/api/<mutator>.ts` とテスト（MSW）、coverage。Orval 8.36 の fetch client が mutator に期待する戻り値（旧は `{ data, status, headers }`）は版で変わり得るため、実装時に生成コードで確かめる。
- **理由**：更新系 API を一本でも呼ぶと 403 になるため、最初の業務 API の時点で避けられない。

#### C3. mutation 後の cache 無効化は生成された query key を使う

- **内容**：mutation の `onSuccess` で無効化する key は、Orval が生成する query key 関数から作る。配列の key を手書きしない。
- **根拠**：旧 `frontend-data-patterns.md` の「queryKey の明示上書き（必須）」。Orval の既定 key は URL ベースであり、手書きの `["categories"]` で無効化すると一覧が更新されないまま失敗に気づかない、という実害を記録している。
- **衝突**：旧の解決策（全 Hook で key を手書きに上書き）は、生成物を正本にする現 `api-client-orval.md` と、包むだけの Hook を禁じる `routing-and-state.md` に反する。取り込むのは「key をずらさない」という教訓だけにし、現の方針に合わせて「生成 key を正本にする」と書く。
- **影響範囲**：`docs/frontend/routing-and-state.md` または `api-client-orval.md` に数行。最初の mutation の component test（MSW）で、更新後に一覧が再取得されることを確かめる。
- **理由**：テストを書かないと見逃す silent な不具合で、規約一行で防げる。

#### C4. operationId の命名パターンと query parameter object の展開

- **内容**：`@Operation(operationId = ...)` を明示し、`create<Resource>`、`list<Resource>`、`find<Resource>ById`、`<動詞><Resource>` のように命名する。POJO の query parameter には `@ParameterObject` を付ける。
- **根拠**：旧 `rest-api-standards.md` の差分。SpringDoc は既定で Java の method 名を operationId にし、重複時に `findById_1` の連番を付ける。Orval はそれを Hook 名にする。`@ParameterObject` が無いと object 全体が一つの query parameter として出力され、生成 client が壊れた query string を作る。backend 単体テストでは見つからない。
- **衝突**：無い。現 `.spectral.yaml` は `operation-operationId` で存在だけを検査し、命名は検査しない。`docs/web-api/` と `docs/backend/` に operationId の命名規則は無い（grep で確認済み）。現 `docs/web-api/query-parameters.md` は `limit` と `cursor` を共通語彙にしており、旧の `Pageable` の例はそのまま当てはまらない。
- **影響範囲**：`docs/web-api/`（命名規則）、必要なら `.spectral.yaml` に pattern rule、backend の雛形。frontend 側の変更は無い。
- **理由**：operationId は ADR-032 が「安定した契約」とする値であり、後から変えると生成 Hook 名が変わって frontend 全体に波及する。

#### C5. ファイル名の表記を最初の feature の前に決める

- **内容**：feature 内の component file を kebab-case（旧）にするか PascalCase（現 `component-design.md`）にするかを決め、lint 設定と docs を一致させる。
- **根拠**：旧 ADR-0012 と `unicorn/filename-case: kebabCase`。現 `component-design.md` は `OrderListItem.tsx`、ADR-032 と `architecture.md` は `<Feature>Page.tsx` とする。一方で現 `vite.config.ts` は `style` カテゴリを error にしている。`unicorn/filename-case` は `style` カテゴリに属し、既定は kebab-case である（[Oxlint rules](https://oxc.rs/docs/guide/usage/linter/rules.html)、[unicorn/filename-case](https://oxc.rs/docs/guide/usage/linter/rules/unicorn/filename-case.html)）。カテゴリ指定でこの rule が有効になっていれば、現 docs のとおり PascalCase のファイルを作ると `task fe-check` が失敗する（要検証）。
- **衝突**：現 docs 同士、または docs と lint 設定が衝突している可能性がある。
- **影響範囲**：`docs/frontend/component-design.md`、`architecture.md`、`runbook-add-feature.md`、`vite.config.ts`（PascalCase を採るなら `cases` の設定）。
- **理由**：最初の feature を作った後に変えるとファイル名の一括変更になる。

### 中

#### C6. TanStack Router の非ネスト route の命名と `<Outlet />` の落とし穴

- **内容**：一覧、詳細、編集が別画面なら `products_.$id.tsx`、`products_.$id_.edit.tsx` のように `_` で親から外す。`_` を付けずに作ると、親に `<Outlet />` が無い場合に子の内容が表示されない。
- **根拠**：旧 `frontend-dev-environment.md` の「TanStack Router ルート命名規則」と「ネスト vs 独立の判断基準」。旧 `src/routes/` の実装がこの形になっている。
- **衝突**：無い。現 `url-design.md` は URL の形（`/orders/{orderId}/edit`）を決めているが、route file の命名は決めていない。
- **影響範囲**：`docs/frontend/routing-and-state.md` の「ルーティング」節に短い表を足す。

#### C7. 楽観ロック 409 の画面側の扱い

- **内容**：更新と削除で取得済みの version を送り、409 のときは再取得を促す表示を出す。削除時の 409（関連データあり）はバックエンドが返す文言を表示する。
- **根拠**：旧 `frontend-data-patterns.md` の「楽観ロック（version）対応」。現 `docs/web-api/optimistic-locking.md` は API 側を決めているが、`docs/frontend/` には 409 や競合の記述が無い（grep で確認済み）。
- **衝突**：旧の「一覧に version が無いので一覧に削除ボタンを置かない」は、一覧の応答にも version を含める現 `optimistic-locking.md` と前提が違う。この部分は取り込まない。文言の扱いは現 `i18n.md` の Problem Details の規則に従う。
- **影響範囲**：`docs/frontend/form-validation.md` か `routing-and-state.md` に節を追加。入力内容を保持するかどうかは Q-e で決める。

#### C8. 画面の状態表示の規約

- **内容**：Loading、Error、Empty、Content の順で early return する。Loading は Skeleton を使う。Error は再試行操作を持つ共通 component にする。Dialog には必ず Title を付ける。余白は `space-*` ではなく `flex` と `gap-*` にする。
- **根拠**：旧 `frontend-ui-patterns.md`、`src/components/error-message.tsx`、`empty-state.tsx`、`confirm-dialog.tsx`。現 `testing.md` は「loading、success、empty、error、再試行のうち、その画面が提供する状態を検証する」と検査する状態は決めているが、表示の形は決めていない。
- **衝突**：共通 component の置き場所は現 `ui-and-style.md`（`components/ui` は primitive だけ）と `architecture.md`（二つ以上の feature で確認できてから共有）に従う必要がある。旧の `components/` 直下はそのまま採らない（Q-e の 4）。shadcn の registry に同等の component があればそれを `components/ui` に追加する方が規約に合う（推測、registry の現状は未確認）。旧の色の直書き（`bg-emerald-100`）は現 `shadcn/no-raw-colors` に反する。
- **影響範囲**：`docs/frontend/ui-and-style.md` に節を追加。現 `src/routes/index.tsx` は `space-y-3` を使っており、規約にするなら直す対象になる。

#### C9. 生成物への書込みを事前に拒否する agent hook

- **内容**：Kiro の PreToolUse hook で、`frontend/src/api/generated/**` と `frontend/src/routeTree.gen.ts` への書込みを拒否し、再生成の手順を返す。
- **根拠**：旧 `.kiro/hooks/frontend-write-guard.sh`。現 `.kiro/hooks/block-iwe-normalize.json` が同じ PreToolUse の形式を使っており、置き場所と書式の前例がある。
- **衝突**：旧は `components/ui` も拒否しているが、現 ADR-025 は `components/ui` を app code として管理するので対象から外す。旧の `api-readonly.sh`（未追跡ファイルの検出）は採らず、生成差分の検査は ADR-024 のとおり「再生成して `git diff --exit-code`」を CI に置く方が確実である。
- **影響範囲**：`.kiro/hooks/` に json と sh を一組、そのテスト一本。`docs/agents/` に記述。
- **理由**：steering の「生成ファイルは直接編集しない」を、書く前に機械で止められる。

#### C10. 機能追加の runbook に止まる条件を足す

- **内容**：次の三つを `runbook-add-feature.md` に足す。
  - 必要な API が OpenAPI に無いときは、手書きの fetch や仮実装を作らず止めて確認する。
  - backend の変更が要るときは、変更内容を示して確認を取ってから進める。
  - 階層移動や公開などのドメイン操作 API があるときは、範囲を確認してから実装する。
- **根拠**：旧 `frontend-data-patterns.md` の「API が存在しない・生成できない場合」、`frontend-dev-environment.md` の「新規 feature 作成時の調査フロー」と「複雑な関連リソースAPIのスコープ判断」、`.kiro/skills/frontend-feature/SKILL.md` の「API 不足・修正時の対応」。旧は sample 実装の分析を経てこれらを追加している（commit `f71c44f`、`47b90a8` など）。
- **衝突**：無い。旧の skill そのものは、規約を docs に置く ADR-038 と skill 群を定める ADR-039 に合わないので取り込まず、runbook の手順に溶かす。
- **影響範囲**：`docs/frontend/runbook-add-feature.md` に数行。

#### C11. floating promise の書き方を決める（要検証）

- **内容**：`navigate()`、`invalidateQueries()`、`refetch()` のように Promise を返す呼出しを event handler で使うときの書き方を決める。
- **根拠**：旧 ADR-0010 は、`restriction` カテゴリを error にすると `no-void` が `allowAsStatement: false` で有効になり、`void navigate()` が違反になったと記録している。旧はそれを `no-void: ["error", { allowAsStatement: true }]` で解き、JSX 内では `() => { void refetch(); }` の形にしている。現 `vite.config.ts` は同じカテゴリを error にし、`typeAware` も有効なので、`typescript/no-floating-promises` と `no-void` の組合せで同じ問題が起きる見込みが高い（推測）。
- **衝突**：ADR-034 の「個別規則の調整」の範囲に収まるかを確認する。
- **影響範囲**：`vite.config.ts` の rule 一行と理由のコメント、必要なら ADR-034 の例外の一覧。

### 低

#### C12. `exactOptionalPropertyTypes` と生成型の組合せの書き方

- **内容**：optional な値を生成 request 型や JSX props に渡すときは、条件付き spread（`...(x !== undefined && { x })`）で一箇所にまとめ、JSX を if で複製しない。
- **根拠**：旧 `frontend-lint-fix-guide.md` の「exactOptionalPropertyTypes」。現 `tsconfig.json` も ADR-031 で同じ option を有効にしている。
- **影響範囲**：lint 修正の手引きを docs に置くかどうかの判断が先に要る。置くなら `docs/frontend/` か `docs/tooling/` に一文書。

#### C13. Link を Button の見た目で描画する方法

- **内容**：`<Link>` の中に `<Button>` を入れない。Base UI では `render` prop で描画要素を差し替える。
- **根拠**：旧 `frontend-ui-patterns.md` の「Link と Button の組み合わせ」、`project-rules/no-button-inside-link`。
- **衝突**：旧の `asChild` は Radix 版の API であり、現 ADR-025 の Base UI 版では使えない。Base UI での正しい書き方（`render` と `nativeButton` の指定）は実装時に Base UI の文書で確認する（推測）。
- **影響範囲**：`docs/frontend/ui-and-style.md` に一項目。lint rule にするのは違反が出てからでよい。

#### C14. 追加の lint rule

- **内容**：`better-tailwindcss/enforce-shorthand-classes`（`w-10 h-10` を `size-10` に自動修正）、`no-restricted-imports` による `../` の禁止、`radix`、`no-new-wrappers`、`typescript/consistent-type-definitions`。
- **根拠**：旧 `frontend/vite.config.ts`、ADR-0010。
- **衝突**：無い。ただし、これらのうちどれがカテゴリ指定ですでに有効かは、実行して確かめていない（要検証）。
- **影響範囲**：`vite.config.ts` に数行。

#### C15. enum の表示 mapping と区分値の管理基準

- **内容**：backend の enum ごとに `Record<Enum, ...>` で表示を網羅し、enum の追加漏れを型で検出する。区分値は「デプロイ無しで値を変える必要があるか」で、定数か参照 API かを選ぶ。
- **根拠**：旧 `frontend-code-patterns.md` の「ステータスラベル・カラー定義」と「区分値の管理方式」、`src/features/product/types/product-status.ts`。
- **衝突**：旧は日本語ラベルと生の色を直書きしている。現では label を i18n の key、色を Badge の variant などの semantic token に置き換える必要がある（`i18n.md`、`shadcn/no-raw-colors`）。
- **影響範囲**：`docs/frontend/component-design.md` か `i18n.md` に数行。

#### C16. 1 ファイルの import 数を見越した component 分割

- **内容**：詳細画面のように依存が多い画面は、header、状態表示、本体、skeleton の単位で最初から分ける。
- **根拠**：旧 `frontend-dev-environment.md` の「import(max-dependencies) 超過を避ける事前分割」。`import/max-dependencies`（既定 10）は `pedantic` カテゴリに属し、現 `vite.config.ts` は `pedantic` を error にして off にしていない（rule の所属は Oxlint の rule 一覧で確認、現で有効かは要検証）。
- **影響範囲**：`component-design.md` に一項目。lint が教えてくれるので書かなくても困りは小さい。

#### C17. その他の agent hook

- **内容**：agentSpawn で `features/`、`routes/`、`api/` の一覧を文脈に注入する。stop で検査を走らせ、失敗を通知する。
- **根拠**：旧 `.kiro/hooks/frontend-context.sh`、`frontend-lint-check.sh`。
- **衝突**：無いが、現は lefthook の pre-commit と pre-push、CI で同じ検査を担っている。stop で毎回 `fe-check` を走らせると応答ごとの待ち時間が増える。
- **影響範囲**：`.kiro/hooks/`。

### 取り込まないもの

| 旧の内容 | 根拠 | 除外理由 |
| --- | --- | --- |
| `components/ui` の編集禁止と `check-ui-readonly.sh` | 旧 `scripts/checks/check-ui-readonly.sh`、`frontend-write-guard.sh` | ADR-025 は `components/ui` を app code として管理し、現 `vite.config.ts` も `src/components/ui/**` に専用 override を持つ |
| feature 直下にファイル禁止、`components`、`hooks`、`types` のみ | 旧 `check-features-structure.sh` | ADR-032 と `architecture.md` は `<Feature>Page.tsx`、`queries.ts`、`schemas.ts` を feature 直下に置く |
| 全 Orval Hook を feature の Hook で包む、ロジックは必ず Hook に置く | 旧 `frontend-code-patterns.md` の「Hooks パターン」、`frontend-data-patterns.md` | `routing-and-state.md` は生成 Hook をそのまま返す wrapper Hook を禁じ、custom Hook を具体的な再利用がある場合に限る |
| Hook と lib ごとのテストファイル必須 | 旧 `check-test-exists.sh` | `test-strategy.md` は Hook を component test 経由で検証すると定め、現は全体の branch coverage 85% で担保している |
| `vi.mock("@/lib/api-client")` と MSW の lint 禁止 | 旧 `frontend-test-patterns.md`、`vite.config.ts` の test override | ADR-027 と `testing.md` は MSW で HTTP 境界を置き換える。旧が記録した「Orval 生成 module の mock は内部参照のため効かない」という事実は、現の MSW 方針を裏付ける |
| `Link` を `vi.mock` で `<a>` に差し替える | 旧 `frontend-test-patterns.md` | 現 `src/routes/-index.test.tsx` は memory history の実 router で検証しており、こちらが利用者視点の検証に近い |
| フォームライブラリを使わない | 旧 `frontend-ui-patterns.md` の「フォーム」 | ADR-023 が TanStack Form と Zod を採用している |
| `<input type="date">` を `T00:00:00Z` の Instant に変換 | 旧 `frontend-ui-patterns.md` の「Instant 型フィールド」 | `docs/datetime/timezone-conventions.md` の 66 行目は日付だけの値を UTC へ変換しないと定める |
| filter を `useState` に置く | 旧 `src/features/product/hooks/use-product-list-page.ts` | `url-design.md` と `routing-and-state.md` は filter と page を search parameter に置く。旧自身の steering とも食い違っている |
| loader を使わず component から取得 | 旧 `src/routes/*.tsx` | `routing-and-state.md` は初期描画の server state を loader から preload する |
| Radix と `asChild` | 旧 `package.json` の `radix-ui` | ADR-025 が Base UI 版を採用している |
| babel 経由の React Compiler | 旧 `vite.config.ts` | ADR-026 が OXC で有効化している |
| `/api` だけの proxy、`changeOrigin: true` | 旧 `vite.config.ts` | ADR-033 と ADR-014 に基づく現の設定（固定 origin、複数 path、`changeOrigin: false`、CSP）が上位互換である |
| 日本語文言の JSX 直書き、色の直書き | 旧 `src/features/*/types/*.ts` | `i18n.md` と `react/jsx-no-literals`、`shadcn/no-raw-colors` に反する |
| `api-readonly.sh`（未追跡ファイルの検出） | 旧 `scripts/checks/api-readonly.sh` | 再生成して差分を見る方が生成物の改変を確実に検出できる（ADR-024 の未決事項として扱う） |
| `QueryClient` の既定 `staleTime: 60s`、`retry: 1` | 旧 `src/lib/query-client.ts` | `browser-storage-and-cache.md` は `staleTime` を既定値のまま使う |
| default export の lint 禁止 | 旧 `vite.config.ts` | 現は `import/no-default-export` を off にしており、得るものが小さい |
| `frontend-feature` skill | 旧 `.kiro/skills/frontend-feature/SKILL.md` | 規約の正文を docs に置く ADR-038 と合わない。中身は C10 として runbook に取り込む |
| JSDoc を書かない方針（ADR-0011） | 旧 `docs/adr/0011-*.md` | 旧のコード自体が全 export に JSDoc を書いており、方針が実装で守られていない。現で決める価値は小さい |
| 品質チェックの実行時機の表 | 旧 `frontend/README.md` | 現 `docs/tooling/lint-and-test.md` が Git hook と CI の対応をすでに持つ |
| vite-plus の `fmt.ignorePath` 不具合の回避 | 旧 `frontend-dev-environment.md` | 現は `fmt.ignorePatterns` を使っており、同じ経路を通らない（推測） |

### 現リポジトリの方が優れている点

- 依存方向と共有の条件を、空ディレクトリを作らずに文書で定めている（`architecture.md`）。旧は構成を決めたが依存方向を決めておらず、結果として feature 間の循環を許した。
- 生成物（`api/generated`）と手書きの mutator、MSW の lifecycle を分け、Orval の `clean` で手書きコードが消えない（`api-client-orval.md`）。
- loader preload、URL state、TanStack Form、TanStack Query の役割分担を state の種類で決めている（`routing-and-state.md`）。
- テストは MSW と実 router で利用者視点に寄せ、coverage を全体で強制している（ADR-027）。
- i18n、CSP、HTML 注入系 API の lint 禁止、開発 origin の固定、厳格な tsconfig、Knip、jscpd、React Doctor、frontend CI を持つ。旧はどれも持たない。
- 規約の正文を docs に置き、steering を案内に限っているので、規約の重複と食い違いが起きにくい（ADR-038）。旧は steering とコードと ADR の間で食い違いがある（filter の置き場所、JSDoc）。

## Q-e 人間が決める論点

1. **ファイル名の表記**：kebab-case（旧）か PascalCase（現 `component-design.md`）か。決める前に、現の lint 設定で `unicorn/filename-case` が有効かを `task fe-check` で確かめる（C5）。
2. **feature 境界の lint を入れる時期と手段**：最初の feature と同時か、二つ目の feature で違反が出てからか。手段は `no-restricted-imports` の override と自作 jsPlugin の組合せでよいか（C1）。ADR-032 を改訂するか新 ADR にするか。
3. **feature 間で能力を共有する形**：旧で起きた「product が category の選択肢を使う」「dashboard が product と pricing の一覧を使う」場面を、route での合成、所有 feature の公開境界（公開ファイルを一つ決める）、生成 query options の直接利用のどれで解くか。ADR-032 は方向だけを示し、公開境界の具体形を決めていない。
4. **アプリシェルと feature 非依存の複合 component の置き場所**：header、sidebar、layout、ErrorMessage、EmptyState、ConfirmDialog を `components/ui` に入れるか、別のディレクトリ（`components/app` や `app/` など）を作るか（C8）。
5. **custom mutator の導入時期と形**：最初の更新系 API で入れるか、ファイル名と置き場所、`ApiError` が持つ項目、Problem Details の文言をどこまで画面に出すか（C2、`i18n.md` の Problem Details 規則との整合）。
6. **Orval 生成物と OpenAPI snapshot の Git 管理と差分検査**：ADR-024 が最初の業務 API まで先送りしている事項であり、C9 の hook と組で決める。
7. **operationId の命名規則の置き場所と強制**：`docs/web-api/` に置くか、Spectral の pattern rule まで入れるか（C4）。
8. **楽観ロック 409 の UX**：自動で再取得するか、利用者に再読込を促すか、入力中の内容を保持するか（C7）。
9. **agent hook の範囲**：PreToolUse の書込み拒否だけにするか、stop での検査や agentSpawn での構造注入まで入れるか。応答ごとの待ち時間と、lefthook と CI で足りる部分との兼ね合いで決める（C9、C17）。
10. **floating promise の書き方**：`void` 文を許すか、async handler で `await` するか、`.catch` を必須にするか（C11）。
11. **lint 修正の手引きを docs に置くか**：置くなら C12 と C16 の受け皿になる。

## 推奨する進め方

最初の業務 feature を作る前に、Q-e の 1、2、4、7 を決めるとよい。
どれも、feature を作った後に変えるとファイル名の変更、import の付け替え、生成 Hook 名の変更として全体に波及する。

次に、最初の業務 API を追加する変更で C2、C3、C9 と Q-e の 5、6 を一度に片付けるとよい。
ADR-024 と ADR-032 がもともとこの時点で決めると定めている事項と重なるためである。

C6、C7、C8、C10 は docs への数行から数節の追加で済み、最初の feature の実装中に必要になった時点で足せばよい。
